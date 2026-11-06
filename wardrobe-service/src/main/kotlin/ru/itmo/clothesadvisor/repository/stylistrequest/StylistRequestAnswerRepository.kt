package ru.itmo.clothesadvisor.repository.stylistrequest

import org.springframework.data.jdbc.repository.query.Modifying
import org.springframework.data.jdbc.repository.query.Query
import org.springframework.data.repository.Repository
import ru.itmo.clothesadvisor.model.stylistrequest.StylistRequestAnswer

internal interface StylistRequestAnswerRepository : Repository<StylistRequestAnswer, Long> {
    @Modifying
    @Query("INSERT INTO stylist_request_answer (request_id, outfit_id) VALUES (:requestId, :outfitId)")
    fun insert(requestId: Long, outfitId: Long)

    @Query("""
        SELECT a.request_id, a.outfit_id, a.created_at, o.explanation
        FROM stylist_request_answer a JOIN outfit o ON o.id = a.outfit_id
        WHERE a.request_id IN (:requestIds)
    """)
    fun findAllByRequestIdIn(requestIds: List<Long>): List<StylistRequestAnswer>
}
