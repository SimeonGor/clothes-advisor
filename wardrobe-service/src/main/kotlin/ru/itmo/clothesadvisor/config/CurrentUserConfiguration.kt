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
import reactor.core.scheduler.Schedulers
import ru.itmo.clothesadvisor.dto.user.CurrentUser
import ru.itmo.clothesadvisor.service.user.CurrentUserService

internal class InvalidCurrentUserException : RuntimeException()

internal class CurrentUserArgumentResolver(private val users: CurrentUserService) :
    HandlerMethodArgumentResolver {
    override fun supportsParameter(parameter: MethodParameter) =
        parameter.parameterType == CurrentUser::class.java

    override fun resolveArgument(
        parameter: MethodParameter,
        bindingContext: BindingContext,
        exchange: ServerWebExchange,
    ): Mono<Any> =
        Mono.fromCallable<Any> {
                val id =
                    exchange.request.headers.getFirst("X-User-Id")
                        ?.toLongOrNull()?.takeIf { it > 0 }
                        ?: throw InvalidCurrentUserException()
                users.requireActive(id)
            }
            .subscribeOn(Schedulers.boundedElastic())
}

@Configuration
internal class CurrentUserConfiguration(
    // Feign initializes HTTP codec support while creating the account client.
    @param:Lazy private val users: CurrentUserService,
) : WebFluxConfigurer {
    override fun configureArgumentResolvers(configurer: ArgumentResolverConfigurer) {
        configurer.addCustomResolver(CurrentUserArgumentResolver(users))
    }
}
