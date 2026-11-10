package ru.itmo.clothesadvisor.controller.stylistrequest

import java.net.URI
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RestController
import ru.itmo.clothesadvisor.controller.toPageRequest
import ru.itmo.clothesadvisor.dto.stylistrequest.AnswerStylistRequest
import ru.itmo.clothesadvisor.dto.user.CurrentUser
import ru.itmo.clothesadvisor.service.stylistrequest.ReactiveStylistRequestService

@RestController
internal class StylistClientRequestController(private val requests: ReactiveStylistRequestService) : StylistClientRequestApi {
    override fun answer(
        user: CurrentUser,
        ownerId: Long,
        id: Long,
        request: AnswerStylistRequest,
        key: String?,
    ) = requests.answer(user, ownerId, id, request, key).map {
        ResponseEntity.created(URI("/api/stylist/clients/$ownerId/requests/${it.id}")).body(it)
    }

    override fun list(
        user: CurrentUser,
        ownerId: Long,
        page: Int,
        size: Int,
    ) = requests.listForClient(user, ownerId, toPageRequest(page, size)).map {
        ResponseEntity.ok().header("X-Total-Count", it.totalElements.toString()).body(it.content)
    }

    override fun get(user: CurrentUser, ownerId: Long, id: Long) =
        requests.getForClient(user, ownerId, id)
}
