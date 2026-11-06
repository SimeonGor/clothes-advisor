package ru.itmo.clothesadvisor.dto.stylistrequest

import com.fasterxml.jackson.annotation.JsonInclude
import io.swagger.v3.oas.annotations.media.ArraySchema
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Positive
import jakarta.validation.constraints.Size
import java.time.Instant
import ru.itmo.clothesadvisor.dto.outfit.OutfitWeatherDto
import ru.itmo.clothesadvisor.model.stylistrequest.StylistRequestStatus

internal data class CreateStylistRequest(
    @field:Positive val stylistId: Long,
    @field:Valid val weather: OutfitWeatherDto,
    @field:Size(max = 2000) val wishes: String? = null,
)

internal data class CancelStylistRequest(@field:Positive val version: Long)

internal data class AnswerStylistRequest(
    @field:Positive val version: Long,
    @field:NotBlank @field:Size(max = 300) val name: String,
    @field:ArraySchema(
        minItems = 1, maxItems = 50, uniqueItems = true,
        schema = Schema(type = "integer", format = "int64", minimum = "1", nullable = false),
        arraySchema = Schema(description = "Различные положительные ID вещей клиента; null недопустим"),
    )
    val itemIds: List<Long?>,
    @field:NotBlank @field:Size(max = 2000) val explanation: String,
)

internal data class StylistRequestAnswerResponse(val outfitId: Long, val explanation: String, val createdAt: Instant)

internal data class StylistRequestResponse(
    val id: Long,
    val ownerId: Long,
    val stylistId: Long,
    val wishes: String,
    val weather: OutfitWeatherDto,
    val status: StylistRequestStatus,
    val version: Long,
    val createdAt: Instant,
    val modifiedAt: Instant,
    @get:JsonInclude(JsonInclude.Include.NON_NULL) val answer: StylistRequestAnswerResponse? = null,
)
