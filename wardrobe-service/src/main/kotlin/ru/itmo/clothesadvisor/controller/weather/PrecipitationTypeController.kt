package ru.itmo.clothesadvisor.controller.weather

import org.springframework.web.bind.annotation.RestController
import reactor.core.publisher.Mono
import ru.itmo.clothesadvisor.controller.toPageRequest
import ru.itmo.clothesadvisor.dto.weather.PrecipitationTypeResponse
import ru.itmo.clothesadvisor.service.weather.ReactivePrecipitationTypeService

@RestController
internal class PrecipitationTypeController(
    private val precipitationTypes: ReactivePrecipitationTypeService,
) : PrecipitationTypeApi {
    override fun list(
        page: Int,
        size: Int,
    ): Mono<List<PrecipitationTypeResponse>> =
        precipitationTypes.list(toPageRequest(page, size)).map { types ->
            types.map {
                PrecipitationTypeResponse(requireNotNull(it.id), it.code, it.name)
            }
        }
}
