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
import org.assertj.core.api.Assertions.catchThrowable
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
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import ru.itmo.clothesadvisor.config.PostgresIntegrationTest
import ru.itmo.clothesadvisor.config.TestTimeConfiguration
import ru.itmo.clothesadvisor.config.insertAccessGrant
import ru.itmo.clothesadvisor.config.insertItem
import ru.itmo.clothesadvisor.config.insertOutfit
import ru.itmo.clothesadvisor.config.insertUser
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

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestTimeConfiguration::class)
class OutfitRatingIntegrationTests : PostgresIntegrationTest() {
    @LocalServerPort private var port: Int = 0
    @Autowired private lateinit var users: AppUserService
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
    fun `create change and identical vote advance once and preserve old snapshots`() {
        // given
        val fixtureState = fixture()
        val actorId = actorId(fixtureState.stylist)
        val path = ratingPath(fixtureState)
        val forged =
            """{"vote":"LIKE","stylistId":${fixtureState.other.id},"version":98,"source":"AI"}"""

        // when
        val created = request("POST", path, actorId, forged)

        // then
        assertRating(created, 201, "LIKE", 1)
        val first = current(fixtureState).single()
        val createdAt =
            jdbc.queryForObject(
                "SELECT created_at FROM outfit_rating WHERE outfit_id = ?",
                Timestamp::class.java,
                fixtureState.id,
            )!!

        // when
        val duplicateCreation = request("POST", path, actorId, vote())

        // then
        assertEmpty(duplicateCreation, 409)

        // when
        val changedVote = request("PUT", path, actorId, vote("DISLIKE", 1))

        // then
        assertRating(changedVote, 200, "DISLIKE", 2)
        val second = current(fixtureState).single()

        // when
        val identicalVote = request("PUT", path, actorId, vote("DISLIKE", 2))

        // then
        assertRating(identicalVote, 200, "DISLIKE", 3)

        // when
        val staleUpdate = request("PUT", path, actorId, vote("LIKE", 2))

        // then
        assertEmpty(staleUpdate, 409)

        // when
        val foreignUpdate = request("PUT", path, actorId(fixtureState.other), vote("LIKE", 1))

        // then
        assertEmpty(foreignUpdate, 404)
        assertThat(current(fixtureState))
            .containsExactly(snapshot(fixtureState.stylist.id!!, "DISLIKE", 3))
        assertThat(archived(fixtureState)).containsExactly(first, second)
        assertThat(
                jdbc.queryForObject(
                    "SELECT created_at FROM outfit_rating WHERE outfit_id = ?",
                    Timestamp::class.java,
                    fixtureState.id,
                ),
            )
            .isEqualTo(createdAt)
    }

    @Test
    fun `withdraw archives exact current vote and recasts keep increasing own version`() {
        // given
        val fixtureState = fixture()
        val actorId = actorId(fixtureState.stylist)
        val otherActorId = actorId(fixtureState.other)
        val path = ratingPath(fixtureState)

        // when
        val absentWithdrawal = request("DELETE", "$path?version=1", actorId)

        // then
        assertEmpty(absentWithdrawal, 404)

        // when
        val createdVote = request("POST", path, actorId, vote())

        // then
        assertRating(createdVote, 201, "LIKE", 1)

        // when
        val changedVote = request("PUT", path, actorId, vote("DISLIKE", 1))

        // then
        assertRating(changedVote, 200, "DISLIKE", 2)

        // when
        val otherCreatedVote = request("POST", path, otherActorId, vote())

        // then
        assertRating(otherCreatedVote, 201, "LIKE", 1)

        // given
        val oldTime = TestTimeConfiguration.FIXED_TIME.minusSeconds(60)
        jdbc.update(
            "UPDATE outfit_rating SET created_at = ?, modified_at = ? WHERE outfit_id = ? AND stylist_id = ?",
            Timestamp.from(oldTime),
            Timestamp.from(oldTime),
            fixtureState.id,
            fixtureState.stylist.id!!,
        )
        val last = current(fixtureState).first { it.stylistId == fixtureState.stylist.id }

        // when
        val staleWithdrawal = request("DELETE", "$path?version=1", actorId)

        // then
        assertEmpty(staleWithdrawal, 409)

        // when
        val withdrawal =
            request("DELETE", "$path?version=2&stylistId=${fixtureState.other.id}", actorId)

        // then
        assertEmpty(withdrawal, 204)

        // when
        val repeatedWithdrawal = request("DELETE", "$path?version=2", actorId)

        // then
        assertEmpty(repeatedWithdrawal, 404)
        assertThat(current(fixtureState))
            .containsExactly(snapshot(fixtureState.other.id!!, "LIKE", 1))
        assertThat(archived(fixtureState))
            .containsExactly(snapshot(fixtureState.stylist.id!!, "LIKE", 1), last)

        // when
        val withdrawnCard =
            dto(request("GET", clientPath(fixtureState) + "/${fixtureState.id}", actorId))
        val withdrawnListResponse = request("GET", clientPath(fixtureState), actorId)

        // then
        for (card in listOf(withdrawnCard, rows(withdrawnListResponse).single())) {
            assertThat(card).containsEntry("myRating", null)
            assertThat(number(card, "likes")).isEqualTo(1)
            assertThat(number(card, "dislikes")).isZero()
        }

        // when
        val otherStylistCard =
            request("GET", clientPath(fixtureState) + "/${fixtureState.id}", otherActorId)

        // then
        assertThat(dto(otherStylistCard)["myRating"])
            .isEqualTo(mapOf("vote" to "LIKE", "version" to 1))
        for ((historyPath, credential) in
            listOf(
                historyPath(fixtureState) to actorId,
                "/api/outfits/${fixtureState.id}/ratings/history" to actorId(fixtureState.owner),
            )) {

            // when
            val response = request("GET", historyPath, credential)

            // then
            assertThat(response.headers().firstValue("X-Total-Count")).hasValue("3")
            val withdrawn =
                rows(response).single {
                    number(it, "stylistId") == fixtureState.stylist.id &&
                        number(it, "version") == 2L
                }
            assertThat(withdrawn)
                .containsEntry("vote", "DISLIKE")
                .containsEntry("modifiedAt", oldTime.toString())
                .containsEntry(
                    "archivedAt",
                    jdbc
                        .queryForObject(
                            "SELECT archived_at FROM outfit_rating_history WHERE outfit_id = ? AND stylist_id = ? AND version = 2",
                            Timestamp::class.java,
                            fixtureState.id,
                            fixtureState.stylist.id,
                        )!!
                        .toInstant()
                        .toString(),
                )
        }

        // when
        val recastVote = request("POST", path, actorId, vote())

        // then
        assertRating(recastVote, 201, "LIKE", 3)
        assertThat(
                jdbc.queryForObject(
                    "SELECT created_at = modified_at FROM outfit_rating WHERE outfit_id = ? AND stylist_id = ?",
                    Boolean::class.java,
                    fixtureState.id,
                    fixtureState.stylist.id!!,
                ),
            )
            .isTrue()

        // when
        val duplicateRecast = request("POST", path, actorId, vote())

        // then
        assertEmpty(duplicateRecast, 409)

        // when
        val staleUpdate = request("PUT", path, actorId, vote("DISLIKE", 2))

        // then
        assertEmpty(staleUpdate, 409)

        // when
        val staleRecastWithdrawal = request("DELETE", "$path?version=2", actorId)

        // then
        assertEmpty(staleRecastWithdrawal, 409)

        // when
        val updatedRecast = request("PUT", path, actorId, vote("DISLIKE", 3))

        // then
        assertRating(updatedRecast, 200, "DISLIKE", 4)

        // when
        val recastWithdrawal = request("DELETE", "$path?version=4", actorId)

        // then
        assertEmpty(recastWithdrawal, 204)

        // when
        val secondRecast = request("POST", path, actorId, vote())

        // then
        assertRating(secondRecast, 201, "LIKE", 5)
        assertThat(archived(fixtureState))
            .containsExactly(
                snapshot(fixtureState.stylist.id!!, "LIKE", 1),
                last,
                snapshot(fixtureState.stylist.id!!, "LIKE", 3),
                snapshot(fixtureState.stylist.id!!, "DISLIKE", 4),
            )
        assertThat(current(fixtureState))
            .containsExactly(
                snapshot(fixtureState.stylist.id!!, "LIKE", 5),
                snapshot(fixtureState.other.id!!, "LIKE", 1),
            )
    }

    @Test
    fun `cards and lists count all current votes and expose only requesting stylist own rating in batches`() {
        // given
        val fixtureState = fixture()
        val ownerActorId = actorId(fixtureState.owner)
        val stylistActorId = actorId(fixtureState.stylist)
        val otherActorId = actorId(fixtureState.other)
        val ids =
            listOf(fixtureState.id, outfit(fixtureState.owner), outfit(fixtureState.owner))
                .sortedDescending()
        for (id in ids) {
            ratings.create(
                fixtureState.stylist.id!!,
                fixtureState.owner.id!!,
                id,
                CreateRatingRequest(RatingVote.LIKE),
            )
            ratings.create(
                fixtureState.other.id!!,
                fixtureState.owner.id!!,
                id,
                CreateRatingRequest(RatingVote.DISLIKE),
            )
        }
        ratings.update(
            fixtureState.stylist.id!!,
            fixtureState.owner.id!!,
            fixtureState.id,
            UpdateRatingRequest(RatingVote.DISLIKE, 1),
        )

        // when
        val ownerCard = dto(request("GET", "/api/outfits/${fixtureState.id}", ownerActorId))

        // then
        assertThat(ownerCard).doesNotContainKey("myRating")
        assertThat(number(ownerCard, "likes")).isZero()
        assertThat(number(ownerCard, "dislikes")).isEqualTo(2)

        // when
        val stylistCard =
            request("GET", clientPath(fixtureState) + "/${fixtureState.id}", stylistActorId)

        // then
        assertThat(dto(stylistCard)["myRating"])
            .isEqualTo(mapOf("vote" to "DISLIKE", "version" to 2))

        // when
        val otherStylistCard =
            request("GET", clientPath(fixtureState) + "/${fixtureState.id}", otherActorId)

        // then
        assertThat(dto(otherStylistCard)["myRating"])
            .isEqualTo(mapOf("vote" to "DISLIKE", "version" to 1))

        // given
        clearInvocations(ratingRepository)

        // when
        val listResponse = request("GET", clientPath(fixtureState), stylistActorId)

        // then
        val listed = rows(listResponse)
        assertThat(listed.map { number(it, "id") }).isEqualTo(ids)
        verify(ratingRepository).counts(ids)
        verify(ratingRepository).findAllByIdOutfitIdInAndIdStylistId(ids, fixtureState.stylist.id!!)
        assertThat(listed.map { number(it, "likes") }).containsExactly(1, 1, 0)
        assertThat(listed.map { number(it, "dislikes") }).containsExactly(1, 1, 2)
        assertThat(listed.map { it["myRating"] })
            .containsExactly(
                mapOf("vote" to "LIKE", "version" to 1),
                mapOf("vote" to "LIKE", "version" to 1),
                mapOf("vote" to "DISLIKE", "version" to 2),
            )

        // given
        access.revoke(fixtureState.owner.id!!, fixtureState.other.id!!)
        users.changeRoleAndStatus(
            fixtureState.other.id!!,
            fixtureState.other.version,
            UserRole.STYLIST,
            UserStatus.BLOCKED,
        )

        // when
        val ownerCardAfterRevocation =
            request("GET", "/api/outfits/${fixtureState.id}", ownerActorId)

        // then
        assertThat(dto(ownerCardAfterRevocation)).isEqualTo(ownerCard)

        // when
        val ownerList = request("GET", "/api/outfits", ownerActorId)

        // then
        assertThat(rows(ownerList)).allMatch { !it.containsKey("myRating") }

        // given
        val unrated = outfit(fixtureState.owner)

        // when
        val emptyCard = dto(request("GET", clientPath(fixtureState) + "/$unrated", stylistActorId))

        // then
        assertThat(number(emptyCard, "likes")).isZero()
        assertThat(number(emptyCard, "dislikes")).isZero()
        assertThat(emptyCard).containsEntry("myRating", null)
    }

    @Test
    fun `validation roles self voting and inaccessible resources reject without writes`() {
        // given
        val fixtureState = fixture()
        val actorId = actorId(fixtureState.stylist)
        val malformed = listOf("{}", "{")
        val invalidVotes =
            listOf(
                """{"vote":null}""",
                """{"vote":"OTHER"}""",
                """{"vote":0}""",
                """{"vote":"0"}""",
                """{"vote":"like"}""",
            )
        for (invalid in malformed + invalidVotes) {

            // when
            val response = request("POST", ratingPath(fixtureState), actorId, invalid)

            // then
            assertThat(response.statusCode()).describedAs(invalid).isEqualTo(400)
        }
        for (invalid in malformed) {

            // when
            val response = request("PUT", ratingPath(fixtureState), actorId, invalid)

            // then
            assertThat(response.statusCode()).describedAs(invalid).isEqualTo(400)
        }
        for (invalid in invalidVotes) {

            // when
            val response =
                request(
                    "PUT",
                    ratingPath(fixtureState),
                    actorId,
                    invalid.dropLast(1) + ",\"version\":1}",
                )

            // then
            assertThat(response.statusCode()).describedAs(invalid).isEqualTo(400)
        }

        // when
        val missingVersion = request("PUT", ratingPath(fixtureState), actorId, vote())

        // then
        assertThat(missingVersion.statusCode()).isEqualTo(400)
        for (version in listOf("0", "-1", "null", "9223372036854775808")) {

            // when
            val response =
                request(
                    "PUT",
                    ratingPath(fixtureState),
                    actorId,
                    """{"vote":"LIKE","version":$version}""",
                )

            // then
            assertThat(response.statusCode()).isEqualTo(400)
        }
        for (query in
            listOf(
                "",
                "?version=",
                "?version=0",
                "?version=-1",
                "?version=null",
                "?version=abc",
                "?version=9223372036854775808",
            )) {

            // when
            val response = request("DELETE", ratingPath(fixtureState) + query, actorId)

            // then
            assertThat(response.statusCode()).isEqualTo(400)
        }

        // given
        val self =
            outfits
                .createForClient(
                    fixtureState.stylist.id!!,
                    fixtureState.owner.id!!,
                    input(item(fixtureState.owner)),
                )
                .outfit
                .id
        val foreign = user()
        val foreignOutfit = outfit(foreign)
        jdbc.insertAccessGrant(foreign.id!!, fixtureState.stylist.id!!)
        for (method in listOf("POST", "PUT", "DELETE")) {

            // given
            val body =
                when (method) {
                    "POST" -> vote()
                    "PUT" -> vote(version = 1)
                    else -> null
                }
            val query = if (method == "DELETE") "?version=1" else ""

            // when
            val selfVote =
                request(method, clientPath(fixtureState) + "/$self/rating$query", actorId, body)

            // then
            assertThat(selfVote.statusCode()).isEqualTo(403)
            for (id in listOf(foreignOutfit, Long.MAX_VALUE)) {

                // when
                val response =
                    request(method, clientPath(fixtureState) + "/$id/rating$query", actorId, body)

                // then
                assertEmpty(response, 404)
            }

            // when
            val missingClient =
                request(
                    method,
                    "/api/stylist/clients/${Long.MAX_VALUE}/outfits/${fixtureState.id}/rating$query",
                    actorId,
                    body,
                )

            // then
            assertEmpty(missingClient, 404)
            for ((credential, status) in
                listOf(
                    null to 400,
                    actorId(fixtureState.owner) to 404,
                    actorId(user(UserRole.ADMIN)) to 404,
                )) {

                // when
                val response = request(method, ratingPath(fixtureState) + query, credential, body)

                // then
                assertThat(response.statusCode()).isEqualTo(status)
            }
        }

        // when
        access.revoke(fixtureState.owner.id!!, fixtureState.stylist.id!!)
        val revokedResponses = requestRatingAndHistory(fixtureState, actorId)

        // then
        assertDeniedRatingAndHistory(revokedResponses, 404)

        // when
        access.grant(fixtureState.owner.id!!, fixtureState.stylist.id!!)
        var owner =
            users.changeRoleAndStatus(
                fixtureState.owner.id!!,
                fixtureState.owner.version,
                UserRole.USER,
                UserStatus.BLOCKED,
            )
        val blockedOwnerResponses = requestRatingAndHistory(fixtureState, actorId)

        // then
        assertDeniedRatingAndHistory(blockedOwnerResponses, 404)

        // when
        owner =
            users.changeRoleAndStatus(owner.id!!, owner.version, UserRole.ADMIN, UserStatus.ACTIVE)
        val changedOwnerRoleResponses = requestRatingAndHistory(fixtureState, actorId)

        // then
        assertDeniedRatingAndHistory(changedOwnerRoleResponses, 404)

        // when
        users.changeRoleAndStatus(owner.id!!, owner.version, UserRole.USER, UserStatus.ACTIVE)
        val stylist =
            users.changeRoleAndStatus(
                fixtureState.stylist.id!!,
                fixtureState.stylist.version,
                UserRole.STYLIST,
                UserStatus.BLOCKED,
            )
        val blockedStylistResponses = requestRatingAndHistory(fixtureState, actorId)

        // then
        assertDeniedRatingAndHistory(blockedStylistResponses, 403)

        // when
        users.changeRoleAndStatus(stylist.id!!, stylist.version, UserRole.USER, UserStatus.ACTIVE)
        val changedStylistRoleResponses = requestRatingAndHistory(fixtureState, actorId)

        // then
        assertDeniedRatingAndHistory(changedStylistRoleResponses, 404)
        assertThat(current(fixtureState)).isEmpty()
        assertThat(archived(fixtureState)).isEmpty()
    }

    @Test
    fun `history union orders by time stylist and version and paginates with exact total and permissions`() {
        // given
        val fixtureState = fixture()
        for (stylist in listOf(fixtureState.stylist, fixtureState.other)) {
            ratings.create(
                stylist.id!!,
                fixtureState.owner.id!!,
                fixtureState.id,
                CreateRatingRequest(RatingVote.LIKE),
            )
            ratings.update(
                stylist.id!!,
                fixtureState.owner.id!!,
                fixtureState.id,
                UpdateRatingRequest(RatingVote.DISLIKE, 1),
            )
        }
        val latest = TestTimeConfiguration.FIXED_TIME.plusSeconds(1)
        jdbc.update(
            "UPDATE outfit_rating SET modified_at = ? WHERE outfit_id = ?",
            Timestamp.from(TestTimeConfiguration.FIXED_TIME),
            fixtureState.id,
        )
        jdbc.update(
            "UPDATE outfit_rating_history SET modified_at = ? WHERE outfit_id = ?",
            Timestamp.from(TestTimeConfiguration.FIXED_TIME),
            fixtureState.id,
        )
        jdbc.update(
            "UPDATE outfit_rating_history SET modified_at = ? WHERE outfit_id = ? AND stylist_id = ?",
            Timestamp.from(latest),
            fixtureState.id,
            fixtureState.stylist.id!!,
        )
        val expected =
            listOf(
                fixtureState.stylist.id!! to 1L,
                fixtureState.other.id!! to 2L,
                fixtureState.other.id!! to 1L,
                fixtureState.stylist.id!! to 2L,
            )
        val ownerPath = "/api/outfits/${fixtureState.id}/ratings/history"
        val ownerActorId = actorId(fixtureState.owner)
        for ((path, actorId) in
            listOf(
                ownerPath to ownerActorId,
                historyPath(fixtureState) to actorId(fixtureState.stylist),
            )) {

            // when
            val all = request("GET", path, actorId)

            // then
            assertThat(all.headers().firstValue("X-Total-Count")).hasValue("4")
            val timeline = rows(all)
            assertThat(timeline.map { number(it, "stylistId") to number(it, "version") })
                .isEqualTo(expected)
            assertThat(timeline).allSatisfy { row ->
                assertThat(row.keys)
                    .containsExactlyInAnyOrder(
                        "stylistId",
                        "vote",
                        "version",
                        "modifiedAt",
                        "archivedAt",
                    )
                val expectedArchive =
                    if (number(row, "version") == 2L) null
                    else
                        jdbc
                            .queryForObject(
                                "SELECT archived_at FROM outfit_rating_history WHERE outfit_id = ? AND stylist_id = ? AND version = ?",
                                Timestamp::class.java,
                                fixtureState.id,
                                number(row, "stylistId"),
                                number(row, "version"),
                            )!!
                            .toInstant()
                            .toString()
                assertThat(row["archivedAt"]).isEqualTo(expectedArchive)
            }
            assertThat(timeline.first()["modifiedAt"]).isEqualTo(latest.toString())

            // when
            val page = request("GET", "$path?page=1&size=2", actorId)

            // then
            assertThat(page.headers().firstValue("X-Total-Count")).hasValue("4")
            assertThat(rows(page)).isEqualTo(timeline.drop(2))

            // when
            val empty = request("GET", "$path?page=10&size=2", actorId)

            // then
            assertThat(rows(empty)).isEmpty()
            assertThat(empty.headers().firstValue("X-Total-Count")).hasValue("4")
            for (query in
                listOf(
                    "page=-1",
                    "size=0",
                    "size=51",
                    "page=abc",
                    "page=${Int.MAX_VALUE}&size=2",
                )) {

                // when
                val response = request("GET", "$path?$query", actorId)

                // then
                assertThat(response.statusCode()).isEqualTo(400)
            }
        }

        // when
        val foreignHistory = request("GET", ownerPath, actorId(user()))

        // then
        assertEmpty(foreignHistory, 404)

        // when
        val stylistOwnerHistory = request("GET", ownerPath, actorId(fixtureState.stylist))

        // then
        assertThat(stylistOwnerHistory.statusCode()).isEqualTo(404)

        // when
        val missingActorHistory = request("GET", ownerPath)

        // then
        assertThat(missingActorHistory.statusCode()).isEqualTo(400)

        // when
        val ownerClientHistory =
            request("GET", historyPath(fixtureState), actorId(fixtureState.owner))

        // then
        assertThat(ownerClientHistory.statusCode()).isEqualTo(404)

        // given
        access.revoke(fixtureState.owner.id!!, fixtureState.stylist.id!!)

        // when
        val revokedHistory =
            request("GET", historyPath(fixtureState), actorId(fixtureState.stylist))

        // then
        assertEmpty(revokedHistory, 404)

        // given
        outfits.delete(fixtureState.owner.id!!, fixtureState.id)

        // when
        val deletedOwnerHistory = request("GET", ownerPath, ownerActorId)

        // then
        assertEmpty(deletedOwnerHistory, 404)

        // when
        val deletedClientHistory =
            request("GET", historyPath(fixtureState), actorId(fixtureState.other))

        // then
        assertEmpty(deletedClientHistory, 404)
    }

    @Test
    fun `history retains one repeatable read snapshot when delete commits after ownership read`() {
        // given
        val fixtureState = fixture()
        ratings.create(
            fixtureState.stylist.id!!,
            fixtureState.owner.id!!,
            fixtureState.id,
            CreateRatingRequest(RatingVote.LIKE),
        )
        ratings.update(
            fixtureState.stylist.id!!,
            fixtureState.owner.id!!,
            fixtureState.id,
            UpdateRatingRequest(RatingVote.DISLIKE, 1),
        )
        val loaded = CountDownLatch(1)
        val release = CountDownLatch(1)
        doAnswer { call ->
                val row =
                    mockingDetails(outfitRepository).mockCreationSettings.defaultAnswer.answer(call)
                loaded.countDown()
                assertThat(release.await(10, TimeUnit.SECONDS)).isTrue()
                row
            }
            .`when`(outfitRepository)
            .findByIdAndOwnerId(fixtureState.id, fixtureState.owner.id!!)
        val actorId = actorId(fixtureState.owner)

        // when
        Executors.newSingleThreadExecutor().use { executor ->
            val read =
                executor.submit(
                    Callable {
                        request(
                            "GET",
                            "/api/outfits/${fixtureState.id}/ratings/history?size=1",
                            actorId,
                        )
                    },
                )
            try {
                assertThat(loaded.await(10, TimeUnit.SECONDS)).isTrue()
                outfits.delete(fixtureState.owner.id!!, fixtureState.id)
            } finally {
                release.countDown()
            }
            val response = read.get(30, TimeUnit.SECONDS)

            // then
            assertThat(response.headers().firstValue("X-Total-Count")).hasValue("2")
            val current = rows(response).single()
            assertThat(number(current, "version")).isEqualTo(2)
            assertThat(current["archivedAt"]).isNull()
        }
    }

    @Test
    fun `history collision rolls back written update withdrawal and final archive delete`() {
        // given
        val fixtureState = fixture()
        ratings.create(
            fixtureState.stylist.id!!,
            fixtureState.owner.id!!,
            fixtureState.id,
            CreateRatingRequest(RatingVote.LIKE),
        )
        ratings.create(
            fixtureState.other.id!!,
            fixtureState.owner.id!!,
            fixtureState.id,
            CreateRatingRequest(RatingVote.DISLIKE),
        )
        jdbc.update(
            """INSERT INTO outfit_rating_history (outfit_id, stylist_id, version, vote, modified_at, archived_at)
            SELECT outfit_id, stylist_id, version, 'DISLIKE', modified_at, modified_at FROM outfit_rating
            WHERE outfit_id = ? AND stylist_id = ?""",
            fixtureState.id,
            fixtureState.stylist.id!!,
        )
        val before = current(fixtureState)
        val previous = archived(fixtureState)

        // when
        val updateConflict =
            request(
                "PUT",
                ratingPath(fixtureState),
                actorId(fixtureState.stylist),
                vote("DISLIKE", 1),
            )

        // then
        assertEmpty(updateConflict, 409)
        assertThat(current(fixtureState)).isEqualTo(before)
        assertThat(archived(fixtureState)).isEqualTo(previous)

        // when
        val withdrawalConflict =
            request(
                "DELETE",
                ratingPath(fixtureState) + "?version=1",
                actorId(fixtureState.stylist),
            )

        // then
        assertEmpty(withdrawalConflict, 409)
        assertThat(current(fixtureState)).isEqualTo(before)
        assertThat(archived(fixtureState)).isEqualTo(previous)

        // when
        val deletionConflict =
            request("DELETE", "/api/outfits/${fixtureState.id}", actorId(fixtureState.owner))

        // then
        assertEmpty(deletionConflict, 409)

        // when
        val retainedOutfit = outfits.get(fixtureState.owner.id!!, fixtureState.id)

        // then
        assertThat(retainedOutfit.id).isEqualTo(fixtureState.id)
        assertThat(current(fixtureState)).isEqualTo(before)
        assertThat(archived(fixtureState)).isEqualTo(previous)
    }

    @ParameterizedTest
    @ValueSource(strings = ["update", "withdraw", "delete"])
    fun `outer transaction failure rolls back rating mutation or outfit deletion together with history`(
        operation: String,
    ) {
        // given
        val fixtureState = fixture()
        ratings.create(
            fixtureState.stylist.id!!,
            fixtureState.owner.id!!,
            fixtureState.id,
            CreateRatingRequest(RatingVote.LIKE),
        )
        val before = current(fixtureState)
        val original = outfits.get(fixtureState.owner.id!!, fixtureState.id)

        // when
        val failure = catchThrowable {
            TransactionTemplate(transactionManager).executeWithoutResult {
                when (operation) {
                    "delete" -> outfits.delete(fixtureState.owner.id!!, fixtureState.id)
                    "withdraw" ->
                        ratings.withdraw(
                            fixtureState.stylist.id!!,
                            fixtureState.owner.id!!,
                            fixtureState.id,
                            1,
                        )
                    else ->
                        ratings.update(
                            fixtureState.stylist.id!!,
                            fixtureState.owner.id!!,
                            fixtureState.id,
                            UpdateRatingRequest(RatingVote.DISLIKE, 1),
                        )
                }
                assertThat(current(fixtureState))
                    .isEqualTo(
                        if (operation == "update")
                            listOf(snapshot(fixtureState.stylist.id!!, "DISLIKE", 2))
                        else emptyList<Snapshot>(),
                    )
                assertThat(archived(fixtureState)).isEqualTo(before)
                error("forced failure after writes")
            }
        }

        // then
        assertThat(failure)
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessage("forced failure after writes")
        assertThat(current(fixtureState)).isEqualTo(before)
        assertThat(archived(fixtureState)).isEmpty()

        // when
        val retainedOutfit = outfits.get(fixtureState.owner.id!!, fixtureState.id)

        // then
        assertThat(retainedOutfit).isEqualTo(original)
    }

    @Test
    fun `delete archives last versions of all stylists including version one and retains prior history`() {
        // given
        val fixtureState = fixture()
        ratings.create(
            fixtureState.stylist.id!!,
            fixtureState.owner.id!!,
            fixtureState.id,
            CreateRatingRequest(RatingVote.LIKE),
        )
        ratings.update(
            fixtureState.stylist.id!!,
            fixtureState.owner.id!!,
            fixtureState.id,
            UpdateRatingRequest(RatingVote.DISLIKE, 1),
        )
        ratings.create(
            fixtureState.other.id!!,
            fixtureState.owner.id!!,
            fixtureState.id,
            CreateRatingRequest(RatingVote.LIKE),
        )
        access.revoke(fixtureState.owner.id!!, fixtureState.other.id!!)
        val expected =
            (archived(fixtureState) + current(fixtureState)).sortedWith(
                compareBy({ it.stylistId }, { it.version }),
            )
        val actorId = actorId(fixtureState.owner)

        // when
        val deletion = request("DELETE", "/api/outfits/${fixtureState.id}", actorId)

        // then
        assertEmpty(deletion, 204)
        assertThat(current(fixtureState)).isEmpty()
        assertThat(archived(fixtureState)).isEqualTo(expected)

        // when
        val repeatedDeletion = request("DELETE", "/api/outfits/${fixtureState.id}", actorId)

        // then
        assertEmpty(repeatedDeletion, 404)
        assertThat(archived(fixtureState)).isEqualTo(expected)
    }

    @ParameterizedTest
    @ValueSource(strings = ["POST", "PUT", "DELETE"])
    fun `rating mutation followed by delete archives every committed version`(method: String) {
        // given
        val fixtureState = fixture()
        if (method != "POST")
            ratings.create(
                fixtureState.stylist.id!!,
                fixtureState.owner.id!!,
                fixtureState.id,
                CreateRatingRequest(RatingVote.LIKE),
            )
        val actorId = actorId(fixtureState.stylist)
        val ownerActorId = actorId(fixtureState.owner)

        // when
        val (write, deletion) =
            race(
                fixtureState,
                { mutateRating(fixtureState, method, actorId) },
                { request("DELETE", "/api/outfits/${fixtureState.id}", ownerActorId) },
            )

        // then
        assertThat(write.statusCode())
            .isEqualTo(
                when (method) {
                    "POST" -> 201
                    "PUT" -> 200
                    else -> 204
                },
            )
        assertEmpty(deletion, 204)
        assertThat(current(fixtureState)).isEmpty()
        val expected =
            listOf(snapshot(fixtureState.stylist.id!!, "LIKE", 1)) +
                if (method == "PUT") listOf(snapshot(fixtureState.stylist.id!!, "DISLIKE", 2))
                else emptyList()
        assertThat(archived(fixtureState)).isEqualTo(expected)
    }

    @ParameterizedTest
    @ValueSource(strings = ["POST", "PUT", "DELETE"])
    fun `delete followed by rating mutation rejects waiter without losing final state`(
        method: String,
    ) {
        // given
        val fixtureState = fixture()
        if (method != "POST")
            ratings.create(
                fixtureState.stylist.id!!,
                fixtureState.owner.id!!,
                fixtureState.id,
                CreateRatingRequest(RatingVote.LIKE),
            )
        val actorId = actorId(fixtureState.stylist)
        val ownerActorId = actorId(fixtureState.owner)

        // when
        val (deletion, write) =
            race(
                fixtureState,
                { request("DELETE", "/api/outfits/${fixtureState.id}", ownerActorId) },
                { mutateRating(fixtureState, method, actorId) },
            )

        // then
        assertEmpty(deletion, 204)
        assertEmpty(write, 404)
        assertThat(current(fixtureState)).isEmpty()
        assertThat(archived(fixtureState))
            .isEqualTo(
                if (method != "POST") listOf(snapshot(fixtureState.stylist.id!!, "LIKE", 1))
                else emptyList<Snapshot>(),
            )
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `concurrent duplicate creation or same expected version has one winner`(update: Boolean) {
        // given
        val fixtureState = fixture()
        if (update)
            ratings.create(
                fixtureState.stylist.id!!,
                fixtureState.owner.id!!,
                fixtureState.id,
                CreateRatingRequest(RatingVote.LIKE),
            )
        val actorId = actorId(fixtureState.stylist)
        val method = if (update) "PUT" else "POST"

        // when
        val (first, second) =
            race(
                fixtureState,
                {
                    request(
                        method,
                        ratingPath(fixtureState),
                        actorId,
                        if (update) vote("DISLIKE", 1) else vote(),
                    )
                },
                {
                    request(
                        method,
                        ratingPath(fixtureState),
                        actorId,
                        if (update) vote("LIKE", 1) else vote("DISLIKE"),
                    )
                },
            )

        // then
        assertRating(
            first,
            if (update) 200 else 201,
            if (update) "DISLIKE" else "LIKE",
            if (update) 2 else 1,
        )
        assertEmpty(second, 409)
        assertThat(current(fixtureState))
            .containsExactly(
                snapshot(
                    fixtureState.stylist.id!!,
                    if (update) "DISLIKE" else "LIKE",
                    if (update) 2 else 1,
                ),
            )
        assertThat(archived(fixtureState))
            .isEqualTo(
                if (update) listOf(snapshot(fixtureState.stylist.id!!, "LIKE", 1))
                else emptyList<Snapshot>(),
            )
    }

    @ParameterizedTest
    @CsvSource(
        "DELETE,PUT,204,404",
        "PUT,DELETE,200,409",
        "DELETE,DELETE,204,404",
        "DELETE,POST,204,201",
        "POST,DELETE,409,204",
    )
    fun `withdraw serializes with update another withdrawal and recast`(
        firstMethod: String,
        secondMethod: String,
        firstStatus: Int,
        secondStatus: Int,
    ) {
        // given
        val fixtureState = fixture()
        ratings.create(
            fixtureState.stylist.id!!,
            fixtureState.owner.id!!,
            fixtureState.id,
            CreateRatingRequest(RatingVote.LIKE),
        )
        val actorId = actorId(fixtureState.stylist)

        // when
        val (first, second) =
            race(
                fixtureState,
                { mutateRating(fixtureState, firstMethod, actorId) },
                { mutateRating(fixtureState, secondMethod, actorId) },
            )

        // then
        assertThat(first.statusCode()).isEqualTo(firstStatus)
        assertThat(second.statusCode()).isEqualTo(secondStatus)
        val expectedVote =
            when {
                firstMethod == "PUT" -> "DISLIKE"
                secondMethod == "POST" -> "LIKE"
                else -> null
            }
        assertThat(current(fixtureState))
            .isEqualTo(
                expectedVote?.let { listOf(snapshot(fixtureState.stylist.id!!, it, 2)) }
                    ?: emptyList<Snapshot>(),
            )
        assertThat(archived(fixtureState))
            .containsExactly(snapshot(fixtureState.stylist.id!!, "LIKE", 1))
    }

    @ParameterizedTest
    @ValueSource(strings = ["POST", "PUT", "DELETE"])
    fun `access revoked during lock wait prevents rating mutation`(method: String) {
        // given
        val fixtureState = fixture()
        if (method != "POST")
            ratings.create(
                fixtureState.stylist.id!!,
                fixtureState.owner.id!!,
                fixtureState.id,
                CreateRatingRequest(RatingVote.LIKE),
            )
        val actorId = actorId(fixtureState.stylist)
        val otherActorId = actorId(fixtureState.other)

        // when
        val (other, waiting) =
            race(
                fixtureState,
                { request("POST", ratingPath(fixtureState), otherActorId, vote("DISLIKE")) },
                { mutateRating(fixtureState, method, actorId) },
                { access.revoke(fixtureState.owner.id!!, fixtureState.stylist.id!!) },
            )

        // then
        assertRating(other, 201, "DISLIKE", 1)
        assertEmpty(waiting, 404)
        assertThat(current(fixtureState).filter { it.stylistId == fixtureState.stylist.id })
            .isEqualTo(
                if (method != "POST") listOf(snapshot(fixtureState.stylist.id!!, "LIKE", 1))
                else emptyList<Snapshot>(),
            )
        assertThat(archived(fixtureState)).isEmpty()
    }

    private fun race(
        fixtureState: Fixture,
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
                val row =
                    mockingDetails(outfitRepository).mockCreationSettings.defaultAnswer.answer(call)
                if (firstCall) {
                    assertThat(row).isNotNull()
                    locked.countDown()
                    assertThat(release.await(10, TimeUnit.SECONDS)).isTrue()
                }
                row
            }
            .`when`(outfitRepository)
            .findLockedByIdAndOwnerId(fixtureState.id, fixtureState.owner.id!!)
        Executors.newFixedThreadPool(2).use { executor ->
            val firstResult = executor.submit(Callable { first() })
            try {
                assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue()
                val secondResult = executor.submit(Callable { second() })
                assertThat(enteringSecondLock.await(10, TimeUnit.SECONDS)).isTrue()
                assertDatabaseLockWait()
                whileWaiting()
                release.countDown()
                return firstResult.get(30, TimeUnit.SECONDS) to
                    secondResult.get(30, TimeUnit.SECONDS)
            } finally {
                release.countDown()
            }
        }
    }

    private fun assertDatabaseLockWait() {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        do {
            if (
                jdbc.queryForObject(
                    """SELECT EXISTS (SELECT 1 FROM pg_stat_activity
                WHERE datname = current_database() AND cardinality(pg_blocking_pids(pid)) > 0
                AND query LIKE '%outfit%' AND wait_event_type = 'Lock')""",
                    Boolean::class.java,
                ) == true
            )
                return
            Thread.yield()
        } while (System.nanoTime() < deadline)
        throw AssertionError("The second outfit operation never waited for a PostgreSQL row lock")
    }

    private fun requestRatingAndHistory(
        fixtureState: Fixture,
        actorId: String,
    ): List<HttpResponse<String>> =
        listOf(
            request("POST", ratingPath(fixtureState), actorId, vote()),
            request("PUT", ratingPath(fixtureState), actorId, vote(version = 1)),
            request("DELETE", ratingPath(fixtureState) + "?version=1", actorId),
            request("GET", historyPath(fixtureState), actorId),
        )

    private fun assertDeniedRatingAndHistory(responses: List<HttpResponse<String>>, status: Int) {
        for (response in responses) {
            assertThat(response.statusCode()).isEqualTo(status)
            if (status == 404) assertThat(response.body()).isEmpty()
        }
    }

    private fun fixture(): Fixture {
        val owner = user()
        val stylist = user(UserRole.STYLIST)
        val other = user(UserRole.STYLIST)
        jdbc.insertAccessGrant(owner.id!!, stylist.id!!)
        jdbc.insertAccessGrant(owner.id!!, other.id!!)
        return Fixture(owner, stylist, other, outfit(owner))
    }

    private fun user(role: UserRole = UserRole.USER): AppUser =
        jdbc.insertUser(UUID.randomUUID().toString(), "!", role)

    private fun item(owner: AppUser) =
        jdbc.insertItem(owner.id!!, CreateWardrobeItemRequest("Shirt", 1, "White", "Cotton")).id

    private fun outfit(owner: AppUser) = jdbc.insertOutfit(owner.id!!, listOf(item(owner)))

    private fun input(item: Long) =
        CreateOutfitRequest(
            "Daily",
            listOf(item),
            OutfitWeatherDto(BigDecimal.TEN, 1, BigDecimal.ZERO),
        )

    private fun clientPath(fixtureState: Fixture) =
        "/api/stylist/clients/${fixtureState.owner.id}/outfits"

    private fun ratingPath(fixtureState: Fixture) =
        clientPath(fixtureState) + "/${fixtureState.id}/rating"

    private fun historyPath(fixtureState: Fixture) =
        clientPath(fixtureState) + "/${fixtureState.id}/ratings/history"

    private fun mutateRating(
        fixtureState: Fixture,
        method: String,
        actorId: String,
    ): HttpResponse<String> =
        when (method) {
            "DELETE" -> request(method, ratingPath(fixtureState) + "?version=1", actorId)
            "PUT" -> request(method, ratingPath(fixtureState), actorId, vote("DISLIKE", 1))
            else -> request(method, ratingPath(fixtureState), actorId, vote())
        }

    private fun vote(vote: String = "LIKE", version: Long? = null) =
        """{"vote":"$vote"${version?.let { ",\"version\":$it" } ?: ""}}"""

    private fun actorId(user: AppUser): String = requireNotNull(user.id).toString()

    private fun request(
        method: String,
        path: String,
        actorId: String? = null,
        body: String? = null,
    ): HttpResponse<String> {
        val request =
            HttpRequest.newBuilder(URI("http://localhost:$port$path"))
                .method(
                    method,
                    body?.let(HttpRequest.BodyPublishers::ofString)
                        ?: HttpRequest.BodyPublishers.noBody(),
                )
        if (body != null) request.header("Content-Type", "application/json")
        if (actorId != null) request.header("X-User-Id", "$actorId")
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun dto(response: HttpResponse<String>): Map<String, Any?> =
        JsonPath.read(response.body(), "$")

    private fun rows(response: HttpResponse<String>): List<Map<String, Any?>> {
        assertThat(response.statusCode()).isEqualTo(200)
        return JsonPath.read(response.body(), "$")
    }

    private fun number(row: Map<String, Any?>, key: String) = (row.getValue(key) as Number).toLong()

    private fun assertEmpty(response: HttpResponse<String>, status: Int) {
        assertThat(response.statusCode()).isEqualTo(status)
        assertThat(response.body()).isEmpty()
    }

    private fun assertRating(
        response: HttpResponse<String>,
        status: Int,
        vote: String,
        version: Long,
    ) {
        assertThat(response.statusCode()).isEqualTo(status)
        assertThat(dto(response).keys).containsExactlyInAnyOrder("vote", "version")
        assertThat(dto(response)["vote"]).isEqualTo(vote)
        assertThat(number(dto(response), "version")).isEqualTo(version)
    }

    private fun current(fixtureState: Fixture) = snapshots("outfit_rating", fixtureState)

    private fun archived(fixtureState: Fixture) = snapshots("outfit_rating_history", fixtureState)

    private fun snapshots(table: String, fixtureState: Fixture): List<Snapshot> =
        jdbc.query(
            "SELECT stylist_id, vote, version, modified_at FROM $table WHERE outfit_id = ? ORDER BY stylist_id, version",
            { row, _ ->
                Snapshot(
                    row.getLong("stylist_id"),
                    row.getString("vote"),
                    row.getLong("version"),
                    row.getTimestamp("modified_at").toInstant(),
                )
            },
            fixtureState.id,
        )

    private fun snapshot(stylistId: Long, vote: String, version: Long): Snapshot {
        val modifiedAt =
            jdbc
                .queryForObject(
                    """
            SELECT modified_at FROM outfit_rating WHERE stylist_id = ? AND version = ?
            UNION ALL SELECT modified_at FROM outfit_rating_history WHERE stylist_id = ? AND version = ?
        """,
                    Timestamp::class.java,
                    stylistId,
                    version,
                    stylistId,
                    version,
                )!!
                .toInstant()
        return Snapshot(stylistId, vote, version, modifiedAt)
    }

    private data class Fixture(
        val owner: AppUser,
        val stylist: AppUser,
        val other: AppUser,
        val id: Long,
    )

    private data class Snapshot(
        val stylistId: Long,
        val vote: String,
        val version: Long,
        val modifiedAt: java.time.Instant,
    )

    companion object {}
}
