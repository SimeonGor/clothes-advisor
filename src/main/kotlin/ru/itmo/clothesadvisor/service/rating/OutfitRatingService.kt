package ru.itmo.clothesadvisor.service.rating

import jakarta.persistence.EntityNotFoundException
import java.time.Clock
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.security.access.AccessDeniedException
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
    private val clock: Clock,
) {
    @Transactional
    fun create(stylistId: Long, ownerId: Long, outfitId: Long, request: CreateRatingRequest): RatingResponse {
        lockForRating(stylistId, ownerId, outfitId)
        val key = OutfitRatingId(outfitId, stylistId)
        if (ratings.findById(key) != null) throw RatingConflictException()
        val version = (history.maxVersion(outfitId, stylistId) ?: 0) + 1
        return ratings.save(OutfitRating(key, request.vote, clock.instant(), version = version)).response()
    }

    @Transactional
    fun update(stylistId: Long, ownerId: Long, outfitId: Long, request: UpdateRatingRequest): RatingResponse {
        lockForRating(stylistId, ownerId, outfitId)
        val rating = ratings.findById(OutfitRatingId(outfitId, stylistId)) ?: throw EntityNotFoundException()
        if (rating.version != request.version) throw RatingConflictException()
        val now = clock.instant()
        val previous = OutfitRatingHistory(rating, now)
        rating.vote = request.vote
        rating.version += 1
        rating.modifiedAt = now
        ratings.flush()
        history.save(previous)
        history.flush()
        return rating.response()
    }

    @Transactional
    fun withdraw(stylistId: Long, ownerId: Long, outfitId: Long, version: Long) {
        lockForRating(stylistId, ownerId, outfitId)
        val rating = ratings.findById(OutfitRatingId(outfitId, stylistId)) ?: throw EntityNotFoundException()
        if (rating.version != version) throw RatingConflictException()
        val previous = OutfitRatingHistory(rating, clock.instant())
        ratings.delete(rating)
        ratings.flush()
        history.save(previous)
        history.flush()
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
        history.archiveCurrent(outfitId, clock.instant())
    }

    private fun lockForRating(stylistId: Long, ownerId: Long, outfitId: Long) {
        access.requireAccess(stylistId, ownerId)
        val outfit = outfits.findLockedByIdAndOwnerId(outfitId, ownerId) ?: throw EntityNotFoundException()
        access.requireAccess(stylistId, ownerId)
        if (outfit.authorId == stylistId) throw AccessDeniedException("Cannot rate own outfit")
    }

    private fun OutfitRating.response() = RatingResponse(vote, version)
}
