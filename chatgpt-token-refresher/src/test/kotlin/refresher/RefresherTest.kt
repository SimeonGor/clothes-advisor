package refresher

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLDecoder
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.PosixFilePermissions
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Date
import java.util.concurrent.Executors
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import tools.jackson.databind.JsonNode

internal class RefresherTest {
    @TempDir lateinit var temporary: Path
    private lateinit var privateRecords: Records
    private lateinit var output: Records
    private lateinit var server: HttpServer
    private val executor = Executors.newCachedThreadPool()
    private val clock = TestClock(Instant.parse("2026-10-20T12:00:00Z"))
    private var tokenCalls = 0
    private var jwksCalls = 0
    private var tokenStatus = 200
    private var jwksStatus = 200
    private var jwksBody: String? = null
    private var response: Map<String, Any?> = emptyMap()
    private var rawResponse: String? = null
    private var lastForm: Map<String, String> = emptyMap()
    private var pendingSeenByJwks = false
    private lateinit var tokenUri: URI
    private lateinit var jwksUri: URI

    @BeforeEach fun setup() {
        val root = temporary.toRealPath()
        fun records(name: String) = Records(Files.createDirectory(root.resolve(name),
            PosixFilePermissions.asFileAttribute(ownerDirectory)))
        privateRecords = records("private")
        output = records("output")
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = executor
        server.createContext("/token") { exchange ->
            tokenCalls++
            lastForm = exchange.requestBody.bufferedReader().readText().split('&').associate {
                val pair = it.split('=', limit = 2)
                pair[0] to URLDecoder.decode(pair[1], Charsets.UTF_8)
            }
            val bytes = (rawResponse ?: json.writeValueAsString(response)).toByteArray()
            exchange.sendResponseHeaders(tokenStatus, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
            exchange.close()
        }
        server.createContext("/jwks") { exchange ->
            jwksCalls++
            pendingSeenByJwks = privateRecords.read("refresh-state.json")?.text("phase") == "pending"
            val bytes = (jwksBody ?: JWKSet(key.toPublicJWK()).toString()).toByteArray()
            exchange.sendResponseHeaders(jwksStatus, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
            exchange.close()
        }
        server.start()
        tokenUri = URI("http://127.0.0.1:${server.address.port}/token")
        jwksUri = URI("http://127.0.0.1:${server.address.port}/jwks")
        response = success()
    }

    @AfterEach fun stop() { server.stop(0); executor.shutdownNow() }

    private fun refresher() = Refresher(privateRecords, output, OAuth(token = tokenUri, jwks = jwksUri), clock)
    private fun success() = mapOf<String, Any>("access_token" to "new-access", "refresh_token" to "new-refresh",
        "token_type" to "Bearer", "expires_in" to 3600)
    private fun login(expires: Instant = clock.instant().plusSeconds(30), access: String = "old-access") {
        privateRecords.write("registration.json", mapOf("host_id" to "urn:uuid:00000000-0000-4000-8000-000000000000", "client_id" to "issued"))
        privateRecords.write("credentials.json", mapOf("client_id" to "issued", "issuer" to ISSUER, "subject" to "same-user",
            "access_token" to access, "refresh_token" to "old-refresh", "id_token" to "old-verified-id",
            "expires_at" to expires.toString(), "scopes" to (requiredScopes + "openid").sorted()))
    }

    private fun signed(claims: JWTClaimsSet.Builder.() -> Unit = {}): String {
        val body = JWTClaimsSet.Builder().issuer(ISSUER).subject("same-user").audience("issued")
            .issueTime(Date.from(clock.instant())).expirationTime(Date.from(clock.instant().plusSeconds(600)))
        body.claims()
        return SignedJWT(JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.keyID).build(), body.build()).also {
            it.sign(RSASSASigner(key))
        }.serialize()
    }

    @Test fun `scheduled refresh rotates privately and publishes only allowed access fields`() {
        // given
        login()
        // when
        assertEquals("refreshed", refresher().cycle())
        // then
        val private = privateRecords.read("credentials.json")!!
        assertEquals("new-refresh", private.text("refresh_token"))
        assertEquals("old-verified-id", private.text("id_token"))
        assertEquals("same-user", private.text("subject"))
        assertTrue(private.path("scopes").toList().map { it.asString() }.contains("openid"))
        assertEquals(setOf("grant_type", "client_id", "refresh_token", "resource"), lastForm.keys)
        assertEquals("refresh_token", lastForm["grant_type"])
        assertEquals(RESOURCE, lastForm["resource"])
        val projected = output.read("credentials.json")!!
        assertEquals(setOf("client_id", "issuer", "subject", "access_token", "scopes", "expires_at"), projected.properties().map { it.key }.toSet())
        assertEquals("new-access", projected.text("access_token"))
        assertEquals(setOf("client_id"), output.read("registration.json")!!.properties().map { it.key }.toSet())
        assertNull(privateRecords.read("refresh-state.json"))
        assertEquals(0, jwksCalls)
        for (file in listOf("registration.json", "credentials.json")) {
            assertEquals(ownerFile, Files.getPosixFilePermissions(output.directory.resolve(file)))
        }
    }

    @Test fun `valid refreshed ID token is checked after private checkpoint`() {
        // given
        login()
        response = success() + ("id_token" to signed { claim("azp", "issued") })
        // when / then
        assertEquals("refreshed", refresher().cycle())
        assertTrue(pendingSeenByJwks)
        assertEquals(response["id_token"], privateRecords.read("credentials.json")!!.text("id_token"))
        assertEquals(1, jwksCalls)
    }

    @ParameterizedTest
    @ValueSource(strings = ["503", "404", "malformed", "missing-key"])
    fun `pending verification survives restart without replaying refresh token`(failure: String) {
        // given
        login()
        response = success() + ("id_token" to signed())
        jwksStatus = failure.toIntOrNull() ?: 200
        jwksBody = when (failure) {
            "malformed" -> "not JSON"
            "missing-key" -> "{\"keys\":[]}"
            else -> null
        }
        // when
        assertEquals("verification_pending", refresher().cycle())
        assertNull(output.read("credentials.json"))
        assertEquals("old-refresh", privateRecords.read("credentials.json")!!.text("refresh_token"))
        clock.now = clock.now.plusSeconds(700) // ID token expired now, but was valid at receipt.
        jwksStatus = 200
        jwksBody = null
        assertEquals("refreshed", refresher().cycle())
        // then
        assertEquals(1, tokenCalls)
        assertEquals(2, jwksCalls)
        assertEquals("new-refresh", privateRecords.read("credentials.json")!!.text("refresh_token"))
    }

    @Test fun `identity mismatch blocks same generation but fresh login recovers`() {
        // given
        login()
        response = success() + ("id_token" to signed { subject("other-user") })
        // when
        assertEquals("identity_mismatch", refresher().cycle())
        assertEquals("login_required", refresher().cycle())
        // then
        assertEquals(1, tokenCalls)
        assertNull(output.read("credentials.json"))
        assertEquals("same-user", privateRecords.read("credentials.json")!!.text("subject"))
        login(access = "new-login-access")
        response = success()
        assertEquals("refreshed", refresher().cycle())
        assertEquals(2, tokenCalls)
    }

    @Test fun `invalid JWT claims never publish rotated credentials`() {
        // given: each independent credential generation receives a signed but invalid ID token
        val cases: List<JWTClaimsSet.Builder.() -> Unit> = listOf(
            { issuer("https://other.example") }, { audience("other-client") }, { claim("azp", "other-client") },
            { audience(listOf("issued", "other-client")) }, { issueTime(Date.from(clock.instant().plusSeconds(120))) },
            { expirationTime(Date.from(clock.instant().minusSeconds(120))) },
            { notBeforeTime(Date.from(clock.instant().plusSeconds(120))) },
        )
        cases.forEachIndexed { index, claims ->
            login(access = "generation-$index")
            response = success() + ("id_token" to signed(claims))
            // when / then
            assertTrue(refresher().cycle() in setOf("identity_mismatch", "invalid_id_token"))
            assertNull(output.read("credentials.json"))
            assertEquals("blocked", privateRecords.read("refresh-state.json")!!.text("phase"))
        }
    }

    @Test fun `revoked grant and incomplete successful responses block until login`() {
        // given
        val cases = listOf(success() - "refresh_token", success() + ("token_type" to "Basic"),
            success() + ("expires_in" to 0), success() + ("scope" to "openid"), success() + ("id_token" to "invalid"))
        cases.forEachIndexed { index, value ->
            login(access = "generation-$index")
            response = value
            // when / then
            refresher().cycle()
            assertNull(output.read("credentials.json"))
            assertEquals("login_required", refresher().cycle())
        }
        login(access = "revoked")
        tokenStatus = 400
        assertEquals("authorization_rejected", refresher().cycle())
        assertEquals("login_required", refresher().cycle())
    }

    @Test fun `transient explicit HTTP error retries while retaining only unexpired projection`() {
        // given
        login()
        tokenStatus = 429
        // when / then
        assertEquals("retry_later", refresher().cycle())
        assertEquals("old-access", output.read("credentials.json")!!.text("access_token"))
        clock.now = clock.now.plusSeconds(31)
        tokenStatus = 503
        assertEquals("retry_later", refresher().cycle())
        assertNull(output.read("credentials.json"))
        tokenStatus = 200
        assertEquals("refreshed", refresher().cycle())
        assertEquals(3, tokenCalls)
    }

    @Test fun `request marker after crash prevents replay of unknown provider outcome`() {
        // given
        login()
        tokenStatus = 302
        // when / then: redirects are terminal, never followed
        assertEquals("authorization_rejected", refresher().cycle())
        val state = privateRecords.read("refresh-state.json")!!
        privateRecords.write("refresh-state.json", mapOf("generation" to state.text("generation"), "phase" to "requesting"))
        assertEquals("login_required", refresher().cycle())
        assertEquals(1, tokenCalls)
        assertNull(output.read("credentials.json"))
    }

    @Test fun `malformed and oversized responses cannot leak through diagnostics or be retried`() {
        // given
        login()
        rawResponse = "{\"access_token\":\"secret-marker\",\"access_token\":\"duplicate\"}"
        // when / then
        val status = refresher().cycle()
        assertEquals("invalid_response", status)
        assertFalse(status.contains("secret-marker"))
        assertEquals("login_required", refresher().cycle())
        login(access = "fresh-generation")
        rawResponse = "x".repeat(MAX_BYTES + 1)
        assertEquals("ambiguous_transport", refresher().cycle())
        assertEquals("login_required", refresher().cycle())
        assertNull(output.read("credentials.json"))
    }

    @Test fun `empty invalid and missing records remove stale projection without network calls`() {
        // given
        login(expires = clock.instant().plusSeconds(600))
        assertEquals("waiting", refresher().cycle())
        privateRecords.delete("credentials.json")
        // when / then
        assertEquals("login_required", refresher().cycle())
        assertNull(output.read("credentials.json"))
        privateRecords.write("credentials.json", mapOf("access_token" to "malformed"))
        assertEquals("invalid_record", refresher().cycle())
        privateRecords.delete("credentials.json")
        privateRecords.delete("registration.json")
        assertEquals("login_required", refresher().cycle())
        assertEquals(0, tokenCalls)
    }

    @Test fun `earliest refresh time delays refresh even near expiry and accepts both provider formats`() {
        val initialTime = clock.instant()
        for (earliest in listOf<Any>(clock.instant().plusSeconds(7200).epochSecond, clock.instant().plusSeconds(7200).toString())) {
            // given
            clock.now = initialTime
            login(access = "generation-$earliest")
            response = success() + ("earliest_refresh_at" to earliest)
            assertEquals("refreshed", refresher().cycle())
            val calls = tokenCalls
            // when / then
            clock.now = clock.now.plusSeconds(3570)
            assertEquals("waiting", refresher().cycle())
            assertEquals(calls, tokenCalls)
            clock.now = clock.now.plusSeconds(31)
            assertEquals("waiting", refresher().cycle())
            assertNull(output.read("credentials.json"))
        }
    }

    @Test fun `null optional refresh fields preserve previously verified identity and scope`() {
        // given
        login()
        response = success() + mapOf("id_token" to null, "scope" to null, "earliest_refresh_at" to null)
        // when / then
        assertEquals("refreshed", refresher().cycle())
        assertEquals("old-verified-id", privateRecords.read("credentials.json")!!.text("id_token"))
        assertTrue(privateRecords.read("credentials.json")!!.path("scopes").toList().map { it.asString() }.contains("openid"))
        assertEquals(0, jwksCalls)
    }

    @Test fun `directory guard coordinates writers and stale guard is never stolen`() {
        // given
        login()
        val guard = privateRecords.directory.resolve("command.guard")
        Files.createDirectory(guard)
        // when / then
        assertEquals("busy", refresher().cycle())
        assertTrue(Files.exists(guard))
        assertEquals(0, tokenCalls)
        Files.delete(guard)
        assertEquals("refreshed", refresher().cycle())
        assertFalse(Files.exists(guard))
    }

    @Test fun `native file lock is honored and exceptions release the directory guard`() {
        // given
        privateRecords.locked { }
        FileChannel.open(privateRecords.directory.resolve("command.lock"), WRITE).use { channel ->
            channel.lock().use {
                // when / then
                assertEquals("busy", refresher().cycle())
                assertFalse(Files.exists(privateRecords.directory.resolve("command.guard")))
            }
        }
        assertThrows(IllegalStateException::class.java) { privateRecords.locked { error("synthetic") } }
        assertFalse(Files.exists(privateRecords.directory.resolve("command.guard")))
    }

    @Test fun `symbolic links unsafe files and oversized records are rejected`() {
        // given
        val target = temporary.toRealPath().resolve("sentinel")
        Files.writeString(target, "unchanged")
        Files.createSymbolicLink(privateRecords.directory.resolve("credentials.json"), target)
        // when / then
        assertThrows(Failure::class.java) { privateRecords.read("credentials.json") }
        assertThrows(Failure::class.java) { privateRecords.write("credentials.json", emptyMap<String, String>()) }
        assertEquals("unchanged", Files.readString(target))
        Files.delete(privateRecords.directory.resolve("credentials.json"))
        privateRecords.write("credentials.json", mapOf("x" to "value"))
        Files.setPosixFilePermissions(privateRecords.directory.resolve("credentials.json"), PosixFilePermissions.fromString("rw-r--r--"))
        assertThrows(Failure::class.java) { privateRecords.read("credentials.json") }
        assertThrows(Failure::class.java) { privateRecords.writeBytes("large", ByteArray(MAX_BYTES + 1)) }
    }

    private class TestClock(var now: Instant) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
        override fun instant(): Instant = now
    }

    companion object { private val key = RSAKeyGenerator(2048).keyID("synthetic-test-key").generate() }
}
