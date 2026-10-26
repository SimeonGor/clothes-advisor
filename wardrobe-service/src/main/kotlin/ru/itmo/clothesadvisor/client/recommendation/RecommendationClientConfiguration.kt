package ru.itmo.clothesadvisor.client.recommendation

import feign.Request
import feign.Retryer
import feign.codec.Decoder
import feign.codec.Encoder
import feign.codec.ErrorDecoder
import java.util.concurrent.TimeUnit
import org.springframework.context.annotation.Bean
import ru.itmo.clothesadvisor.dto.recommendation.ItemSelectionResponse
import ru.itmo.clothesadvisor.config.LiveClientSettings
import ru.itmo.clothesadvisor.model.ai.AiSelectionException
import ru.itmo.clothesadvisor.model.ai.AiSelectionFailure
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
            val explanation = body?.get("explanation")
            val expectedFields = if (explanation == null) 1 else 2
            if (response.status() != 200 || body == null || !body.isObject || body.size() != expectedFields ||
                ids == null || !ids.isArray || ids.isEmpty || ids.size() > 20 ||
                ids.any { !it.isIntegralNumber || !it.canConvertToLong() || it.longValue() <= 0 } ||
                (explanation != null && (!explanation.isString || explanation.asString().isBlank() ||
                    explanation.asString().length > 2000))
            ) throw AiSelectionException(AiSelectionFailure.INVALID_RESPONSE)
            val selected = ids.toList().map { it.longValue() }
            if (selected.toSet().size != selected.size) {
                throw AiSelectionException(AiSelectionFailure.INVALID_RESPONSE)
            }
            ItemSelectionResponse(selected, explanation?.asString())
        } catch (error: JacksonIOException) {
            throw error
        } catch (_: JacksonException) {
            throw AiSelectionException(AiSelectionFailure.INVALID_RESPONSE)
        }
    }

    @Bean
    fun recommendationErrorDecoder() = ErrorDecoder { methodKey, response ->
        val failure = try {
            val body = response.body()?.asInputStream()?.use { mapper.readTree(it) }
            val expected = when (response.status()) {
                400 -> AiSelectionFailure.INVALID_REQUEST
                422 -> when (methodKey) {
                    "RecommendationClient#select(ItemSelectionRequest)" -> AiSelectionFailure.NO_OUTFIT
                    "RecommendationClient#selectCapsule(ItemSelectionRequest)" -> AiSelectionFailure.NO_CAPSULE
                    else -> null
                }
                502 -> AiSelectionFailure.INVALID_RESPONSE
                503 -> AiSelectionFailure.UNAVAILABLE
                504 -> AiSelectionFailure.TIMEOUT
                else -> null
            }
            if (expected != null && body != null && body.isObject && body.size() == 1 &&
                body.path("code").isString &&
                body.path("code").asString() == expected.name.lowercase() &&
                expected != AiSelectionFailure.INVALID_REQUEST
            ) expected else AiSelectionFailure.INVALID_RESPONSE
        } catch (error: JacksonIOException) {
            throw error
        } catch (_: JacksonException) {
            AiSelectionFailure.INVALID_RESPONSE
        }
        AiSelectionException(failure)
    }

    @Bean
    internal fun recommendationOptions(settings: LiveClientSettings): Request.Options =
        object : Request.Options(5, TimeUnit.SECONDS, 90, TimeUnit.SECONDS, false) {
            override fun getMethodOptions(methodName: String): Request.Options = settings.recommendationOptions()
        }

    @Bean
    fun recommendationRetryer(): Retryer = Retryer.NEVER_RETRY

    @Bean
    fun recommendationDiscoveryCapability() = RecommendationDiscoveryCapability()

    @Bean
    fun recommendationFallbackFactory() = RecommendationFallbackFactory()
}
