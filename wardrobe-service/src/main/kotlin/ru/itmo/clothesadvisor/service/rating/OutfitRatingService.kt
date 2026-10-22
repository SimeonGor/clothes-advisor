package ru.itmo.clothesadvisor.service.rating

import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import ru.itmo.clothesadvisor.client.account.AccountClient
import ru.itmo.clothesadvisor.dto.rating.CreateRatingRequest
import ru.itmo.clothesadvisor.dto.rating.RatingHistoryResponse
import ru.itmo.clothesadvisor.dto.rating.RatingResponse
import ru.itmo.clothesadvisor.dto.rating.UpdateRatingRequest
import ru.itmo.clothesadvisor.model.AccessDeniedException
import ru.itmo.clothesadvisor.model.EntityNotFoundException
import ru.itmo.clothesadvisor.model.rating.OutfitRating
import ru.itmo.clothesadvisor.model.rating.OutfitRatingHistory
import ru.itmo.clothesadvisor.model.rating.OutfitRatingId
import ru.itmo.clothesadvisor.repository.outfit.OutfitRepository
import ru.itmo.clothesadvisor.repository.rating.OutfitRatingHistoryRepository
import ru.itmo.clothesadvisor.repository.rating.OutfitRatingRepository
import ru.itmo.clothesadvisor.repository.rating.RatingCounts

internal class RatingConflictException : RuntimeException()

@Service
internal class OutfitRatingService(
    private val outfits: OutfitRepository,
    private val accounts: AccountClient,
    private val ratings: OutfitRatingRepository,
    private val history: OutfitRatingHistoryRepository,
    transactionManager: PlatformTransactionManager,
) {
    private val writeTransaction = TransactionTemplate(transactionManager)
    private val readTransaction =
        TransactionTemplate(transactionManager).apply {
            isReadOnly = true
            isolationLevel = TransactionDefinition.ISOLATION_REPEATABLE_READ
        }

    fun create(
        stylistId: Long,
        ownerId: Long,
        outfitId: Long,
        request: CreateRatingRequest,
    ): RatingResponse {
        accounts.requireStylistAccess(stylistId, ownerId)
        return writeTransaction.execute {
            lockForRating(stylistId, ownerId, outfitId)
            val key = OutfitRatingId(outfitId, stylistId)
            val version = (history.maxVersion(outfitId, stylistId) ?: 0) + 1
            val rating = ratings.insert(key, request.vote, version) ?: throw RatingConflictException()
            rating.toResponse()
        }
    }

    fun update(
        stylistId: Long,
        ownerId: Long,
        outfitId: Long,
        request: UpdateRatingRequest,
    ): RatingResponse {
        accounts.requireStylistAccess(stylistId, ownerId)
        return writeTransaction.execute {
            lockForRating(stylistId, ownerId, outfitId)
            val rating =
                ratings.findById(OutfitRatingId(outfitId, stylistId)) ?: throw EntityNotFoundException()
            if (rating.version != request.version) {
                throw RatingConflictException()
            }
            val previous = OutfitRatingHistory(rating)
            rating.vote = request.vote
            rating.version += 1
            val saved = ratings.update(rating, request.version)
            history.insert(previous)
            saved.toResponse()
        }
    }

    fun withdraw(stylistId: Long, ownerId: Long, outfitId: Long, version: Long) {
        accounts.requireStylistAccess(stylistId, ownerId)
        writeTransaction.executeWithoutResult {
            lockForRating(stylistId, ownerId, outfitId)
            val rating =
                ratings.findById(OutfitRatingId(outfitId, stylistId)) ?: throw EntityNotFoundException()
            if (rating.version != version) {
                throw RatingConflictException()
            }
            val previous = OutfitRatingHistory(rating)
            ratings.delete(rating)
            history.insert(previous)
        }
    }

    fun counts(outfitIds: List<Long>): Map<Long, RatingCounts> =
        readTransaction.execute { ratings.counts(outfitIds).associateBy { it.outfitId } }

    fun ownRatings(stylistId: Long, outfitIds: List<Long>): Map<Long, RatingResponse> =
        readTransaction.execute {
            if (outfitIds.isEmpty()) emptyMap()
            else
                ratings.findAllByIdOutfitIdInAndIdStylistId(outfitIds, stylistId).associate {
                    it.id.outfitId to it.toResponse()
                }
        }

    fun history(ownerId: Long, outfitId: Long, pageable: Pageable): Page<RatingHistoryResponse> =
        readTransaction.execute {
            outfits.findByIdAndOwnerId(outfitId, ownerId) ?: throw EntityNotFoundException()
            history.timeline(outfitId, pageable).map {
                RatingHistoryResponse(it.stylistId, it.vote, it.version, it.modifiedAt, it.archivedAt)
            }
        }

    fun historyForClient(
        stylistId: Long,
        ownerId: Long,
        outfitId: Long,
        pageable: Pageable,
    ): Page<RatingHistoryResponse> {
        accounts.requireStylistAccess(stylistId, ownerId)
        return history(ownerId, outfitId, pageable)
    }

    fun archiveForDeletion(outfitId: Long) {
        writeTransaction.executeWithoutResult { history.archiveCurrent(outfitId) }
    }

    private fun lockForRating(stylistId: Long, ownerId: Long, outfitId: Long) {
        val outfit =
            outfits.findLockedByIdAndOwnerId(outfitId, ownerId) ?: throw EntityNotFoundException()
        if (outfit.authorId == stylistId) {
            throw AccessDeniedException("Cannot rate own outfit")
        }
    }

    private fun OutfitRating.toResponse() = RatingResponse(vote, version)
}
