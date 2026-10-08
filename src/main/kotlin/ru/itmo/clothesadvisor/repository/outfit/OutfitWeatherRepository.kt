package ru.itmo.clothesadvisor.repository.outfit

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import ru.itmo.clothesadvisor.model.outfit.OutfitWeather

@Repository
internal class OutfitWeatherRepository(private val jdbc: JdbcTemplate) {
    fun save(weather: OutfitWeather) {
        jdbc.update("INSERT INTO outfit_weather (outfit_id, temperature_c, precipitation_type_id, wind_speed_mps) VALUES (?, ?, ?, ?)",
            weather.id, weather.temperatureC, weather.precipitationTypeId, weather.windSpeedMps)
    }

    fun findAllByOutfitIdIn(outfitIds: Collection<Long>): List<OutfitWeather> {
        if (outfitIds.isEmpty()) return emptyList()
        return jdbc.query("SELECT * FROM outfit_weather WHERE outfit_id IN (${outfitIds.joinToString { "?" }})",
            { row, _ -> OutfitWeather(row.getLong("outfit_id"), row.getBigDecimal("temperature_c"),
                row.getLong("precipitation_type_id"), row.getBigDecimal("wind_speed_mps")) }, *outfitIds.toTypedArray())
    }
}
