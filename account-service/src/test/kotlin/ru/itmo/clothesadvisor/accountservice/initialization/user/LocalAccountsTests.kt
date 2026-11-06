package ru.itmo.clothesadvisor.accountservice.initialization.user

import java.sql.Timestamp
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.Test
import org.springframework.boot.SpringApplication
import org.springframework.boot.availability.AvailabilityChangeEvent
import org.springframework.boot.availability.ReadinessState
import org.springframework.context.ApplicationEvent
import org.springframework.context.ApplicationListener
import org.springframework.context.ConfigurableApplicationContext
import ru.itmo.clothesadvisor.accountservice.AccountServiceApplication
import ru.itmo.clothesadvisor.accountservice.config.PostgresIntegrationTest

class LocalAccountsTests : PostgresIntegrationTest() {
    @Test
    fun `non local startup does not seed and local restart preserves existing account state`() {
        // given
        start(local = false).use {
            assertThat(rows()).isEmpty()
        }
        val countAtReadiness = AtomicInteger(-1)

        // when
        start { countAtReadiness.set(rows().size) }.use {

            // then
            val seeded = rows()
            assertThat(countAtReadiness).hasValue(3)
            assertThat(seeded.map { row -> row["login"] })
                .containsExactly("admin", "stylist", "user")
            for (row in seeded) {
                assertThat(row["role"].toString())
                    .isEqualTo(row["login"].toString().uppercase())
                assertThat(row["status"].toString()).isEqualTo("ACTIVE")
                assertThat(row["password_hash"]).isEqualTo("!")
                assertThat(row["version"]).isEqualTo(1L)
                assertThat(row["created_at"]).isInstanceOf(Timestamp::class.java)
                assertThat(row["modified_at"]).isEqualTo(row["created_at"])
            }
        }
        jdbc.update("""UPDATE app_user SET password_hash = 'changed-hash', role = 'USER',
            status = 'BLOCKED', version = 7, modified_at = CURRENT_TIMESTAMP
            WHERE login = 'admin'""")
        jdbc.update("DELETE FROM app_user WHERE login = 'stylist'")
        val existing = rows()

        // when
        start { countAtReadiness.set(rows().size) }.use {

            // then
            assertThat(countAtReadiness).hasValue(3)
            assertThat(rows().filter { row -> row["login"] != "stylist" })
                .isEqualTo(existing)
            val restored = rows().single { row -> row["login"] == "stylist" }
            assertThat(restored["role"].toString()).isEqualTo("STYLIST")
            assertThat(restored["version"]).isEqualTo(1L)
            assertThat(jdbc.queryForObject("SELECT count(*) FROM app_user_history", Long::class.java))
                .isZero()
        }
    }

    @Test
    fun `readiness waits for local seed statement to finish`() {
        // given
        start(local = false).close()
        val ready = AtomicBoolean()
        val countAtReadiness = AtomicInteger(-1)
        jdbc.dataSource!!.connection.use { lock ->
            lock.autoCommit = false
            lock.createStatement().use { it.execute("LOCK TABLE app_user IN ACCESS EXCLUSIVE MODE") }
            Executors.newSingleThreadExecutor().use { executor ->

                // when
                val pending = executor.submit(Callable {
                    start {
                        countAtReadiness.set(rows().size)
                        ready.set(true)
                    }
                })
                try {
                    assertSeedLockWait()

                    // then
                    assertThat(ready).isFalse()
                    assertThat(pending.isDone).isFalse()

                    // when
                    lock.commit()
                    pending.get(30, TimeUnit.SECONDS).use {

                        // then
                        assertThat(ready).isTrue()
                        assertThat(countAtReadiness).hasValue(3)
                    }
                } finally {
                    lock.rollback()
                    pending.get(30, TimeUnit.SECONDS).close()
                }
            }
        }
    }

    @Test
    fun `seed failure prevents readiness and leaves no partial accounts`() {
        // given
        start(local = false).close()
        jdbc.execute("ALTER TABLE app_user ADD CONSTRAINT test_reject_stylist CHECK (login <> 'stylist')")
        val ready = AtomicBoolean()
        try {

            // when
            val failure = catchThrowable { start { ready.set(true) }.close() }

            // then
            assertThat(failure).isNotNull().hasStackTraceContaining("localAccountsInitializer")
            assertThat(ready).isFalse()
            assertThat(rows()).isEmpty()
        } finally {
            jdbc.execute("ALTER TABLE app_user DROP CONSTRAINT test_reject_stylist")
        }
    }

    private fun start(
        local: Boolean = true,
        onReady: () -> Unit = {},
    ): ConfigurableApplicationContext = SpringApplication(AccountServiceApplication::class.java)
        .apply {
            addListeners(ApplicationListener<ApplicationEvent> { event ->
                if (event is AvailabilityChangeEvent<*> &&
                    event.state == ReadinessState.ACCEPTING_TRAFFIC) onReady()
            })
        }.run(
            "--server.port=0",
            "--spring.profiles.active=${if (local) "test,local" else "test"}",
            "--spring.r2dbc.url=r2dbc:postgresql://${postgres.host}:" +
                "${postgres.firstMappedPort}/${postgres.databaseName}",
            "--spring.r2dbc.username=${postgres.username}",
            "--spring.r2dbc.password=${postgres.password}",
            "--spring.flyway.url=${postgres.jdbcUrl}",
            "--spring.flyway.user=${postgres.username}",
            "--spring.flyway.password=${postgres.password}",
        )

    private fun rows() = jdbc.queryForList("SELECT * FROM app_user ORDER BY login")

    private fun assertSeedLockWait() {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        do {
            if (jdbc.queryForObject(
                """SELECT EXISTS (SELECT 1 FROM pg_stat_activity
                    WHERE datname = current_database() AND cardinality(pg_blocking_pids(pid)) > 0
                    AND query LIKE '%INSERT INTO app_user%' AND wait_event_type = 'Lock')""",
                Boolean::class.java,
            ) == true) return
            Thread.yield()
        } while (System.nanoTime() < deadline)
        throw AssertionError("Local seed did not reach the PostgreSQL table lock")
    }
}
