package ru.itmo.clothesadvisor.service.weather

import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Service
import reactor.core.publisher.Mono
import ru.itmo.clothesadvisor.model.weather.PrecipitationType
import ru.itmo.clothesadvisor.service.blockingCall

@Service
internal class ReactivePrecipitationTypeService(private val precipitation: PrecipitationTypeService) {
    fun list(pageable: Pageable): Mono<List<PrecipitationType>> =
        blockingCall { precipitation.list(pageable) }
}
