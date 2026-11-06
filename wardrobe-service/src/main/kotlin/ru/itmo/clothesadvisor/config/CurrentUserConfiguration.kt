package ru.itmo.clothesadvisor.config

import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Lazy
import org.springframework.core.MethodParameter
import org.springframework.web.reactive.BindingContext
import org.springframework.web.reactive.config.WebFluxConfigurer
import org.springframework.web.reactive.result.method.HandlerMethodArgumentResolver
import org.springframework.web.reactive.result.method.annotation.ArgumentResolverConfigurer
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Mono
import ru.itmo.clothesadvisor.dto.user.CurrentUser
import ru.itmo.clothesadvisor.service.user.ReactiveCurrentUserService

internal class InvalidCurrentUserException : RuntimeException()

internal class CurrentUserArgumentResolver(private val users: ReactiveCurrentUserService) :
    HandlerMethodArgumentResolver {
    override fun supportsParameter(parameter: MethodParameter) =
        parameter.parameterType == CurrentUser::class.java

    override fun resolveArgument(
        parameter: MethodParameter,
        bindingContext: BindingContext,
        exchange: ServerWebExchange,
    ): Mono<Any> =
        Mono.defer {
            val id =
                exchange.request.headers.getFirst("X-User-Id")
                    ?.toLongOrNull()?.takeIf { it > 0 }
                    ?: throw InvalidCurrentUserException()
            users.requireActive(id).map<Any> { it }
        }
}

@Configuration
internal class CurrentUserConfiguration(
    // Feign initializes HTTP codec support while creating the account client.
    @param:Lazy private val users: ReactiveCurrentUserService,
) : WebFluxConfigurer {
    override fun configureArgumentResolvers(configurer: ArgumentResolverConfigurer) {
        configurer.addCustomResolver(CurrentUserArgumentResolver(users))
    }
}
