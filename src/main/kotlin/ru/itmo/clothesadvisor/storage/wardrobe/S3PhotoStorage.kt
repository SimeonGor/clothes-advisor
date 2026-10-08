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
import ru.itmo.clothesadvisor.config.S3Properties
import ru.itmo.clothesadvisor.service.wardrobe.MAX_PHOTO_BYTES

@Component
internal class S3PhotoStorage(
    private val client: HttpClient,
    private val properties: S3Properties,
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
        val started = System.nanoTime()
        val responseInfo = AtomicReference<HttpResponse.ResponseInfo>()
        var future: CompletableFuture<HttpResponse<ByteArray>>? = null
        var byteCount: Int? = upload?.size
        var result = "transport"
        try {
            if (sizeBytes !in 1..MAX_PHOTO_BYTES.toLong()) {
                result = "invalid_size"
                throw PhotoStorageUnavailableException()
            }
            val uri = URI(properties.endpointUri.toASCIIString().trimEnd('/') +
                "/${pathSegment(properties.bucket)}/${pathSegment(key)}")
            val request = HttpRequest.newBuilder(uri)
                .timeout(timeout)
                .header("Authorization", "Bearer ${properties.iamToken}")
                .apply {
                    if (upload != null) {
                        header("Content-Type", requireNotNull(contentType))
                        header("If-None-Match", "*")
                        PUT(HttpRequest.BodyPublishers.ofByteArray(upload))
                    } else GET()
                }.build()
            val handler = HttpResponse.BodyHandler<ByteArray> { info ->
                responseInfo.set(info)
                if (operation == "GET" && info.statusCode() == 200) {
                    HttpResponse.BodyHandlers.limiting(HttpResponse.BodyHandlers.ofByteArray(), sizeBytes).apply(info)
                } else {
                    // Bound even discarded error/PUT bodies so a peer cannot drain forever.
                    HttpResponse.BodyHandlers.limiting(
                        HttpResponse.BodyHandlers.replacing(ByteArray(0)), 64 * 1024L,
                    ).apply(info)
                }
            }
            future = client.sendAsync(request, handler)
            val remaining = timeout.toNanos() - (System.nanoTime() - started)
            val response = future.get(remaining.coerceAtLeast(0), TimeUnit.NANOSECONDS)
            if (response.statusCode() != 200) {
                result = "http_status"
                throw PhotoStorageUnavailableException()
            }
            if (operation == "GET") {
                byteCount = response.body().size
                if (byteCount.toLong() != sizeBytes) {
                    result = "size_mismatch"
                    throw PhotoStorageUnavailableException()
                }
            }
            result = "success"
            return response.body()
        } catch (_: InterruptedException) {
            result = "interrupted"
            Thread.currentThread().interrupt()
            throw PhotoStorageUnavailableException()
        } catch (_: TimeoutException) {
            result = "timeout"
            throw PhotoStorageUnavailableException()
        } catch (failure: ExecutionException) {
            if (failure.cause is HttpTimeoutException) result = "timeout"
            throw PhotoStorageUnavailableException()
        } catch (_: Exception) {
            throw PhotoStorageUnavailableException()
        } finally {
            future?.let { if (!it.isDone) it.cancel(true) }
            val info = responseInfo.get()
            val requestId = info?.headers()?.firstValue("x-amz-request-id")?.orElse(null)
                ?.take(128)?.map { if (it.isLetterOrDigit() && it.code < 128 || it in "-_.") it else '_' }
                ?.joinToString("") ?: "-"
            val message = "Object storage operation={} elapsedMs={} status={} bytes={} requestId={} result={}"
            val arguments = arrayOf<Any>(operation, (System.nanoTime() - started) / 1_000_000,
                info?.statusCode() ?: "-", byteCount ?: "-", requestId, result)
            if (result == "success") logger.debug(message, *arguments) else logger.warn(message, *arguments)
        }
    }

    private fun pathSegment(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)
        .replace("+", "%20").let { if (it == "." || it == "..") it.replace(".", "%2E") else it }
}

internal class PhotoStorageUnavailableException : RuntimeException()
