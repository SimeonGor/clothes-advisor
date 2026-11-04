package ru.itmo.clothesadvisor.accountservice.initialization.user

import java.time.Duration
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.boot.SpringApplication
import org.springframework.boot.availability.AvailabilityChangeEvent
import org.springframework.boot.availability.ReadinessState
import org.springframework.context.ApplicationEvent
import org.springframework.context.ApplicationListener
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.core.env.StandardEnvironment
import org.springframework.core.env.SystemEnvironmentPropertySource
import ru.itmo.clothesadvisor.accountservice.AccountServiceApplication
import ru.itmo.clothesadvisor.accountservice.config.PostgresIntegrationTest
import ru.itmo.clothesadvisor.accountservice.config.TestJwt
import ru.itmo.clothesadvisor.accountservice.service.auth.PasswordService
import ru.itmo.clothesadvisor.accountservice.service.user.BootstrapAdminException
import ru.itmo.clothesadvisor.accountservice.service.user.UserManagementService

class BootstrapAdminTests : PostgresIntegrationTest() {
    @ParameterizedTest
    @ValueSource(strings = ["#{'initial-secret-password'}", "#{initial-secret-password", "\${user.home}-password"])
    fun `expression shaped passwords remain literal credentials`(password: String) {
        // given: a valid password that resembles Spring expression syntax

        // when
        start(LOGIN, password).use { context ->
            // then
            assertMatches(context, password, rows().single()["password_hash"] as String)
        }
    }

    @Test
    fun `disabled bootstrap leaves database empty and configured bootstrap is ready with usable credentials`() {
        start().use { assertThat(rows()).isEmpty() }
        val ready = AtomicBoolean()
        start(LOGIN, PASSWORD, onReady = {
            assertThat(rows().single()["login"]).isEqualTo(LOGIN)
            ready.set(true)
        }).use { context ->
            assertThat(ready).isTrue()
            val created = rows().single()
            assertThat(created["role"].toString()).isEqualTo("ADMIN")
            assertThat(created["status"].toString()).isEqualTo("ACTIVE")
            assertThat(created["version"]).isEqualTo(1L)
            assertMatches(context, PASSWORD, created["password_hash"] as String)
            assertThat(history()).isEmpty()
        }
        val before = rows()
        start(LOGIN, OTHER_PASSWORD).use {
            assertThat(rows()).isEqualTo(before)
            assertMatches(it, PASSWORD, before.single()["password_hash"] as String)
            assertThat(history()).isEmpty()
        }
    }

    @Test
    fun `local seed runs first and only exact admin placeholder is initialized once`() {
        start("admin", PASSWORD, local = true).use { context ->
            val admin = rows().single { it["login"] == "admin" }
            assertMatches(context, PASSWORD, admin["password_hash"] as String)
            assertThat(admin["version"]).isEqualTo(2L)
            assertThat(rows().filter { it["login"] != "admin" }.map { it["password_hash"] })
                .containsExactly("!", "!")
            assertThat(history().single()["version"]).isEqualTo(1L)
            assertThat(history().single()["login"]).isEqualTo("admin")
        }
        val before = rows()
        val archived = history()
        start("admin", OTHER_PASSWORD, local = true).use {
            assertThat(rows()).isEqualTo(before)
            assertThat(history()).isEqualTo(archived)
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["unknown-hash", "! "])
    fun `bootstrap never replaces an unknown existing credential`(hash: String) {
        start().close()
        seed(hash = hash)
        val before = rows()
        start(LOGIN, PASSWORD).use {
            assertThat(rows()).isEqualTo(before)
            assertThat(history()).isEmpty()
        }
    }

    @ParameterizedTest
    @CsvSource("USER, ACTIVE", "STYLIST, ACTIVE", "ADMIN, BLOCKED")
    fun `bootstrap does not promote or unblock and fails before readiness`(role: String, status: String) {
        start().close()
        seed(role, status)
        val before = rows()
        val ready = AtomicBoolean()

        val failure = catchThrowable { start(LOGIN, PASSWORD, onReady = { ready.set(true) }).close() }

        assertSafeFailure(failure)
        assertThat(ready).isFalse()
        assertThat(rows()).isEqualTo(before)
        assertThat(history()).isEmpty()
    }

    @Test
    fun `missing or invalid credentials fail safely without creating accounts`() {
        start().close()
        for ((login, password) in listOf(
            LOGIN to "", "" to PASSWORD, "x".repeat(101) to PASSWORD, LOGIN to "short",
        )) {
            val ready = AtomicBoolean()
            val failure = catchThrowable { start(login, password, onReady = { ready.set(true) }).close() }
            assertSafeFailure(failure)
            assertThat(failure.stackTraceToString()).doesNotContain("short")
            assertThat(ready).isFalse()
            assertThat(rows()).isEmpty()
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `concurrent bootstrap keeps first successful credential and only one history snapshot`(placeholder: Boolean) {
        start().use { context ->
            if (placeholder) seed()
            val users = context.getBean(UserManagementService::class.java)
            val candidates = listOf(PASSWORD, OTHER_PASSWORD)
            jdbc.dataSource!!.connection.use { lock ->
                lock.autoCommit = false
                lock.createStatement().use { it.execute("LOCK TABLE app_user IN ACCESS EXCLUSIVE MODE") }
                Executors.newFixedThreadPool(2).use { executor ->
                    val pending = candidates.map { password ->
                        executor.submit(Callable { users.bootstrap(LOGIN, password).block(Duration.ofSeconds(30)) })
                    }
                    try {
                        assertInsertWait(2)
                        lock.commit()
                        pending.forEach { it.get(30, TimeUnit.SECONDS) }
                    } finally {
                        lock.rollback()
                        pending.forEach { it.get(30, TimeUnit.SECONDS) }
                    }
                }
            }
            val after = rows().single()
            assertThat(after["version"]).isEqualTo(if (placeholder) 2L else 1L)
            assertThat(history()).hasSize(if (placeholder) 1 else 0)
            val passwords = context.getBean(PasswordService::class.java)
            assertThat(candidates.count {
                passwords.matches(it, after["password_hash"] as String).block(Duration.ofSeconds(10)) == true
            }).isEqualTo(1)
            val before = rows()
            users.bootstrap(LOGIN, "third-password").block(Duration.ofSeconds(10))
            assertThat(rows()).isEqualTo(before)
            assertThat(history()).hasSize(if (placeholder) 1 else 0)
        }
    }

    @Test
    fun `readiness waits for bootstrap transaction to finish`() {
        start().close()
        val ready = AtomicBoolean()
        jdbc.dataSource!!.connection.use { lock ->
            lock.autoCommit = false
            lock.createStatement().use { it.execute("LOCK TABLE app_user IN ACCESS EXCLUSIVE MODE") }
            Executors.newSingleThreadExecutor().use { executor ->
                val pending = executor.submit(Callable {
                    start(LOGIN, PASSWORD, onReady = { ready.set(true) })
                })
                try {
                    assertInsertWait(1)
                    assertThat(ready).isFalse()
                    assertThat(pending.isDone).isFalse()
                    lock.commit()
                    pending.get(30, TimeUnit.SECONDS).use {
                        assertThat(ready).isTrue()
                        assertThat(rows()).hasSize(1)
                    }
                } finally {
                    lock.rollback()
                    pending.get(30, TimeUnit.SECONDS).close()
                }
            }
        }
    }

    private fun assertSafeFailure(failure: Throwable?) {
        assertThat(failure).isNotNull()
        assertThat(generateSequence(failure) { it.cause }.last())
            .isInstanceOf(BootstrapAdminException::class.java)
        assertThat(failure!!.stackTraceToString()).doesNotContain(LOGIN, PASSWORD, OTHER_PASSWORD)
        assertThat(generateSequence(failure) { it.cause }.last().message)
            .isEqualTo("Initial administrator setup failed")
    }

    private fun assertMatches(context: ConfigurableApplicationContext, password: String, hash: String) {
        assertThat(context.getBean(PasswordService::class.java).matches(password, hash)
            .block(Duration.ofSeconds(10))).isTrue()
    }

    private fun seed(role: String = "ADMIN", status: String = "ACTIVE", hash: String = "!") {
        jdbc.update("""INSERT INTO app_user (login, password_hash, role, status, version)
            VALUES (?, ?, ?::user_role, ?::user_status, 1)""", LOGIN, hash, role, status)
    }

    private fun rows() = jdbc.queryForList("SELECT * FROM app_user ORDER BY login")
    private fun history() = jdbc.queryForList("SELECT * FROM app_user_history ORDER BY version")

    private fun assertInsertWait(count: Int) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        do {
            if ((jdbc.queryForObject("""SELECT count(*) FROM pg_stat_activity
                WHERE datname = current_database() AND cardinality(pg_blocking_pids(pid)) > 0
                AND query LIKE '%INSERT INTO app_user%' AND wait_event_type = 'Lock'""",
                Long::class.java) ?: 0L) >= count) return
            Thread.yield()
        } while (System.nanoTime() < deadline)
        throw AssertionError("Bootstrap did not reach the PostgreSQL table lock")
    }

    private fun start(
        login: String = "",
        password: String = "",
        local: Boolean = false,
        onReady: () -> Unit = {},
    ): ConfigurableApplicationContext = SpringApplication(AccountServiceApplication::class.java)
        .apply {
            addInitializers({ context ->
                val name = StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME
                context.environment.propertySources.replace(name, SystemEnvironmentPropertySource(name, mapOf(
                    "APP_BOOTSTRAP_ADMIN_LOGIN" to login,
                    "APP_BOOTSTRAP_ADMIN_PASSWORD" to password,
                )))
            })
            addListeners(ApplicationListener<ApplicationEvent> { event ->
                if (event is AvailabilityChangeEvent<*> &&
                    event.state == ReadinessState.ACCEPTING_TRAFFIC) onReady()
            })
        }.run(
            "--server.port=0",
            "--app.jwt.private-key=${TestJwt.privateKeyLocation}",
            "--app.jwt.public-key=${TestJwt.publicKeyLocation}",
            "--spring.profiles.active=${if (local) "test,local" else "test"}",
            "--spring.r2dbc.url=r2dbc:postgresql://${postgres.host}:${postgres.firstMappedPort}/${postgres.databaseName}",
            "--spring.r2dbc.username=${postgres.username}",
            "--spring.r2dbc.password=${postgres.password}",
            "--spring.flyway.url=${postgres.jdbcUrl}",
            "--spring.flyway.user=${postgres.username}",
            "--spring.flyway.password=${postgres.password}",
        )

    companion object {
        private const val LOGIN = "initial-secret-admin"
        private const val PASSWORD = "initial-secret-password"
        private const val OTHER_PASSWORD = "other-secret-password"
    }
}
