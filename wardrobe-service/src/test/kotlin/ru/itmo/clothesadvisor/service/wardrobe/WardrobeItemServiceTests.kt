package ru.itmo.clothesadvisor.service.wardrobe

import jakarta.validation.ConstraintViolationException
import jakarta.validation.Validation
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import org.springframework.dao.OptimisticLockingFailureException
import ru.itmo.clothesadvisor.dto.wardrobe.CreateWardrobeItemRequest
import ru.itmo.clothesadvisor.dto.wardrobe.UpdateWardrobeItemRequest
import ru.itmo.clothesadvisor.model.wardrobe.WardrobeCategory
import ru.itmo.clothesadvisor.model.wardrobe.WardrobeItem
import ru.itmo.clothesadvisor.model.wardrobe.WardrobeItemHistory
import ru.itmo.clothesadvisor.repository.wardrobe.WardrobeItemHistoryRepository
import ru.itmo.clothesadvisor.repository.wardrobe.WardrobeItemRepository
import ru.itmo.clothesadvisor.service.user.AppUserService

class WardrobeItemServiceTests {
    private val items = mock(WardrobeItemRepository::class.java)
    private val history = mock(WardrobeItemHistoryRepository::class.java)
    private val categories = mock(WardrobeCategoryService::class.java)
    private val users = mock(AppUserService::class.java)
    private val validation = Validation.buildDefaultValidatorFactory()
    private val service =
        WardrobeItemService(items, history, categories, users, validation.validator)
    private val storedAt = Instant.parse("2026-01-01T00:00:00Z")
    private val category = WardrobeCategory("TOP", "Top").apply { id = 1 }

    private fun item() =
        WardrobeItem(1, category, "Shirt", "White", "Cotton", storedAt).apply { id = 9 }

    @AfterEach fun closeValidation() = validation.close()

    @Test
    fun `invalid text is rejected before persistence`() {
        for (request in
            listOf(
                CreateWardrobeItemRequest("x".repeat(301), 1, "White", "Cotton"),
                CreateWardrobeItemRequest("Shirt", 1, "x".repeat(101), "Cotton"),
                CreateWardrobeItemRequest("Shirt", 1, "White", "x".repeat(101)),
                CreateWardrobeItemRequest("Shirt", 1, " ", "Cotton"),
            )) {

            // given: a request with an invalid text field

            // when
            val failure = catchThrowable { service.create(1, request) }

            // then
            assertThat(failure).isInstanceOf(ConstraintViolationException::class.java)
        }
        verifyNoInteractions(items, history, categories, users)
    }

    @Test
    fun `unchanged update skips write but checks expected version first`() {

        // given
        val row = item()
        `when`(items.findByIdAndOwnerId(9, 1)).thenReturn(row)
        `when`(categories.getById(1)).thenReturn(category)
        val request = UpdateWardrobeItemRequest(1, "Shirt", 1, "White", "Cotton")

        // when
        val unchanged = service.update(1, 9, request)

        // then
        assertThat(unchanged.version).isEqualTo(1)

        // when
        val stale = catchThrowable {
            service.update(1, 9, UpdateWardrobeItemRequest(2, "Shirt", 1, "White", "Cotton"))
        }

        // then
        assertThat(stale).isInstanceOf(OptimisticLockingFailureException::class.java)
        verify(items, never()).update(row)
        verifyNoInteractions(history)
    }

    @Test
    fun `update uses stored timestamps and archives exact previous contents`() {

        // given
        val row = item()
        val saved =
            item().apply {
                replace(category, "Jacket", "Blue", "Wool")
                version = 2
                modifiedAt = storedAt.plusSeconds(10)
            }
        `when`(items.findByIdAndOwnerId(9, 1)).thenReturn(row)
        `when`(categories.getById(1)).thenReturn(category)
        `when`(items.update(row)).thenReturn(saved)
        var archived: WardrobeItemHistory? = null
        doAnswer {
                archived = it.getArgument(0)
                null
            }
            .`when`(history)
            .insert(any(WardrobeItemHistory::class.java) ?: WardrobeItemHistory(row))

        // when
        val response =
            service.update(1, 9, UpdateWardrobeItemRequest(1, "Jacket", 1, "Blue", "Wool"))

        // then
        assertThat(response.modifiedAt).isEqualTo(saved.modifiedAt)
        assertThat(response.createdAt).isEqualTo(storedAt)
        assertThat(archived!!.name).isEqualTo("Shirt")
        assertThat(archived!!.modifiedAt).isEqualTo(storedAt)
        assertThat(archived!!.id.version).isEqualTo(1)
    }

    @Test
    fun `stale SQL delete fails without archiving final state`() {

        // given
        val row = item()
        `when`(items.findByIdAndOwnerId(9, 1)).thenReturn(row)
        doThrow(OptimisticLockingFailureException("conflict")).`when`(items).delete(row)

        // when
        val failure = catchThrowable { service.delete(1, 9, 1) }

        // then
        assertThat(failure).isInstanceOf(OptimisticLockingFailureException::class.java)
        verifyNoInteractions(history)
    }
}
