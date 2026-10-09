package ru.itmo.clothesadvisor.model.outfit

import java.math.BigDecimal
import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.relational.core.mapping.Table

@Table("outfit_weather")
internal class OutfitWeather(
    @Id @Column("outfit_id") val id: Long,
    val temperatureC: BigDecimal,
    val precipitationTypeId: Long,
    val windSpeedMps: BigDecimal,
)
