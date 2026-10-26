package ru.itmo.clothesadvisor.controller.stylistrequest

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.headers.Header
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import java.net.URI
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import ru.itmo.clothesadvisor.controller.toPageRequest
import ru.itmo.clothesadvisor.dto.ApiErrorResponse
import ru.itmo.clothesadvisor.dto.stylistrequest.AnswerStylistRequest
import ru.itmo.clothesadvisor.dto.user.CurrentUser
import ru.itmo.clothesadvisor.service.stylistrequest.ReactiveStylistRequestService

@Tag(name = "Запросы клиента", description = "Только назначенный STYLIST с действующим доступом.")
@RestController
@RequestMapping("/api/stylist/clients/{ownerId}/requests")
@ApiResponse(responseCode = "503", description = "Account недоступен", content = [Content()])
internal class StylistClientRequestController(private val requests: ReactiveStylistRequestService) {
    @PostMapping("/{id}/answer")
    @Operation(summary = "Ответить новым образом", description = "Текущая version и PENDING; погода из запроса. Недоступные вещи разрешены для ручного образа.")
    @ApiResponses(
        ApiResponse(responseCode = "201", description = "Ответ и образ созданы", useReturnTypeSchema = true,
            headers = [Header(name = "Location", schema = Schema(type = "string", format = "uri"))]),
        ApiResponse(responseCode = "409", description = "Конфликт версии/состояния — пустое тело; Idempotency-Key — код ошибки",
            content = [Content(mediaType = "application/json", schema = Schema(implementation = ApiErrorResponse::class))]),
    )
    fun answer(
        @Parameter(hidden = true) user: CurrentUser,
        @PathVariable ownerId: Long,
        @PathVariable id: Long,
        @Valid @RequestBody request: AnswerStylistRequest,
        @RequestHeader("Idempotency-Key", required = false) key: String?,
    ) = requests.answer(user, ownerId, id, request, key).map {
        ResponseEntity.created(URI("/api/stylist/clients/$ownerId/requests/${it.id}")).body(it)
    }

    @GetMapping
    @Operation(summary = "Запросы клиента этому стилисту", description = "По ID по убыванию; общее число в X-Total-Count.")
    @ApiResponse(responseCode = "200", description = "Список", useReturnTypeSchema = true,
        headers = [Header(name = "X-Total-Count", schema = Schema(type = "integer", format = "int64"))])
    fun list(
        @Parameter(hidden = true) user: CurrentUser,
        @PathVariable ownerId: Long,
        @RequestParam(defaultValue = "0") @Min(0) page: Int,
        @RequestParam(defaultValue = "50") @Min(1) @Max(50) size: Int,
    ) = requests.listForClient(user, ownerId, toPageRequest(page, size)).map {
        ResponseEntity.ok().header("X-Total-Count", it.totalElements.toString()).body(it.content)
    }

    @GetMapping("/{id}")
    @Operation(summary = "Прочитать адресованный стилисту запрос")
    @ApiResponse(responseCode = "200", description = "Запрос", useReturnTypeSchema = true)
    fun get(@Parameter(hidden = true) user: CurrentUser, @PathVariable ownerId: Long, @PathVariable id: Long) =
        requests.getForClient(user, ownerId, id)
}
