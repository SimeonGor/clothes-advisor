package ru.itmo.clothesadvisor.controller.access

import com.jayway.jsonpath.JsonPath
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import ru.itmo.clothesadvisor.config.PostgresIntegrationTest
import ru.itmo.clothesadvisor.config.TestTimeConfiguration
import ru.itmo.clothesadvisor.config.insertUser
import ru.itmo.clothesadvisor.model.user.AppUser
import ru.itmo.clothesadvisor.model.user.UserRole
import ru.itmo.clothesadvisor.model.user.UserStatus
import ru.itmo.clothesadvisor.service.user.AppUserService

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestTimeConfiguration::class)
@Execution(ExecutionMode.SAME_THREAD)
class StylistAccessIntegrationTests : PostgresIntegrationTest() {
    @LocalServerPort private var port: Int = 0
    @Autowired private lateinit var users: AppUserService
    @Autowired private lateinit var jdbc: JdbcTemplate
    private val client = HttpClient.newHttpClient()
    private val passwordHash = "!"

    @AfterEach fun closeClient() = client.close()

    @Test
    fun `grants and revokes are idempotent isolated and use only the selected owner`() {

        // given
        val owner = user()
        val other = user()
        val stylist = user(UserRole.STYLIST)
        val actorId = actorId(owner)
        val otherActorId = actorId(other)
        val stylistActorId = actorId(stylist)
        val path = "$ACCESS/${stylist.id}"

        // when
        val initialGrants = rows(request("GET", ACCESS, actorId))

        // then
        assertThat(initialGrants).isEmpty()

        // when
        val initialClients = rows(request("GET", CLIENTS, stylistActorId))

        // then
        assertThat(initialClients).isEmpty()
        repeat(2) {

            // when
            val granted =
                request("PUT", "$path?ownerId=${other.id}", actorId, """{"ownerId":${other.id}}""")

            // then
            assertThat(granted.statusCode()).isEqualTo(204)
            assertThat(granted.body()).isEmpty()
        }
        assertThat(grants(owner, stylist)).isEqualTo(1)
        assertThat(grants(other, stylist)).isZero()

        // when
        val grantedStylists = rows(request("GET", ACCESS, actorId))

        // then
        assertThat(grantedStylists).containsExactly(identity(stylist))

        // when
        val otherOwnerGrants = rows(request("GET", "$ACCESS?ownerId=${owner.id}", otherActorId))

        // then
        assertThat(otherOwnerGrants).isEmpty()

        // when
        val grantedClients = rows(request("GET", CLIENTS, stylistActorId))

        // then
        assertThat(grantedClients).containsExactly(identity(owner))

        // when
        val foreignRevokeStatus =
            request("DELETE", "$path?ownerId=${owner.id}", otherActorId).statusCode()

        // then
        assertThat(foreignRevokeStatus).isEqualTo(204)
        assertThat(grants(owner, stylist)).isEqualTo(1)
        repeat(2) {

            // when
            val revoked = request("DELETE", path, actorId)

            // then
            assertThat(revoked.statusCode()).isEqualTo(204)
            assertThat(revoked.body()).isEmpty()
        }
        assertThat(grants(owner, stylist)).isZero()

        // when
        val missingRevokeStatus =
            request("DELETE", "$ACCESS/${Long.MAX_VALUE}", actorId).statusCode()

        // then
        assertThat(missingRevokeStatus).isEqualTo(204)

        // when
        val remainingGrants = rows(request("GET", ACCESS, actorId))

        // then
        assertThat(remainingGrants).isEmpty()

        // when
        val remainingClients = rows(request("GET", CLIENTS, stylistActorId))

        // then
        assertThat(remainingClients).isEmpty()
    }

    @Test
    fun `concurrent PUTs succeed and leave exactly one pair`() {

        // given
        val owner = user()
        val stylist = user(UserRole.STYLIST)
        val actorId = actorId(owner)
        val ready = CyclicBarrier(4)
        Executors.newFixedThreadPool(4).use { executor ->

            // when
            val attempts =
                List(4) {
                    executor.submit(
                        Callable {
                            ready.await(10, TimeUnit.SECONDS)
                            request("PUT", "$ACCESS/${stylist.id}", actorId).statusCode()
                        },
                    )
                }

            // then
            assertThat(attempts.map { it.get(20, TimeUnit.SECONDS) }).containsOnly(204)
        }
        assertThat(grants(owner, stylist)).isEqualTo(1)
    }

    @Test
    fun `PUT requires an active stylist even on repeat while DELETE accepts suspended targets`() {

        // given
        val owner = user()
        val actorId = actorId(owner)
        val stylist = user(UserRole.STYLIST)
        val invalid =
            listOf(owner, user(), user(UserRole.ADMIN), user(UserRole.STYLIST, UserStatus.BLOCKED))
        for (id in invalid.map { it.id } + Long.MAX_VALUE) {

            // when
            val invalidTarget = request("PUT", "$ACCESS/$id", actorId)

            // then
            assertNotFound(invalidTarget)
        }
        for ((role, status) in
            listOf(UserRole.STYLIST to UserStatus.BLOCKED, UserRole.USER to UserStatus.ACTIVE)) {

            // given: the next role or status change
            change(stylist, UserRole.STYLIST, UserStatus.ACTIVE)

            // when
            val grantStatus = request("PUT", "$ACCESS/${stylist.id}", actorId).statusCode()

            // then
            assertThat(grantStatus).isEqualTo(204)

            // given: the next role or status change
            change(stylist, role, status)

            // when
            val suspendedTarget = request("PUT", "$ACCESS/${stylist.id}", actorId)

            // then
            assertNotFound(suspendedTarget)

            // when
            val preservedGrants = rows(request("GET", ACCESS, actorId))

            // then
            assertThat(preservedGrants).containsExactly(identity(stylist))
            assertThat(grants(owner, stylist)).isEqualTo(1)

            // when
            val revokeStatus = request("DELETE", "$ACCESS/${stylist.id}", actorId).statusCode()

            // then
            assertThat(revokeStatus).isEqualTo(204)
            assertThat(grants(owner, stylist)).isZero()
        }
    }

    @Test
    fun `permission and client lists have exact DTOs ordered bounded pages and no totals`() {

        // given
        val owner = user()
        val stylist = user(UserRole.STYLIST)
        val stylists = List(55) { user(UserRole.STYLIST) }
        val owners = List(55) { user() }
        stylists.reversed().forEach { seedGrant(owner, it) }
        owners.reversed().forEach { seedGrant(it, stylist) }
        val ownerActorId = actorId(owner)
        val stylistActorId = actorId(stylist)
        for ((path, actorId, expected) in
            listOf(
                Triple(ACCESS, ownerActorId, stylists.map(::identity)),
                Triple(CLIENTS, stylistActorId, owners.map(::identity)),
            )) {

            // when
            val response = request("GET", path, actorId)
            val first = rows(response)
            val second = rows(request("GET", "$path?page=1&size=50", actorId))

            // then

            // then
            assertThat(first).hasSize(50)
            assertThat(second).hasSize(5)
            assertThat(first + second).isEqualTo(expected)
            first.forEach {
                assertThat(it.keys).containsExactlyInAnyOrder("id", "login")
            }
            assertThat(response.headers().allValues("X-Total-Count")).isEmpty()

            // when
            val singleEntryPage = rows(request("GET", "$path?page=1&size=1", actorId))

            // then
            assertThat(singleEntryPage).containsExactly(expected[1])

            // when
            val emptyPage = rows(request("GET", "$path?page=2&size=50", actorId))

            // then
            assertThat(emptyPage).isEmpty()

            // when
            val maximumOffsetPage =
                rows(request("GET", "$path?page=${Int.MAX_VALUE}&size=1", actorId))

            // then
            assertThat(maximumOffsetPage).isEmpty()
            for (query in INVALID_PAGES) {

                // when
                val invalidPageStatus = request("GET", "$path?$query", actorId).statusCode()

                // then
                assertThat(invalidPageStatus).describedAs(query).isEqualTo(400)
            }
        }

        // given: the next role or status change
        change(stylists[0], UserRole.STYLIST, UserStatus.BLOCKED)

        // given: the next role or status change
        change(stylists[1], UserRole.ADMIN, UserStatus.ACTIVE)

        // given: the next role or status change
        change(owners[0], UserRole.USER, UserStatus.BLOCKED)

        // given: the next role or status change
        change(owners[1], UserRole.STYLIST, UserStatus.ACTIVE)

        // when
        val retainedStylists = rows(request("GET", ACCESS, ownerActorId))

        // then
        assertThat(retainedStylists).isEqualTo(stylists.take(50).map(::identity))

        // when
        val activeClients = rows(request("GET", CLIENTS, stylistActorId))

        // then
        assertThat(activeClients).isEqualTo(owners.drop(2).take(50).map(::identity))

        // when
        val newStylistClients = rows(request("GET", CLIENTS, actorId(user(UserRole.STYLIST))))

        // then
        assertThat(newStylistClients).isEmpty()
    }

    private fun user(
        role: UserRole = UserRole.USER,
        status: UserStatus = UserStatus.ACTIVE,
    ): AppUser = jdbc.insertUser(UUID.randomUUID().toString(), passwordHash, role, status)

    private fun change(user: AppUser, role: UserRole, status: UserStatus) {
        val current = requireNotNull(users.findById(user.id!!))
        users.changeRoleAndStatus(user.id!!, current.version, role, status)
    }

    private fun actorId(user: AppUser): String = requireNotNull(user.id).toString()

    private fun seedGrant(owner: AppUser, stylist: AppUser) {
        jdbc.update(
            "INSERT INTO access_grant (owner_id, stylist_id) VALUES (?, ?)",
            owner.id,
            stylist.id,
        )
    }

    private fun grants(owner: AppUser, stylist: AppUser): Long =
        jdbc.queryForObject(
            "SELECT count(*) FROM access_grant WHERE owner_id = ? AND stylist_id = ?",
            Long::class.java,
            owner.id,
            stylist.id,
        )!!

    private fun identity(user: AppUser): Map<String, Any> =
        mapOf("id" to user.id!!.toInt(), "login" to user.login)

    private fun assertNotFound(response: HttpResponse<ByteArray>) {

        // then
        assertThat(response.statusCode()).isEqualTo(404)
        assertThat(response.body()).isEmpty()
    }

    private fun rows(response: HttpResponse<ByteArray>): List<Map<String, Any>> {

        // then
        assertThat(response.statusCode()).isEqualTo(200)
        return JsonPath.read(String(response.body()), "$")
    }

    private fun request(
        method: String,
        path: String,
        actorId: String? = null,
        body: String? = null,
        contentType: String = "application/json",
    ): HttpResponse<ByteArray> {
        val request =
            HttpRequest.newBuilder(URI("http://localhost:$port$path"))
                .method(
                    method,
                    body?.let(HttpRequest.BodyPublishers::ofString)
                        ?: HttpRequest.BodyPublishers.noBody(),
                )
        if (body != null) request.header("Content-Type", contentType)
        if (actorId != null) request.header("X-User-Id", "$actorId")
        return client.send(request.build(), HttpResponse.BodyHandlers.ofByteArray())
    }

    companion object {
        private const val ACCESS = "/api/me/stylist-access"
        private const val CLIENTS = "/api/stylist/clients"
        private val INVALID_PAGES =
            listOf(
                "page=-1",
                "size=0",
                "size=-1",
                "size=51",
                "page=abc",
                "size=abc",
                "page=1.5",
                "size=1.5",
                "page=2147483648",
                "size=2147483648",
                "page=2147483647&size=2",
            )
    }
}
