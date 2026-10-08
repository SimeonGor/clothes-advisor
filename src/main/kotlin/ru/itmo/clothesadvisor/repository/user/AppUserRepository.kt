package ru.itmo.clothesadvisor.repository.user

import jakarta.persistence.LockModeType
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.repository.Repository
import ru.itmo.clothesadvisor.model.user.AppUser

internal interface AppUserRepository : Repository<AppUser, Long> {
    fun findById(id: Long): AppUser?
    fun findByLogin(login: String): AppUser?
    fun findAll(pageable: Pageable): Page<AppUser>

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    fun findAllByIdInOrderByIdAsc(ids: Collection<Long>): List<AppUser>

    fun save(user: AppUser): AppUser
    fun flush()
}
