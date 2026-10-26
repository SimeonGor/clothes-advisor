package ru.itmo.clothesadvisor.client.recommendation

import feign.FeignException
import feign.RetryableException
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.http.HttpTimeoutException
import java.util.concurrent.TimeoutException
import java.util.function.Predicate
import org.springframework.cloud.openfeign.FallbackFactory
import ru.itmo.clothesadvisor.dto.recommendation.ItemSelectionRequest
import ru.itmo.clothesadvisor.dto.recommendation.ItemSelectionResponse
import ru.itmo.clothesadvisor.model.ai.AiSelectionException
import ru.itmo.clothesadvisor.model.ai.AiSelectionFailure

class RecommendationFallbackFactory : FallbackFactory<RecommendationClient> {
    override fun create(cause: Throwable): RecommendationClient {
        val causes = generateSequence(cause) { it.cause }.toList()
        val failure = causes.filterIsInstance<AiSelectionException>().firstOrNull()?.failure
            ?: when {
                causes.any {
                    it is SocketTimeoutException || it is HttpTimeoutException || it is TimeoutException
                } -> AiSelectionFailure.TIMEOUT
                causes.any { it is RetryableException || it is IOException } ->
                    AiSelectionFailure.UNAVAILABLE
                causes.any { it is FeignException } -> AiSelectionFailure.INVALID_RESPONSE
                else -> AiSelectionFailure.UNAVAILABLE
            }
        return object : RecommendationClient {
            override fun select(request: ItemSelectionRequest): ItemSelectionResponse =
                throw AiSelectionException(failure)

            override fun selectCapsule(request: ItemSelectionRequest): ItemSelectionResponse =
                throw AiSelectionException(failure)
        }
    }
}

class NoSelectionFailurePredicate : Predicate<Throwable> {
    override fun test(error: Throwable): Boolean =
        generateSequence(error) { it.cause }.any {
            it is AiSelectionException &&
                (it.failure == AiSelectionFailure.NO_OUTFIT || it.failure == AiSelectionFailure.NO_CAPSULE)
        }
}
