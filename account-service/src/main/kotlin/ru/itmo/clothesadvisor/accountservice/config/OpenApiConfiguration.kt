package ru.itmo.clothesadvisor.accountservice.config

import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.Components
import io.swagger.v3.oas.models.security.SecurityRequirement
import io.swagger.v3.oas.models.security.SecurityScheme
import io.swagger.v3.oas.models.info.Info
import io.swagger.v3.oas.models.servers.Server
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
internal class OpenApiConfiguration {
    @Bean
    fun accountApi(): OpenAPI =
        OpenAPI()
            .components(Components().addSecuritySchemes("bearerAuth", SecurityScheme()
                .type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")))
            .addSecurityItem(SecurityRequirement().addList("bearerAuth"))
            .info(Info().title("Account API").version("1"))
            .addServersItem(Server().url("/"))
}
