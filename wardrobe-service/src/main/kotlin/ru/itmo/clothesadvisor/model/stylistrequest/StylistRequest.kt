package ru.itmo.clothesadvisor.model.stylistrequest

import java.math.BigDecimal
import java.time.Instant
import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Embedded
import org.springframework.data.relational.core.mapping.Table

internal enum class StylistRequestStatus { PENDING, CANCELLED, ANSWERED }

internal data class StylistRequestWeather(
    val temperatureC: BigDecimal,
    val precipitationTypeId: Long,
    val windSpeedMps: BigDecimal,
)

@Table("stylist_request")
internal data class StylistRequest(
    @Id val id: Long,
    val ownerId: Long,
    val stylistId: Long,
    val wishes: String,
    val status: StylistRequestStatus,
    val version: Long,
    val createdAt: Instant,
    val modifiedAt: Instant,
    @Embedded(onEmpty = Embedded.OnEmpty.USE_EMPTY, prefix = "weather_")
    val weather: StylistRequestWeather,
)

@Table("stylist_request_answer")
internal data class StylistRequestAnswer(
    @Id val requestId: Long,
    val outfitId: Long,
    val explanation: String,
    val createdAt: Instant,
)
