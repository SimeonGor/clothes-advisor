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
import ru.itmo.clothesadvisor.dto.outfit.CreateOutfitRequest
import ru.itmo.clothesadvisor.dto.outfit.OutfitResponse
import ru.itmo.clothesadvisor.dto.recommendation.OutfitSelectionCandidate
import ru.itmo.clothesadvisor.dto.recommendation.OutfitSelectionRequest
import ru.itmo.clothesadvisor.dto.recommendation.OutfitSelectionResponse
import ru.itmo.clothesadvisor.dto.recommendation.OutfitSelectionWeather
import ru.itmo.clothesadvisor.model.AccessDeniedException
import ru.itmo.clothesadvisor.model.EntityNotFoundException
import ru.itmo.clothesadvisor.model.ai.AiOutfitException
import ru.itmo.clothesadvisor.model.ai.AiOutfitFailure
import ru.itmo.clothesadvisor.model.user.UserRole
import ru.itmo.clothesadvisor.model.wardrobe.WardrobeItem
import ru.itmo.clothesadvisor.repository.wardrobe.WardrobeItemPhotoRepository
import ru.itmo.clothesadvisor.repository.wardrobe.WardrobeItemRepository
import ru.itmo.clothesadvisor.repository.weather.PrecipitationTypeRepository
import ru.itmo.clothesadvisor.service.outfit.OutfitService
import ru.itmo.clothesadvisor.service.user.CurrentUserService
import ru.itmo.clothesadvisor.service.wardrobe.WardrobeItemPhotoService

@Service
internal class AiOutfitService(
    private val currentUsers: CurrentUserService,
    private val items: WardrobeItemRepository,
    private val photos: WardrobeItemPhotoRepository,
    private val precipitation: PrecipitationTypeRepository,
    private val photoContent: WardrobeItemPhotoService,
    private val recommendation: RecommendationClient,
    private val outfits: OutfitService,
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

    fun create(ownerId: Long, request: CreateAiOutfitRequest): OutfitResponse {
        requireAiAccess(ownerId)
        if (!active.compareAndSet(false, true)) {
            rejected.increment()
            throw AiOutfitException(AiOutfitFailure.UNAVAILABLE)
        }

        val startedAt = System.nanoTime()
        var succeeded = false
        try {
            val prepared = prepareCandidates(ownerId, request)
            val selection =
                OutfitSelectionRequest(prepared.weather, loadImages(ownerId, prepared.candidates))
            requireAiAccess(ownerId)
            val response = recommendation.select(selection)
            validateSelection(response, prepared.candidates.map { requireNotNull(it.item.id) }.toSet())
            requireAiAccess(ownerId)

            val saved = saveOutfit(ownerId, request, prepared.candidates, response.itemIds)
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
    ): PreparedCandidates = transaction.execute {
        val ids = request.candidateItemIds
        if (
            ids != null &&
                (ids.isEmpty() ||
                    ids.any { it == null || it <= 0 } ||
                    ids.distinct().size != ids.size)
        ) {
            throw AiOutfitException(AiOutfitFailure.INVALID_REQUEST)
        }
        val precipitationType =
            precipitation.findById(request.weather.precipitationTypeId)
                ?: throw AiOutfitException(AiOutfitFailure.INVALID_REQUEST)
        val candidateItems =
            if (ids == null)
                items.findOwnedItemsWithPhotos(ownerId, MAX_AI_CANDIDATES + 1).also {
                    if (it.size > MAX_AI_CANDIDATES) {
                        throw AiOutfitException(AiOutfitFailure.INVALID_REQUEST)
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
            }

        val candidates = candidateItems.mapNotNull { item ->
            val id = requireNotNull(item.id)
            photos.findFirstByItemIdOrderByIdAsc(id)?.let { photo ->
                PreparedCandidate(item, requireNotNull(photo.id))
            }
        }
        if (candidates.size !in 1..MAX_AI_CANDIDATES) {
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

    private fun validateSelection(response: OutfitSelectionResponse, candidateIds: Set<Long>) {
        val selectedIds = response.itemIds
        if (
            selectedIds.isEmpty() ||
                selectedIds.distinct().size != selectedIds.size ||
                selectedIds.any { it !in candidateIds }
        ) {
            throw AiOutfitException(AiOutfitFailure.INVALID_RESPONSE)
        }
    }

    private data class PreparedCandidate(val item: WardrobeItem, val photoId: Long)

    private data class PreparedCandidates(
        val weather: OutfitSelectionWeather,
        val candidates: List<PreparedCandidate>,
    )

    companion object {
        private const val MAX_AI_CANDIDATES = 20
    }
}
