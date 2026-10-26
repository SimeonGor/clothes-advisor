package ru.itmo.clothesadvisor.service.capsule

import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Service
import ru.itmo.clothesadvisor.dto.capsule.CreateCapsuleRequest
import ru.itmo.clothesadvisor.dto.user.CurrentUser
import ru.itmo.clothesadvisor.service.blockingCall

@Service
internal class ReactiveCapsuleService(private val capsules: CapsuleService) {
    fun create(user: CurrentUser, request: CreateCapsuleRequest, key: String?) =
        blockingCall { capsules.create(user, request, key) }

    fun list(user: CurrentUser, pageable: Pageable) = blockingCall { capsules.list(user, pageable) }

    fun get(user: CurrentUser, id: Long) = blockingCall { capsules.get(user, id) }

    fun delete(user: CurrentUser, id: Long) = blockingCall { capsules.delete(user, id) }
}
