package ru.itmo.clothesadvisor.service.user

import jakarta.validation.ConstraintViolationException
import java.sql.Timestamp
import java.time.Instant
import java.util.concurrent.Callable
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.Test
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mockingDetails
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import ru.itmo.clothesadvisor.config.PostgresIntegrationTest
import ru.itmo.clothesadvisor.config.TestTimeConfiguration
import ru.itmo.clothesadvisor.model.EntityNotFoundException
import ru.itmo.clothesadvisor.model.user.AppUser
import ru.itmo.clothesadvisor.model.user.UserRole
import ru.itmo.clothesadvisor.model.user.UserStatus
import ru.itmo.clothesadvisor.repository.user.AppUserRepository

@SpringBootTest
@Import(TestTimeConfiguration::class)
class AppUserPersistenceTests : PostgresIntegrationTest() {
    @Autowired private lateinit var users: AppUserService

    @Autowired private lateinit var jdbc: JdbcTemplate

    @MockitoSpyBean private lateinit var repository: AppUserRepository

    @Autowired private lateinit var transactionManager: PlatformTransactionManager

    @Test
    fun `creation stores version one and equal timestamps with case sensitive login`() {

        // given: a new case-sensitive login and valid credentials

        // when
        val user = createUser()
        val loaded = users.findById(user.id!!)!!

        // then
        assertThat(loaded.version).isEqualTo(1)
        assertThat(loaded.createdAt).isEqualTo(loaded.modifiedAt)
        assertThat(loaded.createdAt)
            .isEqualTo(
                jdbc
                    .queryForObject(
                        "SELECT created_at FROM app_user WHERE id = ?",
                        Timestamp::class.java,
                        user.id,
                    )!!
                    .toInstant(),
            )
        assertThat(loaded.passwordHash).isEqualTo("test-password-hash")
        assertThat(loaded.toString()).doesNotContain("test-password-hash")

        // when
        val matchingUserId = users.findByLogin("alice")?.id

        // then
        assertThat(matchingUserId).isEqualTo(user.id)

        // when
        val missingLogin = users.findByLogin("missing")

        // then
        assertThat(missingLogin).isNull()

        // when
        val missingId = users.findById(Long.MAX_VALUE)

        // then
        assertThat(missingId).isNull()
        assertThat(current(user.id!!))
            .isEqualTo(
                Snapshot("alice", "USER", "ACTIVE", 1, loaded.modifiedAt),
            )

        // when
        val differentCaseUserId = createUser("Alice").id

        // then
        assertThat(differentCaseUserId).isNotEqualTo(user.id)
        assertThat(history()).isEmpty()
    }

    @Test
    fun `all enum values round trip through current and historical database rows`() {

        // given
        for (role in UserRole.entries) {
            for (status in UserStatus.entries) {

                // given: the current role and status enum values

                // when
                val user = users.create("${role.name}-${status.name}", "hash", role, status)
                val id = user.id!!
                val created = users.findById(id)!!

                // then
                assertThat(created.role).isEqualTo(role)
                assertThat(created.status).isEqualTo(status)

                // given
                val nextRole = UserRole.entries[(role.ordinal + 1) % UserRole.entries.size]
                val nextStatus = UserStatus.entries.single { it != status }

                // when
                users.changeRoleAndStatus(id, 1, nextRole, nextStatus)
                val changed = users.findById(id)!!

                // then
                assertThat(changed.role).isEqualTo(nextRole)
                assertThat(changed.status).isEqualTo(nextStatus)
                val archived =
                    jdbc.queryForMap(
                        "SELECT role, status FROM app_user_history WHERE user_id = ? AND version = 1",
                        id,
                    )
                assertThat(archived["role"].toString()).isEqualTo(role.name)
                assertThat(archived["status"].toString()).isEqualTo(status.name)
            }
        }
    }

    @Test
    fun `duplicate login and blank credentials cannot be persisted`() {

        // given
        createUser()

        // when
        val duplicateLogin = catchThrowable { createUser() }

        // then
        assertThat(duplicateLogin).isInstanceOf(DataIntegrityViolationException::class.java)

        for (blank in listOf("", " \t\n")) {
            for ((login, hash) in listOf(blank to "hash", "another" to blank)) {

                // given: a blank login or password hash

                // when
                val failure = catchThrowable { users.create(login, hash, UserRole.USER) }

                // then
                assertThat(generateSequence(failure) { it.cause }.toList()).anyMatch {
                    it is ConstraintViolationException
                }
            }
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM app_user", Long::class.java))
            .isEqualTo(1)
    }

    @Test
    fun `change archives the exact old state and increments the version once`() {

        // given
        val id = createUser().id!!
        val previousTime = TestTimeConfiguration.FIXED_TIME.minusSeconds(1)
        jdbc.update(
            "UPDATE app_user SET created_at = ?, modified_at = ? WHERE id = ?",
            Timestamp.from(previousTime),
            Timestamp.from(previousTime),
            id,
        )
        val original = users.findById(id)!!
        val old = current(id)

        // when
        val changed = users.changeRoleAndStatus(id, 1, UserRole.STYLIST, UserStatus.BLOCKED)

        // then
        assertThat(changed.version).isEqualTo(2)
        assertThat(changed.role).isEqualTo(UserRole.STYLIST)
        assertThat(changed.status).isEqualTo(UserStatus.BLOCKED)
        assertThat(changed.createdAt).isEqualTo(original.createdAt)
        assertThat(changed.passwordHash).isEqualTo(original.passwordHash)
        assertThat(current(id).modifiedAt).isEqualTo(changed.modifiedAt)
        assertThat(history()).containsExactly(Archived(id, old, changed.modifiedAt))

        // given
        val previous = current(id)

        // when
        users.changeRoleAndStatus(id, 2, UserRole.ADMIN, UserStatus.BLOCKED)

        // then
        assertThat(current(id).version).isEqualTo(3)
        assertThat(history())
            .containsExactly(
                Archived(id, old, changed.modifiedAt),
                Archived(id, previous, current(id).modifiedAt),
            )
    }

    @Test
    fun `no-op is stable but still rejects a stale expected version`() {

        // given
        val id = createUser().id!!
        val before = current(id)

        // when
        val unchangedVersion =
            users.changeRoleAndStatus(id, 1, UserRole.USER, UserStatus.ACTIVE).version

        // then
        assertThat(unchangedVersion).isEqualTo(1)
        assertThat(current(id)).isEqualTo(before)

        // when
        val staleNoOp = catchThrowable {
            users.changeRoleAndStatus(id, 0, UserRole.USER, UserStatus.ACTIVE)
        }

        // then
        assertThat(staleNoOp).isInstanceOf(OptimisticLockingFailureException::class.java)

        // when
        val staleChange = catchThrowable {
            users.changeRoleAndStatus(id, 0, UserRole.ADMIN, UserStatus.BLOCKED)
        }

        // then
        assertThat(staleChange).isInstanceOf(OptimisticLockingFailureException::class.java)
        assertThat(current(id)).isEqualTo(before)
        assertThat(history()).isEmpty()

        // when
        val missingUser = catchThrowable {
            users.changeRoleAndStatus(Long.MAX_VALUE, 1, UserRole.USER, UserStatus.ACTIVE)
        }

        // then
        assertThat(missingUser).isInstanceOf(EntityNotFoundException::class.java)
    }

    @Test
    fun `failure after both writes rolls back user and history`() {

        // given
        val id = createUser().id!!
        val before = current(id)

        // when
        val rollbackFailure = catchThrowable {
            TransactionTemplate(transactionManager).executeWithoutResult {
                users.changeRoleAndStatus(id, 1, UserRole.ADMIN, UserStatus.BLOCKED)

                assertThat(current(id).version).isEqualTo(2)
                assertThat(history()).hasSize(1)
                error("forced failure after writes")
            }
        }

        // then
        assertThat(rollbackFailure)
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessage("forced failure after writes")

        assertThat(current(id)).isEqualTo(before)
        assertThat(history()).isEmpty()
    }

    @Test
    fun `history key conflict rolls back written user and never overwrites historical row`() {

        // given
        val id = createUser().id!!
        val before = current(id)
        jdbc.update(
            """
            INSERT INTO app_user_history (user_id, version, login, role, status, modified_at, archived_at)
            SELECT id, version, 'existing-history', role, status, modified_at, modified_at FROM app_user
            WHERE id = ?
            """
                .trimIndent(),
            id,
        )
        val existing = history()

        // when
        val historyConflict = catchThrowable {
            users.changeRoleAndStatus(id, 1, UserRole.ADMIN, UserStatus.BLOCKED)
        }

        // then
        assertThat(historyConflict).isInstanceOf(DataIntegrityViolationException::class.java)

        assertThat(current(id)).isEqualTo(before)
        assertThat(history()).isEqualTo(existing)
    }

    @Test
    fun `two transactions loaded at the same version produce one winner and one history row`() {

        // given
        val id = createUser().id!!
        val before = current(id)
        val loaded = CyclicBarrier(2)
        doAnswer { call ->
                val user =
                    mockingDetails(call.mock).mockCreationSettings.defaultAnswer.answer(call)
                        as AppUser
                assertThat(user.version).isEqualTo(1)
                loaded.await(10, TimeUnit.SECONDS)
                user
            }
            .`when`(repository)
            .findById(id)
        val executor = Executors.newFixedThreadPool(2)
        try {

            // when
            val futures =
                listOf(UserRole.ADMIN, UserRole.STYLIST).map { role ->
                    executor.submit(
                        Callable {
                            try {
                                TransactionTemplate(transactionManager)
                                    .apply { timeout = 20 }
                                    .executeWithoutResult {
                                        users.changeRoleAndStatus(id, 1, role, UserStatus.BLOCKED)
                                    }
                                Attempt(role, null)
                            } catch (failure: Exception) {
                                Attempt(role, failure)
                            }
                        },
                    )
                }
            val attempts = futures.map { it.get(30, TimeUnit.SECONDS) }

            // then
            val winner = attempts.single { it.failure == null }
            assertThat(attempts.single { it.failure != null }.failure)
                .isInstanceOf(OptimisticLockingFailureException::class.java)
            val result = current(id)
            assertThat(result.version).isEqualTo(2)
            assertThat(result.role).isEqualTo(winner.role.name)
            assertThat(result.status).isEqualTo("BLOCKED")
            assertThat(history()).containsExactly(Archived(id, before, result.modifiedAt))
        } finally {
            executor.shutdownNow()
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue()
        }
    }

    private fun createUser(login: String = "alice"): AppUser =
        users.create(login, "test-password-hash", UserRole.USER)

    @Test
    fun `login boundary is enforced by both service and database`() {

        // given
        val user = createUser("x".repeat(100))

        // when
        val oversizedLogin = catchThrowable { createUser("x".repeat(101)) }

        // then
        assertThat(oversizedLogin).isInstanceOf(ConstraintViolationException::class.java)
        for (invalid in listOf("x".repeat(101), " \t\n")) {

            // when
            val invalidLogin = catchThrowable {
                jdbc.update("UPDATE app_user SET login = ? WHERE id = ?", invalid, user.id)
            }

            // then
            assertThat(invalidLogin).isInstanceOf(DataIntegrityViolationException::class.java)
        }

        // when
        val invalidVersion = catchThrowable {
            jdbc.update("UPDATE app_user SET version = 0 WHERE id = ?", user.id)
        }

        // then
        assertThat(invalidVersion).isInstanceOf(DataIntegrityViolationException::class.java)

        // when
        val preservedLogin = users.findById(user.id!!)!!.login

        // then
        assertThat(preservedLogin).isEqualTo("x".repeat(100))
    }

    @Test
    fun `database transaction timestamp owns creation modification and archival dates`() {

        // given: one explicit database transaction
        TransactionTemplate(transactionManager).executeWithoutResult {
            val transactionTime =
                jdbc.queryForObject("SELECT CURRENT_TIMESTAMP", Timestamp::class.java)!!.toInstant()

            // when
            val created = createUser()
            val changed =
                users.changeRoleAndStatus(created.id!!, 1, UserRole.STYLIST, UserStatus.ACTIVE)

            // then
            assertThat(created.createdAt).isEqualTo(transactionTime)
            assertThat(created.modifiedAt).isEqualTo(transactionTime)
            assertThat(changed.createdAt).isEqualTo(created.createdAt)
            assertThat(changed.modifiedAt).isEqualTo(transactionTime)
            assertThat(history().single())
                .isEqualTo(
                    Archived(
                        created.id!!,
                        Snapshot(created.login, "USER", "ACTIVE", 1, created.modifiedAt),
                        transactionTime,
                    ),
                )
            assertThat(changed.version).isEqualTo(2)
        }
    }

    private fun current(id: Long): Snapshot =
        jdbc.queryForObject(
            "SELECT login, role, status, version, modified_at FROM app_user WHERE id = ?",
            { row, _ ->
                Snapshot(
                    row.getString("login"),
                    row.getString("role"),
                    row.getString("status"),
                    row.getLong("version"),
                    row.getTimestamp("modified_at").toInstant(),
                )
            },
            id,
        )

    private fun history(): List<Archived> =
        jdbc.query(
            "SELECT * FROM app_user_history ORDER BY user_id, version",
        ) { row, _ ->
            Archived(
                row.getLong("user_id"),
                Snapshot(
                    row.getString("login"),
                    row.getString("role"),
                    row.getString("status"),
                    row.getLong("version"),
                    row.getTimestamp("modified_at").toInstant(),
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

    private data class Attempt(val role: UserRole, val failure: Exception?)
}
