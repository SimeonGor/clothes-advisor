package ru.itmo.clothesadvisor.controller.capsule

import com.jayway.jsonpath.JsonPath
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.doThrow
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
import ru.itmo.clothesadvisor.config.AccountBackedIntegrationTest
import ru.itmo.clothesadvisor.config.TestTimeConfiguration
import ru.itmo.clothesadvisor.config.insertItem
import ru.itmo.clothesadvisor.config.insertUser
import ru.itmo.clothesadvisor.dto.wardrobe.CreateWardrobeItemRequest
import ru.itmo.clothesadvisor.model.access.AccountUnavailableException
import ru.itmo.clothesadvisor.model.capsule.CapsuleItem
import ru.itmo.clothesadvisor.model.user.UserRole
import ru.itmo.clothesadvisor.repository.capsule.CapsuleItemRepository
import ru.itmo.clothesadvisor.repository.capsule.CapsuleRepository
import ru.itmo.clothesadvisor.repository.idempotency.IdempotencyCommandRepository
import ru.itmo.clothesadvisor.repository.wardrobe.WardrobeItemRepository
import ru.itmo.clothesadvisor.storage.wardrobe.S3PhotoStorage

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestTimeConfiguration::class)
@Execution(ExecutionMode.SAME_THREAD)
class CapsuleIntegrationTests : AccountBackedIntegrationTest() {
    @LocalServerPort private var port = 0
    @Autowired private lateinit var jdbc: JdbcTemplate
    @MockitoSpyBean private lateinit var capsules: CapsuleRepository
    @MockitoSpyBean private lateinit var composition: CapsuleItemRepository
    @MockitoSpyBean private lateinit var wardrobe: WardrobeItemRepository
    @MockitoSpyBean private lateinit var commands: IdempotencyCommandRepository
    @MockitoBean private lateinit var storage: S3PhotoStorage
    private val client = HttpClient.newHttpClient()
    private val path = "/api/me/capsules"

    @AfterEach fun closeClientAndCheckStorage() {
        client.close()
        verifyNoInteractions(storage)
    }

    @ParameterizedTest
    @ValueSource(ints = [1, 20])
    fun `creates ordered immutable capsule with database date and deletes only its composition`(size: Int) {
        // given
        val owner = user()
        val ids = List(size) { item(owner) }.reversed()
        val name = " " + "x".repeat(299)

        // when
        val created = http("POST", owner, payload(ids, name))
        val id = id(created)
        val found = http("GET", owner, target = "$path/$id")
        val update = http("PUT", owner, payload(ids), "$path/$id")

        // then
        assertThat(created.headers().firstValue("Location")).hasValue("$path/$id")
        assertThat(found.statusCode()).isEqualTo(200)
        assertThat(found.body()).isEqualTo(created.body())
        val body = JsonPath.parse(created.body())
        assertThat(body.read<Map<String, Any?>>("$").keys)
            .containsExactlyInAnyOrder("id", "ownerId", "name", "itemIds", "createdAt")
        assertThat(body.read<Number>("$.ownerId").toLong()).isEqualTo(owner)
        assertThat(body.read<String>("$.name")).isEqualTo(name)
        assertThat(itemIds(created)).isEqualTo(ids)
        assertThat(Instant.parse(body.read<String>("$.createdAt")))
            .isEqualTo(jdbc.queryForObject("SELECT created_at FROM capsule WHERE id = ?", Timestamp::class.java, id)!!.toInstant())
        assertThat(jdbc.queryForList("SELECT position FROM capsule_item WHERE capsule_id = ? ORDER BY position", Int::class.java, id))
            .containsExactlyElementsOf((0 until size).toList())
        assertThat(update.statusCode()).isEqualTo(405)

        // when
        val deleted = http("DELETE", owner, target = "$path/$id")

        // then
        assertThat(deleted.statusCode()).isEqualTo(204)
        assertThat(count("capsule")).isZero()
        assertThat(count("capsule_item")).isZero()
        assertThat(count("wardrobe_item")).isEqualTo(size.toLong())
        assertThat(http("GET", owner, target = "$path/$id").statusCode()).isEqualTo(404)
        assertThat(http("DELETE", owner, target = "$path/$id").statusCode()).isEqualTo(404)
    }

    @Test
    fun `rejects invalid names ids and foreign or missing items without writes`() {
        // given
        val owner = user()
        val own = item(owner)
        val foreign = item(user())
        val tooMany = List(21) { item(owner) }
        val invalid = listOf(payload(emptyList()), payload(listOf(own, own)), payload(listOf(0)), payload(listOf(-1)),
            payload(tooMany), payload(listOf(own), ""), payload(listOf(own), "   "), payload(listOf(own), "x".repeat(301)),
            """{"name":"Daily","itemIds":[null]}""")

        // when
        val responses = invalid.map { http("POST", owner, it) }
        val missing = listOf(foreign, Long.MAX_VALUE).map { http("POST", owner, payload(listOf(own, it))) }

        // then
        assertThat(responses.map { it.statusCode() }).containsOnly(400)
        assertThat(missing.map { it.statusCode() }).containsOnly(404)
        assertThat(count("capsule")).isZero()
        assertThat(count("capsule_item")).isZero()
    }

    @Test
    fun `pagination returns only own capsules ordered descending with total and bounds`() {
        // given
        val owner = user()
        val other = user()
        val ids = List(3) { id(http("POST", owner, payload(listOf(item(owner))))) }
        val foreign = id(http("POST", other, payload(listOf(item(other)))))

        // when
        val page = http("GET", owner, target = "$path?page=1&size=2")
        val all = http("GET", owner)
        val empty = http("GET", owner, target = "$path?page=3&size=2")

        // then
        assertThat(page.statusCode()).isEqualTo(200)
        assertThat(page.headers().firstValue("X-Total-Count")).hasValue("3")
        assertThat(JsonPath.parse(page.body()).read<List<Number>>("$[*].id").map { it.toLong() }).containsExactly(ids.first())
        assertThat(JsonPath.parse(all.body()).read<List<Number>>("$[*].id").map { it.toLong() }).isEqualTo(ids.reversed())
        assertThat(empty.body()).isEqualTo("[]")
        assertThat(empty.headers().firstValue("X-Total-Count")).hasValue("3")
        for (query in listOf("page=-1", "size=0", "size=51")) {
            assertThat(http("GET", owner, target = "$path?$query").statusCode()).isEqualTo(400)
        }
        assertThat(http("GET", owner, target = "$path/$foreign").statusCode()).isEqualTo(404)
        assertThat(http("DELETE", owner, target = "$path/$foreign").statusCode()).isEqualTo(404)
    }

    @ParameterizedTest
    @ValueSource(strings = ["STYLIST", "ADMIN", "BLOCKED", "MISSING", "UNAVAILABLE"])
    fun `current account permissions are rechecked before keyed replay and all reads and deletes`(state: String) {
        // given
        val owner = user()
        val request = payload(listOf(item(owner)))
        val id = id(http("POST", owner, request, key = "access"))
        val expected = when (state) {
            "MISSING" -> { jdbc.update("DELETE FROM app_user WHERE id = ?", owner); 404 }
            "UNAVAILABLE" -> { doThrow(AccountUnavailableException()).`when`(accounts).getUser(owner); 503 }
            "BLOCKED" -> { jdbc.update("UPDATE app_user SET status = 'BLOCKED' WHERE id = ?", owner); 403 }
            else -> { jdbc.update("UPDATE app_user SET role = ?::user_role WHERE id = ?", state, owner); 403 }
        }

        // when
        val responses = listOf(http("POST", owner, request, key = "access"), http("GET", owner),
            http("GET", owner, target = "$path/$id"), http("DELETE", owner, target = "$path/$id"))

        // then
        assertThat(responses.map { it.statusCode() }).containsOnly(expected)
        assertThat(count("capsule")).isEqualTo(1)
        assertThat(count("capsule_item")).isEqualTo(1)
    }

    @Test
    fun `key replays exact result after deletion and rejects different payload`() {
        // given
        val owner = user()
        val ids = listOf(item(owner))
        val request = payload(ids)
        val created = http("POST", owner, request, key = "saved")
        val id = id(created)

        // when
        val replay = http("POST", owner, request, key = "saved")
        val conflict = http("POST", owner, payload(ids, "Other"), key = "saved")
        assertThat(http("DELETE", owner, target = "$path/$id").statusCode()).isEqualTo(204)
        assertThat(http("DELETE", owner, target = "/api/wardrobe/items/${ids.single()}?version=1").statusCode()).isEqualTo(204)
        val deletedReplay = http("POST", owner, request, key = "saved")

        // then
        assertThat(replay.statusCode()).isEqualTo(201)
        assertThat(deletedReplay.statusCode()).isEqualTo(201)
        assertThat(listOf(replay.body(), deletedReplay.body())).containsOnly(created.body())
        assertThat(deletedReplay.headers().firstValue("Location")).isEqualTo(created.headers().firstValue("Location"))
        assertThat(conflict.statusCode()).isEqualTo(409)
        assertThat(conflict.body()).contains("idempotency_key_reused")
        assertThat(count("capsule")).isZero()
        assertThat(jdbc.queryForMap("SELECT operation, state, response_status FROM idempotency_command"))
            .containsEntry("operation", "CREATE_CAPSULE").containsEntry("state", "SUCCEEDED").containsEntry("response_status", 201)
    }

    @ParameterizedTest
    @ValueSource(strings = ["composition", "result"])
    fun `failure after composition writes rolls back capsule and result atomically`(stage: String) {
        // given
        val owner = user()
        val request = payload(List(2) { item(owner) })
        val failure = DataIntegrityViolationException("forced $stage")
        if (stage == "composition") {
            doAnswer { call ->
                mockingDetails(call.mock).mockCreationSettings.defaultAnswer.answer(call)
                throw failure
            }.`when`(composition).insertAll(any<Iterable<CapsuleItem>>() ?: emptyList())
        } else doThrow(failure).`when`(commands).succeed(anyLong(), anyString())

        // when
        val failed = http("POST", owner, request, key = "failed")
        val replay = http("POST", owner, request, key = "failed")

        // then
        assertThat(failed.statusCode()).isEqualTo(409)
        assertThat(replay.statusCode()).isEqualTo(409)
        assertThat(replay.body()).contains("idempotency_failed")
        assertThat(count("capsule")).isZero()
        assertThat(count("capsule_item")).isZero()
        assertThat(jdbc.queryForMap("SELECT state, response_json FROM idempotency_command"))
            .containsEntry("state", "FAILED").containsEntry("response_json", null)
    }

    @Test
    fun `referenced item delete preserves item photo and history until all capsules are removed`() {
        // given
        val owner = user()
        val item = item(owner)
        photo(item)
        val first = id(http("POST", owner, payload(listOf(item))))
        val second = id(http("POST", owner, payload(listOf(item))))
        val original = jdbc.queryForMap("SELECT * FROM wardrobe_item WHERE id = ?", item)

        // when
        val blocked = http("DELETE", owner, target = "/api/wardrobe/items/$item?version=1")

        // then
        assertThat(blocked.statusCode()).isEqualTo(409)
        assertThat(jdbc.queryForMap("SELECT * FROM wardrobe_item WHERE id = ?", item)).isEqualTo(original)
        assertThat(count("wardrobe_item_photo")).isEqualTo(1)
        assertThat(count("wardrobe_item_history")).isZero()

        // when
        assertThat(http("DELETE", owner, target = "$path/$first").statusCode()).isEqualTo(204)
        val stillUsed = http("DELETE", owner, target = "/api/wardrobe/items/$item?version=1")
        assertThat(http("DELETE", owner, target = "$path/$second").statusCode()).isEqualTo(204)

        // then
        assertThat(stillUsed.statusCode()).isEqualTo(409)
        assertThat(count("wardrobe_item")).isEqualTo(1)
        assertThat(count("wardrobe_item_photo")).isEqualTo(1)
        assertThat(count("wardrobe_item_history")).isZero()

        // when
        val deleted = http("DELETE", owner, target = "/api/wardrobe/items/$item?version=1")

        // then
        assertThat(deleted.statusCode()).isEqualTo(204)
        assertThat(count("wardrobe_item_photo")).isZero()
        assertThat(count("wardrobe_item_history")).isEqualTo(1)
    }

    @Test
    fun `availability characteristics and admin photo moderation preserve capsule composition`() {
        // given
        val owner = user()
        val admin = user(UserRole.ADMIN)
        val ids = List(3) { item(owner) }
        jdbc.update("UPDATE wardrobe_item SET availability = 'IN_LAUNDRY' WHERE id = ?", ids[1])
        jdbc.update("UPDATE wardrobe_item SET availability = 'STORED' WHERE id = ?", ids[2])
        val photo = photo(ids[0])
        val created = http("POST", owner, payload(ids))
        val id = id(created)

        // when
        val changed = http("PUT", owner,
            """{"version":1,"name":"Changed","categoryId":2,"color":"red","material":"wool","availability":"STORED"}""",
            "/api/wardrobe/items/${ids[0]}")
        val moderated = http("DELETE", admin, target = "/api/admin/users/$owner/wardrobe/items/${ids[0]}/photos/$photo")

        // then
        assertThat(changed.statusCode()).isEqualTo(200)
        assertThat(moderated.statusCode()).isEqualTo(204)
        assertThat(http("GET", owner, target = "$path/$id").body()).isEqualTo(created.body())
        assertThat(count("wardrobe_item_photo")).isZero()
        assertThat(count("wardrobe_item")).isEqualTo(3)
    }

    @Test
    fun `item deleted after ownership check makes creation conflict without orphan or successful key`() {
        // given
        val owner = user()
        val item = item(owner)
        val checked = CountDownLatch(1)
        val release = CountDownLatch(1)
        doAnswer { call ->
            val count = mockingDetails(call.mock).mockCreationSettings.defaultAnswer.answer(call)
            checked.countDown()
            check(release.await(15, TimeUnit.SECONDS))
            count
        }.`when`(wardrobe).countByOwnerIdAndIdIn(owner, listOf(item))
        Executors.newSingleThreadExecutor().use { executor ->
            val creation = executor.submit(Callable { http("POST", owner, payload(listOf(item)), key = "race") })
            try {
                check(checked.await(10, TimeUnit.SECONDS))

                // when
                assertThat(http("DELETE", owner, target = "/api/wardrobe/items/$item?version=1").statusCode()).isEqualTo(204)
                release.countDown()
                val failed = creation.get(20, TimeUnit.SECONDS)

                // then
                assertThat(failed.statusCode()).isEqualTo(409)
                assertThat(count("capsule")).isZero()
                assertThat(count("capsule_item")).isZero()
                assertThat(count("wardrobe_item_history")).isEqualTo(1)
                assertThat(http("POST", owner, payload(listOf(item)), key = "race").body()).contains("idempotency_failed")
            } finally { release.countDown() }
        }
    }

    @Test
    fun `composition in progress blocks item deletion and key replays after commit`() {
        // given
        val owner = user()
        val item = item(owner)
        photo(item)
        val inserted = CountDownLatch(1)
        val release = CountDownLatch(1)
        doAnswer { call ->
            val result = mockingDetails(call.mock).mockCreationSettings.defaultAnswer.answer(call)
            inserted.countDown()
            check(release.await(15, TimeUnit.SECONDS))
            result
        }.`when`(composition).insertAll(any<Iterable<CapsuleItem>>() ?: emptyList())
        Executors.newFixedThreadPool(2).use { executor ->
            val creation = executor.submit(Callable { http("POST", owner, payload(listOf(item)), key = "pending") })
            try {
                check(inserted.await(10, TimeUnit.SECONDS))

                // when
                val pending = http("POST", owner, payload(listOf(item)), key = "pending")
                val deletion = executor.submit(Callable {
                    http("DELETE", owner, target = "/api/wardrobe/items/$item?version=1")
                })
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                while (jdbc.queryForObject("""SELECT EXISTS (SELECT 1 FROM pg_stat_activity
                    WHERE datname = current_database() AND cardinality(pg_blocking_pids(pid)) > 0
                        AND query LIKE '%wardrobe_item%' AND wait_event_type = 'Lock')""", Boolean::class.java) != true) {
                    check(System.nanoTime() < deadline) { "Expected PostgreSQL item lock wait" }
                    Thread.yield()
                }
                release.countDown()
                val created = creation.get(20, TimeUnit.SECONDS)
                val deleted = deletion.get(20, TimeUnit.SECONDS)
                val replay = http("POST", owner, payload(listOf(item)), key = "pending")

                // then
                assertThat(pending.statusCode()).isEqualTo(409)
                assertThat(pending.body()).contains("idempotency_in_progress")
                assertThat(created.statusCode()).isEqualTo(201)
                assertThat(deleted.statusCode()).isEqualTo(409)
                assertThat(replay.statusCode()).isEqualTo(201)
                assertThat(replay.body()).isEqualTo(created.body())
                assertThat(count("capsule")).isEqualTo(1)
                assertThat(count("capsule_item")).isEqualTo(1)
                assertThat(count("wardrobe_item")).isEqualTo(1)
                assertThat(count("wardrobe_item_photo")).isEqualTo(1)
                assertThat(count("wardrobe_item_history")).isZero()
            } finally { release.countDown() }
        }
    }

    @Test
    fun `list retains page composition and count snapshot during capsule deletion`() {
        // given
        val owner = user()
        val created = http("POST", owner, payload(listOf(item(owner))))
        val id = id(created)
        val loaded = CountDownLatch(1)
        val release = CountDownLatch(1)
        doAnswer { call ->
            val rows = mockingDetails(call.mock).mockCreationSettings.defaultAnswer.answer(call)
            loaded.countDown()
            check(release.await(15, TimeUnit.SECONDS))
            rows
        }.`when`(capsules).findPage(owner, 50, 0)
        Executors.newSingleThreadExecutor().use { executor ->
            val reading = executor.submit(Callable { http("GET", owner) })
            try {
                check(loaded.await(10, TimeUnit.SECONDS))

                // when
                assertThat(http("DELETE", owner, target = "$path/$id").statusCode()).isEqualTo(204)
                release.countDown()
                val page = reading.get(20, TimeUnit.SECONDS)

                // then
                assertThat(page.statusCode()).isEqualTo(200)
                assertThat(page.headers().firstValue("X-Total-Count")).hasValue("1")
                assertThat(page.body()).isEqualTo("[${created.body()}]")
                assertThat(count("capsule")).isZero()
            } finally { release.countDown() }
        }
    }

    private fun user(role: UserRole = UserRole.USER) = jdbc.insertUser(UUID.randomUUID().toString(), "!", role).id
    private fun item(owner: Long) = jdbc.insertItem(owner, CreateWardrobeItemRequest("Shirt", 1, "blue", "cotton")).id
    private fun photo(item: Long) = jdbc.queryForObject("""INSERT INTO wardrobe_item_photo
        (wardrobe_item_id, s3_key, content_type, size_bytes) VALUES (?, ?, 'image/png', 1) RETURNING id""",
        Long::class.java, item, UUID.randomUUID().toString())!!
    private fun count(table: String) = jdbc.queryForObject("SELECT count(*) FROM $table", Long::class.java)!!
    private fun itemIds(response: HttpResponse<String>) = JsonPath.parse(response.body()).read<List<Number>>("$.itemIds").map { it.toLong() }
    private fun id(response: HttpResponse<String>): Long {
        assertThat(response.statusCode()).isEqualTo(201)
        return JsonPath.parse(response.body()).read<Number>("$.id").toLong()
    }
    private fun payload(ids: List<Long>, name: String = "Daily") = """{"name":"$name","itemIds":[${ids.joinToString(",")}]}"""
    private fun http(method: String, actor: Long, body: String? = null, target: String = path, key: String? = null): HttpResponse<String> {
        val request = HttpRequest.newBuilder(URI("http://localhost:$port$target"))
            .timeout(Duration.ofSeconds(25)).header("X-User-Id", actor.toString())
            .method(method, body?.let(HttpRequest.BodyPublishers::ofString) ?: HttpRequest.BodyPublishers.noBody())
        if (body != null) request.header("Content-Type", "application/json")
        if (key != null) request.header("Idempotency-Key", key)
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString())
    }
}
