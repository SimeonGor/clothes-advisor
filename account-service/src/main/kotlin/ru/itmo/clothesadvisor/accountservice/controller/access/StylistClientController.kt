package ru.itmo.clothesadvisor.accountservice.controller.access

import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.RestController
import ru.itmo.clothesadvisor.accountservice.controller.pageRequest
import ru.itmo.clothesadvisor.accountservice.service.access.AccessGrantService

@RestController
internal class StylistClientController(private val access: AccessGrantService) : StylistClientApi {
    override fun list(actor: Jwt, page: Int, size: Int) = access.listClients(actor.subject!!.toLong(), pageRequest(page, size))
}
