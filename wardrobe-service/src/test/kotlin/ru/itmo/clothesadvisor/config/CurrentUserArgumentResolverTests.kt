package ru.itmo.clothesadvisor.config

import java.time.Duration
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import org.springframework.core.MethodParameter
import org.springframework.security.oauth2.core.OAuth2AuthenticationException
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.server.MockServerWebExchange
import org.springframework.web.reactive.BindingContext
import reactor.core.publisher.Mono
import reactor.core.scheduler.Schedulers
import ru.itmo.clothesadvisor.client.account.AccountClient
import ru.itmo.clothesadvisor.dto.account.AccountUserResponse
import ru.itmo.clothesadvisor.dto.user.CurrentUser
import ru.itmo.clothesadvisor.model.EntityNotFoundException
import ru.itmo.clothesadvisor.model.access.AccountUnavailableException
import ru.itmo.clothesadvisor.model.user.UserRole
import ru.itmo.clothesadvisor.model.user.UserStatus
import ru.itmo.clothesadvisor.service.user.ReactiveCurrentUserService

class CurrentUserArgumentResolverTests {
    private val users = mock(AccountClient::class.java)
    private val converter = CurrentUserJwtConverter(ReactiveCurrentUserService(users))
    private val resolver = CurrentUserArgumentResolver()
    private val parameter = mock(MethodParameter::class.java)

    @Test
    fun `resolver consumes authenticated user and ignores legacy header`() {
        // given
        val actor = CurrentUser(7, "user", UserRole.USER)
        val authentication = CurrentUserJwtAuthentication(jwt(), actor)
        val exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/")
            .header("X-User-Id", "99")).mutate().principal(Mono.just(authentication)).build()

        // when
        val result = resolver.resolveArgument(parameter, BindingContext(), exchange).block()

        // then
        assertThat(result).isSameAs(actor)
        verifyNoInteractions(users)
    }

    @Test
    fun `resolver does not accept header without principal`() {
        // given
        val exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/").header("X-User-Id", "7"))

        // when
        val failure = catchThrowable { resolver.resolveArgument(parameter, BindingContext(), exchange).block() }

        // then
        assertThat(failure).isInstanceOf(OAuth2AuthenticationException::class.java)
        verifyNoInteractions(users)
    }

    @Test
    fun `missing and blocked actors map to separate domain errors`() {

        // given: no actor exists for the token
        `when`(users.getUser(7)).thenReturn(Mono.error(EntityNotFoundException()))

        // when
        val missing = catchThrowable { resolve("7") }

        // then
        assertThat((missing as OAuth2AuthenticationException).error.errorCode).isEqualTo("invalid_token")

        // given
        `when`(users.getUser(7)).thenReturn(
            Mono.just(AccountUserResponse(7, "user", UserRole.USER, UserStatus.BLOCKED)),
        )

        // when
        val blocked = catchThrowable { resolve("7") }

        // then
        assertThat((blocked as OAuth2AuthenticationException).error.errorCode).isEqualTo("account_blocked")
    }

    @Test
    fun `actor context reflects current account role`() {
        UserRole.entries.forEach { role ->

            // given
            `when`(users.getUser(7))
                .thenReturn(Mono.just(AccountUserResponse(7, "user", role, UserStatus.ACTIVE)))

            // when
            val actor = resolve("7")

            // then
            assertThat(actor).isEqualTo(CurrentUser(7, "user", role))
        }
    }

    @Test
    fun `empty account response fails instead of producing an empty actor`() {

        // given
        `when`(users.getUser(7)).thenReturn(Mono.empty())

        // when
        val failure = catchThrowable { resolve("7") }

        // then
        assertThat((failure as OAuth2AuthenticationException).error.errorCode).isEqualTo("account_unavailable")
    }

    @Test
    fun `account unavailability is propagated`() {

        // given
        val error = AccountUnavailableException()
        `when`(users.getUser(7)).thenReturn(Mono.error(error))

        // when
        val failure = catchThrowable { resolve("7") }

        // then
        assertThat((failure as OAuth2AuthenticationException).error.errorCode).isEqualTo("account_unavailable")
    }

    @Test
    fun `account lookup is deferred and stays on the nonblocking subscriber thread`() {

        // given
        `when`(users.getUser(7)).thenAnswer {
            assertThat(Schedulers.isInNonBlockingThread()).isTrue()
            assertThat(Thread.currentThread().name).startsWith("parallel-")
            val callerThread = Thread.currentThread()
            Mono.defer {
                assertThat(Thread.currentThread()).isSameAs(callerThread)
                Mono.just(AccountUserResponse(7, "user", UserRole.USER, UserStatus.ACTIVE))
            }
        }
        val result = resolvePublisher("7")
        verifyNoInteractions(users)

        // when
        val actor = Mono.defer {
            val subscriberThread = Thread.currentThread()
            result.doOnNext {
                assertThat(Thread.currentThread()).isSameAs(subscriberThread)
            }
        }.subscribeOn(Schedulers.parallel()).block(Duration.ofSeconds(5))

        // then
        assertThat(actor).isEqualTo(CurrentUser(7, "user", UserRole.USER))
    }

    private fun resolve(value: String?): CurrentUser =
        resolvePublisher(value).block(Duration.ofSeconds(5)) as CurrentUser

    private fun resolvePublisher(value: String?): Mono<Any> =
        converter.convert(jwt(value!!)).map { (it as CurrentUserJwtAuthentication).user }

    private fun jwt(subject: String = "7"): Jwt = Jwt.withTokenValue("verified")
        .header("alg", "RS256").subject(subject).issuedAt(Instant.EPOCH)
        .expiresAt(Instant.EPOCH.plusSeconds(900)).build()
}
