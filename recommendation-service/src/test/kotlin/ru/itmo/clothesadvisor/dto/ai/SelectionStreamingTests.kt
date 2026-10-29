package ru.itmo.clothesadvisor.dto.ai

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.core.io.buffer.DataBufferUtils
import org.springframework.core.io.buffer.DefaultDataBufferFactory
import reactor.core.publisher.BaseSubscriber
import reactor.core.publisher.Flux
import ru.itmo.clothesadvisor.client.ai.AiSelectionException
import ru.itmo.clothesadvisor.client.ai.AiSelectionFailure
import ru.itmo.clothesadvisor.client.ai.OpenAiSelectionClient
import ru.itmo.clothesadvisor.recommendationservice.controller.ItemSelectionController
import ru.itmo.clothesadvisor.recommendationservice.service.ItemSelectionService
import tools.jackson.databind.json.JsonMapper

class SelectionStreamingTests {
    private val provider = Mockito.mock(OpenAiSelectionClient::class.java)
    private val controller = ItemSelectionController(ItemSelectionService(provider))
    private val mapper = JsonMapper.builder().build()
    private fun candidate(image: String = "AQID", id: Int = 7) =
        """{"itemId":$id,"name":"Shirt","categoryCode":"TOP","categoryName":"Top","color":"White","material":"Cotton","contentType":"image/png","imageBase64":${mapper.writeValueAsString(image)}}"""
    private fun request(candidates: String) =
        """{"weather":{"temperatureC":0,"windSpeedMps":0,"precipitationCode":"NONE","precipitationName":"None"},"candidates":[$candidates]}"""
    private fun select(json: String) = controller.selectOutfit(Flux.just(
        DefaultDataBufferFactory.sharedInstance.wrap(json.toByteArray()),
    )).block(Duration.ofSeconds(10))
    private fun files(): Set<Path> = Files.list(Path.of(System.getProperty("java.io.tmpdir"))).use {
        it.filter { path -> path.fileName.toString().startsWith("selection-photo-") }.toList().toSet()
    }

    @Test
    fun `invalid image and malformed later fields clean earlier images before provider`() {
        val baseline = files()
        val invalidImages = listOf("", "data:image/png;base64,AQID", "!!!!", "AQI", "AR==", "AQ-D",
            Base64.getEncoder().encodeToString(ByteArray(10_000_001)))
        val invalid = invalidImages.map { request(candidate() + "," + candidate(it, 9)) } +
            listOf(request(candidate()) + " {}", request(candidate()).dropLast(1) + ""","unknown":1}""",
                request(candidate()).replace("\"itemId\":7", "\"itemId\":7,\"itemId\":9"),
                request(candidate()).replace("\"imageBase64\":\"AQID\"", "\"imageBase64\":null"))
        invalid.forEach { json ->
            val failure = catchThrowable { select(json) }
            assertThat(failure).describedAs(json.takeLast(120)).isInstanceOfSatisfying(AiSelectionException::class.java) {
                assertThat(it.failure).isEqualTo(AiSelectionFailure.INVALID_REQUEST)
                assertThat(it.cause).isNull()
            }
            assertThat(files()).isEqualTo(baseline)
        }
        Mockito.verifyNoInteractions(provider)
    }

    @Test
    fun `provider failure cleans parsed images and preserves status`() {
        val baseline = files()
        Mockito.doAnswer { call ->
            val input = call.getArgument<ItemSelectionRequest>(1)
            assertThat(input.candidates.single().imageBase64.open().use { it.readAllBytes() })
                .isEqualTo("AQID".toByteArray())
            throw AiSelectionException(AiSelectionFailure.TIMEOUT)
        }.`when`(provider).select(
            Mockito.eq(ru.itmo.clothesadvisor.client.ai.SelectionGoal.OUTFIT)
                ?: ru.itmo.clothesadvisor.client.ai.SelectionGoal.OUTFIT,
            Mockito.any(ItemSelectionRequest::class.java)
                ?: ItemSelectionRequest(ItemSelectionWeather(java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO, "", ""), emptyList()),
        )
        val failure = catchThrowable { select(request(candidate())) }
        assertThat(failure).isInstanceOfSatisfying(AiSelectionException::class.java) {
            assertThat(it.failure).isEqualTo(AiSelectionFailure.TIMEOUT)
        }
        assertThat(files()).isEqualTo(baseline)
    }

    @Test
    fun `temporary photo IO failure is sanitized and releases earlier scoped photo`() {
        // given
        val photos = Mockito.spy(SelectionPhotos())
        val earlier = photos.add(byteArrayOf(1, 2, 3))
        val parser = Mockito.mock(tools.jackson.core.JsonParser::class.java)
        val context = Mockito.mock(tools.jackson.databind.DeserializationContext::class.java)
        Mockito.`when`(parser.hasToken(tools.jackson.core.JsonToken.VALUE_STRING)).thenReturn(true)
        Mockito.`when`(parser.string).thenReturn("AQID")
        Mockito.`when`(context.getAttribute(SelectionPhotos::class.java)).thenReturn(photos)
        Mockito.doAnswer { throw IOException("synthetic private temporary path and image data") }
            .`when`(photos).add(Mockito.any(ByteArray::class.java) ?: byteArrayOf())

        // when
        val failure = catchThrowable {
            photos.use { SelectionImageDeserializer().deserialize(parser, context) }
        }

        // then
        assertThat(failure).isInstanceOfSatisfying(AiSelectionException::class.java) {
            assertThat(it.failure).isEqualTo(AiSelectionFailure.UNAVAILABLE)
            assertThat(it.message).isNull()
            assertThat(it.cause).isNull()
        }
        assertThat(Files.exists(earlier.path)).isFalse()
    }

    @Test
    fun `private file remains available to active reader until operation terminates`() {
        val photos = SelectionPhotos()
        val image = photos.add(byteArrayOf(1, 2, 3))
        assertThat(Files.getPosixFilePermissions(image.path).map { it.name })
            .containsExactlyInAnyOrder("OWNER_READ", "OWNER_WRITE")
        val input = image.open()
        photos.close()
        assertThat(Files.exists(image.path)).isTrue()
        assertThat(input.readAllBytes()).isEqualTo("AQID".toByteArray())
        input.close()
        assertThat(Files.exists(image.path)).isFalse()
    }

    @Test
    fun `cancelled backpressured JSON writer terminates and releases active file`() {
        val photos = SelectionPhotos()
        val image = photos.add(ByteArray(1_000_000))
        val ended = CountDownLatch(1)
        val first = CountDownLatch(1)
        val stream = Flux.from(DataBufferUtils.outputStreamPublisher({ output ->
            try { mapper.writeValue(output, mapOf("image" to Image(image))) }
            finally { ended.countDown() }
        }, DefaultDataBufferFactory.sharedInstance, { Thread.startVirtualThread(it) }, 8192))
        val subscriber = object : BaseSubscriber<org.springframework.core.io.buffer.DataBuffer>() {
            override fun hookOnSubscribe(subscription: org.reactivestreams.Subscription) { request(1) }
            override fun hookOnNext(value: org.springframework.core.io.buffer.DataBuffer) {
                DataBufferUtils.release(value)
                first.countDown()
            }
        }
        stream.subscribe(subscriber)
        assertThat(first.await(3, TimeUnit.SECONDS)).isTrue()
        assertThat(ended.count).isEqualTo(1)
        photos.close()
        subscriber.cancel()
        assertThat(ended.await(3, TimeUnit.SECONDS)).isTrue()
        assertThat(Files.exists(image.path)).isFalse()
    }

    data class Image(
        @get:tools.jackson.databind.annotation.JsonSerialize(using = SelectionImageSerializer::class)
        val value: SelectionImage,
    )
}
