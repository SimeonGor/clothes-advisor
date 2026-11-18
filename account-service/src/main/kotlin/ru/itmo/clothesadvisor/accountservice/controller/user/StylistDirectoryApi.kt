package ru.itmo.clothesadvisor.accountservice.controller.user

import io.swagger.v3.oas.annotations.Operation
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import reactor.core.publisher.Flux
import ru.itmo.clothesadvisor.accountservice.dto.access.AccessRecipientResponse

internal interface StylistDirectoryApi {
    @GetMapping("/api/stylists")
    @Operation(summary = "Действующие стилисты", description = "По возрастанию ID, без X-Total-Count.")
    fun list(
        @RequestParam(defaultValue = "0") @Min(0) page: Int,
        @RequestParam(defaultValue = "50") @Min(1) @Max(50) size: Int,
    ): Flux<AccessRecipientResponse>
}
