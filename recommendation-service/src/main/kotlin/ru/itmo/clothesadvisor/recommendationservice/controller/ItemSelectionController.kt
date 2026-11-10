package ru.itmo.clothesadvisor.recommendationservice.controller

import org.springframework.http.ResponseEntity
import org.springframework.core.io.buffer.DataBuffer
import org.springframework.core.io.buffer.DataBufferUtils
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ServerWebInputException
import reactor.core.publisher.Mono
import reactor.core.publisher.Flux
import reactor.core.scheduler.Schedulers
import ru.itmo.clothesadvisor.dto.ai.ItemSelectionRequest
import ru.itmo.clothesadvisor.dto.ai.ItemSelectionResponse
import ru.itmo.clothesadvisor.dto.ai.SelectionPhotos
import ru.itmo.clothesadvisor.recommendationservice.model.ai.AiSelectionException
import ru.itmo.clothesadvisor.recommendationservice.model.ai.AiSelectionFailure
import ru.itmo.clothesadvisor.recommendationservice.config.SelectionJsonConfiguration
import ru.itmo.clothesadvisor.recommendationservice.service.ItemSelectionService

@RestController
class ItemSelectionController(private val service: ItemSelectionService) : ItemSelectionApi {
    private val reader = SelectionJsonConfiguration.mapper().readerFor(ItemSelectionRequest::class.java)

    override fun selectOutfit(body: Flux<DataBuffer>) = select(body, service::selectOutfit)

    override fun selectCapsule(body: Flux<DataBuffer>) = select(body, service::selectCapsule)

    private fun select(body: Flux<DataBuffer>, action: (ItemSelectionRequest) -> ItemSelectionResponse) =
        Mono.fromCallable {
            try {
                SelectionPhotos().use { photos ->
                    DataBufferUtils.subscriberInputStream(body, 1).use { input ->
                        val request = try {
                            reader.withAttribute(SelectionPhotos::class.java, photos)
                                .readValue<ItemSelectionRequest?>(input)
                                ?: throw AiSelectionException(AiSelectionFailure.INVALID_REQUEST)
                        } catch (error: tools.jackson.core.JacksonException) {
                            throw generateSequence<Throwable>(error) { it.cause }
                                .filterIsInstance<AiSelectionException>().firstOrNull()
                                ?: AiSelectionException(AiSelectionFailure.INVALID_REQUEST)
                        }
                        action(request)
                    }
                }
            } catch (_: java.io.IOException) {
                throw AiSelectionException(AiSelectionFailure.UNAVAILABLE)
            }
        }.subscribeOn(Schedulers.boundedElastic())

    @ExceptionHandler(AiSelectionException::class)
    fun selectionFailure(error: AiSelectionException): ResponseEntity<Map<String, String>> =
        ResponseEntity.status(error.failure.httpStatus).body(mapOf("code" to error.failure.name.lowercase()))

    @ExceptionHandler(ServerWebInputException::class)
    fun invalidBody(): ResponseEntity<Map<String, String>> =
        ResponseEntity.badRequest().body(mapOf("code" to "invalid_request"))
}

private val AiSelectionFailure.httpStatus: Int
    get() = when (this) {
        AiSelectionFailure.INVALID_REQUEST -> 400
        AiSelectionFailure.NO_SELECTION,
        AiSelectionFailure.NO_OUTFIT,
        AiSelectionFailure.NO_CAPSULE -> 422
        AiSelectionFailure.INVALID_RESPONSE -> 502
        AiSelectionFailure.UNAVAILABLE -> 503
        AiSelectionFailure.TIMEOUT -> 504
    }
