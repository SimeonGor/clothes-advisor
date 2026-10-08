package ru.itmo.clothesadvisor.controller.ai

import jakarta.validation.Valid
import java.net.URI
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import ru.itmo.clothesadvisor.dto.ai.CreateAiOutfitRequest
import ru.itmo.clothesadvisor.dto.auth.CurrentUser
import ru.itmo.clothesadvisor.service.ai.AiOutfitService

@RestController
@PreAuthorize("hasRole('USER')")
internal class AiOutfitController(private val outfits: AiOutfitService) {
    @PostMapping("/api/outfits/ai")
    fun create(@AuthenticationPrincipal user: CurrentUser, @Valid @RequestBody request: CreateAiOutfitRequest) =
        outfits.create(user.id, request).let { ResponseEntity.created(URI("/api/outfits/${it.id}")).body(it) }
}
