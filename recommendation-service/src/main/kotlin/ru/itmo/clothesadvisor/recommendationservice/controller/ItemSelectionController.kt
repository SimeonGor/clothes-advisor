package ru.itmo.clothesadvisor.recommendationservice.controller

import org.springframework.http.ResponseEntity
import org.springframework.core.io.buffer.DataBuffer
import org.springframework.core.io.buffer.DataBufferUtils
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ServerWebInputException
import reactor.core.publisher.Mono
import reactor.core.publisher.Flux
import reactor.core.scheduler.Schedulers
import ru.itmo.clothesadvisor.client.ai.AiSelectionException
import ru.itmo.clothesadvisor.dto.ai.ItemSelectionRequest
import ru.itmo.clothesadvisor.dto.ai.ItemSelectionResponse
import ru.itmo.clothesadvisor.dto.ai.SelectionPhotos
import ru.itmo.clothesadvisor.client.ai.AiSelectionFailure
import ru.itmo.clothesadvisor.recommendationservice.config.SelectionJsonConfiguration
import ru.itmo.clothesadvisor.recommendationservice.service.ItemSelectionService

@RestController
class ItemSelectionController(private val service: ItemSelectionService) {
    private val reader = SelectionJsonConfiguration.mapper().readerFor(ItemSelectionRequest::class.java)

    @PostMapping("/api/outfit-selections", consumes = ["application/json"])
    fun selectOutfit(@RequestBody body: Flux<DataBuffer>) = select(body, service::selectOutfit)

    @PostMapping("/api/capsule-selections", consumes = ["application/json"])
    fun selectCapsule(@RequestBody body: Flux<DataBuffer>) = select(body, service::selectCapsule)

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
        ResponseEntity.status(error.status).body(mapOf("code" to error.failure.name.lowercase()))

    @ExceptionHandler(ServerWebInputException::class)
    fun invalidBody(): ResponseEntity<Map<String, String>> =
        ResponseEntity.badRequest().body(mapOf("code" to "invalid_request"))
}
