package ru.itmo.clothesadvisor.service.ai

import ru.itmo.clothesadvisor.model.EntityNotFoundException
import org.springframework.beans.factory.annotation.Value
import ru.itmo.clothesadvisor.model.AccessDeniedException
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import ru.itmo.clothesadvisor.client.ai.AiCandidate
import ru.itmo.clothesadvisor.client.ai.AiCandidateImage
import ru.itmo.clothesadvisor.client.ai.AiOutfitException
import ru.itmo.clothesadvisor.client.ai.AiOutfitFailure
import ru.itmo.clothesadvisor.client.ai.AiWeather
import ru.itmo.clothesadvisor.client.ai.OpenAiOutfitClient
import ru.itmo.clothesadvisor.dto.ai.CreateAiOutfitRequest
import ru.itmo.clothesadvisor.dto.outfit.CreateOutfitRequest
import ru.itmo.clothesadvisor.dto.outfit.OutfitResponse
import ru.itmo.clothesadvisor.model.user.UserRole
import ru.itmo.clothesadvisor.model.user.UserStatus
import ru.itmo.clothesadvisor.repository.user.AppUserRepository
import ru.itmo.clothesadvisor.repository.wardrobe.WardrobeItemPhotoRepository
import ru.itmo.clothesadvisor.repository.wardrobe.WardrobeItemRepository
import ru.itmo.clothesadvisor.repository.weather.PrecipitationTypeRepository
import ru.itmo.clothesadvisor.service.outfit.OutfitService
import ru.itmo.clothesadvisor.service.wardrobe.WardrobeItemPhotoService

@Service
internal class AiOutfitService(
    private val users: AppUserRepository,
    private val items: WardrobeItemRepository,
    private val photos: WardrobeItemPhotoRepository,
    private val precipitation: PrecipitationTypeRepository,
    private val photoContent: WardrobeItemPhotoService,
    private val provider: OpenAiOutfitClient,
    private val outfits: OutfitService,
    transactionManager: PlatformTransactionManager,
    @Value("\${app.openai.owner-login:user}") private val ownerLogin: String,
) {
    private val transaction = TransactionTemplate(transactionManager).apply {
        isolationLevel = TransactionDefinition.ISOLATION_READ_COMMITTED
    }

    fun create(ownerId: Long, request: CreateAiOutfitRequest): OutfitResponse {
        requireActiveUser(ownerId)
        if (ownerLogin.isBlank() || users.findByLogin(ownerLogin)?.id != ownerId) throw AiOutfitException(AiOutfitFailure.UNAVAILABLE)
        provider.requireAvailable()
        val prepared = prepareCandidates(ownerId, request)
        val images = loadImages(ownerId, prepared.candidates)
        requireActiveUser(ownerId)
        val selected = provider.select(prepared.weather, images)
        return saveOutfit(ownerId, request, prepared.candidates, selected)
    }

    private fun prepareCandidates(ownerId: Long, request: CreateAiOutfitRequest): PreparedCandidates =
        transaction.execute {
            requireActiveUser(ownerId)
            val ids = request.candidateItemIds
            if (ids != null && (ids.isEmpty() || ids.any { it == null || it <= 0 } || ids.distinct().size != ids.size)) {
                throw AiOutfitException(AiOutfitFailure.INVALID_REQUEST)
            }
            val condition = precipitation.findById(request.weather.precipitationTypeId) ?: throw AiOutfitException(AiOutfitFailure.INVALID_REQUEST)
            val rows = if (ids == null) items.findOwnedItemsWithPhotos(ownerId, MAX_AI_CANDIDATES + 1).also {
                if (it.size > MAX_AI_CANDIDATES) throw AiOutfitException(AiOutfitFailure.INVALID_REQUEST)
            } else {
                items.findAllByOwnerIdAndIdInOrderByIdAsc(ownerId, ids.map { requireNotNull(it) }).also {
                    if (it.size != ids.size) throw EntityNotFoundException()
                }
            }
            val selected = rows.mapNotNull { item ->
                val id = requireNotNull(item.id)
                photos.findFirstByItemIdOrderByIdAsc(id)?.let { photo ->
                    AiCandidate(id, requireNotNull(photo.id), item.category.code, item.category.name,
                        item.name, item.color, item.material)
                }
            }
            if (selected.size !in 1..MAX_AI_CANDIDATES) throw AiOutfitException(AiOutfitFailure.INVALID_REQUEST)
            PreparedCandidates(AiWeather(request.weather, condition.code, condition.name), selected)
        }

    private fun loadImages(ownerId: Long, candidates: List<AiCandidate>): List<AiCandidateImage> =
        candidates.map { candidate ->
            requireActiveUser(ownerId)
            val content = try {
                photoContent.getContent(ownerId, candidate.id, candidate.photoId)
            } catch (_: EntityNotFoundException) {
                throw AiOutfitException(AiOutfitFailure.SOURCE_CHANGED)
            }
            requireActiveUser(ownerId)
            AiCandidateImage(candidate, content.contentType, content.bytes)
        }

    private fun saveOutfit(
        ownerId: Long,
        request: CreateAiOutfitRequest,
        candidates: List<AiCandidate>,
        selected: List<Long>,
    ): OutfitResponse = transaction.execute {
        if (users.lockActiveUserForShare(ownerId) == null) throw AccessDeniedException("Access denied")
        if (items.lockOwnedItemsForShare(ownerId, candidates.map { it.id }).size != candidates.size) throw AiOutfitException(AiOutfitFailure.SOURCE_CHANGED)
        candidates.forEach {
            if (photos.findByIdAndItemIdAndItemOwnerId(it.photoId, it.id, ownerId) == null) throw AiOutfitException(AiOutfitFailure.SOURCE_CHANGED)
        }
        outfits.createAi(ownerId, CreateOutfitRequest(request.name, selected, request.weather))
    }

    private fun requireActiveUser(ownerId: Long) {
        if (!users.existsByIdAndRoleAndStatus(ownerId, UserRole.USER, UserStatus.ACTIVE)) throw AccessDeniedException("Access denied")
    }

    private data class PreparedCandidates(val weather: AiWeather, val candidates: List<AiCandidate>)

    companion object {
        private const val MAX_AI_CANDIDATES = 20
    }
}
