package ru.itmo.clothesadvisor.repository.outfit

import jakarta.persistence.LockModeType
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.repository.Repository
import ru.itmo.clothesadvisor.model.outfit.Outfit

internal interface OutfitRepository : Repository<Outfit, Long> {
    fun save(outfit: Outfit): Outfit
    fun findByIdAndOwnerId(id: Long, ownerId: Long): Outfit?
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    fun findLockedByIdAndOwnerId(id: Long, ownerId: Long): Outfit?
    fun findAllByOwnerIdOrderByIdDesc(ownerId: Long, pageable: Pageable): Page<Outfit>
    fun delete(outfit: Outfit)
    fun flush()
}
