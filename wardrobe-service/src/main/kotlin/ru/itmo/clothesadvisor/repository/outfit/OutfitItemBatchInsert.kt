package ru.itmo.clothesadvisor.repository.outfit

import ru.itmo.clothesadvisor.model.outfit.OutfitItem

internal interface OutfitItemBatchInsert {
    fun insertAll(items: Iterable<OutfitItem>)
}
