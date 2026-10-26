package ru.itmo.clothesadvisor.recommendationservice

import java.math.BigDecimal
import java.util.Base64
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import ru.itmo.clothesadvisor.client.ai.AiOutfitException
import ru.itmo.clothesadvisor.client.ai.AiOutfitFailure
import ru.itmo.clothesadvisor.client.ai.OpenAiOutfitClient
import ru.itmo.clothesadvisor.dto.ai.OutfitSelectionCandidate
import ru.itmo.clothesadvisor.dto.ai.OutfitSelectionRequest
import ru.itmo.clothesadvisor.dto.ai.OutfitSelectionResponse
import ru.itmo.clothesadvisor.dto.ai.OutfitSelectionWeather
import ru.itmo.clothesadvisor.recommendationservice.service.OutfitSelectionService

class OutfitSelectionServiceTests {
    private val provider = mock(OpenAiOutfitClient::class.java)
    private val service = OutfitSelectionService(provider)
    private val candidate =
        OutfitSelectionCandidate(7, "Shirt", "TOP", "Top", "White", "Cotton", "image/png", "AQID")
    private val request = OutfitSelectionRequest(
        OutfitSelectionWeather(BigDecimal("-1.23456789"), BigDecimal.ZERO, "NONE", "None"),
        listOf(candidate, candidate.copy(itemId = 9)),
    )

    @Test
    fun `valid selection keeps provider order and unchanged decimal weather`() {
        // given
        `when`(provider.select(request)).thenReturn(OutfitSelectionResponse(listOf(9, 7), "Подходит для погоды."))

        // when
        val response = service.select(request)

        // then
        assertThat(response.itemIds).containsExactly(9, 7)
        verify(provider).select(request)
    }

    @Test
    fun `optional context accepts null empty and maximum values unchanged`() {
        // given
        val requests = listOf(request, request.copy(occasion = "", preferredStyle = ""),
            request.copy(occasion = "o".repeat(300), preferredStyle = "s".repeat(300)))
        requests.forEach { input ->
            `when`(provider.select(input)).thenReturn(OutfitSelectionResponse(listOf(7), "Подходит для погоды."))

            // when
            val response = service.select(input)

            // then
            assertThat(response.itemIds).containsExactly(7)
            verify(provider).select(input)
        }
    }

    @Test
    fun `oversized context is rejected before provider`() {
        // given
        val requests = listOf(request.copy(occasion = "o".repeat(301)),
            request.copy(preferredStyle = "s".repeat(301)))
        requests.forEach { input ->
            // when
            val failure = catchThrowable { service.select(input) }

            // then
            assertFailure(AiOutfitFailure.INVALID_REQUEST, failure)
        }
        verifyNoInteractions(provider)
    }

    @Test
    fun `invalid candidates weather and images fail before provider`() {
        // given
        val invalid = listOf(
            request.copy(candidates = emptyList()),
            request.copy(candidates = (1L..21).map { candidate.copy(itemId = it) }),
            request.copy(candidates = listOf(candidate, candidate)),
            request.copy(weather = request.weather.copy(windSpeedMps = BigDecimal("-0.01"))),
        ) + listOf(
            candidate.copy(itemId = 0), candidate.copy(itemId = -1),
            candidate.copy(contentType = "image/gif"), candidate.copy(imageBase64 = ""),
            candidate.copy(imageBase64 = "data:image/png;base64,AQID"),
            candidate.copy(imageBase64 = "!!!!"), candidate.copy(imageBase64 = "AQI"),
            candidate.copy(imageBase64 = "AR=="), candidate.copy(imageBase64 = "AQ-D"),
            candidate.copy(imageBase64 = Base64.getEncoder().encodeToString(ByteArray(10_000_001))),
        ).map { request.copy(candidates = listOf(it)) }

        invalid.forEach {
            // when
            val failure = catchThrowable { service.select(it) }

            // then
            assertFailure(AiOutfitFailure.INVALID_REQUEST, failure)
        }
        verifyNoInteractions(provider)
    }

    @Test
    fun `maximum image and twenty candidates reach provider`() {
        // given
        val image = Base64.getEncoder().encodeToString(ByteArray(10_000_000))
        val maximum = request.copy(
            candidates = (1L..20).map { candidate.copy(itemId = it, imageBase64 = image) },
        )
        `when`(provider.select(maximum)).thenReturn(OutfitSelectionResponse(listOf(20), "Подходит для погоды."))

        // when
        val result = service.select(maximum)

        // then
        assertThat(result.itemIds).containsExactly(20)
    }

    @Test
    fun `provider failures retain their category`() {
        // given
        val failures = listOf(
            AiOutfitFailure.NO_OUTFIT, AiOutfitFailure.INVALID_RESPONSE,
            AiOutfitFailure.UNAVAILABLE, AiOutfitFailure.TIMEOUT,
        )

        failures.forEach { failure ->
            doThrow(AiOutfitException(failure)).`when`(provider).select(request)

            // when
            val error = catchThrowable { service.select(request) }

            // then
            assertFailure(failure, error)
        }
    }

    @Test
    fun `invalid required IDs are rejected before provider`() {
        // given
        val invalid = listOf(listOf(null), listOf(0L), listOf(-1L), listOf(7L, 7L), listOf(99L))
        invalid.forEach { ids ->
            // when
            val failure = catchThrowable { service.select(request.copy(requiredItemIds = ids)) }

            // then
            assertFailure(AiOutfitFailure.INVALID_REQUEST, failure)
        }
        verifyNoInteractions(provider)
    }

    @Test
    fun `empty and nonempty required IDs reach provider unchanged`() {
        // given
        for (ids in listOf(emptyList(), listOf(7L))) {
            val input = request.copy(requiredItemIds = ids)
            `when`(provider.select(input)).thenReturn(OutfitSelectionResponse(listOf(7, 9), "Подходит."))

            // when
            val result = service.select(input)

            // then
            assertThat(result.itemIds).containsExactly(7, 9)
            verify(provider).select(input)
        }
    }

    private fun assertFailure(expected: AiOutfitFailure, error: Throwable?) {
        assertThat(error).isInstanceOfSatisfying(AiOutfitException::class.java) {
            assertThat(it.failure).isEqualTo(expected)
        }
    }
}
