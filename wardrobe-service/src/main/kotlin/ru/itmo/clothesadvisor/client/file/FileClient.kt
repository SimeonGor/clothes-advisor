package ru.itmo.clothesadvisor.client.file

import io.netty.channel.ChannelOption
import io.netty.handler.timeout.WriteTimeoutHandler
import java.util.UUID
import java.util.concurrent.TimeUnit
import org.springframework.core.io.buffer.DataBuffer
import org.springframework.core.io.buffer.DataBufferUtils
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.client.reactive.ReactorClientHttpConnector
import org.springframework.web.reactive.function.client.ClientResponse
import org.springframework.web.reactive.function.client.WebClient
import reactor.core.publisher.Mono
import reactor.netty.http.client.HttpClient
import ru.itmo.clothesadvisor.config.LiveClientSettings
import ru.itmo.clothesadvisor.service.wardrobe.FileCreated
import ru.itmo.clothesadvisor.service.wardrobe.MAX_PHOTO_BYTES
import ru.itmo.clothesadvisor.service.wardrobe.PhotoFiles
import ru.itmo.clothesadvisor.storage.wardrobe.PhotoStorageUnavailableException
import tools.jackson.core.StreamReadFeature
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.json.JsonMapper

internal class FileClient(private val builder: WebClient.Builder, private val settings: LiveClientSettings) : PhotoFiles {
    private val mapper = JsonMapper.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .build()

    override fun put(fileId: UUID, contentType: String, bytes: ByteArray): Mono<FileCreated> = Mono.defer {
        val expected = FileCreated(fileId, contentType, bytes.size.toLong())
        validatePhoto(contentType, expected.sizeBytes)
        client().put().uri(FILE_PATH, fileId)
            .contentType(MediaType.parseMediaType(contentType)).contentLength(expected.sizeBytes)
            .bodyValue(bytes).exchangeToMono { response ->
                if (response.statusCode() != HttpStatus.CREATED ||
                    response.headers().contentLength().orElse(0) > METADATA_LIMIT
                ) return@exchangeToMono reject(response)
                readBody(response, METADATA_LIMIT).map { json ->
                    val body = mapper.readTree(json)
                    val size = body.path("sizeBytes")
                    if (body.path("fileId").stringValue()?.equals(fileId.toString(), ignoreCase = true) != true ||
                        body.path("contentType").stringValue() != contentType ||
                        !size.isIntegralNumber || !size.canConvertToLong() || size.longValue() != expected.sizeBytes
                    ) throw PhotoStorageUnavailableException()
                    expected
                }
            }
    }.onErrorMap { PhotoStorageUnavailableException() }

    override fun get(fileId: UUID, contentType: String, sizeBytes: Long): Mono<ByteArray> = Mono.defer {
        validatePhoto(contentType, sizeBytes)
        client().get().uri(FILE_PATH, fileId).exchangeToMono { response ->
            val types = response.headers().header(HttpHeaders.CONTENT_TYPE)
            val lengths = response.headers().header(HttpHeaders.CONTENT_LENGTH)
            if (response.statusCode() != HttpStatus.OK || types.singleOrNull()?.equals(contentType, true) != true ||
                (lengths.isNotEmpty() && lengths.singleOrNull()?.toLongOrNull() != sizeBytes)
            ) return@exchangeToMono reject(response)
            readBody(response, sizeBytes.toInt(), exact = true)
        }
    }.onErrorMap { PhotoStorageUnavailableException() }

    private fun client(): WebClient {
        val snapshot = settings.fileProperties()
        val transport = HttpClient.create().disableRetry(true).followRedirect(false)
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, snapshot.connectTimeout.toMillis().toInt())
            .responseTimeout(snapshot.readTimeout)
            .doOnRequest { _, connection ->
                connection.addHandlerLast(WriteTimeoutHandler(snapshot.writeTimeout.toMillis(), TimeUnit.MILLISECONDS))
            }
        return builder.clone().clientConnector(ReactorClientHttpConnector(transport)).build()
    }

    private fun validatePhoto(contentType: String, sizeBytes: Long) {
        require(contentType == "image/jpeg" || contentType == "image/png")
        require(sizeBytes in 1..MAX_PHOTO_BYTES.toLong())
    }

    private fun readBody(response: ClientResponse, limit: Int, exact: Boolean = false): Mono<ByteArray> =
        DataBufferUtils.join(response.bodyToFlux(DataBuffer::class.java), limit).map { buffer ->
            try {
                val count = buffer.readableByteCount()
                if (exact && count != limit) throw PhotoStorageUnavailableException()
                ByteArray(count).also { buffer.read(it) }
            } finally {
                DataBufferUtils.release(buffer)
            }
        }.switchIfEmpty(Mono.error(PhotoStorageUnavailableException()))

    private fun <T : Any> reject(response: ClientResponse): Mono<T> =
        // Subscribe and cancel before WebClient's automatic cleanup can drain an untrusted body.
        response.bodyToFlux(DataBuffer::class.java).take(0, false).then(Mono.error(PhotoStorageUnavailableException()))

    private companion object {
        const val FILE_PATH = "/api/files/{fileId}"
        const val METADATA_LIMIT = 4 * 1024
    }
}
