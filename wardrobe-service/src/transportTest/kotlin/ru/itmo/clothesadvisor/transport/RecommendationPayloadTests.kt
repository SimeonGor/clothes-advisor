package ru.itmo.clothesadvisor.transport

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import java.lang.management.ManagementFactory
import java.math.BigDecimal
import java.time.Instant
import java.util.Base64
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
import ru.itmo.clothesadvisor.client.ai.OpenAiOutfitClient
import ru.itmo.clothesadvisor.client.recommendation.RecommendationClient
import ru.itmo.clothesadvisor.config.ExternalClientsConfiguration
import ru.itmo.clothesadvisor.dto.ai.CreateAiOutfitRequest
import ru.itmo.clothesadvisor.dto.ai.OutfitSelectionRequest as ServerSelectionRequest
import ru.itmo.clothesadvisor.dto.ai.OutfitSelectionResponse as ServerSelectionResponse
import ru.itmo.clothesadvisor.dto.ai.OutfitSelectionWeather as ServerSelectionWeather
import ru.itmo.clothesadvisor.dto.outfit.CreateOutfitRequest
import ru.itmo.clothesadvisor.dto.outfit.OutfitResponse
import ru.itmo.clothesadvisor.dto.outfit.OutfitWeatherDto
import ru.itmo.clothesadvisor.dto.user.CurrentUser
import ru.itmo.clothesadvisor.dto.wardrobe.WardrobeItemPhotoContent
import ru.itmo.clothesadvisor.model.outfit.OutfitSource
import ru.itmo.clothesadvisor.model.user.UserRole
import ru.itmo.clothesadvisor.model.wardrobe.WardrobeCategory
import ru.itmo.clothesadvisor.model.wardrobe.WardrobeItem
import ru.itmo.clothesadvisor.model.wardrobe.WardrobeItemPhoto
import ru.itmo.clothesadvisor.model.weather.PrecipitationType
import ru.itmo.clothesadvisor.recommendationservice.RecommendationServiceApplication
import ru.itmo.clothesadvisor.repository.wardrobe.WardrobeItemPhotoRepository
import ru.itmo.clothesadvisor.repository.wardrobe.WardrobeItemRepository
import ru.itmo.clothesadvisor.repository.weather.PrecipitationTypeRepository
import ru.itmo.clothesadvisor.service.ai.AiOutfitService
import ru.itmo.clothesadvisor.service.outfit.OutfitService
import ru.itmo.clothesadvisor.service.user.CurrentUserService
import ru.itmo.clothesadvisor.service.wardrobe.WardrobeItemPhotoService

@SpringBootTest(
    classes = [RecommendationServiceApplication::class, RecommendationPayloadTests.Provider::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["spring.config.name=application-transport"],
)
@ActiveProfiles("transport-server")
class RecommendationPayloadTests {
    @TestConfiguration
    class Provider {
        @Bean @Primary
        fun transportProvider(): OpenAiOutfitClient = mock(OpenAiOutfitClient::class.java)
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import(ExternalClientsConfiguration::class)
    class ClientApplication

    @LocalServerPort private var port = 0
    @Autowired private lateinit var provider: OpenAiOutfitClient

    @Test
    fun `twenty maximum images are loaded encoded and sent through production Feign and decoder`() {
        // given
        val storedAt = Instant.parse("2024-02-03T04:05:06Z")
        val weather = OutfitWeatherDto(BigDecimal("-12.3456789"), 1, BigDecimal.ZERO)
        val request = CreateAiOutfitRequest("Maximum images", weather, null)
        val candidateIds = (1L..20).toList()
        val selectedIds = listOf(20L, 1L)
        val users = mock(CurrentUserService::class.java)
        val items = mock(WardrobeItemRepository::class.java)
        val photos = mock(WardrobeItemPhotoRepository::class.java)
        val precipitation = mock(PrecipitationTypeRepository::class.java)
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
        `when`(items.findOwnedItemsWithPhotos(7, 21)).thenReturn(candidates)
        `when`(items.lockOwnedItemsForShare(7, candidateIds)).thenReturn(candidates)
        candidateIds.forEach { id ->
            val photoId = id + 100
            val photo = WardrobeItemPhoto(
                id, "photo-$id", "image/jpeg", 10_000_000, storedAt,
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
                val actual = call.getArgument<ServerSelectionRequest>(0)
                assertThat(actual.weather).isEqualTo(serverRequest.weather)
                assertThat(actual.candidates.map { it.itemId }).containsExactlyElementsOf(candidateIds)
                actual.candidates.forEach { candidate ->
                    assertThat(candidate.name).isEqualTo("Item ${candidate.itemId}")
                    assertThat(candidate.categoryCode).isEqualTo("TOP")
                    assertThat(candidate.categoryName).isEqualTo("Top")
                    assertThat(candidate.color).isEqualTo("White")
                    assertThat(candidate.material).isEqualTo("Cotton")
                    assertThat(candidate.contentType).isEqualTo("image/jpeg")
                    assertThat(candidate.imageBase64).hasSize(13_333_336)
                    val decoded = Base64.getDecoder().decode(candidate.imageBase64)
                    assertThat(decoded).hasSize(10_000_000)
                    assertThat(decoded.all { it == candidate.itemId.toByte() }).isTrue()
                }
                ServerSelectionResponse(selectedIds)
            }
            .`when`(provider).select(any(ServerSelectionRequest::class.java) ?: serverRequest)
        val expectedSave = CreateOutfitRequest(request.name, selectedIds, weather)
        val saved = OutfitResponse(
            80, 7, 7, OutfitSource.AI, request.name, selectedIds, weather, storedAt,
        )
        `when`(outfits.createAi(eq(7L), refEq(expectedSave) ?: expectedSave)).thenReturn(saved)
        SpringApplicationBuilder(ClientApplication::class.java).run(
            "--spring.config.name=application-transport",
            "--spring.profiles.active=transport-client",
            "--spring.cloud.discovery.client.simple.instances.recommendation-service[0].uri=http://127.0.0.1:$port",
        ).use { context ->
            val service = AiOutfitService(
                users, items, photos, precipitation, content,
                context.getBean(RecommendationClient::class.java), outfits, transactions,
                SimpleMeterRegistry(), "user",
            )
            val pools = ManagementFactory.getMemoryPoolMXBeans()
            pools.forEach { it.resetPeakUsage() }

            // when
            val response = service.create(7, request)

            // then
            assertThat(response).isSameAs(saved)
            assertThat(response.itemIds).containsExactlyElementsOf(selectedIds)
            candidateIds.forEach { id -> verify(content).getContent(7, id, id + 100) }
            verify(provider).select(any(ServerSelectionRequest::class.java) ?: serverRequest)
            verify(outfits).createAi(eq(7L), refEq(expectedSave) ?: expectedSave)
            println(
                "Maximum-image preparation and transport memory pool peaks: " +
                    pools.joinToString { "${it.name}=${it.peakUsage.used}" },
            )
        }
    }
}
