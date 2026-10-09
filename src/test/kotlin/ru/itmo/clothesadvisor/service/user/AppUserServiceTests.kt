package ru.itmo.clothesadvisor.service.user

import jakarta.validation.ConstraintViolationException
import jakarta.validation.Validation
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import org.springframework.dao.OptimisticLockingFailureException
import ru.itmo.clothesadvisor.model.EntityNotFoundException
import ru.itmo.clothesadvisor.model.user.AppUser
import ru.itmo.clothesadvisor.model.user.AppUserHistory
import ru.itmo.clothesadvisor.model.user.AppUserHistoryId
import ru.itmo.clothesadvisor.model.user.UserRole
import ru.itmo.clothesadvisor.model.user.UserStatus
import ru.itmo.clothesadvisor.repository.user.AppUserHistoryRepository
import ru.itmo.clothesadvisor.repository.user.AppUserRepository

class AppUserServiceTests {
    private val repository = mock(AppUserRepository::class.java)
    private val history = mock(AppUserHistoryRepository::class.java)
    private val validation = Validation.buildDefaultValidatorFactory()
    private val service = AppUserService(repository, history, validation.validator)
    private val storedAt = Instant.parse("2026-01-01T01:02:03Z")

    @AfterEach fun closeValidation() = validation.close()

    private fun user(role: UserRole = UserRole.USER, version: Long = 1) =
        AppUser("alice", "hash", role, UserStatus.ACTIVE, storedAt).apply {
            id = 7
            this.version = version
        }

    @Test
    fun `input constraints reject blank credentials and oversized login before writes`() {
        for ((login, hash) in
            listOf("" to "hash", "  " to "hash", "a".repeat(101) to "hash", "alice" to " ")) {

            // given: invalid credentials from this case

            // when
            val failure = catchThrowable { service.create(login, hash, UserRole.USER) }

            // then
            assertThat(failure).isInstanceOf(ConstraintViolationException::class.java)
        }
        verifyNoInteractions(repository, history)

        // given
        val login = "a".repeat(100)
        val saved = user()
        `when`(repository.create(login, "hash", UserRole.USER, UserStatus.ACTIVE)).thenReturn(saved)

        // when
        val created = service.create(login, "hash", UserRole.USER)

        // then
        assertThat(created).isSameAs(saved)
    }

    @Test
    fun `no op preserves row while stale and missing writes fail without history`() {

        // given
        val original = user()
        `when`(repository.findById(7)).thenReturn(original)

        // when
        val unchanged = service.changeRoleAndStatus(7, 1, UserRole.USER, UserStatus.ACTIVE)

        // then
        assertThat(unchanged).isSameAs(original)

        // when
        val stale = catchThrowable {
            service.changeRoleAndStatus(7, 2, UserRole.USER, UserStatus.ACTIVE)
        }

        // then
        assertThat(stale).isInstanceOf(OptimisticLockingFailureException::class.java)

        // when
        val missing = catchThrowable {
            service.changeRoleAndStatus(8, 1, UserRole.ADMIN, UserStatus.ACTIVE)
        }

        // then
        assertThat(missing).isInstanceOf(EntityNotFoundException::class.java)
        verify(repository, never()).update(original)
        verifyNoInteractions(history)
    }

    @Test
    fun `change archives pre update snapshot and returns repository dates and version`() {

        // given
        val original = user()
        val saved = user(UserRole.STYLIST, 2).apply { modifiedAt = storedAt.plusSeconds(30) }
        `when`(repository.findById(7)).thenReturn(original)
        `when`(repository.update(original)).thenReturn(saved)
        var archived: AppUserHistory? = null
        doAnswer {
                archived = it.getArgument(0)
                null
            }
            .`when`(history)
            .insert(any(AppUserHistory::class.java) ?: AppUserHistory(original))

        // when
        val changed = service.changeRoleAndStatus(7, 1, UserRole.STYLIST, UserStatus.ACTIVE)

        // then
        assertThat(changed).isSameAs(saved)
        assertThat(archived!!.id).isEqualTo(AppUserHistoryId(7, 1))
        assertThat(archived!!.role).isEqualTo(UserRole.USER)
        assertThat(archived!!.modifiedAt).isEqualTo(storedAt)
        inOrder(repository, history).apply {
            verify(repository).update(original)
            verify(history).insert(archived!!)
        }
    }

    @Test
    fun `database version conflict cannot append history`() {

        // given
        val original = user()
        `when`(repository.findById(7)).thenReturn(original)
        `when`(repository.update(original)).thenThrow(OptimisticLockingFailureException("conflict"))

        // when
        val failure = catchThrowable {
            service.changeRoleAndStatus(7, 1, UserRole.ADMIN, UserStatus.ACTIVE)
        }

        // then
        assertThat(failure).isInstanceOf(OptimisticLockingFailureException::class.java)
        verifyNoInteractions(history)
    }
}
