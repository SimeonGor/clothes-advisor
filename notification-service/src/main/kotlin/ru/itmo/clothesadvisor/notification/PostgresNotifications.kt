package ru.itmo.clothesadvisor.notification

import io.micrometer.core.instrument.MeterRegistry
import io.r2dbc.spi.Row
import java.time.OffsetDateTime
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Repository
import reactor.core.publisher.Mono

@Repository
internal class PostgresNotifications(private val db: DatabaseClient, meters: MeterRegistry) : Notifications {
    private val duplicates = meters.counter("notification.duplicates")

    override fun save(notification: NewNotification): Mono<Void> {
        val query = db.sql("""
            INSERT INTO notification (source_event_id, recipient_id, type, message, resource_type, resource_id, created_at)
            VALUES (:event, :recipient, :type, :message, :resourceType, :resourceId, :created)
            ON CONFLICT (source_event_id, recipient_id) DO NOTHING
        """).bind("event", notification.sourceEvent).bind("recipient", notification.recipient)
            .bind("type", notification.type).bind("message", notification.message)
            .bind("created", notification.createdAt)
        val resource = notification.resource
        val insert = if (resource == null) {
            query.bindNull("resourceType", String::class.java).bindNull("resourceId", String::class.java)
        } else {
            query.bind("resourceType", resource.type).bind("resourceId", resource.id)
        }
        // A single autocommit statement stores the effect and its delivery identity.
        return insert.fetch().rowsUpdated().doOnNext { if (it == 0L) duplicates.increment() }.then()
    }

    override fun reject(topic: String, partition: Int, offset: Long, reason: RejectionReason): Mono<Void> =
        db.sql("""
            INSERT INTO notification_rejected_event (topic, partition_id, record_offset, reason)
            VALUES (:topic, :partition, :offset, :reason)
            ON CONFLICT (topic, partition_id, record_offset) DO NOTHING
        """).bind("topic", topic).bind("partition", partition).bind("offset", offset).bind("reason", reason.name)
            .fetch().rowsUpdated().then()

    override fun page(recipient: Long, offset: Long, size: Int): Mono<NotificationPage> =
        db.sql("""
            SELECT total.count, page.* FROM
              (SELECT count(*) AS count FROM notification WHERE recipient_id = :recipient) total
            LEFT JOIN (
              SELECT id, type, message, resource_type, resource_id, created_at, read_at
                FROM notification WHERE recipient_id = :recipient
                ORDER BY created_at DESC, id DESC LIMIT :size OFFSET :offset
            ) page ON true
            ORDER BY page.created_at DESC, page.id DESC
        """).bind("recipient", recipient).bind("size", size).bind("offset", offset)
            .map { row, _ -> row.get("count", Long::class.javaObjectType)!! to notification(row) }
            .all().collectList().map { rows -> NotificationPage(rows.first().first, rows.mapNotNull { it.second }) }

    private fun notification(row: Row): Notification? {
        val id = row.get("id", Long::class.javaObjectType) ?: return null
        return Notification(id, row.get("type", String::class.java)!!, row.get("message", String::class.java)!!,
            row.get("resource_type", String::class.java)?.let { NotificationResource(it, row.get("resource_id", String::class.java)!!) },
            row.get("created_at", OffsetDateTime::class.java)!!.toInstant(),
            row.get("read_at", OffsetDateTime::class.java)?.toInstant())
    }

    override fun unread(recipient: Long): Mono<Long> = db.sql("""
        SELECT count(*) FROM notification WHERE recipient_id = :recipient AND read_at IS NULL
    """).bind("recipient", recipient).map { row, _ -> row.get(0, Long::class.javaObjectType)!! }.one()

    override fun markRead(recipient: Long, id: Long): Mono<Boolean> = db.sql("""
        UPDATE notification SET read_at = COALESCE(read_at, CURRENT_TIMESTAMP)
        WHERE recipient_id = :recipient AND id = :id
    """).bind("recipient", recipient).bind("id", id).fetch().rowsUpdated().map { it == 1L }
}
