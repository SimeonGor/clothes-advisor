package ru.itmo.clothesadvisor.recommendationservice.messaging

import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.postgresql.PostgreSQLContainer
import ru.itmo.clothesadvisor.recommendationservice.request.RecommendationRequestStore

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test", "request-store-test")
class AiJobFinalizedTests {
    @Autowired private lateinit var db: DatabaseClient
    @Autowired private lateinit var context: ApplicationContext
    @Autowired private lateinit var store: RecommendationRequestStore
    @Autowired private lateinit var rejections: DeliveryRejections

    @Test
    fun `ack applies once when provider success leads to wardrobe rejection`() {
        // given
        assertThat(context.getBeansOfType(AiJobFinalizedListener::class.java)).isEmpty()
        val listener = AiJobFinalizedListener(store, rejections)
        val id = request("SUCCEEDED")
        // when
        listener.receive(ack(id, "FAILED"))
        val first = value("SELECT applied_at::text || '/' || version::text FROM recommendation_request WHERE request_id='$id'")
        listener.receive(ack(id, "FAILED"))
        // then
        assertThat(value("SELECT applied_at::text || '/' || version::text FROM recommendation_request WHERE request_id='$id'")).isEqualTo(first)
        val conflict = ack(id, "SUCCEEDED")
        listener.receive(conflict)
        assertThat(value("SELECT application_outcome FROM recommendation_request WHERE request_id='$id'")).isEqualTo("FAILED")
        assertThat(reason(conflict)).isEqualTo("finalization_conflict")
    }

    @Test
    fun `wrong period ownership state and key never acknowledge another operation`() {
        // given
        val id = request("UNKNOWN")
        val listener = AiJobFinalizedListener(store, rejections)
        for (bad in listOf(ack(id, "SUCCEEDED"), ack(id, "UNKNOWN", actor = 8),
            ack(id, "UNKNOWN", period = month().minusMonths(1)), ack(id, "UNKNOWN", key = "wrong"),
            ack(id, "UNKNOWN", version = 3),
        )) {
            // when
            listener.receive(bad)
            // then
            assertThat(reason(bad)).isIn("invalid_finalization", "finalization_conflict")
        }
        assertThat(value("SELECT count(applied_at) FROM recommendation_request WHERE request_id='$id'")).isEqualTo("0")
        listener.receive(ack(id, "UNKNOWN"))
        assertThat(value("SELECT application_outcome FROM recommendation_request WHERE request_id='$id'")).isEqualTo("UNKNOWN")
        val pending = request("RUNNING")
        val early = ack(pending, "FAILED")
        listener.receive(early)
        assertThat(reason(early)).isEqualTo("finalization_conflict")
        assertThat(value("SELECT count(applied_at) FROM recommendation_request WHERE request_id='$pending'")).isEqualTo("0")
    }

    private fun request(state: String): UUID {
        val id = UUID.randomUUID()
        db.sql("""INSERT INTO recommendation_request (request_month,request_id,owner_id,state,input_version,input,goal,accepted_at,start_deadline)
            VALUES (:month,:id,7,:state,1,'{}','OUTFIT',clock_timestamp(),clock_timestamp())""")
            .bind("month", month()).bind("id", id).bind("state", state).fetch().rowsUpdated().block()
        return id
    }

    private fun reason(record: ConsumerRecord<*, *>) = value("SELECT reason FROM kafka_delivery_rejection WHERE record_offset=${record.offset()}")
    private fun value(sql: String) = db.sql(sql).map { row, _ -> requireNotNull(row.get(0)).toString() }.one().block()!!
    private fun month() = LocalDate.parse(value("SELECT date_trunc('month',current_timestamp AT TIME ZONE 'UTC')::date"))
    private fun ack(id: UUID, outcome: String, period: LocalDate = month(), actor: Long = 7, key: String = "$period/$id",
                    version: Int = 1): ConsumerRecord<String, ByteArray> {
        val event = """{"eventId":"${UUID.randomUUID()}","occurredAt":"2026-11-01T00:00:00Z","schemaVersion":$version,
            "aggregateId":"$id","eventType":"AiJobFinalized","payload":{
            "requestId":"$id","requestMonth":"$period","ownerId":$actor,"applicationOutcome":"$outcome"}}"""
        return ConsumerRecord("ai-jobs.events", 0, offsets.incrementAndGet(), key, event.toByteArray())
    }

    companion object {
        private val offsets = AtomicLong()
        private val postgres = PostgreSQLContainer("postgres:18-alpine").apply { start() }
        @JvmStatic
        @DynamicPropertySource
        fun postgresProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.r2dbc.url") { "r2dbc:postgresql://${postgres.host}:${postgres.firstMappedPort}/${postgres.databaseName}" }
            registry.add("spring.r2dbc.username", postgres::getUsername)
            registry.add("spring.r2dbc.password", postgres::getPassword)
            registry.add("spring.flyway.url", postgres::getJdbcUrl)
            registry.add("spring.flyway.user", postgres::getUsername)
            registry.add("spring.flyway.password", postgres::getPassword)
        }
    }
}
