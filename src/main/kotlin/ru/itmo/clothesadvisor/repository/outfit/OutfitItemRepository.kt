package ru.itmo.clothesadvisor.repository.outfit

import org.springframework.data.repository.Repository
import ru.itmo.clothesadvisor.model.outfit.OutfitItem
import ru.itmo.clothesadvisor.model.outfit.OutfitItemId

internal interface OutfitItemRepository : Repository<OutfitItem, OutfitItemId> {
    fun saveAll(items: Iterable<OutfitItem>): List<OutfitItem>
    fun findAllByKeyOutfitIdInOrderByPositionAsc(outfitIds: Collection<Long>): List<OutfitItem>
}
