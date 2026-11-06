package ru.itmo.clothesadvisor.config

import org.springframework.context.annotation.Configuration
import org.springframework.core.MethodParameter
import org.springframework.security.oauth2.core.OAuth2AuthenticationException
import org.springframework.web.reactive.BindingContext
import org.springframework.web.reactive.config.WebFluxConfigurer
import org.springframework.web.reactive.result.method.HandlerMethodArgumentResolver
import org.springframework.web.reactive.result.method.annotation.ArgumentResolverConfigurer
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Mono
import ru.itmo.clothesadvisor.dto.user.CurrentUser

internal class CurrentUserArgumentResolver :
    HandlerMethodArgumentResolver {
    override fun supportsParameter(parameter: MethodParameter) =
        parameter.parameterType == CurrentUser::class.java

    override fun resolveArgument(
        parameter: MethodParameter,
        bindingContext: BindingContext,
        exchange: ServerWebExchange,
    ): Mono<Any> =
        exchange.getPrincipal<CurrentUserJwtAuthentication>()
            .map<Any> { it.user }
            .switchIfEmpty(Mono.error(OAuth2AuthenticationException("invalid_token")))
}

@Configuration
internal class CurrentUserConfiguration : WebFluxConfigurer {
    override fun configureArgumentResolvers(configurer: ArgumentResolverConfigurer) {
        configurer.addCustomResolver(CurrentUserArgumentResolver())
    }
}
