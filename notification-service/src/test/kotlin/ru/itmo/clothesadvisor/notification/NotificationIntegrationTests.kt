package ru.itmo.clothesadvisor.notification

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.*
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.dao.DataAccessException
import org.springframework.r2dbc.core.DatabaseClient
import org.testcontainers.postgresql.PostgreSQLContainer
import tools.jackson.databind.json.JsonMapper

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class NotificationIntegrationTests {
    private val db = PostgreSQLContainer("postgres:18-alpine")
    private val account = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val accountState = AtomicReference("ACTIVE")
    private val http = HttpClient.newHttpClient()
    private val json = JsonMapper.builder().build()
    private lateinit var context: ConfigurableApplicationContext
    private lateinit var events: KafkaEvents
    private lateinit var sql: DatabaseClient
    private lateinit var base: String
    private val owner = TestJwt.sign("17", Instant.now())
    private val stylist = TestJwt.sign("23", Instant.now())

    @BeforeAll
    fun start() {
        db.start()
        account.createContext("/internal/users/") { exchange ->
            exchange.use {
                val id = it.requestURI.path.substringAfterLast("/")
                check(it.requestHeaders.getFirst("Authorization") == null)
                val state = accountState.get()
                val body = """{"id":$id,"login":"synthetic","role":"USER","status":"$state"}""".toByteArray()
                it.responseHeaders.add("Content-Type", "application/json")
                it.sendResponseHeaders(if (state == "OFFLINE") 503 else 200, body.size.toLong())
                it.responseBody.write(body)
            }
        }
        account.start()
        context = SpringApplicationBuilder(NotificationApplication::class.java).profiles("test").run(
            "--server.port=0", "--app.jwt.public-key=${TestJwt.publicKeyLocation}",
            "--spring.r2dbc.url=r2dbc:postgresql://${db.host}:${db.firstMappedPort}/${db.databaseName}",
            "--spring.r2dbc.username=${db.username}", "--spring.r2dbc.password=${db.password}",
            "--spring.flyway.url=${db.jdbcUrl}", "--spring.flyway.user=${db.username}", "--spring.flyway.password=${db.password}",
            "--spring.cloud.discovery.client.simple.instances.account-service[0].uri=http://127.0.0.1:${account.address.port}",
        )
        sql = context.getBean(DatabaseClient::class.java)
        events = context.getBean(KafkaEvents::class.java)
        base = "http://127.0.0.1:${context.environment.getRequiredProperty("local.server.port")}/api/me/notifications"
    }

    @BeforeEach
    fun reset() {
        sql.sql("TRUNCATE notification, notification_rejected_event").then().block()
        accountState.set("ACTIVE")
    }

    @AfterAll
    fun close() {
        if (::context.isInitialized) context.close()
        http.close()
        account.stop(0)
        db.stop()
    }

    private fun record(topic: String, type: String, fields: String, eventId: UUID = UUID.randomUUID(),
        at: String = "2026-01-01T00:00:00Z", version: Int = 1): ConsumerRecord<ByteArray, ByteArray> {
        val body = """{"eventId":"$eventId","eventType":"$type","schemaVersion":$version,"occurredAt":"$at",
            "aggregateId":"42","payload":{"ownerId":17,$fields}}"""
        return ConsumerRecord(topic, 0, 22, byteArrayOf(), body.toByteArray())
    }

    private fun request(path: String = "", token: String? = owner, method: String = "GET"): HttpResponse<String> {
        val builder = HttpRequest.newBuilder(URI.create("$base$path")).method(method, HttpRequest.BodyPublishers.noBody())
            .header("X-User-Id", "17")
        if (token != null) builder.header("Authorization", "Bearer $token")
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }

    @Test
    fun `concurrent delivery and replay after committed insert create one notification`() {
        val duplicatesBefore = duplicateCount()
        val record = record(REQUESTS, "StylistRequestAnswered", """"requestId":7,"privateNotes":"secret-note"""")
        val gate = CountDownLatch(1)
        Executors.newFixedThreadPool(8).use { pool ->
            val calls = (1..8).map {
                pool.submit(Callable {
                    check(gate.await(5, TimeUnit.SECONDS))
                    events.consume(record)
                })
            }
            gate.countDown()
            calls.forEach { it.get(15, TimeUnit.SECONDS) }
        }
        events.consume(record)
        assertThat(duplicateCount() - duplicatesBefore).isEqualTo(8.0)
        val page = request()
        assertThat(page.statusCode()).isEqualTo(200)
        assertThat(page.headers().firstValue("X-Total-Count")).hasValue("1")
        assertThat(page.body()).doesNotContain("secret-note", "sourceEvent", "recipient", "privateNotes")
        val item = json.readTree(page.body())[0]
        assertThat(item.path("resource").path("id").stringValue()).isEqualTo("7")
        assertThat(item.path("type").stringValue()).isEqualTo("StylistRequestAnswered")
        val id = item.path("id").longValue()
        assertThat(request("/unread-count").body()).isEqualTo("""{"count":1}""")
        assertThat(request(token = stylist).body()).isEqualTo("[]")
        assertThat(request("/$id/read", stylist, "PUT").statusCode()).isEqualTo(404)
        assertThat(request("/$id/read", method = "PUT").statusCode()).isEqualTo(204)
        val readAt = json.readTree(request().body())[0].path("readAt").stringValue()
        assertThat(readAt).isNotBlank()
        assertThat(request("/$id/read", method = "PUT").statusCode()).isEqualTo(204)
        assertThat(json.readTree(request().body())[0].path("readAt").stringValue()).isEqualTo(readAt)
        assertThat(request("/unread-count").body()).isEqualTo("""{"count":0}""")
    }

    private fun duplicateCount(): Double {
        val endpoint = base.substringBefore("/api/") + "/actuator/metrics/notification.duplicates"
        val response = http.send(HttpRequest.newBuilder(URI.create(endpoint)).GET().build(),
            HttpResponse.BodyHandlers.ofString())
        assertThat(response.statusCode()).isEqualTo(200)
        return json.readTree(response.body()).path("measurements").single().path("value").doubleValue()
    }

    @Test
    fun `failed insert escapes listener and redelivery after recovery commits exactly once`() {
        val record = record(OUTFITS, "StylistOutfitCreated", """"outfitId":31""")
        sql.sql("ALTER TABLE notification ADD CONSTRAINT simulate_failure CHECK (recipient_id <> 17)").then().block()
        try {
            assertThatThrownBy { events.consume(record) }.isInstanceOf(DataAccessException::class.java)
            assertThat(request().body()).isEqualTo("[]")
        } finally {
            sql.sql("ALTER TABLE notification DROP CONSTRAINT simulate_failure").then().block()
        }
        events.consume(record)
        events.consume(record)
        assertThat(request("/unread-count").body()).isEqualTo("""{"count":1}""")
    }

    @Test
    fun `events select intended recipients and stable resource links without private details`() {
        for (type in listOf("AccessGranted", "AccessRevoked")) {
            events.consume(record(ACCESS, type, """"stylistId":23"""))
        }
        for (type in listOf("StylistRequestCreated", "StylistRequestCancelled")) {
            events.consume(record(REQUESTS, type, """"stylistId":23,"requestId":7"""))
        }
        val stylistItems = json.readTree(request(token = stylist).body()).associateBy { it.path("type").stringValue() }
        assertThat(stylistItems).hasSize(4)
        assertThat(stylistItems.getValue("AccessGranted").path("resource").path("id").stringValue()).isEqualTo("17")
        assertThat(stylistItems.getValue("AccessRevoked").path("resource").isNull).isTrue()
        assertThat(stylistItems.getValue("StylistRequestCreated").path("resource").path("id").stringValue()).isEqualTo("17/7")
        val jobId = UUID.randomUUID()
        for (outcome in listOf("SUCCEEDED", "FAILED", "UNKNOWN")) {
            events.consume(record(JOBS, "AiJobFinalized",
                """"requestMonth":"2026-01-01","requestId":"$jobId","applicationOutcome":"$outcome""""))
        }
        for (type in listOf("OutfitRatingChanged", "OutfitRatingWithdrawn")) {
            events.consume(record(RATINGS, type, """"outfitId":31"""))
        }
        val items = json.readTree(request().body())
        assertThat(items.size()).isEqualTo(5)
        assertThat(items.count { it.path("resource").path("id").stringValue() == jobId.toString() }).isEqualTo(3)
        val page = request("?page=1&size=2")
        assertThat(page.headers().firstValue("X-Total-Count")).hasValue("5")
        assertThat(json.readTree(page.body())[0]).isEqualTo(items[2])
        assertThat(request("?page=3&size=2").body()).isEqualTo("[]")
        assertThat(request("?page=3&size=2").headers().firstValue("X-Total-Count")).hasValue("5")
        assertThat(request("?page=-1").statusCode()).isEqualTo(400)
        assertThat(request("?size=51").statusCode()).isEqualTo(400)
    }

    @Test
    fun `invalid and misrouted records create no effect`() {
        listOf(
            record(ACCESS, "AccessRevoked", """"stylistId":"23""""),
            record(REQUESTS, "AccessGranted", """"stylistId":23"""),
            record(OUTFITS, "StylistOutfitCreated", """"outfitId":2""", version = 2),
            record(OUTFITS, "StylistOutfitCreated", """"outfitId":2,"ownerId":23"""),
            record(JOBS, "AiJobFinalized", """"requestId":"bad""""),
            ConsumerRecord(ACCESS, 0, 5, byteArrayOf(), "{".toByteArray()),
        ).forEach(events::consume)
        assertThat(request().body()).isEqualTo("[]")
        assertThat(request(token = stylist).body()).isEqualTo("[]")
    }

    @Test
    fun `unsupported version is quarantined before ack and replays only preserve the original reason`() {
        val invalid = record(ACCESS, "AccessRevoked", """"stylistId":23,"privateNotes":"secret-note"""", version = 2)
        sql.sql("ALTER TABLE notification_rejected_event ADD CONSTRAINT simulate_failure CHECK (partition_id <> 0)")
            .then().block()
        try {
            assertThatThrownBy { events.consume(invalid) }.isInstanceOf(DataAccessException::class.java)
        } finally {
            sql.sql("ALTER TABLE notification_rejected_event DROP CONSTRAINT simulate_failure").then().block()
        }
        events.consume(invalid)
        events.consume(invalid)
        events.consume(ConsumerRecord(ACCESS, 0, 23, byteArrayOf(), "{".toByteArray()))
        val records = sql.sql("SELECT * FROM notification_rejected_event ORDER BY record_offset").fetch().all()
            .collectList().block()!!
        assertThat(records).hasSize(2)
        assertThat(records[0]).containsExactlyInAnyOrderEntriesOf(mapOf("topic" to ACCESS,
            "partition_id" to 0, "record_offset" to 22L, "reason" to "UNSUPPORTED_VERSION"))
        assertThat(records[1]["reason"]).isEqualTo("INVALID_EVENT")
        assertThat(request(token = stylist).body()).isEqualTo("[]")
    }

    @Test
    fun `JWT and live account status guard all operations`() {
        for (token in listOf(null, "malformed", TestJwt.sign("bad", Instant.now()),
            TestJwt.signClaims(TestJwt.claims("17", Instant.now()).audience("untrusted")))) {
            assertThat(request(token = token).statusCode()).isEqualTo(401)
        }
        for ((state, expected) in listOf("BLOCKED" to 403, "OFFLINE" to 503, "invalid" to 503)) {
            accountState.set(state)
            assertThat(request().statusCode()).isEqualTo(expected)
            assertThat(request("/unread-count").statusCode()).isEqualTo(expected)
            assertThat(request("/1/read", method = "PUT").statusCode()).isEqualTo(expected)
        }
    }
}
