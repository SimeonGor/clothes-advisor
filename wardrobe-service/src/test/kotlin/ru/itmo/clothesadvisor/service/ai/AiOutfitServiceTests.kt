package ru.itmo.clothesadvisor.service.ai

import ru.itmo.clothesadvisor.config.idempotencyService

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import java.math.BigDecimal
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.RETURNS_DEFAULTS
import org.mockito.Mockito.any
import org.mockito.Mockito.anyLong
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.eq
import org.mockito.Mockito.isNull
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.refEq
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.SimpleTransactionStatus
import ru.itmo.clothesadvisor.client.recommendation.RecommendationClient
import ru.itmo.clothesadvisor.dto.ai.CreateAiOutfitRequest
import ru.itmo.clothesadvisor.dto.ai.CreateAiOutfitReplacementRequest
import ru.itmo.clothesadvisor.dto.outfit.CreateOutfitRequest
import ru.itmo.clothesadvisor.dto.outfit.OutfitResponse
import ru.itmo.clothesadvisor.dto.outfit.OutfitWeatherDto
import ru.itmo.clothesadvisor.dto.recommendation.OutfitSelectionCandidate
import ru.itmo.clothesadvisor.dto.recommendation.OutfitSelectionRequest
import ru.itmo.clothesadvisor.dto.recommendation.OutfitSelectionResponse
import ru.itmo.clothesadvisor.dto.user.CurrentUser
import ru.itmo.clothesadvisor.dto.wardrobe.WardrobeItemPhotoContent
import ru.itmo.clothesadvisor.model.AccessDeniedException
import ru.itmo.clothesadvisor.model.EntityNotFoundException
import ru.itmo.clothesadvisor.model.ai.AiOutfitException
import ru.itmo.clothesadvisor.model.ai.AiOutfitFailure
import ru.itmo.clothesadvisor.model.outfit.OutfitSource
import ru.itmo.clothesadvisor.model.user.UserRole
import ru.itmo.clothesadvisor.model.wardrobe.WardrobeCategory
import ru.itmo.clothesadvisor.model.wardrobe.WardrobeItem
import ru.itmo.clothesadvisor.model.wardrobe.WardrobeItemPhoto
import ru.itmo.clothesadvisor.model.weather.PrecipitationType
import ru.itmo.clothesadvisor.repository.wardrobe.WardrobeItemPhotoRepository
import ru.itmo.clothesadvisor.repository.wardrobe.WardrobeItemRepository
import ru.itmo.clothesadvisor.repository.weather.PrecipitationTypeRepository
import ru.itmo.clothesadvisor.service.outfit.OutfitService
import ru.itmo.clothesadvisor.service.user.CurrentUserService
import ru.itmo.clothesadvisor.service.wardrobe.WardrobeItemPhotoService
import ru.itmo.clothesadvisor.storage.wardrobe.PhotoStorageUnavailableException

class AiOutfitServiceTests {
    private val users = mock(CurrentUserService::class.java)
    private val items = mock(WardrobeItemRepository::class.java)
    private val photos = mock(WardrobeItemPhotoRepository::class.java)
    private val precipitation = mock(PrecipitationTypeRepository::class.java)
    private val content = mock(WardrobeItemPhotoService::class.java)
    private var selected = listOf(11L)
    private var explanation: String? = null
    private var providerFailure: AiOutfitFailure? = null
    private var onSelection: () -> Unit = {}
    private var receivedSelection: OutfitSelectionRequest? = null
    private val provider =
        mock(RecommendationClient::class.java) { call ->
            if (call.method.name == "select") {
                receivedSelection = call.getArgument(0)
                providerFailure?.let { throw AiOutfitException(it) }
                onSelection()
                OutfitSelectionResponse(selected, explanation)
            } else RETURNS_DEFAULTS.answer(call)
        }
    private var savedRequest: CreateOutfitRequest? = null
    private var savedOccasion: String? = null
    private var savedStyle: String? = null
    private var savedExplanation: String? = null
    private var savedSourceId: Long? = null
    private val storedAt = Instant.parse("2024-02-03T04:05:06Z")
    private val weather = OutfitWeatherDto(BigDecimal.ONE, 1, BigDecimal.ZERO)
    private val saved =
        OutfitResponse(80, 7, 7, OutfitSource.AI, "Daily", listOf(11), weather, storedAt)
    private val outfits =
        mock(OutfitService::class.java) { call ->
            if (call.method.name == "createAi") {
                savedRequest = call.getArgument(1)
                savedOccasion = call.getArgument(2)
                savedStyle = call.getArgument(3)
                savedExplanation = call.getArgument(4)
                savedSourceId = call.getArgument(5)
                saved
            } else RETURNS_DEFAULTS.answer(call)
        }
    private val transactions = mock(PlatformTransactionManager::class.java)
    private val meters = SimpleMeterRegistry()
    private val service =
        AiOutfitService(
            users,
            items,
            photos,
            precipitation,
            content,
            provider,
            outfits,
            idempotencyService(transactions),
            transactions,
            meters,
            "user",
        )
    private val owner = CurrentUser(7, "user", UserRole.USER)
    private val item =
        WardrobeItem(7, WardrobeCategory("TOP", "Top"), "Shirt", "White", "Cotton", storedAt)
            .apply { id = 11 }
    private val photo = WardrobeItemPhoto(11, "key", "image/png", 3, storedAt).apply { id = 21 }

    private fun request(ids: List<Long?>? = null) = CreateAiOutfitRequest("Daily", weather, ids)

    @BeforeEach
    fun prepare() {
        `when`(transactions.getTransaction(any())).thenAnswer { SimpleTransactionStatus() }
        `when`(users.requireActive(7)).thenReturn(owner)
        `when`(precipitation.findById(1)).thenReturn(PrecipitationType("NONE", "None"))
        `when`(items.findOwnedItemsWithPhotos(7, 21)).thenReturn(listOf(item))
        `when`(items.lockOwnedItemsForShare(7, listOf(11))).thenReturn(listOf(item))
        `when`(photos.findFirstByItemIdOrderByIdAsc(11)).thenReturn(photo)
        `when`(photos.findByIdAndItemIdAndItemOwnerId(21, 11, 7)).thenReturn(photo)
        `when`(content.getContent(7, 11, 21))
            .thenReturn(WardrobeItemPhotoContent("image/png", byteArrayOf(1, 2, 3)))
    }

    @Test
    fun `provider selection is saved with original weather and returns the stored outfit`() {

        // given: the connected owner has one candidate with a photo

        // when
        val response = service.create(7, request())

        // then
        assertThat(response).isSameAs(saved)
        assertThat(savedRequest!!.itemIds).containsExactly(11)
        assertThat(savedRequest!!.weather).isSameAs(weather)
        assertThat(savedExplanation).isNull()
        assertThat(receivedSelection!!.requiredItemIds).isNull()
        assertThat(savedSourceId).isNull()
        assertThat(receivedSelection!!.candidates.single())
            .isEqualTo(
                OutfitSelectionCandidate(
                    11,
                    "Shirt",
                    "TOP",
                    "Top",
                    "White",
                    "Cotton",
                    "image/png",
                    "AQID",
                ),
            )
        assertThat(receivedSelection!!.weather.precipitationCode).isEqualTo("NONE")
        assertThat(receivedSelection!!.weather.precipitationName).isEqualTo("None")
        verify(photos).findByIdAndItemIdAndItemOwnerId(21, 11, 7)
        assertThat(meters.get("wardrobe.ai.active").gauge().value()).isZero()
        assertThat(meters.get("wardrobe.ai.rejected").counter().count()).isZero()
        assertThat(meters.get("wardrobe.ai.duration").tag("outcome", "success").timer().count())
            .isEqualTo(1)
        assertThat(meters.get("wardrobe.ai.duration").tag("outcome", "failure").timer().count())
            .isZero()
    }

    @Test
    fun `context reaches recommendation and persistence without trimming`() {
        // given
        val request = CreateAiOutfitRequest("Daily", weather, occasion = "  Work  ", preferredStyle = "")
        explanation = "  Тёплая рубашка для прохладного дня.  "

        // when
        service.create(7, request)

        // then
        assertThat(receivedSelection!!.occasion).isEqualTo("  Work  ")
        assertThat(receivedSelection!!.preferredStyle).isEmpty()
        assertThat(savedOccasion).isEqualTo("  Work  ")
        assertThat(savedStyle).isEmpty()
        assertThat(savedExplanation).isEqualTo(explanation)
    }

    @Test
    fun `inactive or missing owner is denied before any local transaction`() {
        for (reason in listOf(AccessDeniedException(), EntityNotFoundException())) {
            // given
            doThrow(reason).`when`(users).requireActive(7)

            // when
            val denied = catchThrowable { service.create(7, request()) }

            // then
            assertThat(denied).isInstanceOf(AccessDeniedException::class.java)
            verifyNoInteractions(transactions, provider, content, outfits)
            assertThat(meters.get("wardrobe.ai.active").gauge().value()).isZero()
            assertThat(meters.get("wardrobe.ai.rejected").counter().count()).isZero()
            assertThat(meters.get("wardrobe.ai.duration").timers()).allSatisfy {
                assertThat(it.count()).isZero()
            }
        }
    }

    @Test
    fun `active non user roles are denied and disconnected owner is unavailable`() {
        for (role in listOf(UserRole.ADMIN, UserRole.STYLIST)) {
            // given
            doReturn(owner.copy(role = role)).`when`(users).requireActive(7)

            // when
            val denied = catchThrowable { service.create(7, request()) }

            // then
            assertThat(denied).isInstanceOf(AccessDeniedException::class.java)
        }

        // given
        doReturn(owner.copy(login = "other")).`when`(users).requireActive(7)

        // when
        val unavailable = catchThrowable { service.create(7, request()) }

        // then
        assertFailure(AiOutfitFailure.UNAVAILABLE, unavailable)
        verifyNoInteractions(transactions, provider, content, outfits)
    }

    @Test
    fun `blank configured login is unavailable before any local transaction`() {
        // given
        val unconfigured =
            AiOutfitService(
                users, items, photos, precipitation, content, provider, outfits, idempotencyService(transactions), transactions,
                SimpleMeterRegistry(), " ",
            )

        // when
        val failure = catchThrowable { unconfigured.create(7, request()) }

        // then
        assertFailure(AiOutfitFailure.UNAVAILABLE, failure)
        verifyNoInteractions(transactions, provider, content, outfits)
    }

    @Test
    fun `current user technical failure propagates unchanged`() {
        // given
        val unavailable = IllegalStateException("account unavailable")
        doThrow(unavailable).`when`(users).requireActive(7)

        // when
        val failure = catchThrowable { service.create(7, request()) }

        // then
        assertThat(failure).isSameAs(unavailable)
        verifyNoInteractions(transactions, provider, content, outfits)
    }

    @Test
    fun `invalid explicit IDs and foreign items stop before image reads`() {
        // given
        providerFailure = AiOutfitFailure.UNAVAILABLE

        for (ids in listOf(emptyList(), listOf(null), listOf(0L), listOf(11L, 11L))) {

            // given: invalid explicit candidate IDs

            // when
            val failure = catchThrowable { service.create(7, request(ids)) }

            // then
            assertFailure(AiOutfitFailure.INVALID_REQUEST, failure)
        }

        // when
        val foreignItem = catchThrowable { service.create(7, request(listOf(99))) }

        // then
        assertThat(foreignItem).isInstanceOf(EntityNotFoundException::class.java)
        verifyNoInteractions(content, outfits)
        assertThat(receivedSelection).isNull()
    }

    @Test
    fun `candidate limit stops after bounded query before photo and external reads`() {

        // given
        `when`(items.findOwnedItemsWithPhotos(7, 21)).thenReturn(List(21) { item })

        // when
        val failure = catchThrowable { service.create(7, request()) }

        // then
        assertFailure(AiOutfitFailure.INVALID_REQUEST, failure)
        verify(items).findOwnedItemsWithPhotos(7, 21)
        verifyNoInteractions(photos, content, outfits)

        // when
        doReturn(listOf(item)).`when`(items).findOwnedItemsWithPhotos(7, 21)
        val recovered = service.create(7, request())

        // then
        assertRecovered(recovered)
    }

    @Test
    fun `explicit items without photos are filtered before selection`() {

        // given
        val empty =
            WardrobeItem(7, item.category, "Empty", "Grey", "Wool", storedAt).apply { id = 12 }
        `when`(items.findAllByOwnerIdAndIdInOrderByIdAsc(7, listOf(11, 12)))
            .thenReturn(listOf(item, empty))

        // when
        val response = service.create(7, request(listOf(11, 12)))

        // then
        assertThat(response).isSameAs(saved)
        assertThat(receivedSelection!!.candidates.map { it.itemId }).containsExactly(11)
        verify(content, never()).getContent(eq(7L), eq(12L), anyLong())
    }

    @Test
    fun `empty filtered candidates do not call storage or select`() {

        // given
        `when`(photos.findFirstByItemIdOrderByIdAsc(11)).thenReturn(null)

        // when
        val failure = catchThrowable { service.create(7, request()) }

        // then
        assertFailure(AiOutfitFailure.INVALID_REQUEST, failure)
        verifyNoInteractions(content, outfits)
        assertThat(receivedSelection).isNull()
    }

    @Test
    fun `deleted source during image read becomes conflict and does not save`() {

        // given
        `when`(content.getContent(7, 11, 21)).thenThrow(EntityNotFoundException())

        // when
        val failure = catchThrowable { service.create(7, request()) }

        // then
        assertFailure(AiOutfitFailure.SOURCE_CHANGED, failure)
        verifyNoInteractions(outfits)
        assertThat(receivedSelection).isNull()
    }

    @Test
    fun `provider failure never reaches save or final locks`() {

        // given
        providerFailure = AiOutfitFailure.TIMEOUT

        // when
        val failure = catchThrowable { service.create(7, request()) }

        // then
        assertFailure(AiOutfitFailure.TIMEOUT, failure)
        verify(items, never()).lockOwnedItemsForShare(7, listOf(11))
        verifyNoInteractions(outfits)

        // when
        providerFailure = null
        val recovered = service.create(7, request())

        // then
        assertRecovered(recovered)
    }

    @Test
    fun `unavailable recommendation is reported after image reads without final locks or save`() {
        // given
        providerFailure = AiOutfitFailure.UNAVAILABLE

        // when
        val failure = catchThrowable { service.create(7, request()) }

        // then
        assertFailure(AiOutfitFailure.UNAVAILABLE, failure)
        verify(content).getContent(7, 11, 21)
        assertThat(receivedSelection).isNotNull()
        verify(items, never()).lockOwnedItemsForShare(7, listOf(11))
        verifyNoInteractions(outfits)
    }

    @Test
    fun `storage failure precedes unavailable recommendation without remote call or save`() {
        // given
        providerFailure = AiOutfitFailure.UNAVAILABLE
        `when`(content.getContent(7, 11, 21)).thenThrow(PhotoStorageUnavailableException())

        // when
        val failure = catchThrowable { service.create(7, request()) }

        // then
        assertThat(failure).isInstanceOf(PhotoStorageUnavailableException::class.java)
        verifyNoInteractions(provider, outfits)
        verify(items, never()).lockOwnedItemsForShare(7, listOf(11))

        // when
        providerFailure = null
        doReturn(WardrobeItemPhotoContent("image/png", byteArrayOf(1, 2, 3)))
            .`when`(content).getContent(7, 11, 21)
        val recovered = service.create(7, request())

        // then
        assertRecovered(recovered)
    }

    @Test
    fun `save failure releases admission for a later successful request`() {
        // given
        val unavailable = IllegalStateException("save unavailable")
        val expected = CreateOutfitRequest("Daily", listOf(11), weather)
        doThrow(unavailable).`when`(outfits).createAi(eq(7L), refEq(expected) ?: expected, isNull(), isNull(), isNull(), isNull())

        // when
        val failure = catchThrowable { service.create(7, request()) }

        // then
        assertThat(failure).isSameAs(unavailable)

        // when
        doReturn(saved).`when`(outfits).createAi(eq(7L), refEq(expected) ?: expected, isNull(), isNull(), isNull(), isNull())
        val recovered = service.create(7, request())

        // then
        assertRecovered(recovered)
    }

    @Test
    fun `empty duplicate or unoffered provider IDs fail before final locks and save`() {

        // given: an owned item that was not included in the request
        val unofferedItem =
            WardrobeItem(7, item.category, "Unselected", "Black", "Wool", storedAt).apply {
                id = 12
            }
        `when`(items.findAllByOwnerIdAndIdInOrderByIdAsc(7, listOf(11)))
            .thenReturn(listOf(item))

        for (itemIds in listOf(emptyList(), listOf(11L, 11L), listOf(unofferedItem.id!!))) {

            // given: invalid provider selection
            selected = itemIds

            // when
            val failure = catchThrowable { service.create(7, request(listOf(11))) }

            // then
            assertFailure(AiOutfitFailure.INVALID_RESPONSE, failure)
        }
        verify(items, never()).lockOwnedItemsForShare(7, listOf(11))
        verifyNoInteractions(outfits)
    }

    @Test
    fun `revoked actor after provider response prevents final locks and save`() {

        // given
        onSelection = { doThrow(AccessDeniedException()).`when`(users).requireActive(7) }

        // when
        val denied = catchThrowable { service.create(7, request()) }

        // then
        assertThat(denied).isInstanceOf(AccessDeniedException::class.java)
        verify(items, never()).lockOwnedItemsForShare(7, listOf(11))
        verifyNoInteractions(outfits)
    }

    @Test
    fun `changed login after provider response prevents final locks and save`() {
        // given
        onSelection = { doReturn(owner.copy(login = "other")).`when`(users).requireActive(7) }

        // when
        val failure = catchThrowable { service.create(7, request()) }

        // then
        assertFailure(AiOutfitFailure.UNAVAILABLE, failure)
        verify(items, never()).lockOwnedItemsForShare(7, listOf(11))
        verifyNoInteractions(outfits)
    }

    @Test
    fun `changed login during image read prevents recommendation and save`() {
        // given
        doAnswer {
                doReturn(owner.copy(login = "other")).`when`(users).requireActive(7)
                WardrobeItemPhotoContent("image/png", byteArrayOf(1, 2, 3))
            }
            .`when`(content)
            .getContent(7, 11, 21)

        // when
        val failure = catchThrowable { service.create(7, request()) }

        // then
        assertFailure(AiOutfitFailure.UNAVAILABLE, failure)
        verifyNoInteractions(provider, outfits)
    }

    @Test
    fun `deleted source at final check prevents save`() {
        // given
        `when`(photos.findByIdAndItemIdAndItemOwnerId(21, 11, 7)).thenReturn(null)

        // when
        val failure = catchThrowable { service.create(7, request()) }

        // then
        assertFailure(AiOutfitFailure.SOURCE_CHANGED, failure)
        verifyNoInteractions(outfits)
    }

    @Test
    fun `replacement excludes removed items adds retained items and preserves source context`() {
        // given
        prepareReplacement()
        selected = listOf(12, 11)

        // when
        service.replace(7, 80, CreateAiOutfitReplacementRequest("Replacement", listOf(13), listOf(12, 13)))

        // then
        assertThat(receivedSelection!!.candidates.map { it.itemId }).containsExactly(12, 11)
        assertThat(receivedSelection!!.requiredItemIds).containsExactly(11)
        assertThat(receivedSelection!!.occasion).isEqualTo("  Dinner  ")
        assertThat(receivedSelection!!.preferredStyle).isEqualTo("Classic")
        assertThat(savedRequest!!.name).isEqualTo("Replacement")
        assertThat(savedRequest!!.weather).isSameAs(weather)
        assertThat(savedRequest!!.itemIds).containsExactly(12, 11)
        assertThat(savedOccasion).isEqualTo("  Dinner  ")
        assertThat(savedStyle).isEqualTo("Classic")
        assertThat(savedSourceId).isEqualTo(80)
        verify(photos, never()).findFirstByItemIdOrderByIdAsc(13)
        verify(outfits, never()).delete(7, 80)
    }

    @Test
    fun `invalid replacement lists fail before storage`() {
        // given
        prepareReplacement()
        val invalid = listOf(emptyList(), listOf(null), listOf(0L), listOf(-1L), listOf(13L, 13L), listOf(12L))

        invalid.forEach { ids ->
            // when
            val failure = catchThrowable { service.replace(7, 80, CreateAiOutfitReplacementRequest("New", ids)) }

            // then
            assertFailure(AiOutfitFailure.INVALID_REQUEST, failure)
        }
        verifyNoInteractions(content, provider)
    }

    @Test
    fun `explicit foreign candidate is not masked by replacement filtering`() {
        // given
        prepareReplacement()

        // when
        val failure = catchThrowable {
            service.replace(7, 80, CreateAiOutfitReplacementRequest("New", listOf(13), listOf(12, 13, 99)))
        }

        // then
        assertThat(failure).isInstanceOf(EntityNotFoundException::class.java)
        verifyNoInteractions(content, provider)
    }

    @Test
    fun `retained item without a photo rejects replacement before storage`() {
        // given
        prepareReplacement()
        doReturn(null).`when`(photos).findFirstByItemIdOrderByIdAsc(11)

        // when
        val failure = catchThrowable {
            service.replace(7, 80, CreateAiOutfitReplacementRequest("New", listOf(13), listOf(12, 13)))
        }

        // then
        assertFailure(AiOutfitFailure.INVALID_REQUEST, failure)
        verifyNoInteractions(content, provider)
    }

    @Test
    fun `replacement needs at least one candidate besides retained items`() {
        // given
        prepareReplacement()
        doReturn(listOf(item)).`when`(items).findOwnedItemsWithPhotosExcluding(7, setOf(13L), 21)

        // when
        val failure = catchThrowable { service.replace(7, 80, CreateAiOutfitReplacementRequest("New", listOf(13))) }

        // then
        assertFailure(AiOutfitFailure.INVALID_REQUEST, failure)
        verifyNoInteractions(content, provider)
    }

    @Test
    fun `replacement rejects omitted retained items unchanged selection and excluded items`() {
        // given
        prepareReplacement()
        for (ids in listOf(listOf(12L), listOf(11L), listOf(11L, 13L))) {
            selected = ids

            // when
            val failure = catchThrowable {
                service.replace(7, 80, CreateAiOutfitReplacementRequest("New", listOf(13), listOf(12, 13)))
            }

            // then
            assertFailure(AiOutfitFailure.INVALID_RESPONSE, failure)
        }
        assertThat(savedRequest).isNull()
        verify(items, never()).lockOwnedItemsForShare(7, listOf(12, 11))
    }

    private fun prepareReplacement() {
        doReturn(saved.copy(itemIds = listOf(11, 13), occasion = "  Dinner  ", preferredStyle = "Classic"))
            .`when`(outfits).get(7, 80)
        val added = WardrobeItem(7, item.category, "New", "Black", "Wool", storedAt).apply { id = 12 }
        val removed = WardrobeItem(7, item.category, "Old", "Grey", "Wool", storedAt).apply { id = 13 }
        val addedPhoto = WardrobeItemPhoto(12, "new", "image/png", 3, storedAt).apply { id = 22 }
        doReturn(listOf(added, removed)).`when`(items).findAllByOwnerIdAndIdInOrderByIdAsc(7, listOf(12, 13))
        doReturn(listOf(item)).`when`(items).findAllByOwnerIdAndIdInOrderByIdAsc(7, setOf(11L))
        doReturn(listOf(added, item)).`when`(items).lockOwnedItemsForShare(7, listOf(12, 11))
        doReturn(addedPhoto).`when`(photos).findFirstByItemIdOrderByIdAsc(12)
        doReturn(addedPhoto).`when`(photos).findByIdAndItemIdAndItemOwnerId(22, 12, 7)
        doReturn(WardrobeItemPhotoContent("image/png", byteArrayOf(1, 2, 3))).`when`(content).getContent(7, 12, 22)
    }

    private fun assertRecovered(response: OutfitResponse) {
        assertThat(response).isSameAs(saved)
        assertThat(meters.get("wardrobe.ai.active").gauge().value()).isZero()
        assertThat(meters.get("wardrobe.ai.rejected").counter().count()).isZero()
        assertThat(meters.get("wardrobe.ai.duration").tag("outcome", "success").timer().count())
            .isEqualTo(1)
        assertThat(meters.get("wardrobe.ai.duration").tag("outcome", "failure").timer().count())
            .isEqualTo(1)
    }

    private fun assertFailure(expected: AiOutfitFailure, failure: Throwable?) {
        assertThat(failure).isInstanceOfSatisfying(AiOutfitException::class.java) {
            assertThat(it.failure).isEqualTo(expected)
        }
    }
}
