package ru.itmo.clothesadvisor.controller.preferences

import com.jayway.jsonpath.JsonPath
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.Mockito.doThrow
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import ru.itmo.clothesadvisor.config.AccountBackedIntegrationTest
import ru.itmo.clothesadvisor.config.TestTimeConfiguration
import ru.itmo.clothesadvisor.config.insertAccessGrant
import ru.itmo.clothesadvisor.config.insertUser
import ru.itmo.clothesadvisor.config.updateUserRoleAndStatus
import ru.itmo.clothesadvisor.model.access.AccountUnavailableException
import ru.itmo.clothesadvisor.model.user.UserRole
import ru.itmo.clothesadvisor.model.user.UserStatus

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestTimeConfiguration::class)
@Execution(ExecutionMode.SAME_THREAD)
class StylePreferencesIntegrationTests : AccountBackedIntegrationTest() {
    @LocalServerPort private var port = 0
    @Autowired private lateinit var jdbc: JdbcTemplate
    private val client = HttpClient.newHttpClient()

    @AfterEach fun closeClient() = client.close()

    @Test
    fun `missing profile stays absent until explicit empty creation`() {
        // given
        val owner = user()

        // when
        val missing = request("GET", owner)
        val prematureUpdate = request("PUT", owner, """{"version":1}""")

        // then
        assertThat(missing.statusCode()).isEqualTo(404)
        assertThat(prematureUpdate.statusCode()).isEqualTo(409)
        assertThat(jdbc.queryForObject("SELECT count(*) FROM style_preferences", Long::class.java)).isZero()

        // when
        val created = request("PUT", owner, "{}")
        val fetched = request("GET", owner)
        val duplicate = request("PUT", owner, "{}")

        // then
        assertThat(created.statusCode()).isEqualTo(200)
        assertThat(fetched.statusCode()).isEqualTo(200)
        assertThat(fetched.body()).isEqualTo(created.body())
        assertThat(duplicate.statusCode()).isEqualTo(409)
        val body = JsonPath.parse(created.body())
        listOf("preferredColorsAndMaterials", "avoidances", "additionalWishes").forEach {
            assertThat(body.read<String>("$.$it")).isEmpty()
        }
        assertThat(body.read<Int>("$.version")).isEqualTo(1)
        for ((field, column) in listOf("createdAt" to "created_at", "modifiedAt" to "modified_at")) {
            assertThat(Instant.parse(body.read<String>("$.$field"))).isEqualTo(
                jdbc.queryForObject("SELECT $column FROM style_preferences WHERE owner_id = ?", Timestamp::class.java, owner)!!.toInstant(),
            )
        }
    }

    @Test
    fun `null fields replace previous values while no-op and stale writes preserve current state`() {
        // given
        val owner = user()
        val created = request("PUT", owner, """{"preferredColorsAndMaterials":"  Blue  ","avoidances":"Wool","additionalWishes":"Casual","version":null}""")
        assertThat(created.statusCode()).isEqualTo(200)

        // when
        val updated = request("PUT", owner, """{"version":1,"preferredColorsAndMaterials":"  Blue  ","avoidances":null}""")
        val noOp = request("PUT", owner, """{"version":2,"preferredColorsAndMaterials":"  Blue  "}""")
        val stale = request("PUT", owner, """{"version":1,"preferredColorsAndMaterials":"  Blue  "}""")
        val missingVersion = request("PUT", owner, """{"version":null}""")

        // then
        assertThat(updated.statusCode()).isEqualTo(200)
        assertThat(noOp.statusCode()).isEqualTo(200)
        assertThat(noOp.body()).isEqualTo(updated.body())
        assertThat(stale.statusCode()).isEqualTo(409)
        assertThat(missingVersion.statusCode()).isEqualTo(409)
        val body = JsonPath.parse(updated.body())
        assertThat(body.read<String>("$.preferredColorsAndMaterials")).isEqualTo("  Blue  ")
        assertThat(body.read<String>("$.avoidances")).isEmpty()
        assertThat(body.read<String>("$.additionalWishes")).isEmpty()
        assertThat(body.read<Int>("$.version")).isEqualTo(2)
        assertThat(jdbc.queryForObject("SELECT count(*) FROM style_preferences_history", Long::class.java)).isEqualTo(1)
    }

    @ParameterizedTest
    @ValueSource(strings = ["preferredColorsAndMaterials", "avoidances", "additionalWishes"])
    fun `each text field accepts two thousand characters and rejects excess`(field: String) {
        // given
        val owner = user()
        val text = "x".repeat(2000)

        // when
        val accepted = request("PUT", owner, """{"$field":"$text"}""")
        val rejected = request("PUT", owner, """{"version":1,"$field":"${text}x"}""")

        // then
        assertThat(accepted.statusCode()).isEqualTo(200)
        assertThat(JsonPath.parse(accepted.body()).read<String>("$.$field")).isEqualTo(text)
        assertThat(rejected.statusCode()).isEqualTo(400)
        assertThat(request("GET", owner).body()).isEqualTo(accepted.body())
    }

    @ParameterizedTest
    @ValueSource(strings = ["0", "-1"])
    fun `nonpositive version is invalid`(version: String) {
        // given
        val owner = user()

        // when
        val response = request("PUT", owner, """{"version":$version}""")

        // then
        assertThat(response.statusCode()).isEqualTo(400)
        assertThat(jdbc.queryForObject("SELECT count(*) FROM style_preferences", Long::class.java)).isZero()
    }

    @ParameterizedTest
    @ValueSource(strings = ["ADMIN", "STYLIST"])
    fun `non-user cannot read or write own profile or bypass client access`(role: String) {
        // given
        val actor = user(UserRole.valueOf(role))
        val owner = user()
        request("PUT", owner, "{}")

        // when
        val read = request("GET", actor)
        val write = request("PUT", actor, "{}")
        val shared = request("GET", actor, path = clientPath(owner))

        // then
        assertThat(read.statusCode()).isEqualTo(403)
        assertThat(write.statusCode()).isEqualTo(403)
        assertThat(shared.statusCode()).isEqualTo(404)
    }

    @Test
    fun `stylist grant permits only reading its owner and revocation takes effect`() {
        // given
        val owner = user()
        val otherOwner = user()
        val stylist = user(UserRole.STYLIST)
        jdbc.insertAccessGrant(owner, stylist)
        assertThat(request("GET", stylist, path = clientPath(owner)).statusCode()).isEqualTo(404)
        val own = request("PUT", owner, """{"additionalWishes":"Casual"}""")
        request("PUT", otherOwner, "{}")

        // when
        val shared = request("GET", stylist, path = clientPath(owner))
        val other = request("GET", stylist, path = clientPath(otherOwner))
        val write = request("PUT", stylist, "{}", clientPath(owner))

        // then
        assertThat(shared.statusCode()).isEqualTo(200)
        assertThat(shared.body()).isEqualTo(own.body())
        assertThat(other.statusCode()).isEqualTo(404)
        assertThat(write.statusCode()).isEqualTo(405)
        assertThat(jdbc.queryForObject("SELECT count(*) FROM style_preferences_history", Long::class.java)).isZero()

        // given
        jdbc.update("DELETE FROM access_grant WHERE owner_id = ? AND stylist_id = ?", owner, stylist)

        // when
        val revoked = request("GET", stylist, path = clientPath(owner))

        // then
        assertThat(revoked.statusCode()).isEqualTo(404)
    }

    @Test
    fun `fresh roles and statuses deny access after an allowed read`() {
        // given
        val owner = user()
        val stylist = user(UserRole.STYLIST)
        jdbc.insertAccessGrant(owner, stylist)
        request("PUT", owner, "{}")
        assertThat(request("GET", stylist, path = clientPath(owner)).statusCode()).isEqualTo(200)

        // when
        jdbc.updateUserRoleAndStatus(owner, UserRole.USER, UserStatus.BLOCKED)
        val ownBlocked = request("GET", owner)
        val blockedWrite = request("PUT", owner, """{"version":1}""")
        val blockedOwner = request("GET", stylist, path = clientPath(owner))
        jdbc.updateUserRoleAndStatus(owner, UserRole.STYLIST, UserStatus.ACTIVE)
        val changedOwnerRole = request("GET", stylist, path = clientPath(owner))
        jdbc.updateUserRoleAndStatus(owner, UserRole.USER, UserStatus.ACTIVE)
        jdbc.updateUserRoleAndStatus(stylist, UserRole.STYLIST, UserStatus.BLOCKED)
        val blockedStylist = request("GET", stylist, path = clientPath(owner))
        jdbc.updateUserRoleAndStatus(stylist, UserRole.ADMIN, UserStatus.ACTIVE)
        val changedStylistRole = request("GET", stylist, path = clientPath(owner))

        // then
        assertThat(ownBlocked.statusCode()).isEqualTo(403)
        assertThat(blockedWrite.statusCode()).isEqualTo(403)
        assertThat(blockedOwner.statusCode()).isEqualTo(404)
        assertThat(changedOwnerRole.statusCode()).isEqualTo(404)
        assertThat(blockedStylist.statusCode()).isEqualTo(403)
        assertThat(changedStylistRole.statusCode()).isEqualTo(404)
    }

    @Test
    fun `account failure becomes unavailable for own and client requests`() {
        // given
        val owner = user()
        val stylist = user(UserRole.STYLIST)
        doThrow(AccountUnavailableException()).`when`(accounts).getUser(owner)
        doThrow(AccountUnavailableException()).`when`(accounts).requireStylistAccess(stylist, owner)

        // when
        val own = request("GET", owner)
        val write = request("PUT", owner, "{}")
        val shared = request("GET", stylist, path = clientPath(owner))

        // then
        assertThat(own.statusCode()).isEqualTo(503)
        assertThat(write.statusCode()).isEqualTo(503)
        assertThat(shared.statusCode()).isEqualTo(503)
        assertThat(jdbc.queryForObject("SELECT count(*) FROM style_preferences", Long::class.java)).isZero()
    }

    private fun user(role: UserRole = UserRole.USER) =
        jdbc.insertUser(UUID.randomUUID().toString(), "!", role).id

    private fun clientPath(owner: Long) = "/api/stylist/clients/$owner/style-preferences"

    private fun request(
        method: String,
        actor: Long,
        body: String? = null,
        path: String = "/api/me/style-preferences",
    ): HttpResponse<String> {
        val request = HttpRequest.newBuilder(URI("http://localhost:$port$path"))
            .header("Authorization", bearer(actor.toString()))
            .method(method, body?.let(HttpRequest.BodyPublishers::ofString) ?: HttpRequest.BodyPublishers.noBody())
        if (body != null) request.header("Content-Type", "application/json")
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString())
    }
}
