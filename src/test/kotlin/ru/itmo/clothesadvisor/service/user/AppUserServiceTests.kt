package ru.itmo.clothesadvisor.service.user

import jakarta.validation.ConstraintViolationException
import jakarta.validation.Validation
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.*
import org.springframework.dao.OptimisticLockingFailureException
import ru.itmo.clothesadvisor.model.EntityNotFoundException
import ru.itmo.clothesadvisor.model.user.*
import ru.itmo.clothesadvisor.repository.user.*

class AppUserServiceTests {
    private val repository = mock(AppUserRepository::class.java)
    private val history = mock(AppUserHistoryRepository::class.java)
    private val validation = Validation.buildDefaultValidatorFactory()
    private val service = AppUserService(repository, history, validation.validator)
    private val storedAt = Instant.parse("2026-01-01T01:02:03Z")

    @AfterEach fun closeValidation() = validation.close()

    private fun user(role: UserRole = UserRole.USER, version: Long = 1) =
        AppUser("alice", "hash", role, UserStatus.ACTIVE, storedAt).apply { id = 7; this.version = version }

    @Test
    fun `input constraints reject blank credentials and oversized login before writes`() {
        for ((login, hash) in listOf("" to "hash", "  " to "hash", "a".repeat(101) to "hash", "alice" to " ")) {
            assertThatThrownBy { service.create(login, hash, UserRole.USER) }.isInstanceOf(ConstraintViolationException::class.java)
        }
        verifyNoInteractions(repository, history)
        val login = "a".repeat(100)
        val saved = user()
        `when`(repository.create(login, "hash", UserRole.USER, UserStatus.ACTIVE)).thenReturn(saved)
        assertThat(service.create(login, "hash", UserRole.USER)).isSameAs(saved)
    }

    @Test
    fun `no op preserves row while stale and missing writes fail without history`() {
        val original = user()
        `when`(repository.findById(7)).thenReturn(original)
        assertThat(service.changeRoleAndStatus(7, 1, UserRole.USER, UserStatus.ACTIVE)).isSameAs(original)
        assertThatThrownBy { service.changeRoleAndStatus(7, 2, UserRole.USER, UserStatus.ACTIVE) }
            .isInstanceOf(OptimisticLockingFailureException::class.java)
        assertThatThrownBy { service.changeRoleAndStatus(8, 1, UserRole.ADMIN, UserStatus.ACTIVE) }
            .isInstanceOf(EntityNotFoundException::class.java)
        verify(repository, never()).update(original)
        verifyNoInteractions(history)
    }

    @Test
    fun `change archives pre update snapshot and returns repository dates and version`() {
        val original = user()
        val saved = user(UserRole.STYLIST, 2).apply { modifiedAt = storedAt.plusSeconds(30) }
        `when`(repository.findById(7)).thenReturn(original)
        `when`(repository.update(original)).thenReturn(saved)
        var archived: AppUserHistory? = null
        doAnswer { archived = it.getArgument(0); null }.`when`(history).insert(any(AppUserHistory::class.java) ?: AppUserHistory(original))
        assertThat(service.changeRoleAndStatus(7, 1, UserRole.STYLIST, UserStatus.ACTIVE)).isSameAs(saved)
        assertThat(archived!!.id).isEqualTo(AppUserHistoryId(7, 1))
        assertThat(archived!!.role).isEqualTo(UserRole.USER)
        assertThat(archived!!.modifiedAt).isEqualTo(storedAt)
        inOrder(repository, history).apply { verify(repository).update(original); verify(history).insert(archived!!) }
    }

    @Test
    fun `database version conflict cannot append history`() {
        val original = user()
        `when`(repository.findById(7)).thenReturn(original)
        `when`(repository.update(original)).thenThrow(OptimisticLockingFailureException("conflict"))
        assertThatThrownBy { service.changeRoleAndStatus(7, 1, UserRole.ADMIN, UserStatus.ACTIVE) }
            .isInstanceOf(OptimisticLockingFailureException::class.java)
        verifyNoInteractions(history)
    }
}
