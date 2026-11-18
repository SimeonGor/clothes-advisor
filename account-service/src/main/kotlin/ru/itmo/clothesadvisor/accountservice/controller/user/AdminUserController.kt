package ru.itmo.clothesadvisor.accountservice.controller.user

import java.net.URI
import org.springframework.http.ResponseEntity
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.RestController
import ru.itmo.clothesadvisor.accountservice.controller.pageRequest
import ru.itmo.clothesadvisor.accountservice.dto.user.CreateUserRequest
import ru.itmo.clothesadvisor.accountservice.dto.user.SetUserPasswordRequest
import ru.itmo.clothesadvisor.accountservice.dto.user.UpdateUserRoleAndStatusRequest
import ru.itmo.clothesadvisor.accountservice.service.user.AdminUserService
import ru.itmo.clothesadvisor.accountservice.service.user.UserManagementService

@RestController
internal class AdminUserController(
    private val users: AdminUserService,
    private val management: UserManagementService,
) : AdminUserApi {
    override fun create(actor: Jwt, request: CreateUserRequest) = management.create(actor.subject!!.toLong(), request).map {
        ResponseEntity.created(URI("/api/admin/users/${it.id}")).body(it)
    }

    override fun setPassword(actor: Jwt, id: Long, request: SetUserPasswordRequest) = management.setPassword(actor.subject!!.toLong(), id, request)

    override fun list(page: Int, size: Int) = users.list(pageRequest(page, size)).map {
        ResponseEntity.ok().header("X-Total-Count", it.totalElements.toString()).body(it.content)
    }

    override fun get(id: Long) = users.get(id)

    override fun update(actor: Jwt, id: Long, request: UpdateUserRoleAndStatusRequest) = users.update(
        actor.subject!!.toLong(),
        id,
        request,
    )
}
