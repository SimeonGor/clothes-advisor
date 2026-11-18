package ru.itmo.clothesadvisor.controller.outfit

import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RestController
import ru.itmo.clothesadvisor.controller.toPageRequest
import ru.itmo.clothesadvisor.dto.user.CurrentUser
import ru.itmo.clothesadvisor.service.outfit.ReactiveAdminOutfitService

@RestController
internal class AdminOutfitController(private val outfits: ReactiveAdminOutfitService) : AdminOutfitApi {
    override fun list(
        user: CurrentUser,
        ownerId: Long,
        page: Int,
        size: Int,
    ) =
        outfits.list(user.id, ownerId, toPageRequest(page, size)).map {
            ResponseEntity.ok()
                .header("X-Total-Count", it.totalElements.toString())
                .body(it.content)
        }

    override fun get(
        user: CurrentUser,
        ownerId: Long,
        id: Long,
    ) =
        outfits.get(user.id, ownerId, id)

    override fun delete(
        user: CurrentUser,
        ownerId: Long,
        id: Long,
    ) =
        outfits.delete(user.id, ownerId, id)
}
