package ru.itmo.clothesadvisor.controller.outfit

import java.net.URI
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RestController
import reactor.core.publisher.Mono
import ru.itmo.clothesadvisor.controller.toPageRequest
import ru.itmo.clothesadvisor.dto.outfit.CreateOutfitRequest
import ru.itmo.clothesadvisor.dto.rating.CreateRatingRequest
import ru.itmo.clothesadvisor.dto.rating.UpdateRatingRequest
import ru.itmo.clothesadvisor.dto.user.CurrentUser
import ru.itmo.clothesadvisor.service.outfit.ReactiveOutfitService

@RestController
internal class StylistOutfitController(
    private val outfits: ReactiveOutfitService,
) : StylistOutfitApi {
    override fun create(
        user: CurrentUser,
        ownerId: Long,
        request: CreateOutfitRequest,
        key: String?,
    ) =
        outfits.createForClient(user.id, ownerId, request, key).map {
            ResponseEntity.created(URI("/api/stylist/clients/$ownerId/outfits/${it.outfit.id}"))
                .body(it)
        }

    override fun list(
        user: CurrentUser,
        ownerId: Long,
        page: Int,
        size: Int,
    ) =
        outfits.listForClient(user.id, ownerId, toPageRequest(page, size)).map {
            ResponseEntity.ok()
                .header("X-Total-Count", it.totalElements.toString())
                .body(it.content)
        }

    override fun get(
        user: CurrentUser,
        ownerId: Long,
        id: Long,
    ) =
        outfits.getForClient(user.id, ownerId, id)

    override fun createRating(
        user: CurrentUser,
        ownerId: Long,
        id: Long,
        request: CreateRatingRequest,
    ) =
        outfits.createRating(user.id, ownerId, id, request)
            .map { ResponseEntity.status(201).body(it) }

    override fun updateRating(
        user: CurrentUser,
        ownerId: Long,
        id: Long,
        request: UpdateRatingRequest,
    ) =
        outfits.updateRating(user.id, ownerId, id, request)

    override fun withdrawRating(
        user: CurrentUser,
        ownerId: Long,
        id: Long,
        version: Long,
    ): Mono<ResponseEntity<Void>> =
        outfits.withdrawRating(user.id, ownerId, id, version)
            .thenReturn(ResponseEntity.noContent().build<Void>())

    override fun history(
        user: CurrentUser,
        ownerId: Long,
        id: Long,
        page: Int,
        size: Int,
    ) =
        outfits.ratingHistoryForClient(user.id, ownerId, id, toPageRequest(page, size)).map {
            ResponseEntity.ok()
                .header("X-Total-Count", it.totalElements.toString())
                .body(it.content)
        }
}
