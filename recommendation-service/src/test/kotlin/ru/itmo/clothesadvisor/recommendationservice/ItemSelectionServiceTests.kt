package ru.itmo.clothesadvisor.recommendationservice

import java.math.BigDecimal
import java.util.Base64
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.mockito.Mockito.mock
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import ru.itmo.clothesadvisor.client.ai.AiSelectionException
import ru.itmo.clothesadvisor.client.ai.AiSelectionFailure
import ru.itmo.clothesadvisor.client.ai.OpenAiSelectionClient
import ru.itmo.clothesadvisor.client.ai.SelectionGoal
import ru.itmo.clothesadvisor.dto.ai.ItemSelectionCandidate
import ru.itmo.clothesadvisor.dto.ai.ItemSelectionRequest
import ru.itmo.clothesadvisor.dto.ai.ItemSelectionResponse
import ru.itmo.clothesadvisor.dto.ai.ItemSelectionStylePreferences
import ru.itmo.clothesadvisor.dto.ai.ItemSelectionWeather
import ru.itmo.clothesadvisor.recommendationservice.service.ItemSelectionService

class ItemSelectionServiceTests : ru.itmo.clothesadvisor.dto.ai.SelectionPhotoFixture() {
    private val provider = mock(OpenAiSelectionClient::class.java)
    private val service = ItemSelectionService(provider)
    private val candidate =
        ItemSelectionCandidate(7, "Shirt", "TOP", "Top", "White", "Cotton", "image/png", image())
    private val request = ItemSelectionRequest(
        ItemSelectionWeather(BigDecimal("-1.23456789"), BigDecimal.ZERO, "NONE", "None"),
        listOf(candidate, candidate.copy(itemId = 9)),
    )

    @Test
    fun `valid selection keeps provider order and unchanged decimal weather`() {
        // given
        `when`(provider.select(SelectionGoal.OUTFIT, request)).thenReturn(ItemSelectionResponse(listOf(9, 7), "Подходит для погоды."))

        // when
        val response = service.selectOutfit(request)

        // then
        assertThat(response.itemIds).containsExactly(9, 7)
        verify(provider).select(SelectionGoal.OUTFIT, request)
    }

    @ParameterizedTest
    @EnumSource(SelectionGoal::class)
    fun `optional context accepts null empty and maximum values unchanged`(goal: SelectionGoal) {
        // given
        val requests = listOf(request, request.copy(occasion = "", preferredStyle = ""),
            request.copy(occasion = "o".repeat(300), preferredStyle = "s".repeat(300)))
        requests.forEach { input ->
            `when`(provider.select(goal, input)).thenReturn(ItemSelectionResponse(listOf(7), "Подходит для погоды."))

            // when
            val response = select(goal, input)

            // then
            assertThat(response.itemIds).containsExactly(7)
            verify(provider).select(goal, input)
        }
    }

    @ParameterizedTest
    @EnumSource(SelectionGoal::class)
    fun `optional preferences accept absent empty and maximum values unchanged`(goal: SelectionGoal) {
        // given
        val inputs = listOf(request, request.copy(stylePreferences = ItemSelectionStylePreferences("", "", "")),
            request.copy(stylePreferences = ItemSelectionStylePreferences("c".repeat(2000), "a".repeat(2000), "w".repeat(2000))))
        inputs.forEach { input ->
            `when`(provider.select(goal, input)).thenReturn(ItemSelectionResponse(listOf(7), "Подходит."))

            // when
            val response = select(goal, input)

            // then
            assertThat(response.itemIds).containsExactly(7)
            verify(provider).select(goal, input)
        }
    }

    @ParameterizedTest
    @EnumSource(SelectionGoal::class)
    fun `each oversized preference is rejected before provider`(goal: SelectionGoal) {
        // given
        val preferences = ItemSelectionStylePreferences("", "", "")
        val invalid = listOf(
            preferences.copy(preferredColorsAndMaterials = "c".repeat(2001)),
            preferences.copy(avoidances = "a".repeat(2001)),
            preferences.copy(additionalWishes = "w".repeat(2001)),
        )
        invalid.forEach { input ->
            // when
            val failure = catchThrowable { select(goal, request.copy(stylePreferences = input)) }

            // then
            assertFailure(AiSelectionFailure.INVALID_REQUEST, failure)
        }
        verifyNoInteractions(provider)
    }

    @ParameterizedTest
    @EnumSource(SelectionGoal::class)
    fun `oversized context is rejected before provider`(goal: SelectionGoal) {
        // given
        val requests = listOf(request.copy(occasion = "o".repeat(301)),
            request.copy(preferredStyle = "s".repeat(301)))
        requests.forEach { input ->
            // when
            val failure = catchThrowable { select(goal, input) }

            // then
            assertFailure(AiSelectionFailure.INVALID_REQUEST, failure)
        }
        verifyNoInteractions(provider)
    }

    @ParameterizedTest
    @EnumSource(SelectionGoal::class)
    fun `invalid candidates weather and images fail before provider`(goal: SelectionGoal) {
        // given
        val invalid = listOf(
            request.copy(candidates = emptyList()),
            request.copy(candidates = (1L..21).map { candidate.copy(itemId = it) }),
            request.copy(candidates = listOf(candidate, candidate)),
            request.copy(weather = request.weather.copy(windSpeedMps = BigDecimal("-0.01"))),
        ) + listOf(
            candidate.copy(itemId = 0), candidate.copy(itemId = -1),
            candidate.copy(contentType = "image/gif"),
        ).map { request.copy(candidates = listOf(it)) }

        invalid.forEach {
            // when
            val failure = catchThrowable { select(goal, it) }

            // then
            assertFailure(AiSelectionFailure.INVALID_REQUEST, failure)
        }
        verifyNoInteractions(provider)
    }

    @ParameterizedTest
    @EnumSource(SelectionGoal::class)
    fun `maximum image and twenty candidates reach provider`(goal: SelectionGoal) {
        // given
        val image = selectionPhotos.add(ByteArray(10_000_000))
        val maximum = request.copy(
            candidates = (1L..20).map { candidate.copy(itemId = it, imageBase64 = image) },
        )
        `when`(provider.select(goal, maximum)).thenReturn(ItemSelectionResponse(listOf(20), "Подходит для погоды."))

        // when
        val result = select(goal, maximum)

        // then
        assertThat(result.itemIds).containsExactly(20)
    }

    @Test
    fun `provider failures retain their category`() {
        // given
        val failures = listOf(
            AiSelectionFailure.INVALID_RESPONSE,
            AiSelectionFailure.UNAVAILABLE, AiSelectionFailure.TIMEOUT,
        )

        failures.forEach { failure ->
            doThrow(AiSelectionException(failure)).`when`(provider).select(SelectionGoal.OUTFIT, request)

            // when
            val error = catchThrowable { service.selectOutfit(request) }

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
            val failure = catchThrowable { service.selectOutfit(request.copy(requiredItemIds = ids)) }

            // then
            assertFailure(AiSelectionFailure.INVALID_REQUEST, failure)
        }
        verifyNoInteractions(provider)
    }

    @Test
    fun `empty and nonempty required IDs reach provider unchanged`() {
        // given
        for (ids in listOf(emptyList(), listOf(7L))) {
            val input = request.copy(requiredItemIds = ids)
            `when`(provider.select(SelectionGoal.OUTFIT, input)).thenReturn(ItemSelectionResponse(listOf(7, 9), "Подходит."))

            // when
            val result = service.selectOutfit(input)

            // then
            assertThat(result.itemIds).containsExactly(7, 9)
            verify(provider).select(SelectionGoal.OUTFIT, input)
        }
    }

    @Test
    fun `capsule preserves preferences weather candidates and provider order with server selected goal`() {
        // given
        val input = request.copy(
            occasion = "Отпуск", preferredStyle = "Городской",
            stylePreferences = ItemSelectionStylePreferences("Хлопок", "Шерсть", "Свободный крой"),
        )
        val expected = ItemSelectionResponse(listOf(9, 7), "Вещи сочетаются друг с другом.")
        `when`(provider.select(SelectionGoal.CAPSULE, input)).thenReturn(expected)

        // when
        val result = service.selectCapsule(input)

        // then
        assertThat(result).isEqualTo(expected)
        verify(provider).select(SelectionGoal.CAPSULE, input)
    }

    @Test
    fun `capsule rejects even empty required IDs before provider`() {
        // given
        for (ids in listOf(emptyList(), listOf(7L))) {
            // when
            val error = catchThrowable { service.selectCapsule(request.copy(requiredItemIds = ids)) }

            // then
            assertFailure(AiSelectionFailure.INVALID_REQUEST, error)
        }
        verifyNoInteractions(provider)
    }

    @Test
    fun `transport refusal becomes the scenario result at each service boundary`() {
        // given
        for (goal in SelectionGoal.entries) {
            doThrow(AiSelectionException(AiSelectionFailure.NO_SELECTION)).`when`(provider).select(goal, request)

            // when
            val error = catchThrowable {
                if (goal == SelectionGoal.OUTFIT) service.selectOutfit(request) else service.selectCapsule(request)
            }

            // then
            assertFailure(if (goal == SelectionGoal.OUTFIT) AiSelectionFailure.NO_OUTFIT else AiSelectionFailure.NO_CAPSULE, error)
        }
    }

    @Test
    fun `capsule retains transport error categories`() {
        // given
        for (failure in listOf(AiSelectionFailure.INVALID_RESPONSE, AiSelectionFailure.UNAVAILABLE, AiSelectionFailure.TIMEOUT)) {
            doThrow(AiSelectionException(failure)).`when`(provider).select(SelectionGoal.CAPSULE, request)

            // when
            val error = catchThrowable { service.selectCapsule(request) }

            // then
            assertFailure(failure, error)
        }
    }

    private fun select(goal: SelectionGoal, input: ItemSelectionRequest) =
        if (goal == SelectionGoal.OUTFIT) service.selectOutfit(input) else service.selectCapsule(input)

    private fun assertFailure(expected: AiSelectionFailure, error: Throwable?) {
        assertThat(error).isInstanceOfSatisfying(AiSelectionException::class.java) {
            assertThat(it.failure).isEqualTo(expected)
        }
    }
}
