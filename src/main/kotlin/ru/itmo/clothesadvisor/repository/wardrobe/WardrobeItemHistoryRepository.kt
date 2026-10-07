package ru.itmo.clothesadvisor.repository.wardrobe

import org.springframework.data.repository.Repository
import ru.itmo.clothesadvisor.model.wardrobe.WardrobeItemHistory
import ru.itmo.clothesadvisor.model.wardrobe.WardrobeItemHistoryId

internal interface WardrobeItemHistoryRepository : Repository<WardrobeItemHistory, WardrobeItemHistoryId> {
    fun save(history: WardrobeItemHistory): WardrobeItemHistory
}
