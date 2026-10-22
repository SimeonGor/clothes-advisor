package ru.itmo.clothesadvisor.transport

import java.lang.management.ManagementFactory
import java.math.BigDecimal
import java.util.Base64
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
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
import ru.itmo.clothesadvisor.client.ai.OpenAiOutfitClient
import ru.itmo.clothesadvisor.client.recommendation.RecommendationClient
import ru.itmo.clothesadvisor.config.ExternalClientsConfiguration
import ru.itmo.clothesadvisor.dto.ai.OutfitSelectionCandidate as ServerSelectionCandidate
import ru.itmo.clothesadvisor.dto.ai.OutfitSelectionRequest as ServerSelectionRequest
import ru.itmo.clothesadvisor.dto.ai.OutfitSelectionResponse as ServerSelectionResponse
import ru.itmo.clothesadvisor.dto.ai.OutfitSelectionWeather as ServerSelectionWeather
import ru.itmo.clothesadvisor.dto.recommendation.OutfitSelectionCandidate
import ru.itmo.clothesadvisor.dto.recommendation.OutfitSelectionRequest
import ru.itmo.clothesadvisor.dto.recommendation.OutfitSelectionWeather
import ru.itmo.clothesadvisor.recommendationservice.RecommendationServiceApplication

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
    fun `twenty maximum images pass production Feign and actual recommendation decoder`() {
        // given
        val image = Base64.getEncoder().encodeToString(ByteArray(10_000_000))
        val selection = OutfitSelectionRequest(
            OutfitSelectionWeather(BigDecimal("-12.3456789"), BigDecimal.ZERO, "NONE", "None"),
            (1L..20).map {
                OutfitSelectionCandidate(it, "Item $it", "TOP", "Top", "White", "Cotton", "image/jpeg", image)
            },
        )
        val expected = ServerSelectionRequest(
            ServerSelectionWeather(BigDecimal("-12.3456789"), BigDecimal.ZERO, "NONE", "None"),
            (1L..20).map {
                ServerSelectionCandidate(it, "Item $it", "TOP", "Top", "White", "Cotton", "image/jpeg", image)
            },
        )
        `when`(provider.select(expected)).thenReturn(ServerSelectionResponse(listOf(20, 1)))
        val pools = ManagementFactory.getMemoryPoolMXBeans()
        pools.forEach { it.resetPeakUsage() }
        SpringApplicationBuilder(ClientApplication::class.java).run(
            "--spring.config.name=application-transport",
            "--spring.profiles.active=transport-client",
            "--spring.cloud.discovery.client.simple.instances.recommendation-service[0].uri=http://127.0.0.1:$port",
        ).use { context ->
            // when
            val response = context.getBean(RecommendationClient::class.java).select(selection)

            // then
            assertThat(response.itemIds).containsExactly(20, 1)
            verify(provider).select(expected)
            println(
                "Maximum-payload memory pool peaks: " +
                    pools.joinToString { "${it.name}=${it.peakUsage.used}" },
            )
        }
    }
}
