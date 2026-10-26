package ru.itmo.clothesadvisor.repository.capsule

import ru.itmo.clothesadvisor.model.capsule.CapsuleItem

internal interface CapsuleItemBatchInsert {
    fun insertAll(items: Iterable<CapsuleItem>)
}
