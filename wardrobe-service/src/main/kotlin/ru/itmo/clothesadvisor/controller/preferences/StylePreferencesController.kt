package ru.itmo.clothesadvisor.controller.preferences

import org.springframework.web.bind.annotation.RestController
import ru.itmo.clothesadvisor.dto.preferences.PutStylePreferencesRequest
import ru.itmo.clothesadvisor.dto.user.CurrentUser
import ru.itmo.clothesadvisor.service.preferences.ReactiveStylePreferencesService

@RestController
internal class StylePreferencesController(private val preferences: ReactiveStylePreferencesService) : StylePreferencesApi {
    override fun get(user: CurrentUser) = preferences.get(user)

    override fun put(
        user: CurrentUser,
        request: PutStylePreferencesRequest,
    ) = preferences.put(user, request)
}
