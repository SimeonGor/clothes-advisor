package ru.itmo.clothesadvisor.accountservice.controller.internalapi

import com.jayway.jsonpath.JsonPath
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.UUID
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import ru.itmo.clothesadvisor.accountservice.config.PostgresIntegrationTest

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class InternalAccountIntegrationTests : PostgresIntegrationTest() {
    @LocalServerPort private var port = 0
    private val client = HttpClient.newHttpClient()

    @AfterEach fun closeClient() = client.close()

    @ParameterizedTest
    @CsvSource("USER, ACTIVE", "USER, BLOCKED", "STYLIST, ACTIVE", "STYLIST, BLOCKED",
        "ADMIN, ACTIVE", "ADMIN, BLOCKED")
    fun `user lookup exposes exactly current id login role and status`(
        role: String,
        status: String,
    ) {
        // given
        val id = user(role, status)
        val login = jdbc.queryForObject("SELECT login FROM app_user WHERE id = ?",
            String::class.java, id)

        // when
        val response = get("/internal/users/$id", "invalid-actor-header")

        // then
        assertThat(response.statusCode()).isEqualTo(200)
        val body: Map<String, Any?> = JsonPath.read(response.body(), "$")
        assertThat(body.keys).containsExactlyInAnyOrder("id", "login", "role", "status")
        assertThat((body["id"] as Number).toLong()).isEqualTo(id)
        assertThat(body["login"]).isEqualTo(login)
        assertThat(body["role"]).isEqualTo(role)
        assertThat(body["status"]).isEqualTo(status)
        assertThat(response.body()).doesNotContain("private-hash", "password", "version", "At")
    }

    @Test
    fun `user lookup observes updates and deletion on subsequent requests`() {
        // given
        val id = user()
        assertThat(get("/internal/users/$id").statusCode()).isEqualTo(200)

        // when
        change(id, "STYLIST", "BLOCKED")
        val updated = get("/internal/users/$id")
        jdbc.update("DELETE FROM app_user WHERE id = ?", id)
        val deleted = get("/internal/users/$id")

        // then
        assertThat(updated.statusCode()).isEqualTo(200)
        assertThat(JsonPath.read<String>(updated.body(), "$.role")).isEqualTo("STYLIST")
        assertThat(JsonPath.read<String>(updated.body(), "$.status")).isEqualTo("BLOCKED")
        assertEmpty(deleted, 404)
    }

    @ParameterizedTest
    @CsvSource("USER, ACTIVE", "USER, BLOCKED", "STYLIST, ACTIVE", "STYLIST, BLOCKED",
        "ADMIN, ACTIVE", "ADMIN, BLOCKED")
    fun `admin snapshot permits every existing owner role and status`(
        role: String,
        status: String,
    ) {
        // given
        val admin = user("ADMIN")
        val owner = user(role, status)

        // when
        val response = get(adminPath(admin, owner), "malformed-header")

        // then
        assertEmpty(response, 204)
        assertEmpty(get(adminPath(admin, admin)), 204)
    }

    @ParameterizedTest
    @CsvSource("USER, ACTIVE", "USER, BLOCKED", "STYLIST, ACTIVE", "STYLIST, BLOCKED",
        "ADMIN, BLOCKED")
    fun `invalid admin is forbidden before owner absence`(role: String, status: String) {
        // given
        val admin = user(role, status)
        val owner = user()

        // when
        val present = get(adminPath(admin, owner))
        val absent = get(adminPath(admin, Long.MAX_VALUE))

        // then
        assertError(present, 403, "forbidden")
        assertError(absent, 403, "forbidden")
    }

    @Test
    fun `admin check observes blocking role changes owner deletion and admin deletion`() {
        // given
        val admin = user("ADMIN")
        val owner = user()
        val path = adminPath(admin, owner)
        assertEmpty(get(path), 204)

        // when
        change(admin, "ADMIN", "BLOCKED")
        val blocked = get(path)
        change(admin, "USER")
        val wrongRole = get(path)
        change(admin, "ADMIN")
        val restored = get(path)
        jdbc.update("DELETE FROM app_user WHERE id = ?", owner)
        val ownerDeleted = get(path)
        jdbc.update("DELETE FROM app_user WHERE id = ?", admin)
        val bothDeleted = get(path)

        // then
        assertError(blocked, 403, "forbidden")
        assertError(wrongRole, 403, "forbidden")
        assertEmpty(restored, 204)
        assertEmpty(ownerDeleted, 404)
        assertError(bothDeleted, 403, "forbidden")
    }

    @Test
    fun `stylist requires the exact grant and observes revocation on next request`() {
        // given
        val owner = user()
        val stylist = user("STYLIST")
        val otherOwner = user()
        val otherStylist = user("STYLIST")
        val path = stylistPath(stylist, owner)
        assertEmpty(get(path), 404)
        grant(owner, otherStylist)
        grant(otherOwner, stylist)
        assertEmpty(get(path), 404)

        // when
        grant(owner, stylist)
        val allowed = get(path, "not-a-number")
        jdbc.update("DELETE FROM access_grant WHERE owner_id = ? AND stylist_id = ?",
            owner, stylist)
        val revoked = get(path)

        // then
        assertEmpty(allowed, 204)
        assertEmpty(revoked, 404)
    }

    @ParameterizedTest
    @CsvSource("USER, ACTIVE", "USER, BLOCKED", "STYLIST, ACTIVE", "STYLIST, BLOCKED",
        "ADMIN, ACTIVE", "ADMIN, BLOCKED")
    fun `stylist grant respects both current participant roles and statuses`(
        role: String,
        status: String,
    ) {
        // given
        val owner = user()
        val stylist = user("STYLIST")
        grant(owner, stylist)
        val path = stylistPath(stylist, owner)

        // when
        change(owner, role, status)
        val ownerState = get(path)
        change(owner, "USER")
        change(stylist, role, status)
        val stylistState = get(path)
        change(stylist, "STYLIST")
        val restored = get(path)

        // then
        assertEmpty(ownerState, if (role == "USER" && status == "ACTIVE") 204 else 404)
        assertEmpty(stylistState, if (role == "STYLIST" && status == "ACTIVE") 204 else 404)
        assertEmpty(restored, 204)
        assertThat(jdbc.queryForObject("SELECT count(*) FROM access_grant", Long::class.java))
            .isEqualTo(1)
    }

    @ParameterizedTest
    @ValueSource(longs = [0, -1, Long.MIN_VALUE, Long.MAX_VALUE])
    fun `absent numeric ids preserve endpoint specific absence and denial priority`(missing: Long) {
        // given
        val owner = user()
        val admin = user("ADMIN")
        val stylist = user("STYLIST")
        grant(owner, stylist)

        // when
        val lookup = get("/internal/users/$missing")
        val missingAdmin = get(adminPath(missing, owner))
        val missingBoth = get(adminPath(missing, missing))
        val missingOwner = get(adminPath(admin, missing))
        val missingStylist = get(stylistPath(missing, owner))
        val missingClient = get(stylistPath(stylist, missing))

        // then
        listOf(lookup, missingOwner, missingStylist, missingClient).forEach { assertEmpty(it, 404) }
        listOf(missingAdmin, missingBoth).forEach { assertError(it, 403, "forbidden") }
    }

    @ParameterizedTest
    @ValueSource(strings = ["bad", "1.0", "9223372036854775808", "-9223372036854775809"])
    fun `non long path values use scoped internal invalid request code`(invalid: String) {
        // given
        val admin = user("ADMIN")
        val paths = listOf(
            "/internal/users/$invalid",
            "/internal/admin-access/$invalid/users/1",
            "/internal/admin-access/$admin/users/$invalid",
            "/internal/stylist-access/$invalid/clients/1",
            "/internal/stylist-access/1/clients/$invalid",
        )

        for (path in paths) {
            // when
            val response = get(path)

            // then
            assertError(response, 400, "invalid_request")
        }
    }

    @Test
    fun `public errors retain error key and OpenAPI exposes public operations only`() {
        // given
        val publicPaths = listOf("/api/admin/users", "/api/admin/users/{id}",
            "/api/me/stylist-access", "/api/me/stylist-access/{stylistId}", "/api/stylist/clients")

        // when
        val publicError = get("/api/me/stylist-access", "invalid")
        val schema = get("/v3/api-docs")

        // then
        assertThat(publicError.statusCode()).isEqualTo(400)
        assertThat(publicError.body()).isEqualTo("""{"error":"invalid_request"}""")
        assertThat(schema.statusCode()).isEqualTo(200)
        val paths: Map<String, Any?> = JsonPath.read(schema.body(), "$.paths")
        assertThat(paths.keys).containsAll(publicPaths)
        assertThat(paths.keys).noneMatch { it.startsWith("/internal") }
        assertThat(schema.body()).doesNotContain("InternalUserResponse", "InternalApiErrorResponse")
    }

    private fun user(role: String = "USER", status: String = "ACTIVE"): Long =
        jdbc.queryForObject(
            """INSERT INTO app_user (login, password_hash, role, status, version)
                VALUES (?, 'private-hash', ?::user_role, ?::user_status, 1) RETURNING id""",
            Long::class.java, "internal-${UUID.randomUUID()}", role, status,
        )!!

    private fun grant(owner: Long, stylist: Long) {
        jdbc.update("INSERT INTO access_grant (owner_id, stylist_id) VALUES (?, ?)", owner, stylist)
    }

    private fun change(id: Long, role: String, status: String = "ACTIVE") {
        jdbc.update("UPDATE app_user SET role = ?::user_role, status = ?::user_status WHERE id = ?",
            role, status, id)
    }

    private fun adminPath(admin: Long, owner: Long) = "/internal/admin-access/$admin/users/$owner"

    private fun stylistPath(stylist: Long, owner: Long) =
        "/internal/stylist-access/$stylist/clients/$owner"

    private fun get(path: String, actor: String? = null): HttpResponse<String> {
        val request = HttpRequest.newBuilder(URI("http://localhost:$port$path"))
            .timeout(Duration.ofSeconds(15)).GET()
        actor?.let { request.header("X-User-Id", it) }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun assertEmpty(response: HttpResponse<String>, status: Int) {
        assertThat(response.statusCode()).isEqualTo(status)
        assertThat(response.body()).isEmpty()
    }

    private fun assertError(response: HttpResponse<String>, status: Int, code: String) {
        assertThat(response.statusCode()).isEqualTo(status)
        assertThat(response.body()).isEqualTo("""{"code":"$code"}""")
    }
}
