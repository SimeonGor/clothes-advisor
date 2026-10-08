package ru.itmo.clothesadvisor.repository.access

import org.springframework.data.domain.Pageable
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import ru.itmo.clothesadvisor.model.user.AppUser
import ru.itmo.clothesadvisor.repository.user.mapUser

@Repository
internal class AccessGrantRepository(private val jdbc: JdbcTemplate) {
    fun grant(ownerId: Long, stylistId: Long): Int = jdbc.update("""
        INSERT INTO access_grant (owner_id, stylist_id) VALUES (?, ?)
        ON CONFLICT (owner_id, stylist_id) DO NOTHING
    """, ownerId, stylistId)

    fun revoke(ownerId: Long, stylistId: Long): Int =
        jdbc.update("DELETE FROM access_grant WHERE owner_id = ? AND stylist_id = ?", ownerId, stylistId)

    fun findRecipients(ownerId: Long, pageable: Pageable): List<AppUser> = jdbc.query("""
        SELECT s.* FROM access_grant g JOIN app_user s ON s.id = g.stylist_id
        WHERE g.owner_id = ? ORDER BY s.id LIMIT ? OFFSET ?
    """, { row, _ -> mapUser(row) }, ownerId, pageable.pageSize, pageable.offset)

    fun findClients(stylistId: Long, pageable: Pageable): List<AppUser> = jdbc.query("""
        SELECT o.* FROM access_grant g
        JOIN app_user o ON o.id = g.owner_id JOIN app_user s ON s.id = g.stylist_id
        WHERE g.stylist_id = ? AND o.role = 'USER' AND o.status = 'ACTIVE'
            AND s.role = 'STYLIST' AND s.status = 'ACTIVE'
        ORDER BY o.id LIMIT ? OFFSET ?
    """, { row, _ -> mapUser(row) }, stylistId, pageable.pageSize, pageable.offset)

    fun hasActiveAccess(stylistId: Long, ownerId: Long): Boolean = jdbc.queryForObject("""
        SELECT EXISTS (
            SELECT 1 FROM access_grant g
            JOIN app_user o ON o.id = g.owner_id JOIN app_user s ON s.id = g.stylist_id
            WHERE g.owner_id = ? AND g.stylist_id = ?
                AND o.role = 'USER' AND o.status = 'ACTIVE'
                AND s.role = 'STYLIST' AND s.status = 'ACTIVE'
        )
    """, Boolean::class.java, ownerId, stylistId)!!
}
