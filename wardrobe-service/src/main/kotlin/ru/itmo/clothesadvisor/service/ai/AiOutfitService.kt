package ru.itmo.clothesadvisor.service.ai

import io.micrometer.core.instrument.MeterRegistry
import java.util.Base64
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import ru.itmo.clothesadvisor.client.recommendation.RecommendationClient
import ru.itmo.clothesadvisor.dto.ai.CreateAiOutfitRequest
import ru.itmo.clothesadvisor.dto.ai.CreateAiOutfitReplacementRequest
import ru.itmo.clothesadvisor.dto.outfit.CreateOutfitRequest
import ru.itmo.clothesadvisor.dto.outfit.OutfitResponse
import ru.itmo.clothesadvisor.dto.recommendation.OutfitSelectionCandidate
import ru.itmo.clothesadvisor.dto.recommendation.OutfitSelectionRequest
import ru.itmo.clothesadvisor.dto.recommendation.OutfitSelectionResponse
import ru.itmo.clothesadvisor.dto.recommendation.OutfitSelectionStylePreferences
import ru.itmo.clothesadvisor.dto.recommendation.OutfitSelectionWeather
import ru.itmo.clothesadvisor.model.AccessDeniedException
import ru.itmo.clothesadvisor.model.EntityNotFoundException
import ru.itmo.clothesadvisor.model.ai.AiOutfitException
import ru.itmo.clothesadvisor.model.ai.AiOutfitFailure
import ru.itmo.clothesadvisor.model.user.UserRole
import ru.itmo.clothesadvisor.model.wardrobe.WardrobeItem
import ru.itmo.clothesadvisor.repository.wardrobe.WardrobeItemPhotoRepository
import ru.itmo.clothesadvisor.repository.preferences.StylePreferencesRepository
import ru.itmo.clothesadvisor.repository.wardrobe.WardrobeItemRepository
import ru.itmo.clothesadvisor.repository.weather.PrecipitationTypeRepository
import ru.itmo.clothesadvisor.service.outfit.OutfitService
import ru.itmo.clothesadvisor.service.user.CurrentUserService
import ru.itmo.clothesadvisor.service.wardrobe.WardrobeItemPhotoService
import ru.itmo.clothesadvisor.model.idempotency.IdempotencyOperation.CREATE_AI_OUTFIT
import ru.itmo.clothesadvisor.model.idempotency.IdempotencyOperation.CREATE_AI_OUTFIT_REPLACEMENT
import ru.itmo.clothesadvisor.service.idempotency.IdempotencyService
import tools.jackson.core.type.TypeReference

@Service
internal class AiOutfitService(
    private val currentUsers: CurrentUserService,
    private val items: WardrobeItemRepository,
    private val photos: WardrobeItemPhotoRepository,
    private val precipitation: PrecipitationTypeRepository,
    private val preferences: StylePreferencesRepository,
    private val photoContent: WardrobeItemPhotoService,
    private val recommendation: RecommendationClient,
    private val outfits: OutfitService,
    private val idempotency: IdempotencyService,
    transactionManager: PlatformTransactionManager,
    meters: MeterRegistry,
    @Value("\${app.openai.owner-login:user}") private val ownerLogin: String,
) {
    private val active = AtomicBoolean()
    private val rejected = meters.counter("wardrobe.ai.rejected")
    private val successfulDuration = meters.timer("wardrobe.ai.duration", "outcome", "success")
    private val failedDuration = meters.timer("wardrobe.ai.duration", "outcome", "failure")
    private val transaction =
        TransactionTemplate(transactionManager).apply {
            isolationLevel = TransactionDefinition.ISOLATION_READ_COMMITTED
        }

    init {
        meters.gauge("wardrobe.ai.active", active) { if (it.get()) 1.0 else 0.0 }
    }

    fun create(ownerId: Long, request: CreateAiOutfitRequest, key: String? = null): OutfitResponse {
        requireAiAccess(ownerId)
        return idempotency.execute(
            ownerId, CREATE_AI_OUTFIT, key, listOf(ownerId, request),
            object : TypeReference<OutfitResponse>() {},
        ) { completion ->
            select(ownerId, completion) { prepareCandidates(ownerId, request) }
        }
    }

    fun replace(ownerId: Long, sourceId: Long, request: CreateAiOutfitReplacementRequest, key: String? = null): OutfitResponse {
        requireAiAccess(ownerId)
        return idempotency.execute(
            ownerId, CREATE_AI_OUTFIT_REPLACEMENT, key, listOf(ownerId, sourceId, request),
            object : TypeReference<OutfitResponse>() {},
        ) { completion ->
            select(ownerId, completion, sourceId) {
                val source = outfits.get(ownerId, sourceId)
                validateIds(request.replaceItemIds)
                val replaced = request.replaceItemIds.map { requireNotNull(it) }.toSet()
                if (!source.itemIds.containsAll(replaced)) {
                    throw AiOutfitException(AiOutfitFailure.INVALID_REQUEST)
                }
                prepareCandidates(
                    ownerId,
                    CreateAiOutfitRequest(request.name, source.weather, request.candidateItemIds,
                        source.occasion, source.preferredStyle),
                    source.itemIds.filter { it !in replaced }.toSet(),
                    replaced,
                )
            }
        }
    }

    private fun select(
        ownerId: Long,
        completion: IdempotencyService.Completion,
        sourceOutfitId: Long? = null,
        prepare: () -> PreparedCandidates,
    ): OutfitResponse {
        if (!active.compareAndSet(false, true)) {
            rejected.increment()
            throw AiOutfitException(AiOutfitFailure.UNAVAILABLE)
        }

        val startedAt = System.nanoTime()
        var succeeded = false
        try {
            val prepared = prepare()
            val request = prepared.request
            val selection =
                OutfitSelectionRequest(
                    prepared.weather,
                    loadImages(ownerId, prepared.candidates),
                    request.occasion,
                    request.preferredStyle,
                    prepared.requiredItemIds?.toList(),
                    prepared.stylePreferences,
                )
            requireAiAccess(ownerId)
            val response = recommendation.select(selection)
            validateSelection(response, prepared.candidates.map { requireNotNull(it.item.id) }.toSet(), prepared.requiredItemIds)
            requireAiAccess(ownerId)

            val saved = completion.commit {
                saveOutfit(ownerId, request, prepared.candidates, response.itemIds, response.explanation, sourceOutfitId)
            }
            succeeded = true
            return saved
        } finally {
            active.set(false)
            val duration = if (succeeded) successfulDuration else failedDuration
            duration.record(System.nanoTime() - startedAt, TimeUnit.NANOSECONDS)
        }
    }

    private fun prepareCandidates(
        ownerId: Long,
        request: CreateAiOutfitRequest,
        requiredItemIds: Set<Long>? = null,
        excludedIds: Set<Long> = emptySet(),
    ): PreparedCandidates = transaction.execute {
        val ids = request.candidateItemIds
        if (ids != null) validateIds(ids)
        val precipitationType =
            precipitation.findById(request.weather.precipitationTypeId)
                ?: throw AiOutfitException(AiOutfitFailure.INVALID_REQUEST)
        val offeredItems =
            if (ids == null) {
                val found = if (excludedIds.isEmpty()) items.findOwnedItemsWithPhotos(ownerId, MAX_AI_CANDIDATES + 1)
                    else items.findOwnedItemsWithPhotosExcluding(ownerId, excludedIds, MAX_AI_CANDIDATES + 1)
                found.also {
                    if (it.size > MAX_AI_CANDIDATES) {
                        throw AiOutfitException(AiOutfitFailure.INVALID_REQUEST)
                    }
                }
            }
            else {
                items
                    .findAllByOwnerIdAndIdInOrderByIdAsc(ownerId, ids.map { requireNotNull(it) })
                    .also {
                        if (it.size != ids.size) {
                            throw EntityNotFoundException()
                        }
                    }
            }.filter { it.id !in excludedIds }

        val requiredItems = if (requiredItemIds.isNullOrEmpty()) emptyList()
            else items.findAllByOwnerIdAndIdInOrderByIdAsc(ownerId, requiredItemIds).also {
                if (it.size != requiredItemIds.size) throw AiOutfitException(AiOutfitFailure.SOURCE_CHANGED)
            }
        val candidateItems = (offeredItems + requiredItems).distinctBy { it.id }

        val candidates = candidateItems.mapNotNull { item ->
            val id = requireNotNull(item.id)
            val photo = photos.findFirstByItemIdOrderByIdAsc(id)
            if (photo == null && requiredItemIds?.contains(id) == true) {
                throw AiOutfitException(AiOutfitFailure.INVALID_REQUEST)
            }
            photo?.let { PreparedCandidate(item, requireNotNull(it.id)) }
        }
        if (candidates.size !in 1..MAX_AI_CANDIDATES ||
            (requiredItemIds != null && candidates.none { it.item.id !in requiredItemIds })
        ) {
            throw AiOutfitException(AiOutfitFailure.INVALID_REQUEST)
        }

        PreparedCandidates(
            OutfitSelectionWeather(
                request.weather.temperatureC,
                request.weather.windSpeedMps,
                precipitationType.code,
                precipitationType.name,
            ),
            candidates,
            request,
            requiredItemIds,
            preferences.findByOwnerId(ownerId)?.let {
                OutfitSelectionStylePreferences(
                    it.preferredColorsAndMaterials,
                    it.avoidances,
                    it.additionalWishes,
                )
            },
        )
    }

    private fun loadImages(
        ownerId: Long,
        candidates: List<PreparedCandidate>,
    ): List<OutfitSelectionCandidate> =
        candidates.map { candidate ->
            requireAiAccess(ownerId)
            val itemId = requireNotNull(candidate.item.id)
            val content =
                try {
                    photoContent.getContent(ownerId, itemId, candidate.photoId)
                } catch (_: EntityNotFoundException) {
                    throw AiOutfitException(AiOutfitFailure.SOURCE_CHANGED)
                }
            requireAiAccess(ownerId)
            OutfitSelectionCandidate(
                itemId = itemId,
                name = candidate.item.name,
                categoryCode = candidate.item.category.code,
                categoryName = candidate.item.category.name,
                color = candidate.item.color,
                material = candidate.item.material,
                contentType = content.contentType,
                imageBase64 = Base64.getEncoder().encodeToString(content.bytes),
            )
        }

    private fun saveOutfit(
        ownerId: Long,
        request: CreateAiOutfitRequest,
        candidates: List<PreparedCandidate>,
        selectedItemIds: List<Long>,
        explanation: String?,
        sourceOutfitId: Long?,
    ): OutfitResponse {
        val candidateIds = candidates.map { requireNotNull(it.item.id) }
        return transaction.execute {
            if (items.lockOwnedItemsForShare(ownerId, candidateIds).size != candidates.size) {
                throw AiOutfitException(AiOutfitFailure.SOURCE_CHANGED)
            }
            candidates.forEach {
                if (
                    photos.findByIdAndItemIdAndItemOwnerId(
                        it.photoId,
                        requireNotNull(it.item.id),
                        ownerId,
                    ) == null
                ) {
                    throw AiOutfitException(AiOutfitFailure.SOURCE_CHANGED)
                }
            }

            outfits.createAi(
                ownerId,
                CreateOutfitRequest(request.name, selectedItemIds, request.weather),
                request.occasion,
                request.preferredStyle,
                explanation,
                sourceOutfitId,
            )
        }
    }

    private fun requireAiAccess(ownerId: Long) {
        val user =
            try {
                currentUsers.requireActive(ownerId)
            } catch (_: EntityNotFoundException) {
                throw AccessDeniedException("Access denied")
            }
        if (user.role != UserRole.USER) {
            throw AccessDeniedException("Access denied")
        }
        if (ownerLogin.isBlank() || user.login != ownerLogin) {
            throw AiOutfitException(AiOutfitFailure.UNAVAILABLE)
        }
    }

    private fun validateIds(ids: List<Long?>) {
        if (ids.isEmpty() || ids.any { it == null || it <= 0 } || ids.distinct().size != ids.size) {
            throw AiOutfitException(AiOutfitFailure.INVALID_REQUEST)
        }
    }

    private fun validateSelection(response: OutfitSelectionResponse, candidateIds: Set<Long>, requiredIds: Set<Long>?) {
        val selectedIds = response.itemIds
        if (
            selectedIds.isEmpty() ||
                selectedIds.distinct().size != selectedIds.size ||
                selectedIds.any { it !in candidateIds } ||
                (requiredIds != null && (!selectedIds.containsAll(requiredIds) || selectedIds.none { it !in requiredIds }))
        ) {
            throw AiOutfitException(AiOutfitFailure.INVALID_RESPONSE)
        }
    }

    private data class PreparedCandidate(val item: WardrobeItem, val photoId: Long)

    private data class PreparedCandidates(
        val weather: OutfitSelectionWeather,
        val candidates: List<PreparedCandidate>,
        val request: CreateAiOutfitRequest,
        val requiredItemIds: Set<Long>?,
        val stylePreferences: OutfitSelectionStylePreferences?,
    )

    companion object {
        private const val MAX_AI_CANDIDATES = 20
    }
}
