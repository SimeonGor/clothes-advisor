package ru.itmo.clothesadvisor.service.stylistrequest

import jakarta.validation.ConstraintViolationException
import jakarta.validation.Validation
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
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.data.domain.PageRequest
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.SimpleTransactionStatus
import ru.itmo.clothesadvisor.client.account.BlockingAccountClient
import ru.itmo.clothesadvisor.config.idempotencyService
import ru.itmo.clothesadvisor.dto.outfit.OutfitWeatherDto
import ru.itmo.clothesadvisor.dto.stylistrequest.CancelStylistRequest
import ru.itmo.clothesadvisor.dto.stylistrequest.CreateStylistRequest
import ru.itmo.clothesadvisor.dto.user.CurrentUser
import ru.itmo.clothesadvisor.model.AccessDeniedException
import ru.itmo.clothesadvisor.model.EntityNotFoundException
import ru.itmo.clothesadvisor.model.access.AccountUnavailableException
import ru.itmo.clothesadvisor.model.stylistrequest.StylistRequest
import ru.itmo.clothesadvisor.model.stylistrequest.StylistRequestStatus
import ru.itmo.clothesadvisor.model.stylistrequest.StylistRequestWeather
import ru.itmo.clothesadvisor.model.user.UserRole
import ru.itmo.clothesadvisor.repository.stylistrequest.StylistRequestRepository
import ru.itmo.clothesadvisor.repository.weather.PrecipitationTypeRepository

class StylistRequestServiceTests {
    private val requests = mock(StylistRequestRepository::class.java)
    private val precipitation = mock(PrecipitationTypeRepository::class.java)
    private val accounts = mock(BlockingAccountClient::class.java)
    private val transactions = mock(PlatformTransactionManager::class.java)
    private val validation = Validation.buildDefaultValidatorFactory()
    private val service = StylistRequestService(
        requests, precipitation, accounts, validation.validator, idempotencyService(transactions), transactions,
    )
    private val owner = CurrentUser(7, "owner", UserRole.USER)
    private val stylist = CurrentUser(8, "stylist", UserRole.STYLIST)
    private val weather = OutfitWeatherDto(BigDecimal.TEN, 1, BigDecimal.ZERO)
    private val request = CreateStylistRequest(stylist.id, weather)
    private val row = StylistRequest(
        9, owner.id, stylist.id, "", StylistRequestStatus.PENDING, 1,
        Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-02-01T00:00:00Z"),
        StylistRequestWeather(weather.temperatureC, 1, weather.windSpeedMps),
    )

    @BeforeEach fun transactions() {
        `when`(transactions.getTransaction(any())).thenAnswer { SimpleTransactionStatus() }
    }

    @AfterEach fun closeValidation() = validation.close()

    @ParameterizedTest
    @ValueSource(strings = ["STYLIST", "ADMIN"])
    fun `own operations require USER before account and transaction`(role: String) {
        // given
        val actor = owner.copy(role = UserRole.valueOf(role))

        // when
        val failures = listOf(
            catchThrowable { service.create(actor, request) },
            catchThrowable { service.get(actor, 9) },
            catchThrowable { service.list(actor, PageRequest.of(0, 50)) },
            catchThrowable { service.cancel(actor, 9, CancelStylistRequest(1)) },
        )

        // then
        assertThat(failures).allMatch { it is AccessDeniedException }
        verifyNoInteractions(requests, precipitation, accounts, transactions)
    }

    @ParameterizedTest
    @ValueSource(strings = ["USER", "ADMIN"])
    fun `client reads require STYLIST before account and transaction`(role: String) {
        // given
        val actor = stylist.copy(role = UserRole.valueOf(role))

        // when
        val get = catchThrowable { service.getForClient(actor, 7, 9) }
        val list = catchThrowable { service.listForClient(actor, 7, PageRequest.of(0, 50)) }

        // then
        assertThat(listOf(get, list)).allMatch { it is AccessDeniedException }
        verifyNoInteractions(requests, accounts, transactions)
    }

    @Test
    fun `creation checks grant first and returns persisted weather and technical dates`() {
        // given
        `when`(precipitation.existsById(1)).thenReturn(true)
        `when`(requests.create(7, 8, "  Blue  ")).thenReturn(9)
        `when`(requests.findOwned(9, 7, null)).thenReturn(row.copy(wishes = "  Blue  "))

        // when
        val result = service.create(owner, request.copy(wishes = "  Blue  "))

        // then
        assertThat(result.wishes).isEqualTo("  Blue  ")
        assertThat(result.weather).isEqualTo(weather)
        assertThat(result.createdAt).isEqualTo(row.createdAt)
        assertThat(result.modifiedAt).isEqualTo(row.modifiedAt)
        val order = inOrder(accounts, transactions, requests)
        order.verify(accounts).requireStylistAccess(8, 7)
        order.verify(transactions).getTransaction(any())
        order.verify(requests).create(7, 8, "  Blue  ")
        order.verify(requests).insertWeather(9, row.weather)
        order.verify(requests).findOwned(9, 7, null)
    }

    @Test
    fun `DTO violations fail before account and unknown precipitation fails before insert`() {
        // given
        val invalid = listOf(request.copy(stylistId = 0), request.copy(wishes = "x".repeat(2001)),
            request.copy(weather = weather.copy(windSpeedMps = BigDecimal.ONE.negate())))

        // when
        val failures = invalid.map { catchThrowable { service.create(owner, it) } }
        val version = catchThrowable { service.cancel(owner, 9, CancelStylistRequest(0)) }

        // then
        assertThat(failures + version).allMatch { it is ConstraintViolationException }
        verifyNoInteractions(accounts, requests, transactions)

        // when
        val unknown = catchThrowable { service.create(owner, request) }

        // then
        assertThat(unknown).isInstanceOf(UnknownRequestPrecipitationException::class.java)
        verifyNoInteractions(requests)
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `grant denial and account outage prevent create replay and recipient reads`(outage: Boolean) {
        // given
        val failure = if (outage) AccountUnavailableException() else EntityNotFoundException()
        doThrow(failure).`when`(accounts).requireStylistAccess(8, 7)

        // when
        val create = catchThrowable { service.create(owner, request, "replay") }
        val get = catchThrowable { service.getForClient(stylist, 7, 9) }
        val list = catchThrowable { service.listForClient(stylist, 7, PageRequest.of(0, 50)) }

        // then
        assertThat(listOf(create, get, list)).allMatch { it === failure }
        verifyNoInteractions(requests, transactions)
    }

    @Test
    fun `cancel archives before mutation and does not consult account`() {
        // given
        val saved = row.copy(status = StylistRequestStatus.CANCELLED, version = 2)
        `when`(requests.findLocked(9, 7)).thenReturn(row)
        `when`(requests.archive(9, 7, 1)).thenReturn(1)
        `when`(requests.cancel(9, 7, 1)).thenReturn(1)
        `when`(requests.findOwned(9, 7, null)).thenReturn(saved)

        // when
        val result = service.cancel(owner, 9, CancelStylistRequest(1))

        // then
        assertThat(result.status).isEqualTo(StylistRequestStatus.CANCELLED)
        assertThat(result.version).isEqualTo(2)
        val order = inOrder(requests)
        order.verify(requests).findLocked(9, 7)
        order.verify(requests).archive(9, 7, 1)
        order.verify(requests).cancel(9, 7, 1)
        verifyNoInteractions(accounts)
    }

    @Test
    fun `cancelled no-op checks version first and missing own row is not found`() {
        // given
        `when`(requests.findLocked(9, 7)).thenReturn(row.copy(status = StylistRequestStatus.CANCELLED, version = 2))

        // when
        val result = service.cancel(owner, 9, CancelStylistRequest(2))
        val stale = catchThrowable { service.cancel(owner, 9, CancelStylistRequest(1)) }
        val missing = catchThrowable { service.cancel(owner, 10, CancelStylistRequest(1)) }

        // then
        assertThat(result.version).isEqualTo(2)
        assertThat(result.modifiedAt).isEqualTo(row.modifiedAt)
        assertThat(stale).isInstanceOf(OptimisticLockingFailureException::class.java)
        assertThat(missing).isInstanceOf(EntityNotFoundException::class.java)
        verify(requests, never()).archive(9, 7, 2)
        verify(requests, never()).cancel(9, 7, 2)
        verifyNoInteractions(accounts)
    }
}
