package refresher

import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import tools.jackson.databind.JsonNode

internal class Refresher(
    private val privateRecords: Records,
    private val output: Records,
    private val oauth: OAuth,
    private val clock: Clock = Clock.systemUTC(),
) {
    // The process loop is single-threaded. Shutdown waits for the current cycle to finish.
    fun cycle(): String = try {
        privateRecords.locked {
            try { update() }
            catch (e: Exception) {
                clearProjection()
                if (e is Failure) e.category else "operation_failed"
            }
        }
    } catch (e: Failure) { e.category }
    catch (_: Exception) { "storage_unavailable" }

    private fun update(): String {
        val registration = privateRecords.read("registration.json")
        val credentials = privateRecords.read("credentials.json")
        if (registration == null || credentials == null) {
            clearProjection()
            return "login_required"
        }
        validate(credentials, registration)
        val generation = fingerprint(credentials)
        val state = privateRecords.read("refresh-state.json")
        if (state != null && state.text("generation") == generation) {
            when (state.text("phase")) {
                "pending" -> return finish(state, credentials, registration)
                "requesting", "blocked" -> { clearProjection(); return "login_required" }
                else -> fail("invalid_record")
            }
        }
        val now = clock.instant()
        val expires = Instant.parse(credentials.text("expires_at"))
        val earliest = credentials.path("earliest_refresh_at").takeUnless { it.isMissingNode || it.isNull }
            ?.let { Instant.parse(credentials.text("earliest_refresh_at")) }
        if (expires.isAfter(now.plusSeconds(60)) || (earliest != null && now.isBefore(earliest))) {
            publish(credentials)
            return "waiting"
        }
        // A crash or lost response after this durable marker requires a new login. Never replay
        // an old refresh token whose consumption is unknown.
        privateRecords.write("refresh-state.json", mapOf("generation" to generation, "phase" to "requesting"))
        val response = try { oauth.refresh(credentials) }
        catch (e: Failure) {
            block(generation)
            throw e
        }
        // Persist the complete bounded response before any parsing or JWKS request can fail.
        val pending = json.valueToTree<JsonNode>(mapOf("generation" to generation, "phase" to "pending",
            "received_at" to clock.instant().toString(), "response" to response))
        privateRecords.write("refresh-state.json", pending)
        return finish(pending, credentials, registration)
    }

    private fun finish(state: JsonNode, previous: JsonNode, registration: JsonNode): String {
        val generation = state.text("generation")
        try {
            val response = state.path("response")
            val received = Instant.parse(state.text("received_at"))
            val candidate = candidate(response, previous, received)
            if (response.hasNonNull("id_token")) oauth.verify(response.text("id_token"), previous, received)
            validate(candidate, registration)
            // Keep the old identity and the prior ID token when the provider omits an ID token.
            privateRecords.write("credentials.json", candidate)
            privateRecords.delete("refresh-state.json")
            publish(candidate)
            return "refreshed"
        } catch (e: Failure) {
            clearProjection()
            if (e.category == "verification_unavailable") return "verification_pending"
            block(generation)
            throw e
        } catch (_: Exception) {
            // Pending remains durable, including after a disk failure. No old refresh replay.
            clearProjection()
            return "verification_pending"
        }
    }

    private fun candidate(response: JsonNode, previous: JsonNode, received: Instant): JsonNode {
        if (!response.text("token_type").equals("Bearer", ignoreCase = true)) fail("invalid_response")
        val seconds = response.path("expires_in")
        if (!seconds.isIntegralNumber || !seconds.canConvertToLong() || seconds.asLong() <= 0) fail("invalid_response")
        val expires = try { received.plusSeconds(seconds.asLong()) } catch (_: Exception) { fail("invalid_response") }
        val scopes = if (response.hasNonNull("scope")) response.text("scope").split(Regex("\\s+")).toSet()
            else previous.path("scopes").toList().map { it.asString() }.toSet()
        if (!scopes.containsAll(requiredScopes)) fail("invalid_response")
        val values = linkedMapOf<String, Any>(
            "client_id" to previous.text("client_id"), "issuer" to ISSUER, "subject" to previous.text("subject"),
            "access_token" to response.text("access_token"), "refresh_token" to response.text("refresh_token"),
            "id_token" to (if (response.hasNonNull("id_token")) response.text("id_token") else previous.text("id_token")),
            "scopes" to scopes.sorted(), "expires_at" to expires.toString(),
        )
        if (response.hasNonNull("earliest_refresh_at")) {
            val earliest = response.path("earliest_refresh_at")
            values["earliest_refresh_at"] = try {
                when {
                    earliest.isString -> Instant.parse(earliest.asString()).toString()
                    earliest.isIntegralNumber && earliest.canConvertToLong() && earliest.asLong() >= 0 ->
                        Instant.ofEpochSecond(earliest.asLong()).toString()
                    else -> fail("invalid_response")
                }
            } catch (_: Exception) { fail("invalid_response") }
        }
        return json.valueToTree(values)
    }

    private fun validate(credentials: JsonNode, registration: JsonNode) {
        if (registration.text("client_id") == "dynamic_agent_client" ||
            credentials.text("client_id") != registration.text("client_id") || credentials.text("issuer") != ISSUER
        ) fail("invalid_record")
        for (field in listOf("subject", "access_token", "refresh_token", "id_token")) credentials.text(field)
        val scopes = credentials.path("scopes")
        if (!scopes.isArray || scopes.any { !it.isString } || !scopes.toList().map { it.asString() }.containsAll(requiredScopes)) {
            fail("invalid_record")
        }
        Instant.parse(credentials.text("expires_at"))
    }

    private fun publish(credentials: JsonNode) {
        if (!Instant.parse(credentials.text("expires_at")).isAfter(clock.instant())) { clearProjection(); return }
        output.write("registration.json", mapOf("client_id" to credentials.text("client_id")))
        output.write("credentials.json", mapOf(
            "client_id" to credentials.text("client_id"), "issuer" to ISSUER, "subject" to credentials.text("subject"),
            "access_token" to credentials.text("access_token"), "scopes" to credentials.path("scopes"),
            "expires_at" to credentials.text("expires_at"),
        ))
    }

    private fun clearProjection() {
        output.delete("credentials.json")
        output.delete("registration.json")
    }

    private fun block(generation: String) {
        privateRecords.write("refresh-state.json", mapOf("generation" to generation, "phase" to "blocked"))
        clearProjection()
    }

    private fun fingerprint(node: JsonNode): String = MessageDigest.getInstance("SHA-256")
        .digest(json.writeValueAsBytes(node)).joinToString("") { "%02x".format(it) }
}
