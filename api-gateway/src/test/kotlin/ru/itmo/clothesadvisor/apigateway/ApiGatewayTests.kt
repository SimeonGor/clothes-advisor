package ru.itmo.clothesadvisor.apigateway

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.CopyOnWriteArrayList
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.cloud.gateway.config.HttpClientFactory
import org.springframework.cloud.gateway.config.HttpClientProperties
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class ApiGatewayTests {
    @LocalServerPort private var port = 0
    @Autowired private lateinit var httpClientProperties: HttpClientProperties
    @Autowired private lateinit var httpClientFactory: HttpClientFactory
    private val http = HttpClient.newHttpClient()

    @AfterEach
    fun closeClient() {
        http.close()
        backendRequests.clear()
    }

    @Test
    fun `GET preserves path query headers and response headers`() {
        // given
        val request = HttpRequest.newBuilder(uri("/api/test/items?limit=7&offset=2"))
            .header("X-User-Id", "42").GET().build()

        // when
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())

        // then
        assertThat(response.statusCode()).isEqualTo(200)
        assertThat(response.body()).isEqualTo("/api/test/items?limit=7&offset=2|42")
        assertThat(response.headers().firstValue("X-Total-Count").orElse(null)).isEqualTo("11")
        assertThat(backendRequests.single().path).isEqualTo("/api/test/items?limit=7&offset=2")
        assertThat(backendRequests.single().userId).isEqualTo("42")
        assertThat(backendRequests.single().host).isEqualTo("127.0.0.1:$port")
    }

    @Test
    fun `POST preserves status location and JSON body`() {
        // given
        val body = """{"name":"Jacket"}"""
        val request = HttpRequest.newBuilder(uri("/api/test/items"))
            .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build()

        // when
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())

        // then
        assertThat(response.statusCode()).isEqualTo(201)
        assertThat(response.headers().firstValue("Location").orElse(null)).isEqualTo("/api/test/items/9")
        assertThat(response.body()).isEqualTo(body)
        assertThat(backendRequests.single().body.decodeToString()).isEqualTo(body)
    }

    @Test
    fun `large multipart upload and binary download are preserved`() {
        // given
        val partHeaders =
            "--test-boundary\r\n" +
                "Content-Disposition: form-data; name=\"file\"; filename=\"photo.bin\"\r\n" +
                "Content-Type: application/octet-stream\r\n\r\n"
        val partBody = ByteArray(300 * 1024) { (it % 251).toByte() }
        val multipart = partHeaders.encodeToByteArray() + partBody + "\r\n--test-boundary--\r\n".encodeToByteArray()
        val upload = HttpRequest.newBuilder(uri("/api/test/photos"))
            .header("Content-Type", "multipart/form-data; boundary=test-boundary")
            .POST(HttpRequest.BodyPublishers.ofByteArray(multipart)).build()
        val download = HttpRequest.newBuilder(uri("/api/test/photos/9/content")).GET().build()

        // when
        val uploadResponse = http.send(upload, HttpResponse.BodyHandlers.ofByteArray())
        val downloadResponse = http.send(download, HttpResponse.BodyHandlers.ofByteArray())

        // then
        assertThat(uploadResponse.statusCode()).isEqualTo(200)
        assertThat(backendRequests.first().body).containsExactly(*multipart)
        assertThat(backendRequests.first().contentType)
            .isEqualTo("multipart/form-data; boundary=test-boundary")
        assertThat(downloadResponse.statusCode()).isEqualTo(200)
        assertThat(downloadResponse.body()).containsExactly(*downloadBytes)
        assertThat(downloadResponse.headers().firstValue("Content-Type").orElse(null))
            .isEqualTo("application/octet-stream")
        assertThat(downloadResponse.headers().firstValue("Cache-Control").orElse(null)).isEqualTo("no-store")
        assertThat(downloadResponse.headers().firstValue("X-Content-Type-Options").orElse(null))
            .isEqualTo("nosniff")
    }

    @Test
    fun `backend errors and unknown API paths remain API responses`() {
        // given
        val failure = HttpRequest.newBuilder(uri("/api/test/failure")).GET().build()
        val missing = HttpRequest.newBuilder(uri("/api/not-found")).GET().build()

        // when
        val failureResponse = http.send(failure, HttpResponse.BodyHandlers.ofString())
        val missingResponse = http.send(missing, HttpResponse.BodyHandlers.ofString())

        // then
        assertThat(failureResponse.statusCode()).isEqualTo(409)
        assertThat(failureResponse.body()).isEqualTo("backend-error")
        assertThat(missingResponse.statusCode()).isEqualTo(404)
        assertThat(missingResponse.headers().firstValue("Content-Type").orElse(""))
            .contains("application/json")
        assertThat(backendRequests.map { it.path }).containsExactly("/api/test/failure", "/api/not-found")
    }

    @Test
    fun `known UI and docs paths route while internal paths stay closed`() {
        // given
        val routedPaths = listOf("/", "/index.html", "/demo.js", "/demo.css", "/swagger-ui.html",
            "/swagger-ui/index.html", "/v3/api-docs", "/v3/api-docs/openapi-config", "/v3/api-docs.yaml")
        val blockedPaths = listOf("/internal/health", "/eureka/apps", "/wardrobe-service/actuator/health", "/unknown.js")

        // when
        val routed = routedPaths.map(::get)
        val blocked = blockedPaths.map(::get)

        // then
        assertThat(routed.map { it.statusCode() }).containsOnly(200)
        assertThat(backendRequests.map { it.path }).containsExactlyElementsOf(routedPaths)
        assertThat(blocked.map { it.statusCode() }).containsOnly(404)
        assertThat(backendRequests).hasSize(routedPaths.size)
    }

    @Test
    fun `gateway configuration disables reset retry without a response timeout`() {
        // given
        val client = httpClientFactory.getObject()

        // when
        val retryDisabled = client.configuration().isRetryDisabled()

        // then
        assertThat(httpClientProperties.connectTimeout).isEqualTo(5000)
        assertThat(httpClientProperties.responseTimeout).isNull()
        assertThat(retryDisabled).isTrue()
    }

    private fun uri(path: String) = URI("http://127.0.0.1:$port$path")
    private fun get(path: String): HttpResponse<String> =
        http.send(HttpRequest.newBuilder(uri(path)).GET().build(), HttpResponse.BodyHandlers.ofString())

    companion object {
        private val backendRequests = CopyOnWriteArrayList<BackendRequest>()
        private val downloadBytes = byteArrayOf(0, 1, 2, -1, -128, 127)
        private val backend = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)

        init {
            backend.createContext("/") { exchange ->
                val requestBody = exchange.requestBody.readBytes()
                backendRequests += BackendRequest(
                    path = exchange.requestURI.toString(),
                    body = requestBody,
                    userId = exchange.requestHeaders.getFirst("X-User-Id"),
                    host = exchange.requestHeaders.getFirst("Host"),
                    contentType = exchange.requestHeaders.getFirst("Content-Type"),
                )
                val path = exchange.requestURI.path
                val responseBody = when (path) {
                    "/api/test/items" -> if (exchange.requestMethod == "POST") requestBody else
                        "${exchange.requestURI}|${exchange.requestHeaders.getFirst("X-User-Id")}".encodeToByteArray()
                    "/api/test/photos" -> "uploaded".encodeToByteArray()
                    "/api/test/photos/9/content" -> downloadBytes
                    "/api/test/failure" -> "backend-error".encodeToByteArray()
                    "/api/not-found" -> """{"error":"missing"}""".encodeToByteArray()
                    else -> "ok".encodeToByteArray()
                }
                when (path) {
                    "/api/test/items" -> if (exchange.requestMethod == "POST") {
                        exchange.responseHeaders.add("Location", "/api/test/items/9")
                        exchange.sendResponseHeaders(201, responseBody.size.toLong())
                    } else {
                        exchange.responseHeaders.add("X-Total-Count", "11")
                        exchange.sendResponseHeaders(200, responseBody.size.toLong())
                    }
                    "/api/test/photos/9/content" -> {
                        exchange.responseHeaders.add("Content-Type", "application/octet-stream")
                        exchange.responseHeaders.add("Cache-Control", "no-store")
                        exchange.responseHeaders.add("X-Content-Type-Options", "nosniff")
                        exchange.sendResponseHeaders(200, responseBody.size.toLong())
                    }
                    "/api/test/failure" -> exchange.sendResponseHeaders(409, responseBody.size.toLong())
                    "/api/not-found" -> {
                        exchange.responseHeaders.add("Content-Type", "application/json")
                        exchange.sendResponseHeaders(404, responseBody.size.toLong())
                    }
                    else -> exchange.sendResponseHeaders(200, responseBody.size.toLong())
                }
                exchange.responseBody.use { it.write(responseBody) }
                exchange.close()
            }
            backend.start()
        }

        @JvmStatic
        @DynamicPropertySource
        fun gatewayProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.cloud.discovery.client.simple.instances.wardrobe-service[0].uri") {
                "http://127.0.0.1:${backend.address.port}"
            }
        }

        @JvmStatic
        @AfterAll
        fun stopBackend() {
            backend.stop(0)
        }
    }

    private data class BackendRequest(
        val path: String,
        val body: ByteArray,
        val userId: String?,
        val host: String?,
        val contentType: String?,
    )
}
