package ru.itmo.clothesadvisor.repository.outfit

import org.springframework.data.domain.Page
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.Pageable
import org.springframework.data.jdbc.repository.query.Modifying
import org.springframework.data.jdbc.repository.query.Query
import org.springframework.data.repository.Repository
import ru.itmo.clothesadvisor.model.outfit.Outfit
import ru.itmo.clothesadvisor.model.outfit.OutfitSource

internal interface OutfitRepository : Repository<Outfit, Long> {
    @Query(
        """
        INSERT INTO outfit (owner_id, author_id, source, name)
        VALUES (:ownerId, :authorId, :source::outfit_source, :name) RETURNING *
    """,
    )
    fun create(ownerId: Long, authorId: Long, source: OutfitSource, name: String): Outfit

    fun findByIdAndOwnerId(id: Long, ownerId: Long): Outfit?

    @Query("SELECT * FROM outfit WHERE id = :id AND owner_id = :ownerId FOR UPDATE")
    fun findLockedByIdAndOwnerId(id: Long, ownerId: Long): Outfit?

    fun findAllByOwnerIdOrderByIdDesc(ownerId: Long, pageable: Pageable): Page<Outfit> =
        PageImpl(
            findPage(ownerId, pageable.pageSize, pageable.offset),
            pageable,
            countByOwnerId(ownerId),
        )

    @Query(
        "SELECT * FROM outfit WHERE owner_id = :ownerId ORDER BY id DESC LIMIT :limit OFFSET :offset",
    )
    fun findPage(ownerId: Long, limit: Int, offset: Long): List<Outfit>

    fun countByOwnerId(ownerId: Long): Long

    @Modifying @Query("DELETE FROM outfit WHERE id = :#{#outfit.id}") fun delete(outfit: Outfit)
}
