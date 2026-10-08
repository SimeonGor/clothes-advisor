package ru.itmo.clothesadvisor.config

import java.time.Instant
import org.assertj.core.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*
import org.springframework.core.MethodParameter
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.web.context.request.ServletWebRequest
import ru.itmo.clothesadvisor.dto.user.CurrentUser
import ru.itmo.clothesadvisor.model.AccessDeniedException
import ru.itmo.clothesadvisor.model.EntityNotFoundException
import ru.itmo.clothesadvisor.model.user.*
import ru.itmo.clothesadvisor.repository.user.AppUserRepository

class CurrentUserArgumentResolverTests {
    private val users = mock(AppUserRepository::class.java)
    private val resolver = CurrentUserArgumentResolver(users)
    private val parameter = mock(MethodParameter::class.java)

    @Test
    fun `invalid actor headers do not query database`() {
        listOf(null, "", "x", "0", "-1", "9223372036854775808").forEach { value ->
            assertThatThrownBy { resolve(value) }.isInstanceOf(InvalidCurrentUserException::class.java)
        }
        verifyNoInteractions(users)
    }

    @Test
    fun `missing and blocked actors map to separate domain errors`() {
        assertThatThrownBy { resolve("7") }.isInstanceOf(EntityNotFoundException::class.java)
        `when`(users.findById(7)).thenReturn(AppUser("user", "!", UserRole.USER, UserStatus.BLOCKED, Instant.EPOCH))
        assertThatThrownBy { resolve("7") }.isInstanceOf(AccessDeniedException::class.java)
    }

    @Test
    fun `actor context reflects current database role`() {
        UserRole.entries.forEach { role ->
            `when`(users.findById(7)).thenReturn(AppUser("user", "!", role, UserStatus.ACTIVE, Instant.EPOCH))
            assertThat(resolve("7")).isEqualTo(CurrentUser(7, "user", role))
        }
    }

    private fun resolve(value: String?): CurrentUser {
        val request = MockHttpServletRequest()
        if (value != null) request.addHeader("X-User-Id", value)
        return resolver.resolveArgument(parameter, null, ServletWebRequest(request), null)
    }
}
