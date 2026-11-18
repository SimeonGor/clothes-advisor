package ru.itmo.clothesadvisor.fileservice

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpRequest.BodyPublishers
import java.net.http.HttpResponse
import java.nio.file.Path
import java.time.Duration
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.Mockito.clearInvocations
import org.mockito.Mockito.mock
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.r2dbc.core.DatabaseClient
import ru.itmo.clothesadvisor.fileservice.client.iam.IamTokenProvider
import ru.itmo.clothesadvisor.fileservice.model.MAX_PHOTO_BYTES
import ru.itmo.clothesadvisor.fileservice.repository.FileObjectRepository
import tools.jackson.databind.json.JsonMapper

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FileApiIntegrationTests {
    private val bucket = LocalPhotoBucket()
    private val http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()
    private val handler = HttpResponse.BodyHandlers.ofByteArray()
    private val wardrobe = basic("wardrobe-service", FileTestEnvironment.WARDROBE_PASSWORD)
    private val recommendation = basic("recommendation-service", FileTestEnvironment.RECOMMENDATION_PASSWORD)
    private val timeout = Duration.ofSeconds(10)
    private lateinit var context: ConfigurableApplicationContext
    private lateinit var api: String
    private lateinit var files: FileObjectRepository
    private lateinit var sql: DatabaseClient
    private lateinit var tokens: IamTokenProvider

    @BeforeAll
    fun start(@TempDir directory: Path) {
        context = SpringApplicationBuilder(FileServiceApplication::class.java, TestIam::class.java)
            .profiles("test").run(
                *FileTestEnvironment.arguments(directory),
                "--S3_ENDPOINT=${bucket.endpoint}",
            )
        api = "http://127.0.0.1:${context.environment.getRequiredProperty("local.server.port")}"
        files = context.getBean(FileObjectRepository::class.java)
        sql = context.getBean(DatabaseClient::class.java)
        tokens = context.getBean(IamTokenProvider::class.java)
    }

    @AfterEach
    fun clearTestState() {
        files.deleteAll().block(timeout)
        bucket.reset()
        clearInvocations(tokens)
    }

    @AfterAll
    fun close() {
        if (::context.isInitialized) context.close()
        http.close()
        bucket.close()
    }

    @Test
    fun `wardrobe creates immutable metadata and both callers receive original bytes`() {
        // given
        val id = UUID.randomUUID()
        val png = image()

        // when
        val created = send(put(id, png, "IMAGE/PNG"))

        // then
        assertThat(created.statusCode()).isEqualTo(201)
        val mapper = context.getBean(JsonMapper::class.java)
        assertThat(mapper.readTree(created.body())).isEqualTo(mapper.readTree(
            """{"fileId":"$id","contentType":"image/png","sizeBytes":${png.size}}""",
        ))
        val row = files.findById(id).block(timeout)!!
        assertThat(row.fileId).isEqualTo(id)
        assertThat(row.objectKey).isNotBlank()
        assertThat(bucket.objects[row.objectKey]).containsExactly(*png)
        assertThat(row.sizeBytes).isEqualTo(png.size.toLong())
        assertThat(row.contentType).isEqualTo("image/png")
        assertThat(row.createdAt).isNotNull()

        for (auth in listOf(wardrobe, recommendation)) {
            val response = send(get(id, auth))
            assertThat(response.statusCode()).isEqualTo(200)
            assertThat(response.headers().firstValue("Content-Type")).hasValue("image/png")
            assertThat(response.headers().firstValue("Content-Length")).hasValue(png.size.toString())
            assertThat(response.body()).containsExactly(*png)
            assertThat(response.headers().allValues("Set-Cookie")).isEmpty()
        }

        val before = bucket.calls.get()
        clearInvocations(tokens)
        assertThat(send(put(id, image(0x123456))).statusCode()).isEqualTo(409)
        assertThat(bucket.calls).hasValue(before)
        verifyNoInteractions(tokens)
        assertThat(files.findById(id).block(timeout)).isEqualTo(row)
    }

    @Test
    fun `authentication and method permissions precede photo validation`() {
        // given
        val id = UUID.randomUUID()
        val wrong = basic("wardrobe-service", FileTestEnvironment.RECOMMENDATION_PASSWORD)
        val jwt = "Bearer eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiIxIn0.synthetic"

        // when
        for (auth in listOf(null, wrong, jwt)) {
            for (request in listOf(get(id, auth), put(id, byteArrayOf(), "text/plain", auth))) {
                val response = send(request)

                // then
                assertThat(response.statusCode()).isEqualTo(401)
                assertThat(response.headers().firstValue("WWW-Authenticate").orElse("")).startsWith("Basic")
                assertThat(response.body().decodeToString()).doesNotContain(
                    FileTestEnvironment.WARDROBE_PASSWORD, FileTestEnvironment.RECOMMENDATION_PASSWORD,
                )
            }
        }
        assertThat(send(put(id, byteArrayOf(), "text/plain", recommendation)).statusCode())
            .isEqualTo(403)
        verifyNoInteractions(tokens)
        assertThat(bucket.calls).hasValue(0)
        assertThat(files.existsById(id).block(timeout)).isFalse()
    }

    @Test
    fun `invalid ids content types images and empty bodies do not reach storage`() {
        // given
        val id = UUID.randomUUID()
        val png = image()
        val requests = listOf(
            put(id, png, null), put(id, png, "application/octet-stream"),
            put(id, png, "not-a-type"), put(id, png, "image/jpeg"),
            put(id, byteArrayOf(1, 2)), put(id, byteArrayOf()),
        ) + listOf("oops", "1-2-3-4-5").flatMap { invalidId ->
            val path = "/api/files/$invalidId"
            listOf(
                request(path, wardrobe).GET().build(),
                request(path, wardrobe).header("Content-Type", "image/png")
                    .PUT(BodyPublishers.ofByteArray(png)).build(),
            )
        }

        // when
        val statuses = requests.map { send(it).statusCode() }

        // then
        assertThat(statuses).containsOnly(400)
        assertThat(files.existsById(id).block(timeout)).isFalse()
        verifyNoInteractions(tokens)
        assertThat(bucket.calls).hasValue(0)
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `body limit holds with a declared length and with chunked transfer`(chunked: Boolean) {
        // given
        val allowed = UUID.randomUUID()
        val rejected = UUID.randomUUID()
        val atLimit = image().copyOf(MAX_PHOTO_BYTES)

        // when
        val ok = send(put(allowed, atLimit, chunked = chunked))
        val over = send(put(rejected, atLimit.copyOf(MAX_PHOTO_BYTES + 1), chunked = chunked))

        // then
        assertThat(ok.statusCode()).isEqualTo(201)
        assertThat(over.statusCode()).isEqualTo(400)
        assertThat(files.findById(allowed).block(timeout)!!.sizeBytes).isEqualTo(MAX_PHOTO_BYTES.toLong())
        assertThat(files.existsById(rejected).block(timeout)).isFalse()
        assertThat(bucket.calls).hasValue(1)
    }

    @Test
    fun `missing metadata skips storage and legacy keys distinguish missing and unavailable objects`() {
        // given
        val id = UUID.randomUUID()
        val unknown = send(get(id))
        assertThat(unknown.statusCode()).isEqualTo(404)
        verifyNoInteractions(tokens)
        assertThat(bucket.calls).hasValue(0)
        val key = "old/photos/зима 1.png"
        val png = image()
        files.insert(id, key, "image/png", png.size.toLong()).block(timeout)
        bucket.objects[key] = png

        // when
        val found = send(get(id, recommendation))
        bucket.objects[key] = png.copyOf(png.size - 1)
        val shortBody = send(get(id))
        bucket.objects.remove(key)
        val missing = send(get(id))
        bucket.unavailable = true
        val unavailable = send(get(id))

        // then
        assertThat(found.statusCode()).isEqualTo(200)
        assertThat(found.body()).containsExactly(*png)
        assertThat(shortBody.statusCode()).isEqualTo(503)
        assertThat(missing.statusCode()).isEqualTo(404)
        assertThat(unavailable.statusCode()).isEqualTo(503)
        assertThat(bucket.calls).hasValue(4)
    }

    @Test
    fun `concurrent attempts create distinct objects and keep the winner's bytes`() {
        // given
        val id = UUID.randomUUID()
        val waiting = CountDownLatch(2)
        val images = listOf(image(0x010203), image(0xabcdef))
        bucket.beforeReply = {
            waiting.countDown()
            check(waiting.await(10, TimeUnit.SECONDS))
        }

        // when
        val futures = images.map { http.sendAsync(put(id, it), handler) }
        val statuses = futures.map { it.get(20, TimeUnit.SECONDS).statusCode() }

        // then
        assertThat(statuses).containsExactlyInAnyOrder(201, 409)
        assertThat(bucket.calls).hasValue(2)
        assertThat(bucket.objects).hasSize(2)
        val expected = images[statuses.indexOf(201)]
        val row = files.findById(id).block(timeout)!!
        assertThat(bucket.objects[row.objectKey]).containsExactly(*expected)
        assertThat(send(get(id)).body()).containsExactly(*expected)
    }

    @Test
    fun `storage failure leaves no row and does not retry PUT`() {
        // given
        val id = UUID.randomUUID()
        bucket.unavailable = true

        // when
        val result = send(put(id, image()))

        // then
        assertThat(result.statusCode()).isEqualTo(503)
        assertThat(files.existsById(id).block(timeout)).isFalse()
        assertThat(bucket.calls).hasValue(1)
    }

    @Test
    fun `failed insert after storage PUT returns a sanitized server error without retrying`() {
        // given
        val id = UUID.randomUUID()
        sql.sql("ALTER TABLE file_object ADD CONSTRAINT reject_test_row CHECK (file_id <> '$id')")
            .then().block(timeout)
        try {
            // when
            val failure = send(put(id, image()))

            // then
            assertThat(failure.statusCode()).isEqualTo(500)
            assertThat(failure.body().decodeToString()).doesNotContain(
                "reject_test_row", "INSERT INTO", "Exception",
                FileTestEnvironment.WARDROBE_PASSWORD, FileTestEnvironment.RECOMMENDATION_PASSWORD,
            )
            assertThat(files.existsById(id).block(timeout)).isFalse()
            assertThat(bucket.calls).hasValue(1)
            assertThat(bucket.objects).hasSize(1)
        } finally {
            sql.sql("ALTER TABLE file_object DROP CONSTRAINT reject_test_row").then().block(timeout)
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["PUT", "GET"])
    fun `pending storage operation leaves the only DB connection and web server available`(method: String) {
        // given
        val id = UUID.randomUUID()
        val request = if (method == "GET") {
            assertThat(send(put(id, image())).statusCode()).isEqualTo(201)
            get(id)
        } else put(id, image())
        val waiting = CountDownLatch(1)
        val release = CountDownLatch(1)
        bucket.beforeReply = {
            waiting.countDown()
            check(release.await(15, TimeUnit.SECONDS))
        }

        // when
        val pending = http.sendAsync(request, handler)
        try {
            assertThat(waiting.await(10, TimeUnit.SECONDS)).isTrue()
            val rowExists = files.existsById(id).block(Duration.ofSeconds(3))
            val health = send(request("/actuator/health", null).GET().build())

            // then
            assertThat(rowExists).isEqualTo(method == "GET")
            assertThat(health.statusCode()).isEqualTo(200)
            assertThat(pending.isDone).isFalse()
        } finally {
            release.countDown()
        }
        assertThat(pending.get(10, TimeUnit.SECONDS).statusCode())
            .isEqualTo(if (method == "GET") 200 else 201)
    }

    private fun get(id: UUID, auth: String? = wardrobe) =
        request("/api/files/$id", auth).GET().build()

    private fun put(
        id: UUID,
        bytes: ByteArray,
        type: String? = "image/png",
        auth: String? = wardrobe,
        chunked: Boolean = false,
    ): HttpRequest {
        val publisher = if (chunked) BodyPublishers.ofInputStream { bytes.inputStream() }
            else BodyPublishers.ofByteArray(bytes)
        return request("/api/files/$id", auth).apply {
            if (type != null) header("Content-Type", type)
        }.PUT(publisher).build()
    }

    private fun request(path: String, auth: String?) = HttpRequest.newBuilder(URI("$api$path"))
        .timeout(Duration.ofSeconds(25))
        .apply { if (auth != null) header("Authorization", auth) }

    private fun send(request: HttpRequest) = http.send(request, handler)

    private fun image(rgb: Int = 0): ByteArray = ByteArrayOutputStream().use { output ->
        val image = BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB)
        image.setRGB(0, 0, rgb)
        check(ImageIO.write(image, "png", output))
        output.toByteArray()
    }

    private fun basic(name: String, password: String): String =
        "Basic " + Base64.getEncoder().encodeToString("$name:$password".toByteArray())

    @TestConfiguration(proxyBeanMethods = false)
    class TestIam {
        @Bean
        @Primary
        internal fun syntheticTokenProvider(): IamTokenProvider =
            mock(IamTokenProvider::class.java).also {
                `when`(it.accessToken()).thenReturn("synthetic-access-token")
            }
    }
}
