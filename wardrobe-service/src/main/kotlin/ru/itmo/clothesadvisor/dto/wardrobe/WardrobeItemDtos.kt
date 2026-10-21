package ru.itmo.clothesadvisor.dto.wardrobe

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Positive
import jakarta.validation.constraints.Size
import java.time.Instant

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
)
