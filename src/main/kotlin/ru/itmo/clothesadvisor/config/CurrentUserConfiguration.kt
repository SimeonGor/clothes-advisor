package ru.itmo.clothesadvisor.config

import org.springframework.context.annotation.Configuration
import org.springframework.core.MethodParameter
import org.springframework.web.bind.support.WebDataBinderFactory
import org.springframework.web.context.request.NativeWebRequest
import org.springframework.web.method.support.HandlerMethodArgumentResolver
import org.springframework.web.method.support.ModelAndViewContainer
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer
import ru.itmo.clothesadvisor.dto.user.CurrentUser
import ru.itmo.clothesadvisor.model.AccessDeniedException
import ru.itmo.clothesadvisor.model.EntityNotFoundException
import ru.itmo.clothesadvisor.model.user.UserStatus
import ru.itmo.clothesadvisor.repository.user.AppUserRepository

internal class InvalidCurrentUserException : RuntimeException()

internal class CurrentUserArgumentResolver(private val users: AppUserRepository) : HandlerMethodArgumentResolver {
    override fun supportsParameter(parameter: MethodParameter) = parameter.parameterType == CurrentUser::class.java

    override fun resolveArgument(
        parameter: MethodParameter,
        mavContainer: ModelAndViewContainer?,
        webRequest: NativeWebRequest,
        binderFactory: WebDataBinderFactory?,
    ): CurrentUser {
        val id = webRequest.getHeader("X-User-Id")?.toLongOrNull()?.takeIf { it > 0 }
            ?: throw InvalidCurrentUserException()
        val user = users.findById(id) ?: throw EntityNotFoundException()
        if (user.status == UserStatus.BLOCKED) throw AccessDeniedException()
        return CurrentUser(id, user.login, user.role)
    }
}

@Configuration
internal class CurrentUserConfiguration(private val users: AppUserRepository) : WebMvcConfigurer {
    override fun addArgumentResolvers(resolvers: MutableList<HandlerMethodArgumentResolver>) {
        resolvers.add(CurrentUserArgumentResolver(users))
    }
}
