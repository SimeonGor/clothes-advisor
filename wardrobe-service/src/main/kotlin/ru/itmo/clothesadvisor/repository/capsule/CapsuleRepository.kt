package ru.itmo.clothesadvisor.repository.capsule

import org.springframework.data.jdbc.repository.query.Modifying
import org.springframework.data.jdbc.repository.query.Query
import org.springframework.data.repository.Repository
import ru.itmo.clothesadvisor.model.capsule.Capsule

internal interface CapsuleRepository : Repository<Capsule, Long> {
    @Query("INSERT INTO capsule (owner_id, name) VALUES (:ownerId, :name) RETURNING *")
    fun create(ownerId: Long, name: String): Capsule

    @Query("""
        INSERT INTO capsule (owner_id, name, source, occasion, preferred_style, explanation)
        VALUES (:ownerId, :name, 'AI', :occasion, :preferredStyle, :explanation) RETURNING *
    """)
    fun createAi(ownerId: Long, name: String, occasion: String?, preferredStyle: String?, explanation: String): Capsule

    fun findByIdAndOwnerId(id: Long, ownerId: Long): Capsule?

    @Query("SELECT * FROM capsule WHERE id = :id AND owner_id = :ownerId FOR UPDATE")
    fun findLockedByIdAndOwnerId(id: Long, ownerId: Long): Capsule?

    @Query("SELECT * FROM capsule WHERE owner_id = :ownerId ORDER BY id DESC LIMIT :limit OFFSET :offset")
    fun findPage(ownerId: Long, limit: Int, offset: Long): List<Capsule>

    fun countByOwnerId(ownerId: Long): Long

    @Modifying @Query("DELETE FROM capsule WHERE id = :#{#capsule.id}")
    fun delete(capsule: Capsule)
}
