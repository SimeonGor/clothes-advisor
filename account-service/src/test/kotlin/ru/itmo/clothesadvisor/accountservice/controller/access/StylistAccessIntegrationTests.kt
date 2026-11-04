package ru.itmo.clothesadvisor.accountservice.controller.access

import com.jayway.jsonpath.JsonPath
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import ru.itmo.clothesadvisor.accountservice.config.TestJwt
import ru.itmo.clothesadvisor.accountservice.config.PostgresIntegrationTest

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class StylistAccessIntegrationTests : PostgresIntegrationTest() {
    @LocalServerPort private var port = 0
    private val client = HttpClient.newHttpClient()

    @AfterEach fun closeClient() = client.close()

    @Test
    fun `grant and revoke are idempotent and owner comes only from JWT`() {
        // given
        val owner = user()
        val other = user()
        val stylist = user("STYLIST")
        val spoofedPath = "$ACCESS/$stylist?ownerId=$other"
        val body = """{"ownerId":$other}"""
        assertThat(ids(send("GET", ACCESS, owner.toString()))).isEmpty()
        assertThat(ids(send("GET", CLIENTS, stylist.toString()))).isEmpty()

        // when
        val first = send("PUT", spoofedPath, owner.toString(), body)
        val repeated = send("PUT", spoofedPath, owner.toString(), body)

        // then
        assertEmpty(first, 204)
        assertEmpty(repeated, 204)
        assertThat(pairs()).containsExactly(mapOf("owner_id" to owner, "stylist_id" to stylist))
        assertThat(ids(send("GET", "$ACCESS?ownerId=$owner", other.toString()))).isEmpty()

        // when
        val unrelated = send("DELETE", "$ACCESS/$stylist?ownerId=$owner", other.toString())

        // then
        assertEmpty(unrelated, 204)
        assertThat(pairs()).hasSize(1)

        // when
        val revoked = send("DELETE", spoofedPath, owner.toString(), body)
        val revokedAgain = send("DELETE", spoofedPath, owner.toString(), body)
        val absentTarget = send("DELETE", "$ACCESS/${Long.MAX_VALUE}", owner.toString())

        // then
        listOf(revoked, revokedAgain, absentTarget).forEach { assertEmpty(it, 204) }
        assertThat(pairs()).isEmpty()
        assertThat(ids(send("GET", ACCESS, owner.toString()))).isEmpty()
        assertThat(ids(send("GET", CLIENTS, stylist.toString()))).isEmpty()
    }

    @Test
    fun `simultaneous HTTP grants return no content and store one pair`() {
        // given
        val owner = user()
        val stylist = user("STYLIST")
        val ready = CountDownLatch(4)
        val start = CountDownLatch(1)
        Executors.newFixedThreadPool(4).use { executor ->
            val pending = (1..4).map {
                executor.submit(Callable {
                    ready.countDown()
                    check(start.await(10, TimeUnit.SECONDS))
                    send("PUT", "$ACCESS/$stylist", owner.toString())
                })
            }
            try {
                assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue()

                // when
                start.countDown()
                val responses = pending.map { it.get(20, TimeUnit.SECONDS) }

                // then
                responses.forEach { assertEmpty(it, 204) }
                assertThat(pairs())
                    .containsExactly(mapOf("owner_id" to owner, "stylist_id" to stylist))
            } finally {
                start.countDown()
            }
        }
    }

    @Test
    fun `every public operation rejects invalid unknown and blocked actors`() {
        // given
        val owner = user()
        val stylist = user("STYLIST")
        val blocked = user(status = "BLOCKED")
        val actors = listOf(null to 401, "bad" to 401,
            Long.MAX_VALUE.toString() to 401, blocked.toString() to 403)
        val operations = listOf("PUT" to "$ACCESS/$stylist", "DELETE" to "$ACCESS/$stylist",
            "GET" to ACCESS, "GET" to CLIENTS)
        storeGrant(owner, stylist)

        for ((actor, status) in actors) {
            for ((method, path) in operations) {
                // when
                val response = send(method, path, actor)

                // then
                assertError(response, status)
            }
        }
        assertThat(pairs()).containsExactly(mapOf("owner_id" to owner, "stylist_id" to stylist))
    }

    @Test
    fun `same JWT follows current role and status while retaining stored grants`() {
        // given
        val actor = user()
        val stylist = user("STYLIST")
        val token = TestJwt.sign(actor)
        assertEmpty(send("PUT", "$ACCESS/$stylist", token), 204)

        for ((role, status) in listOf("ADMIN" to "ACTIVE", "USER" to "BLOCKED",
            "STYLIST" to "ACTIVE")) {
            // when
            change(actor, role, status)

            // then
            assertError(send("PUT", "$ACCESS/$stylist", token), 403)
            assertError(send("DELETE", "$ACCESS/$stylist", token), 403)
            assertError(send("GET", ACCESS, token), 403)
            assertThat(pairs()).hasSize(1)
        }

        // when
        change(actor, "USER")
        val restored = send("GET", ACCESS, token)
        val revoked = send("DELETE", "$ACCESS/$stylist", token)

        // then
        assertThat(ids(restored)).containsExactly(stylist)
        assertEmpty(revoked, 204)
        assertThat(pairs()).isEmpty()
    }

    @ParameterizedTest
    @CsvSource("USER, ACTIVE", "ADMIN, ACTIVE", "STYLIST, BLOCKED")
    fun `grant validates target even when pair exists and revoke ignores target state`(
        role: String,
        status: String,
    ) {
        // given
        val owner = user()
        val stylist = user("STYLIST")
        assertEmpty(send("PUT", "$ACCESS/$stylist", owner.toString()), 204)
        assertEmpty(send("PUT", "$ACCESS/$owner", owner.toString()), 404)
        change(stylist, role, status)

        // when
        val repeated = send("PUT", "$ACCESS/$stylist", owner.toString())
        val missing = send("PUT", "$ACCESS/${Long.MAX_VALUE}", owner.toString())
        val recipients = send("GET", ACCESS, owner.toString())

        // then
        assertEmpty(repeated, 404)
        assertEmpty(missing, 404)
        assertThat(pairs()).hasSize(1)
        assertThat(ids(recipients)).containsExactly(stylist)

        // when
        val revoked = send("DELETE", "$ACCESS/$stylist", owner.toString())
        val repeatedRevoke = send("DELETE", "$ACCESS/$stylist", owner.toString())
        val invalidNewGrant = send("PUT", "$ACCESS/$stylist", owner.toString())

        // then
        assertEmpty(revoked, 204)
        assertEmpty(repeatedRevoke, 204)
        assertEmpty(invalidNewGrant, 404)
        assertThat(pairs()).isEmpty()
    }

    @Test
    fun `stored grants survive role and status changes and become effective again`() {
        // given
        val owner = user()
        val stylist = user("STYLIST")
        val unrelated = user("STYLIST")
        val ownerIdentity = identity(owner)
        val stylistIdentity = identity(stylist)
        storeGrant(owner, stylist)
        assertThat(rows(send("GET", CLIENTS, stylist.toString()))).containsExactly(ownerIdentity)
        assertThat(ids(send("GET", "$CLIENTS?stylistId=$stylist", unrelated.toString()))).isEmpty()

        for ((role, status) in listOf("USER" to "BLOCKED", "ADMIN" to "ACTIVE",
            "STYLIST" to "ACTIVE")) {
            // when
            change(owner, role, status)
            val suspended = send("GET", CLIENTS, stylist.toString())
            change(owner, "USER")
            val restoredRecipients = send("GET", ACCESS, owner.toString())
            val restored = send("GET", CLIENTS, stylist.toString())

            // then
            assertThat(ids(suspended)).isEmpty()
            assertThat(rows(restoredRecipients)).containsExactly(stylistIdentity)
            assertThat(rows(restored)).containsExactly(ownerIdentity)
            assertThat(pairs())
                .containsExactly(mapOf("owner_id" to owner, "stylist_id" to stylist))
        }
        for ((role, status) in listOf("STYLIST" to "BLOCKED", "USER" to "ACTIVE",
            "ADMIN" to "ACTIVE")) {
            // when
            change(stylist, role, status)
            val suspended = send("GET", CLIENTS, stylist.toString())
            val recipients = send("GET", ACCESS, owner.toString())
            change(stylist, "STYLIST")
            val restoredRecipients = send("GET", ACCESS, owner.toString())
            val restored = send("GET", CLIENTS, stylist.toString())

            // then
            assertError(suspended, 403)
            assertThat(rows(recipients)).containsExactly(stylistIdentity)
            assertThat(rows(restoredRecipients)).containsExactly(stylistIdentity)
            assertThat(rows(restored)).containsExactly(ownerIdentity)
            assertThat(pairs())
                .containsExactly(mapOf("owner_id" to owner, "stylist_id" to stylist))
        }
    }

    @ParameterizedTest
    @CsvSource("recipients", "clients")
    fun `lists sort by id paginate and expose only public fields`(kind: String) {
        // given
        val actor = user(if (kind == "clients") "STYLIST" else "USER")
        val count = if (kind == "clients") 54 else 52
        val expected = (1..count).map {
            user(if (kind == "clients") "USER" else "STYLIST")
        }
        for (id in expected.reversed()) {
            if (kind == "clients") storeGrant(id, actor) else storeGrant(actor, id)
        }
        if (kind == "recipients") {
            change(expected[0], "STYLIST", "BLOCKED")
            change(expected[1], "USER")
        } else {
            change(expected[0], "USER", "BLOCKED")
            change(expected[1], "STYLIST")
            storeGrant(user(status = "BLOCKED"), actor)
            storeGrant(user("ADMIN"), actor)
            storeGrant(user("STYLIST"), actor)
        }
        val visible = if (kind == "clients") expected.drop(2) else expected
        val path = if (kind == "clients") CLIENTS else ACCESS
        val header = actor.toString()

        // when
        val first = send("GET", path, header)
        val next = send("GET", "$path?page=1&size=50", header)
        val single = send("GET", "$path?page=1&size=1", header)
        val empty = send("GET", "$path?page=2147483647&size=1", header)

        // then
        assertThat(ids(first)).containsExactlyElementsOf(visible.take(50))
        assertThat(ids(next)).containsExactlyElementsOf(visible.drop(50))
        assertThat(ids(single)).containsExactly(visible[1])
        assertThat(ids(empty)).isEmpty()
        for (response in listOf(first, next, single, empty)) {
            assertThat(response.headers().firstValue("X-Total-Count")).isEmpty()
            assertThat(response.body()).doesNotContain("private-hash", "password")
            for (row in rows(response)) {
                assertThat(row.keys).containsExactlyInAnyOrder("id", "login")
                val login = jdbc.queryForObject(
                    "SELECT login FROM app_user WHERE id = ?", String::class.java,
                    (row["id"] as Number).toLong(),
                )
                assertThat(row["login"]).isEqualTo(login)
            }
        }
    }

    @Test
    fun `invalid pagination and path ids return sanitized bad request`() {
        // given
        val actor = user().toString()
        val stylist = user("STYLIST").toString()
        val queries = listOf("page=-1", "size=0", "size=-1", "size=51", "page=no", "size=no",
            "page=1.5", "page=0&size=1.5",
            "page=2147483647&size=50", "page=2147483648", "size=2147483648")

        for (path in listOf(ACCESS, CLIENTS)) {
            for (query in queries) {
                // when
                val response = send("GET", "$path?$query", if (path == CLIENTS) stylist else actor)

                // then
                assertError(response, 400)
            }
        }
        for (method in listOf("PUT", "DELETE")) {
            for (id in listOf("text", "9223372036854775808")) {
                // when
                val response = send(method, "$ACCESS/$id", actor)

                // then
                assertError(response, 400)
            }
        }
        assertThat(pairs()).isEmpty()
    }

    @Test
    fun `OpenAPI access operations do not expose identity headers`() {
        // given
        val operations = listOf("$ACCESS/{stylistId}" to "put", "$ACCESS/{stylistId}" to "delete",
            ACCESS to "get", CLIENTS to "get")

        // when
        val response = send("GET", "/v3/api-docs")

        // then
        assertThat(response.statusCode()).isEqualTo(200)
        for ((path, method) in operations) {
            val parameters: List<Map<String, Any?>> =
                JsonPath.read(response.body(), "$.paths['$path'].$method.parameters")
            assertThat(parameters.map { it["name"] }).doesNotContain("X-User-Id")
        }
    }

    private fun user(role: String = "USER", status: String = "ACTIVE"): Long =
        jdbc.queryForObject(
            """INSERT INTO app_user (login, password_hash, role, status, version)
                VALUES (?, 'private-hash', ?::user_role, ?::user_status, 1) RETURNING id""",
            Long::class.java, "access-${UUID.randomUUID()}", role, status,
        )!!

    private fun storeGrant(owner: Long, stylist: Long) {
        jdbc.update("INSERT INTO access_grant (owner_id, stylist_id) VALUES (?, ?)", owner, stylist)
    }

    private fun change(id: Long, role: String, status: String = "ACTIVE") {
        jdbc.update("UPDATE app_user SET role = ?::user_role, status = ?::user_status WHERE id = ?",
            role, status, id)
    }

    private fun pairs() = jdbc.queryForList(
        "SELECT owner_id, stylist_id FROM access_grant ORDER BY owner_id, stylist_id",
    )

    private fun send(
        method: String,
        path: String,
        actor: String? = null,
        body: String? = null,
    ): HttpResponse<String> {
        val request = HttpRequest.newBuilder(URI("http://localhost:$port$path"))
            .timeout(Duration.ofSeconds(15))
        actor?.let { request.header("Authorization", "Bearer " +
            (it.toLongOrNull()?.let(TestJwt::sign) ?: it)) }
        request.header("X-User-Id", "9223372036854775807")
        if (body != null) request.header("Content-Type", "application/json")
        request.method(method, body?.let { HttpRequest.BodyPublishers.ofString(it) }
            ?: HttpRequest.BodyPublishers.noBody())
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun rows(response: HttpResponse<String>): List<Map<String, Any?>> {
        assertThat(response.statusCode()).isEqualTo(200)
        return JsonPath.read(response.body(), "$")
    }

    private fun ids(response: HttpResponse<String>) =
        rows(response).map { (it["id"] as Number).toLong() }

    private fun identity(id: Long): Map<String, Any> =
        mapOf(
            "id" to id.toInt(),
            "login" to jdbc.queryForObject(
                "SELECT login FROM app_user WHERE id = ?",
                String::class.java,
                id,
            )!!,
        )

    private fun assertEmpty(response: HttpResponse<String>, status: Int) {
        assertThat(response.statusCode()).isEqualTo(status)
        assertThat(response.body()).isEmpty()
    }

    private fun assertError(response: HttpResponse<String>, status: Int) {
        assertThat(response.statusCode()).isEqualTo(status)
        when (status) {
            401 -> assertThat(response.body()).isEqualTo("""{"error":"unauthorized"}""")
            400 -> assertThat(response.body()).isEqualTo("""{"error":"invalid_request"}""")
            403 -> assertThat(response.body()).isEqualTo("""{"error":"forbidden"}""")
            404 -> assertThat(response.body()).isEmpty()
        }
    }

    companion object {
        private const val ACCESS = "/api/me/stylist-access"
        private const val CLIENTS = "/api/stylist/clients"
    }
}
