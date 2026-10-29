package ru.itmo.clothesadvisor.client.recommendation

import feign.FeignException
import feign.RetryableException
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.http.HttpTimeoutException
import java.util.concurrent.TimeoutException
import java.util.function.Predicate
import ru.itmo.clothesadvisor.model.ai.AiSelectionException
import ru.itmo.clothesadvisor.model.ai.AiSelectionFailure

class NoSelectionFailurePredicate : Predicate<Throwable> {
    override fun test(error: Throwable): Boolean =
        generateSequence(error) { it.cause }.any {
            it is AiSelectionException &&
                (it.failure == AiSelectionFailure.NO_OUTFIT || it.failure == AiSelectionFailure.NO_CAPSULE)
        }
}

internal fun recommendationFailure(cause: Throwable): AiSelectionException {
    val causes = generateSequence(cause) { it.cause }.toList()
    val failure = causes.filterIsInstance<AiSelectionException>().firstOrNull()?.failure ?: when {
        causes.any { it is SocketTimeoutException || it is HttpTimeoutException || it is TimeoutException ||
            it is io.netty.handler.timeout.TimeoutException || it is io.netty.channel.ConnectTimeoutException } ->
            AiSelectionFailure.TIMEOUT
        causes.any { it is org.springframework.core.io.buffer.DataBufferLimitException } ->
            AiSelectionFailure.INVALID_RESPONSE
        causes.any { it is RetryableException || it is IOException } -> AiSelectionFailure.UNAVAILABLE
        causes.any { it is FeignException } -> AiSelectionFailure.INVALID_RESPONSE
        else -> AiSelectionFailure.UNAVAILABLE
    }
    return AiSelectionException(failure)
}
