package ru.itmo.clothesadvisor.controller.outfit

import com.jayway.jsonpath.JsonPath
import java.math.BigDecimal
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.sql.Timestamp
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.Mockito.clearInvocations
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mockingDetails
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import ru.itmo.clothesadvisor.config.TestTimeConfiguration
import ru.itmo.clothesadvisor.dto.outfit.CreateOutfitRequest
import ru.itmo.clothesadvisor.dto.outfit.OutfitWeatherDto
import ru.itmo.clothesadvisor.dto.rating.CreateRatingRequest
import ru.itmo.clothesadvisor.dto.rating.UpdateRatingRequest
import ru.itmo.clothesadvisor.dto.wardrobe.CreateWardrobeItemRequest
import ru.itmo.clothesadvisor.model.rating.RatingVote
import ru.itmo.clothesadvisor.model.user.AppUser
import ru.itmo.clothesadvisor.model.user.UserRole
import ru.itmo.clothesadvisor.model.user.UserStatus
import ru.itmo.clothesadvisor.repository.outfit.OutfitRepository
import ru.itmo.clothesadvisor.repository.rating.OutfitRatingRepository
import ru.itmo.clothesadvisor.service.access.AccessGrantService
import ru.itmo.clothesadvisor.service.outfit.OutfitService
import ru.itmo.clothesadvisor.service.rating.OutfitRatingService
import ru.itmo.clothesadvisor.service.user.AppUserService
import ru.itmo.clothesadvisor.service.wardrobe.WardrobeItemService
import ru.itmo.clothesadvisor.storage.wardrobe.S3PhotoStorage

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestTimeConfiguration::class)
class OutfitRatingIntegrationTests {
    @LocalServerPort private var port: Int = 0
    @Autowired private lateinit var users: AppUserService
    @Autowired private lateinit var passwords: PasswordEncoder
    @Autowired private lateinit var items: WardrobeItemService
    @Autowired private lateinit var outfits: OutfitService
    @Autowired private lateinit var ratings: OutfitRatingService
    @Autowired private lateinit var access: AccessGrantService
    @Autowired private lateinit var jdbc: JdbcTemplate
    @Autowired private lateinit var transactionManager: PlatformTransactionManager
    @MockitoSpyBean private lateinit var outfitRepository: OutfitRepository
    @MockitoSpyBean private lateinit var ratingRepository: OutfitRatingRepository
    @MockitoBean private lateinit var storage: S3PhotoStorage
    private val client = HttpClient.newHttpClient()

    @AfterEach
    fun closeClientAndCheckStorage() {
        client.close()
        verifyNoInteractions(storage)
    }

    @Test
    fun `create change and identical vote at fixed time advance once and preserve old snapshots`() {
        val f = fixture()
        val token = login(f.stylist)
        val path = ratingPath(f)
        val forged = """{"vote":"LIKE","stylistId":${f.other.id},"version":98,"source":"AI"}"""
        val created = request("POST", path, token, forged)
        assertRating(created, 201, "LIKE", 1)
        assertEmpty(request("POST", path, token, vote()), 409)
        assertRating(request("PUT", path, token, vote("DISLIKE", 1)), 200, "DISLIKE", 2)
        assertRating(request("PUT", path, token, vote("DISLIKE", 2)), 200, "DISLIKE", 3)
        assertEmpty(request("PUT", path, token, vote("LIKE", 2)), 409)
        assertEmpty(request("PUT", path, login(f.other), vote("LIKE", 1)), 404)
        assertThat(current(f)).containsExactly(snapshot(f.stylist.id!!, "DISLIKE", 3))
        assertThat(archived(f)).containsExactly(snapshot(f.stylist.id!!, "LIKE", 1), snapshot(f.stylist.id!!, "DISLIKE", 2))
        assertThat(jdbc.queryForObject("SELECT created_at = modified_at FROM outfit_rating WHERE outfit_id = ?",
            Boolean::class.java, f.id)).isTrue()
        assertThat(jdbc.queryForObject("SELECT bool_and(archived_at = modified_at) FROM outfit_rating_history WHERE outfit_id = ?",
            Boolean::class.java, f.id)).isTrue()
    }

    @Test
    fun `withdraw archives exact current vote and recasts keep increasing own version`() {
        val f = fixture()
        val token = login(f.stylist)
        val otherToken = login(f.other)
        val path = ratingPath(f)
        assertEmpty(request("DELETE", "$path?version=1", token), 404)
        assertRating(request("POST", path, token, vote()), 201, "LIKE", 1)
        assertRating(request("PUT", path, token, vote("DISLIKE", 1)), 200, "DISLIKE", 2)
        assertRating(request("POST", path, otherToken, vote()), 201, "LIKE", 1)
        val oldTime = TestTimeConfiguration.FIXED_TIME.minusSeconds(60)
        jdbc.update("UPDATE outfit_rating SET created_at = ?, modified_at = ? WHERE outfit_id = ? AND stylist_id = ?",
            Timestamp.from(oldTime), Timestamp.from(oldTime), f.id, f.stylist.id!!)
        val last = current(f).first { it.stylistId == f.stylist.id }
        assertEmpty(request("DELETE", "$path?version=1", token), 409)
        assertEmpty(request("DELETE", "$path?version=2&stylistId=${f.other.id}", token), 204)
        assertEmpty(request("DELETE", "$path?version=2", token), 404)
        assertThat(current(f)).containsExactly(snapshot(f.other.id!!, "LIKE", 1))
        assertThat(archived(f)).containsExactly(snapshot(f.stylist.id!!, "LIKE", 1), last)
        for (card in listOf(dto(request("GET", clientPath(f) + "/${f.id}", token)),
            rows(request("GET", clientPath(f), token)).single())) {
            assertThat(card).containsEntry("myRating", null)
            assertThat(number(card, "likes")).isEqualTo(1)
            assertThat(number(card, "dislikes")).isZero()
        }
        assertThat(dto(request("GET", clientPath(f) + "/${f.id}", otherToken))["myRating"])
            .isEqualTo(mapOf("vote" to "LIKE", "version" to 1))
        for ((historyPath, credential) in listOf(historyPath(f) to token,
            "/api/outfits/${f.id}/ratings/history" to login(f.owner))) {
            val response = request("GET", historyPath, credential)
            assertThat(response.headers().firstValue("X-Total-Count")).hasValue("3")
            val withdrawn = rows(response).single { number(it, "stylistId") == f.stylist.id && number(it, "version") == 2L }
            assertThat(withdrawn).containsEntry("vote", "DISLIKE")
                .containsEntry("modifiedAt", oldTime.toString()).containsEntry("archivedAt", TestTimeConfiguration.FIXED_TIME.toString())
        }
        assertRating(request("POST", path, token, vote()), 201, "LIKE", 3)
        assertThat(jdbc.queryForObject("SELECT created_at = ? AND modified_at = ? FROM outfit_rating WHERE outfit_id = ? AND stylist_id = ?",
            Boolean::class.java, Timestamp.from(TestTimeConfiguration.FIXED_TIME), Timestamp.from(TestTimeConfiguration.FIXED_TIME),
            f.id, f.stylist.id!!)).isTrue()
        assertEmpty(request("POST", path, token, vote()), 409)
        assertEmpty(request("PUT", path, token, vote("DISLIKE", 2)), 409)
        assertEmpty(request("DELETE", "$path?version=2", token), 409)
        assertRating(request("PUT", path, token, vote("DISLIKE", 3)), 200, "DISLIKE", 4)
        assertEmpty(request("DELETE", "$path?version=4", token), 204)
        assertRating(request("POST", path, token, vote()), 201, "LIKE", 5)
        assertThat(archived(f)).containsExactly(snapshot(f.stylist.id!!, "LIKE", 1), last,
            snapshot(f.stylist.id!!, "LIKE", 3), snapshot(f.stylist.id!!, "DISLIKE", 4))
        assertThat(current(f)).containsExactly(snapshot(f.stylist.id!!, "LIKE", 5), snapshot(f.other.id!!, "LIKE", 1))
    }

    @Test
    fun `cards and lists count all current votes and expose only requesting stylist own rating in batches`() {
        val f = fixture()
        val ownerToken = login(f.owner)
        val stylistToken = login(f.stylist)
        val otherToken = login(f.other)
        val ids = listOf(f.id, outfit(f.owner), outfit(f.owner)).sortedDescending()
        for (id in ids) {
            ratings.create(f.stylist.id!!, f.owner.id!!, id, CreateRatingRequest(RatingVote.LIKE))
            ratings.create(f.other.id!!, f.owner.id!!, id, CreateRatingRequest(RatingVote.DISLIKE))
        }
        ratings.update(f.stylist.id!!, f.owner.id!!, f.id, UpdateRatingRequest(RatingVote.DISLIKE, 1))
        val ownerCard = dto(request("GET", "/api/outfits/${f.id}", ownerToken))
        assertThat(ownerCard).doesNotContainKey("myRating")
        assertThat(number(ownerCard, "likes")).isZero()
        assertThat(number(ownerCard, "dislikes")).isEqualTo(2)
        assertThat(dto(request("GET", clientPath(f) + "/${f.id}", stylistToken))["myRating"])
            .isEqualTo(mapOf("vote" to "DISLIKE", "version" to 2))
        assertThat(dto(request("GET", clientPath(f) + "/${f.id}", otherToken))["myRating"])
            .isEqualTo(mapOf("vote" to "DISLIKE", "version" to 1))
        clearInvocations(ratingRepository)
        val listed = rows(request("GET", clientPath(f), stylistToken))
        assertThat(listed.map { number(it, "id") }).isEqualTo(ids)
        verify(ratingRepository).counts(ids)
        verify(ratingRepository).findAllByIdOutfitIdInAndIdStylistId(ids, f.stylist.id!!)
        assertThat(listed.map { number(it, "likes") }).containsExactly(1, 1, 0)
        assertThat(listed.map { number(it, "dislikes") }).containsExactly(1, 1, 2)
        assertThat(listed.map { it["myRating"] }).containsExactly(
            mapOf("vote" to "LIKE", "version" to 1),
            mapOf("vote" to "LIKE", "version" to 1),
            mapOf("vote" to "DISLIKE", "version" to 2),
        )
        access.revoke(f.owner.id!!, f.other.id!!)
        users.changeRoleAndStatus(f.other.id!!, f.other.version, UserRole.STYLIST, UserStatus.BLOCKED)
        assertThat(dto(request("GET", "/api/outfits/${f.id}", ownerToken))).isEqualTo(ownerCard)
        assertThat(rows(request("GET", "/api/outfits", ownerToken))).allMatch { !it.containsKey("myRating") }
        val unrated = outfit(f.owner)
        val emptyCard = dto(request("GET", clientPath(f) + "/$unrated", stylistToken))
        assertThat(number(emptyCard, "likes")).isZero()
        assertThat(number(emptyCard, "dislikes")).isZero()
        assertThat(emptyCard).containsEntry("myRating", null)
    }

    @Test
    fun `validation roles self voting and inaccessible resources reject without writes`() {
        val f = fixture()
        val token = login(f.stylist)
        val malformed = listOf("{}", "{")
        val invalidVotes = listOf("""{"vote":null}""", """{"vote":"OTHER"}""",
            """{"vote":0}""", """{"vote":"0"}""", """{"vote":"like"}""")
        for (invalid in malformed + invalidVotes) {
            assertThat(request("POST", ratingPath(f), token, invalid).statusCode()).describedAs(invalid).isEqualTo(400)
        }
        for (invalid in malformed) {
            assertThat(request("PUT", ratingPath(f), token, invalid).statusCode()).describedAs(invalid).isEqualTo(400)
        }
        for (invalid in invalidVotes) {
            assertThat(request("PUT", ratingPath(f), token, invalid.dropLast(1) + ",\"version\":1}").statusCode())
                .describedAs(invalid).isEqualTo(400)
        }
        assertThat(request("PUT", ratingPath(f), token, vote()).statusCode()).isEqualTo(400)
        for (version in listOf("0", "-1", "null", "9223372036854775808")) {
            assertThat(request("PUT", ratingPath(f), token, """{"vote":"LIKE","version":$version}""").statusCode()).isEqualTo(400)
        }
        for (query in listOf("", "?version=", "?version=0", "?version=-1", "?version=null", "?version=abc", "?version=9223372036854775808")) {
            assertThat(request("DELETE", ratingPath(f) + query, token).statusCode()).isEqualTo(400)
        }
        val self = outfits.createForClient(f.stylist.id!!, f.owner.id!!, input(item(f.owner))).outfit.id
        val foreign = user()
        val foreignOutfit = outfit(foreign)
        access.grant(foreign.id!!, f.stylist.id!!)
        for (method in listOf("POST", "PUT", "DELETE")) {
            val body = when (method) { "POST" -> vote(); "PUT" -> vote(version = 1); else -> null }
            val query = if (method == "DELETE") "?version=1" else ""
            assertThat(request(method, clientPath(f) + "/$self/rating$query", token, body).statusCode()).isEqualTo(403)
            for (id in listOf(foreignOutfit, Long.MAX_VALUE)) assertEmpty(request(method, clientPath(f) + "/$id/rating$query", token, body), 404)
            assertEmpty(request(method, "/api/stylist/clients/${Long.MAX_VALUE}/outfits/${f.id}/rating$query", token, body), 404)
            for ((credential, status) in listOf(null to 401, login(f.owner) to 403, login(user(UserRole.ADMIN)) to 403)) {
                assertThat(request(method, ratingPath(f) + query, credential, body).statusCode()).isEqualTo(status)
            }
        }
        access.revoke(f.owner.id!!, f.stylist.id!!)
        deniedRatingAndHistory(f, token, 404)
        access.grant(f.owner.id!!, f.stylist.id!!)
        var owner = users.changeRoleAndStatus(f.owner.id!!, f.owner.version, UserRole.USER, UserStatus.BLOCKED)
        deniedRatingAndHistory(f, token, 404)
        owner = users.changeRoleAndStatus(owner.id!!, owner.version, UserRole.ADMIN, UserStatus.ACTIVE)
        deniedRatingAndHistory(f, token, 404)
        users.changeRoleAndStatus(owner.id!!, owner.version, UserRole.USER, UserStatus.ACTIVE)
        val stylist = users.changeRoleAndStatus(f.stylist.id!!, f.stylist.version, UserRole.STYLIST, UserStatus.BLOCKED)
        deniedRatingAndHistory(f, token, 401)
        users.changeRoleAndStatus(stylist.id!!, stylist.version, UserRole.USER, UserStatus.ACTIVE)
        deniedRatingAndHistory(f, token, 403)
        assertThat(current(f)).isEmpty()
        assertThat(archived(f)).isEmpty()
    }

    @Test
    fun `history union orders by time stylist and version and paginates with exact total and permissions`() {
        val f = fixture()
        for (stylist in listOf(f.stylist, f.other)) {
            ratings.create(stylist.id!!, f.owner.id!!, f.id, CreateRatingRequest(RatingVote.LIKE))
            ratings.update(stylist.id!!, f.owner.id!!, f.id, UpdateRatingRequest(RatingVote.DISLIKE, 1))
        }
        val latest = TestTimeConfiguration.FIXED_TIME.plusSeconds(1)
        jdbc.update("UPDATE outfit_rating_history SET modified_at = ? WHERE outfit_id = ? AND stylist_id = ?",
            Timestamp.from(latest), f.id, f.stylist.id!!)
        val expected = listOf(f.stylist.id!! to 1L, f.other.id!! to 2L, f.other.id!! to 1L, f.stylist.id!! to 2L)
        val ownerPath = "/api/outfits/${f.id}/ratings/history"
        val ownerToken = login(f.owner)
        for ((path, token) in listOf(ownerPath to ownerToken, historyPath(f) to login(f.stylist))) {
            val all = request("GET", path, token)
            assertThat(all.headers().firstValue("X-Total-Count")).hasValue("4")
            val timeline = rows(all)
            assertThat(timeline.map { number(it, "stylistId") to number(it, "version") }).isEqualTo(expected)
            assertThat(timeline).allSatisfy { row ->
                assertThat(row.keys).containsExactlyInAnyOrder("stylistId", "vote", "version", "modifiedAt", "archivedAt")
                assertThat(row["archivedAt"]).isEqualTo(if (number(row, "version") == 2L) null else TestTimeConfiguration.FIXED_TIME.toString())
            }
            assertThat(timeline.first()["modifiedAt"]).isEqualTo(latest.toString())
            val page = request("GET", "$path?page=1&size=2", token)
            assertThat(page.headers().firstValue("X-Total-Count")).hasValue("4")
            assertThat(rows(page)).isEqualTo(timeline.drop(2))
            val empty = request("GET", "$path?page=10&size=2", token)
            assertThat(rows(empty)).isEmpty()
            assertThat(empty.headers().firstValue("X-Total-Count")).hasValue("4")
            for (query in listOf("page=-1", "size=0", "size=51", "page=abc", "page=${Int.MAX_VALUE}&size=2")) {
                assertThat(request("GET", "$path?$query", token).statusCode()).isEqualTo(400)
            }
        }
        assertEmpty(request("GET", ownerPath, login(user())), 404)
        assertThat(request("GET", ownerPath, login(f.stylist)).statusCode()).isEqualTo(403)
        assertThat(request("GET", ownerPath).statusCode()).isEqualTo(401)
        assertThat(request("GET", historyPath(f), login(f.owner)).statusCode()).isEqualTo(403)
        access.revoke(f.owner.id!!, f.stylist.id!!)
        assertEmpty(request("GET", historyPath(f), login(f.stylist)), 404)
        outfits.delete(f.owner.id!!, f.id)
        assertEmpty(request("GET", ownerPath, ownerToken), 404)
        assertEmpty(request("GET", historyPath(f), login(f.other)), 404)
    }

    @Test
    fun `history retains one repeatable read snapshot when delete commits after ownership read`() {
        val f = fixture()
        ratings.create(f.stylist.id!!, f.owner.id!!, f.id, CreateRatingRequest(RatingVote.LIKE))
        ratings.update(f.stylist.id!!, f.owner.id!!, f.id, UpdateRatingRequest(RatingVote.DISLIKE, 1))
        val loaded = CountDownLatch(1)
        val release = CountDownLatch(1)
        doAnswer { call ->
            val row = mockingDetails(outfitRepository).mockCreationSettings.defaultAnswer.answer(call)
            loaded.countDown()
            assertThat(release.await(10, TimeUnit.SECONDS)).isTrue()
            row
        }.`when`(outfitRepository).findByIdAndOwnerId(f.id, f.owner.id!!)
        val token = login(f.owner)
        Executors.newSingleThreadExecutor().use { executor ->
            val read = executor.submit(Callable { request("GET", "/api/outfits/${f.id}/ratings/history?size=1", token) })
            try {
                assertThat(loaded.await(10, TimeUnit.SECONDS)).isTrue()
                outfits.delete(f.owner.id!!, f.id)
            } finally { release.countDown() }
            val response = read.get(30, TimeUnit.SECONDS)
            assertThat(response.headers().firstValue("X-Total-Count")).hasValue("2")
            val current = rows(response).single()
            assertThat(number(current, "version")).isEqualTo(2)
            assertThat(current["archivedAt"]).isNull()
        }
    }

    @Test
    fun `history collision rolls back flushed update withdrawal and final archive delete`() {
        val f = fixture()
        ratings.create(f.stylist.id!!, f.owner.id!!, f.id, CreateRatingRequest(RatingVote.LIKE))
        ratings.create(f.other.id!!, f.owner.id!!, f.id, CreateRatingRequest(RatingVote.DISLIKE))
        jdbc.update("""INSERT INTO outfit_rating_history (outfit_id, stylist_id, version, vote, modified_at, archived_at)
            SELECT outfit_id, stylist_id, version, 'DISLIKE', modified_at, modified_at FROM outfit_rating
            WHERE outfit_id = ? AND stylist_id = ?""", f.id, f.stylist.id!!)
        val before = current(f)
        val previous = archived(f)
        assertEmpty(request("PUT", ratingPath(f), login(f.stylist), vote("DISLIKE", 1)), 409)
        assertThat(current(f)).isEqualTo(before)
        assertThat(archived(f)).isEqualTo(previous)
        assertEmpty(request("DELETE", ratingPath(f) + "?version=1", login(f.stylist)), 409)
        assertThat(current(f)).isEqualTo(before)
        assertThat(archived(f)).isEqualTo(previous)
        assertEmpty(request("DELETE", "/api/outfits/${f.id}", login(f.owner)), 409)
        assertThat(outfits.get(f.owner.id!!, f.id).id).isEqualTo(f.id)
        assertThat(current(f)).isEqualTo(before)
        assertThat(archived(f)).isEqualTo(previous)
    }

    @ParameterizedTest
    @ValueSource(strings = ["update", "withdraw", "delete"])
    fun `outer transaction failure rolls back rating mutation or outfit deletion together with history`(operation: String) {
        val f = fixture()
        ratings.create(f.stylist.id!!, f.owner.id!!, f.id, CreateRatingRequest(RatingVote.LIKE))
        val before = current(f)
        val original = outfits.get(f.owner.id!!, f.id)
        assertThatThrownBy {
            TransactionTemplate(transactionManager).executeWithoutResult {
                when (operation) {
                    "delete" -> outfits.delete(f.owner.id!!, f.id)
                    "withdraw" -> ratings.withdraw(f.stylist.id!!, f.owner.id!!, f.id, 1)
                    else -> ratings.update(f.stylist.id!!, f.owner.id!!, f.id, UpdateRatingRequest(RatingVote.DISLIKE, 1))
                }
                assertThat(current(f)).isEqualTo(if (operation == "update") listOf(snapshot(f.stylist.id!!, "DISLIKE", 2)) else emptyList<Snapshot>())
                assertThat(archived(f)).isEqualTo(before)
                error("forced failure after writes")
            }
        }.isInstanceOf(IllegalStateException::class.java).hasMessage("forced failure after writes")
        assertThat(current(f)).isEqualTo(before)
        assertThat(archived(f)).isEmpty()
        assertThat(outfits.get(f.owner.id!!, f.id)).isEqualTo(original)
    }

    @Test
    fun `delete archives last versions of all stylists including version one and retains prior history`() {
        val f = fixture()
        ratings.create(f.stylist.id!!, f.owner.id!!, f.id, CreateRatingRequest(RatingVote.LIKE))
        ratings.update(f.stylist.id!!, f.owner.id!!, f.id, UpdateRatingRequest(RatingVote.DISLIKE, 1))
        ratings.create(f.other.id!!, f.owner.id!!, f.id, CreateRatingRequest(RatingVote.LIKE))
        access.revoke(f.owner.id!!, f.other.id!!)
        val expected = (archived(f) + current(f)).sortedWith(compareBy({ it.stylistId }, { it.version }))
        val token = login(f.owner)
        assertEmpty(request("DELETE", "/api/outfits/${f.id}", token), 204)
        assertThat(current(f)).isEmpty()
        assertThat(archived(f)).isEqualTo(expected)
        assertEmpty(request("DELETE", "/api/outfits/${f.id}", token), 404)
        assertThat(archived(f)).isEqualTo(expected)
    }

    @ParameterizedTest
    @ValueSource(strings = ["POST", "PUT", "DELETE"])
    fun `rating mutation followed by delete archives every committed version`(method: String) {
        val f = fixture()
        if (method != "POST") ratings.create(f.stylist.id!!, f.owner.id!!, f.id, CreateRatingRequest(RatingVote.LIKE))
        val token = login(f.stylist)
        val ownerToken = login(f.owner)
        val (write, deletion) = race(f,
            { mutateRating(f, method, token) },
            { request("DELETE", "/api/outfits/${f.id}", ownerToken) })
        assertThat(write.statusCode()).isEqualTo(when (method) { "POST" -> 201; "PUT" -> 200; else -> 204 })
        assertEmpty(deletion, 204)
        assertThat(current(f)).isEmpty()
        val expected = listOf(snapshot(f.stylist.id!!, "LIKE", 1)) +
            if (method == "PUT") listOf(snapshot(f.stylist.id!!, "DISLIKE", 2)) else emptyList()
        assertThat(archived(f)).isEqualTo(expected)
    }

    @ParameterizedTest
    @ValueSource(strings = ["POST", "PUT", "DELETE"])
    fun `delete followed by rating mutation rejects waiter without losing final state`(method: String) {
        val f = fixture()
        if (method != "POST") ratings.create(f.stylist.id!!, f.owner.id!!, f.id, CreateRatingRequest(RatingVote.LIKE))
        val token = login(f.stylist)
        val ownerToken = login(f.owner)
        val (deletion, write) = race(f,
            { request("DELETE", "/api/outfits/${f.id}", ownerToken) },
            { mutateRating(f, method, token) })
        assertEmpty(deletion, 204)
        assertEmpty(write, 404)
        assertThat(current(f)).isEmpty()
        assertThat(archived(f)).isEqualTo(if (method != "POST") listOf(snapshot(f.stylist.id!!, "LIKE", 1)) else emptyList<Snapshot>())
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `concurrent duplicate creation or same expected version has one winner`(update: Boolean) {
        val f = fixture()
        if (update) ratings.create(f.stylist.id!!, f.owner.id!!, f.id, CreateRatingRequest(RatingVote.LIKE))
        val token = login(f.stylist)
        val method = if (update) "PUT" else "POST"
        val (first, second) = race(f,
            { request(method, ratingPath(f), token, if (update) vote("DISLIKE", 1) else vote()) },
            { request(method, ratingPath(f), token, if (update) vote("LIKE", 1) else vote("DISLIKE")) })
        assertRating(first, if (update) 200 else 201, if (update) "DISLIKE" else "LIKE", if (update) 2 else 1)
        assertEmpty(second, 409)
        assertThat(current(f)).containsExactly(snapshot(f.stylist.id!!, if (update) "DISLIKE" else "LIKE", if (update) 2 else 1))
        assertThat(archived(f)).isEqualTo(if (update) listOf(snapshot(f.stylist.id!!, "LIKE", 1)) else emptyList<Snapshot>())
    }

    @ParameterizedTest
    @CsvSource("DELETE,PUT,204,404", "PUT,DELETE,200,409", "DELETE,DELETE,204,404", "DELETE,POST,204,201", "POST,DELETE,409,204")
    fun `withdraw serializes with update another withdrawal and recast`(firstMethod: String, secondMethod: String, firstStatus: Int, secondStatus: Int) {
        val f = fixture()
        ratings.create(f.stylist.id!!, f.owner.id!!, f.id, CreateRatingRequest(RatingVote.LIKE))
        val token = login(f.stylist)
        val (first, second) = race(f,
            { mutateRating(f, firstMethod, token) },
            { mutateRating(f, secondMethod, token) })
        assertThat(first.statusCode()).isEqualTo(firstStatus)
        assertThat(second.statusCode()).isEqualTo(secondStatus)
        val expectedVote = when {
            firstMethod == "PUT" -> "DISLIKE"
            secondMethod == "POST" -> "LIKE"
            else -> null
        }
        assertThat(current(f)).isEqualTo(expectedVote?.let { listOf(snapshot(f.stylist.id!!, it, 2)) } ?: emptyList<Snapshot>())
        assertThat(archived(f)).containsExactly(snapshot(f.stylist.id!!, "LIKE", 1))
    }

    @ParameterizedTest
    @ValueSource(strings = ["POST", "PUT", "DELETE"])
    fun `access revoked during lock wait prevents rating mutation`(method: String) {
        val f = fixture()
        if (method != "POST") ratings.create(f.stylist.id!!, f.owner.id!!, f.id, CreateRatingRequest(RatingVote.LIKE))
        val token = login(f.stylist)
        val otherToken = login(f.other)
        val (other, waiting) = race(f,
            { request("POST", ratingPath(f), otherToken, vote("DISLIKE")) },
            { mutateRating(f, method, token) },
            { access.revoke(f.owner.id!!, f.stylist.id!!) })
        assertRating(other, 201, "DISLIKE", 1)
        assertEmpty(waiting, 404)
        assertThat(current(f).filter { it.stylistId == f.stylist.id }).isEqualTo(
            if (method != "POST") listOf(snapshot(f.stylist.id!!, "LIKE", 1)) else emptyList<Snapshot>())
        assertThat(archived(f)).isEmpty()
    }

    private fun race(
        f: Fixture,
        first: () -> HttpResponse<String>,
        second: () -> HttpResponse<String>,
        whileWaiting: () -> Unit = {},
    ): Pair<HttpResponse<String>, HttpResponse<String>> {
        val locked = CountDownLatch(1)
        val enteringSecondLock = CountDownLatch(1)
        val release = CountDownLatch(1)
        val isFirst = AtomicBoolean(true)
        doAnswer { call ->
            val firstCall = isFirst.compareAndSet(true, false)
            if (!firstCall) enteringSecondLock.countDown()
            val row = mockingDetails(outfitRepository).mockCreationSettings.defaultAnswer.answer(call)
            if (firstCall) {
                assertThat(row).isNotNull()
                locked.countDown()
                assertThat(release.await(10, TimeUnit.SECONDS)).isTrue()
            }
            row
        }.`when`(outfitRepository).findLockedByIdAndOwnerId(f.id, f.owner.id!!)
        Executors.newFixedThreadPool(2).use { executor ->
            val firstResult = executor.submit(Callable { first() })
            try {
                assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue()
                val secondResult = executor.submit(Callable { second() })
                assertThat(enteringSecondLock.await(10, TimeUnit.SECONDS)).isTrue()
                assertDatabaseLockWait()
                whileWaiting()
                release.countDown()
                return firstResult.get(30, TimeUnit.SECONDS) to secondResult.get(30, TimeUnit.SECONDS)
            } finally { release.countDown() }
        }
    }

    private fun assertDatabaseLockWait() {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        do {
            if (jdbc.queryForObject("""SELECT EXISTS (SELECT 1 FROM pg_stat_activity
                WHERE datname = current_database() AND cardinality(pg_blocking_pids(pid)) > 0
                AND query LIKE '%outfit%' AND wait_event_type = 'Lock')""", Boolean::class.java) == true) return
            Thread.yield()
        } while (System.nanoTime() < deadline)
        throw AssertionError("The second outfit operation never waited for a PostgreSQL row lock")
    }

    private fun deniedRatingAndHistory(f: Fixture, token: String, status: Int) {
        for (response in listOf(request("POST", ratingPath(f), token, vote()),
            request("PUT", ratingPath(f), token, vote(version = 1)), request("DELETE", ratingPath(f) + "?version=1", token),
            request("GET", historyPath(f), token))) {
            assertThat(response.statusCode()).isEqualTo(status)
            if (status == 404) assertThat(response.body()).isEmpty()
        }
    }

    private fun fixture(): Fixture {
        val owner = user()
        val stylist = user(UserRole.STYLIST)
        val other = user(UserRole.STYLIST)
        access.grant(owner.id!!, stylist.id!!)
        access.grant(owner.id!!, other.id!!)
        return Fixture(owner, stylist, other, outfit(owner))
    }
    private fun user(role: UserRole = UserRole.USER): AppUser = users.create(UUID.randomUUID().toString(), passwords.encode(PASSWORD)!!, role)
    private fun item(owner: AppUser) = items.create(owner.id!!, CreateWardrobeItemRequest("Shirt", 1, "White", "Cotton")).id
    private fun outfit(owner: AppUser) = outfits.create(owner.id!!, input(item(owner))).id
    private fun input(item: Long) = CreateOutfitRequest("Daily", listOf(item), OutfitWeatherDto(BigDecimal.TEN, 1, BigDecimal.ZERO))
    private fun clientPath(f: Fixture) = "/api/stylist/clients/${f.owner.id}/outfits"
    private fun ratingPath(f: Fixture) = clientPath(f) + "/${f.id}/rating"
    private fun historyPath(f: Fixture) = clientPath(f) + "/${f.id}/ratings/history"
    private fun mutateRating(f: Fixture, method: String, token: String): HttpResponse<String> = when (method) {
        "DELETE" -> request(method, ratingPath(f) + "?version=1", token)
        "PUT" -> request(method, ratingPath(f), token, vote("DISLIKE", 1))
        else -> request(method, ratingPath(f), token, vote())
    }
    private fun vote(vote: String = "LIKE", version: Long? = null) =
        """{"vote":"$vote"${version?.let { ",\"version\":$it" } ?: ""}}"""

    private fun login(user: AppUser): String {
        val response = request("POST", "/api/auth/login", body = """{"login":"${user.login}","password":"$PASSWORD"}""")
        assertThat(response.statusCode()).isEqualTo(200)
        return JsonPath.read(response.body(), "$.accessToken")
    }
    private fun request(method: String, path: String, token: String? = null, body: String? = null): HttpResponse<String> {
        val request = HttpRequest.newBuilder(URI("http://localhost:$port$path"))
            .method(method, body?.let(HttpRequest.BodyPublishers::ofString) ?: HttpRequest.BodyPublishers.noBody())
        if (body != null) request.header("Content-Type", "application/json")
        if (token != null) request.header("Authorization", "Bearer $token")
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString())
    }
    private fun dto(response: HttpResponse<String>): Map<String, Any?> = JsonPath.read(response.body(), "$")
    private fun rows(response: HttpResponse<String>): List<Map<String, Any?>> {
        assertThat(response.statusCode()).isEqualTo(200)
        return JsonPath.read(response.body(), "$")
    }
    private fun number(row: Map<String, Any?>, key: String) = (row.getValue(key) as Number).toLong()
    private fun assertEmpty(response: HttpResponse<String>, status: Int) {
        assertThat(response.statusCode()).isEqualTo(status)
        assertThat(response.body()).isEmpty()
    }
    private fun assertRating(response: HttpResponse<String>, status: Int, vote: String, version: Long) {
        assertThat(response.statusCode()).isEqualTo(status)
        assertThat(dto(response).keys).containsExactlyInAnyOrder("vote", "version")
        assertThat(dto(response)["vote"]).isEqualTo(vote)
        assertThat(number(dto(response), "version")).isEqualTo(version)
    }
    private fun current(f: Fixture) = snapshots("outfit_rating", f)
    private fun archived(f: Fixture) = snapshots("outfit_rating_history", f)
    private fun snapshots(table: String, f: Fixture): List<Snapshot> = jdbc.query(
        "SELECT stylist_id, vote, version, modified_at FROM $table WHERE outfit_id = ? ORDER BY stylist_id, version",
        { row, _ -> Snapshot(row.getLong("stylist_id"), row.getString("vote"), row.getLong("version"), row.getTimestamp("modified_at").toInstant()) }, f.id)
    private fun snapshot(stylistId: Long, vote: String, version: Long) = Snapshot(stylistId, vote, version, TestTimeConfiguration.FIXED_TIME)
    private data class Fixture(val owner: AppUser, val stylist: AppUser, val other: AppUser, val id: Long)
    private data class Snapshot(val stylistId: Long, val vote: String, val version: Long, val modifiedAt: java.time.Instant)

    companion object {
        private const val PASSWORD = "rating-password"
        @Container @JvmStatic val postgres = PostgreSQLContainer("postgres:18-alpine")
        @JvmStatic @DynamicPropertySource
        fun postgresProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
        }
    }
}
