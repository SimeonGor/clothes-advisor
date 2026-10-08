package ru.itmo.clothesadvisor.service.rating

import ru.itmo.clothesadvisor.model.EntityNotFoundException
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import ru.itmo.clothesadvisor.model.AccessDeniedException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional
import ru.itmo.clothesadvisor.dto.rating.CreateRatingRequest
import ru.itmo.clothesadvisor.dto.rating.RatingHistoryResponse
import ru.itmo.clothesadvisor.dto.rating.RatingResponse
import ru.itmo.clothesadvisor.dto.rating.UpdateRatingRequest
import ru.itmo.clothesadvisor.model.rating.OutfitRating
import ru.itmo.clothesadvisor.model.rating.OutfitRatingHistory
import ru.itmo.clothesadvisor.model.rating.OutfitRatingId
import ru.itmo.clothesadvisor.model.rating.RatingVote
import ru.itmo.clothesadvisor.repository.outfit.OutfitRepository
import ru.itmo.clothesadvisor.repository.rating.OutfitRatingHistoryRepository
import ru.itmo.clothesadvisor.repository.rating.OutfitRatingRepository
import ru.itmo.clothesadvisor.repository.rating.RatingCounts
import ru.itmo.clothesadvisor.service.access.AccessGrantService

internal class RatingConflictException : RuntimeException()

@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
internal class OutfitRatingService(
    private val outfits: OutfitRepository,
    private val access: AccessGrantService,
    private val ratings: OutfitRatingRepository,
    private val history: OutfitRatingHistoryRepository,
) {
    @Transactional
    fun create(stylistId: Long, ownerId: Long, outfitId: Long, request: CreateRatingRequest): RatingResponse {
        lockForRating(stylistId, ownerId, outfitId)
        val key = OutfitRatingId(outfitId, stylistId)
        val version = (history.maxVersion(outfitId, stylistId) ?: 0) + 1
        val rating = ratings.insert(key, request.vote, version) ?: throw RatingConflictException()
        return rating.response()
    }

    @Transactional
    fun update(stylistId: Long, ownerId: Long, outfitId: Long, request: UpdateRatingRequest): RatingResponse {
        lockForRating(stylistId, ownerId, outfitId)
        val rating = ratings.findById(OutfitRatingId(outfitId, stylistId)) ?: throw EntityNotFoundException()
        if (rating.version != request.version) throw RatingConflictException()
        val previous = OutfitRatingHistory(rating)
        rating.vote = request.vote
        rating.version += 1
        val saved = ratings.update(rating, request.version)
        history.save(previous)
        return saved.response()
    }

    @Transactional
    fun withdraw(stylistId: Long, ownerId: Long, outfitId: Long, version: Long) {
        lockForRating(stylistId, ownerId, outfitId)
        val rating = ratings.findById(OutfitRatingId(outfitId, stylistId)) ?: throw EntityNotFoundException()
        if (rating.version != version) throw RatingConflictException()
        val previous = OutfitRatingHistory(rating)
        ratings.delete(rating)
        history.save(previous)
    }

    fun counts(outfitIds: List<Long>): Map<Long, RatingCounts> =
        ratings.counts(outfitIds).associateBy { it.outfitId }

    fun ownRatings(stylistId: Long, outfitIds: List<Long>): Map<Long, RatingResponse> =
        if (outfitIds.isEmpty()) emptyMap() else
            ratings.findAllByIdOutfitIdInAndIdStylistId(outfitIds, stylistId).associate { it.id.outfitId to it.response() }

    fun history(ownerId: Long, outfitId: Long, pageable: Pageable): Page<RatingHistoryResponse> {
        outfits.findByIdAndOwnerId(outfitId, ownerId) ?: throw EntityNotFoundException()
        return history.timeline(outfitId, pageable).map {
            RatingHistoryResponse(it.stylistId, RatingVote.valueOf(it.vote), it.version, it.modifiedAt, it.archivedAt)
        }
    }

    fun historyForClient(stylistId: Long, ownerId: Long, outfitId: Long, pageable: Pageable): Page<RatingHistoryResponse> {
        access.requireAccess(stylistId, ownerId)
        return history(ownerId, outfitId, pageable)
    }

    @Transactional
    fun archiveForDeletion(outfitId: Long) {
        history.archiveCurrent(outfitId)
    }

    private fun lockForRating(stylistId: Long, ownerId: Long, outfitId: Long) {
        access.requireAccess(stylistId, ownerId)
        val outfit = outfits.findLockedByIdAndOwnerId(outfitId, ownerId) ?: throw EntityNotFoundException()
        access.requireAccess(stylistId, ownerId)
        if (outfit.authorId == stylistId) throw AccessDeniedException("Cannot rate own outfit")
    }

    private fun OutfitRating.response() = RatingResponse(vote, version)
}
