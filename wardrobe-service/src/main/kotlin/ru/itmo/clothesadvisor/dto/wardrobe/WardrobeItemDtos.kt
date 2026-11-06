package ru.itmo.clothesadvisor.dto.wardrobe

import com.fasterxml.jackson.annotation.JsonInclude
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Positive
import jakarta.validation.constraints.Size
import java.time.Instant
import ru.itmo.clothesadvisor.model.wardrobe.WardrobeItemAvailability

internal class CreateWardrobeItemRequest(
    @field:NotBlank @field:Size(max = 300) val name: String,
    @field:Positive val categoryId: Long,
    @field:NotBlank @field:Size(max = 100) val color: String,
    @field:NotBlank @field:Size(max = 100) val material: String,
)

internal class UpdateWardrobeItemRequest(
    @field:Positive val version: Long,
    @field:NotBlank @field:Size(max = 300) val name: String,
    @field:Positive val categoryId: Long,
    @field:NotBlank @field:Size(max = 100) val color: String,
    @field:NotBlank @field:Size(max = 100) val material: String,
    val availability: WardrobeItemAvailability? = null,
)

internal data class WardrobeItemResponse(
    val id: Long,
    val name: String,
    val categoryId: Long,
    val color: String,
    val material: String,
    val version: Long,
    val createdAt: Instant,
    val modifiedAt: Instant,
    @get:JsonInclude(JsonInclude.Include.NON_NULL) val availability: WardrobeItemAvailability? = null,
)
