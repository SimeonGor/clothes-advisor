package ru.itmo.clothesadvisor.controller.preferences

import org.springframework.web.bind.annotation.RestController
import ru.itmo.clothesadvisor.dto.user.CurrentUser
import ru.itmo.clothesadvisor.service.preferences.ReactiveStylePreferencesService

@RestController
internal class StylistStylePreferencesController(
    private val preferences: ReactiveStylePreferencesService,
) : StylistStylePreferencesApi {
    override fun get(
        user: CurrentUser,
        ownerId: Long,
    ) = preferences.getForClient(user.id, ownerId)
}
