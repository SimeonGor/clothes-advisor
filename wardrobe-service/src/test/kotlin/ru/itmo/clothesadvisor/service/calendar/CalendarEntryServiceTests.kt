package ru.itmo.clothesadvisor.service.calendar

import jakarta.validation.ConstraintViolationException
import jakarta.validation.Validation
import java.time.Instant
import java.time.LocalDate
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.verifyNoMoreInteractions
import org.mockito.Mockito.`when`
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.data.domain.PageRequest
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.SimpleTransactionStatus
import ru.itmo.clothesadvisor.config.idempotencyService
import ru.itmo.clothesadvisor.dto.calendar.CreateCalendarEntryRequest
import ru.itmo.clothesadvisor.dto.calendar.UpdateCalendarEntryRequest
import ru.itmo.clothesadvisor.dto.user.CurrentUser
import ru.itmo.clothesadvisor.model.AccessDeniedException
import ru.itmo.clothesadvisor.model.EntityNotFoundException
import ru.itmo.clothesadvisor.model.calendar.CalendarEntry
import ru.itmo.clothesadvisor.model.calendar.CalendarEntryStatus.PLANNED
import ru.itmo.clothesadvisor.model.calendar.CalendarEntryStatus.WORN
import ru.itmo.clothesadvisor.model.outfit.Outfit
import ru.itmo.clothesadvisor.model.outfit.OutfitSource
import ru.itmo.clothesadvisor.model.user.UserRole
import ru.itmo.clothesadvisor.repository.calendar.CalendarEntryRepository
import ru.itmo.clothesadvisor.repository.outfit.OutfitRepository

class CalendarEntryServiceTests {
    private val entries = mock(CalendarEntryRepository::class.java)
    private val outfits = mock(OutfitRepository::class.java)
    private val transactions = mock(PlatformTransactionManager::class.java)
    private val validation = Validation.buildDefaultValidatorFactory()
    private val service = CalendarEntryService(entries, outfits, validation.validator, idempotencyService(transactions), transactions)
    private val owner = CurrentUser(7, "owner", UserRole.USER)
    private val date = LocalDate.parse("2026-10-27")
    private val row = CalendarEntry(9, 7, date, 11, PLANNED, 1,
        Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-02-01T00:00:00Z"))
    private val create = CreateCalendarEntryRequest(date, 11, PLANNED)
    private val update = UpdateCalendarEntryRequest(1, date, 11, PLANNED)
    private val outfit = Outfit(7, 7, OutfitSource.USER, "Daily", row.createdAt)

    @BeforeEach fun transactions() {
        `when`(transactions.getTransaction(any())).thenAnswer { SimpleTransactionStatus() }
    }

    @AfterEach fun closeValidation() = validation.close()

    @ParameterizedTest
    @ValueSource(strings = ["STYLIST", "ADMIN"])
    fun `all operations require USER before key replay or persistence`(role: String) {
        // given
        val actor = owner.copy(role = UserRole.valueOf(role))

        // when
        val failures = listOf(
            catchThrowable { service.create(actor, create, "replay") },
            catchThrowable { service.get(actor, 9) },
            catchThrowable { service.list(actor, date, date, PageRequest.of(0, 50)) },
            catchThrowable { service.update(actor, 9, update) },
            catchThrowable { service.delete(actor, 9, 1) },
        )

        // then
        assertThat(failures).allMatch { it is AccessDeniedException }
        verifyNoInteractions(entries, outfits, transactions)
    }

    @Test
    fun `creation checks owned outfit and returns persisted fields`() {
        // given
        `when`(outfits.findByIdAndOwnerId(11, 7)).thenReturn(outfit)
        `when`(entries.create(7, date, 11, PLANNED)).thenReturn(row)

        // when
        val result = service.create(owner, create)

        // then
        assertThat(result.id).isEqualTo(9)
        assertThat(result.date).isEqualTo(date)
        assertThat(result.createdAt).isEqualTo(row.createdAt)
        assertThat(result.modifiedAt).isEqualTo(row.modifiedAt)
        inOrder(outfits, entries).apply {
            verify(outfits).findByIdAndOwnerId(11, 7)
            verify(entries).create(7, date, 11, PLANNED)
        }
    }

    @Test
    fun `invalid ids versions and reverse range fail without persistence`() {
        // given
        val invalidCreate = create.copy(outfitId = 0)

        // when
        val failures = listOf(catchThrowable { service.create(owner, invalidCreate) },
            catchThrowable { service.update(owner, 9, update.copy(version = 0)) },
            catchThrowable { service.update(owner, 9, update.copy(outfitId = -1)) },
            catchThrowable { service.delete(owner, 9, 0) })
        val range = catchThrowable { service.list(owner, date.plusDays(1), date, PageRequest.of(0, 50)) }

        // then
        assertThat(failures).allMatch { it is ConstraintViolationException }
        assertThat(range).isInstanceOf(InvalidCalendarRangeException::class.java)
        verifyNoInteractions(entries, outfits, transactions)
    }

    @Test
    fun `missing owned entry or outfit is not found without writes`() {
        // given
        `when`(entries.findLocked(9, 7)).thenReturn(row)

        // when
        val failures = listOf(catchThrowable { service.create(owner, create) },
            catchThrowable { service.get(owner, 10) },
            catchThrowable { service.update(owner, 10, update) },
            catchThrowable { service.delete(owner, 10, 1) },
            catchThrowable { service.update(owner, 9, update.copy(outfitId = 12)) })

        // then
        assertThat(failures).allMatch { it is EntityNotFoundException }
        verify(entries).findByIdAndOwnerId(10, 7)
        verify(entries, org.mockito.Mockito.times(2)).findLocked(10, 7)
        verify(entries).findLocked(9, 7)
        verifyNoMoreInteractions(entries)
    }

    @Test
    fun `no-op preserves snapshot and stale version fails before no-op`() {
        // given
        `when`(entries.findLocked(9, 7)).thenReturn(row)

        // when
        val result = service.update(owner, 9, update)
        val stale = catchThrowable { service.update(owner, 9, update.copy(version = 2)) }
        val staleDelete = catchThrowable { service.delete(owner, 9, 2) }

        // then
        assertThat(result.version).isEqualTo(1)
        assertThat(result.modifiedAt).isEqualTo(row.modifiedAt)
        assertThat(listOf(stale, staleDelete)).allMatch { it is OptimisticLockingFailureException }
        verify(entries, org.mockito.Mockito.times(3)).findLocked(9, 7)
        verifyNoMoreInteractions(entries)
        verifyNoInteractions(outfits)
    }

    @Test
    fun `changed entry archives locked snapshot before update and returns saved row`() {
        // given
        val saved = row.copy(status = WORN, version = 2, modifiedAt = row.modifiedAt.plusSeconds(1))
        `when`(entries.findLocked(9, 7)).thenReturn(row)
        `when`(outfits.findByIdAndOwnerId(11, 7)).thenReturn(outfit)
        `when`(entries.archive(9, 7, 1)).thenReturn(1)
        `when`(entries.update(9, 7, 1, date, 11, WORN)).thenReturn(1)
        `when`(entries.findByIdAndOwnerId(9, 7)).thenReturn(saved)

        // when
        val result = service.update(owner, 9, update.copy(status = WORN))

        // then
        assertThat(result.version).isEqualTo(2)
        assertThat(result.createdAt).isEqualTo(row.createdAt)
        assertThat(result.modifiedAt).isEqualTo(saved.modifiedAt)
        inOrder(entries, outfits).apply {
            verify(entries).findLocked(9, 7)
            verify(outfits).findByIdAndOwnerId(11, 7)
            verify(entries).archive(9, 7, 1)
            verify(entries).update(9, 7, 1, date, 11, WORN)
            verify(entries).findByIdAndOwnerId(9, 7)
        }
    }

    @Test
    fun `delete archives final snapshot before physical deletion`() {
        // given
        `when`(entries.findLocked(9, 7)).thenReturn(row)
        `when`(entries.archive(9, 7, 1)).thenReturn(1)
        `when`(entries.delete(9, 7, 1)).thenReturn(1)

        // when
        service.delete(owner, 9, 1)

        // then
        inOrder(entries).apply {
            verify(entries).findLocked(9, 7)
            verify(entries).archive(9, 7, 1)
            verify(entries).delete(9, 7, 1)
        }
        verifyNoInteractions(outfits)
    }

    @Test
    fun `list passes range and SQL pagination and total through`() {
        // given
        `when`(entries.findPage(7, date, date, 1, 2)).thenReturn(listOf(row))
        `when`(entries.countOwned(7, date, date)).thenReturn(3)

        // when
        val result = service.list(owner, date, date, PageRequest.of(2, 1))

        // then
        assertThat(result.content.map { it.id }).containsExactly(9)
        assertThat(result.totalElements).isEqualTo(3)
        verify(entries).findPage(7, date, date, 1, 2)
        verify(entries).countOwned(7, date, date)
        verifyNoMoreInteractions(entries)
    }
}
