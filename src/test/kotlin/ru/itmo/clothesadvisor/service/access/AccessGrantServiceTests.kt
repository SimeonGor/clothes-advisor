package ru.itmo.clothesadvisor.service.access

import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import ru.itmo.clothesadvisor.model.AccessDeniedException
import ru.itmo.clothesadvisor.model.EntityNotFoundException
import ru.itmo.clothesadvisor.model.user.AppUser
import ru.itmo.clothesadvisor.model.user.UserRole
import ru.itmo.clothesadvisor.model.user.UserStatus
import ru.itmo.clothesadvisor.repository.access.AccessGrantRepository
import ru.itmo.clothesadvisor.repository.user.AppUserRepository
import ru.itmo.clothesadvisor.service.user.AppUserService

class AccessGrantServiceTests {
    private val grants = mock(AccessGrantRepository::class.java)
    private val users = mock(AppUserService::class.java)
    private val service = AccessGrantService(grants, users)

    @Test
    fun `only an active stylist can receive access`() {
        for ((role, status) in
            listOf(UserRole.USER to UserStatus.ACTIVE, UserRole.STYLIST to UserStatus.BLOCKED)) {

            // given
            `when`(users.findById(2))
                .thenReturn(
                    AppUser("stylist", "hash", role, status, Instant.parse("2026-01-01T00:00:00Z")),
                )

            // when
            val failure = catchThrowable { service.grant(1, 2) }

            // then
            assertThat(failure).isInstanceOf(EntityNotFoundException::class.java)
        }
        verifyNoInteractions(grants)

        // given
        `when`(users.findById(2))
            .thenReturn(
                AppUser(
                    "stylist",
                    "hash",
                    UserRole.STYLIST,
                    UserStatus.ACTIVE,
                    Instant.parse("2026-01-01T00:00:00Z"),
                ),
            )

        // when
        service.grant(1, 2)

        // then
        verify(grants).grant(1, 2)
    }

    @Test
    fun `missing active access is hidden as not found`() {

        // given
        `when`(grants.hasActiveAccess(2, 1)).thenReturn(false)

        // when
        val failure = catchThrowable { service.requireAccess(2, 1) }

        // then
        assertThat(failure).isInstanceOf(EntityNotFoundException::class.java)
        verifyNoInteractions(users)
    }

    @Test
    fun `admin content access checks actor before disclosing owner existence`() {

        // given
        val repository = mock(AppUserRepository::class.java)
        val admin = AdminContentAccessService(repository)

        // when
        val denied = catchThrowable { admin.requireAccess(1, 2) }

        // then
        assertThat(denied).isInstanceOf(AccessDeniedException::class.java)
        verify(repository, never()).existsById(2)

        // given
        `when`(repository.existsByIdAndRoleAndStatus(1, UserRole.ADMIN, UserStatus.ACTIVE))
            .thenReturn(true)

        // when
        val missingOwner = catchThrowable { admin.requireAccess(1, 2) }

        // then
        assertThat(missingOwner).isInstanceOf(EntityNotFoundException::class.java)
    }
}
