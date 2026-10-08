package ru.itmo.clothesadvisor.client.ai

import com.sun.net.httpserver.HttpServer
import java.math.BigDecimal
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import ru.itmo.clothesadvisor.dto.outfit.OutfitWeatherDto
import tools.jackson.databind.json.JsonMapper

class OpenAiOutfitClientTests {
    @TempDir lateinit var temporary: Path
    private val credentials by lazy { ChatGptCredentialFixture(temporary) }
    private val mapper = JsonMapper.builder().build()
    private val http = HttpClient.newHttpClient()
    private val executor = Executors.newCachedThreadPool()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        this.executor = this@OpenAiOutfitClientTests.executor
        start()
    }
    private val calls = AtomicInteger()
    private val weather = AiWeather(OutfitWeatherDto(BigDecimal("-1.23456789"), 1, BigDecimal("2.3")), "RAIN", "Rain")
    private val candidates = listOf(7L, 9L, 11L).map {
        AiCandidateImage(AiCandidate(it, it + 100, "TOP", "Top", "Shirt $it", "White", "Cotton"),
            "image/png", byteArrayOf(1, 2, 3))
    }

    @AfterEach
    fun close() {
        server.stop(0)
        http.close()
        executor.shutdownNow()
    }

    @Test
    fun `real HTTP request contains strict schema weather images and configuration and preserves selected order`() {
        var requestBody = ""
        var authorization = ""
        server.createContext("/v1/responses") { exchange ->
            calls.incrementAndGet()
            requestBody = exchange.requestBody.bufferedReader().readText()
            authorization = exchange.requestHeaders.getFirst("Authorization")
            assertThat(exchange.requestMethod).isEqualTo("POST")
            val bytes = completed("{\"itemIds\":[11,7]}").toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        assertThat(client().select(weather, candidates)).containsExactly(11, 7)
        assertThat(calls).hasValue(1)
        assertThat(authorization).isEqualTo("Bearer synthetic-test-token")
        val body = mapper.readTree(requestBody)
        assertThat(body.path("model").asString()).isEqualTo("test-model")
        assertThat(body.path("store").asBoolean()).isFalse()
        assertThat(body.path("stream").asBoolean()).isTrue()
        assertThat(body.has("tools")).isFalse()
        assertThat(body.path("instructions").asString()).contains("untrusted data", "empty itemIds")
        val format = body.path("text").path("format")
        assertThat(format.path("type").asString()).isEqualTo("json_schema")
        assertThat(format.path("strict").asBoolean()).isTrue()
        val schema = format.path("schema")
        assertThat(schema.path("additionalProperties").asBoolean()).isFalse()
        assertThat(schema.path("required").toList().map { it.asString() }).containsExactly("itemIds")
        assertThat(schema.path("properties").path("itemIds").path("items").path("enum").toList().map { it.longValue() })
            .containsExactly(7, 9, 11)
        val content = body.path("input")[0].path("content")
        assertThat(content).hasSize(7)
        assertThat(content[0].path("text").asString()).contains("-1.23456789", "2.3", "RAIN", "Rain")
        assertThat(content[1].path("text").asString()).contains("Shirt 7", "White", "Cotton", "TOP").doesNotContain("photoId")
        assertThat(content.filter { it.path("type").asString() == "input_image" }
            .map { it.path("image_url").asString() }).containsOnly("data:image/png;base64,AQID")
    }

    @Test
    fun `refusal empty malformed and invalid IDs have sanitized distinct failures`() {
        val replies = listOf(
            completed("{\"itemIds\":[]}") to 422,
            event("response.completed", """{"status":"completed","output":[{"type":"message","role":"assistant","status":"completed","content":[{"type":"refusal","refusal":"private reason"}]}]}""") to 422,
            "data: not json private provider body\n\n" to 502,
            "" to 502,
            "data: null\n\n" to 502,
            event("response.completed", """{"status":"completed","output":[]}""") to 502,
            completed("{}") to 502,
            completed("") to 502,
            completed("null") to 502,
            completed("{\"itemIds\":[7.0]}") to 502,
            completed("{\"itemIds\":[null]}") to 502,
            completed("{\"itemIds\":[\"7\"]}") to 502,
            completed("{\"itemIds\":[999]}") to 502,
            completed("{\"itemIds\":[7,7]}") to 502,
            completed("{\"itemIds\":[9223372036854775808]}") to 502,
            completed("{\"itemIds\":[7],\"extra\":true}") to 502,
            completed("{\"itemIds\":[7],\"itemIds\":[9]}") to 502,
            completed("{\"itemIds\":[7]} {}") to 502,
            completed("{\"itemIds\":[7]}").trimEnd() + " {}\n\n" to 502,
        )
        var reply = ""
        respond { 200 to reply }
        replies.forEach { (body, status) ->
            reply = body
            failure(status) { client().select(weather, candidates) }
        }
        assertThat(calls).hasValue(replies.size)
    }

    @Test
    fun `HTTP failures become unavailable without retry`() {
        var status = 429
        respond { status to "private provider detail" }
        for (code in listOf(400, 401, 429, 500, 503)) {
            status = code
            failure(503) { client().select(weather, candidates) }
        }
        assertThat(calls).hasValue(5)
    }

    @Test
    fun `request timeout becomes 504 without retry`() {
        val release = CountDownLatch(1)
        respond {
            release.await(5, TimeUnit.SECONDS)
            200 to completed("{\"itemIds\":[7]}")
        }
        try {
            failure(504) { client(timeout = Duration.ofMillis(200)).select(weather, candidates) }
            assertThat(calls).hasValue(1)
        } finally { release.countDown() }
    }

    @Test
    fun `timeout includes body after successful response headers`() {
        val release = CountDownLatch(1)
        val headers = CountDownLatch(1)
        server.createContext("/v1/responses") { exchange ->
            calls.incrementAndGet()
            exchange.requestBody.use { it.readAllBytes() }
            exchange.sendResponseHeaders(200, 0)
            exchange.responseBody.write('{'.code)
            exchange.responseBody.flush()
            headers.countDown()
            release.await(5, TimeUnit.SECONDS)
            exchange.close()
        }
        try {
            failure(504) { client(timeout = Duration.ofMillis(300)).select(weather, candidates) }
            assertThat(headers.count).isZero()
            assertThat(calls).hasValue(1)
        } finally { release.countDown() }
    }

    @Test
    fun `missing credentials or model fails without network and connection failure becomes unavailable`() {
        respond { 200 to completed("{\"itemIds\":[7]}") }
        failure(503) { client(directory = "").select(weather, candidates) }
        failure(503) { client(model = "").select(weather, candidates) }
        assertThat(calls).hasValue(0)
        server.stop(0)
        failure(503) { client().select(weather, candidates) }
    }

    private fun completed(json: String) = event("response.completed", mapper.writeValueAsString(mapOf("status" to "completed", "output" to listOf(
        mapOf("type" to "message", "role" to "assistant", "status" to "completed", "content" to listOf(
            mapOf("type" to "output_text", "text" to json),
        )),
    ))))

    private fun event(type: String, response: String) = "event: $type\ndata: {\"type\":\"$type\",\"response\":$response}\n\n"

    private fun delta(text: String, item: String = "message-1", output: Int = 0, content: Int = 0) =
        "data: " + mapper.writeValueAsString(mapOf("type" to "response.output_text.delta", "delta" to text,
            "item_id" to item, "output_index" to output, "content_index" to content)) + "\n\n"

    @Test
    fun `terminal text takes priority and empty terminal output uses one delta group`() {
        var reply = delta("{\"itemIds\":[") + delta("9,7]}") + event("response.completed", """{"status":"completed","output":[]}""")
        respond { 200 to reply }
        assertThat(client().select(weather, candidates)).containsExactly(9, 7)
        reply = delta("malformed unused partial") + completed("{\"itemIds\":[11]}")
        assertThat(client().select(weather, candidates)).containsExactly(11)
    }

    @Test
    fun `partial streams failures mixed outputs bounds and missing delimiter never succeed`() {
        val successful = completed("{\"itemIds\":[7]}")
        val prefix = delta("{\"itemIds\":[7]}")
        val replies = listOf(
            prefix to 502,
            prefix + event("response.failed", """{"status":"failed"}""") to 503,
            prefix + "data: {\"type\":\"error\",\"message\":\"private\"}\n\n" to 503,
            prefix + event("response.incomplete", """{"status":"incomplete"}""") to 502,
            prefix + "data: {\"type\":\"response.refusal.delta\",\"delta\":\"private\"}\n\n" to 422,
            prefix + event("response.completed", """{"status":"completed","output":[],"error":{"code":"quota"}}""") to 503,
            prefix + delta(" ", item = "another") + successful to 502,
            prefix + delta(" ", output = 1) + successful to 502,
            prefix + delta(" ", content = 1) + successful to 502,
            "data: {\"type\":\"response.output_text.delta\",\"delta\":\"text\"}\n\n" + successful to 502,
            successful.trimEnd() to 502,
            successful.removeSuffix("\n") to 502,
            successful.replace("\"type\":\"response.completed\"", "\"type\":\"response.completed\",\"type\":\"response.completed\"") to 502,
            successful.replace("event: response.completed", "event: response.failed") to 502,
            ":" + "x".repeat(1_048_577) + "\n\n" + successful to 502,
            successful.replace("data: ", "data: " + " ".repeat(600_000))
                .removeSuffix("\n\n") + "\ndata: " + " ".repeat(600_000) + "\n\n" to 502,
            delta("x".repeat(600_000)) + delta("x".repeat(600_000)) + successful to 502,
        )
        var reply = ""
        respond { 200 to reply }
        replies.forEach { (body, status) ->
            reply = body
            failure(status) { client().select(weather, candidates) }
        }
    }

    @Test
    fun `explicit content type must be event stream`() {
        var contentType = "application/json"
        server.createContext("/v1/responses") { exchange ->
            exchange.requestBody.use { it.readAllBytes() }
            exchange.responseHeaders.set("Content-Type", contentType)
            val bytes = completed("{\"itemIds\":[7]}").toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        for (invalid in listOf("application/json", " ")) {
            contentType = invalid
            failure(502) { client().select(weather, candidates) }
        }
        contentType = "text/event-stream; charset=utf-8"
        assertThat(client().select(weather, candidates)).containsExactly(7)
    }

    private fun respond(reply: () -> Pair<Int, String>) {
        server.createContext("/v1/responses") { exchange ->
            calls.incrementAndGet()
            exchange.requestBody.use { it.readAllBytes() }
            val (status, body) = reply()
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
    }

    private fun client(directory: String = credentials.directory.toString(), model: String = "test-model", timeout: Duration = Duration.ofSeconds(5)) =
        OpenAiOutfitClient(http, mapper, ChatGptCredentials(mapper, credentials.clock, directory), model,
            URI("http://127.0.0.1:${server.address.port}/v1/responses"), timeout)

    private fun failure(status: Int, action: () -> Any) {
        assertThatThrownBy { action() }.isInstanceOfSatisfying(AiOutfitException::class.java) {
            assertThat(it.status).isEqualTo(status)
            assertThat(it.message).isNull()
            assertThat(it.cause).isNull()
        }
    }
}
