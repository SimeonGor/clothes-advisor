package ru.itmo.clothesadvisor.config

import io.swagger.v3.oas.models.Components
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import io.swagger.v3.oas.models.media.Content
import io.swagger.v3.oas.models.media.MediaType
import io.swagger.v3.oas.models.media.Schema
import io.swagger.v3.oas.models.responses.ApiResponse
import io.swagger.v3.oas.models.security.SecurityRequirement
import io.swagger.v3.oas.models.security.SecurityScheme
import io.swagger.v3.oas.models.servers.Server
import org.springdoc.core.customizers.OperationCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile

@Configuration
@Profile("local")
internal class OpenApiConfiguration {
    @Bean
    fun clothesAdvisorApi(): OpenAPI =
        OpenAPI()
            .components(Components().addSecuritySchemes("bearerAuth", SecurityScheme()
                .type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")))
            .addSecurityItem(SecurityRequirement().addList("bearerAuth"))
            .info(Info().title("Wardrobe API").version("1").description(
                "Все API, включая справочники, требуют Bearer JWT и действующий аккаунт. " +
                    "Списки возвращаются массивами JSON; page начинается с 0, size по умолчанию 50 (1..50). " +
                    "Ошибки JSON/DTO дают 400; недействующий токен или удалённый пользователь — 401, " +
                    "заблокированный пользователь — 403, недоступный account — 503.",
            ))
            .addServersItem(Server().url("/"))

    @Bean
    fun commonErrorResponses(): OperationCustomizer = OperationCustomizer { operation, _ ->
        mapOf(
            "400" to "Некорректные параметры или тело запроса: JSON invalid_request.",
            "401" to "Нет действующего JWT или аккаунт удалён: JSON unauthorized.",
            "403" to "Аккаунт заблокирован или операция запрещена: JSON forbidden.",
            "503" to "Сервис аккаунтов недоступен.",
        ).forEach { (code, description) ->
            val response = operation.responses.getOrPut(code) { ApiResponse() }
            response.description = listOfNotNull(response.description, description)
                .distinct().joinToString(" ")
            val content = response.content ?: Content().also { response.content = it }
            content.putIfAbsent(
                "application/json",
                MediaType().schema(Schema<Any>().`$ref`("#/components/schemas/ApiErrorResponse")),
            )
        }
        operation
    }
}
