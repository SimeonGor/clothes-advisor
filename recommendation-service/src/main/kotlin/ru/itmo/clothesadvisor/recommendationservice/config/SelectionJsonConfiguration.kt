package ru.itmo.clothesadvisor.recommendationservice.config

import org.springframework.context.annotation.Configuration
import org.springframework.http.codec.ServerCodecConfigurer
import org.springframework.http.codec.json.JacksonJsonDecoder
import org.springframework.web.reactive.config.WebFluxConfigurer
import tools.jackson.core.StreamReadFeature
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.MapperFeature
import tools.jackson.databind.cfg.CoercionAction
import tools.jackson.databind.cfg.CoercionInputShape
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.type.LogicalType

@Configuration
class SelectionJsonConfiguration : WebFluxConfigurer {
    override fun configureHttpMessageCodecs(configurer: ServerCodecConfigurer) {
        val mapper = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(
                DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES,
                DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
            )
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .withCoercionConfig(LogicalType.Textual) { config ->
                listOf(CoercionInputShape.Integer, CoercionInputShape.Float, CoercionInputShape.Boolean)
                    .forEach { config.setCoercion(it, CoercionAction.Fail) }
            }
            .build()
        configurer.defaultCodecs().jacksonJsonDecoder(JacksonJsonDecoder(mapper))
    }
}
