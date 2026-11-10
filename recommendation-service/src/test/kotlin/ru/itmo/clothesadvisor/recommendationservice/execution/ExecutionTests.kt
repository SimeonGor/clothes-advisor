package ru.itmo.clothesadvisor.recommendationservice.execution

import com.sun.net.httpserver.HttpServer
import java.math.BigDecimal
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.nio.file.Path
import java.sql.DriverManager
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.web.reactive.function.client.WebClient
import org.testcontainers.postgresql.PostgreSQLContainer
import ru.itmo.clothesadvisor.client.ai.ChatGptCredentialFixture
import ru.itmo.clothesadvisor.client.ai.OpenAiSelectionClient
import ru.itmo.clothesadvisor.config.SelectionPromptSettings
import ru.itmo.clothesadvisor.dto.ai.ItemSelectionResponse
import ru.itmo.clothesadvisor.dto.ai.ItemSelectionWeather
import ru.itmo.clothesadvisor.observability.DurableTrace
import ru.itmo.clothesadvisor.recommendationservice.model.ai.SelectionGoal
import ru.itmo.clothesadvisor.recommendationservice.request.*

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test", "request-store-test")
internal class ExecutionTests {
    @Autowired private lateinit var store: ExecutionStore
    @Autowired private lateinit var ingress: RecommendationRequestStore
    @Autowired private lateinit var trace: DurableTrace
    @Autowired private lateinit var webClients: WebClient.Builder
    @TempDir lateinit var directory: Path

    private val file = UUID.randomUUID()
    private val calls = AtomicInteger()
    private val validations = AtomicInteger()
    private val executor = Executors.newVirtualThreadPerTaskExecutor()
    private lateinit var server: HttpServer
    private lateinit var client: OpenAiSelectionClient
    private lateinit var worker: ExecutionWorker
    private var response = "valid"
    private var validation: () -> Int = { 204 }
    private var provider: () -> Unit = {}
    @Volatile private var sent = ""
    @Volatile private var fileTrace: String? = null
    @Volatile private var validationTrace: String? = null
    @Volatile private var providerTrace: String? = null

    @BeforeEach
    fun setUp() {
        sql("TRUNCATE recommendation_request, outbox_event")
        sql("DELETE FROM ai_execution_slot")
        sql("INSERT INTO ai_execution_slot(connection_id, state) VALUES ('personal','FREE')")
        val credentials = ChatGptCredentialFixture(directory)
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            this.executor = this@ExecutionTests.executor
            createContext("/api/files/$file") { exchange ->
                fileTrace = exchange.requestHeaders.getFirst("traceparent")
                exchange.responseHeaders.add("Content-Type", "image/png")
                exchange.sendResponseHeaders(200, 3)
                exchange.responseBody.use { it.write(byteArrayOf(1, 2, 3)) }
            }
            createContext("/internal/ai-jobs/") { exchange ->
                validationTrace = exchange.requestHeaders.getFirst("traceparent")
                check(exchange.requestURI.path.endsWith("/validate-input"))
                val body = credentials.mapper.readTree(exchange.requestBody.readAllBytes())
                check(body.has("requestMonth"))
                validations.incrementAndGet()
                exchange.sendResponseHeaders(validation(), -1)
                exchange.close()
            }
            createContext("/responses") { exchange ->
                exchange.use {
                    providerTrace = exchange.requestHeaders.getFirst("traceparent")
                    calls.incrementAndGet()
                    sent = exchange.requestBody.bufferedReader().readText()
                    provider()
                    val ids = if (response == "invalid") "99" else "1"
                    val text = credentials.mapper.writeValueAsString("""{"itemIds":[$ids],"explanation":"OK"}""")
                    val body = when (response) {
                        "refusal" -> """data: {"type":"response.refusal.done"}"""
                        "timeout" -> { Thread.sleep(2000); "" }
                        "disconnect" -> """data: {"type":"response.created"}"""
                        "malformed" -> "data: {"
                        "oversized" -> "data: " + "x".repeat(1_048_576)
                        "contradiction" -> "event: response.failed\ndata: {\"type\":\"response.completed\"}"
                        "nonterminal" -> """data: {"type":"response.completed","response":{"status":"in_progress"}}"""
                        "delta" -> """data: {"type":"response.output_text.delta","delta":"partial"}"""
                        "incomplete" -> """data: {"type":"response.incomplete","response":{"status":"incomplete"}}"""
                        "failed" -> """data: {"type":"response.failed","response":{"status":"failed"}}"""
                        else -> """data: {"type":"response.completed","response":{"status":"completed","output":[{"type":"message","role":"assistant","status":"completed","content":[{"type":"output_text","text":$text}]}]}}"""
                    }
                    val bytes = if (response == "invalid-utf8") byteArrayOf(0xc3.toByte(), 0x28) else (body + "\n\n").toByteArray()
                    exchange.responseHeaders.add("Content-Type", if (response == "wrong-media") "text/html" else "text/event-stream")
                    exchange.sendResponseHeaders(200, bytes.size.toLong())
                    exchange.responseBody.use { it.write(bytes) }
                }
            }
            start()
        }
        val uri = "http://127.0.0.1:${server.address.port}"
        val http = webClients.clone().baseUrl(uri).build()
        val prompt = SelectionPromptSettings("outfit", "8", "Trusted prompt")
        client = OpenAiSelectionClient(HttpClient.newHttpClient(), credentials.mapper, credentials.reader(),
            "test-model", URI("$uri/responses"), Duration.ofSeconds(1), prompt, prompt)
        worker = ExecutionWorker(store, ExecutionInputs(http, http), client, trace)
    }

    @AfterEach
    fun tearDown() {
        worker.stop()
        server.stop(0)
        executor.close()
        client.close()
    }

    @Test
    fun `sync and async replicas share the fenced slot and recovery preserves the execution boundary`() {
        val claimants = (1..12).map { executor.submit<UUID?> { store.claimSync() } }
        val old = claimants.mapNotNull { it.get() }.single()
        val job = enqueue()
        assertThat(store.claimAsync()).isNull()
        assertThat(scalar("SELECT recover_ai_execution('$old')")).isEqualTo("t")
        assertThat(store.claimSync()).isNull() // Ready background work has priority.
        val winner = (1..12).map { executor.submit<ExecutionJob?> { store.claimAsync() } }
            .mapNotNull { it.get() }.single()
        assertThat(winner.id).isEqualTo(job.requestId)
        assertThat(store.startSync(old)).isFalse()
        assertThat(store.finish(old, "FAILED")).isFalse()
        assertThat(store.startAsync(winner, SelectionInstructions("model", 2, "prompt"))).isTrue()
        assertThat(scalar("SELECT recover_ai_execution('${winner.execution}')")).isEqualTo("t")
        assertThat(store.finish(winner.execution, "SUCCEEDED", response = ItemSelectionResponse(listOf(1L), "Late"))).isFalse()
        assertThat(state(job)).isEqualTo("UNKNOWN")
        assertThat(store.claimSync()).isNull()
        val expired = enqueue(Instant.now().minusSeconds(100), 1)
        worker.tick()
        assertThat(scalar("SELECT error_code FROM recommendation_request WHERE request_id = '${expired.requestId}'")).isEqualTo("queue_expired")
        assertThat(scalar("SELECT resume_ai_execution('$old')")).isEqualTo("f")
        assertThat(scalar("SELECT resume_ai_execution('${winner.execution}')")).isEqualTo("t")
        assertThat(state(job)).isEqualTo("UNKNOWN")
        assertThat(calls).hasValue(0)
        assertThat(store.claimSync()).isNotNull()
    }

    @Test
    fun `worker commits pinned settings before network and replays no completed execution`() {
        val traceId = "0123456789abcdef0123456789abcdef"
        val parent = "00-$traceId-1020304050607080-01"
        val earlier = enqueue(Instant.now().minusSeconds(1), traceparent = parent)
        val later = enqueue()
        val phase = java.util.concurrent.atomic.AtomicReference<String>()
        provider = {
            // A separate session can acquire this lock while the provider is executing.
            phase.set(scalar("SELECT phase FROM ai_execution_slot FOR UPDATE NOWAIT"))
            sql("UPDATE recommendation_request SET start_deadline = now() WHERE request_id = '${earlier.requestId}'")
        }
        worker.tick()
        assertThat(phase.get()).isEqualTo("CALL_STARTED")
        assertThat(state(earlier)).isEqualTo("SUCCEEDED")
        assertThat(state(later)).isEqualTo("RECEIVED")
        val workerParent = scalar("SELECT execution_traceparent FROM recommendation_request WHERE request_id='${earlier.requestId}'")
        assertThat(workerParent).matches("00-$traceId-[0-9a-f]{16}-01").isNotEqualTo(parent)
        assertThat(fileTrace).matches("00-$traceId-[0-9a-f]{16}-01").isNotEqualTo(workerParent)
        assertThat(validationTrace).matches("00-$traceId-[0-9a-f]{16}-01").isNotEqualTo(workerParent).isNotEqualTo(fileTrace)
        assertThat(providerTrace).isNull()
        assertThat(sent).contains("Trusted prompt", "test-model", "data:image/png;base64,AQID").doesNotContain(file.toString())
        assertThat(scalar("""
            SELECT count(*) FROM recommendation_request r JOIN outbox_event o ON o.event_id = r.result_event_id
            WHERE r.request_id = '${earlier.requestId}' AND r.completed_at = o.created_at
                AND r.model = 'test-model' AND r.prompt_version = 8 AND r.prompt_text = 'Trusted prompt'
                AND o.payload->'itemIds' = '[1]'::jsonb AND o.payload->>'requestMonth' = r.request_month::text
                AND r.result = '{"itemIds":[1],"explanation":"OK"}'::jsonb
        """)).isEqualTo("1")
        // Restore the immutable command deadline changed by the test injection, then redeliver.
        sql("UPDATE recommendation_request SET start_deadline = '${earlier.startDeadline}' WHERE request_id = '${earlier.requestId}'")
        assertThat(ingress.accept(earlier).block()).isEqualTo(RequestAcceptance.DUPLICATE)
        assertThat(scalar("SELECT traceparent FROM recommendation_request WHERE request_id='${earlier.requestId}'")).isEqualTo(parent)
        sql("DELETE FROM recommendation_request WHERE request_id = '${later.requestId}'")
        worker.tick()
        assertThat(calls).hasValue(1)
    }

    @ParameterizedTest
    @CsvSource("invalid,FAILED,invalid_ai_response,FREE", "refusal,FAILED,selection_unavailable,FREE",
        "disconnect,UNKNOWN,ai_outcome_unknown,PAUSED", "timeout,UNKNOWN,ai_outcome_unknown,PAUSED",
        "malformed,UNKNOWN,ai_outcome_unknown,PAUSED", "oversized,UNKNOWN,ai_outcome_unknown,PAUSED",
        "contradiction,UNKNOWN,ai_outcome_unknown,PAUSED", "nonterminal,UNKNOWN,ai_outcome_unknown,PAUSED",
        "delta,UNKNOWN,ai_outcome_unknown,PAUSED", "invalid-utf8,UNKNOWN,ai_outcome_unknown,PAUSED",
        "wrong-media,UNKNOWN,ai_outcome_unknown,PAUSED",
        "incomplete,FAILED,invalid_ai_response,FREE", "failed,FAILED,selection_unavailable,FREE")
    fun `provider terminal outcomes are durable and never call AI twice`(scenario: String, outcome: String, code: String, slot: String) {
        response = scenario
        val job = enqueue()
        worker.tick()
        worker.tick()
        assertThat(calls).hasValue(1)
        assertThat(state(job)).isEqualTo(outcome)
        assertThat(scalar("SELECT state FROM ai_execution_slot")).isEqualTo(slot)
        assertThat(scalar("SELECT payload->>'errorCode' FROM outbox_event WHERE type = 'RecommendationFinished'")).isEqualTo(code)
        assertThat(scalar("SELECT count(*) FROM outbox_event")).isEqualTo("2")
    }

    @Test
    fun `temporary validation failure retries safely and revoked access prevents provider call`() {
        validation = { if (validations.get() == 1) 503 else 403 }
        enqueue()
        worker.tick()
        assertThat(validations).hasValue(2)
        assertThat(calls).hasValue(0)
        assertThat(scalar("SELECT error_code FROM recommendation_request")).isEqualTo("access_revoked")
    }

    @Test
    fun `deadline crossed after validation finishes without a started event`() {
        val job = enqueue()
        validation = { sql("UPDATE recommendation_request SET start_deadline = now()"); 204 }
        worker.tick()
        assertThat(calls).hasValue(0)
        assertThat(state(job)).isEqualTo("FAILED")
        assertThat(scalar("SELECT error_code FROM recommendation_request")).isEqualTo("queue_expired")
        assertThat(scalar("SELECT count(*) FROM outbox_event")).isEqualTo("1")
    }

    @Test
    fun `failed outbox insertion rolls back terminal state and slot release`() {
        val command = enqueue()
        val job = store.claimAsync()!!
        assertThat(store.startAsync(job, SelectionInstructions("model", 2, "prompt"))).isTrue()
        sql("ALTER TABLE outbox_event ADD CONSTRAINT injected_failure CHECK (type <> 'RecommendationFinished')")
        try {
            assertThatThrownBy { store.finish(job.execution, "FAILED", "selection_unavailable") }.isInstanceOf(Exception::class.java)
            assertThat(state(command)).isEqualTo("RUNNING")
            assertThat(scalar("SELECT phase FROM ai_execution_slot")).isEqualTo("CALL_STARTED")
        } finally { sql("ALTER TABLE outbox_event DROP CONSTRAINT injected_failure") }
    }

    private fun enqueue(at: Instant = Instant.now(), lifetime: Long = 900, traceparent: String? = null): RecommendationRequested {
        val accepted = at.truncatedTo(ChronoUnit.MICROS)
        val month = LocalDate.ofInstant(accepted, ZoneOffset.UTC).withDayOfMonth(1)
        val request = RecommendationRequested(month, UUID.randomUUID(), 17, 1, SelectionGoal.OUTFIT, accepted,
            accepted.plusSeconds(lifetime), RecommendationInput(
                ItemSelectionWeather(BigDecimal.ONE, BigDecimal.ZERO, "NONE", "None"),
                listOf(RecommendationCandidate(1, "Item", "TOP", "Shirt", "Red", "Cotton", 12, file, "image/png", 3)),
            ))
        assertThat(ingress.accept(request, traceparent).block()).isEqualTo(RequestAcceptance.ACCEPTED)
        return request
    }

    private fun state(request: RecommendationRequested) =
        scalar("SELECT state FROM recommendation_request WHERE request_id = '${request.requestId}' AND request_month = '${request.requestMonth}'")

    private fun sql(statement: String) = connection().use { db -> db.createStatement().use { it.execute(statement) } }
    private fun scalar(statement: String) = connection().use { db ->
        db.createStatement().use { it.executeQuery(statement).use { rows -> check(rows.next()); rows.getString(1) } }
    }
    private fun connection() = DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password)

    companion object {
        private val postgres = PostgreSQLContainer("postgres:18-alpine").apply { start() }
        @JvmStatic @DynamicPropertySource
        fun database(registry: DynamicPropertyRegistry) {
            registry.add("spring.r2dbc.url") { "r2dbc:postgresql://${postgres.host}:${postgres.firstMappedPort}/${postgres.databaseName}" }
            registry.add("spring.r2dbc.username", postgres::getUsername)
            registry.add("spring.r2dbc.password", postgres::getPassword)
            registry.add("spring.flyway.url", postgres::getJdbcUrl)
            registry.add("spring.flyway.user", postgres::getUsername)
            registry.add("spring.flyway.password", postgres::getPassword)
        }
        @JvmStatic @AfterAll fun stopDatabase() = postgres.stop()
    }
}
