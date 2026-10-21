package ru.itmo.clothesadvisor.client.recommendation

import feign.Request
import feign.Retryer
import feign.codec.Decoder
import feign.codec.Encoder
import feign.codec.ErrorDecoder
import java.util.concurrent.TimeUnit
import org.springframework.context.annotation.Bean
import ru.itmo.clothesadvisor.client.ai.AiOutfitException
import ru.itmo.clothesadvisor.client.ai.AiOutfitFailure
import ru.itmo.clothesadvisor.dto.ai.OutfitSelectionResponse
import tools.jackson.core.JacksonException
import tools.jackson.core.StreamReadFeature
import tools.jackson.core.exc.JacksonIOException
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.json.JsonMapper

// Registered only in the Feign child context, never through component scanning.
class RecommendationClientConfiguration {
    private val mapper = JsonMapper.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .build()

    @Bean
    fun recommendationEncoder() = Encoder { value, _, template ->
        template.body(mapper.writeValueAsBytes(value), Charsets.UTF_8)
    }

    @Bean
    fun recommendationDecoder() = Decoder { response, _ ->
        try {
            val body = response.body()?.asInputStream()?.use { mapper.readTree(it) }
            val ids = body?.path("itemIds")
            if (response.status() != 200 || body == null || !body.isObject || body.size() != 1 ||
                ids == null || !ids.isArray || ids.isEmpty || ids.size() > 20 ||
                ids.any { !it.isIntegralNumber || !it.canConvertToLong() || it.longValue() <= 0 }
            ) throw AiOutfitException(AiOutfitFailure.INVALID_RESPONSE)
            val selected = ids.toList().map { it.longValue() }
            if (selected.toSet().size != selected.size) {
                throw AiOutfitException(AiOutfitFailure.INVALID_RESPONSE)
            }
            OutfitSelectionResponse(selected)
        } catch (error: JacksonIOException) {
            throw error
        } catch (_: JacksonException) {
            throw AiOutfitException(AiOutfitFailure.INVALID_RESPONSE)
        }
    }

    @Bean
    fun recommendationErrorDecoder() = ErrorDecoder { _, response ->
        val failure = try {
            val body = response.body()?.asInputStream()?.use { mapper.readTree(it) }
            val expected = when (response.status()) {
                400 -> AiOutfitFailure.INVALID_REQUEST
                422 -> AiOutfitFailure.NO_OUTFIT
                502 -> AiOutfitFailure.INVALID_RESPONSE
                503 -> AiOutfitFailure.UNAVAILABLE
                504 -> AiOutfitFailure.TIMEOUT
                else -> null
            }
            if (expected != null && body != null && body.isObject && body.size() == 1 &&
                body.path("code").isString &&
                body.path("code").asString() == expected.name.lowercase() &&
                expected != AiOutfitFailure.INVALID_REQUEST
            ) expected else AiOutfitFailure.INVALID_RESPONSE
        } catch (error: JacksonIOException) {
            throw error
        } catch (_: JacksonException) {
            AiOutfitFailure.INVALID_RESPONSE
        }
        AiOutfitException(failure)
    }

    @Bean
    fun recommendationOptions() = Request.Options(5, TimeUnit.SECONDS, 90, TimeUnit.SECONDS, false)

    @Bean
    fun recommendationRetryer(): Retryer = Retryer.NEVER_RETRY

    @Bean
    fun recommendationFallbackFactory() = RecommendationFallbackFactory()
}
