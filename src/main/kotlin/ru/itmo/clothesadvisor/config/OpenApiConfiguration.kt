package ru.itmo.clothesadvisor.config

import io.swagger.v3.oas.models.Components
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.headers.Header
import io.swagger.v3.oas.models.info.Info
import io.swagger.v3.oas.models.media.Content
import io.swagger.v3.oas.models.media.MediaType
import io.swagger.v3.oas.models.media.Schema
import io.swagger.v3.oas.models.media.StringSchema
import io.swagger.v3.oas.models.responses.ApiResponse
import io.swagger.v3.oas.models.security.SecurityRequirement
import io.swagger.v3.oas.models.security.SecurityScheme
import org.springdoc.core.customizers.OpenApiCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile

@Configuration
@Profile("local")
internal class OpenApiConfiguration {
    @Bean
    fun clothesAdvisorApi(): OpenAPI = OpenAPI()
        .info(Info().title("Clothes Advisor API").version("1")
            .description("Локальный API. Роли точные: ADMIN не наследует USER или STYLIST. " +
                "Все методы, кроме входа, требуют Bearer JWT активной учётной записи. " +
                "Списки возвращаются массивами JSON; page начинается с 0, size по умолчанию 50 (1..50), " +
                "произведение page × size не превышает 2147483647. " +
                "Ошибки JSON/DTO дают 400 с ApiErrorResponse; доменные ошибки 400 — без тела."))
        .components(Components().addSecuritySchemes("bearerAuth", SecurityScheme()
            .type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT"))
            .addResponses("Unauthorized", ApiResponse().description("Отсутствует, неверен или истёк токен; учётная запись неактивна или неверны данные входа")
                .addHeaderObject("WWW-Authenticate", Header().schema(StringSchema().example("Bearer")))
                .content(securityErrorContent("unauthorized")))
            .addResponses("Forbidden", ApiResponse().description("Недостаточная роль или запрещённая операция")
                .content(securityErrorContent("forbidden"))))
        .addSecurityItem(SecurityRequirement().addList("bearerAuth"))

    @Bean
    fun apiSecurityResponses(): OpenApiCustomizer = OpenApiCustomizer { api ->
        api.paths.values.forEach { path ->
            path.readOperations().forEach { operation ->
                operation.responses.addApiResponse("401", ApiResponse().`$ref`("#/components/responses/Unauthorized"))
                if (operation.security?.isEmpty() != true) {
                    operation.responses.addApiResponse("403", ApiResponse().`$ref`("#/components/responses/Forbidden"))
                }
            }
        }
    }

    private fun securityErrorContent(error: String): Content = Content().addMediaType("application/json", MediaType()
        .schema(Schema<Any>().`$ref`("#/components/schemas/ApiErrorResponse"))
        .example(mapOf("error" to error)))
}
