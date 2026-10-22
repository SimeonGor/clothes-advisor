package ru.itmo.clothesadvisor.service.outfit

import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Service
import ru.itmo.clothesadvisor.client.account.AccountClient
import ru.itmo.clothesadvisor.dto.outfit.OutfitResponse

@Service
internal class AdminOutfitService(
    private val accounts: AccountClient,
    private val outfits: OutfitService,
) {
    fun list(adminId: Long, ownerId: Long, pageable: Pageable): Page<OutfitResponse> {
        accounts.requireAdminAccess(adminId, ownerId)
        return outfits.list(ownerId, pageable)
    }

    fun get(adminId: Long, ownerId: Long, id: Long): OutfitResponse {
        accounts.requireAdminAccess(adminId, ownerId)
        return outfits.get(ownerId, id)
    }

    fun delete(adminId: Long, ownerId: Long, id: Long) {
        accounts.requireAdminAccess(adminId, ownerId)
        outfits.delete(ownerId, id)
    }
}
