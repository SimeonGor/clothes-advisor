package ru.itmo.clothesadvisor.repository.access

import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.Repository
import ru.itmo.clothesadvisor.model.access.AccessGrant
import ru.itmo.clothesadvisor.model.access.AccessGrantId
import ru.itmo.clothesadvisor.model.user.AppUser

internal interface AccessGrantRepository : Repository<AccessGrant, AccessGrantId> {
    @Modifying
    @Query(value = """
        INSERT INTO access_grant (owner_id, stylist_id) VALUES (:ownerId, :stylistId)
        ON CONFLICT (owner_id, stylist_id) DO NOTHING
    """, nativeQuery = true)
    fun grant(ownerId: Long, stylistId: Long): Int

    @Modifying
    @Query(value = "DELETE FROM access_grant WHERE owner_id = :ownerId AND stylist_id = :stylistId", nativeQuery = true)
    fun revoke(ownerId: Long, stylistId: Long): Int

    @Query(value = """
        SELECT s.* FROM access_grant g JOIN app_user s ON s.id = g.stylist_id
        WHERE g.owner_id = :ownerId ORDER BY s.id
    """, nativeQuery = true)
    fun findRecipients(ownerId: Long, pageable: Pageable): List<AppUser>

    @Query(value = """
        SELECT o.* FROM access_grant g
        JOIN app_user o ON o.id = g.owner_id JOIN app_user s ON s.id = g.stylist_id
        WHERE g.stylist_id = :stylistId AND o.role = 'USER' AND o.status = 'ACTIVE'
            AND s.role = 'STYLIST' AND s.status = 'ACTIVE'
        ORDER BY o.id
    """, nativeQuery = true)
    fun findClients(stylistId: Long, pageable: Pageable): List<AppUser>

    @Query(value = """
        SELECT EXISTS (
            SELECT 1 FROM access_grant g
            JOIN app_user o ON o.id = g.owner_id JOIN app_user s ON s.id = g.stylist_id
            WHERE g.owner_id = :ownerId AND g.stylist_id = :stylistId
                AND o.role = 'USER' AND o.status = 'ACTIVE'
                AND s.role = 'STYLIST' AND s.status = 'ACTIVE'
        )
    """, nativeQuery = true)
    fun hasActiveAccess(stylistId: Long, ownerId: Long): Boolean
}
