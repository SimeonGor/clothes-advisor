package ru.itmo.clothesadvisor.controller.ai

import ru.itmo.clothesadvisor.client.ai.AiOutfitFailure

import ru.itmo.clothesadvisor.config.PostgresIntegrationTest
import ru.itmo.clothesadvisor.config.insertItem
import ru.itmo.clothesadvisor.config.insertUser
import com.jayway.jsonpath.JsonPath
import java.math.BigDecimal
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyList
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.ArgumentMatchers.anyString
import org.mockito.ArgumentMatchers.any as anyArgument
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mockingDetails
import org.mockito.Mockito.verify
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
import ru.itmo.clothesadvisor.client.ai.AiCandidateImage
import ru.itmo.clothesadvisor.client.ai.AiOutfitException
import ru.itmo.clothesadvisor.client.ai.AiWeather
import ru.itmo.clothesadvisor.client.ai.OpenAiOutfitClient
import ru.itmo.clothesadvisor.config.TestTimeConfiguration
import ru.itmo.clothesadvisor.dto.outfit.OutfitWeatherDto
import ru.itmo.clothesadvisor.dto.wardrobe.CreateWardrobeItemRequest
import ru.itmo.clothesadvisor.dto.wardrobe.UpdateWardrobeItemRequest
import ru.itmo.clothesadvisor.model.user.AppUser
import ru.itmo.clothesadvisor.model.user.UserRole
import ru.itmo.clothesadvisor.model.user.UserStatus
import ru.itmo.clothesadvisor.repository.outfit.OutfitRepository
import ru.itmo.clothesadvisor.repository.outfit.OutfitItemRepository
import ru.itmo.clothesadvisor.repository.wardrobe.WardrobeItemPhotoRepository
import ru.itmo.clothesadvisor.repository.wardrobe.WardrobeItemRepository
import ru.itmo.clothesadvisor.service.user.AppUserService
import ru.itmo.clothesadvisor.service.wardrobe.WardrobeItemPhotoService
import ru.itmo.clothesadvisor.service.wardrobe.WardrobeItemService
import ru.itmo.clothesadvisor.storage.wardrobe.PhotoStorageUnavailableException
import ru.itmo.clothesadvisor.storage.wardrobe.S3PhotoStorage

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestTimeConfiguration::class)
class AiOutfitIntegrationTests : PostgresIntegrationTest() {
    @LocalServerPort private var port: Int = 0
    @Autowired private lateinit var users: AppUserService
    @Autowired private lateinit var items: WardrobeItemService
    @Autowired private lateinit var photoService: WardrobeItemPhotoService
    @Autowired private lateinit var jdbc: JdbcTemplate
    @Autowired private lateinit var transactionManager: PlatformTransactionManager
    @MockitoBean private lateinit var storage: S3PhotoStorage
    @MockitoBean private lateinit var provider: OpenAiOutfitClient
    @MockitoSpyBean private lateinit var wardrobe: WardrobeItemRepository
    @MockitoSpyBean private lateinit var photos: WardrobeItemPhotoRepository
    @MockitoSpyBean private lateinit var composition: OutfitItemRepository
    private val http = HttpClient.newHttpClient()
    private val providerCalls = AtomicInteger()
    private val downloaded = mutableListOf<String>()
    private var onDownload: () -> Unit = {}
    private var select: (List<AiCandidateImage>) -> List<Long> = { it.map { image -> image.item.id }.reversed() }

    @BeforeEach
    fun externalCallsOutsideTransactions() {
        doAnswer { call ->
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse()
            downloaded += call.getArgument<String>(0)
            onDownload()
            byteArrayOf(1, 2, 3)
        }.`when`(storage).get(anyString(), anyLong())
        val weather = AiWeather(OutfitWeatherDto(BigDecimal.ZERO, 1, BigDecimal.ONE), "", "")
        doAnswer { call ->
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse()
            providerCalls.incrementAndGet()
            val actual = call.getArgument<AiWeather>(0)
            assertThat(actual.values.temperatureC).isEqualByComparingTo(TEMPERATURE)
            assertThat(actual.precipitationCode).isNotBlank()
            assertThat(actual.precipitationName).isNotBlank()
            select(call.getArgument(1))
        }.`when`(provider).select(anyArgument(AiWeather::class.java) ?: weather, anyList())
    }

    @AfterEach fun close() = http.close()

    @Test
    fun `absent null and explicit candidates save immutable AI outfits and reuse old reads with exact weather`() {
        val owner = connectedUser()
        val actorId = actorId(owner)
        val first = item(owner)
        val second = item(owner)
        val noPhoto = item(owner, false)
        photo(first, "second-photo")
        item(user())
        select = { images ->
            assertThat(images.map { it.item.id }).containsExactly(first, second)
            assertThat(images.map { it.item.photoId }).hasSize(2)
            listOf(second, first)
        }
        for (candidates in listOf(null, "null", "[$second,$noPhoto,$first]")) {
            val created = request("POST", actorId = actorId, body = body(candidates))
            assertThat(created.statusCode()).isEqualTo(201)
            val row = dto(created)
            val id = number(row, "id")
            assertThat(created.headers().firstValue("Location")).hasValue("/api/outfits/$id")
            assertThat(number(row, "ownerId")).isEqualTo(owner.id)
            assertThat(number(row, "authorId")).isEqualTo(owner.id)
            assertThat(row["source"]).isEqualTo("AI")
            assertThat(row["createdAt"]).isEqualTo(jdbc.queryForObject("SELECT created_at FROM outfit WHERE id = ?", java.sql.Timestamp::class.java, id)!!.toInstant().toString())
            assertThat((row["itemIds"] as List<*>).map { (it as Number).toLong() }).containsExactly(second, first)
            val read = request("GET", "/api/outfits/$id", actorId)
            assertThat(dto(read)).isEqualTo(row)
            assertThat(read.body()).contains("\"temperatureC\":$TEMPERATURE", "\"windSpeedMps\":$WIND")
            assertThat(request("PUT", "/api/outfits/$id", actorId, body()).statusCode()).isEqualTo(405)
        }
        assertThat(downloaded).containsExactly("photo-$first", "photo-$second", "photo-$first", "photo-$second", "photo-$first", "photo-$second")
        assertThat(providerCalls).hasValue(3)
        assertThat(JsonPath.read<List<Any>>(request("GET", "/api/outfits", actorId).body(), "$")).hasSize(3)
    }

    @Test
    fun `known bad inputs do not touch storage or provider and foreign IDs remain 404 without photos`() {
        val owner = connectedUser()
        val actorId = actorId(owner)
        val own = item(owner, false)
        val foreign = item(user(), false)
        for (ids in listOf("[]", "[0]", "[-1]", "[null]", "[$own,$own]", "[$own]", null)) {
            assertThat(request("POST", actorId = actorId, body = body(ids)).statusCode()).isEqualTo(400)
        }
        for (ids in listOf("[$foreign]", "[$own,$foreign]", "[9223372036854775807]")) {
            assertThat(request("POST", actorId = actorId, body = body(ids)).statusCode()).isEqualTo(404)
        }
        item(owner)
        for (invalid in listOf(body().replace("Daily", " "), body().replace("Daily", "x".repeat(301)),
            body().replace("\"precipitationTypeId\":1", "\"precipitationTypeId\":999999"),
            body().replace(WIND, "-1"), "{}")) {
            assertThat(request("POST", actorId = actorId, body = invalid).statusCode()).isEqualTo(400)
        }
        verifyNoInteractions(storage)
        assertThat(providerCalls).hasValue(0)
        assertNoOutfits(owner)
    }

    @Test
    fun `twenty candidates succeed twenty one fail and automatic query is bounded to twenty one`() {
        val owner = connectedUser()
        val actorId = actorId(owner)
        val ids = List(20) { item(owner) }
        select = { listOf(it.last().item.id) }
        assertThat(request("POST", actorId = actorId, body = body()).statusCode()).isEqualTo(201)
        verify(wardrobe).findOwnedItemsWithPhotos(owner.id!!, 21)
        item(owner)
        val downloads = downloaded.size
        assertThat(request("POST", actorId = actorId, body = body()).statusCode()).isEqualTo(400)
        val all = jdbc.queryForList("SELECT id FROM wardrobe_item WHERE owner_id = ? ORDER BY id", Long::class.java, owner.id)
        assertThat(request("POST", actorId = actorId, body = body(all.toString())).statusCode()).isEqualTo(400)
        assertThat(downloaded).hasSize(downloads)
        assertThat(providerCalls).hasValue(1)
        assertThat(request("POST", actorId = actorId, body = body(ids.toString())).statusCode()).isEqualTo(201)
    }

    @Test
    fun `photo deletion after bounded query cannot hide an oversized wardrobe`() {
        val owner = connectedUser()
        val actorId = actorId(owner)
        val ids = List(22) { item(owner) }
        val firstPhoto = firstPhoto(ids.first())
        doAnswer { call ->
            val rows = mockingDetails(wardrobe).mockCreationSettings.defaultAnswer.answer(call)
            Executors.newSingleThreadExecutor().use { executor ->
                executor.submit { photoService.delete(owner.id!!, ids.first(), firstPhoto) }.get(5, TimeUnit.SECONDS)
            }
            rows
        }.`when`(wardrobe).findOwnedItemsWithPhotos(owner.id!!, 21)

        assertEmpty(request("POST", actorId = actorId, body = body()), 400)
        assertThat(jdbc.queryForObject("""
            SELECT count(DISTINCT wardrobe_item_id) FROM wardrobe_item_photo
            WHERE wardrobe_item_id IN (SELECT id FROM wardrobe_item WHERE owner_id = ?)
        """, Long::class.java, owner.id)).isEqualTo(21)
        verifyNoInteractions(storage)
        assertThat(providerCalls).hasValue(0)
        assertNoOutfits(owner)
    }

    @Test
    fun `only active USER may call AI and selected owner overrides extra fields`() {
        val owner = connectedUser()
        val actorId = actorId(owner)
        val id = item(owner)
        val blocked = user()
        val blockedActorId = actorId(blocked)
        users.changeRoleAndStatus(blocked.id!!, blocked.version, blocked.role, UserStatus.BLOCKED)
        for ((callerId, status) in listOf(null to 400, "invalid" to 400, blockedActorId to 403,
            actorId(user(UserRole.STYLIST)) to 403, actorId(user(UserRole.ADMIN)) to 403)) {
            assertThat(request("POST", actorId = callerId, body = body()).statusCode()).isEqualTo(status)
        }
        verifyNoInteractions(storage)
        val created = request("POST", actorId = actorId, body = body("[$id]").dropLast(1) +
            ",\"ownerId\":${blocked.id},\"authorId\":${blocked.id},\"source\":\"USER\"}")
        assertThat(created.statusCode()).isEqualTo(201)
        assertThat(number(dto(created), "ownerId")).isEqualTo(owner.id)
        assertThat(number(dto(created), "authorId")).isEqualTo(owner.id)
        assertThat(dto(created)["source"]).isEqualTo("AI")
    }

    @Test
    fun `other active USER cannot use connected account or touch credentials storage and provider`() {
        connectedUser()
        val foreign = user()
        item(foreign)
        assertEmpty(request("POST", actorId = actorId(foreign), body = body()), 503)
        verifyNoInteractions(provider, storage)
        assertNoOutfits(foreign)
    }

    @Test
    fun `unconfigured AI and remote failures leave no outfit or partial rows`() {
        val owner = connectedUser()
        val actorId = actorId(owner)
        item(owner)
        doThrow(AiOutfitException(AiOutfitFailure.UNAVAILABLE)).`when`(provider).requireAvailable()
        assertEmpty(request("POST", actorId = actorId, body = body()), 503)
        verifyNoInteractions(storage)
        doAnswer { null }.`when`(provider).requireAvailable()
        for (failure in listOf(AiOutfitFailure.NO_OUTFIT, AiOutfitFailure.INVALID_RESPONSE, AiOutfitFailure.UNAVAILABLE, AiOutfitFailure.TIMEOUT)) {
            select = { throw AiOutfitException(failure) }
            assertEmpty(request("POST", actorId = actorId, body = body()), failure.status)
            assertNoOutfits(owner)
        }
        onDownload = { throw PhotoStorageUnavailableException() }
        assertEmpty(request("POST", actorId = actorId, body = body()), 503)
        assertNoOutfits(owner)
    }

    @Test
    fun `photo deletion during S3 download conflicts and skips OpenAI`() {
        val owner = connectedUser()
        val actorId = actorId(owner)
        val id = item(owner)
        val photoId = firstPhoto(id)
        onDownload = { photoService.delete(owner.id!!, id, photoId) }
        assertEmpty(request("POST", actorId = actorId, body = body()), 409)
        assertThat(providerCalls).hasValue(0)
        assertNoOutfits(owner)
    }

    @Test
    fun `deletion of an unselected candidate during OpenAI conflicts and ordinary edits remain allowed`() {
        val owner = connectedUser()
        val actorId = actorId(owner)
        val chosen = item(owner)
        val unused = item(owner)
        select = {
            items.delete(owner.id!!, unused, 1)
            listOf(chosen)
        }
        assertEmpty(request("POST", actorId = actorId, body = body()), 409)
        assertNoOutfits(owner)
        select = {
            items.update(owner.id!!, chosen, UpdateWardrobeItemRequest(1, "Edited", 1, "Black", "Wool"))
            listOf(chosen)
        }
        assertThat(request("POST", actorId = actorId, body = body()).statusCode()).isEqualTo(201)
    }

    @Test
    fun `account revocation during downloads or inference prevents persistence`() {
        for (duringDownload in listOf(true, false)) {
            resetMutableData()
            val owner = connectedUser()
            val actorId = actorId(owner)
            item(owner)
            if (duringDownload) onDownload = {
                users.changeRoleAndStatus(owner.id!!, owner.version, UserRole.STYLIST, UserStatus.ACTIVE)
            } else {
                onDownload = {}
                select = {
                    users.changeRoleAndStatus(owner.id!!, owner.version, owner.role, UserStatus.BLOCKED)
                    it.map { image -> image.item.id }
                }
            }
            assertThat(request("POST", actorId = actorId, body = body()).statusCode()).isEqualTo(403)
            assertNoOutfits(owner)
        }
        assertThat(providerCalls).hasValue(1)
    }

    @Test
    fun `final user lock waits then observes newly committed revocation`() {
        finalLockWait(account = true)
    }

    @Test
    fun `final item lock waits then observes newly committed photo deletion`() {
        finalLockWait(account = false)
    }

    private fun finalLockWait(account: Boolean) {
        val owner = connectedUser()
        val actorId = actorId(owner)
        val item = item(owner)
        val locked = CountDownLatch(1)
        val release = CountDownLatch(1)
        val providerEntered = CountDownLatch(1)
        val blockerPid = AtomicInteger()
        select = {
            providerEntered.countDown()
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue()
            listOf(item)
        }
        Executors.newFixedThreadPool(2).use { executor ->
            val creation = executor.submit(Callable { request("POST", actorId = actorId, body = body()) })
            assertThat(providerEntered.await(10, TimeUnit.SECONDS)).isTrue()
            val blocker = executor.submit(Callable {
                TransactionTemplate(transactionManager).executeWithoutResult {
                    blockerPid.set(jdbc.queryForObject("SELECT pg_backend_pid()", Int::class.java)!!)
                    if (account) {
                        jdbc.queryForObject("SELECT id FROM app_user WHERE id = ? FOR UPDATE", Long::class.java, owner.id)
                        jdbc.update("UPDATE app_user SET status = 'BLOCKED' WHERE id = ?", owner.id)
                    } else {
                        jdbc.queryForObject("SELECT id FROM wardrobe_item WHERE id = ? FOR NO KEY UPDATE", Long::class.java, item)
                        jdbc.update("DELETE FROM wardrobe_item_photo WHERE wardrobe_item_id = ?", item)
                    }
                    locked.countDown()
                    assertThat(release.await(10, TimeUnit.SECONDS)).isTrue()
                }
            })
            try {
                assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue()
                awaitBlockedBy(blockerPid.get())
            } finally { release.countDown() }
            blocker.get(15, TimeUnit.SECONDS)
            assertThat(creation.get(15, TimeUnit.SECONDS).statusCode()).isEqualTo(if (account) 403 else 409)
        }
        assertNoOutfits(owner)
    }

    @Test
    fun `shared item lock holds photo deletion behind final metadata check until creation commits`() {
        val owner = connectedUser()
        val actorId = actorId(owner)
        val item = item(owner)
        val photo = firstPhoto(item)
        val checked = CountDownLatch(1)
        val release = CountDownLatch(1)
        val reads = AtomicInteger()
        val creatorPid = AtomicInteger()
        doAnswer { call ->
            val value = mockingDetails(photos).mockCreationSettings.defaultAnswer.answer(call)
            if (reads.incrementAndGet() == 3) {
                creatorPid.set(jdbc.queryForObject("SELECT pg_backend_pid()", Int::class.java)!!)
                checked.countDown()
                assertThat(release.await(10, TimeUnit.SECONDS)).isTrue()
            }
            value
        }.`when`(photos).findByIdAndItemIdAndItemOwnerId(photo, item, owner.id!!)
        Executors.newFixedThreadPool(2).use { executor ->
            val creation = executor.submit(Callable { request("POST", actorId = actorId, body = body()) })
            assertThat(checked.await(10, TimeUnit.SECONDS)).isTrue()
            val deletion = executor.submit(Callable { photoService.delete(owner.id!!, item, photo) })
            try { awaitBlockedBy(creatorPid.get()) } finally { release.countDown() }
            assertThat(creation.get(15, TimeUnit.SECONDS).statusCode()).isEqualTo(201)
            deletion.get(15, TimeUnit.SECONDS)
        }
        assertThat(count("wardrobe_item_photo", "id", photo)).isZero()
        assertThat(count("outfit", "owner_id", owner.id!!)).isEqualTo(1)
    }

    @Test
    fun `failure after composition write rolls back outfit weather and composition together`() {
        val owner = connectedUser()
        val actorId = actorId(owner)
        item(owner)
        var writtenId = 0L
        doAnswer { call ->
            org.mockito.Mockito.mockingDetails(call.mock).mockCreationSettings.defaultAnswer.answer(call)
            writtenId = jdbc.queryForObject("SELECT id FROM outfit WHERE owner_id = ?", Long::class.java, owner.id)!!
            assertThat(count("outfit_weather", "outfit_id", writtenId)).isEqualTo(1)
            assertThat(count("outfit_item", "outfit_id", writtenId)).isEqualTo(1)
            throw DataIntegrityViolationException("synthetic failure after composition write")
        }.`when`(composition).insertAll(anyList())
        assertEmpty(request("POST", actorId = actorId, body = body()), 409)
        assertThat(writtenId).isPositive()
        assertNoOutfits(owner)
        assertThat(count("outfit_weather", "outfit_id", writtenId)).isZero()
        assertThat(count("outfit_item", "outfit_id", writtenId)).isZero()
    }

    private fun awaitBlockedBy(pid: Int) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            if (jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE ? = ANY(pg_blocking_pids(pid))",
                    Long::class.java, pid)!! > 0) return
            Thread.sleep(10)
        }
        throw AssertionError("Expected a database lock waiter blocked by $pid")
    }

    private fun connectedUser(): AppUser = jdbc.insertUser("user", "!", UserRole.USER)

    private fun user(role: UserRole = UserRole.USER): AppUser =
        jdbc.insertUser(UUID.randomUUID().toString(), "!", role)

    private fun item(owner: AppUser, withPhoto: Boolean = true): Long {
        val id = jdbc.insertItem(owner.id!!, CreateWardrobeItemRequest("Shirt", 1, "White", "Cotton")).id
        if (withPhoto) photo(id, "photo-$id")
        return id
    }

    private fun photo(item: Long, key: String): Long = jdbc.queryForObject("""
        INSERT INTO wardrobe_item_photo (wardrobe_item_id, s3_key, content_type, size_bytes, created_at)
        VALUES (?, ?, 'image/png', 3, ?) RETURNING id
    """, Long::class.java, item, key, java.sql.Timestamp.from(TestTimeConfiguration.FIXED_TIME))!!

    private fun firstPhoto(item: Long): Long = jdbc.queryForObject(
        "SELECT id FROM wardrobe_item_photo WHERE wardrobe_item_id = ? ORDER BY id LIMIT 1", Long::class.java, item)!!

    private fun actorId(user: AppUser): String = requireNotNull(user.id).toString()

    private fun body(candidates: String? = null): String = """{"name":"Daily","weather":{"temperatureC":$TEMPERATURE,"precipitationTypeId":1,"windSpeedMps":$WIND}${candidates?.let { ",\"candidateItemIds\":$it" } ?: ""}}"""

    private fun request(method: String, path: String = "/api/outfits/ai", actorId: String? = null, body: String? = null): HttpResponse<String> {
        val builder = HttpRequest.newBuilder(URI("http://localhost:$port$path"))
        if (actorId != null) builder.header("X-User-Id", "$actorId")
        if (body != null) builder.header("Content-Type", "application/json")
        return http.send(builder.method(method, body?.let(HttpRequest.BodyPublishers::ofString) ?: HttpRequest.BodyPublishers.noBody()).build(),
            HttpResponse.BodyHandlers.ofString())
    }

    private fun dto(response: HttpResponse<String>): Map<String, Any> = JsonPath.read(response.body(), "$")
    private fun number(row: Map<String, Any>, key: String): Long = (row.getValue(key) as Number).toLong()
    private fun count(table: String, column: String, id: Long): Long =
        jdbc.queryForObject("SELECT count(*) FROM $table WHERE $column = ?", Long::class.java, id)!!
    private fun assertNoOutfits(owner: AppUser) = assertThat(count("outfit", "owner_id", owner.id!!)).isZero()
    private fun assertEmpty(response: HttpResponse<String>, status: Int) {
        assertThat(response.statusCode()).isEqualTo(status)
        assertThat(response.body()).isEmpty()
    }

    companion object {
        private const val TEMPERATURE = "-123.123456789123456789"
        private const val WIND = "1.123456789123456789"
    }
}
