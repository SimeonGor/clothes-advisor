package ru.itmo.clothesadvisor.accountservice.service.user

import jakarta.validation.ConstraintViolationException
import jakarta.validation.Validation
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.mock
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.verifyNoMoreInteractions
import org.mockito.Mockito.`when`
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.dao.OptimisticLockingFailureException
import reactor.core.publisher.Mono
import reactor.test.StepVerifier
import ru.itmo.clothesadvisor.accountservice.model.EntityNotFoundException
import ru.itmo.clothesadvisor.accountservice.model.user.AppUser
import ru.itmo.clothesadvisor.accountservice.model.user.AppUserHistory
import ru.itmo.clothesadvisor.accountservice.model.user.AppUserHistoryId
import ru.itmo.clothesadvisor.accountservice.model.user.UserRole
import ru.itmo.clothesadvisor.accountservice.model.user.UserStatus
import ru.itmo.clothesadvisor.accountservice.repository.user.AppUserHistoryRepository
import ru.itmo.clothesadvisor.accountservice.repository.user.AppUserRepository
import ru.itmo.clothesadvisor.accountservice.repository.auth.AuthSessionRepository

class AppUserServiceTests {
    private val repository = mock(AppUserRepository::class.java)
    private val history = mock(AppUserHistoryRepository::class.java)
    private val validation = Validation.buildDefaultValidatorFactory()
    private val sessions = mock(AuthSessionRepository::class.java)
    private val service = AppUserService(repository, history, validation.validator, sessions)
    private val storedAt = Instant.parse("2026-01-01T01:02:03Z")

    @AfterEach fun closeValidation() = validation.close()

    @Test
    fun `invalid credentials fail on subscription without repository writes`() {

        // given
        val credentials = listOf(
            "" to "hash", " \t\n" to "hash", "a".repeat(101) to "hash", "alice" to " ",
        )

        for ((login, hash) in credentials) {

            // when
            val result = service.create(login, hash, UserRole.USER)

            // then
            StepVerifier.create(result)
                .expectError(ConstraintViolationException::class.java)
                .verify()
        }
        verifyNoInteractions(repository, history)
    }

    @Test
    fun `creation is deferred and returns database values at the login boundary`() {

        // given
        val login = "a".repeat(100)
        val saved = user(login = login)
        `when`(repository.create(login, "hash", UserRole.USER, UserStatus.ACTIVE))
            .thenReturn(Mono.just(saved))

        // when
        val result = service.create(login, "hash", UserRole.USER)

        // then
        verifyNoInteractions(repository, history)
        StepVerifier.create(result).assertNext { assertThat(it).isSameAs(saved) }.verifyComplete()
    }

    @Test
    fun `read operations are deferred and preserve absent results`() {

        // given
        `when`(repository.findById(7)).thenReturn(Mono.empty())
        `when`(repository.findByLogin("missing")).thenReturn(Mono.empty())

        // when
        val byId = service.findById(7)
        val byLogin = service.findByLogin("missing")

        // then
        verifyNoInteractions(repository, history)
        StepVerifier.create(byId).verifyComplete()
        StepVerifier.create(byLogin).verifyComplete()
    }

    @Test
    fun `reads return persisted users without exposing hash in string representation`() {

        // given
        val saved = user()
        `when`(repository.findById(7)).thenReturn(Mono.just(saved))
        `when`(repository.findByLogin("alice")).thenReturn(Mono.just(saved))

        // when
        val byId = service.findById(7)
        val byLogin = service.findByLogin("alice")

        // then
        StepVerifier.create(byId).expectNext(saved).verifyComplete()
        StepVerifier.create(byLogin).expectNext(saved).verifyComplete()
        assertThat(saved.toString()).doesNotContain(saved.passwordHash)
    }

    @Test
    fun `no op is deferred and preserves the stored object without writes`() {

        // given
        val original = user()
        `when`(repository.findById(7)).thenReturn(Mono.just(original))

        // when
        val result = service.changeRoleAndStatus(7, 1, UserRole.USER, UserStatus.ACTIVE)

        // then
        verifyNoInteractions(repository, history)
        StepVerifier.create(result).expectNext(original).verifyComplete()
        verify(repository).findById(7)
        verifyNoMoreInteractions(repository)
        verifyNoInteractions(history)
    }

    @Test
    fun `stale version fails before both no op and changed values`() {

        // given
        `when`(repository.findById(7)).thenReturn(Mono.just(user()))

        for (role in listOf(UserRole.USER, UserRole.ADMIN)) {

            // when
            val result = service.changeRoleAndStatus(7, 2, role, UserStatus.ACTIVE)

            // then
            StepVerifier.create(result)
                .expectError(OptimisticLockingFailureException::class.java)
                .verify()
        }
        verify(repository, times(2)).findById(7)
        verifyNoMoreInteractions(repository)
        verifyNoInteractions(history)
    }

    @Test
    fun `missing mutation target emits domain error without writes`() {

        // given
        `when`(repository.findById(7)).thenReturn(Mono.empty())

        // when
        val result = service.changeRoleAndStatus(7, 1, UserRole.ADMIN, UserStatus.ACTIVE)

        // then
        StepVerifier.create(result).expectError(EntityNotFoundException::class.java).verify()
        verify(repository).findById(7)
        verifyNoMoreInteractions(repository)
        verifyNoInteractions(history)
    }

    @Test
    fun `change archives original values and returns database dates and version`() {

        // given
        val original = user()
        val saved = user(UserRole.STYLIST, 2, modifiedAt = storedAt.plusSeconds(30))
        `when`(repository.findById(7)).thenReturn(Mono.just(original))
        `when`(repository.updateRow(7, 1, UserRole.STYLIST, UserStatus.ACTIVE))
            .thenReturn(Mono.just(saved))
        var archived: AppUserHistory? = null
        doAnswer { call ->
            val snapshot = call.getArgument<AppUserHistory>(0)
            archived = snapshot
            Mono.just(snapshot)
        }.`when`(history).insert(any(AppUserHistory::class.java) ?: AppUserHistory(original))

        // when
        val result = service.changeRoleAndStatus(7, 1, UserRole.STYLIST, UserStatus.ACTIVE)

        // then
        StepVerifier.create(result).expectNext(saved).verifyComplete()
        val snapshot = requireNotNull(archived)
        assertThat(snapshot.id).isEqualTo(AppUserHistoryId(7, 1))
        assertThat(snapshot.login).isEqualTo(original.login)
        assertThat(snapshot.role).isEqualTo(original.role)
        assertThat(snapshot.status).isEqualTo(original.status)
        assertThat(snapshot.modifiedAt).isEqualTo(storedAt)
        assertThat(original.role).isEqualTo(UserRole.USER)
        assertThat(original.version).isEqualTo(1)
        inOrder(repository, history).apply {
            verify(repository).findById(7)
            verify(repository).updateRow(7, 1, UserRole.STYLIST, UserStatus.ACTIVE)
            verify(history).insert(snapshot)
        }
    }

    @Test
    fun `changing only status updates row and archives previous active state`() {

        // given
        val original = user()
        val saved = AppUser(7, "alice", "hash", UserRole.USER, UserStatus.BLOCKED, 2,
            storedAt, storedAt.plusSeconds(30))
        `when`(repository.findById(7)).thenReturn(Mono.just(original))
        `when`(repository.updateRow(7, 1, UserRole.USER, UserStatus.BLOCKED))
            .thenReturn(Mono.just(saved))
        doAnswer { call ->
            val snapshot = call.getArgument<AppUserHistory>(0)
            assertThat(snapshot.status).isEqualTo(UserStatus.ACTIVE)
            assertThat(snapshot.role).isEqualTo(UserRole.USER)
            Mono.just(snapshot)
        }.`when`(history).insert(any(AppUserHistory::class.java) ?: AppUserHistory(original))

        // when
        val result = service.changeRoleAndStatus(7, 1, UserRole.USER, UserStatus.BLOCKED)

        // then
        StepVerifier.create(result).expectNext(saved).verifyComplete()
    }

    @Test
    fun `empty update returning emits version conflict without history`() {

        // given
        `when`(repository.findById(7)).thenReturn(Mono.just(user()))
        `when`(repository.updateRow(7, 1, UserRole.ADMIN, UserStatus.ACTIVE))
            .thenReturn(Mono.empty())

        // when
        val result = service.changeRoleAndStatus(7, 1, UserRole.ADMIN, UserStatus.ACTIVE)

        // then
        StepVerifier.create(result)
            .expectError(OptimisticLockingFailureException::class.java)
            .verify()
        verifyNoInteractions(history)
    }

    @Test
    fun `history failure propagates instead of returning the changed user`() {

        // given
        val original = user()
        val failure = DataIntegrityViolationException("history conflict")
        `when`(repository.findById(7)).thenReturn(Mono.just(original))
        `when`(repository.updateRow(7, 1, UserRole.ADMIN, UserStatus.ACTIVE))
            .thenReturn(Mono.just(user(UserRole.ADMIN, 2)))
        doAnswer { Mono.error<AppUserHistory>(failure) }
            .`when`(history).insert(any(AppUserHistory::class.java) ?: AppUserHistory(original))

        // when
        val result = service.changeRoleAndStatus(7, 1, UserRole.ADMIN, UserStatus.ACTIVE)

        // then
        StepVerifier.create(result).expectErrorMatches { it === failure }.verify()
    }

    private fun user(
        role: UserRole = UserRole.USER,
        version: Long = 1,
        login: String = "alice",
        modifiedAt: Instant = storedAt,
    ) = AppUser(7, login, "hash", role, UserStatus.ACTIVE, version, storedAt, modifiedAt)
}
