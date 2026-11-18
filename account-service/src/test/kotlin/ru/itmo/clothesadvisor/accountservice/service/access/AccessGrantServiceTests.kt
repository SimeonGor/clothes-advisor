package ru.itmo.clothesadvisor.accountservice.service.access

import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import org.springframework.data.domain.PageRequest
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.test.StepVerifier
import ru.itmo.clothesadvisor.accountservice.model.AccessDeniedException
import ru.itmo.clothesadvisor.accountservice.model.EntityNotFoundException
import ru.itmo.clothesadvisor.accountservice.model.user.AppUser
import ru.itmo.clothesadvisor.accountservice.model.user.UserRole
import ru.itmo.clothesadvisor.accountservice.model.user.UserStatus
import ru.itmo.clothesadvisor.accountservice.repository.access.AccessGrantRepository
import ru.itmo.clothesadvisor.accountservice.service.user.AppUserService
import ru.itmo.clothesadvisor.accountservice.service.user.CurrentUserService

internal class AccessGrantServiceTests {
    private val grants = mock(AccessGrantRepository::class.java)
    private val users = mock(AppUserService::class.java)
    private val service = AccessGrantService(grants, users, CurrentUserService(users))
    private val page = PageRequest.of(1, 2)

    @ParameterizedTest
    @CsvSource("missing", "blocked")
    fun `all public operations defer and reject unavailable actor before repository work`(
        state: String,
    ) {
        // given
        val actor = if (state == "missing") Mono.empty()
            else Mono.just(user(1, status = UserStatus.BLOCKED))
        `when`(users.findById(1)).thenReturn(actor)
        val results = listOf(service.grant(1, 2), service.revoke(1, 2),
            service.listRecipients(1, page), service.listClients(1, page))

        // when
        verifyNoInteractions(users, grants)
        for (result in results) {
            StepVerifier.create(result).expectError(
                if (state == "missing") EntityNotFoundException::class.java
                else AccessDeniedException::class.java,
            ).verify()
        }

        // then
        verify(users, times(4)).findById(1)
        verifyNoInteractions(grants)
        verify(users, never()).findById(2)
    }

    @ParameterizedTest
    @CsvSource("USER, ACTIVE", "ADMIN, ACTIVE", "STYLIST, BLOCKED")
    fun `grant requires currently active stylist`(role: UserRole, status: UserStatus) {
        // given
        `when`(users.findById(1)).thenReturn(Mono.just(user(1)))
        `when`(users.findById(2)).thenReturn(Mono.just(user(2, role, status)))

        // when
        val result = service.grant(1, 2)

        // then
        StepVerifier.create(result).expectError(EntityNotFoundException::class.java).verify()
        verifyNoInteractions(grants)
    }

    @Test
    fun `missing grant recipient fails without inserting`() {
        // given
        `when`(users.findById(1)).thenReturn(Mono.just(user(1)))
        `when`(users.findById(2)).thenReturn(Mono.empty())

        // when
        val result = service.grant(1, 2)

        // then
        StepVerifier.create(result).expectError(EntityNotFoundException::class.java).verify()
        verifyNoInteractions(grants)
    }

    @ParameterizedTest
    @CsvSource("USER", "STYLIST", "ADMIN")
    fun `any active role can grant and affected counts are not exposed`(role: UserRole) {
        // given
        `when`(users.findById(1)).thenReturn(Mono.just(user(1, role)))
        `when`(users.findById(2)).thenReturn(Mono.just(user(2, UserRole.STYLIST)))
        `when`(grants.grant(1, 2, null)).thenReturn(Mono.just(1), Mono.just(0))

        // when
        val result = service.grant(1, 2)

        // then
        verifyNoInteractions(users, grants)
        StepVerifier.create(result).verifyComplete()
        StepVerifier.create(result).verifyComplete()
        verify(users, times(2)).findById(2)
        verify(grants, times(2)).grant(1, 2, null)
    }

    @Test
    fun `revoke never resolves target and accepts absent grant`() {
        // given
        `when`(users.findById(1)).thenReturn(Mono.just(user(1)))
        `when`(grants.revoke(1, 2, null)).thenReturn(Mono.just(1), Mono.just(0))

        // when
        val result = service.revoke(1, 2)

        // then
        StepVerifier.create(result).verifyComplete()
        StepVerifier.create(result).verifyComplete()
        verify(users, never()).findById(2)
        verify(grants, times(2)).revoke(1, 2, null)
    }

    @Test
    fun `lists pass pagination to SQL and map only id and login`() {
        // given
        `when`(users.findById(1)).thenReturn(Mono.just(user(1)))
        `when`(grants.findRecipients(1, 2, 2)).thenReturn(Flux.just(user(3)))
        `when`(grants.findClients(1, 2, 2)).thenReturn(Flux.just(user(4)))

        // when
        val recipients = service.listRecipients(1, page)
        val clients = service.listClients(1, page)

        // then
        verifyNoInteractions(users, grants)
        StepVerifier.create(recipients).assertNext {
            assertThat(it.single().id).isEqualTo(3)
            assertThat(it.single().login).isEqualTo("user-3")
        }.verifyComplete()
        StepVerifier.create(clients).assertNext {
            assertThat(it.single().id).isEqualTo(4)
            assertThat(it.single().login).isEqualTo("user-4")
        }.verifyComplete()
    }

    private fun user(
        id: Long,
        role: UserRole = UserRole.USER,
        status: UserStatus = UserStatus.ACTIVE,
    ) = AppUser(id, "user-$id", "private-hash", role, status, 1, Instant.EPOCH, Instant.EPOCH)
}
