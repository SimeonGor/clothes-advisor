package ru.itmo.clothesadvisor.service.ai

import org.springframework.stereotype.Service
import reactor.core.publisher.Mono
import ru.itmo.clothesadvisor.dto.ai.CreateAiOutfitRequest
import ru.itmo.clothesadvisor.dto.outfit.OutfitResponse
import ru.itmo.clothesadvisor.service.blockingCall

@Service
internal class ReactiveAiOutfitService(private val outfits: AiOutfitService) {
    fun create(ownerId: Long, request: CreateAiOutfitRequest, key: String? = null): Mono<OutfitResponse> =
        blockingCall { outfits.create(ownerId, request, key) }
}
