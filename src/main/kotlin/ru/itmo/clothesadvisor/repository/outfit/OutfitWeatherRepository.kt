package ru.itmo.clothesadvisor.repository.outfit

import org.springframework.data.jdbc.repository.query.Modifying
import org.springframework.data.jdbc.repository.query.Query
import org.springframework.data.repository.Repository
import ru.itmo.clothesadvisor.model.outfit.OutfitWeather

internal interface OutfitWeatherRepository : Repository<OutfitWeather, Long> {
    @Modifying
    @Query("""
        INSERT INTO outfit_weather (outfit_id, temperature_c, precipitation_type_id, wind_speed_mps)
        VALUES (:#{#weather.id}, :#{#weather.temperatureC}, :#{#weather.precipitationTypeId}, :#{#weather.windSpeedMps})
    """)
    fun insert(weather: OutfitWeather)

    fun findAllByIdIn(outfitIds: Collection<Long>): List<OutfitWeather> =
        if (outfitIds.isEmpty()) emptyList() else findRows(outfitIds)

    @Query("SELECT * FROM outfit_weather WHERE outfit_id IN (:outfitIds)")
    fun findRows(outfitIds: Collection<Long>): List<OutfitWeather>
}
