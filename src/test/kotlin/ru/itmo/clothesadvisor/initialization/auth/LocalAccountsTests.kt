package ru.itmo.clothesadvisor.initialization.auth

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verifyNoInteractions
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.DefaultApplicationArguments
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import ru.itmo.clothesadvisor.config.TestTimeConfiguration
import ru.itmo.clothesadvisor.model.user.UserRole
import ru.itmo.clothesadvisor.model.user.UserStatus
import ru.itmo.clothesadvisor.service.user.AppUserService

@Testcontainers
@SpringBootTest(properties = [
    "LOCAL_USER_PASSWORD=local-user-test",
    "LOCAL_STYLIST_PASSWORD=local-stylist-test",
    "LOCAL_ADMIN_PASSWORD=local-admin-test",
])
@ActiveProfiles("local")
@Import(TestTimeConfiguration::class)
class LocalAccountsTests {
    @Autowired
    private lateinit var users: AppUserService

    @Autowired
    private lateinit var encoder: PasswordEncoder

    @Autowired
    private lateinit var initializer: LocalAccountsInitializer

    @Autowired
    private lateinit var jdbc: JdbcTemplate

    @Test
    fun `local profile creates three hashed accounts and rerun preserves all existing state`() {
        UserRole.entries.forEach { role ->
            val login = role.name.lowercase()
            val user = users.findByLogin(login)!!
            assertThat(user.role).isEqualTo(role)
            assertThat(user.status).isEqualTo(UserStatus.ACTIVE)
            assertThat(user.passwordHash).startsWith("$2a$10$")
            assertThat(encoder.matches("local-$login-test", user.passwordHash)).isTrue()
        }
        val user = users.findByLogin("user")!!
        users.changeRoleAndStatus(user.id!!, user.version, UserRole.ADMIN, UserStatus.BLOCKED)
        val beforeUsers = jdbc.queryForList("SELECT * FROM app_user ORDER BY id")
        val beforeHistory = jdbc.queryForList("SELECT * FROM app_user_history ORDER BY user_id, version")
        initializer.run(DefaultApplicationArguments())
        assertThat(jdbc.queryForList("SELECT * FROM app_user ORDER BY id")).isEqualTo(beforeUsers)
        assertThat(jdbc.queryForList("SELECT * FROM app_user_history ORDER BY user_id, version")).isEqualTo(beforeHistory)
        assertThat(beforeUsers).hasSize(3)
        assertThat(beforeHistory).hasSize(1)
    }

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

class LocalAccountsValidationTests {
    @Test
    fun `all seed passwords are validated before any lookup or write`() {
        val users = mock(AppUserService::class.java)
        val encoder = mock(PasswordEncoder::class.java)
        listOf("", " ", "a".repeat(73), "я".repeat(36) + "a").forEach { invalid ->
            val initializer = LocalAccountsInitializer(users, encoder, "valid", "valid", invalid)
            assertThatThrownBy { initializer.run(DefaultApplicationArguments()) }
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessage("Local account passwords must be nonblank and contain at most 72 UTF-8 bytes")
        }
        verifyNoInteractions(users, encoder)
    }
}
