package ru.itmo.clothesadvisor.service.wardrobe

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.time.Instant
import javax.imageio.ImageIO
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.mockito.Mockito.any
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.verifyNoMoreInteractions
import org.mockito.Mockito.`when`
import org.springframework.http.HttpStatus
import org.springframework.mock.web.MockMultipartFile
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.SimpleTransactionStatus
import ru.itmo.clothesadvisor.dto.wardrobe.CreateWardrobeItemRequest
import ru.itmo.clothesadvisor.model.EntityNotFoundException
import ru.itmo.clothesadvisor.model.wardrobe.WardrobeCategory
import ru.itmo.clothesadvisor.model.wardrobe.WardrobeItem
import ru.itmo.clothesadvisor.repository.wardrobe.WardrobeItemPhotoRepository
import ru.itmo.clothesadvisor.repository.wardrobe.WardrobeItemRepository
import ru.itmo.clothesadvisor.storage.wardrobe.S3PhotoStorage

class WardrobeItemPhotoServiceTests {
    private val items = mock(WardrobeItemRepository::class.java)
    private val photos = mock(WardrobeItemPhotoRepository::class.java)
    private val storage = mock(S3PhotoStorage::class.java)
    private val transactions =
        mock(PlatformTransactionManager::class.java).also {
            `when`(it.getTransaction(any())).thenAnswer { SimpleTransactionStatus() }
        }
    private val service = WardrobeItemPhotoService(items, photos, storage, transactions)
    private val png =
        ByteArrayOutputStream()
            .also { ImageIO.write(BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB), "png", it) }
            .toByteArray()

    private fun file() = MockMultipartFile("photos", "photo.png", "image/png", png)

    @ParameterizedTest(name = "{0} existing photos reject {1} additional photos before storage")
    @CsvSource("5,1", "3,3")
    fun `photo capacity rejects the batch before storage`(existing: Long, additional: Int) {

        // given
        val item =
            WardrobeItem(
                    7,
                    WardrobeCategory("TOP", "Top"),
                    "Shirt",
                    "White",
                    "Cotton",
                    Instant.EPOCH,
                )
                .apply { id = 11 }
        `when`(items.findByIdAndOwnerId(11, 7)).thenReturn(item)
        `when`(photos.countByItemId(11)).thenReturn(existing)

        // when
        val failure = catchThrowable { service.add(7, 11, List(additional) { file() }) }

        // then
        assertThat(failure).isInstanceOfSatisfying(PhotoRequestException::class.java) {
            assertThat(it.status).isEqualTo(HttpStatus.CONFLICT)
        }
        verifyNoInteractions(storage)
        verify(items, never()).lockOwned(11, 7)
        verify(photos).countByItemId(11)
        verifyNoMoreInteractions(photos)
    }

    @Test
    fun `composite creation rejects unknown category before uploads or writes`() {

        // given
        val itemService = mock(WardrobeItemService::class.java)
        val categories = mock(WardrobeCategoryService::class.java)
        `when`(categories.getById(99)).thenThrow(EntityNotFoundException())
        val management = WardrobeManagementService(itemService, service, categories, transactions)

        // when
        val failure = catchThrowable {
            management.createWithPhotos(
                7,
                CreateWardrobeItemRequest("Shirt", 99, "White", "Cotton"),
                listOf(file()),
            )
        }

        // then
        assertThat(failure).isInstanceOf(EntityNotFoundException::class.java)
        verifyNoInteractions(storage, itemService, items, photos)
    }
}
