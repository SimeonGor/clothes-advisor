package ru.itmo.clothesadvisor.model.outfit

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.math.BigDecimal
import org.springframework.data.domain.Persistable

@Entity
@Table(name = "outfit_weather")
internal class OutfitWeather(
    @field:Id
    @field:Column(name = "outfit_id")
    private val outfitId: Long,
    @field:Column(name = "temperature_c", nullable = false, columnDefinition = "numeric")
    val temperatureC: BigDecimal,
    @field:Column(name = "precipitation_type_id", nullable = false)
    val precipitationTypeId: Long,
    @field:Column(name = "wind_speed_mps", nullable = false, columnDefinition = "numeric")
    val windSpeedMps: BigDecimal,
) : Persistable<Long> {
    override fun getId(): Long = outfitId
    override fun isNew(): Boolean = true
}
