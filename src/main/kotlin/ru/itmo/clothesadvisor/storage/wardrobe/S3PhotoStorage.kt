package ru.itmo.clothesadvisor.storage.wardrobe

import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicReference
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import ru.itmo.clothesadvisor.client.iam.IamTokenProvider
import ru.itmo.clothesadvisor.config.S3Properties
import ru.itmo.clothesadvisor.service.wardrobe.MAX_PHOTO_BYTES

private const val MAX_DISCARDED_RESPONSE_BYTES = 64 * 1024L
private const val MAX_REQUEST_ID_LENGTH = 128

@Component
internal class S3PhotoStorage(
    private val client: HttpClient,
    private val properties: S3Properties,
    private val tokenProvider: IamTokenProvider,
    private val timeout: Duration = Duration.ofSeconds(60),
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    fun put(key: String, contentType: String, bytes: ByteArray) {
        exchange("PUT", key, bytes.size.toLong(), contentType, bytes)
    }

    fun get(key: String, sizeBytes: Long): ByteArray = exchange("GET", key, sizeBytes)

    private fun exchange(
        operation: String,
        key: String,
        sizeBytes: Long,
        contentType: String? = null,
        upload: ByteArray? = null,
    ): ByteArray {
        val diagnostics = ExchangeDiagnostics(operation, upload?.size)
        var future: CompletableFuture<HttpResponse<ByteArray>>? = null
        try {
            validateSize(sizeBytes, diagnostics)

            val request = buildRequest(key, contentType, upload)
            future = client.sendAsync(request, responseBodyHandler(sizeBytes, diagnostics))
            val response = awaitResponse(future, diagnostics.started)
            validateResponse(response, sizeBytes, diagnostics)
            diagnostics.result = "success"

            return response.body()
        } catch (_: InterruptedException) {
            diagnostics.result = "interrupted"
            Thread.currentThread().interrupt()
            throw PhotoStorageUnavailableException()
        } catch (_: TimeoutException) {
            diagnostics.result = "timeout"
            throw PhotoStorageUnavailableException()
        } catch (failure: ExecutionException) {
            if (failure.cause is HttpTimeoutException) diagnostics.result = "timeout"
            throw PhotoStorageUnavailableException()
        } catch (_: Exception) {
            throw PhotoStorageUnavailableException()
        } finally {
            future?.let { if (!it.isDone) it.cancel(true) }
            logOutcome(diagnostics)
        }
    }

    private fun validateSize(sizeBytes: Long, diagnostics: ExchangeDiagnostics) {
        if (sizeBytes !in 1..MAX_PHOTO_BYTES.toLong()) {
            diagnostics.result = "invalid_size"
            throw PhotoStorageUnavailableException()
        }
    }

    private fun buildRequest(key: String, contentType: String?, upload: ByteArray?): HttpRequest {
        val uri =
            URI(
                properties.endpointUri.toASCIIString().trimEnd('/') +
                    "/${pathSegment(properties.bucket)}/${pathSegment(key)}",
            )
        return HttpRequest.newBuilder(uri)
            .timeout(timeout)
            .header("Authorization", "Bearer ${tokenProvider.accessToken()}")
            .apply {
                if (upload != null) {
                    header("Content-Type", requireNotNull(contentType))
                    header("If-None-Match", "*")
                    PUT(HttpRequest.BodyPublishers.ofByteArray(upload))
                } else GET()
            }
            .build()
    }

    private fun responseBodyHandler(sizeBytes: Long, diagnostics: ExchangeDiagnostics) =
        HttpResponse.BodyHandler<ByteArray> { info ->
            diagnostics.responseInfo.set(info)
            if (diagnostics.operation == "GET" && info.statusCode() == 200) {
                HttpResponse.BodyHandlers.limiting(
                        HttpResponse.BodyHandlers.ofByteArray(),
                        sizeBytes,
                    )
                    .apply(info)
            } else {
                // Bound even discarded error/PUT bodies so a peer cannot drain forever.
                HttpResponse.BodyHandlers.limiting(
                        HttpResponse.BodyHandlers.replacing(ByteArray(0)),
                        MAX_DISCARDED_RESPONSE_BYTES,
                    )
                    .apply(info)
            }
        }

    private fun awaitResponse(
        future: CompletableFuture<HttpResponse<ByteArray>>,
        started: Long,
    ): HttpResponse<ByteArray> {
        // Include request setup, headers and the entire body in one deadline.
        val remaining = timeout.toNanos() - (System.nanoTime() - started)
        return future.get(remaining.coerceAtLeast(0), TimeUnit.NANOSECONDS)
    }

    private fun validateResponse(
        response: HttpResponse<ByteArray>,
        sizeBytes: Long,
        diagnostics: ExchangeDiagnostics,
    ) {
        if (response.statusCode() != 200) {
            diagnostics.result = "http_status"
            throw PhotoStorageUnavailableException()
        }
        if (diagnostics.operation == "GET") {
            val byteCount = response.body().size
            diagnostics.byteCount = byteCount
            if (byteCount.toLong() != sizeBytes) {
                diagnostics.result = "size_mismatch"
                throw PhotoStorageUnavailableException()
            }
        }
    }

    private fun logOutcome(diagnostics: ExchangeDiagnostics) {
        val info = diagnostics.responseInfo.get()
        val requestId =
            info
                ?.headers()
                ?.firstValue("x-amz-request-id")
                ?.orElse(null)
                ?.take(MAX_REQUEST_ID_LENGTH)
                ?.map { if (it.isLetterOrDigit() && it.code < 128 || it in "-_.") it else '_' }
                ?.joinToString("") ?: "-"
        val message =
            "Object storage operation={} elapsedMs={} status={} bytes={} requestId={} result={}"
        val arguments =
            arrayOf<Any>(
                diagnostics.operation,
                (System.nanoTime() - diagnostics.started) / 1_000_000,
                info?.statusCode() ?: "-",
                diagnostics.byteCount ?: "-",
                requestId,
                diagnostics.result,
            )
        if (diagnostics.result == "success") {
            logger.debug(message, *arguments)
        } else {
            logger.warn(message, *arguments)
        }
    }

    private fun pathSegment(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20").let {
            if (it == "." || it == "..") it.replace(".", "%2E") else it
        }

    private class ExchangeDiagnostics(val operation: String, var byteCount: Int?) {
        val started = System.nanoTime()
        val responseInfo = AtomicReference<HttpResponse.ResponseInfo>()
        var result = "transport"
    }
}

internal class PhotoStorageUnavailableException : RuntimeException()
