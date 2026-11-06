package ru.itmo.clothesadvisor.model.calendar

import java.time.Instant
import java.time.LocalDate
import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table

internal enum class CalendarEntryStatus { PLANNED, WORN }

@Table("calendar_entry")
internal data class CalendarEntry(
    @Id val id: Long,
    val ownerId: Long,
    val calendarDate: LocalDate,
    val outfitId: Long,
    val status: CalendarEntryStatus,
    val version: Long,
    val createdAt: Instant,
    val modifiedAt: Instant,
)
