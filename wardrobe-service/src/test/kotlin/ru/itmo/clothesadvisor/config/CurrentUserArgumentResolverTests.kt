package ru.itmo.clothesadvisor.config

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.Test
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import org.springframework.core.MethodParameter
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.web.context.request.ServletWebRequest
import ru.itmo.clothesadvisor.client.account.AccountClient
import ru.itmo.clothesadvisor.dto.account.AccountUserResponse
import ru.itmo.clothesadvisor.dto.user.CurrentUser
import ru.itmo.clothesadvisor.model.AccessDeniedException
import ru.itmo.clothesadvisor.model.EntityNotFoundException
import ru.itmo.clothesadvisor.model.user.UserRole
import ru.itmo.clothesadvisor.model.user.UserStatus
import ru.itmo.clothesadvisor.service.user.CurrentUserService

class CurrentUserArgumentResolverTests {
    private val users = mock(AccountClient::class.java)
    private val resolver = CurrentUserArgumentResolver(CurrentUserService(users))
    private val parameter = mock(MethodParameter::class.java)

    @Test
    fun `invalid actor headers do not query accounts`() {
        listOf(null, "", "x", "0", "-1", "9223372036854775808").forEach { value ->

            // given: an invalid actor header value

            // when
            val failure = catchThrowable { resolve(value) }

            // then
            assertThat(failure).isInstanceOf(InvalidCurrentUserException::class.java)
        }
        verifyNoInteractions(users)
    }

    @Test
    fun `missing and blocked actors map to separate domain errors`() {

        // given: no actor exists for the header
        `when`(users.getUser(7)).thenThrow(EntityNotFoundException())

        // when
        val missing = catchThrowable { resolve("7") }

        // then
        assertThat(missing).isInstanceOf(EntityNotFoundException::class.java)

        // given
        doReturn(AccountUserResponse(7, "user", UserRole.USER, UserStatus.BLOCKED))
            .`when`(users).getUser(7)

        // when
        val blocked = catchThrowable { resolve("7") }

        // then
        assertThat(blocked).isInstanceOf(AccessDeniedException::class.java)
    }

    @Test
    fun `actor context reflects current account role`() {
        UserRole.entries.forEach { role ->

            // given
            `when`(users.getUser(7))
                .thenReturn(AccountUserResponse(7, "user", role, UserStatus.ACTIVE))

            // when
            val actor = resolve("7")

            // then
            assertThat(actor).isEqualTo(CurrentUser(7, "user", role))
        }
    }

    private fun resolve(value: String?): CurrentUser {
        val request = MockHttpServletRequest()
        if (value != null) request.addHeader("X-User-Id", value)
        return resolver.resolveArgument(parameter, null, ServletWebRequest(request), null)
    }
}
