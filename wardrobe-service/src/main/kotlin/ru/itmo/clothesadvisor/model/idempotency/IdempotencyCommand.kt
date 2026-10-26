package ru.itmo.clothesadvisor.model.idempotency

import java.time.Instant
import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table

@Table("idempotency_command")
internal data class IdempotencyCommand(
    @Id val id: Long,
    val actorId: Long,
    val operation: String,
    val commandKey: String,
    val fingerprint: String,
    val state: String,
    val responseJson: String?,
    val responseStatus: Int?,
    val createdAt: Instant,
    val modifiedAt: Instant,
)

internal enum class IdempotencyOperation {
    CREATE_WARDROBE_ITEM, ADD_WARDROBE_PHOTOS, CREATE_OUTFIT, CREATE_AI_OUTFIT,
    CREATE_AI_OUTFIT_REPLACEMENT, CREATE_STYLIST_REQUEST, ANSWER_STYLIST_REQUEST, CREATE_CALENDAR_ENTRY,
}
