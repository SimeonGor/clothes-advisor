package ru.itmo.clothesadvisor.service.stylistrequest

import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Service
import ru.itmo.clothesadvisor.dto.stylistrequest.AnswerStylistRequest
import ru.itmo.clothesadvisor.dto.stylistrequest.CancelStylistRequest
import ru.itmo.clothesadvisor.dto.stylistrequest.CreateStylistRequest
import ru.itmo.clothesadvisor.dto.user.CurrentUser
import ru.itmo.clothesadvisor.service.blockingCall

@Service
internal class ReactiveStylistRequestService(private val requests: StylistRequestService) {
    fun create(user: CurrentUser, request: CreateStylistRequest, key: String?) =
        blockingCall { requests.create(user, request, key) }

    fun get(user: CurrentUser, id: Long) = blockingCall { requests.get(user, id) }

    fun list(user: CurrentUser, pageable: Pageable) = blockingCall { requests.list(user, pageable) }

    fun cancel(user: CurrentUser, id: Long, request: CancelStylistRequest) =
        blockingCall { requests.cancel(user, id, request) }

    fun getForClient(user: CurrentUser, ownerId: Long, id: Long) =
        blockingCall { requests.getForClient(user, ownerId, id) }

    fun listForClient(user: CurrentUser, ownerId: Long, pageable: Pageable) =
        blockingCall { requests.listForClient(user, ownerId, pageable) }

    fun answer(user: CurrentUser, ownerId: Long, id: Long, request: AnswerStylistRequest, key: String?) =
        blockingCall { requests.answer(user, ownerId, id, request, key) }
}
