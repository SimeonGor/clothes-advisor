package ru.itmo.clothesadvisor.controller.wardrobe

import java.net.URI
import org.springframework.http.ResponseEntity
import org.springframework.http.codec.multipart.Part
import org.springframework.web.bind.annotation.RestController
import reactor.core.publisher.Mono
import ru.itmo.clothesadvisor.controller.toPageRequest
import ru.itmo.clothesadvisor.dto.user.CurrentUser
import ru.itmo.clothesadvisor.dto.wardrobe.CreateWardrobeItemRequest
import ru.itmo.clothesadvisor.dto.wardrobe.UpdateWardrobeItemRequest
import ru.itmo.clothesadvisor.dto.wardrobe.WardrobeItemResponse
import ru.itmo.clothesadvisor.service.wardrobe.ReactiveWardrobeService

@RestController
internal class WardrobeItemController(
    private val items: ReactiveWardrobeService,
) : WardrobeItemApi {
    override fun create(
        user: CurrentUser,
        request: CreateWardrobeItemRequest,
        key: String?,
    ): Mono<ResponseEntity<WardrobeItemResponse>> =
        items.create(user.id, request, key).map { created ->
            ResponseEntity.created(URI("/api/wardrobe/items/${created.id}")).body(created)
        }

    override fun createWithPhotos(
        user: CurrentUser,
        request: CreateWardrobeItemRequest,
        files: List<@JvmSuppressWildcards Part>,
        key: String?,
    ): Mono<ResponseEntity<WardrobeItemResponse>> =
        readPhotoParts(files).flatMap { bytes ->
            items.createWithPhotos(user.id, request, bytes, key).map { created ->
                ResponseEntity.created(URI("/api/wardrobe/items/${created.id}")).body(created)
            }
        }

    override fun list(
        user: CurrentUser,
        page: Int,
        size: Int,
    ): Mono<List<WardrobeItemResponse>> =
        items.list(user.id, toPageRequest(page, size))

    override fun get(
        user: CurrentUser,
        id: Long,
    ): Mono<WardrobeItemResponse> =
        items.get(user.id, id)

    override fun update(
        user: CurrentUser,
        id: Long,
        request: UpdateWardrobeItemRequest,
    ): Mono<WardrobeItemResponse> =
        items.update(user.id, id, request)

    override fun delete(
        user: CurrentUser,
        id: Long,
        version: Long,
    ) =
        items.delete(user.id, id, version)
}
