package ru.itmo.clothesadvisor.accountservice.repository.access

import org.springframework.data.r2dbc.repository.Modifying
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.Repository
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import ru.itmo.clothesadvisor.accountservice.model.access.AccessGrant
import ru.itmo.clothesadvisor.accountservice.model.access.AccessGrantId
import ru.itmo.clothesadvisor.accountservice.model.user.AppUser

internal interface AccessGrantRepository : Repository<AccessGrant, AccessGrantId> {
    @Query(
        """
        SELECT EXISTS (
            SELECT 1 FROM access_grant g
            JOIN app_user o ON o.id = g.owner_id JOIN app_user s ON s.id = g.stylist_id
            WHERE g.owner_id = :ownerId AND g.stylist_id = :stylistId
                AND o.role = 'USER' AND o.status = 'ACTIVE'
                AND s.role = 'STYLIST' AND s.status = 'ACTIVE'
        )
        """,
    )
    fun hasActiveAccess(stylistId: Long, ownerId: Long): Mono<Boolean>

    @Modifying
    @Query(
        """
        INSERT INTO access_grant (owner_id, stylist_id) VALUES (:ownerId, :stylistId)
        ON CONFLICT (owner_id, stylist_id) DO NOTHING
        """,
    )
    fun grant(ownerId: Long, stylistId: Long): Mono<Int>

    @Modifying
    @Query("DELETE FROM access_grant WHERE owner_id = :ownerId AND stylist_id = :stylistId")
    fun revoke(ownerId: Long, stylistId: Long): Mono<Int>

    @Query(
        """
        SELECT s.* FROM access_grant g JOIN app_user s ON s.id = g.stylist_id
        WHERE g.owner_id = :ownerId ORDER BY s.id LIMIT :limit OFFSET :offset
        """,
    )
    fun findRecipients(ownerId: Long, limit: Int, offset: Int): Flux<AppUser>

    @Query(
        """
        SELECT o.* FROM access_grant g
        JOIN app_user o ON o.id = g.owner_id JOIN app_user s ON s.id = g.stylist_id
        WHERE g.stylist_id = :stylistId AND o.role = 'USER' AND o.status = 'ACTIVE'
            AND s.role = 'STYLIST' AND s.status = 'ACTIVE'
        ORDER BY o.id LIMIT :limit OFFSET :offset
        """,
    )
    fun findClients(stylistId: Long, limit: Int, offset: Int): Flux<AppUser>
}
