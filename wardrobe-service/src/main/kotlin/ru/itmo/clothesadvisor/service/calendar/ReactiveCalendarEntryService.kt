package ru.itmo.clothesadvisor.service.calendar

import java.time.LocalDate
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Service
import ru.itmo.clothesadvisor.dto.calendar.CreateCalendarEntryRequest
import ru.itmo.clothesadvisor.dto.calendar.UpdateCalendarEntryRequest
import ru.itmo.clothesadvisor.dto.user.CurrentUser
import ru.itmo.clothesadvisor.service.blockingCall

@Service
internal class ReactiveCalendarEntryService(private val entries: CalendarEntryService) {
    fun create(user: CurrentUser, request: CreateCalendarEntryRequest, key: String?) =
        blockingCall { entries.create(user, request, key) }

    fun get(user: CurrentUser, id: Long) = blockingCall { entries.get(user, id) }

    fun list(user: CurrentUser, from: LocalDate, to: LocalDate, pageable: Pageable) =
        blockingCall { entries.list(user, from, to, pageable) }

    fun update(user: CurrentUser, id: Long, request: UpdateCalendarEntryRequest) =
        blockingCall { entries.update(user, id, request) }

    fun delete(user: CurrentUser, id: Long, version: Long) = blockingCall { entries.delete(user, id, version) }
}
