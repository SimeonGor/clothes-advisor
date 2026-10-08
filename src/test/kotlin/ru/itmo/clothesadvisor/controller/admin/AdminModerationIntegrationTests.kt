package ru.itmo.clothesadvisor.controller.admin

import ru.itmo.clothesadvisor.config.PostgresIntegrationTest
import ru.itmo.clothesadvisor.config.insertAccessGrant
import ru.itmo.clothesadvisor.config.insertItem
import ru.itmo.clothesadvisor.config.insertOutfit
import ru.itmo.clothesadvisor.config.insertRating
import ru.itmo.clothesadvisor.config.insertUser
import com.jayway.jsonpath.JsonPath
import java.math.BigDecimal
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.sql.Timestamp
import java.time.Duration
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.clearInvocations
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mockingDetails
import org.mockito.Mockito.verifyNoInteractions
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate
import ru.itmo.clothesadvisor.config.TestTimeConfiguration
import ru.itmo.clothesadvisor.dto.outfit.CreateOutfitRequest
import ru.itmo.clothesadvisor.dto.outfit.OutfitWeatherDto
import ru.itmo.clothesadvisor.dto.wardrobe.CreateWardrobeItemRequest
import ru.itmo.clothesadvisor.model.user.AppUser
import ru.itmo.clothesadvisor.model.user.UserRole
import ru.itmo.clothesadvisor.model.user.UserStatus
import ru.itmo.clothesadvisor.repository.outfit.OutfitRepository
import ru.itmo.clothesadvisor.repository.wardrobe.WardrobeItemRepository
import ru.itmo.clothesadvisor.service.access.AccessGrantService
import ru.itmo.clothesadvisor.service.user.AppUserService
import ru.itmo.clothesadvisor.service.wardrobe.WardrobeItemService
import ru.itmo.clothesadvisor.storage.wardrobe.PhotoStorageUnavailableException
import ru.itmo.clothesadvisor.storage.wardrobe.S3PhotoStorage

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestTimeConfiguration::class)
internal class AdminModerationIntegrationTests : PostgresIntegrationTest() {
    @LocalServerPort private var port: Int = 0
    @Autowired private lateinit var users: AppUserService
    @Autowired private lateinit var items: WardrobeItemService
    @Autowired private lateinit var access: AccessGrantService
    @Autowired private lateinit var jdbc: JdbcTemplate
    @Autowired private lateinit var transactionManager: PlatformTransactionManager
    @MockitoSpyBean private lateinit var itemRepository: WardrobeItemRepository
    @MockitoSpyBean private lateinit var outfitRepository: OutfitRepository
    @MockitoBean private lateinit var storage: S3PhotoStorage
    private val client = HttpClient.newHttpClient()
    private val passwordHash by lazy { "!" }
    private val objects = ConcurrentHashMap<String, ByteArray>()
    private val image = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47)

    @BeforeEach
    fun prepare() {
        resetMutableData()
        doAnswer { call ->
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse()
            objects.getValue(call.getArgument(0))
        }.`when`(storage).get(anyString(), anyLong())
    }

    @AfterEach fun closeClient() = client.close()

    @Test
    fun `moderation exposes existing DTOs and deletes photos and outfits preserving objects items and rating history`() {
        val f = fixture()
        addRatings(f)
        val actorId = actorId(f.admin)
        val item = dto(request("GET", itemPath(f), actorId))
        assertThat(item.keys).containsExactlyInAnyOrder("id", "name", "categoryId", "color", "material", "version", "createdAt", "modifiedAt")
        assertThat(item).isEqualTo(dto(request("GET", "/api/wardrobe/items/${f.item}", actorId(f.owner))))
        assertThat(rows(request("GET", itemsPath(f), actorId))).containsExactly(item)
        val photos = request("GET", itemPath(f) + "/photos", actorId)
        assertThat(rows(photos)).containsExactly(mapOf("id" to f.photo.toInt(), "itemId" to f.item.toInt(),
            "contentType" to "image/png", "sizeBytes" to image.size, "createdAt" to TestTimeConfiguration.FIXED_TIME.toString()))
        val content = request("GET", photoPath(f) + "/content", actorId)
        assertThat(content.statusCode()).isEqualTo(200)
        assertThat(content.body()).isEqualTo(image)
        assertThat(content.headers().firstValue("Content-Type")).hasValue("image/png")
        assertThat(content.headers().firstValue("Cache-Control")).hasValue("no-store")
        assertThat(content.headers().firstValue("X-Content-Type-Options")).hasValue("nosniff")
        val outfit = dto(request("GET", outfitPath(f), actorId))
        assertThat(outfit.keys).containsExactlyInAnyOrder("id", "ownerId", "authorId", "source", "name", "itemIds", "weather", "createdAt", "likes", "dislikes")
        assertThat(outfit).isEqualTo(dto(request("GET", "/api/outfits/${f.outfit}", actorId(f.owner))))
        assertThat(number(outfit, "likes")).isEqualTo(1)
        assertThat(number(outfit, "dislikes")).isEqualTo(1)
        val allOutfits = request("GET", outfitsPath(f), actorId)
        assertThat(rows(allOutfits)).containsExactly(outfit)
        assertThat(allOutfits.headers().firstValue("X-Total-Count")).hasValue("1")
        val expectedHistory = allRatingVersions(f)
        clearInvocations(storage)
        assertEmpty(request("DELETE", photoPath(f), actorId), 204)
        assertEmpty(request("DELETE", photoPath(f), actorId), 404)
        assertEmpty(request("GET", photoPath(f) + "/content", actorId), 404)
        assertThat(rows(request("GET", itemPath(f) + "/photos", actorId))).isEmpty()
        assertEmpty(request("DELETE", outfitPath(f), actorId), 204)
        assertEmpty(request("DELETE", outfitPath(f), actorId), 404)
        assertEmpty(request("GET", outfitPath(f), actorId), 404)
        assertThat(rows(request("GET", outfitsPath(f), actorId))).isEmpty()
        assertDeletedOutfit(f, expectedHistory)
        assertThat(dto(request("GET", itemPath(f), actorId))).isEqualTo(item)
        assertThat(count("wardrobe_item_history")).isZero()
        assertThat(count("wardrobe_item_photo")).isZero()
        verifyNoInteractions(storage)
    }

    @Test
    fun `lists page in existing order with default and maximum sizes and reject malformed paging`() {
        val f = fixture()
        val itemIds = listOf(f.item) + (1..50).map { item(f.owner) }
        val outfitIds = listOf(f.outfit) + (1..50).map { outfit(f.owner, f.item) }
        val actorId = actorId(f.admin)
        for ((path, ids) in listOf(itemsPath(f) to itemIds, outfitsPath(f) to outfitIds.reversed())) {
            val first = request("GET", path, actorId)
            assertThat(rows(first).map { number(it, "id") }).containsExactlyElementsOf(ids.take(50))
            assertThat(rows(request("GET", "$path?page=1&size=50", actorId)).map { number(it, "id") })
                .containsExactlyElementsOf(ids.drop(50))
            assertThat(rows(request("GET", "$path?page=1&size=1", actorId)).map { number(it, "id") }).containsExactly(ids[1])
            val empty = request("GET", "$path?page=100", actorId)
            assertThat(rows(empty)).isEmpty()
            if (path == outfitsPath(f)) {
                assertThat(first.headers().firstValue("X-Total-Count")).hasValue("51")
                assertThat(empty.headers().firstValue("X-Total-Count")).hasValue("51")
            } else {
                assertThat(first.headers().firstValue("X-Total-Count")).isEmpty()
            }
            for (query in listOf("page=-1", "size=0", "size=51", "page=no", "size=no", "page=2147483647&size=50")) {
                assertThat(request("GET", "$path?$query", actorId).statusCode()).`as`("$path?$query").isEqualTo(400)
            }
        }
        verifyNoInteractions(storage)
    }

    @ParameterizedTest
    @CsvSource("USER, ACTIVE", "USER, BLOCKED", "STYLIST, ACTIVE", "STYLIST, BLOCKED", "ADMIN, ACTIVE", "ADMIN, BLOCKED")
    fun `owner current role and status do not restrict any moderation route`(role: UserRole, status: UserStatus) {
        val f = fixture()
        users.changeRoleAndStatus(f.owner.id!!, 1, role, status)
        val actorId = actorId(f.admin)
        assertThat(count("access_grant")).isZero()
        for ((method, path) in routes(f)) {
            assertThat(request(method, path, actorId).statusCode()).`as`("$method $path").isEqualTo(if (method == "GET") 200 else 204)
        }
    }

    @Test
    fun `all eight routes require active admin using current role and status of existing actorId`() {
        val f = fixture()
        fun denied(actorId: String?, status: Int) {
            routes(f).forEach { (method, path) ->
                assertThat(request(method, path, actorId).statusCode()).`as`("$method $path").isEqualTo(status)
            }
        }
        denied(null, 400)
        denied("invalid-actorId", 400)
        denied(actorId(f.owner), 403)
        denied(actorId(user(UserRole.STYLIST)), 403)
        val actorId = actorId(f.admin)
        users.changeRoleAndStatus(f.admin.id!!, 1, UserRole.ADMIN, UserStatus.BLOCKED)
        denied(actorId, 403)
        users.changeRoleAndStatus(f.admin.id!!, 2, UserRole.USER, UserStatus.ACTIVE)
        denied(actorId, 403)
        users.changeRoleAndStatus(f.admin.id!!, 3, UserRole.ADMIN, UserStatus.ACTIVE)
        assertThat(request("GET", outfitPath(f), actorId).statusCode()).isEqualTo(200)
        assertThat(count("wardrobe_item_photo")).isEqualTo(1)
        assertThat(count("outfit")).isEqualTo(1)
        verifyNoInteractions(storage)
    }

    @Test
    fun `missing owner and mislinked content are hidden and unsupported mutations are absent`() {
        val f = fixture()
        val other = fixture()
        val actorId = actorId(f.admin)
        for (ownerId in listOf(Long.MAX_VALUE, other.owner.id!!)) {
            for ((method, path) in routes(f)) {
                if (ownerId != Long.MAX_VALUE && path in listOf(itemsPath(f), outfitsPath(f))) continue
                assertEmpty(request(method, path.replace("/users/${f.owner.id}/", "/users/$ownerId/"), actorId), 404)
            }
        }
        for (photoId in listOf(other.photo, Long.MAX_VALUE)) {
            assertEmpty(request("GET", itemPath(f) + "/photos/$photoId/content", actorId), 404)
            assertEmpty(request("DELETE", itemPath(f) + "/photos/$photoId", actorId), 404)
        }
        val sibling = item(f.owner)
        assertEmpty(request("GET", itemsPath(f) + "/$sibling/photos/${f.photo}/content", actorId), 404)
        assertEmpty(request("DELETE", itemsPath(f) + "/$sibling/photos/${f.photo}", actorId), 404)
        for ((method, path) in listOf("POST" to itemsPath(f), "PUT" to itemPath(f), "DELETE" to itemPath(f),
            "POST" to itemPath(f) + "/photos", "PUT" to photoPath(f), "POST" to outfitsPath(f), "PUT" to outfitPath(f),
            "POST" to outfitPath(f) + "/rating", "PUT" to outfitPath(f) + "/rating", "DELETE" to outfitPath(f) + "/rating",
            "GET" to outfitPath(f) + "/ratings/history")) {
            assertThat(request(method, path, actorId, if (method in listOf("POST", "PUT")) "{}" else null).statusCode())
                .`as`("$method $path").isIn(404, 405)
        }
        assertThat(count("wardrobe_item_photo")).isEqualTo(2)
        assertThat(count("outfit")).isEqualTo(2)
        verifyNoInteractions(storage)
    }

    @ParameterizedTest
    @CsvSource("USER, ACTIVE", "ADMIN, BLOCKED")
    fun `download rechecks administrator after storage with no surrounding transaction`(role: UserRole, status: UserStatus) {
        val f = fixture()
        val actorId = actorId(f.admin)
        doAnswer {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse()
            users.changeRoleAndStatus(f.admin.id!!, 1, role, status)
            image
        }.`when`(storage).get(anyString(), anyLong())
        assertForbidden(request("GET", photoPath(f) + "/content", actorId))
        assertThat(count("wardrobe_item_photo")).isEqualTo(1)
    }

    @Test
    fun `download rechecks metadata and keeps existing storage failure status`() {
        val f = fixture()
        val actorId = actorId(f.admin)
        doAnswer {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse()
            throw PhotoStorageUnavailableException()
        }.`when`(storage).get(anyString(), anyLong())
        assertEmpty(request("GET", photoPath(f) + "/content", actorId), 503)
        doAnswer {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse()
            assertEmpty(request("DELETE", photoPath(f), actorId), 204)
            image
        }.`when`(storage).get(anyString(), anyLong())
        assertEmpty(request("GET", photoPath(f) + "/content", actorId), 404)
    }

    @ParameterizedTest
    @CsvSource("PHOTO, USER, ACTIVE", "PHOTO, ADMIN, BLOCKED", "OUTFIT, USER, ACTIVE", "OUTFIT, ADMIN, BLOCKED")
    fun `delete rechecks fresh administrator permission after actual business row lock wait`(
        kind: String, role: UserRole, status: UserStatus,
    ) {
        val f = fixture()
        addRatings(f)
        val actorId = actorId(f.admin)
        val beforeHistory = ratingRows("outfit_rating_history", f)
        val beforeCurrent = ratingRows("outfit_rating", f)
        val locked = CountDownLatch(1)
        val enteringLock = CountDownLatch(1)
        val release = CountDownLatch(1)
        val table = if (kind == "PHOTO") "wardrobe_item" else "outfit"
        val id = if (kind == "PHOTO") f.item else f.outfit
        if (kind == "PHOTO") {
            doAnswer { call ->
                enteringLock.countDown()
                mockingDetails(itemRepository).mockCreationSettings.defaultAnswer.answer(call)
            }.`when`(itemRepository).lockOwned(f.item, f.owner.id!!)
        } else {
            doAnswer { call ->
                enteringLock.countDown()
                mockingDetails(outfitRepository).mockCreationSettings.defaultAnswer.answer(call)
            }.`when`(outfitRepository).findLockedByIdAndOwnerId(f.outfit, f.owner.id!!)
        }
        Executors.newFixedThreadPool(2).use { executor ->
            val holder = executor.submit(Callable {
                TransactionTemplate(transactionManager).executeWithoutResult {
                    jdbc.queryForList("SELECT id FROM $table WHERE id = ? FOR UPDATE", id)
                    locked.countDown()
                    assertThat(release.await(15, TimeUnit.SECONDS)).isTrue()
                }
            })
            try {
                assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue()
                val deletion = executor.submit(Callable { request("DELETE", if (kind == "PHOTO") photoPath(f) else outfitPath(f), actorId) })
                assertThat(enteringLock.await(10, TimeUnit.SECONDS)).isTrue()
                assertDatabaseLockWait(table)
                users.changeRoleAndStatus(f.admin.id!!, 1, role, status)
                release.countDown()
                holder.get(20, TimeUnit.SECONDS)
                assertForbidden(deletion.get(20, TimeUnit.SECONDS))
            } finally { release.countDown() }
        }
        assertThat(count("wardrobe_item_photo")).isEqualTo(1)
        assertThat(count("outfit")).isEqualTo(1)
        assertThat(count("outfit_item")).isEqualTo(1)
        assertThat(count("outfit_weather")).isEqualTo(1)
        assertThat(ratingRows("outfit_rating", f)).isEqualTo(beforeCurrent)
        assertThat(ratingRows("outfit_rating_history", f)).isEqualTo(beforeHistory)
        verifyNoInteractions(storage)
    }

    @Test
    fun `owner and admin concurrent outfit deletes archive every version once`() {
        val f = fixture()
        addRatings(f)
        val expectedHistory = allRatingVersions(f)
        val ownerActorId = actorId(f.owner)
        val adminActorId = actorId(f.admin)
        val locked = CountDownLatch(1)
        val enteringSecondLock = CountDownLatch(1)
        val release = CountDownLatch(1)
        val first = AtomicBoolean(true)
        doAnswer { call ->
            val firstCall = first.compareAndSet(true, false)
            if (!firstCall) enteringSecondLock.countDown()
            val result = mockingDetails(outfitRepository).mockCreationSettings.defaultAnswer.answer(call)
            if (firstCall) {
                locked.countDown()
                assertThat(release.await(15, TimeUnit.SECONDS)).isTrue()
            }
            result
        }.`when`(outfitRepository).findLockedByIdAndOwnerId(f.outfit, f.owner.id!!)
        Executors.newFixedThreadPool(2).use { executor ->
            val owner = executor.submit(Callable { request("DELETE", "/api/outfits/${f.outfit}", ownerActorId) })
            try {
                assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue()
                val admin = executor.submit(Callable { request("DELETE", outfitPath(f), adminActorId) })
                assertThat(enteringSecondLock.await(10, TimeUnit.SECONDS)).isTrue()
                assertDatabaseLockWait("outfit")
                release.countDown()
                assertEmpty(owner.get(20, TimeUnit.SECONDS), 204)
                assertEmpty(admin.get(20, TimeUnit.SECONDS), 404)
            } finally { release.countDown() }
        }
        assertDeletedOutfit(f, expectedHistory)
        verifyNoInteractions(storage)
    }

    @Test
    fun `admin outer transaction rolls back written outfit deletion and newly archived ratings together`() {
        val f = fixture()
        addRatings(f)
        val actorId = actorId(f.admin)
        val beforeHistory = ratingRows("outfit_rating_history", f)
        val beforeCurrent = ratingRows("outfit_rating", f)
        doAnswer { call ->
            call.callRealMethod()
            assertThat(count("outfit")).isZero()
            throw DataIntegrityViolationException("Failure after delete")
        }.`when`(outfitRepository).delete(org.mockito.ArgumentMatchers.any(ru.itmo.clothesadvisor.model.outfit.Outfit::class.java)
            ?: ru.itmo.clothesadvisor.model.outfit.Outfit(0, 0, ru.itmo.clothesadvisor.model.outfit.OutfitSource.USER, "fixture", TestTimeConfiguration.FIXED_TIME))
        assertEmpty(request("DELETE", outfitPath(f), actorId), 409)
        assertThat(count("outfit")).isEqualTo(1)
        assertThat(count("outfit_item")).isEqualTo(1)
        assertThat(count("outfit_weather")).isEqualTo(1)
        assertThat(ratingRows("outfit_rating", f)).isEqualTo(beforeCurrent)
        assertThat(ratingRows("outfit_rating_history", f)).isEqualTo(beforeHistory)
        verifyNoInteractions(storage)
    }

    private fun assertDatabaseLockWait(table: String) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        do {
            if (jdbc.queryForObject("""SELECT EXISTS (SELECT 1 FROM pg_stat_activity
                WHERE datname = current_database() AND cardinality(pg_blocking_pids(pid)) > 0
                AND query LIKE ? AND wait_event_type = 'Lock')""", Boolean::class.java, "%$table%") == true) return
            Thread.yield()
        } while (System.nanoTime() < deadline)
        throw AssertionError("Administrative delete never waited for the PostgreSQL $table row lock")
    }

    private fun fixture(): Fixture {
        val owner = user()
        val item = item(owner)
        val key = UUID.randomUUID().toString()
        objects[key] = image
        val photo = jdbc.queryForObject("""INSERT INTO wardrobe_item_photo
            (wardrobe_item_id, s3_key, content_type, size_bytes, created_at) VALUES (?, ?, 'image/png', ?, ?) RETURNING id""",
            Long::class.java, item, key, image.size, Timestamp.from(TestTimeConfiguration.FIXED_TIME))!!
        return Fixture(user(UserRole.ADMIN), owner, item, photo, outfit(owner, item))
    }

    private fun addRatings(f: Fixture) {
        val first = user(UserRole.STYLIST)
        val second = user(UserRole.STYLIST)
        for (stylist in listOf(first, second)) {
            jdbc.insertAccessGrant(f.owner.id!!, stylist.id!!)
            jdbc.insertRating(f.outfit, stylist.id!!)
        }
        jdbc.update("""INSERT INTO outfit_rating_history (outfit_id, stylist_id, vote, version, modified_at)
            SELECT outfit_id, stylist_id, vote, version, modified_at FROM outfit_rating
            WHERE outfit_id = ? AND stylist_id = ?""", f.outfit, first.id)
        jdbc.update("""UPDATE outfit_rating SET vote = 'DISLIKE', version = 2, modified_at = CURRENT_TIMESTAMP
            WHERE outfit_id = ? AND stylist_id = ?""", f.outfit, first.id)
        jdbc.update("DELETE FROM access_grant WHERE owner_id = ? AND stylist_id IN (?, ?)", f.owner.id, first.id, second.id)
        assertThat(count("access_grant")).isZero()
    }

    private fun ratingRows(table: String, f: Fixture): List<Map<String, Any?>> = jdbc.queryForList(
        "SELECT outfit_id, stylist_id, vote::text, version, modified_at FROM $table WHERE outfit_id = ? ORDER BY stylist_id, version", f.outfit)

    private fun allRatingVersions(f: Fixture) = ratingRows("outfit_rating_history", f) + ratingRows("outfit_rating", f)

    private fun assertDeletedOutfit(f: Fixture, expectedHistory: List<Map<String, Any?>>) {
        assertThat(count("outfit")).isZero()
        assertThat(count("outfit_item")).isZero()
        assertThat(count("outfit_weather")).isZero()
        assertThat(count("outfit_rating")).isZero()
        assertThat(count("wardrobe_item")).isEqualTo(1)
        assertThat(ratingRows("outfit_rating_history", f)).containsExactlyInAnyOrderElementsOf(expectedHistory)
        assertThat(expectedHistory).hasSize(3)
        assertThat(jdbc.queryForObject("SELECT bool_and(archived_at IS NOT NULL) FROM outfit_rating_history WHERE outfit_id = ?",
            Boolean::class.java, f.outfit)).isTrue()
    }

    private fun user(role: UserRole = UserRole.USER) = jdbc.insertUser(UUID.randomUUID().toString(), passwordHash, role)
    private fun item(owner: AppUser) = jdbc.insertItem(owner.id!!, CreateWardrobeItemRequest("Shirt", 1, "White", "Cotton")).id
    private fun outfit(owner: AppUser, item: Long) = jdbc.insertOutfit(owner.id!!, listOf(item))
    private fun itemsPath(f: Fixture) = "/api/admin/users/${f.owner.id}/wardrobe/items"
    private fun itemPath(f: Fixture) = itemsPath(f) + "/${f.item}"
    private fun photoPath(f: Fixture) = itemPath(f) + "/photos/${f.photo}"
    private fun outfitsPath(f: Fixture) = "/api/admin/users/${f.owner.id}/outfits"
    private fun outfitPath(f: Fixture) = outfitsPath(f) + "/${f.outfit}"
    private fun routes(f: Fixture) = listOf("GET" to itemsPath(f), "GET" to itemPath(f), "GET" to itemPath(f) + "/photos",
        "GET" to photoPath(f) + "/content", "GET" to outfitsPath(f), "GET" to outfitPath(f),
        "DELETE" to photoPath(f), "DELETE" to outfitPath(f))
    private fun actorId(user: AppUser): String = requireNotNull(user.id).toString()
    private fun request(method: String, path: String, actorId: String? = null, body: String? = null): HttpResponse<ByteArray> {
        val request = HttpRequest.newBuilder(URI("http://localhost:$port$path")).timeout(Duration.ofSeconds(30))
            .method(method, body?.let(HttpRequest.BodyPublishers::ofString) ?: HttpRequest.BodyPublishers.noBody())
        if (body != null) request.header("Content-Type", "application/json")
        if (actorId != null) request.header("X-User-Id", "$actorId")
        return client.send(request.build(), HttpResponse.BodyHandlers.ofByteArray())
    }
    private fun dto(response: HttpResponse<ByteArray>): Map<String, Any?> {
        assertThat(response.statusCode()).isEqualTo(200)
        return JsonPath.read(String(response.body()), "$")
    }
    private fun rows(response: HttpResponse<ByteArray>): List<Map<String, Any?>> {
        assertThat(response.statusCode()).isEqualTo(200)
        return JsonPath.read(String(response.body()), "$")
    }
    private fun number(row: Map<String, Any?>, key: String) = (row.getValue(key) as Number).toLong()
    private fun assertEmpty(response: HttpResponse<ByteArray>, status: Int) {
        assertThat(response.statusCode()).isEqualTo(status)
        assertThat(response.body()).isEmpty()
    }
    private fun assertForbidden(response: HttpResponse<ByteArray>) {
        assertThat(response.statusCode()).isEqualTo(403)
        assertThat(String(response.body())).isEqualTo("{\"error\":\"forbidden\"}")
    }
    private fun count(table: String) = jdbc.queryForObject("SELECT count(*) FROM $table", Long::class.java)!!
    private data class Fixture(val admin: AppUser, val owner: AppUser, val item: Long, val photo: Long, val outfit: Long)

    companion object {
    }
}
