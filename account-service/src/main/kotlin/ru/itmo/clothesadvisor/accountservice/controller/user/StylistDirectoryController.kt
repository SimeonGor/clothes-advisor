package ru.itmo.clothesadvisor.accountservice.controller.user

import org.springframework.web.bind.annotation.RestController
import ru.itmo.clothesadvisor.accountservice.controller.pageRequest
import ru.itmo.clothesadvisor.accountservice.service.user.StylistDirectoryService

@RestController
internal class StylistDirectoryController(private val stylists: StylistDirectoryService) : StylistDirectoryApi {
    override fun list(
        page: Int,
        size: Int,
    ) = stylists.list(pageRequest(page, size))
}
