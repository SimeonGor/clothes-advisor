package ru.itmo.clothesadvisor.notification

import java.time.Instant
import java.util.UUID
import reactor.core.publisher.Mono

internal data class NotificationResource(val type: String, val id: String)
internal data class Notification(
    val id: Long,
    val type: String,
    val message: String,
    val resource: NotificationResource?,
    val createdAt: Instant,
    val readAt: Instant?,
)
internal data class NewNotification(
    val sourceEvent: UUID,
    val recipient: Long,
    val type: String,
    val message: String,
    val resource: NotificationResource?,
    val createdAt: Instant,
)
internal data class NotificationPage(val total: Long, val items: List<Notification>)

internal interface Notifications {
    fun save(notification: NewNotification): Mono<Void>
    fun reject(topic: String, partition: Int, offset: Long, reason: RejectionReason): Mono<Void>
    fun page(recipient: Long, offset: Long, size: Int): Mono<NotificationPage>
    fun unread(recipient: Long): Mono<Long>
    fun markRead(recipient: Long, id: Long): Mono<Boolean>
}
