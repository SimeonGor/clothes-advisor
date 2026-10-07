package ru.itmo.clothesadvisor.repository.user

import org.springframework.data.repository.Repository
import ru.itmo.clothesadvisor.model.user.AppUser

internal interface AppUserRepository : Repository<AppUser, Long> {
    fun findById(id: Long): AppUser?
    fun findByLogin(login: String): AppUser?
    fun save(user: AppUser): AppUser
    fun flush()
}
