package ru.itmo.clothesadvisor.controller.capsule

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
import org.springframework.web.bind.annotation.DeleteMapping
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
import ru.itmo.clothesadvisor.dto.capsule.CreateCapsuleRequest
import ru.itmo.clothesadvisor.dto.user.CurrentUser
import ru.itmo.clothesadvisor.service.capsule.ReactiveCapsuleService

@Tag(name = "Капсулы")
@RestController
@RequestMapping("/api/me/capsules")
@ApiResponses(
    ApiResponse(responseCode = "404", description = "Своя капсула или вещь не найдены", content = [Content()]),
    ApiResponse(responseCode = "503", description = "Account недоступен", content = [Content()]),
)
internal class CapsuleController(private val capsules: ReactiveCapsuleService) {
    @PostMapping
    @Operation(summary = "Создать капсулу", description = "Только USER; неизменяемый набор 1–20 своих вещей. Доступны вещи в любом состоянии. Idempotency-Key необязателен.")
    @ApiResponses(
        ApiResponse(responseCode = "201", description = "Создано", useReturnTypeSchema = true,
            headers = [Header(name = "Location", schema = Schema(type = "string", format = "uri"))]),
        ApiResponse(responseCode = "409", description = "Удалённая вещь — пустое тело; конфликт Idempotency-Key — код ошибки",
            content = [Content(mediaType = "application/json", schema = Schema(implementation = ApiErrorResponse::class))]),
    )
    fun create(
        @Parameter(hidden = true) user: CurrentUser,
        @Valid @RequestBody request: CreateCapsuleRequest,
        @RequestHeader("Idempotency-Key", required = false) key: String?,
    ) = capsules.create(user, request, key).map {
        ResponseEntity.created(URI("/api/me/capsules/${it.id}")).body(it)
    }

    @GetMapping
    @Operation(summary = "Список своих капсул", description = "Только USER; по ID по убыванию.")
    @ApiResponse(responseCode = "200", description = "Список", useReturnTypeSchema = true,
        headers = [Header(name = "X-Total-Count", schema = Schema(type = "integer", format = "int64"))])
    fun list(
        @Parameter(hidden = true) user: CurrentUser,
        @RequestParam(defaultValue = "0") @Min(0) page: Int,
        @RequestParam(defaultValue = "50") @Min(1) @Max(50) size: Int,
    ) = capsules.list(user, toPageRequest(page, size)).map {
        ResponseEntity.ok().header("X-Total-Count", it.totalElements.toString()).body(it.content)
    }

    @GetMapping("/{id}")
    @Operation(summary = "Прочитать свою капсулу", description = "Только USER.")
    @ApiResponse(responseCode = "200", description = "Капсула", useReturnTypeSchema = true)
    fun get(@Parameter(hidden = true) user: CurrentUser, @PathVariable id: Long) = capsules.get(user, id)

    @DeleteMapping("/{id}")
    @Operation(summary = "Удалить свою капсулу", description = "Только USER; вещи сохраняются.")
    @ApiResponse(responseCode = "204", description = "Удалено", content = [Content()])
    fun delete(@Parameter(hidden = true) user: CurrentUser, @PathVariable id: Long) =
        capsules.delete(user, id).thenReturn(ResponseEntity.noContent().build<Void>())
}
