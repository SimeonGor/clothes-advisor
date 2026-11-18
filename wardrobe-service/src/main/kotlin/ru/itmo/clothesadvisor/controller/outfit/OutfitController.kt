package ru.itmo.clothesadvisor.controller.outfit

import java.net.URI
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RestController
import ru.itmo.clothesadvisor.controller.toPageRequest
import ru.itmo.clothesadvisor.dto.outfit.CreateOutfitRequest
import ru.itmo.clothesadvisor.dto.user.CurrentUser
import ru.itmo.clothesadvisor.service.outfit.ReactiveOutfitService

@RestController
internal class OutfitController(
    private val outfits: ReactiveOutfitService,
) : OutfitApi {
    override fun create(
        user: CurrentUser,
        request: CreateOutfitRequest,
        key: String?,
    ) =
        outfits.create(user.id, request, key).map {
            ResponseEntity.created(URI("/api/outfits/${it.id}")).body(it)
        }

    override fun list(
        user: CurrentUser,
        page: Int,
        size: Int,
    ) =
        outfits.list(user.id, toPageRequest(page, size)).map {
            ResponseEntity.ok()
                .header("X-Total-Count", it.totalElements.toString())
                .body(it.content)
        }

    override fun get(user: CurrentUser, id: Long) =
        outfits.get(user.id, id)

    override fun history(
        user: CurrentUser,
        id: Long,
        page: Int,
        size: Int,
    ) =
        outfits.ratingHistory(user.id, id, toPageRequest(page, size)).map {
            ResponseEntity.ok()
                .header("X-Total-Count", it.totalElements.toString())
                .body(it.content)
        }

    override fun delete(user: CurrentUser, id: Long) =
        outfits.delete(user.id, id)
}
