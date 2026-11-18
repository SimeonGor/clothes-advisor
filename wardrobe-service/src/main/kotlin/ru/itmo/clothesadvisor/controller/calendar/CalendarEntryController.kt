package ru.itmo.clothesadvisor.controller.calendar

import java.net.URI
import java.time.LocalDate
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RestController
import ru.itmo.clothesadvisor.controller.toPageRequest
import ru.itmo.clothesadvisor.dto.calendar.CreateCalendarEntryRequest
import ru.itmo.clothesadvisor.dto.calendar.UpdateCalendarEntryRequest
import ru.itmo.clothesadvisor.dto.user.CurrentUser
import ru.itmo.clothesadvisor.service.calendar.ReactiveCalendarEntryService

@RestController
internal class CalendarEntryController(private val entries: ReactiveCalendarEntryService) : CalendarEntryApi {
    override fun create(
        user: CurrentUser,
        request: CreateCalendarEntryRequest,
        key: String?,
    ) = entries.create(user, request, key).map {
        ResponseEntity.created(URI("/api/me/calendar/${it.id}")).body(it)
    }

    override fun list(
        user: CurrentUser,
        from: LocalDate,
        to: LocalDate,
        page: Int,
        size: Int,
    ) = entries.list(user, from, to, toPageRequest(page, size)).map {
        ResponseEntity.ok().header("X-Total-Count", it.totalElements.toString()).body(it.content)
    }

    override fun get(user: CurrentUser, id: Long) = entries.get(user, id)

    override fun update(
        user: CurrentUser,
        id: Long,
        request: UpdateCalendarEntryRequest,
    ) = entries.update(user, id, request)

    override fun delete(
        user: CurrentUser,
        id: Long,
        version: Long,
    ) = entries.delete(user, id, version).thenReturn(ResponseEntity.noContent().build<Void>())
}
