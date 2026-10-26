package ru.itmo.clothesadvisor.repository.calendar

import java.time.LocalDate
import org.springframework.data.jdbc.repository.query.Modifying
import org.springframework.data.jdbc.repository.query.Query
import org.springframework.data.repository.Repository
import ru.itmo.clothesadvisor.model.calendar.CalendarEntry
import ru.itmo.clothesadvisor.model.calendar.CalendarEntryStatus

internal interface CalendarEntryRepository : Repository<CalendarEntry, Long> {
    @Query("""
        INSERT INTO calendar_entry (owner_id, calendar_date, outfit_id, status)
        VALUES (:ownerId, :date, :outfitId, :status::calendar_entry_status) RETURNING *
    """)
    fun create(ownerId: Long, date: LocalDate, outfitId: Long, status: CalendarEntryStatus): CalendarEntry

    fun findByIdAndOwnerId(id: Long, ownerId: Long): CalendarEntry?

    @Query("SELECT * FROM calendar_entry WHERE id = :id AND owner_id = :ownerId FOR UPDATE")
    fun findLocked(id: Long, ownerId: Long): CalendarEntry?

    @Query("""
        SELECT * FROM calendar_entry WHERE owner_id = :ownerId AND calendar_date BETWEEN :from AND :to
        ORDER BY calendar_date ASC, id ASC LIMIT :limit OFFSET :offset
    """)
    fun findPage(ownerId: Long, from: LocalDate, to: LocalDate, limit: Int, offset: Long): List<CalendarEntry>

    @Query("SELECT count(*) FROM calendar_entry WHERE owner_id = :ownerId AND calendar_date BETWEEN :from AND :to")
    fun countOwned(ownerId: Long, from: LocalDate, to: LocalDate): Long

    @Modifying
    @Query("""
        INSERT INTO calendar_entry_history
            (entry_id, version, owner_id, calendar_date, outfit_id, status, created_at, modified_at)
        SELECT id, version, owner_id, calendar_date, outfit_id, status, created_at, modified_at
        FROM calendar_entry WHERE id = :id AND owner_id = :ownerId AND version = :version
    """)
    fun archive(id: Long, ownerId: Long, version: Long): Int

    @Modifying
    @Query("""
        UPDATE calendar_entry SET calendar_date = :date, outfit_id = :outfitId, status = :status::calendar_entry_status,
            version = version + 1, modified_at = CURRENT_TIMESTAMP
        WHERE id = :id AND owner_id = :ownerId AND version = :version
    """)
    fun update(id: Long, ownerId: Long, version: Long, date: LocalDate, outfitId: Long, status: CalendarEntryStatus): Int

    @Modifying
    @Query("DELETE FROM calendar_entry WHERE id = :id AND owner_id = :ownerId AND version = :version")
    fun delete(id: Long, ownerId: Long, version: Long): Int
}
