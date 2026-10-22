package ru.itmo.clothesadvisor.config

import java.time.Duration
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.Test
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import org.springframework.core.MethodParameter
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.server.MockServerWebExchange
import org.springframework.web.reactive.BindingContext
import reactor.core.publisher.Mono
import reactor.core.scheduler.Schedulers
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

    @Test
    fun `account lookup is deferred and runs on a blocking worker`() {

        // given
        `when`(users.getUser(7)).thenAnswer {
            assertThat(Schedulers.isInNonBlockingThread()).isFalse()
            assertThat(Thread.currentThread().name).startsWith("boundedElastic-")
            AccountUserResponse(7, "user", UserRole.USER, UserStatus.ACTIVE)
        }

        // when
        val result = resolvePublisher("7")

        // then
        verifyNoInteractions(users)
        assertThat(result.block(Duration.ofSeconds(5)))
            .isEqualTo(CurrentUser(7, "user", UserRole.USER))
    }

    private fun resolve(value: String?): CurrentUser =
        resolvePublisher(value).block(Duration.ofSeconds(5)) as CurrentUser

    private fun resolvePublisher(value: String?): Mono<Any> {
        val request = MockServerHttpRequest.get("/")
        if (value != null) request.header("X-User-Id", value)
        return resolver.resolveArgument(
            parameter, BindingContext(), MockServerWebExchange.from(request),
        )
    }
}
