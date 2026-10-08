package ru.itmo.clothesadvisor.service.ai

import java.math.BigDecimal
import java.time.Instant
import org.assertj.core.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.SimpleTransactionStatus
import ru.itmo.clothesadvisor.client.ai.*
import ru.itmo.clothesadvisor.dto.ai.CreateAiOutfitRequest
import ru.itmo.clothesadvisor.dto.outfit.*
import ru.itmo.clothesadvisor.dto.wardrobe.WardrobeItemPhotoContent
import ru.itmo.clothesadvisor.model.AccessDeniedException
import ru.itmo.clothesadvisor.model.EntityNotFoundException
import ru.itmo.clothesadvisor.model.outfit.OutfitSource
import ru.itmo.clothesadvisor.model.user.*
import ru.itmo.clothesadvisor.model.wardrobe.*
import ru.itmo.clothesadvisor.model.weather.PrecipitationType
import ru.itmo.clothesadvisor.repository.user.AppUserRepository
import ru.itmo.clothesadvisor.repository.wardrobe.*
import ru.itmo.clothesadvisor.repository.weather.PrecipitationTypeRepository
import ru.itmo.clothesadvisor.service.outfit.OutfitService
import ru.itmo.clothesadvisor.service.wardrobe.WardrobeItemPhotoService

class AiOutfitServiceTests {
    private val users = mock(AppUserRepository::class.java)
    private val items = mock(WardrobeItemRepository::class.java)
    private val photos = mock(WardrobeItemPhotoRepository::class.java)
    private val precipitation = mock(PrecipitationTypeRepository::class.java)
    private val content = mock(WardrobeItemPhotoService::class.java)
    private var selected = listOf(11L)
    private var providerFailure: AiOutfitFailure? = null
    private var receivedImages = emptyList<AiCandidateImage>()
    private val provider = mock(OpenAiOutfitClient::class.java) { call ->
        if (call.method.name == "select") {
            receivedImages = call.getArgument(1)
            providerFailure?.let { throw AiOutfitException(it) }
            selected
        } else RETURNS_DEFAULTS.answer(call)
    }
    private var savedRequest: CreateOutfitRequest? = null
    private val storedAt = Instant.parse("2024-02-03T04:05:06Z")
    private val weather = OutfitWeatherDto(BigDecimal.ONE, 1, BigDecimal.ZERO)
    private val saved = OutfitResponse(80, 7, 7, OutfitSource.AI, "Daily", listOf(11), weather, storedAt)
    private val outfits = mock(OutfitService::class.java) { call ->
        if (call.method.name == "createAi") { savedRequest = call.getArgument(1); saved }
        else RETURNS_DEFAULTS.answer(call)
    }
    private val transactions = mock(PlatformTransactionManager::class.java)
    private val service = AiOutfitService(users, items, photos, precipitation, content, provider, outfits, transactions, "user")
    private val owner = AppUser("user", "!", UserRole.USER, UserStatus.ACTIVE, storedAt).apply { id = 7 }
    private val item = WardrobeItem(7, WardrobeCategory("TOP", "Top"), "Shirt", "White", "Cotton", storedAt).apply { id = 11 }
    private val photo = WardrobeItemPhoto(11, "key", "image/png", 3, storedAt).apply { id = 21 }
    private fun request(ids: List<Long?>? = null) = CreateAiOutfitRequest("Daily", weather, ids)

    @BeforeEach
    fun prepare() {
        `when`(transactions.getTransaction(any())).thenAnswer { SimpleTransactionStatus() }
        `when`(users.existsByIdAndRoleAndStatus(7, UserRole.USER, UserStatus.ACTIVE)).thenReturn(true)
        `when`(users.findByLogin("user")).thenReturn(owner)
        `when`(users.lockActiveUserForShare(7)).thenReturn(7)
        `when`(precipitation.findById(1)).thenReturn(PrecipitationType("NONE", "None"))
        `when`(items.findOwnedItemsWithPhotos(7, 21)).thenReturn(listOf(item))
        `when`(items.lockOwnedItemsForShare(7, listOf(11))).thenReturn(listOf(item))
        `when`(photos.findFirstByItemIdOrderByIdAsc(11)).thenReturn(photo)
        `when`(photos.findByIdAndItemIdAndItemOwnerId(21, 11, 7)).thenReturn(photo)
        `when`(content.getContent(7, 11, 21)).thenReturn(WardrobeItemPhotoContent("image/png", byteArrayOf(1, 2, 3)))
    }

    @Test
    fun `provider selection is saved with original weather and database timestamp`() {
        assertThat(service.create(7, request())).isSameAs(saved)
        assertThat(saved.createdAt).isEqualTo(storedAt)
        assertThat(savedRequest!!.itemIds).containsExactly(11)
        assertThat(savedRequest!!.weather).isSameAs(weather)
        assertThat(receivedImages.single().item.photoId).isEqualTo(21)
        assertThat(receivedImages.single().bytes).containsExactly(1, 2, 3)
        verify(photos).findByIdAndItemIdAndItemOwnerId(21, 11, 7)
    }

    @Test
    fun `inactive or disconnected owner never touches provider or images`() {
        `when`(users.existsByIdAndRoleAndStatus(7, UserRole.USER, UserStatus.ACTIVE)).thenReturn(false)
        assertThatThrownBy { service.create(7, request()) }.isInstanceOf(AccessDeniedException::class.java)
        `when`(users.existsByIdAndRoleAndStatus(7, UserRole.USER, UserStatus.ACTIVE)).thenReturn(true)
        `when`(users.findByLogin("user")).thenReturn(null)
        failure(AiOutfitFailure.UNAVAILABLE) { service.create(7, request()) }
        verifyNoInteractions(provider, content, outfits)
    }

    @Test
    fun `invalid explicit IDs and foreign items stop before image reads`() {
        for (ids in listOf(emptyList(), listOf(null), listOf(0L), listOf(11L, 11L))) {
            failure(AiOutfitFailure.INVALID_REQUEST) { service.create(7, request(ids)) }
        }
        assertThatThrownBy { service.create(7, request(listOf(99))) }.isInstanceOf(EntityNotFoundException::class.java)
        verifyNoInteractions(content, outfits)
        assertThat(receivedImages).isEmpty()
    }

    @Test
    fun `candidate limit stops after bounded query before photo and external reads`() {
        `when`(items.findOwnedItemsWithPhotos(7, 21)).thenReturn(List(21) { item })
        failure(AiOutfitFailure.INVALID_REQUEST) { service.create(7, request()) }
        verify(items).findOwnedItemsWithPhotos(7, 21)
        verifyNoInteractions(photos, content, outfits)
    }

    @Test
    fun `explicit items without photos are filtered before selection`() {
        val empty = WardrobeItem(7, item.category, "Empty", "Grey", "Wool", storedAt).apply { id = 12 }
        `when`(items.findAllByOwnerIdAndIdInOrderByIdAsc(7, listOf(11, 12))).thenReturn(listOf(item, empty))
        assertThat(service.create(7, request(listOf(11, 12)))).isSameAs(saved)
        assertThat(receivedImages.map { it.item.id }).containsExactly(11)
        verify(content, never()).getContent(eq(7L), eq(12L), anyLong())
    }

    @Test
    fun `empty filtered candidates do not call storage or select`() {
        `when`(photos.findFirstByItemIdOrderByIdAsc(11)).thenReturn(null)
        failure(AiOutfitFailure.INVALID_REQUEST) { service.create(7, request()) }
        verifyNoInteractions(content, outfits)
        assertThat(receivedImages).isEmpty()
    }

    @Test
    fun `deleted source during image read becomes conflict and does not save`() {
        `when`(content.getContent(7, 11, 21)).thenThrow(EntityNotFoundException())
        failure(AiOutfitFailure.SOURCE_CHANGED) { service.create(7, request()) }
        verifyNoInteractions(outfits)
        assertThat(receivedImages).isEmpty()
    }

    @Test
    fun `provider failure never reaches save or final locks`() {
        providerFailure = AiOutfitFailure.TIMEOUT
        failure(AiOutfitFailure.TIMEOUT) { service.create(7, request()) }
        verify(users, never()).lockActiveUserForShare(7)
        verifyNoInteractions(outfits)
    }

    @Test
    fun `revoked actor or deleted source at final check prevents save`() {
        `when`(users.lockActiveUserForShare(7)).thenReturn(null)
        assertThatThrownBy { service.create(7, request()) }.isInstanceOf(AccessDeniedException::class.java)
        `when`(users.lockActiveUserForShare(7)).thenReturn(7)
        `when`(photos.findByIdAndItemIdAndItemOwnerId(21, 11, 7)).thenReturn(null)
        failure(AiOutfitFailure.SOURCE_CHANGED) { service.create(7, request()) }
        verifyNoInteractions(outfits)
    }

    private fun failure(expected: AiOutfitFailure, action: () -> Unit) {
        assertThatThrownBy(action).isInstanceOfSatisfying(AiOutfitException::class.java) {
            assertThat(it.failure).isEqualTo(expected)
        }
    }
}
