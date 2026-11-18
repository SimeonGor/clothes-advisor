package ru.itmo.clothesadvisor.accountservice.controller.access

import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.RestController
import ru.itmo.clothesadvisor.accountservice.controller.pageRequest
import ru.itmo.clothesadvisor.accountservice.service.access.AccessGrantService

@RestController
internal class StylistAccessController(private val access: AccessGrantService) : StylistAccessApi {
    override fun grant(actor: Jwt, stylistId: Long) = access.grant(actor.subject!!.toLong(), stylistId)

    override fun revoke(actor: Jwt, stylistId: Long) = access.revoke(actor.subject!!.toLong(), stylistId)

    override fun list(actor: Jwt, page: Int, size: Int) = access.listRecipients(actor.subject!!.toLong(), pageRequest(page, size))
}
