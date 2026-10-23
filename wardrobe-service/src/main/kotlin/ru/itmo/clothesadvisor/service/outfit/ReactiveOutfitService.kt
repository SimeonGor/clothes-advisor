package ru.itmo.clothesadvisor.service.outfit

import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Service
import reactor.core.publisher.Mono
import ru.itmo.clothesadvisor.dto.outfit.CreateOutfitRequest
import ru.itmo.clothesadvisor.dto.outfit.OutfitResponse
import ru.itmo.clothesadvisor.dto.outfit.StylistOutfitResponse
import ru.itmo.clothesadvisor.dto.rating.CreateRatingRequest
import ru.itmo.clothesadvisor.dto.rating.RatingHistoryResponse
import ru.itmo.clothesadvisor.dto.rating.RatingResponse
import ru.itmo.clothesadvisor.dto.rating.UpdateRatingRequest
import ru.itmo.clothesadvisor.service.blockingAction
import ru.itmo.clothesadvisor.service.blockingCall
import ru.itmo.clothesadvisor.service.rating.OutfitRatingService

@Service
internal class ReactiveOutfitService(
    private val outfits: OutfitService,
    private val ratings: OutfitRatingService,
) {
    fun create(ownerId: Long, request: CreateOutfitRequest): Mono<OutfitResponse> =
        blockingCall { outfits.create(ownerId, request) }

    fun createForClient(
        stylistId: Long,
        ownerId: Long,
        request: CreateOutfitRequest,
    ): Mono<StylistOutfitResponse> =
        blockingCall { outfits.createForClient(stylistId, ownerId, request) }

    fun list(ownerId: Long, pageable: Pageable): Mono<Page<OutfitResponse>> =
        blockingCall { outfits.list(ownerId, pageable) }

    fun listForClient(
        stylistId: Long,
        ownerId: Long,
        pageable: Pageable,
    ): Mono<Page<StylistOutfitResponse>> =
        blockingCall { outfits.listForClient(stylistId, ownerId, pageable) }

    fun get(ownerId: Long, id: Long): Mono<OutfitResponse> =
        blockingCall { outfits.get(ownerId, id) }

    fun getForClient(stylistId: Long, ownerId: Long, id: Long): Mono<StylistOutfitResponse> =
        blockingCall { outfits.getForClient(stylistId, ownerId, id) }

    fun delete(ownerId: Long, id: Long): Mono<Void> =
        blockingAction { outfits.delete(ownerId, id) }

    fun createRating(
        stylistId: Long,
        ownerId: Long,
        outfitId: Long,
        request: CreateRatingRequest,
    ): Mono<RatingResponse> =
        blockingCall { ratings.create(stylistId, ownerId, outfitId, request) }

    fun updateRating(
        stylistId: Long,
        ownerId: Long,
        outfitId: Long,
        request: UpdateRatingRequest,
    ): Mono<RatingResponse> =
        blockingCall { ratings.update(stylistId, ownerId, outfitId, request) }

    fun withdrawRating(stylistId: Long, ownerId: Long, outfitId: Long, version: Long): Mono<Void> =
        blockingAction { ratings.withdraw(stylistId, ownerId, outfitId, version) }

    fun ratingHistory(
        ownerId: Long,
        outfitId: Long,
        pageable: Pageable,
    ): Mono<Page<RatingHistoryResponse>> =
        blockingCall { ratings.history(ownerId, outfitId, pageable) }

    fun ratingHistoryForClient(
        stylistId: Long,
        ownerId: Long,
        outfitId: Long,
        pageable: Pageable,
    ): Mono<Page<RatingHistoryResponse>> =
        blockingCall { ratings.historyForClient(stylistId, ownerId, outfitId, pageable) }
}
