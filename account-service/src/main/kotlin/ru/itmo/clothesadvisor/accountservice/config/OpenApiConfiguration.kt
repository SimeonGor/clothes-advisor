package ru.itmo.clothesadvisor.accountservice.config

import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import io.swagger.v3.oas.models.servers.Server
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
internal class OpenApiConfiguration {
    @Bean
    fun accountApi(): OpenAPI =
        OpenAPI()
            .info(Info().title("Account API").version("1"))
            .addServersItem(Server().url("/"))
}
