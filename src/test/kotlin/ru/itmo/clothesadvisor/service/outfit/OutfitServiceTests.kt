package ru.itmo.clothesadvisor.service.outfit

import jakarta.validation.ConstraintViolationException
import jakarta.validation.Validation
import java.math.BigDecimal
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyList
import org.mockito.Mockito.*
import ru.itmo.clothesadvisor.dto.outfit.*
import ru.itmo.clothesadvisor.model.EntityNotFoundException
import ru.itmo.clothesadvisor.model.outfit.*
import ru.itmo.clothesadvisor.repository.outfit.*
import ru.itmo.clothesadvisor.repository.wardrobe.WardrobeItemRepository
import ru.itmo.clothesadvisor.repository.weather.PrecipitationTypeRepository
import ru.itmo.clothesadvisor.service.access.AccessGrantService
import ru.itmo.clothesadvisor.service.rating.OutfitRatingService

class OutfitServiceTests {
    private val outfits = mock(OutfitRepository::class.java)
    private val composition = mock(OutfitItemRepository::class.java)
    private val weather = mock(OutfitWeatherRepository::class.java)
    private val wardrobe = mock(WardrobeItemRepository::class.java)
    private val precipitation = mock(PrecipitationTypeRepository::class.java)
    private val access = mock(AccessGrantService::class.java)
    private val ratings = mock(OutfitRatingService::class.java)
    private val validation = Validation.buildDefaultValidatorFactory()
    private val service = OutfitService(outfits, composition, weather, wardrobe, precipitation, access, ratings, validation.validator)
    private val storedAt = Instant.parse("2026-01-01T00:00:00Z")
    private fun request(ids: List<Long?> = listOf(2, 1), name: String = "Daily", wind: BigDecimal = BigDecimal.ZERO) =
        CreateOutfitRequest(name, ids, OutfitWeatherDto(BigDecimal("-123.123456789"), 1, wind))

    @AfterEach fun closeValidation() = validation.close()

    @Test
    fun `invalid composition name and wind are rejected without database writes`() {
        for (ids in listOf(emptyList(), listOf(1L, 1L), listOf(null), listOf(0L), List(51) { it.toLong() + 1 })) {
            assertThatThrownBy { service.create(7, request(ids)) }.isInstanceOf(InvalidOutfitRequestException::class.java)
        }
        for (input in listOf(request(name = " "), request(name = "x".repeat(301)), request(wind = BigDecimal("-0.1")))) {
            assertThatThrownBy { service.create(7, input) }.isInstanceOf(ConstraintViolationException::class.java)
        }
        verifyNoInteractions(outfits, composition, weather, wardrobe, precipitation)
    }

    @Test
    fun `foreign composition is hidden and unknown precipitation rejected`() {
        assertThatThrownBy { service.create(7, request()) }.isInstanceOf(InvalidOutfitRequestException::class.java)
        `when`(precipitation.existsById(1)).thenReturn(true)
        `when`(wardrobe.countByOwnerIdAndIdIn(7, listOf(2, 1))).thenReturn(1)
        assertThatThrownBy { service.create(7, request()) }.isInstanceOf(EntityNotFoundException::class.java)
        verifyNoInteractions(outfits, composition, weather)
    }

    @Test
    fun `create preserves composition order and returns database creation time`() {
        val stored = Outfit(7, 7, OutfitSource.USER, "Daily", storedAt).apply { id = 11 }
        `when`(precipitation.existsById(1)).thenReturn(true)
        `when`(wardrobe.countByOwnerIdAndIdIn(7, listOf(2, 1))).thenReturn(2)
        `when`(outfits.create(7, 7, OutfitSource.USER, "Daily")).thenReturn(stored)
        var savedItems = emptyList<OutfitItem>()
        doAnswer { savedItems = it.getArgument<Iterable<OutfitItem>>(0).toList(); null }.`when`(composition).insertAll(anyList())
        val response = service.create(7, request())
        assertThat(response.createdAt).isEqualTo(storedAt)
        assertThat(savedItems.map { it.id.wardrobeItemId to it.position }).containsExactly(2L to 0, 1L to 1)
        assertThat(response.itemIds).containsExactly(2, 1)
    }

    @Test
    fun `deletion locks outfit and archives ratings before deleting`() {
        val stored = Outfit(7, 7, OutfitSource.USER, "Daily", storedAt).apply { id = 11 }
        `when`(outfits.findLockedByIdAndOwnerId(11, 7)).thenReturn(stored)
        service.delete(7, 11)
        inOrder(outfits, ratings).apply {
            verify(outfits).findLockedByIdAndOwnerId(11, 7)
            verify(ratings).archiveForDeletion(11)
            verify(outfits).delete(stored)
        }
    }
}
