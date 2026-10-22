package ru.itmo.clothesadvisor.accountservice.service.access

import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.mockito.Mockito.mock
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.verifyNoMoreInteractions
import org.mockito.Mockito.`when`
import reactor.core.publisher.Mono
import reactor.test.StepVerifier
import ru.itmo.clothesadvisor.accountservice.model.AccessDeniedException
import ru.itmo.clothesadvisor.accountservice.model.EntityNotFoundException
import ru.itmo.clothesadvisor.accountservice.model.access.AdminAccessState
import ru.itmo.clothesadvisor.accountservice.repository.access.AccessGrantRepository
import ru.itmo.clothesadvisor.accountservice.repository.user.AppUserRepository

internal class AccountAccessServiceTests {
    private val users = mock(AppUserRepository::class.java)
    private val grants = mock(AccessGrantRepository::class.java)
    private val service = AccountAccessService(users, grants)

    @ParameterizedTest
    @CsvSource("false, false", "false, true", "true, false", "true, true")
    fun `admin decision uses one deferred snapshot and denies admin before missing owner`(
        adminAllowed: Boolean,
        ownerExists: Boolean,
    ) {
        // given
        `when`(users.findAdminAccessState(1, 2))
            .thenReturn(Mono.just(AdminAccessState(adminAllowed, ownerExists)))

        // when
        val result = service.requireAdminAccess(1, 2)

        // then
        verifyNoInteractions(users, grants)
        val verifier = StepVerifier.create(result)
        when {
            !adminAllowed -> verifier.expectError(AccessDeniedException::class.java).verify()
            !ownerExists -> verifier.expectError(EntityNotFoundException::class.java).verify()
            else -> verifier.verifyComplete()
        }
        verify(users).findAdminAccessState(1, 2)
        verifyNoMoreInteractions(users)
        verifyNoInteractions(grants)
    }

    @Test
    fun `stylist permission is deferred and reloaded on each subscription`() {
        // given
        `when`(grants.hasActiveAccess(3, 4)).thenReturn(Mono.just(true), Mono.just(false))

        // when
        val result = service.requireStylistAccess(3, 4)

        // then
        verifyNoInteractions(users, grants)
        StepVerifier.create(result).verifyComplete()
        StepVerifier.create(result).expectError(EntityNotFoundException::class.java).verify()
        verify(grants, times(2)).hasActiveAccess(3, 4)
        verifyNoMoreInteractions(grants)
        verifyNoInteractions(users)
    }

    @Test
    fun `admin permission is reloaded on each subscription`() {
        // given
        `when`(users.findAdminAccessState(1, 2)).thenReturn(
            Mono.just(AdminAccessState(true, true)), Mono.just(AdminAccessState(false, true)),
        )
        val result = service.requireAdminAccess(1, 2)

        // when
        StepVerifier.create(result).verifyComplete()

        // then
        StepVerifier.create(result).expectError(AccessDeniedException::class.java).verify()
        verify(users, times(2)).findAdminAccessState(1, 2)
        verifyNoMoreInteractions(users)
        verifyNoInteractions(grants)
    }

    @Test
    fun `database errors propagate unchanged for both permission checks`() {
        // given
        val failure = IllegalStateException("database unavailable")
        `when`(users.findAdminAccessState(1, 2)).thenReturn(Mono.error(failure))
        `when`(grants.hasActiveAccess(3, 4)).thenReturn(Mono.error(failure))

        // when
        val admin = service.requireAdminAccess(1, 2)
        val stylist = service.requireStylistAccess(3, 4)

        // then
        StepVerifier.create(admin).expectErrorMatches { it === failure }.verify()
        StepVerifier.create(stylist).expectErrorMatches { it === failure }.verify()
    }
}
