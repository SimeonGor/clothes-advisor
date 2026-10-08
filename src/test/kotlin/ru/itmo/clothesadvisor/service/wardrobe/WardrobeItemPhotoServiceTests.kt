package ru.itmo.clothesadvisor.service.wardrobe

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.time.Instant
import javax.imageio.ImageIO
import org.assertj.core.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*
import org.springframework.http.HttpStatus
import org.springframework.mock.web.MockMultipartFile
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.SimpleTransactionStatus
import ru.itmo.clothesadvisor.model.EntityNotFoundException
import ru.itmo.clothesadvisor.dto.wardrobe.CreateWardrobeItemRequest
import ru.itmo.clothesadvisor.model.wardrobe.*
import ru.itmo.clothesadvisor.repository.wardrobe.*
import ru.itmo.clothesadvisor.storage.wardrobe.S3PhotoStorage

class WardrobeItemPhotoServiceTests {
    private val items = mock(WardrobeItemRepository::class.java)
    private val photos = mock(WardrobeItemPhotoRepository::class.java)
    private val storage = mock(S3PhotoStorage::class.java)
    private val transactions = mock(PlatformTransactionManager::class.java).also {
        `when`(it.getTransaction(any())).thenAnswer { SimpleTransactionStatus() }
    }
    private val service = WardrobeItemPhotoService(items, photos, storage, transactions)
    private val png = ByteArrayOutputStream().also { ImageIO.write(BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB), "png", it) }.toByteArray()
    private fun file() = MockMultipartFile("photos", "photo.png", "image/png", png)

    @Test
    fun `five stored photos reject an additional photo before storage`() = rejectCapacity(5, 1)

    @Test
    fun `three stored photos reject a batch of three before storage`() = rejectCapacity(3, 3)

    private fun rejectCapacity(existing: Long, additional: Int) {
        val item = WardrobeItem(7, WardrobeCategory("TOP", "Top"), "Shirt", "White", "Cotton", Instant.EPOCH).apply { id = 11 }
        `when`(items.findByIdAndOwnerId(11, 7)).thenReturn(item)
        `when`(photos.countByItemId(11)).thenReturn(existing)
        assertThatThrownBy { service.add(7, 11, List(additional) { file() }) }
            .isInstanceOfSatisfying(PhotoRequestException::class.java) { assertThat(it.status).isEqualTo(HttpStatus.CONFLICT) }
        verifyNoInteractions(storage)
        verify(items, never()).lockOwned(11, 7)
        verify(photos).countByItemId(11)
        verifyNoMoreInteractions(photos)
    }

    @Test
    fun `composite creation rejects unknown category before uploads or writes`() {
        val itemService = mock(WardrobeItemService::class.java)
        val categories = mock(WardrobeCategoryService::class.java)
        `when`(categories.getById(99)).thenThrow(EntityNotFoundException())
        val management = WardrobeManagementService(itemService, service, categories, transactions)
        assertThatThrownBy { management.createWithPhotos(7, CreateWardrobeItemRequest("Shirt", 99, "White", "Cotton"), listOf(file())) }
            .isInstanceOf(EntityNotFoundException::class.java)
        verifyNoInteractions(storage, itemService, items, photos)
    }
}
