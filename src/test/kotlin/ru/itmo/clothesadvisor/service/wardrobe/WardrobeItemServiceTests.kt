package ru.itmo.clothesadvisor.service.wardrobe

import jakarta.validation.ConstraintViolationException
import jakarta.validation.Validation
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.*
import org.springframework.dao.OptimisticLockingFailureException
import ru.itmo.clothesadvisor.dto.wardrobe.*
import ru.itmo.clothesadvisor.model.wardrobe.*
import ru.itmo.clothesadvisor.repository.wardrobe.*
import ru.itmo.clothesadvisor.service.user.AppUserService

class WardrobeItemServiceTests {
    private val items = mock(WardrobeItemRepository::class.java)
    private val history = mock(WardrobeItemHistoryRepository::class.java)
    private val categories = mock(WardrobeCategoryService::class.java)
    private val users = mock(AppUserService::class.java)
    private val validation = Validation.buildDefaultValidatorFactory()
    private val service = WardrobeItemService(items, history, categories, users, validation.validator)
    private val storedAt = Instant.parse("2026-01-01T00:00:00Z")
    private val category = WardrobeCategory("TOP", "Top").apply { id = 1 }
    private fun item() = WardrobeItem(1, category, "Shirt", "White", "Cotton", storedAt).apply { id = 9 }

    @AfterEach fun closeValidation() = validation.close()

    @Test
    fun `invalid text is rejected before persistence`() {
        for (request in listOf(CreateWardrobeItemRequest("x".repeat(301), 1, "White", "Cotton"),
            CreateWardrobeItemRequest("Shirt", 1, "x".repeat(101), "Cotton"),
            CreateWardrobeItemRequest("Shirt", 1, "White", "x".repeat(101)),
            CreateWardrobeItemRequest("Shirt", 1, " ", "Cotton"))) {
            assertThatThrownBy { service.create(1, request) }.isInstanceOf(ConstraintViolationException::class.java)
        }
        verifyNoInteractions(items, history, categories, users)
    }

    @Test
    fun `unchanged update skips write but checks expected version first`() {
        val row = item()
        `when`(items.findByIdAndOwnerId(9, 1)).thenReturn(row)
        `when`(categories.getById(1)).thenReturn(category)
        val request = UpdateWardrobeItemRequest(1, "Shirt", 1, "White", "Cotton")
        assertThat(service.update(1, 9, request).version).isEqualTo(1)
        assertThatThrownBy { service.update(1, 9, UpdateWardrobeItemRequest(2, "Shirt", 1, "White", "Cotton")) }
            .isInstanceOf(OptimisticLockingFailureException::class.java)
        verify(items, never()).update(row)
        verifyNoInteractions(history)
    }

    @Test
    fun `update uses stored timestamps and archives exact previous contents`() {
        val row = item()
        val saved = item().apply { replace(category, "Jacket", "Blue", "Wool"); version = 2; modifiedAt = storedAt.plusSeconds(10) }
        `when`(items.findByIdAndOwnerId(9, 1)).thenReturn(row)
        `when`(categories.getById(1)).thenReturn(category)
        `when`(items.update(row)).thenReturn(saved)
        var archived: WardrobeItemHistory? = null
        doAnswer { archived = it.getArgument(0); null }.`when`(history).insert(any(WardrobeItemHistory::class.java) ?: WardrobeItemHistory(row))
        val response = service.update(1, 9, UpdateWardrobeItemRequest(1, "Jacket", 1, "Blue", "Wool"))
        assertThat(response.modifiedAt).isEqualTo(saved.modifiedAt)
        assertThat(response.createdAt).isEqualTo(storedAt)
        assertThat(archived!!.name).isEqualTo("Shirt")
        assertThat(archived!!.modifiedAt).isEqualTo(storedAt)
        assertThat(archived!!.id.version).isEqualTo(1)
    }

    @Test
    fun `stale SQL delete fails without archiving final state`() {
        val row = item()
        `when`(items.findByIdAndOwnerId(9, 1)).thenReturn(row)
        doThrow(OptimisticLockingFailureException("conflict")).`when`(items).delete(row)
        assertThatThrownBy { service.delete(1, 9, 1) }.isInstanceOf(OptimisticLockingFailureException::class.java)
        verifyNoInteractions(history)
    }
}
