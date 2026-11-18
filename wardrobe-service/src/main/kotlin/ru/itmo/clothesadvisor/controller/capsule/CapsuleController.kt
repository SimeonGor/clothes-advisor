package ru.itmo.clothesadvisor.controller.capsule

import java.net.URI
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RestController
import ru.itmo.clothesadvisor.controller.toPageRequest
import ru.itmo.clothesadvisor.dto.capsule.CreateCapsuleRequest
import ru.itmo.clothesadvisor.dto.user.CurrentUser
import ru.itmo.clothesadvisor.service.capsule.ReactiveCapsuleService

@RestController
internal class CapsuleController(private val capsules: ReactiveCapsuleService) : CapsuleApi {
    override fun create(
        user: CurrentUser,
        request: CreateCapsuleRequest,
        key: String?,
    ) = capsules.create(user, request, key).map {
        ResponseEntity.created(URI("/api/me/capsules/${it.id}")).body(it)
    }

    override fun list(
        user: CurrentUser,
        page: Int,
        size: Int,
    ) = capsules.list(user, toPageRequest(page, size)).map {
        ResponseEntity.ok().header("X-Total-Count", it.totalElements.toString()).body(it.content)
    }

    override fun get(user: CurrentUser, id: Long) = capsules.get(user, id)

    override fun delete(user: CurrentUser, id: Long) =
        capsules.delete(user, id).thenReturn(ResponseEntity.noContent().build<Void>())
}
