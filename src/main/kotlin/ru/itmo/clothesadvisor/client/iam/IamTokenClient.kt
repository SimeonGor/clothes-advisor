package ru.itmo.clothesadvisor.client.iam

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.MGF1ParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.PSSParameterSpec
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.concurrent.TimeUnit
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

internal const val IAM_TOKEN_ENDPOINT = "https://iam.api.cloud.yandex.net/iam/v1/tokens"
private const val MAX_AUTHORIZED_KEY_BYTES = 64 * 1024
private const val MAX_TOKEN_RESPONSE_BYTES = 64 * 1024

internal class IamToken(val value: String, val expiresAt: Instant)

internal class IamTokenUnavailableException : RuntimeException()

internal class IamTokenClient(
    private val serviceAccountKeyFile: String,
    private val mapper: JsonMapper,
    private val clock: Clock,
    private val endpoint: URI = URI(IAM_TOKEN_ENDPOINT),
    private val timeout: Duration = Duration.ofSeconds(10),
) : AutoCloseable {
    private val httpClient =
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build()

    fun issueToken(): IamToken {
        try {
            if (Thread.currentThread().isInterrupted) throw IamTokenUnavailableException()

            val startedAtNanos = System.nanoTime()
            val authorizedKey = readAuthorizedKey()
            val jwt = createSignedJwt(authorizedKey)
            val request = buildTokenRequest(jwt)
            val response = sendTokenRequest(request, startedAtNanos)

            return parseToken(response)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IamTokenUnavailableException()
        } catch (_: Exception) {
            throw IamTokenUnavailableException()
        }
    }

    private fun readAuthorizedKey(): JsonNode {
        require(serviceAccountKeyFile.isNotBlank())
        val keyBytes =
            Files.newInputStream(Path.of(serviceAccountKeyFile)).use {
                it.readNBytes(MAX_AUTHORIZED_KEY_BYTES + 1)
            }
        require(keyBytes.size <= MAX_AUTHORIZED_KEY_BYTES)

        return mapper.readTree(keyBytes)
    }

    private fun decodePrivateKey(pem: String): PrivateKey {
        val pemStart = pem.indexOf("-----BEGIN PRIVATE KEY-----")
        require(pemStart >= 0)
        val prefix = pem.substring(0, pemStart).trim()
        require(
            prefix.isEmpty() ||
                prefix.startsWith("PLEASE DO NOT REMOVE THIS LINE! Yandex.Cloud SA Key ID "),
        )

        val encodedKey = pem.substring(pemStart).removePrefix("-----BEGIN PRIVATE KEY-----").trim()
        require(encodedKey.endsWith("-----END PRIVATE KEY-----"))
        val der =
            Base64.getDecoder()
                .decode(
                    encodedKey.removeSuffix("-----END PRIVATE KEY-----").filterNot {
                        it.isWhitespace()
                    },
                )

        return KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(der))
    }

    private fun createSignedJwt(authorizedKey: JsonNode): String {
        val keyId = requiredText(authorizedKey, "id")
        val serviceAccountId = requiredText(authorizedKey, "service_account_id")
        val privateKey = decodePrivateKey(requiredText(authorizedKey, "private_key"))

        val issuedAt = clock.instant()
        val header = mapOf("typ" to "JWT", "alg" to "PS256", "kid" to keyId)
        val claims =
            mapOf(
                "iss" to serviceAccountId,
                "aud" to IAM_TOKEN_ENDPOINT,
                "iat" to issuedAt.epochSecond,
                "exp" to issuedAt.plusSeconds(300).epochSecond,
            )

        val signingInput =
            "${encodeBase64Url(mapper.writeValueAsBytes(header))}.${encodeBase64Url(mapper.writeValueAsBytes(claims))}"
        val signer = Signature.getInstance("RSASSA-PSS")
        signer.setParameter(PSSParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, 32, 1))
        signer.initSign(privateKey)
        signer.update(signingInput.toByteArray(Charsets.US_ASCII))

        return "$signingInput.${encodeBase64Url(signer.sign())}"
    }

    private fun buildTokenRequest(jwt: String): HttpRequest =
        HttpRequest.newBuilder(endpoint)
            .timeout(timeout)
            .header("Content-Type", "application/json")
            .POST(
                HttpRequest.BodyPublishers.ofByteArray(
                    mapper.writeValueAsBytes(mapOf("jwt" to jwt)),
                ),
            )
            .build()

    private fun sendTokenRequest(
        request: HttpRequest,
        startedAtNanos: Long,
    ): HttpResponse<ByteArray> {
        val responseFuture =
            httpClient.sendAsync(
                request,
                HttpResponse.BodyHandlers.limiting(
                    HttpResponse.BodyHandlers.ofByteArray(),
                    MAX_TOKEN_RESPONSE_BYTES.toLong(),
                ),
            )

        try {
            val remainingNanos = timeout.toNanos() - (System.nanoTime() - startedAtNanos)
            return responseFuture.get(remainingNanos.coerceAtLeast(0), TimeUnit.NANOSECONDS)
        } finally {
            if (!responseFuture.isDone) responseFuture.cancel(true)
        }
    }

    private fun parseToken(response: HttpResponse<ByteArray>): IamToken {
        require(response.statusCode() == 200)

        val body = mapper.readTree(response.body())
        val token = requiredText(body, "iamToken")
        require(token.all { it in '!'..'~' })

        val expiresAt = Instant.parse(requiredText(body, "expiresAt"))
        require(expiresAt.isAfter(clock.instant()))

        return IamToken(token, expiresAt)
    }

    private fun requiredText(node: JsonNode, field: String): String {
        val value = node.get(field)
        require(value != null && value.isString)
        return value.stringValue().also { require(it.isNotBlank()) }
    }

    private fun encodeBase64Url(bytes: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    override fun close() {
        httpClient.shutdownNow()
    }
}
