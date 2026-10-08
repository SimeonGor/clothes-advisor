package ru.itmo.clothesadvisor.service.access

import java.time.Instant
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*
import ru.itmo.clothesadvisor.model.AccessDeniedException
import ru.itmo.clothesadvisor.model.EntityNotFoundException
import ru.itmo.clothesadvisor.model.user.*
import ru.itmo.clothesadvisor.repository.access.AccessGrantRepository
import ru.itmo.clothesadvisor.repository.user.AppUserRepository
import ru.itmo.clothesadvisor.service.user.AppUserService

class AccessGrantServiceTests {
    private val grants = mock(AccessGrantRepository::class.java)
    private val users = mock(AppUserService::class.java)
    private val service = AccessGrantService(grants, users)

    @Test
    fun `only an active stylist can receive access`() {
        for ((role, status) in listOf(UserRole.USER to UserStatus.ACTIVE, UserRole.STYLIST to UserStatus.BLOCKED)) {
            `when`(users.findById(2)).thenReturn(AppUser("stylist", "hash", role, status, Instant.parse("2026-01-01T00:00:00Z")))
            assertThatThrownBy { service.grant(1, 2) }.isInstanceOf(EntityNotFoundException::class.java)
        }
        verifyNoInteractions(grants)
        `when`(users.findById(2)).thenReturn(AppUser("stylist", "hash", UserRole.STYLIST, UserStatus.ACTIVE, Instant.parse("2026-01-01T00:00:00Z")))
        service.grant(1, 2)
        verify(grants).grant(1, 2)
    }

    @Test
    fun `revoked access is hidden as not found`() {
        `when`(grants.hasActiveAccess(2, 1)).thenReturn(false)
        assertThatThrownBy { service.requireAccess(2, 1) }.isInstanceOf(EntityNotFoundException::class.java)
        verifyNoInteractions(users)
    }

    @Test
    fun `admin content access checks actor before disclosing owner existence`() {
        val repository = mock(AppUserRepository::class.java)
        val admin = AdminContentAccessService(repository)
        assertThatThrownBy { admin.requireAccess(1, 2) }.isInstanceOf(AccessDeniedException::class.java)
        verify(repository, never()).existsById(2)
        `when`(repository.existsByIdAndRoleAndStatus(1, UserRole.ADMIN, UserStatus.ACTIVE)).thenReturn(true)
        assertThatThrownBy { admin.requireAccess(1, 2) }.isInstanceOf(EntityNotFoundException::class.java)
    }
}
