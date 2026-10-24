package ru.itmo.clothesadvisor.service.outfit

import jakarta.validation.ConstraintViolationException
import jakarta.validation.Validation
import ru.itmo.clothesadvisor.config.idempotencyService
import java.math.BigDecimal
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyList
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.SimpleTransactionStatus
import ru.itmo.clothesadvisor.client.account.BlockingAccountClient
import ru.itmo.clothesadvisor.dto.outfit.CreateOutfitRequest
import ru.itmo.clothesadvisor.dto.outfit.OutfitWeatherDto
import ru.itmo.clothesadvisor.model.EntityNotFoundException
import ru.itmo.clothesadvisor.model.access.AccountUnavailableException
import ru.itmo.clothesadvisor.model.outfit.Outfit
import ru.itmo.clothesadvisor.model.outfit.OutfitItem
import ru.itmo.clothesadvisor.model.outfit.OutfitSource
import ru.itmo.clothesadvisor.repository.outfit.OutfitItemRepository
import ru.itmo.clothesadvisor.repository.outfit.OutfitRepository
import ru.itmo.clothesadvisor.repository.outfit.OutfitWeatherRepository
import ru.itmo.clothesadvisor.repository.wardrobe.WardrobeItemRepository
import ru.itmo.clothesadvisor.repository.weather.PrecipitationTypeRepository
import ru.itmo.clothesadvisor.service.rating.OutfitRatingService

class OutfitServiceTests {
    private val outfits = mock(OutfitRepository::class.java)
    private val composition = mock(OutfitItemRepository::class.java)
    private val weather = mock(OutfitWeatherRepository::class.java)
    private val wardrobe = mock(WardrobeItemRepository::class.java)
    private val precipitation = mock(PrecipitationTypeRepository::class.java)
    private val access = mock(BlockingAccountClient::class.java)
    private val ratings = mock(OutfitRatingService::class.java)
    private val validation = Validation.buildDefaultValidatorFactory()
    private val transactions = mock(PlatformTransactionManager::class.java)
    private val service =
        OutfitService(
            outfits,
            composition,
            weather,
            wardrobe,
            precipitation,
            access,
            ratings,
            validation.validator,
            idempotencyService(transactions),
            transactions,
        )
    private val storedAt = Instant.parse("2026-01-01T00:00:00Z")

    private fun request(
        ids: List<Long?> = listOf(2, 1),
        name: String = "Daily",
        wind: BigDecimal = BigDecimal.ZERO,
    ) = CreateOutfitRequest(name, ids, OutfitWeatherDto(BigDecimal("-123.123456789"), 1, wind))

    @AfterEach fun closeValidation() = validation.close()

    @BeforeEach
    fun prepareTransactions() {
        `when`(transactions.getTransaction(any())).thenAnswer { SimpleTransactionStatus() }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `account denial or outage precedes transaction and outfit writes`(unavailable: Boolean) {
        // given
        val denial = if (unavailable) AccountUnavailableException() else EntityNotFoundException()
        doThrow(denial).`when`(access).requireStylistAccess(2, 7)

        // when
        val failure = catchThrowable { service.createForClient(2, 7, request()) }

        // then
        assertThat(failure).isSameAs(denial)
        verifyNoInteractions(transactions, outfits, composition, weather, wardrobe, precipitation)
    }

    @Test
    fun `invalid composition name and wind are rejected without database writes`() {
        for (ids in
            listOf(
                emptyList(),
                listOf(1L, 1L),
                listOf(null),
                listOf(0L),
                List(51) { it.toLong() + 1 },
            )) {

            // given: invalid composition IDs
            val input = request(ids)

            // when
            val failure = catchThrowable { service.create(7, input) }

            // then
            assertThat(failure).isInstanceOf(InvalidOutfitRequestException::class.java)
        }
        for (input in
            listOf(
                request(name = " "),
                request(name = "x".repeat(301)),
                request(wind = BigDecimal("-0.1")),
            )) {

            // given: an invalid name or wind speed in input

            // when
            val failure = catchThrowable { service.create(7, input) }

            // then
            assertThat(failure).isInstanceOf(ConstraintViolationException::class.java)
        }
        verifyNoInteractions(outfits, composition, weather, wardrobe, precipitation)
    }

    @Test
    fun `unknown precipitation is rejected and foreign composition is hidden`() {

        // given: the precipitation type does not exist

        // when
        val unknownPrecipitation = catchThrowable { service.create(7, request()) }

        // then
        assertThat(unknownPrecipitation).isInstanceOf(InvalidOutfitRequestException::class.java)

        // given
        `when`(precipitation.existsById(1)).thenReturn(true)
        `when`(wardrobe.countByOwnerIdAndIdIn(7, listOf(2, 1))).thenReturn(1)

        // when
        val foreignComposition = catchThrowable { service.create(7, request()) }

        // then
        assertThat(foreignComposition).isInstanceOf(EntityNotFoundException::class.java)
        verifyNoInteractions(outfits, composition, weather)
    }

    @Test
    fun `create preserves composition order and returns database creation time`() {

        // given
        val stored = Outfit(7, 7, OutfitSource.USER, "Daily", storedAt).apply { id = 11 }
        `when`(precipitation.existsById(1)).thenReturn(true)
        `when`(wardrobe.countByOwnerIdAndIdIn(7, listOf(2, 1))).thenReturn(2)
        `when`(outfits.create(7, 7, OutfitSource.USER, "Daily")).thenReturn(stored)
        var savedItems = emptyList<OutfitItem>()
        doAnswer {
                savedItems = it.getArgument<Iterable<OutfitItem>>(0).toList()
                null
            }
            .`when`(composition)
            .insertAll(anyList())

        // when
        val response = service.create(7, request())

        // then
        assertThat(response.createdAt).isEqualTo(storedAt)
        assertThat(savedItems.map { it.id.wardrobeItemId to it.position })
            .containsExactly(2L to 0, 1L to 1)
        assertThat(response.itemIds).containsExactly(2, 1)
    }

    @Test
    fun `AI creation persists context and returns stored values`() {
        // given
        val stored = Outfit(7, 7, OutfitSource.AI, "Daily", storedAt, "  Work  ", "").apply { id = 11 }
        `when`(precipitation.existsById(1)).thenReturn(true)
        `when`(wardrobe.countByOwnerIdAndIdIn(7, listOf(2, 1))).thenReturn(2)
        `when`(outfits.create(7, 7, OutfitSource.AI, "Daily", "  Work  ", "")).thenReturn(stored)

        // when
        val response = service.createAi(7, request(), "  Work  ", "")

        // then
        assertThat(response.occasion).isEqualTo(stored.occasion)
        assertThat(response.preferredStyle).isEqualTo(stored.preferredStyle)
        verify(outfits).create(7, 7, OutfitSource.AI, "Daily", "  Work  ", "")
        verify(composition).insertAll(anyList())
    }

    @Test
    fun `deletion locks outfit and archives ratings before deleting`() {

        // given
        val stored = Outfit(7, 7, OutfitSource.USER, "Daily", storedAt).apply { id = 11 }
        `when`(outfits.findLockedByIdAndOwnerId(11, 7)).thenReturn(stored)

        // when
        service.delete(7, 11)

        // then
        inOrder(outfits, ratings).apply {
            verify(outfits).findLockedByIdAndOwnerId(11, 7)
            verify(ratings).archiveForDeletion(11)
            verify(outfits).delete(stored)
        }
    }
}
