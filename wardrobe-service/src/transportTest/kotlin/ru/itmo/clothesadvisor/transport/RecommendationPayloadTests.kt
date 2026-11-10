package ru.itmo.clothesadvisor.transport

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import java.lang.management.ManagementFactory
import java.math.BigDecimal
import java.time.Instant
import java.util.Base64
import java.nio.file.Files
import java.net.InetSocketAddress
import com.sun.net.httpserver.HttpServer
import java.util.UUID
import org.junit.jupiter.api.AfterAll
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.any
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.eq
import org.mockito.Mockito.mock
import org.mockito.Mockito.refEq
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.SpringBootConfiguration
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.SimpleTransactionStatus
import ru.itmo.clothesadvisor.client.ai.OpenAiSelectionClient
import ru.itmo.clothesadvisor.recommendationservice.model.ai.SelectionGoal
import ru.itmo.clothesadvisor.client.recommendation.RecommendationClient
import ru.itmo.clothesadvisor.config.ExternalClientsConfiguration
import ru.itmo.clothesadvisor.dto.ai.CreateAiOutfitRequest
import ru.itmo.clothesadvisor.dto.ai.ItemSelectionRequest as ServerSelectionRequest
import ru.itmo.clothesadvisor.dto.ai.ItemSelectionResponse as ServerSelectionResponse
import ru.itmo.clothesadvisor.dto.ai.ItemSelectionWeather as ServerSelectionWeather
import ru.itmo.clothesadvisor.dto.ai.ItemSelectionStylePreferences as ServerStylePreferences
import ru.itmo.clothesadvisor.dto.outfit.CreateOutfitRequest
import ru.itmo.clothesadvisor.dto.outfit.OutfitResponse
import ru.itmo.clothesadvisor.dto.outfit.OutfitWeatherDto
import ru.itmo.clothesadvisor.dto.user.CurrentUser
import ru.itmo.clothesadvisor.dto.wardrobe.WardrobeItemPhotoContent
import ru.itmo.clothesadvisor.model.outfit.OutfitSource
import ru.itmo.clothesadvisor.model.preferences.StylePreferences
import ru.itmo.clothesadvisor.repository.preferences.StylePreferencesRepository
import ru.itmo.clothesadvisor.model.user.UserRole
import ru.itmo.clothesadvisor.model.wardrobe.WardrobeCategory
import ru.itmo.clothesadvisor.model.wardrobe.WardrobeItem
import ru.itmo.clothesadvisor.model.wardrobe.WardrobeItemPhoto
import ru.itmo.clothesadvisor.model.weather.PrecipitationType
import ru.itmo.clothesadvisor.recommendationservice.RecommendationServiceApplication
import ru.itmo.clothesadvisor.recommendationservice.service.ItemSelectionProvider
import ru.itmo.clothesadvisor.repository.wardrobe.WardrobeItemPhotoRepository
import ru.itmo.clothesadvisor.repository.wardrobe.WardrobeItemRepository
import ru.itmo.clothesadvisor.repository.weather.PrecipitationTypeRepository
import ru.itmo.clothesadvisor.service.ai.AiOutfitService
import ru.itmo.clothesadvisor.service.ai.AiSelectionService
import ru.itmo.clothesadvisor.service.outfit.OutfitService
import ru.itmo.clothesadvisor.service.user.CurrentUserService
import ru.itmo.clothesadvisor.service.wardrobe.WardrobeItemPhotoService

@SpringBootTest(
    classes = [RecommendationServiceApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["spring.config.name=application-transport"],
)
@ActiveProfiles("transport-server")
@Import(RecommendationPayloadTests.TransportSecurity::class)
class RecommendationPayloadTests {
    @TestConfiguration
    class TransportSecurity {
        @Bean("syncSelectionProvider")
        fun selectionProvider(client: OpenAiSelectionClient) = ItemSelectionProvider(client::select)

        @Bean
        fun transportSecurity(http: org.springframework.security.config.web.server.ServerHttpSecurity) =
            http.csrf { it.disable() }.authorizeExchange { it.anyExchange().permitAll() }.build()
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import(ExternalClientsConfiguration::class)
    class ClientApplication

    @LocalServerPort private var port = 0
    @MockitoSpyBean private lateinit var provider: OpenAiSelectionClient

    @Test
    fun `twenty maximum images are loaded encoded and sent through production Feign and decoder`() {
        // given
        val storedAt = Instant.parse("2024-02-03T04:05:06Z")
        val weather = OutfitWeatherDto(BigDecimal("-12.3456789"), 1, BigDecimal.ZERO)
        val request = CreateAiOutfitRequest(
            "Maximum images", weather, null, occasion = "Work", preferredStyle = "Casual",
        )
        val candidateIds = (1L..20).toList()
        val selectedIds = listOf(20L, 1L)
        val users = mock(CurrentUserService::class.java)
        val items = mock(WardrobeItemRepository::class.java)
        val photos = mock(WardrobeItemPhotoRepository::class.java)
        val precipitation = mock(PrecipitationTypeRepository::class.java)
        val preferences = mock(StylePreferencesRepository::class.java)
        val content = mock(WardrobeItemPhotoService::class.java)
        val outfits = mock(OutfitService::class.java)
        val transactions = mock(PlatformTransactionManager::class.java)
        val candidates = candidateIds.map { id ->
            WardrobeItem(
                7, WardrobeCategory("TOP", "Top"), "Item $id", "White", "Cotton", storedAt,
            ).apply { this.id = id }
        }
        `when`(users.requireActive(7)).thenReturn(CurrentUser(7, "user", UserRole.USER))
        `when`(transactions.getTransaction(any())).thenAnswer { SimpleTransactionStatus() }
        `when`(precipitation.findById(1)).thenReturn(PrecipitationType("NONE", "None"))
        `when`(preferences.findByOwnerId(7)).thenReturn(
            StylePreferences(7, "c".repeat(2000), "a".repeat(2000), "w".repeat(2000), 3, storedAt, storedAt),
        )
        `when`(items.findAvailableOwnedItemsWithPhotos(7, 21)).thenReturn(candidates)
        `when`(items.lockOwnedItemsForShare(7, candidateIds)).thenReturn(candidates)
        candidateIds.forEach { id ->
            val photoId = id + 100
            val photo = WardrobeItemPhoto(
                id, UUID.randomUUID(), "image/jpeg", 10_000_000, storedAt,
            ).apply { this.id = photoId }
            `when`(photos.findFirstByItemIdOrderByIdAsc(id)).thenReturn(photo)
            `when`(photos.findByIdAndItemIdAndItemOwnerId(photoId, id, 7)).thenReturn(photo)
            `when`(content.getContent(7, id, photoId)).thenAnswer {
                WardrobeItemPhotoContent("image/jpeg", ByteArray(10_000_000) { id.toByte() })
            }
        }
        val serverRequest = ServerSelectionRequest(
            ServerSelectionWeather(BigDecimal("-12.3456789"), BigDecimal.ZERO, "NONE", "None"),
            emptyList(),
        )
        doAnswer { call ->
                val actual = call.getArgument<ServerSelectionRequest>(1)
                assertThat(actual.weather).isEqualTo(serverRequest.weather)
                assertThat(actual.occasion).isEqualTo("Work")
                assertThat(actual.preferredStyle).isEqualTo("Casual")
                assertThat(actual.stylePreferences)
                    .isEqualTo(ServerStylePreferences("c".repeat(2000), "a".repeat(2000), "w".repeat(2000)))
                assertThat(actual.candidates.map { it.itemId }).containsExactlyElementsOf(candidateIds)
                actual.candidates.forEach { candidate ->
                    assertThat(candidate.name).isEqualTo("Item ${candidate.itemId}")
                    assertThat(candidate.categoryCode).isEqualTo("TOP")
                    assertThat(candidate.categoryName).isEqualTo("Top")
                    assertThat(candidate.color).isEqualTo("White")
                    assertThat(candidate.material).isEqualTo("Cotton")
                    assertThat(candidate.contentType).isEqualTo("image/jpeg")
                    val decoded = candidate.imageBase64.open().buffered().use {
                        Base64.getDecoder().wrap(it).readAllBytes()
                    }
                    assertThat(decoded).hasSize(10_000_000)
                    assertThat(decoded.all { it == candidate.itemId.toByte() }).isTrue()
                }
                call.callRealMethod()
            }
            .`when`(provider).select(eq(SelectionGoal.OUTFIT) ?: SelectionGoal.OUTFIT, any(ServerSelectionRequest::class.java) ?: serverRequest)
        val expectedSave = CreateOutfitRequest(request.name, selectedIds, weather)
        val saved = OutfitResponse(
            80, 7, 7, OutfitSource.AI, request.name, selectedIds, weather, storedAt,
        )
        `when`(outfits.createAi(
            eq(7L), refEq(expectedSave) ?: expectedSave, eq("Work"), eq("Casual"), eq("Подходит для погоды."), org.mockito.Mockito.isNull(),
        )).thenReturn(saved)
        SpringApplicationBuilder(ClientApplication::class.java).run(
            "--spring.config.name=application-transport",
            "--spring.profiles.active=transport-client",
            "--spring.cloud.discovery.client.simple.instances.recommendation-service[0].uri=http://127.0.0.1:$port",
        ).use { context ->
            val service = AiOutfitService(
                AiSelectionService(
                    users, items, photos, precipitation, preferences, content,
                    context.getBean(RecommendationClient::class.java), transactions, SimpleMeterRegistry(), "user",
                ), outfits,
                ru.itmo.clothesadvisor.service.idempotency.IdempotencyService(
                    mock(ru.itmo.clothesadvisor.repository.idempotency.IdempotencyCommandRepository::class.java),
                    tools.jackson.databind.json.JsonMapper.builder().findAndAddModules().build(), transactions,
                ),
            )
            val pools = ManagementFactory.getMemoryPoolMXBeans()
            pools.forEach { it.resetPeakUsage() }

            // when
            val response = service.create(7, request)

            // then
            assertThat(response).isSameAs(saved)
            assertThat(response.itemIds).containsExactlyElementsOf(selectedIds)
            assertThat(providerFailure.get()).isNull()
            assertThat(providerImages.get()).isEqualTo(20)
            candidateIds.forEach { id -> verify(content).getContent(7, id, id + 100) }
            verify(provider).select(eq(SelectionGoal.OUTFIT) ?: SelectionGoal.OUTFIT, any(ServerSelectionRequest::class.java) ?: serverRequest)
            verify(outfits).createAi(
                eq(7L), refEq(expectedSave) ?: expectedSave, eq("Work"), eq("Casual"), eq("Подходит для погоды."), org.mockito.Mockito.isNull(),
            )
            println(
                "Maximum-image preparation and transport memory pool peaks: " +
                    pools.joinToString { "${it.name}=${it.peakUsage.used}" },
            )
        }
    }

    companion object {
        private val directory = Files.createTempDirectory("transport-fake-credentials-").toRealPath().also {
            Files.writeString(it.resolve("registration.json"), """{"client_id":"transport-test"}""")
            Files.writeString(it.resolve("credentials.json"), """{"client_id":"transport-test","issuer":"https://auth.openai.com","subject":"test","scopes":["resource.invoke","chatgpt.tokens.use.direct"],"expires_at":"2999-01-01T00:00:00Z","access_token":"local-fake-token"}""")
        }
        private val providerImages = java.util.concurrent.atomic.AtomicInteger()
        private val providerFailure = java.util.concurrent.atomic.AtomicReference<Throwable?>()
        private val executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()
        private val fakeProvider = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            this.executor = Companion.executor
            createContext("/responses") { exchange ->
                exchange.use {
                    try {
                        val mapper = tools.jackson.databind.json.JsonMapper.builder().build()
                        mapper.createParser(it.requestBody).use { parser ->
                            while (parser.nextToken() != null) {
                                if (parser.currentToken() == tools.jackson.core.JsonToken.PROPERTY_NAME &&
                                    parser.currentName() == "image_url"
                                ) {
                                    parser.nextToken()
                                    val value = parser.string
                                    assertThat(value).startsWith("data:image/jpeg;base64,")
                                    val bytes = Base64.getDecoder().decode(value.substringAfter(','))
                                    val id = providerImages.incrementAndGet()
                                    assertThat(bytes).hasSize(10_000_000)
                                    val expected = java.security.MessageDigest.getInstance("SHA-256")
                                    val chunk = ByteArray(8192) { id.toByte() }
                                    var remaining = 10_000_000
                                    while (remaining > 0) {
                                        val count = minOf(remaining, chunk.size)
                                        expected.update(chunk, 0, count)
                                        remaining -= count
                                    }
                                    assertThat(java.security.MessageDigest.getInstance("SHA-256").digest(bytes))
                                        .isEqualTo(expected.digest())
                                }
                            }
                        }
                        val text = mapper.writeValueAsString(mapOf("itemIds" to listOf(20, 1), "explanation" to "Подходит для погоды."))
                        val response = "data: " + mapper.writeValueAsString(mapOf(
                            "type" to "response.completed", "response" to mapOf(
                                "status" to "completed", "output" to listOf(mapOf(
                                    "type" to "message", "role" to "assistant", "status" to "completed",
                                    "content" to listOf(mapOf("type" to "output_text", "text" to text)),
                                )),
                            ),
                        )) + "\n\n"
                        val bytes = response.toByteArray()
                        it.responseHeaders.add("Content-Type", "text/event-stream")
                        it.sendResponseHeaders(200, bytes.size.toLong())
                        it.responseBody.write(bytes)
                    } catch (error: Throwable) {
                        providerFailure.set(error)
                        runCatching { it.sendResponseHeaders(500, -1) }
                    }
                }
            }
            start()
        }

        @JvmStatic @DynamicPropertySource
        fun providerProperties(registry: DynamicPropertyRegistry) {
            registry.add("app.openai.endpoint") { "http://127.0.0.1:${fakeProvider.address.port}/responses" }
            registry.add("app.openai.credentials-directory") { directory.toString() }
            registry.add("app.openai.model") { "local-test-model" }
            registry.add("app.openai.timeout") { "90s" }
        }

        @JvmStatic @AfterAll
        fun close() {
            fakeProvider.stop(0)
            executor.close()
            Files.deleteIfExists(directory.resolve("credentials.json"))
            Files.deleteIfExists(directory.resolve("registration.json"))
            Files.deleteIfExists(directory)
        }
    }
}
