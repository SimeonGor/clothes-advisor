package ru.itmo.clothesadvisor.model.wardrobe

import jakarta.validation.constraints.NotBlank

internal class WardrobeCategory(code: String, name: String) {
    var id: Long? = null
        internal set

    @field:NotBlank
    var code: String = code
        internal set

    @field:NotBlank
    var name: String = name
        internal set
}
