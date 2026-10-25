package ru.itmo.clothesadvisor.service.outfit

import jakarta.validation.ConstraintViolationException
import jakarta.validation.Validator
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import ru.itmo.clothesadvisor.client.account.BlockingAccountClient
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
import ru.itmo.clothesadvisor.service.rating.OutfitRatingService
import ru.itmo.clothesadvisor.model.idempotency.IdempotencyOperation.CREATE_OUTFIT
import ru.itmo.clothesadvisor.service.idempotency.IdempotencyService
import tools.jackson.core.type.TypeReference

internal class InvalidOutfitRequestException : RuntimeException()

@Service
internal class OutfitService(
    private val outfits: OutfitRepository,
    private val composition: OutfitItemRepository,
    private val weather: OutfitWeatherRepository,
    private val wardrobe: WardrobeItemRepository,
    private val precipitation: PrecipitationTypeRepository,
    private val accounts: BlockingAccountClient,
    private val ratings: OutfitRatingService,
    private val validator: Validator,
    private val idempotency: IdempotencyService,
    transactionManager: PlatformTransactionManager,
) {
    private val writeTransaction = TransactionTemplate(transactionManager)
    private val readTransaction =
        TransactionTemplate(transactionManager).apply {
            isReadOnly = true
            isolationLevel = TransactionDefinition.ISOLATION_REPEATABLE_READ
        }

    fun create(ownerId: Long, request: CreateOutfitRequest, key: String? = null): OutfitResponse =
        idempotency.execute(
            ownerId, CREATE_OUTFIT, key, listOf(ownerId, request),
            object : TypeReference<OutfitResponse>() {},
        ) { completion ->
            completion.commit {
                writeTransaction.execute { create(ownerId, ownerId, OutfitSource.USER, request) }
            }
        }

    fun createAi(
        ownerId: Long,
        request: CreateOutfitRequest,
        occasion: String? = null,
        preferredStyle: String? = null,
        explanation: String? = null,
    ): OutfitResponse =
        writeTransaction.execute {
            create(ownerId, ownerId, OutfitSource.AI, request, occasion, preferredStyle, explanation)
        }

    fun createForClient(
        stylistId: Long,
        ownerId: Long,
        request: CreateOutfitRequest,
        key: String? = null,
    ): StylistOutfitResponse {
        accounts.requireStylistAccess(stylistId, ownerId)
        val created = idempotency.execute(
            stylistId, CREATE_OUTFIT, key, listOf(ownerId, request),
            object : TypeReference<OutfitResponse>() {},
        ) { completion ->
            completion.commit {
                writeTransaction.execute { create(ownerId, stylistId, OutfitSource.STYLIST, request) }
            }
        }
        return StylistOutfitResponse(created, null)
    }

    fun list(ownerId: Long, pageable: Pageable): Page<OutfitResponse> = readTransaction.execute {
        val page = outfits.findAllByOwnerIdOrderByIdDesc(ownerId, pageable)
        PageImpl(toResponses(page.content), page.pageable, page.totalElements)
    }

    fun listForClient(
        stylistId: Long,
        ownerId: Long,
        pageable: Pageable,
    ): Page<StylistOutfitResponse> {
        accounts.requireStylistAccess(stylistId, ownerId)
        return readTransaction.execute {
            val page = list(ownerId, pageable)
            val ownRatings = ratings.ownRatings(stylistId, page.content.map { it.id })
            page.map { StylistOutfitResponse(it, ownRatings[it.id]) }
        }
    }

    fun get(ownerId: Long, id: Long): OutfitResponse =
        readTransaction.execute { toResponses(listOf(findOwnedOutfit(ownerId, id))).single() }

    fun getForClient(stylistId: Long, ownerId: Long, id: Long): StylistOutfitResponse {
        accounts.requireStylistAccess(stylistId, ownerId)
        return readTransaction.execute {
            StylistOutfitResponse(
                get(ownerId, id),
                ratings.ownRatings(stylistId, listOf(id))[id],
            )
        }
    }

    fun delete(ownerId: Long, id: Long) {
        writeTransaction.executeWithoutResult {
            val outfit =
                outfits.findLockedByIdAndOwnerId(id, ownerId) ?: throw EntityNotFoundException()
            ratings.archiveForDeletion(id)
            outfits.delete(outfit)
        }
    }

    private fun create(
        ownerId: Long,
        authorId: Long,
        source: OutfitSource,
        request: CreateOutfitRequest,
        occasion: String? = null,
        preferredStyle: String? = null,
        explanation: String? = null,
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

        val outfit = outfits.create(ownerId, authorId, source, request.name, occasion, preferredStyle, explanation)
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
            occasion = outfit.occasion,
            preferredStyle = outfit.preferredStyle,
            explanation = outfit.explanation,
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
                it.occasion,
                it.preferredStyle,
                it.explanation,
            )
        }
    }
}
