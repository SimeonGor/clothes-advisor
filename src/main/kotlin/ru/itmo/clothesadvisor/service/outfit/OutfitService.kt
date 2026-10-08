package ru.itmo.clothesadvisor.service.outfit

import jakarta.persistence.EntityNotFoundException
import java.time.Clock
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional
import ru.itmo.clothesadvisor.dto.outfit.CreateOutfitRequest
import ru.itmo.clothesadvisor.dto.outfit.OutfitResponse
import ru.itmo.clothesadvisor.dto.outfit.OutfitWeatherDto
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
    private val clock: Clock,
) {
    @Transactional
    fun create(ownerId: Long, request: CreateOutfitRequest): OutfitResponse =
        create(ownerId, ownerId, OutfitSource.USER, request)

    @Transactional
    fun createForClient(stylistId: Long, ownerId: Long, request: CreateOutfitRequest): OutfitResponse {
        access.requireAccess(stylistId, ownerId)
        return create(ownerId, stylistId, OutfitSource.STYLIST, request)
    }

    fun list(ownerId: Long, pageable: Pageable): Page<OutfitResponse> {
        val page = outfits.findAllByOwnerIdOrderByIdDesc(ownerId, pageable)
        return PageImpl(responses(page.content), page.pageable, page.totalElements)
    }

    fun listForClient(stylistId: Long, ownerId: Long, pageable: Pageable): Page<OutfitResponse> {
        access.requireAccess(stylistId, ownerId)
        return list(ownerId, pageable)
    }

    fun get(ownerId: Long, id: Long): OutfitResponse = responses(listOf(owned(ownerId, id))).single()

    fun getForClient(stylistId: Long, ownerId: Long, id: Long): OutfitResponse {
        access.requireAccess(stylistId, ownerId)
        return get(ownerId, id)
    }

    @Transactional
    fun delete(ownerId: Long, id: Long) {
        outfits.delete(owned(ownerId, id))
        outfits.flush()
    }

    private fun create(ownerId: Long, authorId: Long, source: OutfitSource, request: CreateOutfitRequest): OutfitResponse {
        if (request.itemIds.size !in 1..50 || request.itemIds.any { it == null || it <= 0 } ||
            request.itemIds.distinct().size != request.itemIds.size
        ) throw InvalidOutfitRequestException()
        val ids = request.itemIds.map { requireNotNull(it) }
        if (!precipitation.existsById(request.weather.precipitationTypeId)) throw InvalidOutfitRequestException()
        if (wardrobe.countByOwnerIdAndIdIn(ownerId, ids) != ids.size.toLong()) throw EntityNotFoundException()

        val outfit = outfits.save(Outfit(ownerId, authorId, source, request.name, clock.instant()))
        val id = requireNotNull(outfit.id)
        weather.save(OutfitWeather(id, request.weather.temperatureC, request.weather.precipitationTypeId, request.weather.windSpeedMps))
        composition.saveAll(ids.mapIndexed { position, itemId -> OutfitItem(OutfitItemId(id, itemId), position) })
        outfits.flush()
        return OutfitResponse(id, ownerId, authorId, source, outfit.name, ids, request.weather, outfit.createdAt)
    }

    private fun owned(ownerId: Long, id: Long): Outfit =
        outfits.findByIdAndOwnerId(id, ownerId) ?: throw EntityNotFoundException()

    private fun responses(rows: List<Outfit>): List<OutfitResponse> {
        if (rows.isEmpty()) return emptyList()
        val ids = rows.map { requireNotNull(it.id) }
        val itemsByOutfit = composition.findAllByKeyOutfitIdInOrderByPositionAsc(ids).groupBy { it.id.outfitId }
        val weatherByOutfit = weather.findAllByOutfitIdIn(ids).associateBy { it.id }
        return rows.map {
            val id = requireNotNull(it.id)
            val conditions = weatherByOutfit.getValue(id)
            OutfitResponse(id, it.ownerId, it.authorId, it.source, it.name,
                itemsByOutfit.getValue(id).map { item -> item.id.wardrobeItemId },
                OutfitWeatherDto(conditions.temperatureC, conditions.precipitationTypeId, conditions.windSpeedMps), it.createdAt)
        }
    }
}
