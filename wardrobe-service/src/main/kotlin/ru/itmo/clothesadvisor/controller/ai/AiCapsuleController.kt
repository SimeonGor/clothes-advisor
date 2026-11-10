package ru.itmo.clothesadvisor.controller.ai

import java.net.URI
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RestController
import ru.itmo.clothesadvisor.dto.ai.CreateAiCapsuleRequest
import ru.itmo.clothesadvisor.dto.user.CurrentUser
import ru.itmo.clothesadvisor.service.ai.ReactiveAiCapsuleService

@RestController
internal class AiCapsuleController(private val capsules: ReactiveAiCapsuleService) : AiCapsuleApi {
    override fun create(
        user: CurrentUser,
        request: CreateAiCapsuleRequest,
        key: String?,
    ) = capsules.create(user.id, request, key).map {
        ResponseEntity.created(URI("/api/me/capsules/${it.id}")).body(it)
    }
}
