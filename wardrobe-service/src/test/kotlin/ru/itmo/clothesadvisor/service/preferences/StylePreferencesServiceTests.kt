package ru.itmo.clothesadvisor.service.preferences

import jakarta.validation.ConstraintViolationException
import jakarta.validation.Validation
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.SimpleTransactionStatus
import ru.itmo.clothesadvisor.client.account.BlockingAccountClient
import ru.itmo.clothesadvisor.dto.preferences.PutStylePreferencesRequest
import ru.itmo.clothesadvisor.dto.user.CurrentUser
import ru.itmo.clothesadvisor.model.AccessDeniedException
import ru.itmo.clothesadvisor.model.EntityNotFoundException
import ru.itmo.clothesadvisor.model.access.AccountUnavailableException
import ru.itmo.clothesadvisor.model.preferences.StylePreferences
import ru.itmo.clothesadvisor.model.preferences.StylePreferencesHistory
import ru.itmo.clothesadvisor.model.preferences.StylePreferencesHistoryId
import ru.itmo.clothesadvisor.model.user.UserRole
import ru.itmo.clothesadvisor.repository.preferences.StylePreferencesHistoryRepository
import ru.itmo.clothesadvisor.repository.preferences.StylePreferencesRepository

class StylePreferencesServiceTests {
    private val preferences = mock(StylePreferencesRepository::class.java)
    private val history = mock(StylePreferencesHistoryRepository::class.java)
    private val accounts = mock(BlockingAccountClient::class.java)
    private val transactions = mock(PlatformTransactionManager::class.java)
    private val validation = Validation.buildDefaultValidatorFactory()
    private val service = StylePreferencesService(
        preferences, history, accounts, validation.validator, transactions,
    )
    private val owner = CurrentUser(7, "owner", UserRole.USER)
    private val createdAt = Instant.parse("2026-01-01T00:00:00Z")
    private val row = StylePreferences(7, "Blue", "Wool", "Casual", 3, createdAt, createdAt.plusSeconds(10))

    @BeforeEach
    fun prepareTransactions() {
        `when`(transactions.getTransaction(any())).thenAnswer { SimpleTransactionStatus() }
    }

    @AfterEach fun closeValidation() = validation.close()

    @ParameterizedTest
    @ValueSource(strings = ["STYLIST", "ADMIN"])
    fun `own reads and writes reject other roles before transaction`(role: String) {
        // given
        val actor = owner.copy(role = UserRole.valueOf(role))

        // when
        val read = catchThrowable { service.get(actor) }
        val write = catchThrowable { service.put(actor, PutStylePreferencesRequest()) }

        // then
        assertThat(read).isInstanceOf(AccessDeniedException::class.java)
        assertThat(write).isInstanceOf(AccessDeniedException::class.java)
        verifyNoInteractions(preferences, history, accounts, transactions)
    }

    @Test
    fun `missing own profile returns not found without creating it`() {
        // given: no stored row

        // when
        val failure = catchThrowable { service.get(owner) }

        // then
        assertThat(failure).isInstanceOf(EntityNotFoundException::class.java)
        verify(preferences).findByOwnerId(owner.id)
        verifyNoInteractions(history, accounts)
    }

    @Test
    fun `create normalizes null only and returns persisted values and dates`() {
        // given
        val stored = row.copy(preferredColorsAndMaterials = "  Blue  ", avoidances = "", additionalWishes = "", version = 1)
        `when`(preferences.create(7, "  Blue  ", "", "")).thenReturn(stored)

        // when
        val result = service.put(owner, PutStylePreferencesRequest("  Blue  "))

        // then
        assertThat(result.preferredColorsAndMaterials).isEqualTo("  Blue  ")
        assertThat(result.avoidances).isEmpty()
        assertThat(result.additionalWishes).isEmpty()
        assertThat(result.version).isEqualTo(1)
        assertThat(result.createdAt).isEqualTo(stored.createdAt)
        assertThat(result.modifiedAt).isEqualTo(stored.modifiedAt)
        verifyNoInteractions(history, accounts)
    }

    @Test
    fun `failed create and update of missing profile are conflicts`() {
        // given: both repository methods return no row

        // when
        val create = catchThrowable { service.put(owner, PutStylePreferencesRequest()) }
        val update = catchThrowable { service.put(owner, PutStylePreferencesRequest(version = 1)) }

        // then
        assertThat(create).isInstanceOf(OptimisticLockingFailureException::class.java)
        assertThat(update).isInstanceOf(OptimisticLockingFailureException::class.java)
        verifyNoInteractions(history)
    }

    @Test
    fun `no-op preserves row and checks version before comparing fields`() {
        // given
        `when`(preferences.findByOwnerId(7)).thenReturn(row)
        val request = PutStylePreferencesRequest("Blue", "Wool", "Casual", 3)

        // when
        val result = service.put(owner, request)
        val stale = catchThrowable { service.put(owner, request.copy(version = 2)) }

        // then
        assertThat(result.version).isEqualTo(3)
        assertThat(result.createdAt).isEqualTo(row.createdAt)
        assertThat(result.modifiedAt).isEqualTo(row.modifiedAt)
        assertThat(stale).isInstanceOf(OptimisticLockingFailureException::class.java)
        verifyNoInteractions(history)
        verify(preferences, org.mockito.Mockito.never()).update(any() ?: row)
    }

    @Test
    fun `update archives all previous fields and returns database timestamps`() {
        // given
        val changed = row.copy(preferredColorsAndMaterials = "", avoidances = "", additionalWishes = "Formal")
        val saved = changed.copy(version = 4, modifiedAt = createdAt.plusSeconds(20))
        `when`(preferences.findByOwnerId(7)).thenReturn(row)
        `when`(preferences.update(changed)).thenReturn(saved)

        // when
        val result = service.put(owner, PutStylePreferencesRequest(additionalWishes = "Formal", version = 3))

        // then
        assertThat(result.preferredColorsAndMaterials).isEmpty()
        assertThat(result.avoidances).isEmpty()
        assertThat(result.additionalWishes).isEqualTo("Formal")
        assertThat(result.version).isEqualTo(4)
        assertThat(result.createdAt).isEqualTo(row.createdAt)
        assertThat(result.modifiedAt).isEqualTo(saved.modifiedAt)
        verify(history).insert(StylePreferencesHistory(
            StylePreferencesHistoryId(7, 3), "Blue", "Wool", "Casual", row.createdAt, row.modifiedAt,
        ))
    }

    @Test
    fun `lost optimistic update does not archive history`() {
        // given
        `when`(preferences.findByOwnerId(7)).thenReturn(row)

        // when
        val failure = catchThrowable { service.put(owner, PutStylePreferencesRequest(version = 3)) }

        // then
        assertThat(failure).isInstanceOf(OptimisticLockingFailureException::class.java)
        verifyNoInteractions(history)
    }

    @Test
    fun `invalid fields and nonpositive versions fail before transaction`() {
        // given
        val invalid = listOf(
            PutStylePreferencesRequest(preferredColorsAndMaterials = "x".repeat(2001)),
            PutStylePreferencesRequest(avoidances = "x".repeat(2001)),
            PutStylePreferencesRequest(additionalWishes = "x".repeat(2001)),
            PutStylePreferencesRequest(version = 0), PutStylePreferencesRequest(version = -1),
        )

        // when
        val failures = invalid.map { catchThrowable { service.put(owner, it) } }

        // then
        assertThat(failures).allMatch { it is ConstraintViolationException }
        verifyNoInteractions(preferences, history, accounts, transactions)
    }

    @Test
    fun `two thousand characters in every field are accepted`() {
        // given
        val text = "x".repeat(2000)
        val stored = row.copy(preferredColorsAndMaterials = text, avoidances = text, additionalWishes = text, version = 1)
        `when`(preferences.create(7, text, text, text)).thenReturn(stored)

        // when
        val result = service.put(owner, PutStylePreferencesRequest(text, text, text))

        // then
        assertThat(result.preferredColorsAndMaterials).isEqualTo(text)
        assertThat(result.avoidances).isEqualTo(text)
        assertThat(result.additionalWishes).isEqualTo(text)
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `stylist denial and account outage precede local transaction`(outage: Boolean) {
        // given
        val failure = if (outage) AccountUnavailableException() else EntityNotFoundException()
        doThrow(failure).`when`(accounts).requireStylistAccess(2, 7)

        // when
        val result = catchThrowable { service.getForClient(2, 7) }

        // then
        assertThat(result).isSameAs(failure)
        verifyNoInteractions(preferences, history, transactions)
    }

    @Test
    fun `stylist access is checked before opening transaction and reading owner`() {
        // given
        `when`(preferences.findByOwnerId(7)).thenReturn(row)

        // when
        val result = service.getForClient(2, 7)

        // then
        assertThat(result.preferredColorsAndMaterials).isEqualTo("Blue")
        val order = inOrder(accounts, transactions, preferences)
        order.verify(accounts).requireStylistAccess(2, 7)
        order.verify(transactions).getTransaction(any())
        order.verify(preferences).findByOwnerId(7)
        verifyNoInteractions(history)
    }
}
