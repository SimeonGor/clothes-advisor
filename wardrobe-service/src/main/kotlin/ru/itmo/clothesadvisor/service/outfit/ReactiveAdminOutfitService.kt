package ru.itmo.clothesadvisor.service.outfit

import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Service
import reactor.core.publisher.Mono
import ru.itmo.clothesadvisor.dto.outfit.OutfitResponse
import ru.itmo.clothesadvisor.service.blockingAction
import ru.itmo.clothesadvisor.service.blockingCall

@Service
internal class ReactiveAdminOutfitService(private val outfits: AdminOutfitService) {
    fun list(adminId: Long, ownerId: Long, pageable: Pageable): Mono<Page<OutfitResponse>> =
        blockingCall { outfits.list(adminId, ownerId, pageable) }

    fun get(adminId: Long, ownerId: Long, id: Long): Mono<OutfitResponse> =
        blockingCall { outfits.get(adminId, ownerId, id) }

    fun delete(adminId: Long, ownerId: Long, id: Long): Mono<Void> =
        blockingAction { outfits.delete(adminId, ownerId, id) }
}
