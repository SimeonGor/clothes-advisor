package ru.itmo.clothesadvisor.dto.calendar

import jakarta.validation.constraints.Positive
import java.time.Instant
import java.time.LocalDate
import ru.itmo.clothesadvisor.model.calendar.CalendarEntryStatus

internal data class CreateCalendarEntryRequest(
    val date: LocalDate,
    @field:Positive val outfitId: Long,
    val status: CalendarEntryStatus,
)

internal data class UpdateCalendarEntryRequest(
    @field:Positive val version: Long,
    val date: LocalDate,
    @field:Positive val outfitId: Long,
    val status: CalendarEntryStatus,
)

internal data class DeleteCalendarEntryRequest(@field:Positive val version: Long)

internal data class CalendarEntryResponse(
    val id: Long,
    val ownerId: Long,
    val date: LocalDate,
    val outfitId: Long,
    val status: CalendarEntryStatus,
    val version: Long,
    val createdAt: Instant,
    val modifiedAt: Instant,
)
