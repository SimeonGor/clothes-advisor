package ru.itmo.clothesadvisor.model.wardrobe

import jakarta.validation.constraints.NotBlank
import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table

@Table("wardrobe_category")
internal class WardrobeCategory(code: String, name: String) {
    @Id
    var id: Long? = null
        internal set

    @field:NotBlank
    var code: String = code
        internal set

    @field:NotBlank
    var name: String = name
        internal set
}
