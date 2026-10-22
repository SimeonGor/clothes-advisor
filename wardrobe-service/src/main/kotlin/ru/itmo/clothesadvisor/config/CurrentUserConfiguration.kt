package ru.itmo.clothesadvisor.config

import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Lazy
import org.springframework.core.MethodParameter
import org.springframework.web.bind.support.WebDataBinderFactory
import org.springframework.web.context.request.NativeWebRequest
import org.springframework.web.method.support.HandlerMethodArgumentResolver
import org.springframework.web.method.support.ModelAndViewContainer
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer
import ru.itmo.clothesadvisor.dto.user.CurrentUser
import ru.itmo.clothesadvisor.service.user.CurrentUserService

internal class InvalidCurrentUserException : RuntimeException()

internal class CurrentUserArgumentResolver(private val users: CurrentUserService) :
    HandlerMethodArgumentResolver {
    override fun supportsParameter(parameter: MethodParameter) =
        parameter.parameterType == CurrentUser::class.java

    override fun resolveArgument(
        parameter: MethodParameter,
        mavContainer: ModelAndViewContainer?,
        webRequest: NativeWebRequest,
        binderFactory: WebDataBinderFactory?,
    ): CurrentUser {
        val id =
            webRequest.getHeader("X-User-Id")?.toLongOrNull()?.takeIf { it > 0 }
                ?: throw InvalidCurrentUserException()
        return users.requireActive(id)
    }
}

@Configuration
internal class CurrentUserConfiguration(
    // Feign initializes MVC converter support while creating the account client.
    @param:Lazy private val users: CurrentUserService,
) : WebMvcConfigurer {
    override fun addArgumentResolvers(resolvers: MutableList<HandlerMethodArgumentResolver>) {
        resolvers.add(CurrentUserArgumentResolver(users))
    }
}
