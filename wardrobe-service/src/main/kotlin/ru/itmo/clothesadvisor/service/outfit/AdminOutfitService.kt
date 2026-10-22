package ru.itmo.clothesadvisor.service.outfit

import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Service
import ru.itmo.clothesadvisor.dto.outfit.OutfitResponse
import ru.itmo.clothesadvisor.service.access.AdminContentAccessService

@Service
internal class AdminOutfitService(
    private val access: AdminContentAccessService,
    private val outfits: OutfitService,
) {
    fun list(adminId: Long, ownerId: Long, pageable: Pageable): Page<OutfitResponse> {
        access.requireAccess(adminId, ownerId)
        return outfits.list(ownerId, pageable)
    }

    fun get(adminId: Long, ownerId: Long, id: Long): OutfitResponse {
        access.requireAccess(adminId, ownerId)
        return outfits.get(ownerId, id)
    }

    fun delete(adminId: Long, ownerId: Long, id: Long) {
        access.requireAccess(adminId, ownerId)
        outfits.delete(ownerId, id)
    }
}
