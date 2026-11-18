package ru.itmo.clothesadvisor.recommendationservice.request

import io.r2dbc.spi.ConnectionFactories
import java.math.BigDecimal
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.r2dbc.connection.R2dbcTransactionManager
import org.springframework.r2dbc.core.DatabaseClient
import org.testcontainers.postgresql.PostgreSQLContainer
import ru.itmo.clothesadvisor.dto.ai.ItemSelectionWeather
import ru.itmo.clothesadvisor.recommendationservice.model.ai.SelectionGoal

class PartitionMaintenanceTests {
    private val factory = ConnectionFactories.get(
        "r2dbc:postgresql://${postgres.username}:${postgres.password}@${postgres.host}:${postgres.firstMappedPort}/${postgres.databaseName}",
    )
    private val database = DatabaseClient.create(factory)
    private val store = RecommendationRequestStore(database, R2dbcTransactionManager(factory))
    private lateinit var current: LocalDate

    @BeforeEach
    fun recreateTestDatabase() {
        val migrations = Flyway.configure().dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            .cleanDisabled(false).load()
        migrations.clean()
        migrations.migrate()
        current = LocalDate.parse(value("SELECT date_trunc('month', now() AT TIME ZONE 'UTC')::date"))
    }

    @Test
    fun `closure checks jobs reviews application and both coordinates of pending messages`() {
        val m = current.minusMonths(4)
        val id = seedOldRequest(m)
        val resultEvent = insertEvent(m, id, "RecommendationFinished")
        val startedEvent = insertEvent(m, id, "RecommendationStarted")
        val unrelated = insertEvent(current, id, "RecommendationFinished")

        assertThat(step()).isEqualTo("blocked/unfinished_request")
        execute("UPDATE recommendation_request SET state = 'RUNNING'")
        assertThat(step()).isEqualTo("blocked/unfinished_request")
        execute("UPDATE recommendation_request SET state = 'UNKNOWN', result_event_id = '$resultEvent'")
        assertThat(step()).isEqualTo("blocked/awaiting_application")
        execute("UPDATE recommendation_request SET applied_at = now()")
        assertThat(step()).isEqualTo("blocked/unreviewed_unknown")
        execute("UPDATE recommendation_request SET reviewed_at = now()")
        assertThat(step()).isEqualTo("blocked/unpublished_event")
        execute("UPDATE outbox_event SET published_at = now() WHERE event_id = '$resultEvent'")
        assertThat(step()).isEqualTo("blocked/unpublished_event")
        assertThat(barrier()).isEqualTo(m.toString())

        execute("UPDATE outbox_event SET published_at = now() WHERE event_id = '$startedEvent'")
        assertThat(step()).isEqualTo("closed")
        assertThat(step()).isEqualTo("removed")
        assertThat(step()).isEqualTo("ready")
        assertThat(value("SELECT count(*) FROM outbox_event")).isEqualTo("3")
        assertThat(value("SELECT published_at IS NULL FROM outbox_event WHERE event_id = '$unrelated'")).isEqualTo("t")
    }

    @Test
    fun `committed barrier rejects replays before deletion and another replica completes cleanup after interruption`() {
        val m = current.minusMonths(4)
        val id = seedOldRequest(m)
        execute("UPDATE recommendation_request SET state = 'SUCCEEDED', applied_at = now()")

        connection().use { db ->
            db.autoCommit = false
            val savepoint = db.setSavepoint()
            assertThat(db.value(stepSql)).isEqualTo("closed")
            db.releaseSavepoint(savepoint)
            assertThatThrownBy { db.value(stepSql) }.isInstanceOf(SQLException::class.java)
                .hasMessageContaining("Commit the retention barrier")
            db.rollback()
        }
        assertThat(barrier()).isEqualTo(m.toString())
        assertThat(step()).isEqualTo("closed")
        assertThat(exists(m)).isTrue()
        assertThat(store.accept(command(m, id)).block()).isEqualTo(RequestAcceptance.REQUEST_PERIOD_CLOSED)

        PartitionMaintenance(database).maintain()
        assertThat(exists(m)).isFalse()
        assertThat(store.accept(command(m, id)).block()).isEqualTo(RequestAcceptance.REQUEST_PERIOD_CLOSED)
        assertThat(exists(m)).isFalse()
        assertThat(barrier()).isEqualTo(m.plusMonths(1).toString())
    }

    @Test
    fun `month boundary and concurrent catchup after a long outage recreate the open window`() {
        val oldest = current.minusMonths(16)
        seedOldRequest(oldest)
        for (offset in -3L..1L) execute("DROP TABLE ${name(current.plusMonths(offset))}")

        assertThat(step()).isEqualTo("blocked/unfinished_request")
        assertThat(barrier()).isEqualTo(oldest.toString())
        for (offset in -16L..1L) assertThat(exists(current.plusMonths(offset))).isTrue()
        assertThat(store.accept(command(current.plusMonths(1))).block()).isEqualTo(RequestAcceptance.FUTURE_REQUEST_PERIOD)
        assertThat(store.accept(command(current.minusMonths(6))).block()).isEqualTo(RequestAcceptance.ACCEPTED)
        execute("UPDATE recommendation_request SET state = 'FAILED', applied_at = now()")

        val replicas = List(2) { CompletableFuture.runAsync { PartitionMaintenance(database).maintain() } }
        CompletableFuture.allOf(*replicas.toTypedArray()).get(30, TimeUnit.SECONDS)
        assertThat(barrier()).isEqualTo(current.minusMonths(3).toString())
        assertThat(value("SELECT count(*) FROM pg_inherits WHERE inhparent = 'recommendation_request'::regclass"))
            .isEqualTo("5")
        for (offset in -3L..1L) assertThat(exists(current.plusMonths(offset))).isTrue()
        assertThat(store.accept(command(current.minusMonths(3))).block()).isEqualTo(RequestAcceptance.ACCEPTED)
        assertThat(store.accept(command(current.minusMonths(4))).block()).isEqualTo(RequestAcceptance.REQUEST_PERIOD_CLOSED)
    }

    @Test
    fun `maintenance waits for an in-flight admission then observes its newly committed work`() {
        val m = current.minusMonths(4)
        openOldMonth(m)
        connection().use { writer ->
            writer.autoCommit = false
            writer.value("SELECT closed_before_month FROM request_retention FOR SHARE")
            writer.createStatement().use { it.execute(insertRequest(m, UUID.randomUUID())) }
            connection().use { maintainer ->
                val pid = maintainer.value("SELECT pg_backend_pid()")
                val maintenance = CompletableFuture.supplyAsync { maintainer.value(stepSql) }
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                while (value("SELECT EXISTS(SELECT FROM pg_stat_activity WHERE pid = $pid AND wait_event_type = 'Lock')") != "t") {
                    check(System.nanoTime() < deadline) { "Maintenance did not wait for admission" }
                    Thread.sleep(20)
                }
                writer.commit()
                assertThat(maintenance.get(10, TimeUnit.SECONDS)).isEqualTo("blocked/unfinished_request")
                assertThat(barrier()).isEqualTo(m.toString())
            }
        }
    }

    @Test
    fun `an unrelated table with the expected child name is never dropped`() {
        val m = current.minusMonths(4)
        openOldMonth(m)
        assertThat(step()).isEqualTo("closed")
        execute("ALTER TABLE ${name(m)} RENAME TO renamed_partition")
        execute("CREATE TABLE ${name(m)} (sentinel integer)")
        assertThatThrownBy { step() }.isInstanceOf(SQLException::class.java)
            .hasMessageContaining("Unexpected partition")
        assertThat(value("SELECT pending_drop_xid IS NOT NULL FROM request_retention")).isEqualTo("t")
        assertThat(value("SELECT to_regclass('${name(m)}') IS NOT NULL")).isEqualTo("t")
        assertThat(value("SELECT to_regclass('renamed_partition') IS NOT NULL")).isEqualTo("t")
    }

    private fun command(m: LocalDate, id: UUID = UUID.randomUUID()): RecommendationRequested {
        val accepted = m.atStartOfDay().toInstant(ZoneOffset.UTC)
        val candidate = RecommendationCandidate(1, "Shirt", "A", "Shirts", "Blue", "Cotton", 1,
            UUID.randomUUID(), "image/jpeg", 42)
        return RecommendationRequested(m, id, 7, 1, SelectionGoal.OUTFIT, accepted, accepted.plusSeconds(900),
            RecommendationInput(ItemSelectionWeather(BigDecimal.ONE, BigDecimal.ZERO, "NONE", "None"), listOf(candidate)))
    }

    private fun seedOldRequest(m: LocalDate): UUID {
        openOldMonth(m)
        return UUID.randomUUID().also { execute(insertRequest(m, it)) }
    }

    private fun openOldMonth(m: LocalDate) {
        execute("CREATE TABLE ${name(m)} PARTITION OF recommendation_request FOR VALUES FROM ('$m') TO ('${m.plusMonths(1)}')")
        execute("UPDATE request_retention SET closed_before_month = '$m'")
    }

    private fun insertRequest(m: LocalDate, id: UUID) = """
        INSERT INTO recommendation_request (request_month, request_id, owner_id, input_version, input, goal, accepted_at, start_deadline)
        VALUES ('$m', '$id', 7, 1, '{}', 'OUTFIT', now(), now())
    """

    private fun insertEvent(m: LocalDate, id: UUID, type: String): UUID = UUID.randomUUID().also { event ->
        execute("""
            INSERT INTO outbox_event(event_id, type, aggregate_id, payload) VALUES
                ('$event', '$type', '$id', '{"requestMonth":"$m","requestId":"$id"}')
        """)
    }

    private fun name(m: LocalDate) = "recommendation_request_${m.toString().take(7).replace('-', '_')}"
    private fun exists(m: LocalDate) = value("SELECT recommendation_partition_exists('$m')") == "t"
    private fun barrier() = value("SELECT closed_before_month FROM request_retention")
    private val stepSql = "SELECT concat_ws('/', action, reason) FROM maintain_recommendation_partitions()"
    private fun step() = value(stepSql)
    private fun value(sql: String) = connection().use { it.value(sql) }
    private fun Connection.value(sql: String): String = createStatement().use { statement ->
        statement.executeQuery(sql).use { rows -> check(rows.next()); rows.getString(1) }
    }
    private fun execute(sql: String) = connection().use { db -> db.createStatement().use { it.execute(sql) } }
    private fun connection() = DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password)

    companion object {
        private val postgres = PostgreSQLContainer("postgres:18-alpine").apply { start() }

        @JvmStatic
        @AfterAll
        fun stopDatabase() = postgres.stop()
    }
}
