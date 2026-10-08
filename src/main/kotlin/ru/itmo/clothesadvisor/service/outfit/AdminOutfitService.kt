package ru.itmo.clothesadvisor.service.outfit

import ru.itmo.clothesadvisor.model.EntityNotFoundException
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import ru.itmo.clothesadvisor.dto.outfit.OutfitResponse
import ru.itmo.clothesadvisor.repository.outfit.OutfitRepository
import ru.itmo.clothesadvisor.service.access.AdminContentAccessService

@Service
internal class AdminOutfitService(
    private val access: AdminContentAccessService,
    private val outfits: OutfitService,
    private val repository: OutfitRepository,
) {
    fun list(adminId: Long, ownerId: Long, pageable: Pageable): Page<OutfitResponse> {
        access.requireAccess(adminId, ownerId)
        return outfits.list(ownerId, pageable)
    }

    fun get(adminId: Long, ownerId: Long, id: Long): OutfitResponse {
        access.requireAccess(adminId, ownerId)
        return outfits.get(ownerId, id)
    }

    @Transactional
    fun delete(adminId: Long, ownerId: Long, id: Long) {
        access.requireAccess(adminId, ownerId)
        repository.findLockedByIdAndOwnerId(id, ownerId) ?: throw EntityNotFoundException()
        access.requireAccess(adminId, ownerId)
        outfits.delete(ownerId, id)
    }
}
