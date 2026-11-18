package ru.itmo.clothesadvisor.controller.ai

import java.net.URI
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RestController
import ru.itmo.clothesadvisor.dto.ai.CreateAiOutfitRequest
import ru.itmo.clothesadvisor.dto.ai.CreateAiOutfitReplacementRequest
import ru.itmo.clothesadvisor.dto.user.CurrentUser
import ru.itmo.clothesadvisor.service.ai.ReactiveAiOutfitService

@RestController
internal class AiOutfitController(private val outfits: ReactiveAiOutfitService) : AiOutfitApi {
    override fun replace(
        user: CurrentUser,
        id: Long,
        request: CreateAiOutfitReplacementRequest,
        key: String?,
    ) = outfits.replace(user.id, id, request, key).map {
        ResponseEntity.created(URI("/api/outfits/${it.id}")).body(it)
    }

    override fun create(
        user: CurrentUser,
        request: CreateAiOutfitRequest,
        key: String?,
    ) =
        outfits.create(user.id, request, key).map {
            ResponseEntity.created(URI("/api/outfits/${it.id}")).body(it)
        }
}
