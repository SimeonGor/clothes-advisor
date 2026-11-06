package ru.itmo.clothesadvisor.repository.capsule

import org.springframework.data.jdbc.repository.query.Query
import org.springframework.data.repository.Repository
import ru.itmo.clothesadvisor.model.capsule.CapsuleItem
import ru.itmo.clothesadvisor.model.capsule.CapsuleItemId

internal interface CapsuleItemRepository : Repository<CapsuleItem, CapsuleItemId>, CapsuleItemBatchInsert {
    @Query("SELECT * FROM capsule_item WHERE capsule_id IN (:capsuleIds) ORDER BY position")
    fun findRows(capsuleIds: Collection<Long>): List<CapsuleItem>
}
