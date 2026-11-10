package ru.itmo.clothesadvisor.controller.stylistrequest

import java.net.URI
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RestController
import ru.itmo.clothesadvisor.controller.toPageRequest
import ru.itmo.clothesadvisor.dto.stylistrequest.CancelStylistRequest
import ru.itmo.clothesadvisor.dto.stylistrequest.CreateStylistRequest
import ru.itmo.clothesadvisor.dto.user.CurrentUser
import ru.itmo.clothesadvisor.service.stylistrequest.ReactiveStylistRequestService

@RestController
internal class StylistRequestController(private val requests: ReactiveStylistRequestService) : StylistRequestApi {
    override fun create(
        user: CurrentUser,
        request: CreateStylistRequest,
        key: String?,
    ) = requests.create(user, request, key).map {
        ResponseEntity.created(URI("/api/me/stylist-requests/${it.id}")).body(it)
    }

    override fun list(
        user: CurrentUser,
        page: Int,
        size: Int,
    ) = requests.list(user, toPageRequest(page, size)).map {
        ResponseEntity.ok().header("X-Total-Count", it.totalElements.toString()).body(it.content)
    }

    override fun get(user: CurrentUser, id: Long) = requests.get(user, id)

    override fun cancel(
        user: CurrentUser,
        id: Long,
        request: CancelStylistRequest,
    ) = requests.cancel(user, id, request)
}
