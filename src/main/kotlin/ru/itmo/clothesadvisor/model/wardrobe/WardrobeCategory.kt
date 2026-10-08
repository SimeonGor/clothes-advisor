package ru.itmo.clothesadvisor.model.wardrobe

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.validation.constraints.NotBlank

@Entity
@Table(name = "wardrobe_category")
internal class WardrobeCategory(code: String, name: String) {
    @field:Id
    @field:GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
        protected set

    @field:NotBlank
    @field:Column(nullable = false, unique = true, columnDefinition = "text")
    var code: String = code
        protected set

    @field:NotBlank
    @field:Column(nullable = false, columnDefinition = "text")
    var name: String = name
        protected set
}
