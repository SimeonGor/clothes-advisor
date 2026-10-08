package ru.itmo.clothesadvisor.controller.outfit

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
import java.util.concurrent.atomic.AtomicBoolean
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mockingDetails
import org.mockito.Mockito.verifyNoInteractions
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.data.domain.PageRequest
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
import ru.itmo.clothesadvisor.dto.wardrobe.CreateWardrobeItemRequest
import ru.itmo.clothesadvisor.model.user.AppUser
import ru.itmo.clothesadvisor.model.user.UserRole
import ru.itmo.clothesadvisor.model.user.UserStatus
import ru.itmo.clothesadvisor.repository.outfit.OutfitRepository
import ru.itmo.clothesadvisor.repository.wardrobe.WardrobeItemRepository
import ru.itmo.clothesadvisor.service.access.AccessGrantService
import ru.itmo.clothesadvisor.service.outfit.OutfitService
import ru.itmo.clothesadvisor.service.user.AppUserService
import ru.itmo.clothesadvisor.service.wardrobe.WardrobeItemService
import ru.itmo.clothesadvisor.storage.wardrobe.S3PhotoStorage

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestTimeConfiguration::class)
class OutfitIntegrationTests {
    @LocalServerPort private var port: Int = 0
    @Autowired private lateinit var users: AppUserService
    @Autowired private lateinit var passwords: PasswordEncoder
    @Autowired private lateinit var items: WardrobeItemService
    @Autowired private lateinit var outfits: OutfitService
    @Autowired private lateinit var access: AccessGrantService
    @Autowired private lateinit var jdbc: JdbcTemplate
    @Autowired private lateinit var transactionManager: PlatformTransactionManager
    @MockitoSpyBean private lateinit var wardrobe: WardrobeItemRepository
    @MockitoSpyBean private lateinit var outfitRepository: OutfitRepository
    @MockitoBean private lateinit var storage: S3PhotoStorage
    private val client = HttpClient.newHttpClient()

    @AfterEach
    fun closeClientAndCheckStorage() {
        client.close()
        verifyNoInteractions(storage)
    }

    @Test
    fun `owner creates immutable ordered outfit with exact decimal weather and deletes only outfit data`() {
        val owner = user()
        val token = login(owner)
        val ids = List(2) { item(owner) }.reversed()
        val name = "  Winter " + "x".repeat(291)
        val created = request("POST", token = token, body = body(ids, name).dropLast(1) +
            ",\"ownerId\":9223372036854775807,\"authorId\":9223372036854775807,\"source\":\"AI\"}")
        assertThat(created.statusCode()).isEqualTo(201)
        val original = dto(created)
        val id = number(original, "id")
        assertThat(created.headers().firstValue("Location")).hasValue("$OUTFITS/$id")
        assertThat(original.keys).containsExactlyInAnyOrder("id", "ownerId", "authorId", "source", "name", "itemIds", "weather", "createdAt", "likes", "dislikes")
        assertThat(number(original, "ownerId")).isEqualTo(owner.id)
        assertThat(number(original, "authorId")).isEqualTo(owner.id)
        assertThat(original["source"]).isEqualTo("USER")
        assertThat(original["name"]).isEqualTo(name)
        assertThat(original["createdAt"]).isEqualTo(TestTimeConfiguration.FIXED_TIME.toString())
        assertThat(number(original, "likes")).isZero()
        assertThat(number(original, "dislikes")).isZero()
        assertThat(created.body()).contains("\"temperatureC\":$TEMPERATURE", "\"windSpeedMps\":$WIND")
        assertThat((original["itemIds"] as List<*>).map { (it as Number).toLong() }).isEqualTo(ids)
        val expectedWeather = OutfitWeatherDto(BigDecimal(TEMPERATURE), 1, BigDecimal(WIND))
        assertThat(outfits.get(owner.id!!, id).weather).isEqualTo(expectedWeather)
        assertThat(jdbc.queryForObject("SELECT temperature_c FROM outfit_weather WHERE outfit_id = ?", BigDecimal::class.java, id))
            .isEqualByComparingTo(TEMPERATURE)
        assertThat(jdbc.queryForObject("SELECT wind_speed_mps FROM outfit_weather WHERE outfit_id = ?", BigDecimal::class.java, id))
            .isEqualByComparingTo(WIND)
        val read = request("GET", "$OUTFITS/$id", token)
        assertThat(dto(read)).isEqualTo(original)
        assertThat(read.body()).contains("\"temperatureC\":$TEMPERATURE", "\"windSpeedMps\":$WIND")
        assertThat(request("PUT", "$OUTFITS/$id", token, body(ids)).statusCode()).isEqualTo(405)
        assertThat(request("PATCH", "$OUTFITS/$id", token, "{}").statusCode()).isEqualTo(405)
        val listed = request("GET", token = token)
        assertThat(listed.headers().firstValue("X-Total-Count")).hasValue("1")
        assertThat(rows(listed)).containsExactly(original)

        assertThat(request("DELETE", "$OUTFITS/$id", token).statusCode()).isEqualTo(204)
        assertEmpty(request("GET", "$OUTFITS/$id", token), 404)
        assertEmpty(request("DELETE", "$OUTFITS/$id", token), 404)
        assertThat(count("outfit_item", "outfit_id", id)).isZero()
        assertThat(count("outfit_weather", "outfit_id", id)).isZero()
        ids.forEach { assertThat(items.get(owner.id!!, it).version).isEqualTo(1) }
    }

    @Test
    fun `stylist creates and reads authorized client outfits but cannot edit delete or choose authorship`() {
        val owner = user()
        val stylist = user(UserRole.STYLIST)
        access.grant(owner.id!!, stylist.id!!)
        val token = login(stylist)
        val path = stylistPath(owner)
        val item = item(owner)
        val created = request("POST", path, token, body(listOf(item)).dropLast(1) +
            ",\"ownerId\":${stylist.id},\"authorId\":${owner.id},\"source\":\"USER\"}")
        assertThat(created.statusCode()).isEqualTo(201)
        val original = dto(created)
        val id = number(original, "id")
        assertThat(created.headers().firstValue("Location")).hasValue("$path/$id")
        assertThat(number(original, "ownerId")).isEqualTo(owner.id)
        assertThat(number(original, "authorId")).isEqualTo(stylist.id)
        assertThat(original["source"]).isEqualTo("STYLIST")
        assertThat(original).containsEntry("myRating", null)
        assertThat(number(original, "likes")).isZero()
        assertThat(number(original, "dislikes")).isZero()
        assertThat(dto(request("GET", "$path/$id", token))).isEqualTo(original)
        assertThat(rows(request("GET", path, token))).containsExactly(original)
        assertThat(request("DELETE", "$path/$id", token).statusCode()).isEqualTo(405)
        assertThat(request("PUT", "$path/$id", token, body(listOf(item))).statusCode()).isEqualTo(405)
        val ownerToken = login(owner)
        assertThat(dto(request("GET", "$OUTFITS/$id", ownerToken))).isEqualTo(original - "myRating")
        users.changeRoleAndStatus(stylist.id!!, stylist.version, UserRole.USER, UserStatus.ACTIVE)
        assertThat(dto(request("GET", "$OUTFITS/$id", ownerToken))["source"]).isEqualTo("STYLIST")
        assertThat(request("GET", "$path/$id", token).statusCode()).isEqualTo(403)
        assertThat(request("DELETE", "$OUTFITS/$id", ownerToken).statusCode()).isEqualTo(204)
    }

    @Test
    fun `foreign missing and ungranted resources are indistinguishable and create nothing`() {
        val owner = user()
        val foreign = user()
        val stylist = user(UserRole.STYLIST)
        val ownerToken = login(owner)
        val foreignToken = login(foreign)
        val stylistToken = login(stylist)
        val ownItem = item(owner)
        val foreignItem = item(foreign)
        val id = number(dto(request("POST", token = ownerToken, body = body(listOf(ownItem)))), "id")
        for (target in listOf(id, Long.MAX_VALUE)) {
            assertEmpty(request("GET", "$OUTFITS/$target", foreignToken), 404)
            assertEmpty(request("DELETE", "$OUTFITS/$target", foreignToken), 404)
            assertEmpty(request("GET", "${stylistPath(owner)}/$target", stylistToken), 404)
        }
        for (ids in listOf(listOf(foreignItem), listOf(ownItem, foreignItem), listOf(Long.MAX_VALUE))) {
            assertEmpty(request("POST", token = ownerToken, body = body(ids)), 404)
        }
        for (path in listOf(stylistPath(owner), "/api/stylist/clients/${Long.MAX_VALUE}/outfits")) {
            assertEmpty(request("GET", path, stylistToken), 404)
            assertEmpty(request("POST", path, stylistToken, body(listOf(ownItem))), 404)
        }
        access.grant(foreign.id!!, stylist.id!!)
        assertEmpty(request("GET", "${stylistPath(foreign)}/$id", stylistToken), 404)
        assertThat(rows(request("GET", token = foreignToken))).isEmpty()
        assertThat(count("outfit", "owner_id", owner.id!!)).isEqualTo(1)
        assertThat(count("outfit", "owner_id", foreign.id!!)).isZero()
    }

    @Test
    fun `every stylist route rechecks grants and both client and stylist current state`() {
        val owner = user()
        val stylist = user(UserRole.STYLIST)
        var currentOwner = owner
        var currentStylist = stylist
        val token = login(stylist)
        val ids = listOf(item(owner))
        access.grant(owner.id!!, stylist.id!!)
        val path = stylistPath(owner)
        val id = number(dto(request("POST", path, token, body(ids))), "id")
        fun denied(status: Int) {
            for (response in listOf(request("GET", path, token), request("GET", "$path/$id", token),
                request("POST", path, token, body(ids)))) {
                assertThat(response.statusCode()).isEqualTo(status)
                if (status == 404) assertThat(response.body()).isEmpty()
            }
            assertThat(count("outfit", "owner_id", owner.id!!)).isEqualTo(1)
        }
        access.revoke(owner.id!!, stylist.id!!)
        denied(404)
        access.grant(owner.id!!, stylist.id!!)
        currentOwner = users.changeRoleAndStatus(owner.id!!, currentOwner.version, UserRole.USER, UserStatus.BLOCKED)
        denied(404)
        currentOwner = users.changeRoleAndStatus(owner.id!!, currentOwner.version, UserRole.STYLIST, UserStatus.ACTIVE)
        denied(404)
        users.changeRoleAndStatus(owner.id!!, currentOwner.version, UserRole.USER, UserStatus.ACTIVE)
        assertThat(request("GET", path, token).statusCode()).isEqualTo(200)
        currentStylist = users.changeRoleAndStatus(stylist.id!!, currentStylist.version, UserRole.STYLIST, UserStatus.BLOCKED)
        denied(401)
        users.changeRoleAndStatus(stylist.id!!, currentStylist.version, UserRole.USER, UserStatus.ACTIVE)
        denied(403)
    }

    @Test
    fun `outfit owner endpoints require a currently active USER`() {
        val owner = user()
        val token = login(owner)
        val ids = listOf(item(owner))
        val id = number(dto(request("POST", token = token, body = body(ids))), "id")
        val stylist = login(user(UserRole.STYLIST))
        val admin = login(user(UserRole.ADMIN))
        var current = users.changeRoleAndStatus(owner.id!!, owner.version, owner.role, UserStatus.BLOCKED)
        for ((credential, status) in listOf(null to 401, token to 401, stylist to 403, admin to 403)) {
            for ((method, path) in listOf("GET" to OUTFITS, "POST" to OUTFITS,
                "GET" to "$OUTFITS/$id", "DELETE" to "$OUTFITS/$id")) {
                assertThat(request(method, path, credential, if (method == "POST") body(ids) else null).statusCode()).isEqualTo(status)
            }
        }
        current = users.changeRoleAndStatus(owner.id!!, current.version, UserRole.STYLIST, UserStatus.ACTIVE)
        assertThat(request("GET", token = token).statusCode()).isEqualTo(403)
        assertThat(count("outfit", "owner_id", current.id!!)).isEqualTo(1)
    }

    @Test
    fun `invalid composition name and weather never write any part of an outfit`() {
        val owner = user()
        val token = login(owner)
        val item = item(owner)
        val fiftyOne = List(51) { item(owner) }
        val tables = listOf("outfit", "outfit_item", "outfit_weather")
        val before = tables.map { jdbc.queryForObject("SELECT count(*) FROM $it", Long::class.java) }
        val valid = body(listOf(item))
        val invalid = listOf("{", "{}", body(emptyList()), body(fiftyOne), body(listOf(item, item)),
            body(listOf(0)), body(listOf(-1)), body(listOf(item), " "), body(listOf(item), "x".repeat(301)),
            valid.replace("[$item]", "[null]"), valid.replace("[$item]", "null"),
            valid.replace("[$item]", "[9223372036854775808]"), valid.replace("[$item]", "[\"bad\"]"),
            valid.replace("\"Daily\"", "null"), valid.replace(TEMPERATURE, "null"),
            valid.replace(TEMPERATURE, "\"cold\""), valid.replace(WIND, "-0.001"), valid.replace(WIND, "null"),
            valid.replace("\"precipitationTypeId\":1", "\"precipitationTypeId\":null"),
            valid.replace("\"precipitationTypeId\":1", "\"precipitationTypeId\":${Long.MAX_VALUE}"),
            """{"name":"Daily","itemIds":[$item],"weather":null}""")
        for (body in invalid) {
            assertThat(request("POST", token = token, body = body).statusCode()).describedAs(body).isEqualTo(400)
        }
        assertThat(count("outfit", "owner_id", owner.id!!)).isZero()
        assertThat(tables.map { jdbc.queryForObject("SELECT count(*) FROM $it", Long::class.java) }).isEqualTo(before)
        assertThat(request("POST", token = token, body = body(fiftyOne.take(50)).replace(WIND, "0")).statusCode()).isEqualTo(201)
    }

    @Test
    fun `lists page in descending ID order with owner scoped totals for both callers`() {
        val owner = user()
        val stylist = user(UserRole.STYLIST)
        val ownerToken = login(owner)
        val stylistToken = login(stylist)
        access.grant(owner.id!!, stylist.id!!)
        val input = input(listOf(item(owner)))
        val expected = List(53) { outfits.create(owner.id!!, input).id }.reversed()
        val foreign = user()
        outfits.create(foreign.id!!, input(listOf(item(foreign))))
        for ((path, token) in listOf(OUTFITS to ownerToken, stylistPath(owner) to stylistToken)) {
            val first = request("GET", path, token)
            val second = request("GET", "$path?page=1&size=50", token)
            assertThat(first.headers().firstValue("X-Total-Count")).hasValue("53")
            assertThat(second.headers().firstValue("X-Total-Count")).hasValue("53")
            assertThat(rows(first)).hasSize(50)
            assertThat(rows(second)).hasSize(3)
            assertThat((rows(first) + rows(second)).map { number(it, "id") }).isEqualTo(expected)
            val one = request("GET", "$path?page=1&size=1", token)
            assertThat(rows(one).map { number(it, "id") }).containsExactly(expected[1])
            val empty = request("GET", "$path?page=${Int.MAX_VALUE}&size=1", token)
            assertThat(rows(empty)).isEmpty()
            assertThat(empty.headers().firstValue("X-Total-Count")).hasValue("53")
            for (query in listOf("page=-1", "size=0", "size=51", "page=abc", "size=1.5",
                "page=2147483648", "page=${Int.MAX_VALUE}&size=2")) {
                assertThat(request("GET", "$path?$query", token).statusCode()).isEqualTo(400)
            }
        }
    }

    @Test
    fun `referenced item delete conflicts and rolls back photo metadata and history until outfit deleted`() {
        val owner = user()
        val token = login(owner)
        val item = item(owner)
        jdbc.update("""INSERT INTO wardrobe_item_photo (wardrobe_item_id, s3_key, content_type, size_bytes, created_at)
            VALUES (?, ?, 'image/png', 1, ?)""", item, UUID.randomUUID().toString(), java.sql.Timestamp.from(TestTimeConfiguration.FIXED_TIME))
        val outfit = outfits.create(owner.id!!, input(listOf(item)))
        assertEmpty(request("DELETE", "/api/wardrobe/items/$item?version=1", token), 409)
        assertThat(items.get(owner.id!!, item).version).isEqualTo(1)
        assertThat(count("wardrobe_item_photo", "wardrobe_item_id", item)).isEqualTo(1)
        assertThat(count("wardrobe_item_history", "wardrobe_item_id", item)).isZero()
        assertThat(request("DELETE", "$OUTFITS/${outfit.id}", token).statusCode()).isEqualTo(204)
        assertThat(request("DELETE", "/api/wardrobe/items/$item?version=1", token).statusCode()).isEqualTo(204)
        assertThat(count("wardrobe_item_photo", "wardrobe_item_id", item)).isZero()
        assertThat(count("wardrobe_item_history", "wardrobe_item_id", item)).isEqualTo(1)
    }

    @Test
    fun `outer rollback removes flushed outfit composition and weather together`() {
        val owner = user()
        val ids = List(2) { item(owner) }
        var id = 0L
        assertThatThrownBy {
            TransactionTemplate(transactionManager).executeWithoutResult {
                id = outfits.create(owner.id!!, input(ids)).id
                assertThat(count("outfit_item", "outfit_id", id)).isEqualTo(2)
                assertThat(count("outfit_weather", "outfit_id", id)).isEqualTo(1)
                error("forced rollback")
            }
        }.isInstanceOf(IllegalStateException::class.java).hasMessage("forced rollback")
        assertThat(count("outfit", "id", id)).isZero()
        assertThat(count("outfit_item", "outfit_id", id)).isZero()
        assertThat(count("outfit_weather", "outfit_id", id)).isZero()
        ids.forEach { assertThat(items.get(owner.id!!, it).version).isEqualTo(1) }
    }

    @Test
    fun `concurrent outfit deletes serialize with one not found and preserve wardrobe items`() {
        val owner = user()
        val token = login(owner)
        val ids = List(2) { item(owner) }
        val outfit = outfits.create(owner.id!!, input(ids))
        val loaded = CountDownLatch(1)
        val release = CountDownLatch(1)
        val secondLock = CountDownLatch(1)
        val firstRead = AtomicBoolean(true)
        doAnswer { call ->
            val first = firstRead.compareAndSet(true, false)
            if (!first) secondLock.countDown()
            val entity = mockingDetails(outfitRepository).mockCreationSettings.defaultAnswer.answer(call)
            if (first) {
                assertThat(entity).isNotNull()
                loaded.countDown()
                assertThat(release.await(10, TimeUnit.SECONDS)).isTrue()
            }
            entity
        }.`when`(outfitRepository).findLockedByIdAndOwnerId(outfit.id, owner.id!!)
        Executors.newFixedThreadPool(2).use { executor ->
            val firstDelete = executor.submit(Callable { request("DELETE", "$OUTFITS/${outfit.id}", token) })
            try {
                assertThat(loaded.await(10, TimeUnit.SECONDS)).isTrue()
                val secondDelete = executor.submit(Callable { request("DELETE", "$OUTFITS/${outfit.id}", token) })
                assertThat(secondLock.await(10, TimeUnit.SECONDS)).isTrue()
                release.countDown()
                assertEmpty(firstDelete.get(30, TimeUnit.SECONDS), 204)
                assertEmpty(secondDelete.get(30, TimeUnit.SECONDS), 404)
            } finally { release.countDown() }
        }
        assertThat(count("outfit", "id", outfit.id)).isZero()
        assertThat(count("outfit_item", "outfit_id", outfit.id)).isZero()
        assertThat(count("outfit_weather", "outfit_id", outfit.id)).isZero()
        ids.forEach { assertThat(items.get(owner.id!!, it).version).isEqualTo(1) }
        assertEmpty(request("DELETE", "$OUTFITS/${outfit.id}", token), 404)
    }

    @Test
    fun `outfit committed after deleter loaded item prevents stale delete`() {
        val owner = user()
        val item = item(owner)
        val loaded = CountDownLatch(1)
        val created = CountDownLatch(1)
        Executors.newSingleThreadExecutor().use { executor ->
            val deletion = executor.submit(Callable {
                try {
                    TransactionTemplate(transactionManager).apply { timeout = 20 }.executeWithoutResult {
                        assertThat(wardrobe.findByIdAndOwnerId(item, owner.id!!)!!.version).isEqualTo(1)
                        loaded.countDown()
                        assertThat(created.await(10, TimeUnit.SECONDS)).isTrue()
                        items.delete(owner.id!!, item, 1)
                    }
                    null
                } catch (failure: Exception) { failure }
            })
            try {
                assertThat(loaded.await(10, TimeUnit.SECONDS)).isTrue()
                outfits.create(owner.id!!, input(listOf(item)))
            } finally { created.countDown() }
            assertThat(deletion.get(30, TimeUnit.SECONDS)).isInstanceOf(DataIntegrityViolationException::class.java)
        }
        assertThat(items.get(owner.id!!, item).version).isEqualTo(1)
        assertThat(count("wardrobe_item_history", "wardrobe_item_id", item)).isZero()
        assertThat(outfits.list(owner.id!!, PageRequest.of(0, 50)).totalElements).isEqualTo(1)
    }

    @Test
    fun `delete committed after ownership validation makes creation conflict and roll back all parts`() {
        val owner = user()
        val token = login(owner)
        val item = item(owner)
        val validated = CountDownLatch(1)
        val deleted = CountDownLatch(1)
        doAnswer { call ->
            val count = mockingDetails(wardrobe).mockCreationSettings.defaultAnswer.answer(call)
            validated.countDown()
            assertThat(deleted.await(10, TimeUnit.SECONDS)).isTrue()
            count
        }.`when`(wardrobe).countByOwnerIdAndIdIn(owner.id!!, listOf(item))
        Executors.newSingleThreadExecutor().use { executor ->
            val creation = executor.submit(Callable { request("POST", token = token, body = body(listOf(item))) })
            try {
                assertThat(validated.await(10, TimeUnit.SECONDS)).isTrue()
                items.delete(owner.id!!, item, 1)
            } finally { deleted.countDown() }
            assertEmpty(creation.get(30, TimeUnit.SECONDS), 409)
        }
        assertThat(count("outfit", "owner_id", owner.id!!)).isZero()
        assertThat(count("outfit_item", "wardrobe_item_id", item)).isZero()
        assertThat(count("wardrobe_item_history", "wardrobe_item_id", item)).isEqualTo(1)
    }

    @Test
    fun `list retains a complete snapshot when outfit is deleted before component reads`() {
        val owner = user()
        val token = login(owner)
        val original = outfits.create(owner.id!!, input(listOf(item(owner))))
        val loaded = CountDownLatch(1)
        val deleted = CountDownLatch(1)
        doAnswer { call ->
            val page = mockingDetails(outfitRepository).mockCreationSettings.defaultAnswer.answer(call)
            loaded.countDown()
            assertThat(deleted.await(10, TimeUnit.SECONDS)).isTrue()
            page
        }.`when`(outfitRepository).findAllByOwnerIdOrderByIdDesc(owner.id!!, PageRequest.of(0, 50))
        Executors.newSingleThreadExecutor().use { executor ->
            val reading = executor.submit(Callable { request("GET", token = token) })
            try {
                assertThat(loaded.await(10, TimeUnit.SECONDS)).isTrue()
                outfits.delete(owner.id!!, original.id)
            } finally { deleted.countDown() }
            val response = reading.get(30, TimeUnit.SECONDS)
            assertThat(response.headers().firstValue("X-Total-Count")).hasValue("1")
            val row = rows(response).single()
            assertThat(number(row, "id")).isEqualTo(original.id)
            assertThat(row["itemIds"] as List<*>).hasSize(1)
            assertThat(response.body()).contains("\"temperatureC\":$TEMPERATURE", "\"windSpeedMps\":$WIND")
        }
        assertEmpty(request("GET", "$OUTFITS/${original.id}", token), 404)
    }

    private fun user(role: UserRole = UserRole.USER): AppUser =
        users.create(UUID.randomUUID().toString(), passwords.encode(PASSWORD)!!, role)

    private fun item(owner: AppUser): Long =
        items.create(owner.id!!, CreateWardrobeItemRequest("Shirt", 1, "White", "Cotton")).id

    private fun login(user: AppUser): String {
        val response = request("POST", "/api/auth/login", body = """{"login":"${user.login}","password":"$PASSWORD"}""")
        assertThat(response.statusCode()).isEqualTo(200)
        return JsonPath.read(response.body(), "$.accessToken")
    }

    private fun stylistPath(owner: AppUser) = "/api/stylist/clients/${owner.id}/outfits"

    private fun input(ids: List<Long>) = CreateOutfitRequest("Daily", ids,
        OutfitWeatherDto(BigDecimal(TEMPERATURE), 1, BigDecimal(WIND)))

    private fun body(ids: List<Long>, name: String = "Daily") =
        """{"name":"$name","itemIds":[${ids.joinToString(",")}],"weather":{"temperatureC":$TEMPERATURE,"precipitationTypeId":1,"windSpeedMps":$WIND}}"""

    private fun request(method: String, path: String = OUTFITS, token: String? = null, body: String? = null): HttpResponse<String> {
        val request = HttpRequest.newBuilder(URI("http://localhost:$port$path"))
            .method(method, body?.let(HttpRequest.BodyPublishers::ofString) ?: HttpRequest.BodyPublishers.noBody())
        if (body != null) request.header("Content-Type", "application/json")
        if (token != null) request.header("Authorization", "Bearer $token")
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun dto(response: HttpResponse<String>): Map<String, Any> = JsonPath.read(response.body(), "$")
    private fun rows(response: HttpResponse<String>): List<Map<String, Any>> {
        assertThat(response.statusCode()).isEqualTo(200)
        return JsonPath.read(response.body(), "$")
    }
    private fun number(row: Map<String, Any>, key: String): Long = (row.getValue(key) as Number).toLong()
    private fun count(table: String, column: String, id: Long): Long =
        jdbc.queryForObject("SELECT count(*) FROM $table WHERE $column = ?", Long::class.java, id)!!
    private fun assertEmpty(response: HttpResponse<String>, status: Int) {
        assertThat(response.statusCode()).isEqualTo(status)
        assertThat(response.body()).isEmpty()
    }

    companion object {
        private const val OUTFITS = "/api/outfits"
        private const val PASSWORD = "outfit-password"
        private const val TEMPERATURE = "-123.123456789123456789"
        private const val WIND = "1.123456789123456789"
        @Container @JvmStatic val postgres = PostgreSQLContainer("postgres:18-alpine")
        @JvmStatic @DynamicPropertySource
        fun postgresProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
        }
    }
}
