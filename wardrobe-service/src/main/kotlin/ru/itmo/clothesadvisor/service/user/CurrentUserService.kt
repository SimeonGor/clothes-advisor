package ru.itmo.clothesadvisor.service.user

import org.springframework.stereotype.Service
import ru.itmo.clothesadvisor.dto.user.CurrentUser
import ru.itmo.clothesadvisor.model.AccessDeniedException
import ru.itmo.clothesadvisor.model.EntityNotFoundException
import ru.itmo.clothesadvisor.model.user.UserStatus
import ru.itmo.clothesadvisor.repository.user.AppUserRepository

@Service
internal class CurrentUserService(private val users: AppUserRepository) {
    fun requireActive(id: Long): CurrentUser {
        val user = users.findById(id) ?: throw EntityNotFoundException()
        if (user.status == UserStatus.BLOCKED) {
            throw AccessDeniedException()
        }
        return CurrentUser(id, user.login, user.role)
    }
}
