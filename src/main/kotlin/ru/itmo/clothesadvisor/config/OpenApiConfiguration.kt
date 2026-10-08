package ru.itmo.clothesadvisor.config

import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import io.swagger.v3.oas.models.media.IntegerSchema
import io.swagger.v3.oas.models.media.Content
import io.swagger.v3.oas.models.media.MediaType
import io.swagger.v3.oas.models.media.Schema
import io.swagger.v3.oas.models.parameters.Parameter
import io.swagger.v3.oas.models.responses.ApiResponse
import java.math.BigDecimal
import org.springdoc.core.customizers.OperationCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import ru.itmo.clothesadvisor.dto.user.CurrentUser

@Configuration
@Profile("local")
internal class OpenApiConfiguration {
    @Bean
    fun clothesAdvisorApi(): OpenAPI = OpenAPI()
        .info(
            Info().title("Clothes Advisor API").version("1")
                .description(
                    "Локальный API. Действующий пользователь передаётся в X-User-Id " +
                        "только для методов с действующим пользователем; это не аутентификация. " +
                        "Списки возвращаются массивами JSON; page начинается с 0, size по умолчанию 50 (1..50). " +
                        "Ошибки JSON/DTO дают 400 с ApiErrorResponse; неизвестный пользователь — 404, " +
                        "заблокированный — 403."
                )
        )

    @Bean
    fun currentUserHeader(): OperationCustomizer = OperationCustomizer { operation, handlerMethod ->
        if (handlerMethod.methodParameters.any { it.parameterType == CurrentUser::class.java } &&
            operation.parameters.orEmpty().none { it.name == "X-User-Id" && it.`in` == "header" }) {
            operation.addParametersItem(
                Parameter()
                    .name("X-User-Id")
                    .`in`("header")
                    .required(true)
                    .description("Положительный ID действующего пользователя; применяется только к этому методу.")
                    .schema(IntegerSchema().format("int64").minimum(BigDecimal.ONE))
            )
            val errors = mapOf(
                "400" to "Отсутствует или некорректен X-User-Id: JSON invalid_request.",
                "404" to "Пользователь из X-User-Id не найден; пустое тело.",
                "403" to "Пользователь заблокирован или операция запрещена бизнес-правилами: JSON forbidden.",
            )
            errors.forEach { (code, description) ->
                val response = operation.responses.getOrPut(code) { ApiResponse() }
                response.description = listOfNotNull(response.description, description).distinct().joinToString(" ")
                if (code != "404") {
                    val content = response.content ?: Content().also { response.content = it }
                    content.putIfAbsent("application/json", MediaType()
                        .schema(Schema<Any>().`$ref`("#/components/schemas/ApiErrorResponse")))
                }
            }
        }
        operation
    }
}
