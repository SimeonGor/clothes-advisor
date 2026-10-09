package ru.itmo.clothesadvisor.repository.outfit

import org.springframework.data.jdbc.repository.query.Query
import org.springframework.data.repository.Repository
import ru.itmo.clothesadvisor.model.outfit.OutfitItem
import ru.itmo.clothesadvisor.model.outfit.OutfitItemId

internal interface OutfitItemRepository : Repository<OutfitItem, OutfitItemId>, OutfitItemBatchInsert {
    fun findAllByIdOutfitIdInOrderByPositionAsc(outfitIds: Collection<Long>): List<OutfitItem> =
        if (outfitIds.isEmpty()) emptyList() else findRows(outfitIds)

    @Query("SELECT * FROM outfit_item WHERE outfit_id IN (:outfitIds) ORDER BY position")
    fun findRows(outfitIds: Collection<Long>): List<OutfitItem>
}
