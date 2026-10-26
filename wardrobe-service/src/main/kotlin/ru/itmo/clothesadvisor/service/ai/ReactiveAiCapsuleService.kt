package ru.itmo.clothesadvisor.service.ai

import org.springframework.stereotype.Service
import reactor.core.publisher.Mono
import ru.itmo.clothesadvisor.dto.ai.CreateAiCapsuleRequest
import ru.itmo.clothesadvisor.dto.capsule.CapsuleResponse
import ru.itmo.clothesadvisor.service.blockingCall

@Service
internal class ReactiveAiCapsuleService(private val capsules: AiCapsuleService) {
    fun create(ownerId: Long, request: CreateAiCapsuleRequest, key: String? = null): Mono<CapsuleResponse> =
        blockingCall { capsules.create(ownerId, request, key) }
}
