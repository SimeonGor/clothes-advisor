package ru.itmo.clothesadvisor.client.recommendation

import feign.FeignException
import feign.RetryableException
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.http.HttpTimeoutException
import java.util.concurrent.TimeoutException
import java.util.function.Predicate
import org.springframework.cloud.openfeign.FallbackFactory
import ru.itmo.clothesadvisor.dto.recommendation.OutfitSelectionRequest
import ru.itmo.clothesadvisor.dto.recommendation.OutfitSelectionResponse
import ru.itmo.clothesadvisor.model.ai.AiOutfitException
import ru.itmo.clothesadvisor.model.ai.AiOutfitFailure

class RecommendationFallbackFactory : FallbackFactory<RecommendationClient> {
    override fun create(cause: Throwable): RecommendationClient {
        val causes = generateSequence(cause) { it.cause }.toList()
        val failure = causes.filterIsInstance<AiOutfitException>().firstOrNull()?.failure
            ?: when {
                causes.any {
                    it is SocketTimeoutException || it is HttpTimeoutException || it is TimeoutException
                } -> AiOutfitFailure.TIMEOUT
                causes.any { it is RetryableException || it is IOException } ->
                    AiOutfitFailure.UNAVAILABLE
                causes.any { it is FeignException } -> AiOutfitFailure.INVALID_RESPONSE
                else -> AiOutfitFailure.UNAVAILABLE
            }
        return object : RecommendationClient {
            override fun select(request: OutfitSelectionRequest): OutfitSelectionResponse =
                throw AiOutfitException(failure)
        }
    }
}

class NoOutfitFailurePredicate : Predicate<Throwable> {
    override fun test(error: Throwable): Boolean =
        generateSequence(error) { it.cause }.any {
            it is AiOutfitException && it.failure == AiOutfitFailure.NO_OUTFIT
        }
}
