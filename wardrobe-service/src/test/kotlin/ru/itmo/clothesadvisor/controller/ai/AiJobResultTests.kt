package ru.itmo.clothesadvisor.controller.ai

import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.verifyNoInteractions
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.bean.override.mockito.MockitoBean
import ru.itmo.clothesadvisor.client.recommendation.RecommendationClient
import ru.itmo.clothesadvisor.config.AccountBackedIntegrationTest
import ru.itmo.clothesadvisor.config.insertItem
import ru.itmo.clothesadvisor.config.insertOutfit
import ru.itmo.clothesadvisor.config.insertUser
import ru.itmo.clothesadvisor.dto.ai.*
import ru.itmo.clothesadvisor.dto.outfit.OutfitWeatherDto
import ru.itmo.clothesadvisor.dto.wardrobe.CreateWardrobeItemRequest
import ru.itmo.clothesadvisor.messaging.AiJobResultListener
import ru.itmo.clothesadvisor.model.access.AccountUnavailableException
import ru.itmo.clothesadvisor.model.user.UserRole
import ru.itmo.clothesadvisor.service.ai.AiJobResults
import ru.itmo.clothesadvisor.service.ai.AiJobService

@SpringBootTest
class AiJobResultTests : AccountBackedIntegrationTest() {
    @Autowired private lateinit var jdbc: JdbcTemplate
    @Autowired private lateinit var jobs: AiJobService
    @Autowired private lateinit var results: AiJobResults
    @Autowired private lateinit var context: ApplicationContext
    @MockitoBean private lateinit var provider: RecommendationClient
    private var owner = 0L
    private var shirt = 0L
    private var coat = 0L
    private val weather = OutfitWeatherDto(10.toBigDecimal(), 1, 0.toBigDecimal())
    private val offsets = AtomicLong()

    @BeforeEach
    fun wardrobe() {
        owner = jdbc.insertUser("user", "unused", UserRole.USER).id
        shirt = item("Shirt")
        coat = item("Coat")
        jdbc.update("DELETE FROM kafka_delivery_rejection")
    }

    @AfterEach
    fun noAdditionalCall() = verifyNoInteractions(provider)

    @Test
    fun `concurrent finished and late started produce one capsule and stable readiness after deletion`() {
        val id = jobs.capsule(owner, CreateAiCapsuleRequest("Office", weather), null).jobId
        val success = record(id)
        jdbc.update("UPDATE ai_job SET start_deadline=clock_timestamp()-interval '1 hour'")
        val ready = CountDownLatch(1)
        Executors.newFixedThreadPool(2).use { pool ->
            val deliveries = List(2) { pool.submit { ready.await(); results.receive(success) } }
            ready.countDown()
            deliveries.forEach { it.get(15, TimeUnit.SECONDS) }
        }
        val initial = value("SELECT event_id::text || '/' || created_at::text FROM outbox_event WHERE type='AiJobFinalized'")
        val terminal = value("SELECT modified_at::text || '/' || version::text FROM ai_job")
        assertThat(value("SELECT count(*) FROM capsule")).isEqualTo("1")
        assertThat(value("SELECT status FROM ai_job")).isEqualTo("SUCCEEDED")
        jdbc.update("DELETE FROM capsule")
        jdbc.update("DELETE FROM outbox_event WHERE type='AiJobFinalized'")
        jdbc.update("UPDATE app_user SET status='BLOCKED'")
        results.receive(success)
        results.receive(record(id, type = "RecommendationStarted", fields = ""))
        assertThat(value("SELECT count(*) FROM capsule")).isEqualTo("0")
        assertThat(value("SELECT event_id::text || '/' || created_at::text FROM outbox_event WHERE type='AiJobFinalized'")).isEqualTo(initial)
        assertThat(value("SELECT modified_at::text || '/' || version::text FROM ai_job")).isEqualTo(terminal)
        results.receive(record(id, fields = ""","outcome":"FAILED","errorCode":"selection_unavailable""""))
        assertThat(value("SELECT reason FROM kafka_delivery_rejection")).isEqualTo("terminal_result_conflict")
        assertThat(context.getBeansOfType(AiJobResultListener::class.java)).isEmpty()
    }

    @Test
    fun `successful replacement preserves source and creates a new composition`() {
        val extra = item("Shoes")
        val source = jdbc.insertOutfit(owner, listOf(shirt, coat))
        val id = jobs.replacement(owner, source, CreateAiOutfitReplacementRequest("Replacement", listOf(coat)), null).jobId
        results.receive(record(id, type = "RecommendationStarted", fields = ""))
        assertThat(value("SELECT status FROM ai_job")).isEqualTo("RUNNING")
        results.receive(record(id, ids = "$shirt,$extra"))
        assertThat(value("SELECT count(*) FROM outfit")).isEqualTo("2")
        assertThat(value("SELECT count(*) FROM outfit_item WHERE outfit_id=$source AND wardrobe_item_id=$coat")).isEqualTo("1")
        assertThat(value("SELECT count(*) FROM outfit_item i JOIN ai_job j ON i.outfit_id=j.result_id WHERE wardrobe_item_id=$extra")).isEqualTo("1")
    }

    @Test
    fun `replacement needs both the original outfit and the retained items`() {
        val extra = item("Scarf")
        val source = jdbc.insertOutfit(owner, listOf(shirt, coat))
        fun replace() = jobs.replacement(owner, source,
            CreateAiOutfitReplacementRequest("Replacement", listOf(coat)), null).jobId
        val invalid = replace()
        results.receive(record(invalid, ids = "$extra"))
        assertThat(value("SELECT error_code FROM ai_job WHERE job_id='$invalid'")).isEqualTo("invalid_ai_response")
        val missing = replace()
        jdbc.update("DELETE FROM outfit WHERE id=?", source)
        results.receive(record(missing, ids = "$shirt,$extra"))
        assertThat(value("SELECT error_code FROM ai_job WHERE job_id='$missing'")).isEqualTo("source_changed")
        assertThat(value("SELECT count(*) FROM outfit")).isEqualTo("0")
    }

    @Test
    fun `mismatching period or owner and malformed envelopes have no business effect`() {
        val id = accept()
        val month = month(id)
        results.receive(record(id, period = month.minusMonths(1)))
        results.receive(record(id, actor = owner + 1))
        results.receive(record(id, key = "other"))
        results.receive(record(id, schema = 2))
        results.receive(ConsumerRecord("ai-selection.execution-events", 0, 999L, "$month/$id", "private".toByteArray()))
        assertThat(value("SELECT status FROM ai_job")).isEqualTo("QUEUED")
        assertThat(value("SELECT count(*) FROM kafka_delivery_rejection")).isEqualTo("5")
        assertThat(value("SELECT count(*) FROM kafka_delivery_rejection WHERE reason LIKE '%private%'")).isEqualTo("0")
    }

    @Test
    fun `failed and unknown remain terminal without creating resources`() {
        for ((outcome, code) in listOf("FAILED" to "queue_expired", "UNKNOWN" to "ai_outcome_unknown")) {
            val id = accept()
            results.receive(record(id, fields = ""","outcome":"$outcome","errorCode":"$code""""))
            results.receive(record(id, type = "RecommendationStarted", fields = ""))
            assertThat(value("SELECT status || '/' || error_code FROM ai_job WHERE job_id='$id'")).isEqualTo("$outcome/$code")
            assertThat(value("SELECT payload->>'applicationOutcome' FROM outbox_event WHERE type='AiJobFinalized' AND aggregate_id='$id'"))
                .isEqualTo(outcome)
        }
        assertThat(value("SELECT count(*) FROM outfit")).isEqualTo("0")
    }

    @Test
    fun `account outage is retriable and revocation prevents saving`() {
        val id = accept()
        val result = record(id)
        doThrow(AccountUnavailableException()).`when`(accounts).getUser(owner)
        assertThatThrownBy { results.receive(result) }.isInstanceOf(AccountUnavailableException::class.java)
        assertThat(value("SELECT status FROM ai_job")).isEqualTo("QUEUED")
        configureAccountFixture()
        jdbc.update("UPDATE app_user SET status='BLOCKED' WHERE id=?", owner)
        results.receive(result)
        assertThat(value("SELECT error_code FROM ai_job")).isEqualTo("access_revoked")
        assertThat(value("SELECT count(*) FROM outfit")).isEqualTo("0")
    }

    @Test
    fun `all original candidates are checked including unselected unavailable items or missing photos`() {
        val id = accept()
        jdbc.update("UPDATE wardrobe_item SET availability='STORED' WHERE id=?", coat)
        results.receive(record(id))
        assertThat(value("SELECT error_code FROM ai_job WHERE job_id='$id'")).isEqualTo("source_changed")
        jdbc.update("UPDATE wardrobe_item SET availability='AVAILABLE' WHERE id=?", coat)
        val second = accept()
        jdbc.update("DELETE FROM wardrobe_item_photo WHERE wardrobe_item_id=?", coat)
        results.receive(record(second))
        assertThat(value("SELECT error_code FROM ai_job WHERE job_id='$second'")).isEqualTo("source_changed")
        val third = accept()
        results.receive(record(third, ids = "$shirt,$shirt"))
        assertThat(value("SELECT error_code FROM ai_job WHERE job_id='$third'")).isEqualTo("invalid_ai_response")
        assertThat(value("SELECT count(*) FROM outfit")).isEqualTo("0")
    }

    @Test
    fun `outbox insert failure rolls back resource job and receipt and replay succeeds`() {
        val id = accept()
        val event = record(id)
        jdbc.execute("ALTER TABLE outbox_event ADD CONSTRAINT reject_ready CHECK (type <> 'AiJobFinalized')")
        try {
            assertThatThrownBy { results.receive(event) }.isInstanceOf(DataAccessException::class.java)
            assertThat(value("SELECT status FROM ai_job")).isEqualTo("QUEUED")
            assertThat(value("SELECT count(*) FROM outfit")).isEqualTo("0")
        } finally {
            jdbc.execute("ALTER TABLE outbox_event DROP CONSTRAINT reject_ready")
        }
        results.receive(event)
        assertThat(value("SELECT status FROM ai_job")).isEqualTo("SUCCEEDED")
        assertThat(value("SELECT count(*) FROM outfit")).isEqualTo("1")
    }

    private fun item(name: String): Long {
        val id = jdbc.insertItem(owner, CreateWardrobeItemRequest(name, 1, "blue", "cotton")).id
        jdbc.update("INSERT INTO wardrobe_item_photo (wardrobe_item_id,file_id,content_type,size_bytes) VALUES (?,?,'image/png',42)",
            id, UUID.randomUUID())
        return id
    }

    private fun accept(): UUID = jobs.outfit(owner, CreateAiOutfitRequest("Daily", weather), null).jobId
    private fun value(sql: String) = jdbc.queryForObject(sql, String::class.java)!!
    private fun month(id: UUID) = LocalDate.parse(value("SELECT request_month::text FROM ai_job WHERE job_id='$id'"))
    private fun record(id: UUID, ids: String = "$shirt", period: LocalDate = month(id), actor: Long = owner,
                       key: String = "$period/$id", type: String = "RecommendationFinished", schema: Int = 1,
                       fields: String = ""","outcome":"SUCCEEDED","itemIds":[$ids],"explanation":"Good"""",
    ): ConsumerRecord<String, ByteArray> {
        val event = """{"eventId":"${UUID.randomUUID()}","occurredAt":"2026-11-01T00:00:00Z","schemaVersion":$schema,
            "aggregateId":"$id","eventType":"$type","payload":{"requestMonth":"$period","requestId":"$id","ownerId":$actor$fields}}"""
        return ConsumerRecord("ai-selection.execution-events", 0, offsets.incrementAndGet(), key, event.toByteArray())
    }
}
