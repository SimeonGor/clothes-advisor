package ru.itmo.clothesadvisor.repository.outfit

import org.springframework.data.repository.Repository
import ru.itmo.clothesadvisor.model.outfit.OutfitWeather

internal interface OutfitWeatherRepository : Repository<OutfitWeather, Long> {
    fun save(weather: OutfitWeather): OutfitWeather
    fun findAllByOutfitIdIn(outfitIds: Collection<Long>): List<OutfitWeather>
}
