package ru.itmo.clothesadvisor.accountservice.service.user

import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.dao.DuplicateKeyException
import org.springframework.data.domain.PageRequest
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.test.StepVerifier
import ru.itmo.clothesadvisor.accountservice.dto.user.UpdateUserRoleAndStatusRequest
import ru.itmo.clothesadvisor.accountservice.model.AccessDeniedException
import ru.itmo.clothesadvisor.accountservice.model.EntityNotFoundException
import ru.itmo.clothesadvisor.accountservice.model.user.AppUser
import ru.itmo.clothesadvisor.accountservice.model.user.UserRole
import ru.itmo.clothesadvisor.accountservice.model.user.UserStatus
import ru.itmo.clothesadvisor.accountservice.repository.user.AppUserRepository

internal class AdminUserServiceTests {
    private val repository = mock(AppUserRepository::class.java)
    private val appUsers = mock(AppUserService::class.java)
    private val service = AdminUserService(repository, appUsers)
    private val timestamp = Instant.parse("2026-01-01T01:02:03Z")
    private val request = UpdateUserRoleAndStatusRequest(UserRole.STYLIST, UserStatus.BLOCKED, 1)

    @Test
    fun `list defers queries and counts only after page query completes`() {
        // given
        var pageCompleted = false
        `when`(repository.findPage(2, 2)).thenReturn(
            Flux.just(user(3), user(2)).doOnComplete { pageCompleted = true },
        )
        `when`(repository.count()).thenAnswer {
            assertThat(pageCompleted).isTrue()
            Mono.just(5L)
        }

        // when
        val result = service.list(PageRequest.of(1, 2))

        // then
        verifyNoInteractions(repository)
        StepVerifier.create(result).assertNext {
            assertThat(it.totalElements).isEqualTo(5)
            assertThat(it.content.map { row -> row.id }).containsExactly(3L, 2L)
            assertThat(it.content.first().createdAt).isEqualTo(timestamp)
        }.verifyComplete()
    }

    @Test
    fun `get maps public data and missing user emits not found`() {
        // given
        `when`(repository.findById(1)).thenReturn(Mono.just(user(1)))
        `when`(repository.findById(2)).thenReturn(Mono.empty())

        // when
        val found = service.get(1)
        val missing = service.get(2)

        // then
        verifyNoInteractions(repository)
        StepVerifier.create(found).assertNext {
            assertThat(it.id).isEqualTo(1)
            assertThat(it.login).isEqualTo("user-1")
            assertThat(it.role).isEqualTo(UserRole.ADMIN)
            assertThat(it.status).isEqualTo(UserStatus.ACTIVE)
            assertThat(it.version).isEqualTo(1)
            assertThat(it.createdAt).isEqualTo(timestamp)
            assertThat(it.modifiedAt).isEqualTo(timestamp)
        }.verifyComplete()
        StepVerifier.create(missing).expectError(EntityNotFoundException::class.java).verify()
    }

    @Test
    fun `missing actor fails before mutation`() {
        // given
        `when`(repository.findLockedRows(1, 2)).thenReturn(Flux.just(user(2)))

        // when
        val result = service.update(1, 2, request)

        // then
        StepVerifier.create(result).expectError(EntityNotFoundException::class.java).verify()
        verifyNoInteractions(appUsers)
    }

    @ParameterizedTest
    @CsvSource("USER, ACTIVE", "STYLIST, ACTIVE", "ADMIN, BLOCKED")
    fun `actor must still be active administrator after locks complete`(
        role: UserRole,
        status: UserStatus,
    ) {
        // given
        `when`(repository.findLockedRows(1, 2))
            .thenReturn(Flux.just(user(1, role, status), user(2)))

        // when
        val result = service.update(1, 2, request)

        // then
        verifyNoInteractions(repository, appUsers)
        StepVerifier.create(result).expectError(AccessDeniedException::class.java).verify()
        verifyNoInteractions(appUsers)
    }

    @ParameterizedTest
    @CsvSource("USER, ACTIVE", "ADMIN, BLOCKED")
    fun `self demotion and blocking fail before mutation`(role: UserRole, status: UserStatus) {
        // given
        `when`(repository.findLockedRows(1, 1)).thenReturn(Flux.just(user(1)))

        // when
        val result = service.update(1, 1, UpdateUserRoleAndStatusRequest(role, status, 1))

        // then
        StepVerifier.create(result).expectError(AdminUserConflictException::class.java).verify()
        verifyNoInteractions(appUsers)
    }

    @Test
    fun `authorized change delegates version and history semantics after locking`() {
        // given
        `when`(repository.findLockedRows(1, 2)).thenReturn(Flux.just(user(1), user(2)))
        `when`(appUsers.changeRoleAndStatus(2, 1, request.role, request.status))
            .thenReturn(Mono.just(user(2, request.role, request.status, 2)))

        // when
        val result = service.update(1, 2, request)

        // then
        verifyNoInteractions(repository, appUsers)
        StepVerifier.create(result).assertNext {
            assertThat(it.role).isEqualTo(request.role)
            assertThat(it.status).isEqualTo(request.status)
            assertThat(it.version).isEqualTo(2)
        }.verifyComplete()
        verify(repository).findLockedRows(1, 2)
        verify(appUsers).changeRoleAndStatus(2, 1, request.role, request.status)
    }

    @Test
    fun `self no op returns unchanged data and stale version propagates`() {
        // given
        `when`(repository.findLockedRows(1, 1)).thenReturn(Flux.just(user(1)))
        `when`(appUsers.changeRoleAndStatus(1, 1, UserRole.ADMIN, UserStatus.ACTIVE))
            .thenReturn(Mono.just(user(1)))
        `when`(appUsers.changeRoleAndStatus(1, 2, UserRole.ADMIN, UserStatus.ACTIVE))
            .thenReturn(Mono.error(OptimisticLockingFailureException("stale")))

        // when
        val noOp = service.update(1, 1,
            UpdateUserRoleAndStatusRequest(UserRole.ADMIN, UserStatus.ACTIVE, 1))
        val stale = service.update(1, 1,
            UpdateUserRoleAndStatusRequest(UserRole.ADMIN, UserStatus.ACTIVE, 2))

        // then
        StepVerifier.create(noOp).assertNext {
            assertThat(it.version).isEqualTo(1)
            assertThat(it.modifiedAt).isEqualTo(timestamp)
        }.verifyComplete()
        StepVerifier.create(stale).expectError(OptimisticLockingFailureException::class.java).verify()
    }

    @Test
    fun `missing target failure propagates from existing user service`() {
        // given
        `when`(repository.findLockedRows(1, 2)).thenReturn(Flux.just(user(1)))
        `when`(appUsers.changeRoleAndStatus(2, 1, request.role, request.status))
            .thenReturn(Mono.error(EntityNotFoundException("missing")))

        // when
        val result = service.update(1, 2, request)

        // then
        StepVerifier.create(result).expectError(EntityNotFoundException::class.java).verify()
    }

    @Test
    fun `create converts only duplicate key errors to conflict`() {
        `when`(repository.findLockedRows(1, 1)).thenReturn(Flux.just(user(1)))
        `when`(appUsers.create("new", "hash", UserRole.USER))
            .thenReturn(Mono.error(DuplicateKeyException("login already exists")))

        StepVerifier.create(service.create(1, "new", "hash", UserRole.USER))
            .expectError(AdminUserConflictException::class.java).verify()

        val failure = DataIntegrityViolationException("unrelated constraint")
        `when`(appUsers.create("new", "hash", UserRole.USER)).thenReturn(Mono.error(failure))
        StepVerifier.create(service.create(1, "new", "hash", UserRole.USER))
            .expectErrorMatches { it === failure }.verify()
    }

    @Test
    fun `missing actor cannot create or reset credentials`() {
        `when`(repository.findLockedRows(1, 1)).thenReturn(Flux.empty())
        `when`(repository.findLockedRows(1, 2)).thenReturn(Flux.just(user(2)))

        StepVerifier.create(service.create(1, "new", "hash", UserRole.USER))
            .expectError(AccessDeniedException::class.java).verify()
        StepVerifier.create(service.setPassword(1, 2, 1, "hash"))
            .expectError(AccessDeniedException::class.java).verify()

        verifyNoInteractions(appUsers)
    }

    private fun user(
        id: Long,
        role: UserRole = UserRole.ADMIN,
        status: UserStatus = UserStatus.ACTIVE,
        version: Long = 1,
    ) = AppUser(id, "user-$id", "secret-hash", role, status, version, timestamp, timestamp)
}
