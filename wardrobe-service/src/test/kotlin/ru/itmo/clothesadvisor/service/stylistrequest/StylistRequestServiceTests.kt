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
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.verifyNoMoreInteractions
import org.mockito.Mockito.`when`
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.data.domain.PageRequest
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.SimpleTransactionStatus
import ru.itmo.clothesadvisor.client.account.BlockingAccountClient
import ru.itmo.clothesadvisor.config.idempotencyService
import ru.itmo.clothesadvisor.dto.outfit.OutfitWeatherDto
import ru.itmo.clothesadvisor.dto.outfit.CreateOutfitRequest
import ru.itmo.clothesadvisor.dto.outfit.OutfitResponse
import ru.itmo.clothesadvisor.dto.stylistrequest.AnswerStylistRequest
import ru.itmo.clothesadvisor.dto.stylistrequest.CancelStylistRequest
import ru.itmo.clothesadvisor.dto.stylistrequest.CreateStylistRequest
import ru.itmo.clothesadvisor.dto.user.CurrentUser
import ru.itmo.clothesadvisor.model.AccessDeniedException
import ru.itmo.clothesadvisor.model.EntityNotFoundException
import ru.itmo.clothesadvisor.model.access.AccountUnavailableException
import ru.itmo.clothesadvisor.model.stylistrequest.StylistRequest
import ru.itmo.clothesadvisor.model.stylistrequest.StylistRequestAnswer
import ru.itmo.clothesadvisor.model.outfit.OutfitSource
import ru.itmo.clothesadvisor.model.stylistrequest.StylistRequestStatus
import ru.itmo.clothesadvisor.model.stylistrequest.StylistRequestWeather
import ru.itmo.clothesadvisor.model.user.UserRole
import ru.itmo.clothesadvisor.repository.stylistrequest.StylistRequestRepository
import ru.itmo.clothesadvisor.repository.stylistrequest.StylistRequestAnswerRepository
import ru.itmo.clothesadvisor.service.outfit.OutfitService
import ru.itmo.clothesadvisor.repository.weather.PrecipitationTypeRepository

class StylistRequestServiceTests {
    private val requests = mock(StylistRequestRepository::class.java)
    private val answers = mock(StylistRequestAnswerRepository::class.java)
    private val outfits = mock(OutfitService::class.java)
    private val precipitation = mock(PrecipitationTypeRepository::class.java)
    private val accounts = mock(BlockingAccountClient::class.java)
    private val transactions = mock(PlatformTransactionManager::class.java)
    private val validation = Validation.buildDefaultValidatorFactory()
    private val service = StylistRequestService(
        requests, answers, outfits, precipitation, accounts, validation.validator, idempotencyService(transactions), transactions,
    )
    private val owner = CurrentUser(7, "owner", UserRole.USER)
    private val stylist = CurrentUser(8, "stylist", UserRole.STYLIST)
    private val weather = OutfitWeatherDto(BigDecimal.TEN, 1, BigDecimal.ZERO)
    private val request = CreateStylistRequest(stylist.id, weather)
    private val answer = AnswerStylistRequest(1, "  Daily  ", listOf(2, 1), "  Why  ")
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
        val answer = catchThrowable { service.answer(actor, 7, 9, answer) }

        // then
        assertThat(listOf(get, list, answer)).allMatch { it is AccessDeniedException }
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
        val answer = catchThrowable { service.answer(stylist, 7, 9, answer, "replay") }

        // then
        assertThat(listOf(create, get, list, answer)).allMatch { it === failure }
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

    @Test
    fun `answer validates scalar fields before account or writes`() {
        // given
        val invalid = listOf(answer.copy(version = 0), answer.copy(name = " "),
            answer.copy(name = "x".repeat(301)), answer.copy(explanation = " "),
            answer.copy(explanation = "x".repeat(2001)))

        // when
        val failures = invalid.map { catchThrowable { service.answer(stylist, 7, 9, it) } }

        // then
        assertThat(failures).allMatch { it is ConstraintViolationException }
        verifyNoInteractions(accounts, transactions, requests, answers, outfits)
    }

    @Test
    fun `answer checks access before transaction and passes locked request weather to outfit creation`() {
        // given
        val saved = row.copy(status = StylistRequestStatus.ANSWERED, version = 2)
        val storedAnswer = StylistRequestAnswer(9, 11, answer.explanation, row.modifiedAt)
        val input = CreateOutfitRequest(answer.name, answer.itemIds, weather)
        `when`(requests.findLocked(9, 7)).thenReturn(row)
        `when`(requests.archive(9, 7, 1)).thenReturn(1)
        `when`(requests.answer(9, 7, 8, 1)).thenReturn(1)
        `when`(requests.findOwned(9, 7, 8)).thenReturn(saved)
        `when`(answers.findAllByRequestIdIn(listOf(9))).thenReturn(listOf(storedAnswer))
        doAnswer { call ->
            assertThat(call.getArgument<Long>(0)).isEqualTo(8)
            assertThat(call.getArgument<Long>(1)).isEqualTo(7)
            assertThat(call.getArgument<CreateOutfitRequest>(2)).usingRecursiveComparison().isEqualTo(input)
            assertThat(call.getArgument<String>(3)).isEqualTo(answer.explanation)
            OutfitResponse(11, 7, 8, OutfitSource.STYLIST, answer.name, listOf(2, 1), weather, row.createdAt)
        }.`when`(outfits).createForStylistRequest(anyLong(), anyLong(), any(CreateOutfitRequest::class.java) ?: input, anyString())

        // when
        val result = service.answer(stylist, 7, 9, answer)

        // then
        assertThat(result.status).isEqualTo(StylistRequestStatus.ANSWERED)
        assertThat(result.version).isEqualTo(2)
        assertThat(result.answer!!.outfitId).isEqualTo(11)
        assertThat(result.answer.explanation).isEqualTo(answer.explanation)
        assertThat(result.answer.createdAt).isEqualTo(storedAnswer.createdAt)
        inOrder(accounts, transactions, requests, answers).apply {
            verify(accounts).requireStylistAccess(8, 7)
            verify(transactions).getTransaction(any())
            verify(requests).findLocked(9, 7)
            verify(requests).archive(9, 7, 1)
            verify(answers).insert(9, 11)
            verify(requests).answer(9, 7, 8, 1)
        }
    }

    @Test
    fun `missing recipient stale and terminal requests cannot create outfits or history`() {
        // given
        val rows = listOf(null, row.copy(stylistId = 10), row.copy(version = 2),
            row.copy(status = StylistRequestStatus.CANCELLED), row.copy(status = StylistRequestStatus.ANSWERED))

        // when
        val failures = rows.map {
            `when`(requests.findLocked(9, 7)).thenReturn(it)
            catchThrowable { service.answer(stylist, 7, 9, answer) }
        }

        // then
        assertThat(failures.take(2)).allMatch { it is EntityNotFoundException }
        assertThat(failures.drop(2)).allMatch { it is OptimisticLockingFailureException }
        verify(requests, never()).archive(9, 7, 1)
        verifyNoInteractions(outfits, answers)
    }

    @Test
    fun `list loads answers in one batch and keeps pending answer absent`() {
        // given
        val answered = row.copy(status = StylistRequestStatus.ANSWERED, version = 2)
        `when`(requests.findPage(7, null, 50, 0)).thenReturn(listOf(answered, answered.copy(id = 10), row.copy(id = 12)))
        `when`(requests.countOwned(7, null)).thenReturn(3)
        `when`(answers.findAllByRequestIdIn(listOf(9, 10))).thenReturn(listOf(
            StylistRequestAnswer(9, 11, "first", row.createdAt), StylistRequestAnswer(10, 12, "second", row.createdAt),
        ))

        // when
        val result = service.list(owner, PageRequest.of(0, 50))

        // then
        assertThat(result.content.map { it.answer?.explanation }).containsExactly("first", "second", null)
        verify(answers).findAllByRequestIdIn(listOf(9, 10))
        verifyNoMoreInteractions(answers)
    }
}
