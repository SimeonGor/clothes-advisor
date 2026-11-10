package ru.itmo.clothesadvisor.repository.messaging

import java.util.UUID
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import ru.itmo.clothesadvisor.observability.DurableTrace
import tools.jackson.databind.ObjectMapper

@Repository
internal class WardrobeEventOutbox(private val jdbc: JdbcTemplate, private val mapper: ObjectMapper) {
    fun stylistRequest(type: String, id: Long, ownerId: Long, stylistId: Long) =
        insert(type, id, mapOf("requestId" to id, "ownerId" to ownerId, "stylistId" to stylistId))

    fun outfit(type: String, id: Long, ownerId: Long) =
        insert(type, id, mapOf("outfitId" to id, "ownerId" to ownerId))

    private fun insert(type: String, id: Long, payload: Map<String, Long>) {
        jdbc.update(
            """INSERT INTO outbox_event (event_id, type, schema_version, aggregate_id, payload, traceparent)
               VALUES (?, ?, 1, ?, CAST(? AS jsonb), ?)""",
            UUID.randomUUID(), type, id.toString(), mapper.writeValueAsString(payload), DurableTrace.capture(),
        )
    }
}
