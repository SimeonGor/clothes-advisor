package refresher

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.crypto.RSASSAVerifier
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.KeyUse
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jwt.SignedJWT
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.ByteBuffer
import java.time.Duration
import java.time.Instant
import java.util.concurrent.Flow
import java.util.concurrent.TimeUnit
import tools.jackson.databind.JsonNode

internal const val ISSUER = "https://auth.openai.com"
internal const val RESOURCE = "https://api.openai.com/v1"
internal val requiredScopes = setOf("resource.invoke", "chatgpt.tokens.use.direct")

// Endpoint injection is internal and used only by synthetic tests. The entrypoint uses defaults.
internal class OAuth(
    private val client: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NEVER).build(),
    private val token: URI = URI.create("$ISSUER/api/accounts/oauth/token"),
    private val jwks: URI = URI.create("$ISSUER/.well-known/jwks.json"),
) {
    fun refresh(credentials: JsonNode): JsonNode {
        val form = mapOf("grant_type" to "refresh_token", "client_id" to credentials.text("client_id"),
            "refresh_token" to credentials.text("refresh_token"), "resource" to RESOURCE)
            .entries.joinToString("&") { "${it.key}=${URLEncoder.encode(it.value, Charsets.UTF_8)}" }
        return request(HttpRequest.newBuilder(token).header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(form)).build())
    }

    fun verify(idToken: String, previous: JsonNode, now: Instant) {
        val keys = try { JWKSet.parse(request(HttpRequest.newBuilder(jwks).GET().build()).toString()) }
            catch (_: Exception) { fail("verification_unavailable") }
        try {
            val jwt = SignedJWT.parse(idToken)
            if (jwt.header.algorithm != JWSAlgorithm.RS256 || jwt.header.keyID.isNullOrBlank()) fail("invalid_id_token")
            val key = keys.keys.filter { it.keyID == jwt.header.keyID }.singleOrNull() as? RSAKey
                ?: fail("verification_unavailable")
            if ((key.keyUse != null && key.keyUse != KeyUse.SIGNATURE) ||
                (key.algorithm != null && key.algorithm != JWSAlgorithm.RS256) ||
                !jwt.verify(RSASSAVerifier(key.toRSAPublicKey()))) fail("invalid_id_token")
            val claims = jwt.jwtClaimsSet
            val clientId = previous.text("client_id")
            val azp = claims.getStringClaim("azp")
            if (claims.issuer != ISSUER || clientId !in claims.audience ||
                claims.subject != previous.text("subject") ||
                (azp != null && azp != clientId) || (claims.audience.size > 1 && azp != clientId)
            ) fail("identity_mismatch")
            val expires = claims.expirationTime?.toInstant() ?: fail("invalid_id_token")
            val issued = claims.issueTime?.toInstant() ?: fail("invalid_id_token")
            if (!expires.isAfter(now.minusSeconds(30)) || issued.isAfter(now.plusSeconds(30)) ||
                !expires.isAfter(issued) || claims.notBeforeTime?.toInstant()?.isAfter(now.plusSeconds(30)) == true
            ) fail("invalid_id_token")
        } catch (e: Failure) { throw e }
        catch (_: Exception) { fail("invalid_id_token") }
    }

    private fun request(request: HttpRequest): JsonNode {
        val future = client.sendAsync(request, HttpResponse.BodyHandler { LimitedBody() })
        val response = try { future.get(20, TimeUnit.SECONDS) }
        catch (_: Exception) {
            future.cancel(true)
            fail("ambiguous_transport")
        }
        if (response.statusCode() == 429 || response.statusCode() in 500..599) fail("retry_later")
        if (response.statusCode() !in 200..299) fail("authorization_rejected")
        return try { json.readTree(response.body())?.takeIf { it.isObject } ?: fail("invalid_response") }
        catch (_: Exception) { fail("invalid_response") }
    }

    private class LimitedBody : HttpResponse.BodySubscriber<ByteArray> {
        private val delegate = HttpResponse.BodySubscribers.ofByteArray()
        private lateinit var subscription: Flow.Subscription
        private var size = 0L
        override fun getBody() = delegate.body
        override fun onSubscribe(value: Flow.Subscription) { subscription = value; delegate.onSubscribe(value) }
        override fun onNext(items: List<ByteBuffer>) {
            size += items.sumOf { it.remaining().toLong() }
            if (size > MAX_BYTES) {
                subscription.cancel()
                delegate.onError(Failure("response_too_large"))
            } else delegate.onNext(items)
        }
        override fun onError(error: Throwable) = delegate.onError(Failure("transport_failed"))
        override fun onComplete() = delegate.onComplete()
    }
}
