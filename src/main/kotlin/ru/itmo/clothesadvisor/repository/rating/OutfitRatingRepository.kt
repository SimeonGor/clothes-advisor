package ru.itmo.clothesadvisor.repository.rating

import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.Repository
import ru.itmo.clothesadvisor.model.rating.OutfitRating
import ru.itmo.clothesadvisor.model.rating.OutfitRatingId

internal interface RatingCounts {
    val outfitId: Long
    val likes: Long
    val dislikes: Long
}

internal interface OutfitRatingRepository : Repository<OutfitRating, OutfitRatingId> {
    fun findById(id: OutfitRatingId): OutfitRating?
    fun save(rating: OutfitRating): OutfitRating
    fun delete(rating: OutfitRating)
    fun flush()
    fun findAllByIdOutfitIdInAndIdStylistId(outfitIds: List<Long>, stylistId: Long): List<OutfitRating>

    @Query(value = """
        SELECT outfit_id AS "outfitId", count(*) FILTER (WHERE vote = 'LIKE') AS likes,
            count(*) FILTER (WHERE vote = 'DISLIKE') AS dislikes
        FROM outfit_rating WHERE outfit_id IN (:outfitIds) GROUP BY outfit_id
    """, nativeQuery = true)
    fun counts(outfitIds: List<Long>): List<RatingCounts>
}
