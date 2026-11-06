package ru.itmo.clothesadvisor.service.preferences

import org.springframework.stereotype.Service
import ru.itmo.clothesadvisor.dto.preferences.PutStylePreferencesRequest
import ru.itmo.clothesadvisor.dto.user.CurrentUser
import ru.itmo.clothesadvisor.service.blockingCall

@Service
internal class ReactiveStylePreferencesService(private val preferences: StylePreferencesService) {
    fun get(user: CurrentUser) = blockingCall { preferences.get(user) }

    fun put(user: CurrentUser, request: PutStylePreferencesRequest) =
        blockingCall { preferences.put(user, request) }

    fun getForClient(stylistId: Long, ownerId: Long) =
        blockingCall { preferences.getForClient(stylistId, ownerId) }
}
