package ru.itmo.clothesadvisor.dto.outfit

import com.fasterxml.jackson.annotation.JsonUnwrapped
import io.swagger.v3.oas.annotations.media.ArraySchema
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.Valid
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.math.BigDecimal
import java.time.Instant
import ru.itmo.clothesadvisor.dto.rating.RatingResponse
import ru.itmo.clothesadvisor.model.outfit.OutfitSource

internal class CreateOutfitRequest(
    @field:NotBlank @field:Size(max = 300) val name: String,
    @field:ArraySchema(
        minItems = 1,
        maxItems = 50,
        uniqueItems = true,
        schema = Schema(type = "integer", format = "int64", minimum = "1", nullable = false),
        arraySchema =
            Schema(
                description = "Различные положительные ID вещей владельца; null недопустим",
                requiredMode = Schema.RequiredMode.REQUIRED,
            ),
    )
    val itemIds: List<Long?>,
    @field:Valid val weather: OutfitWeatherDto,
)

internal data class OutfitWeatherDto(
    @field:Schema(
        types = ["number"],
        description = "Температура в °C, допускает дробные и отрицательные значения",
    )
    val temperatureC: BigDecimal,
    val precipitationTypeId: Long,
    @field:Schema(types = ["number"], description = "Скорость ветра в м/с")
    @field:DecimalMin("0")
    val windSpeedMps: BigDecimal,
)

internal data class OutfitResponse(
    val id: Long,
    val ownerId: Long,
    val authorId: Long,
    val source: OutfitSource,
    val name: String,
    val itemIds: List<Long>,
    val weather: OutfitWeatherDto,
    val createdAt: Instant,
    val likes: Long = 0,
    val dislikes: Long = 0,
)

internal data class StylistOutfitResponse(
    @get:JsonUnwrapped val outfit: OutfitResponse,
    @field:Schema(
        nullable = true,
        requiredMode = Schema.RequiredMode.REQUIRED,
        description = "Текущая оценка этого стилиста или null; только в ответах стилисту",
    )
    val myRating: RatingResponse?,
)
