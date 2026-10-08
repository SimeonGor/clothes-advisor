package ru.itmo.clothesadvisor.service.user

import jakarta.persistence.EntityManager
import jakarta.persistence.EntityNotFoundException
import jakarta.validation.ConstraintViolationException
import java.sql.Timestamp
import java.time.Instant
import java.util.concurrent.Callable
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.orm.ObjectOptimisticLockingFailureException
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import ru.itmo.clothesadvisor.config.TestTimeConfiguration
import ru.itmo.clothesadvisor.model.user.AppUser
import ru.itmo.clothesadvisor.model.user.AppUserHistory
import ru.itmo.clothesadvisor.model.user.AppUserHistoryId
import ru.itmo.clothesadvisor.model.user.UserRole
import ru.itmo.clothesadvisor.model.user.UserStatus
import ru.itmo.clothesadvisor.repository.user.AppUserRepository

@Testcontainers
@SpringBootTest
@Import(TestTimeConfiguration::class)
class AppUserPersistenceTests {
    @Autowired
    private lateinit var users: AppUserService

    @Autowired
    private lateinit var jdbc: JdbcTemplate

    @Autowired
    private lateinit var repository: AppUserRepository

    @Autowired
    private lateinit var entityManager: EntityManager

    @Autowired
    private lateinit var transactionManager: PlatformTransactionManager

    @BeforeEach
    fun clearUserAndItemData() {
        jdbc.execute("""TRUNCATE TABLE outfit_item, outfit_weather, outfit, access_grant,
            wardrobe_item_photo, wardrobe_item_history, wardrobe_item, app_user_history, app_user RESTART IDENTITY""")
    }

    @Test
    fun `creation stores version one and equal timestamps with case sensitive login`() {
        val user = createUser()
        val loaded = users.findById(user.id!!)!!

        assertThat(loaded.version).isEqualTo(1)
        assertThat(loaded.createdAt).isEqualTo(loaded.modifiedAt)
        assertThat(loaded.createdAt).isEqualTo(TestTimeConfiguration.FIXED_TIME)
        assertThat(loaded.passwordHash).isEqualTo("test-password-hash")
        assertThat(loaded.toString()).doesNotContain("test-password-hash")
        assertThat(users.findByLogin("alice")?.id).isEqualTo(user.id)
        assertThat(users.findByLogin("missing")).isNull()
        assertThat(users.findById(Long.MAX_VALUE)).isNull()
        assertThat(current(user.id!!)).isEqualTo(
            Snapshot("alice", "USER", "ACTIVE", 1, loaded.modifiedAt),
        )
        assertThat(createUser("Alice").id).isNotEqualTo(user.id)
        assertThat(history()).isEmpty()
    }

    @Test
    fun `all enum values round trip through current and historical entities`() {
        for (role in UserRole.entries) {
            for (status in UserStatus.entries) {
                val user = users.create("${role.name}-${status.name}", "hash", role, status)
                val id = user.id!!
                val created = users.findById(id)!!
                assertThat(created.role).isEqualTo(role)
                assertThat(created.status).isEqualTo(status)

                val nextRole = UserRole.entries[(role.ordinal + 1) % UserRole.entries.size]
                val nextStatus = UserStatus.entries.single { it != status }
                users.changeRoleAndStatus(id, 1, nextRole, nextStatus)
                val changed = users.findById(id)!!
                assertThat(changed.role).isEqualTo(nextRole)
                assertThat(changed.status).isEqualTo(nextStatus)
                val archived = entityManager.find(AppUserHistory::class.java, AppUserHistoryId(id, 1))
                assertThat(archived.role).isEqualTo(role)
                assertThat(archived.status).isEqualTo(status)
            }
        }
    }

    @Test
    fun `duplicate login and blank credentials cannot be persisted`() {
        createUser()
        assertThatThrownBy { createUser() }.isInstanceOf(DataIntegrityViolationException::class.java)

        for (blank in listOf("", " \t\n")) {
            for ((login, hash) in listOf(blank to "hash", "another" to blank)) {
                val failure = catchThrowable { users.create(login, hash, UserRole.USER) }
                assertThat(generateSequence(failure) { it.cause }.toList())
                    .anyMatch { it is ConstraintViolationException }
            }
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM app_user", Long::class.java)).isEqualTo(1)
    }

    @Test
    fun `change archives the exact old state and increments the version once`() {
        val id = createUser().id!!
        val previousTime = TestTimeConfiguration.FIXED_TIME.minusSeconds(1)
        jdbc.update(
            "UPDATE app_user SET created_at = ?, modified_at = ? WHERE id = ?",
            Timestamp.from(previousTime), Timestamp.from(previousTime), id,
        )
        val original = users.findById(id)!!
        val old = current(id)

        val changed = users.changeRoleAndStatus(id, 1, UserRole.STYLIST, UserStatus.BLOCKED)

        assertThat(changed.version).isEqualTo(2)
        assertThat(changed.role).isEqualTo(UserRole.STYLIST)
        assertThat(changed.status).isEqualTo(UserStatus.BLOCKED)
        assertThat(changed.createdAt).isEqualTo(original.createdAt)
        assertThat(changed.passwordHash).isEqualTo(original.passwordHash)
        assertThat(changed.modifiedAt).isEqualTo(TestTimeConfiguration.FIXED_TIME)
        assertThat(current(id).modifiedAt).isEqualTo(changed.modifiedAt)
        assertThat(history()).containsExactly(Archived(id, old, changed.modifiedAt))

        val previous = current(id)
        users.changeRoleAndStatus(id, 2, UserRole.ADMIN, UserStatus.BLOCKED)
        assertThat(current(id).version).isEqualTo(3)
        assertThat(history()).containsExactly(
            Archived(id, old, TestTimeConfiguration.FIXED_TIME),
            Archived(id, previous, TestTimeConfiguration.FIXED_TIME),
        )
    }

    @Test
    fun `no-op is stable but still rejects a stale expected version`() {
        val id = createUser().id!!
        val before = current(id)

        assertThat(users.changeRoleAndStatus(id, 1, UserRole.USER, UserStatus.ACTIVE).version).isEqualTo(1)
        assertThat(current(id)).isEqualTo(before)
        assertThatThrownBy { users.changeRoleAndStatus(id, 0, UserRole.USER, UserStatus.ACTIVE) }
            .isInstanceOf(ObjectOptimisticLockingFailureException::class.java)
        assertThatThrownBy { users.changeRoleAndStatus(id, 0, UserRole.ADMIN, UserStatus.BLOCKED) }
            .isInstanceOf(ObjectOptimisticLockingFailureException::class.java)
        assertThat(current(id)).isEqualTo(before)
        assertThat(history()).isEmpty()
        assertThatThrownBy {
            users.changeRoleAndStatus(Long.MAX_VALUE, 1, UserRole.USER, UserStatus.ACTIVE)
        }.isInstanceOf(EntityNotFoundException::class.java)
    }

    @Test
    fun `failure after both flushes rolls back user and history`() {
        val id = createUser().id!!
        val before = current(id)

        assertThatThrownBy {
            TransactionTemplate(transactionManager).executeWithoutResult {
                users.changeRoleAndStatus(id, 1, UserRole.ADMIN, UserStatus.BLOCKED)
                repository.flush()
                assertThat(current(id).version).isEqualTo(2)
                assertThat(history()).hasSize(1)
                error("forced failure after writes")
            }
        }.isInstanceOf(IllegalStateException::class.java).hasMessage("forced failure after writes")

        assertThat(current(id)).isEqualTo(before)
        assertThat(history()).isEmpty()
    }

    @Test
    fun `history key conflict rolls back flushed user and never overwrites historical row`() {
        val id = createUser().id!!
        val before = current(id)
        jdbc.update(
            """
            INSERT INTO app_user_history (user_id, version, login, role, status, modified_at, archived_at)
            SELECT id, version, 'existing-history', role, status, modified_at, modified_at FROM app_user
            WHERE id = ?
            """.trimIndent(), id,
        )
        val existing = history()

        assertThatThrownBy { users.changeRoleAndStatus(id, 1, UserRole.ADMIN, UserStatus.BLOCKED) }
            .isInstanceOf(DataIntegrityViolationException::class.java)

        assertThat(current(id)).isEqualTo(before)
        assertThat(history()).isEqualTo(existing)
    }

    @Test
    fun `two transactions loaded at the same version produce one winner and one history row`() {
        val id = createUser().id!!
        val before = current(id)
        val loaded = CyclicBarrier(2)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val futures = listOf(UserRole.ADMIN, UserRole.STYLIST).map { role ->
                executor.submit(Callable {
                    try {
                        TransactionTemplate(transactionManager).apply { timeout = 20 }.executeWithoutResult {
                            // Both transactions retain version 1 in their own persistence context.
                            assertThat(requireNotNull(repository.findById(id)).version).isEqualTo(1)
                            loaded.await(10, TimeUnit.SECONDS)
                            users.changeRoleAndStatus(id, 1, role, UserStatus.BLOCKED)
                        }
                        Attempt(role, null)
                    } catch (failure: Exception) {
                        Attempt(role, failure)
                    }
                })
            }
            val attempts = futures.map { it.get(30, TimeUnit.SECONDS) }
            val winner = attempts.single { it.failure == null }
            assertThat(attempts.single { it.failure != null }.failure)
                .isInstanceOf(ObjectOptimisticLockingFailureException::class.java)
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

    private fun current(id: Long): Snapshot = jdbc.queryForObject(
        "SELECT login, role, status, version, modified_at FROM app_user WHERE id = ?",
        { row, _ -> Snapshot(
            row.getString("login"), row.getString("role"), row.getString("status"),
            row.getLong("version"), row.getTimestamp("modified_at").toInstant(),
        ) }, id,
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
    private data class Attempt(val role: UserRole, val failure: Exception?)

    companion object {
        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:18-alpine")

        @JvmStatic
        @DynamicPropertySource
        fun postgresProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
        }
    }
}
