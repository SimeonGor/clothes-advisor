package ru.itmo.clothesadvisor.model.capsule

import java.math.BigDecimal
import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.relational.core.mapping.Table

@Table("capsule_weather")
internal class CapsuleWeather(
    @Id @Column("capsule_id") val id: Long,
    val temperatureC: BigDecimal,
    val precipitationTypeId: Long,
    val windSpeedMps: BigDecimal,
)
