package ru.itmo.clothesadvisor.repository.access

import org.springframework.data.domain.Pageable
import org.springframework.data.jdbc.repository.query.Modifying
import org.springframework.data.jdbc.repository.query.Query
import org.springframework.data.repository.Repository
import ru.itmo.clothesadvisor.model.access.AccessGrant
import ru.itmo.clothesadvisor.model.access.AccessGrantId
import ru.itmo.clothesadvisor.model.user.AppUser

internal interface AccessGrantRepository : Repository<AccessGrant, AccessGrantId> {
    @Modifying
    @Query("""
        INSERT INTO access_grant (owner_id, stylist_id) VALUES (:ownerId, :stylistId)
        ON CONFLICT (owner_id, stylist_id) DO NOTHING
    """)
    fun grant(ownerId: Long, stylistId: Long): Int

    @Modifying
    @Query("DELETE FROM access_grant WHERE owner_id = :ownerId AND stylist_id = :stylistId")
    fun revoke(ownerId: Long, stylistId: Long): Int

    fun findRecipients(ownerId: Long, pageable: Pageable): List<AppUser> =
        findRecipientRows(ownerId, pageable.pageSize, pageable.offset)

    @Query("""
        SELECT s.* FROM access_grant g JOIN app_user s ON s.id = g.stylist_id
        WHERE g.owner_id = :ownerId ORDER BY s.id LIMIT :limit OFFSET :offset
    """)
    fun findRecipientRows(ownerId: Long, limit: Int, offset: Long): List<AppUser>

    fun findClients(stylistId: Long, pageable: Pageable): List<AppUser> =
        findClientRows(stylistId, pageable.pageSize, pageable.offset)

    @Query("""
        SELECT o.* FROM access_grant g
        JOIN app_user o ON o.id = g.owner_id JOIN app_user s ON s.id = g.stylist_id
        WHERE g.stylist_id = :stylistId AND o.role = 'USER' AND o.status = 'ACTIVE'
            AND s.role = 'STYLIST' AND s.status = 'ACTIVE'
        ORDER BY o.id LIMIT :limit OFFSET :offset
    """)
    fun findClientRows(stylistId: Long, limit: Int, offset: Long): List<AppUser>

    @Query("""
        SELECT EXISTS (
            SELECT 1 FROM access_grant g
            JOIN app_user o ON o.id = g.owner_id JOIN app_user s ON s.id = g.stylist_id
            WHERE g.owner_id = :ownerId AND g.stylist_id = :stylistId
                AND o.role = 'USER' AND o.status = 'ACTIVE'
                AND s.role = 'STYLIST' AND s.status = 'ACTIVE'
        )
    """)
    fun hasActiveAccess(stylistId: Long, ownerId: Long): Boolean
}
