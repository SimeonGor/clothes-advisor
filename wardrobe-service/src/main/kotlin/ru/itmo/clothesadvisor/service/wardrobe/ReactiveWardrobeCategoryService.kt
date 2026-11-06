package ru.itmo.clothesadvisor.service.wardrobe

import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Service
import reactor.core.publisher.Mono
import ru.itmo.clothesadvisor.model.wardrobe.WardrobeCategory
import ru.itmo.clothesadvisor.service.blockingCall

@Service
internal class ReactiveWardrobeCategoryService(private val categories: WardrobeCategoryService) {
    fun list(pageable: Pageable): Mono<List<WardrobeCategory>> =
        blockingCall { categories.list(pageable) }
}
