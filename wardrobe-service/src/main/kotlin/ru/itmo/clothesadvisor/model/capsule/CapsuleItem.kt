package ru.itmo.clothesadvisor.model.capsule

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Embedded
import org.springframework.data.relational.core.mapping.Table

internal data class CapsuleItemId(val capsuleId: Long, val wardrobeItemId: Long)

@Table("capsule_item")
internal class CapsuleItem(
    @Id @Embedded.Empty val id: CapsuleItemId,
    val position: Int,
)
