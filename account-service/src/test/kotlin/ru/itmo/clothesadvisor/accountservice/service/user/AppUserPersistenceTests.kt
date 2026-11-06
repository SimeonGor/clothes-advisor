package ru.itmo.clothesadvisor.accountservice.service.user

import jakarta.validation.ConstraintViolationException
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.util.concurrent.atomic.AtomicInteger
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.Test
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mockingDetails
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import org.springframework.transaction.ReactiveTransactionManager
import org.springframework.transaction.reactive.TransactionalOperator
import reactor.core.publisher.Mono
import reactor.core.publisher.Sinks
import reactor.test.StepVerifier
import ru.itmo.clothesadvisor.accountservice.config.PostgresIntegrationTest
import ru.itmo.clothesadvisor.accountservice.model.EntityNotFoundException
import ru.itmo.clothesadvisor.accountservice.model.user.AppUser
import ru.itmo.clothesadvisor.accountservice.model.user.AppUserHistory
import ru.itmo.clothesadvisor.accountservice.model.user.AppUserHistoryId
import ru.itmo.clothesadvisor.accountservice.model.user.UserRole
import ru.itmo.clothesadvisor.accountservice.model.user.UserStatus
import ru.itmo.clothesadvisor.accountservice.repository.user.AppUserHistoryRepository
import ru.itmo.clothesadvisor.accountservice.repository.user.AppUserRepository

@SpringBootTest
class AppUserPersistenceTests : PostgresIntegrationTest() {
    @Autowired private lateinit var users: AppUserService

    @Autowired private lateinit var historyRepository: AppUserHistoryRepository

    @MockitoSpyBean private lateinit var repository: AppUserRepository

    @Autowired private lateinit var transactionManager: ReactiveTransactionManager

    @Autowired private lateinit var databaseClient: DatabaseClient

    @Test
    fun `creation and reads retain database values and case sensitive login`() {

        // given
        val created = createUser()

        // when
        val loaded = users.findById(created.id).block(timeout)!!

        // then
        assertThat(loaded.id).isPositive()
        assertThat(loaded.login).isEqualTo("alice")
        assertThat(loaded.version).isEqualTo(1)
        assertThat(loaded.createdAt).isEqualTo(loaded.modifiedAt)
        assertThat(loaded.createdAt).isEqualTo(created.createdAt)
        assertThat(loaded.createdAt).isEqualTo(
            jdbc.queryForObject(
                "SELECT created_at FROM app_user WHERE id = ?",
                Timestamp::class.java,
                loaded.id,
            )!!.toInstant(),
        )
        assertThat(loaded.passwordHash).isEqualTo("test-password-hash")
        assertThat(loaded.toString()).doesNotContain("test-password-hash")
        StepVerifier.create(users.findByLogin("alice"))
            .assertNext { assertThat(it.id).isEqualTo(created.id) }
            .verifyComplete()
        StepVerifier.create(users.findByLogin("missing")).verifyComplete()
        StepVerifier.create(users.findById(Long.MAX_VALUE)).verifyComplete()
        assertThat(createUser("Alice").id).isNotEqualTo(created.id)
        assertThat(history()).isEmpty()
    }

    @Test
    fun `all enum values round trip through current and historical rows`() {
        for (role in UserRole.entries) {
            for (status in UserStatus.entries) {

                // given
                val created = users.create("$role-$status", "hash", role, status)
                    .block(timeout)!!
                val nextRole = UserRole.entries[(role.ordinal + 1) % UserRole.entries.size]
                val nextStatus = UserStatus.entries.single { it != status }

                // when
                val loaded = users.findById(created.id).block(timeout)!!
                val changed = users.changeRoleAndStatus(created.id, 1, nextRole, nextStatus)
                    .block(timeout)!!

                // then
                assertThat(loaded.role).isEqualTo(role)
                assertThat(loaded.status).isEqualTo(status)
                assertThat(changed.role).isEqualTo(nextRole)
                assertThat(changed.status).isEqualTo(nextStatus)
                val archived = history().single { it.userId == created.id }
                assertThat(archived.snapshot.role).isEqualTo(role.name)
                assertThat(archived.snapshot.status).isEqualTo(status.name)
                assertThat(archived.snapshot.modifiedAt).isEqualTo(created.modifiedAt)
            }
        }
    }

    @Test
    fun `history returning maps composite identity and original instant`() {

        // given
        val created = createUser()
        val snapshot = AppUserHistory(created)

        // when
        val result = historyRepository.insert(snapshot)

        // then
        StepVerifier.create(result).assertNext { saved ->
            assertThat(saved.id).isEqualTo(AppUserHistoryId(created.id, 1))
            assertThat(saved.login).isEqualTo(created.login)
            assertThat(saved.role).isEqualTo(created.role)
            assertThat(saved.status).isEqualTo(created.status)
            assertThat(saved.modifiedAt).isEqualTo(created.modifiedAt)
        }.verifyComplete()
        assertThat(history()).hasSize(1)
    }

    @Test
    fun `duplicate login and invalid credentials fail without additional rows`() {

        // given
        createUser()
        val invalid = listOf(
            "" to "hash", " \t\n" to "hash", "another" to " ",
            "another" to "", "another" to " \t\n",
        )

        // when
        val duplicate = users.create("alice", "hash", UserRole.USER)

        // then
        StepVerifier.create(duplicate).expectError(DataIntegrityViolationException::class.java)
            .verify()
        for ((login, hash) in invalid) {

            // when
            val result = users.create(login, hash, UserRole.USER)

            // then
            StepVerifier.create(result).expectError(ConstraintViolationException::class.java)
                .verify()
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM app_user", Long::class.java))
            .isEqualTo(1)
    }

    @Test
    fun `login and version constraints are preserved in current and historical rows`() {

        // given
        val created = createUser("x".repeat(100))
        historyRepository.insert(AppUserHistory(created)).block(timeout)

        // when
        val oversized = users.create("x".repeat(101), "hash", UserRole.USER)

        // then
        StepVerifier.create(oversized).expectError(ConstraintViolationException::class.java).verify()
        for (table in listOf("app_user", "app_user_history")) {
            for (invalid in listOf("x".repeat(101), " \t\n")) {

                // when
                val invalidLogin = catchThrowable {
                    jdbc.update("UPDATE $table SET login = ?", invalid)
                }

                // then
                assertThat(invalidLogin).isInstanceOf(DataIntegrityViolationException::class.java)
            }

            // when
            val invalidVersion = catchThrowable { jdbc.update("UPDATE $table SET version = 0") }

            // then
            assertThat(invalidVersion).isInstanceOf(DataIntegrityViolationException::class.java)
        }
        assertThat(current(created.id).login).isEqualTo(created.login)
        assertThat(history().single().snapshot.login).isEqualTo(created.login)
    }

    @Test
    fun `each change archives exact previous values with database transaction time`() {

        // given
        val created = createUser()
        val previousTime = Instant.parse("2026-01-01T01:02:03Z")
        jdbc.update(
            "UPDATE app_user SET created_at = ?, modified_at = ? WHERE id = ?",
            Timestamp.from(previousTime), Timestamp.from(previousTime), created.id,
        )
        val before = current(created.id)

        // when
        val changed = users.changeRoleAndStatus(created.id, 1, UserRole.STYLIST, UserStatus.BLOCKED)
            .block(timeout)!!

        // then
        assertThat(changed.version).isEqualTo(2)
        assertThat(changed.role).isEqualTo(UserRole.STYLIST)
        assertThat(changed.status).isEqualTo(UserStatus.BLOCKED)
        assertThat(changed.login).isEqualTo(created.login)
        assertThat(changed.passwordHash).isEqualTo(created.passwordHash)
        assertThat(changed.createdAt).isEqualTo(previousTime)
        assertThat(changed.modifiedAt).isEqualTo(current(created.id).modifiedAt)
        assertThat(history()).containsExactly(Archived(created.id, before, changed.modifiedAt))

        // given
        val intermediate = current(created.id)

        // when
        val next = users.changeRoleAndStatus(created.id, 2, UserRole.ADMIN, UserStatus.BLOCKED)
            .block(timeout)!!

        // then
        assertThat(next.version).isEqualTo(3)
        assertThat(history()).containsExactly(
            Archived(created.id, before, changed.modifiedAt),
            Archived(created.id, intermediate, next.modifiedAt),
        )
    }

    @Test
    fun `database transaction timestamp owns creation modification and archival dates`() {

        // given
        val transactions = TransactionalOperator.create(transactionManager)

        // when
        val (created, changed, transactionTime) = transactions.transactional(
            databaseClient.sql("SELECT CURRENT_TIMESTAMP AS transaction_time")
                .map { row, _ ->
                    row.get("transaction_time", OffsetDateTime::class.java)!!.toInstant()
                }
                .one()
                .flatMap { transactionTime ->
                    users.create("alice", "test-password-hash", UserRole.USER)
                        .flatMap { created ->
                            users.changeRoleAndStatus(
                                created.id, 1, UserRole.STYLIST, UserStatus.ACTIVE,
                            ).map { changed -> Triple(created, changed, transactionTime) }
                        }
                },
        ).block(timeout)!!

        // then
        assertThat(created.createdAt).isEqualTo(transactionTime)
        assertThat(created.modifiedAt).isEqualTo(transactionTime)
        assertThat(changed.createdAt).isEqualTo(created.createdAt)
        assertThat(changed.modifiedAt).isEqualTo(transactionTime)
        assertThat(changed.version).isEqualTo(2)
        assertThat(history()).containsExactly(
            Archived(
                created.id,
                Snapshot(created.login, "USER", "ACTIVE", 1, created.modifiedAt),
                transactionTime,
            ),
        )
    }

    @Test
    fun `no op preserves row and history but stale and missing targets fail`() {

        // given
        val created = createUser()
        val before = current(created.id)

        // when
        val noOp = users.changeRoleAndStatus(created.id, 1, UserRole.USER, UserStatus.ACTIVE)

        // then
        StepVerifier.create(noOp).assertNext { unchanged ->
            assertThat(unchanged.version).isEqualTo(1)
            assertThat(unchanged.modifiedAt).isEqualTo(created.modifiedAt)
        }.verifyComplete()
        for (role in listOf(UserRole.USER, UserRole.ADMIN)) {

            // when
            val stale = users.changeRoleAndStatus(created.id, 0, role, UserStatus.ACTIVE)

            // then
            StepVerifier.create(stale).expectError(OptimisticLockingFailureException::class.java)
                .verify()
        }

        // when
        val missing = users.changeRoleAndStatus(Long.MAX_VALUE, 1, UserRole.USER, UserStatus.ACTIVE)

        // then
        StepVerifier.create(missing).expectError(EntityNotFoundException::class.java).verify()
        assertThat(current(created.id)).isEqualTo(before)
        assertThat(history()).isEmpty()
    }

    @Test
    fun `update returning is empty for missing row and stale version`() {

        // given
        val created = createUser()
        val before = current(created.id)

        // when
        val missing = repository.updateRow(Long.MAX_VALUE, 1, UserRole.ADMIN, UserStatus.BLOCKED)
        val stale = repository.updateRow(created.id, 0, UserRole.ADMIN, UserStatus.BLOCKED)

        // then
        StepVerifier.create(missing).verifyComplete()
        StepVerifier.create(stale).verifyComplete()
        assertThat(current(created.id)).isEqualTo(before)
        assertThat(history()).isEmpty()
    }

    @Test
    fun `history key conflict rolls back user and preserves existing history`() {

        // given
        val created = createUser()
        val before = current(created.id)
        jdbc.update(
            """
            INSERT INTO app_user_history (user_id, version, login, role, status, modified_at)
            SELECT id, version, 'existing-history', role, status, modified_at FROM app_user
            WHERE id = ?
            """,
            created.id,
        )
        val existingHistory = history()

        // when
        val result = users.changeRoleAndStatus(created.id, 1, UserRole.ADMIN, UserStatus.BLOCKED)

        // then
        StepVerifier.create(result).expectError(DataIntegrityViolationException::class.java).verify()
        assertThat(current(created.id)).isEqualTo(before)
        assertThat(history()).isEqualTo(existingHistory)
    }

    @Test
    fun `failure after both writes rolls back reactive outer transaction`() {

        // given
        val created = createUser()
        val before = current(created.id)
        val failure = IllegalStateException("forced failure after writes")
        val transactions = TransactionalOperator.create(transactionManager)

        // when
        val result = transactions.transactional(
            users.changeRoleAndStatus(created.id, 1, UserRole.ADMIN, UserStatus.BLOCKED)
                .flatMap { saved ->
                    assertThat(saved.version).isEqualTo(2)
                    databaseClient.sql(
                        "SELECT count(*) AS history_count FROM app_user_history WHERE user_id = :id",
                    ).bind("id", created.id)
                        .map { row, _ -> row.get("history_count", Long::class.javaObjectType)!! }
                        .one()
                        .flatMap { historyCount ->
                            assertThat(historyCount).isEqualTo(1)
                            Mono.error<AppUser>(failure)
                        }
                },
        )

        // then
        StepVerifier.create(result).expectErrorMatches { it === failure }.verify()
        assertThat(current(created.id)).isEqualTo(before)
        assertThat(history()).isEmpty()
    }

    @Test
    fun `two transactions at the same version commit one winner and one history row`() {

        // given
        val created = createUser()
        val before = current(created.id)
        val loadedCount = AtomicInteger()
        val bothLoaded = Sinks.empty<Void>()
        doAnswer { call ->
            @Suppress("UNCHECKED_CAST")
            val query = mockingDetails(call.mock).mockCreationSettings.defaultAnswer.answer(call)
                as Mono<AppUser>
            query.flatMap { loaded ->
                assertThat(loaded.version).isEqualTo(1)
                if (loadedCount.incrementAndGet() == 2) {
                    bothLoaded.tryEmitEmpty().orThrow()
                }
                bothLoaded.asMono().thenReturn(loaded)
            }
        }.`when`(repository).findById(created.id)

        // when
        val attempts = Mono.zip(
            users.changeRoleAndStatus(created.id, 1, UserRole.ADMIN, UserStatus.BLOCKED)
                .materialize(),
            users.changeRoleAndStatus(created.id, 1, UserRole.STYLIST, UserStatus.BLOCKED)
                .materialize(),
        ).block(timeout)!!

        // then
        val outcomes = listOf(attempts.t1, attempts.t2)
        val winner = outcomes.single { it.isOnNext }.get()!!
        assertThat(outcomes.single { it.isOnError }.throwable)
            .isInstanceOf(OptimisticLockingFailureException::class.java)
        assertThat(loadedCount.get()).isEqualTo(2)
        val persisted = current(created.id)
        assertThat(persisted.version).isEqualTo(2)
        assertThat(persisted.role).isEqualTo(winner.role.name)
        assertThat(persisted.status).isEqualTo("BLOCKED")
        assertThat(history()).containsExactly(Archived(created.id, before, persisted.modifiedAt))
    }

    private fun createUser(login: String = "alice"): AppUser =
        users.create(login, "test-password-hash", UserRole.USER).block(timeout)!!

    private fun current(id: Long): Snapshot = jdbc.queryForObject(
        "SELECT login, role, status, version, modified_at FROM app_user WHERE id = ?",
        { row, _ ->
            Snapshot(
                row.getString("login"), row.getString("role"), row.getString("status"),
                row.getLong("version"), row.getTimestamp("modified_at").toInstant(),
            )
        },
        id,
    )

    private fun history(): List<Archived> = jdbc.query(
        "SELECT * FROM app_user_history ORDER BY user_id, version",
    ) { row, _ ->
        Archived(
            row.getLong("user_id"),
            Snapshot(
                row.getString("login"), row.getString("role"), row.getString("status"),
                row.getLong("version"), row.getTimestamp("modified_at").toInstant(),
            ),
            row.getTimestamp("archived_at").toInstant(),
        )
    }

    private data class Snapshot(
        val login: String,
        val role: String,
        val status: String,
        val version: Long,
        val modifiedAt: Instant,
    )

    private data class Archived(val userId: Long, val snapshot: Snapshot, val archivedAt: Instant)

    companion object {
        private val timeout = Duration.ofSeconds(20)
    }
}
