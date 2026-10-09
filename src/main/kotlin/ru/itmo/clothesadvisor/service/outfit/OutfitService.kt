package ru.itmo.clothesadvisor.service.outfit

import jakarta.validation.ConstraintViolationException
import jakarta.validation.Validator
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional
import ru.itmo.clothesadvisor.dto.outfit.CreateOutfitRequest
import ru.itmo.clothesadvisor.dto.outfit.OutfitResponse
import ru.itmo.clothesadvisor.dto.outfit.OutfitWeatherDto
import ru.itmo.clothesadvisor.dto.outfit.StylistOutfitResponse
import ru.itmo.clothesadvisor.model.EntityNotFoundException
import ru.itmo.clothesadvisor.model.outfit.Outfit
import ru.itmo.clothesadvisor.model.outfit.OutfitItem
import ru.itmo.clothesadvisor.model.outfit.OutfitItemId
import ru.itmo.clothesadvisor.model.outfit.OutfitSource
import ru.itmo.clothesadvisor.model.outfit.OutfitWeather
import ru.itmo.clothesadvisor.repository.outfit.OutfitItemRepository
import ru.itmo.clothesadvisor.repository.outfit.OutfitRepository
import ru.itmo.clothesadvisor.repository.outfit.OutfitWeatherRepository
import ru.itmo.clothesadvisor.repository.wardrobe.WardrobeItemRepository
import ru.itmo.clothesadvisor.repository.weather.PrecipitationTypeRepository
import ru.itmo.clothesadvisor.service.access.AccessGrantService
import ru.itmo.clothesadvisor.service.rating.OutfitRatingService

internal class InvalidOutfitRequestException : RuntimeException()

@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
internal class OutfitService(
    private val outfits: OutfitRepository,
    private val composition: OutfitItemRepository,
    private val weather: OutfitWeatherRepository,
    private val wardrobe: WardrobeItemRepository,
    private val precipitation: PrecipitationTypeRepository,
    private val access: AccessGrantService,
    private val ratings: OutfitRatingService,
    private val validator: Validator,
) {
    @Transactional
    fun create(ownerId: Long, request: CreateOutfitRequest): OutfitResponse =
        create(ownerId, ownerId, OutfitSource.USER, request)

    @Transactional
    fun createAi(ownerId: Long, request: CreateOutfitRequest): OutfitResponse =
        create(ownerId, ownerId, OutfitSource.AI, request)

    @Transactional
    fun createForClient(
        stylistId: Long,
        ownerId: Long,
        request: CreateOutfitRequest,
    ): StylistOutfitResponse {
        access.requireAccess(stylistId, ownerId)
        return StylistOutfitResponse(
            create(ownerId, stylistId, OutfitSource.STYLIST, request),
            null,
        )
    }

    fun list(ownerId: Long, pageable: Pageable): Page<OutfitResponse> {
        val page = outfits.findAllByOwnerIdOrderByIdDesc(ownerId, pageable)
        return PageImpl(toResponses(page.content), page.pageable, page.totalElements)
    }

    fun listForClient(
        stylistId: Long,
        ownerId: Long,
        pageable: Pageable,
    ): Page<StylistOutfitResponse> {
        access.requireAccess(stylistId, ownerId)
        val page = list(ownerId, pageable)
        val ownRatings = ratings.ownRatings(stylistId, page.content.map { it.id })
        return page.map { StylistOutfitResponse(it, ownRatings[it.id]) }
    }

    fun get(ownerId: Long, id: Long): OutfitResponse =
        toResponses(listOf(findOwnedOutfit(ownerId, id))).single()

    fun getForClient(stylistId: Long, ownerId: Long, id: Long): StylistOutfitResponse {
        access.requireAccess(stylistId, ownerId)
        return StylistOutfitResponse(
            get(ownerId, id),
            ratings.ownRatings(stylistId, listOf(id))[id],
        )
    }

    @Transactional
    fun delete(ownerId: Long, id: Long) {
        val outfit =
            outfits.findLockedByIdAndOwnerId(id, ownerId) ?: throw EntityNotFoundException()
        ratings.archiveForDeletion(id)
        outfits.delete(outfit)
    }

    private fun create(
        ownerId: Long,
        authorId: Long,
        source: OutfitSource,
        request: CreateOutfitRequest,
    ): OutfitResponse {
        val violations = validator.validate(request)
        if (violations.isNotEmpty()) {
            throw ConstraintViolationException(violations)
        }
        if (
            request.itemIds.size !in 1..50 ||
                request.itemIds.any { it == null || it <= 0 } ||
                request.itemIds.distinct().size != request.itemIds.size
        ) {
            throw InvalidOutfitRequestException()
        }

        val ids = request.itemIds.map { requireNotNull(it) }
        if (!precipitation.existsById(request.weather.precipitationTypeId)) {
            throw InvalidOutfitRequestException()
        }
        if (wardrobe.countByOwnerIdAndIdIn(ownerId, ids) != ids.size.toLong()) {
            throw EntityNotFoundException()
        }

        val outfit = outfits.create(ownerId, authorId, source, request.name)
        val id = requireNotNull(outfit.id)
        weather.insert(
            OutfitWeather(
                id,
                request.weather.temperatureC,
                request.weather.precipitationTypeId,
                request.weather.windSpeedMps,
            ),
        )
        composition.insertAll(
            ids.mapIndexed { position, itemId -> OutfitItem(OutfitItemId(id, itemId), position) },
        )

        return OutfitResponse(
            id,
            ownerId,
            authorId,
            source,
            outfit.name,
            ids,
            request.weather,
            outfit.createdAt,
        )
    }

    private fun findOwnedOutfit(ownerId: Long, id: Long): Outfit =
        outfits.findByIdAndOwnerId(id, ownerId) ?: throw EntityNotFoundException()

    private fun toResponses(outfitRows: List<Outfit>): List<OutfitResponse> {
        if (outfitRows.isEmpty()) {
            return emptyList()
        }

        val ids = outfitRows.map { requireNotNull(it.id) }
        val itemsByOutfit =
            composition.findAllByIdOutfitIdInOrderByPositionAsc(ids).groupBy { it.id.outfitId }
        val weatherByOutfit = weather.findAllByIdIn(ids).associateBy { it.id }
        val counts = ratings.counts(ids)

        return outfitRows.map {
            val id = requireNotNull(it.id)
            val weather = weatherByOutfit.getValue(id)

            OutfitResponse(
                id,
                it.ownerId,
                it.authorId,
                it.source,
                it.name,
                itemsByOutfit.getValue(id).map { item -> item.id.wardrobeItemId },
                OutfitWeatherDto(
                    weather.temperatureC,
                    weather.precipitationTypeId,
                    weather.windSpeedMps,
                ),
                it.createdAt,
                counts[id]?.likes ?: 0,
                counts[id]?.dislikes ?: 0,
            )
        }
    }
}
