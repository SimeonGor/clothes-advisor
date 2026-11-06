package ru.itmo.clothesadvisor.service.capsule

import jakarta.validation.ConstraintViolationException
import jakarta.validation.Validation
import java.time.Instant
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.verifyNoMoreInteractions
import org.mockito.Mockito.`when`
import org.springframework.data.domain.PageRequest
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.SimpleTransactionStatus
import ru.itmo.clothesadvisor.config.idempotencyService
import ru.itmo.clothesadvisor.dto.capsule.CreateCapsuleRequest
import ru.itmo.clothesadvisor.dto.ai.CreateAiCapsuleRequest
import ru.itmo.clothesadvisor.dto.outfit.OutfitWeatherDto
import ru.itmo.clothesadvisor.dto.user.CurrentUser
import ru.itmo.clothesadvisor.model.AccessDeniedException
import ru.itmo.clothesadvisor.model.EntityNotFoundException
import ru.itmo.clothesadvisor.model.capsule.Capsule
import ru.itmo.clothesadvisor.model.capsule.CapsuleItem
import ru.itmo.clothesadvisor.model.capsule.CapsuleItemId
import ru.itmo.clothesadvisor.model.capsule.CapsuleSource
import ru.itmo.clothesadvisor.model.capsule.CapsuleWeather
import ru.itmo.clothesadvisor.model.user.UserRole
import ru.itmo.clothesadvisor.repository.capsule.CapsuleItemRepository
import ru.itmo.clothesadvisor.repository.capsule.CapsuleRepository
import ru.itmo.clothesadvisor.repository.capsule.CapsuleWeatherRepository
import ru.itmo.clothesadvisor.repository.wardrobe.WardrobeItemRepository

class CapsuleServiceTests {
    private val capsules = mock(CapsuleRepository::class.java)
    private val composition = mock(CapsuleItemRepository::class.java)
    private val weather = mock(CapsuleWeatherRepository::class.java)
    private val wardrobe = mock(WardrobeItemRepository::class.java)
    private val transactions = mock(PlatformTransactionManager::class.java)
    private val validation = Validation.buildDefaultValidatorFactory()
    private val service = CapsuleService(capsules, composition, weather, wardrobe, validation.validator,
        idempotencyService(transactions), transactions)
    private val owner = CurrentUser(7, "owner", UserRole.USER)
    private val row = Capsule(9, 7, "Daily", Instant.parse("2026-01-01T00:00:00Z"))
    private val request = CreateCapsuleRequest("Daily", listOf(12, 11))

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
        val failures = listOf(catchThrowable { service.create(actor, request, "replay") },
            catchThrowable { service.get(actor, 9) },
            catchThrowable { service.list(actor, PageRequest.of(0, 50)) },
            catchThrowable { service.delete(actor, 9) })

        // then
        assertThat(failures).allMatch { it is AccessDeniedException }
        verifyNoInteractions(capsules, composition, wardrobe, transactions)
    }

    @Test
    fun `create counts owned items then inserts ordered composition and returns persisted fields`() {
        // given
        `when`(wardrobe.countByOwnerIdAndIdIn(7, listOf(12, 11))).thenReturn(2)
        `when`(capsules.create(7, "Daily")).thenReturn(row)
        var inserted = emptyList<CapsuleItem>()
        doAnswer { call -> inserted = call.getArgument<Iterable<CapsuleItem>>(0).toList(); null }
            .`when`(composition).insertAll(any<Iterable<CapsuleItem>>() ?: emptyList())

        // when
        val result = service.create(owner, request)

        // then
        assertThat(result.id).isEqualTo(9)
        assertThat(result.ownerId).isEqualTo(7)
        assertThat(result.name).isEqualTo(row.name)
        assertThat(result.createdAt).isEqualTo(row.createdAt)
        assertThat(result.source).isEqualTo(CapsuleSource.USER)
        assertThat(result.weather).isNull()
        assertThat(result.itemIds).containsExactly(12, 11)
        assertThat(inserted.map { it.id }).containsExactly(CapsuleItemId(9, 12), CapsuleItemId(9, 11))
        assertThat(inserted.map { it.position }).containsExactly(0, 1)
        inOrder(wardrobe, capsules, composition).apply {
            verify(wardrobe).countByOwnerIdAndIdIn(7, listOf(12, 11))
            verify(capsules).create(7, "Daily")
            verify(composition).insertAll(inserted)
        }
    }

    @Test
    fun `AI creation saves weather and ordered items with persisted source context and date`() {
        // given
        val input = CreateAiCapsuleRequest("Daily", OutfitWeatherDto(BigDecimal.ONE, 1, BigDecimal.TEN),
            occasion = "Work", preferredStyle = "Classic")
        val aiRow = row.copy(source = CapsuleSource.AI, occasion = "Work", preferredStyle = "Classic", explanation = "Fits.")
        `when`(capsules.createAi(7, "Daily", "Work", "Classic", "Fits.")).thenReturn(aiRow)
        var savedWeather: CapsuleWeather? = null
        doAnswer { savedWeather = it.getArgument(0); null }
            .`when`(weather).insert(any(CapsuleWeather::class.java) ?: CapsuleWeather(9, BigDecimal.ONE, 1, BigDecimal.TEN))
        var inserted = emptyList<CapsuleItem>()
        doAnswer { inserted = it.getArgument<Iterable<CapsuleItem>>(0).toList(); null }
            .`when`(composition).insertAll(any<Iterable<CapsuleItem>>() ?: emptyList())

        // when
        val result = service.createAi(7, input, listOf(12, 11), "Fits.")

        // then
        assertThat(result.source).isEqualTo(CapsuleSource.AI)
        assertThat(result.weather).isEqualTo(input.weather)
        assertThat(result.occasion).isEqualTo(aiRow.occasion)
        assertThat(result.preferredStyle).isEqualTo(aiRow.preferredStyle)
        assertThat(result.explanation).isEqualTo(aiRow.explanation)
        assertThat(result.createdAt).isEqualTo(row.createdAt)
        assertThat(savedWeather!!.id).isEqualTo(row.id)
        assertThat(savedWeather!!.temperatureC).isEqualByComparingTo(BigDecimal.ONE)
        assertThat(savedWeather!!.precipitationTypeId).isEqualTo(1)
        assertThat(savedWeather!!.windSpeedMps).isEqualByComparingTo(BigDecimal.TEN)
        assertThat(inserted.map { it.id }).containsExactly(CapsuleItemId(9, 12), CapsuleItemId(9, 11))
        assertThat(inserted.map { it.position }).containsExactly(0, 1)
        verifyNoInteractions(wardrobe)
    }

    @Test
    fun `invalid names and compositions fail before persistence`() {
        // given
        val invalidIds = listOf(emptyList(), listOf(null), listOf(0L), listOf(-1L), listOf(1L, 1L), (1L..21L).toList())

        // when
        val ids = invalidIds.map { catchThrowable { service.create(owner, request.copy(itemIds = it)) } }
        val names = listOf("", "  ", "x".repeat(301)).map {
            catchThrowable { service.create(owner, request.copy(name = it)) }
        }

        // then
        assertThat(ids).allMatch { it is InvalidCapsuleRequestException }
        assertThat(names).allMatch { it is ConstraintViolationException }
        verifyNoInteractions(capsules, composition, wardrobe, transactions)
    }

    @Test
    fun `missing owned items or capsule fail without writes`() {
        // given
        `when`(wardrobe.countByOwnerIdAndIdIn(7, listOf(12, 11))).thenReturn(1)

        // when
        val failures = listOf(catchThrowable { service.create(owner, request) },
            catchThrowable { service.get(owner, 9) }, catchThrowable { service.delete(owner, 9) })

        // then
        assertThat(failures).allMatch { it is EntityNotFoundException }
        verify(capsules).findByIdAndOwnerId(9, 7)
        verify(capsules).findLockedByIdAndOwnerId(9, 7)
        verifyNoMoreInteractions(capsules)
        verifyNoInteractions(composition)
    }

    @Test
    fun `list passes page and count and loads all compositions in one batch`() {
        // given
        `when`(capsules.findPage(7, 2, 2)).thenReturn(listOf(row, row.copy(id = 8, source = CapsuleSource.AI, explanation = "Fits.")))
        `when`(capsules.countByOwnerId(7)).thenReturn(4)
        `when`(composition.findRows(listOf(9, 8))).thenReturn(listOf(
            CapsuleItem(CapsuleItemId(9, 12), 0), CapsuleItem(CapsuleItemId(8, 11), 0),
            CapsuleItem(CapsuleItemId(9, 11), 1)))
        `when`(weather.findAllByIdIn(listOf(9, 8))).thenReturn(listOf(CapsuleWeather(8, BigDecimal.ONE, 1, BigDecimal.TEN)))

        // when
        val result = service.list(owner, PageRequest.of(1, 2))

        // then
        assertThat(result.totalElements).isEqualTo(4)
        assertThat(result.content.map { it.id }).containsExactly(9, 8)
        assertThat(result.content.map { it.itemIds }).containsExactly(listOf(12L, 11L), listOf(11L))
        assertThat(result.content[0].source).isEqualTo(CapsuleSource.USER)
        assertThat(result.content[0].weather).isNull()
        assertThat(result.content[1].source).isEqualTo(CapsuleSource.AI)
        assertThat(result.content[1].weather!!.windSpeedMps).isEqualByComparingTo(BigDecimal.TEN)
        assertThat(result.content[1].explanation).isEqualTo("Fits.")
        verify(weather).findAllByIdIn(listOf(9, 8))
        verifyNoMoreInteractions(weather)
        verify(composition).findRows(listOf(9, 8))
        verifyNoMoreInteractions(composition)
    }

    @Test
    fun `empty page avoids composition lookup`() {
        // given
        `when`(capsules.findPage(7, 50, 0)).thenReturn(emptyList())

        // when
        val result = service.list(owner, PageRequest.of(0, 50))

        // then
        assertThat(result.content).isEmpty()
        assertThat(result.totalElements).isZero()
        verifyNoInteractions(composition, weather)
    }

    @Test
    fun `delete locks owned capsule before deleting it`() {
        // given
        `when`(capsules.findLockedByIdAndOwnerId(9, 7)).thenReturn(row)

        // when
        service.delete(owner, 9)

        // then
        inOrder(capsules).apply {
            verify(capsules).findLockedByIdAndOwnerId(9, 7)
            verify(capsules).delete(row)
        }
        verifyNoInteractions(wardrobe, composition)
    }
}
