package ru.itmo.clothesadvisor.model.weather

import jakarta.validation.constraints.NotBlank

internal class PrecipitationType(code: String, name: String) {
    var id: Long? = null
        internal set

    @field:NotBlank
    var code: String = code
        internal set

    @field:NotBlank
    var name: String = name
        internal set
}
