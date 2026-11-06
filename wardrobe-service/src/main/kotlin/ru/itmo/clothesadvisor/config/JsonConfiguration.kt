package ru.itmo.clothesadvisor.config

import org.springframework.context.annotation.Configuration
import org.springframework.http.codec.ServerCodecConfigurer
import org.springframework.http.codec.json.JacksonJsonDecoder
import org.springframework.web.reactive.config.WebFluxConfigurer

@Configuration
internal class JsonConfiguration : WebFluxConfigurer {
    override fun configureHttpMessageCodecs(configurer: ServerCodecConfigurer) {
        configurer.defaultCodecs().configureDefaultCodec { codec ->
            if (codec is JacksonJsonDecoder) codec.maxInMemorySize = -1
        }
    }
}
