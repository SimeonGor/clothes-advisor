package ru.itmo.clothesadvisor.repository.capsule

import org.springframework.data.jdbc.repository.query.Modifying
import org.springframework.data.jdbc.repository.query.Query
import org.springframework.data.repository.Repository
import ru.itmo.clothesadvisor.model.capsule.CapsuleWeather

internal interface CapsuleWeatherRepository : Repository<CapsuleWeather, Long> {
    @Modifying
    @Query("""
        INSERT INTO capsule_weather (capsule_id, temperature_c, precipitation_type_id, wind_speed_mps)
        VALUES (:#{#weather.id}, :#{#weather.temperatureC}, :#{#weather.precipitationTypeId}, :#{#weather.windSpeedMps})
    """)
    fun insert(weather: CapsuleWeather)

    fun findAllByIdIn(capsuleIds: Collection<Long>): List<CapsuleWeather> =
        if (capsuleIds.isEmpty()) emptyList() else findRows(capsuleIds)

    @Query("SELECT * FROM capsule_weather WHERE capsule_id IN (:capsuleIds)")
    fun findRows(capsuleIds: Collection<Long>): List<CapsuleWeather>
}
