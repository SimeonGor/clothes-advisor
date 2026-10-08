package ru.itmo.clothesadvisor.controller.admin

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
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionSynchronizationManager
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
import ru.itmo.clothesadvisor.repository.wardrobe.WardrobeItemRepository
import ru.itmo.clothesadvisor.service.access.AccessGrantService
import ru.itmo.clothesadvisor.service.outfit.OutfitService
import ru.itmo.clothesadvisor.service.rating.OutfitRatingService
import ru.itmo.clothesadvisor.service.user.AppUserService
import ru.itmo.clothesadvisor.service.wardrobe.WardrobeItemService
import ru.itmo.clothesadvisor.storage.wardrobe.PhotoStorageUnavailableException
import ru.itmo.clothesadvisor.storage.wardrobe.S3PhotoStorage

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestTimeConfiguration::class)
internal class AdminModerationIntegrationTests {
    @LocalServerPort private var port: Int = 0
    @Autowired private lateinit var users: AppUserService
    @Autowired private lateinit var passwords: PasswordEncoder
    @Autowired private lateinit var items: WardrobeItemService
    @Autowired private lateinit var outfits: OutfitService
    @Autowired private lateinit var ratings: OutfitRatingService
    @Autowired private lateinit var access: AccessGrantService
    @Autowired private lateinit var jdbc: JdbcTemplate
    @Autowired private lateinit var transactionManager: PlatformTransactionManager
    @MockitoSpyBean private lateinit var itemRepository: WardrobeItemRepository
    @MockitoSpyBean private lateinit var outfitRepository: OutfitRepository
    @MockitoBean private lateinit var storage: S3PhotoStorage
    private val client = HttpClient.newHttpClient()
    private val passwordHash by lazy { passwords.encode(PASSWORD)!! }
    private val objects = ConcurrentHashMap<String, ByteArray>()
    private val image = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47)

    @BeforeEach
    fun prepare() {
        jdbc.execute("""TRUNCATE TABLE outfit_rating_history, outfit_rating, outfit_item, outfit_weather, outfit, access_grant,
            wardrobe_item_photo, wardrobe_item_history, wardrobe_item, app_user_history, app_user RESTART IDENTITY""")
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
        val token = login(f.admin)
        val item = dto(request("GET", itemPath(f), token))
        assertThat(item.keys).containsExactlyInAnyOrder("id", "name", "categoryId", "color", "material", "version", "createdAt", "modifiedAt")
        assertThat(item).isEqualTo(dto(request("GET", "/api/wardrobe/items/${f.item}", login(f.owner))))
        assertThat(rows(request("GET", itemsPath(f), token))).containsExactly(item)
        val photos = request("GET", itemPath(f) + "/photos", token)
        assertThat(rows(photos)).containsExactly(mapOf("id" to f.photo.toInt(), "itemId" to f.item.toInt(),
            "contentType" to "image/png", "sizeBytes" to image.size, "createdAt" to TestTimeConfiguration.FIXED_TIME.toString()))
        val content = request("GET", photoPath(f) + "/content", token)
        assertThat(content.statusCode()).isEqualTo(200)
        assertThat(content.body()).isEqualTo(image)
        assertThat(content.headers().firstValue("Content-Type")).hasValue("image/png")
        assertThat(content.headers().firstValue("Cache-Control")).hasValue("no-store")
        assertThat(content.headers().firstValue("X-Content-Type-Options")).hasValue("nosniff")
        val outfit = dto(request("GET", outfitPath(f), token))
        assertThat(outfit.keys).containsExactlyInAnyOrder("id", "ownerId", "authorId", "source", "name", "itemIds", "weather", "createdAt", "likes", "dislikes")
        assertThat(outfit).isEqualTo(dto(request("GET", "/api/outfits/${f.outfit}", login(f.owner))))
        assertThat(number(outfit, "likes")).isEqualTo(1)
        assertThat(number(outfit, "dislikes")).isEqualTo(1)
        val allOutfits = request("GET", outfitsPath(f), token)
        assertThat(rows(allOutfits)).containsExactly(outfit)
        assertThat(allOutfits.headers().firstValue("X-Total-Count")).hasValue("1")
        val expectedHistory = allRatingVersions(f)
        clearInvocations(storage)
        assertEmpty(request("DELETE", photoPath(f), token), 204)
        assertEmpty(request("DELETE", photoPath(f), token), 404)
        assertEmpty(request("GET", photoPath(f) + "/content", token), 404)
        assertThat(rows(request("GET", itemPath(f) + "/photos", token))).isEmpty()
        assertEmpty(request("DELETE", outfitPath(f), token), 204)
        assertEmpty(request("DELETE", outfitPath(f), token), 404)
        assertEmpty(request("GET", outfitPath(f), token), 404)
        assertThat(rows(request("GET", outfitsPath(f), token))).isEmpty()
        assertDeletedOutfit(f, expectedHistory)
        assertThat(dto(request("GET", itemPath(f), token))).isEqualTo(item)
        assertThat(count("wardrobe_item_history")).isZero()
        assertThat(count("wardrobe_item_photo")).isZero()
        verifyNoInteractions(storage)
    }

    @Test
    fun `lists page in existing order with default and maximum sizes and reject malformed paging`() {
        val f = fixture()
        val itemIds = listOf(f.item) + (1..50).map { item(f.owner) }
        val outfitIds = listOf(f.outfit) + (1..50).map { outfit(f.owner, f.item) }
        val token = login(f.admin)
        for ((path, ids) in listOf(itemsPath(f) to itemIds, outfitsPath(f) to outfitIds.reversed())) {
            val first = request("GET", path, token)
            assertThat(rows(first).map { number(it, "id") }).containsExactlyElementsOf(ids.take(50))
            assertThat(rows(request("GET", "$path?page=1&size=50", token)).map { number(it, "id") })
                .containsExactlyElementsOf(ids.drop(50))
            assertThat(rows(request("GET", "$path?page=1&size=1", token)).map { number(it, "id") }).containsExactly(ids[1])
            val empty = request("GET", "$path?page=100", token)
            assertThat(rows(empty)).isEmpty()
            if (path == outfitsPath(f)) {
                assertThat(first.headers().firstValue("X-Total-Count")).hasValue("51")
                assertThat(empty.headers().firstValue("X-Total-Count")).hasValue("51")
            } else {
                assertThat(first.headers().firstValue("X-Total-Count")).isEmpty()
            }
            for (query in listOf("page=-1", "size=0", "size=51", "page=no", "size=no", "page=2147483647&size=50")) {
                assertThat(request("GET", "$path?$query", token).statusCode()).`as`("$path?$query").isEqualTo(400)
            }
        }
        verifyNoInteractions(storage)
    }

    @ParameterizedTest
    @CsvSource("USER, ACTIVE", "USER, BLOCKED", "STYLIST, ACTIVE", "STYLIST, BLOCKED", "ADMIN, ACTIVE", "ADMIN, BLOCKED")
    fun `owner current role and status do not restrict any moderation route`(role: UserRole, status: UserStatus) {
        val f = fixture()
        users.changeRoleAndStatus(f.owner.id!!, 1, role, status)
        val token = login(f.admin)
        assertThat(count("access_grant")).isZero()
        for ((method, path) in routes(f)) {
            assertThat(request(method, path, token).statusCode()).`as`("$method $path").isEqualTo(if (method == "GET") 200 else 204)
        }
    }

    @Test
    fun `all eight routes require active admin using current role and status of existing token`() {
        val f = fixture()
        fun denied(token: String?, status: Int) {
            routes(f).forEach { (method, path) ->
                assertThat(request(method, path, token).statusCode()).`as`("$method $path").isEqualTo(status)
            }
        }
        denied(null, 401)
        denied("invalid-token", 401)
        denied(login(f.owner), 403)
        denied(login(user(UserRole.STYLIST)), 403)
        val token = login(f.admin)
        users.changeRoleAndStatus(f.admin.id!!, 1, UserRole.ADMIN, UserStatus.BLOCKED)
        denied(token, 401)
        users.changeRoleAndStatus(f.admin.id!!, 2, UserRole.USER, UserStatus.ACTIVE)
        denied(token, 403)
        users.changeRoleAndStatus(f.admin.id!!, 3, UserRole.ADMIN, UserStatus.ACTIVE)
        assertThat(request("GET", outfitPath(f), token).statusCode()).isEqualTo(200)
        assertThat(count("wardrobe_item_photo")).isEqualTo(1)
        assertThat(count("outfit")).isEqualTo(1)
        verifyNoInteractions(storage)
    }

    @Test
    fun `missing owner and mislinked content are hidden and unsupported mutations are absent`() {
        val f = fixture()
        val other = fixture()
        val token = login(f.admin)
        for (ownerId in listOf(Long.MAX_VALUE, other.owner.id!!)) {
            for ((method, path) in routes(f)) {
                if (ownerId != Long.MAX_VALUE && path in listOf(itemsPath(f), outfitsPath(f))) continue
                assertEmpty(request(method, path.replace("/users/${f.owner.id}/", "/users/$ownerId/"), token), 404)
            }
        }
        for (photoId in listOf(other.photo, Long.MAX_VALUE)) {
            assertEmpty(request("GET", itemPath(f) + "/photos/$photoId/content", token), 404)
            assertEmpty(request("DELETE", itemPath(f) + "/photos/$photoId", token), 404)
        }
        val sibling = item(f.owner)
        assertEmpty(request("GET", itemsPath(f) + "/$sibling/photos/${f.photo}/content", token), 404)
        assertEmpty(request("DELETE", itemsPath(f) + "/$sibling/photos/${f.photo}", token), 404)
        for ((method, path) in listOf("POST" to itemsPath(f), "PUT" to itemPath(f), "DELETE" to itemPath(f),
            "POST" to itemPath(f) + "/photos", "PUT" to photoPath(f), "POST" to outfitsPath(f), "PUT" to outfitPath(f),
            "POST" to outfitPath(f) + "/rating", "PUT" to outfitPath(f) + "/rating", "DELETE" to outfitPath(f) + "/rating",
            "GET" to outfitPath(f) + "/ratings/history")) {
            assertThat(request(method, path, token, if (method in listOf("POST", "PUT")) "{}" else null).statusCode())
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
        val token = login(f.admin)
        doAnswer {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse()
            users.changeRoleAndStatus(f.admin.id!!, 1, role, status)
            image
        }.`when`(storage).get(anyString(), anyLong())
        assertForbidden(request("GET", photoPath(f) + "/content", token))
        assertThat(count("wardrobe_item_photo")).isEqualTo(1)
    }

    @Test
    fun `download rechecks metadata and keeps existing storage failure status`() {
        val f = fixture()
        val token = login(f.admin)
        doAnswer {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse()
            throw PhotoStorageUnavailableException()
        }.`when`(storage).get(anyString(), anyLong())
        assertEmpty(request("GET", photoPath(f) + "/content", token), 503)
        doAnswer {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse()
            assertEmpty(request("DELETE", photoPath(f), token), 204)
            image
        }.`when`(storage).get(anyString(), anyLong())
        assertEmpty(request("GET", photoPath(f) + "/content", token), 404)
    }

    @ParameterizedTest
    @CsvSource("PHOTO, USER, ACTIVE", "PHOTO, ADMIN, BLOCKED", "OUTFIT, USER, ACTIVE", "OUTFIT, ADMIN, BLOCKED")
    fun `delete rechecks fresh administrator permission after actual business row lock wait`(
        kind: String, role: UserRole, status: UserStatus,
    ) {
        val f = fixture()
        addRatings(f)
        val token = login(f.admin)
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
                val deletion = executor.submit(Callable { request("DELETE", if (kind == "PHOTO") photoPath(f) else outfitPath(f), token) })
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
        val ownerToken = login(f.owner)
        val adminToken = login(f.admin)
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
            val owner = executor.submit(Callable { request("DELETE", "/api/outfits/${f.outfit}", ownerToken) })
            try {
                assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue()
                val admin = executor.submit(Callable { request("DELETE", outfitPath(f), adminToken) })
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
    fun `admin outer transaction rolls back flushed outfit deletion and newly archived ratings together`() {
        val f = fixture()
        addRatings(f)
        val token = login(f.admin)
        val beforeHistory = ratingRows("outfit_rating_history", f)
        val beforeCurrent = ratingRows("outfit_rating", f)
        doAnswer { call ->
            mockingDetails(outfitRepository).mockCreationSettings.defaultAnswer.answer(call)
            assertThat(count("outfit")).isZero()
            throw DataIntegrityViolationException("Failure after delete flush")
        }.`when`(outfitRepository).flush()
        assertEmpty(request("DELETE", outfitPath(f), token), 409)
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
            access.grant(f.owner.id!!, stylist.id!!)
            ratings.create(stylist.id!!, f.owner.id!!, f.outfit, CreateRatingRequest(RatingVote.LIKE))
        }
        ratings.update(first.id!!, f.owner.id!!, f.outfit, UpdateRatingRequest(RatingVote.DISLIKE, 1))
        for (stylist in listOf(first, second)) access.revoke(f.owner.id!!, stylist.id!!)
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
        assertThat(jdbc.queryForObject("SELECT bool_and(archived_at = ?) FROM outfit_rating_history WHERE outfit_id = ?",
            Boolean::class.java, Timestamp.from(TestTimeConfiguration.FIXED_TIME), f.outfit)).isTrue()
    }

    private fun user(role: UserRole = UserRole.USER) = users.create(UUID.randomUUID().toString(), passwordHash, role)
    private fun item(owner: AppUser) = items.create(owner.id!!, CreateWardrobeItemRequest("Shirt", 1, "White", "Cotton")).id
    private fun outfit(owner: AppUser, item: Long) = outfits.create(owner.id!!,
        CreateOutfitRequest("Daily", listOf(item), OutfitWeatherDto(BigDecimal.TEN, 1, BigDecimal.ZERO))).id
    private fun itemsPath(f: Fixture) = "/api/admin/users/${f.owner.id}/wardrobe/items"
    private fun itemPath(f: Fixture) = itemsPath(f) + "/${f.item}"
    private fun photoPath(f: Fixture) = itemPath(f) + "/photos/${f.photo}"
    private fun outfitsPath(f: Fixture) = "/api/admin/users/${f.owner.id}/outfits"
    private fun outfitPath(f: Fixture) = outfitsPath(f) + "/${f.outfit}"
    private fun routes(f: Fixture) = listOf("GET" to itemsPath(f), "GET" to itemPath(f), "GET" to itemPath(f) + "/photos",
        "GET" to photoPath(f) + "/content", "GET" to outfitsPath(f), "GET" to outfitPath(f),
        "DELETE" to photoPath(f), "DELETE" to outfitPath(f))
    private fun login(user: AppUser): String {
        val response = request("POST", "/api/auth/login", body = """{"login":"${user.login}","password":"$PASSWORD"}""")
        assertThat(response.statusCode()).isEqualTo(200)
        return dto(response)["accessToken"].toString()
    }
    private fun request(method: String, path: String, token: String? = null, body: String? = null): HttpResponse<ByteArray> {
        val request = HttpRequest.newBuilder(URI("http://localhost:$port$path")).timeout(Duration.ofSeconds(30))
            .method(method, body?.let(HttpRequest.BodyPublishers::ofString) ?: HttpRequest.BodyPublishers.noBody())
        if (body != null) request.header("Content-Type", "application/json")
        if (token != null) request.header("Authorization", "Bearer $token")
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
        private const val PASSWORD = "admin-moderation-password"
        @Container @JvmStatic val postgres = PostgreSQLContainer("postgres:18-alpine")
        @JvmStatic @DynamicPropertySource
        fun postgresProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
        }
    }
}
