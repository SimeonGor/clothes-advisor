package ru.itmo.clothesadvisor.controller.access

import com.jayway.jsonpath.JsonPath
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.sql.Timestamp
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.clearInvocations
import org.mockito.Mockito.doAnswer
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
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import ru.itmo.clothesadvisor.config.TestTimeConfiguration
import ru.itmo.clothesadvisor.model.user.AppUser
import ru.itmo.clothesadvisor.model.user.UserRole
import ru.itmo.clothesadvisor.model.user.UserStatus
import ru.itmo.clothesadvisor.service.user.AppUserService
import ru.itmo.clothesadvisor.storage.wardrobe.PhotoStorageUnavailableException
import ru.itmo.clothesadvisor.storage.wardrobe.S3PhotoStorage

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestTimeConfiguration::class)
@Execution(ExecutionMode.SAME_THREAD)
class StylistAccessIntegrationTests {
    @LocalServerPort private var port: Int = 0
    @Autowired private lateinit var users: AppUserService
    @Autowired private lateinit var passwords: PasswordEncoder
    @Autowired private lateinit var jdbc: JdbcTemplate
    @MockitoBean private lateinit var storage: S3PhotoStorage
    private val client = HttpClient.newHttpClient()
    private val passwordHash by lazy { requireNotNull(passwords.encode(PASSWORD)) }
    private val photoBytes = byteArrayOf(0, 1, 2, -1, 13, 10)

    @BeforeEach
    fun storageOutsideTransactions() {
        doAnswer {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse()
            photoBytes
        }.`when`(storage).get(anyString(), anyLong())
    }

    @AfterEach fun closeClient() = client.close()

    @Test
    fun `grants and revokes are idempotent isolated and use only the JWT owner`() {
        val owner = user()
        val other = user()
        val stylist = user(UserRole.STYLIST)
        val token = login(owner)
        val otherToken = login(other)
        val stylistToken = login(stylist)
        val path = "$ACCESS/${stylist.id}"
        assertThat(rows(request("GET", ACCESS, token))).isEmpty()
        assertThat(rows(request("GET", CLIENTS, stylistToken))).isEmpty()
        repeat(2) {
            val granted = request("PUT", "$path?ownerId=${other.id}", token, """{"ownerId":${other.id}}""")
            assertThat(granted.statusCode()).isEqualTo(204)
            assertThat(granted.body()).isEmpty()
        }
        assertThat(grants(owner, stylist)).isEqualTo(1)
        assertThat(grants(other, stylist)).isZero()
        assertThat(rows(request("GET", ACCESS, token))).containsExactly(identity(stylist))
        assertThat(rows(request("GET", "$ACCESS?ownerId=${owner.id}", otherToken))).isEmpty()
        assertThat(rows(request("GET", CLIENTS, stylistToken))).containsExactly(identity(owner))
        assertThat(request("DELETE", "$path?ownerId=${owner.id}", otherToken).statusCode()).isEqualTo(204)
        assertThat(grants(owner, stylist)).isEqualTo(1)
        repeat(2) {
            val revoked = request("DELETE", path, token)
            assertThat(revoked.statusCode()).isEqualTo(204)
            assertThat(revoked.body()).isEmpty()
        }
        assertThat(grants(owner, stylist)).isZero()
        assertThat(request("DELETE", "$ACCESS/${Long.MAX_VALUE}", token).statusCode()).isEqualTo(204)
        assertThat(rows(request("GET", ACCESS, token))).isEmpty()
        assertThat(rows(request("GET", CLIENTS, stylistToken))).isEmpty()
    }

    @Test
    fun `concurrent PUTs succeed and leave exactly one pair`() {
        val owner = user()
        val stylist = user(UserRole.STYLIST)
        val token = login(owner)
        val ready = CyclicBarrier(4)
        Executors.newFixedThreadPool(4).use { executor ->
            val attempts = List(4) {
                executor.submit(Callable {
                    ready.await(10, TimeUnit.SECONDS)
                    request("PUT", "$ACCESS/${stylist.id}", token).statusCode()
                })
            }
            assertThat(attempts.map { it.get(20, TimeUnit.SECONDS) }).containsOnly(204)
        }
        assertThat(grants(owner, stylist)).isEqualTo(1)
    }

    @Test
    fun `PUT requires an active stylist even on repeat while DELETE accepts suspended targets`() {
        val owner = user()
        val token = login(owner)
        val stylist = user(UserRole.STYLIST)
        val invalid = listOf(owner, user(), user(UserRole.ADMIN), user(UserRole.STYLIST, UserStatus.BLOCKED))
        for (id in invalid.map { it.id } + Long.MAX_VALUE) {
            notFound(request("PUT", "$ACCESS/$id", token))
        }
        for ((role, status) in listOf(UserRole.STYLIST to UserStatus.BLOCKED, UserRole.USER to UserStatus.ACTIVE)) {
            change(stylist, UserRole.STYLIST, UserStatus.ACTIVE)
            assertThat(request("PUT", "$ACCESS/${stylist.id}", token).statusCode()).isEqualTo(204)
            change(stylist, role, status)
            notFound(request("PUT", "$ACCESS/${stylist.id}", token))
            assertThat(rows(request("GET", ACCESS, token))).containsExactly(identity(stylist))
            assertThat(grants(owner, stylist)).isEqualTo(1)
            assertThat(request("DELETE", "$ACCESS/${stylist.id}", token).statusCode()).isEqualTo(204)
            assertThat(grants(owner, stylist)).isZero()
        }
    }

    @Test
    fun `every access and wardrobe route enforces current authentication and role`() {
        val owner = user()
        val stylist = user(UserRole.STYLIST)
        val ownerToken = login(owner)
        val stylistToken = login(stylist)
        val adminToken = login(user(UserRole.ADMIN))
        val blocked = user()
        val blockedToken = login(blocked)
        change(blocked, UserRole.USER, UserStatus.BLOCKED)
        val item = create(ownerToken)
        val photo = photo(item)
        for ((token, status) in listOf(null to 401, "invalid" to 401, blockedToken to 401,
            stylistToken to 403, adminToken to 403)) {
            assertThat(request("GET", ACCESS, token).statusCode()).isEqualTo(status)
            assertThat(request("PUT", "$ACCESS/${stylist.id}", token).statusCode()).isEqualTo(status)
            assertThat(request("DELETE", "$ACCESS/${stylist.id}", token).statusCode()).isEqualTo(status)
        }
        grant(ownerToken, stylist)
        change(stylist, UserRole.STYLIST, UserStatus.BLOCKED)
        for ((token, status) in listOf(null to 401, "invalid" to 401, stylistToken to 401,
            ownerToken to 403, adminToken to 403)) {
            for (path in listOf(CLIENTS) + readPaths(owner.id!!, item, photo)) {
                assertThat(request("GET", path, token).statusCode()).describedAs(path).isEqualTo(status)
            }
        }
        change(stylist, UserRole.USER, UserStatus.ACTIVE)
        assertThat(request("GET", CLIENTS, stylistToken).statusCode()).isEqualTo(403)
        change(owner, UserRole.STYLIST, UserStatus.ACTIVE)
        assertThat(request("GET", ACCESS, ownerToken).statusCode()).isEqualTo(403)
        verifyNoInteractions(storage)
    }

    @Test
    fun `permission and client lists have exact DTOs ordered bounded pages and no totals`() {
        val owner = user()
        val stylist = user(UserRole.STYLIST)
        val stylists = List(55) { user(UserRole.STYLIST) }
        val owners = List(55) { user() }
        stylists.reversed().forEach { seedGrant(owner, it) }
        owners.reversed().forEach { seedGrant(it, stylist) }
        val ownerToken = login(owner)
        val stylistToken = login(stylist)
        for ((path, token, expected) in listOf(
            Triple(ACCESS, ownerToken, stylists.map(::identity)),
            Triple(CLIENTS, stylistToken, owners.map(::identity)),
        )) {
            val response = request("GET", path, token)
            val first = rows(response)
            val second = rows(request("GET", "$path?page=1&size=50", token))
            assertThat(first).hasSize(50)
            assertThat(second).hasSize(5)
            assertThat(first + second).isEqualTo(expected)
            first.forEach { assertThat(it.keys).containsExactlyInAnyOrder("id", "login") }
            assertThat(response.headers().allValues("X-Total-Count")).isEmpty()
            assertThat(rows(request("GET", "$path?page=1&size=1", token))).containsExactly(expected[1])
            assertThat(rows(request("GET", "$path?page=2&size=50", token))).isEmpty()
            assertThat(rows(request("GET", "$path?page=${Int.MAX_VALUE}&size=1", token))).isEmpty()
            for (query in INVALID_PAGES) {
                assertThat(request("GET", "$path?$query", token).statusCode()).describedAs(query).isEqualTo(400)
            }
        }
        change(stylists[0], UserRole.STYLIST, UserStatus.BLOCKED)
        change(stylists[1], UserRole.ADMIN, UserStatus.ACTIVE)
        change(owners[0], UserRole.USER, UserStatus.BLOCKED)
        change(owners[1], UserRole.STYLIST, UserStatus.ACTIVE)
        assertThat(rows(request("GET", ACCESS, ownerToken))).isEqualTo(stylists.take(50).map(::identity))
        assertThat(rows(request("GET", CLIENTS, stylistToken))).isEqualTo(owners.drop(2).take(50).map(::identity))
        assertThat(rows(request("GET", CLIENTS, login(user(UserRole.STYLIST))))).isEmpty()
    }

    @ParameterizedTest
    @ValueSource(strings = ["owner-block", "owner-role", "stylist-block", "stylist-role"])
    fun `grant survives account changes and access resumes after restoration`(change: String) {
        val owner = user()
        val stylist = user(UserRole.STYLIST)
        val ownerToken = login(owner)
        val stylistToken = login(stylist)
        val item = create(ownerToken)
        val photo = photo(item)
        grant(ownerToken, stylist)
        val affected = if (change.startsWith("owner")) owner else stylist
        val originalRole = affected.role
        change(affected, if (change.endsWith("role")) UserRole.ADMIN else originalRole,
            if (change.endsWith("block")) UserStatus.BLOCKED else UserStatus.ACTIVE)
        val expected = if (affected === owner) 404 else if (change.endsWith("block")) 401 else 403
        readPaths(owner.id!!, item, photo).forEach {
            val response = request("GET", it, stylistToken)
            assertThat(response.statusCode()).isEqualTo(expected)
            if (expected == 404) assertThat(response.body()).isEmpty()
        }
        if (affected === owner) assertThat(rows(request("GET", CLIENTS, stylistToken))).isEmpty()
        else assertThat(rows(request("GET", ACCESS, ownerToken))).containsExactly(identity(stylist))
        verifyNoInteractions(storage)
        assertThat(grants(owner, stylist)).isEqualTo(1)
        change(affected, originalRole, UserStatus.ACTIVE)
        assertThat(rows(request("GET", ACCESS, ownerToken))).containsExactly(identity(stylist))
        assertThat(rows(request("GET", CLIENTS, stylistToken))).containsExactly(identity(owner))
        readPaths(owner.id!!, item, photo).forEach { assertThat(request("GET", it, stylistToken).statusCode()).isEqualTo(200) }
    }

    @Test
    fun `stylist reads the same private wardrobe and photo DTOs and bytes without modifying history`() {
        val owner = user()
        val stylist = user(UserRole.STYLIST)
        val ownerToken = login(owner)
        val stylistToken = login(stylist)
        val item = create(ownerToken)
        val photo = photo(item)
        grant(ownerToken, stylist)
        val base = wardrobe(owner.id!!)
        for (suffix in listOf("", "/$item", "/$item/photos")) {
            val own = request("GET", "$ITEMS$suffix", ownerToken)
            val shared = request("GET", "$base$suffix", stylistToken)
            assertThat(shared.statusCode()).isEqualTo(200)
            assertThat(shared.body()).isEqualTo(own.body())
        }
        val content = request("GET", "$base/$item/photos/$photo/content", stylistToken)
        assertThat(content.statusCode()).isEqualTo(200)
        assertThat(content.body()).isEqualTo(photoBytes)
        assertThat(content.headers().firstValue("Content-Type")).hasValue("image/png")
        assertThat(content.headers().firstValue("Cache-Control")).hasValue("no-store")
        assertThat(content.headers().firstValue("X-Content-Type-Options")).hasValue("nosniff")
        assertThat(jdbc.queryForObject("SELECT version FROM wardrobe_item WHERE id = ?", Long::class.java, item)).isEqualTo(1)
        assertThat(jdbc.queryForObject("SELECT count(*) FROM wardrobe_item_history WHERE wardrobe_item_id = ?",
            Long::class.java, item)).isZero()
        assertThat(jdbc.queryForObject("SELECT count(*) FROM app_user_history WHERE user_id IN (?, ?)",
            Long::class.java, owner.id, stylist.id)).isZero()
        doAnswer { throw PhotoStorageUnavailableException() }.`when`(storage).get(anyString(), anyLong())
        val unavailable = request("GET", "$base/$item/photos/$photo/content", stylistToken)
        assertThat(unavailable.statusCode()).isEqualTo(503)
        assertThat(unavailable.body()).isEmpty()
    }

    @Test
    fun `shared wardrobe list retains pagination bounds and owner filtering`() {
        val owner = user()
        val stylist = user(UserRole.STYLIST)
        val token = login(stylist)
        grant(login(owner), stylist)
        val now = Timestamp.from(TestTimeConfiguration.FIXED_TIME)
        jdbc.update("""
            INSERT INTO wardrobe_item (owner_id, category_id, name, color, material, version, created_at, modified_at)
            SELECT ?, 1, 'Shirt ' || n, 'White', 'Cotton', 1, ?, ? FROM generate_series(1, 55) n
        """.trimIndent(), owner.id, now, now)
        create(login(user()))
        val path = wardrobe(owner.id!!)
        val firstResponse = request("GET", path, token)
        val first = rows(firstResponse)
        val second = rows(request("GET", "$path?page=1&size=50", token))
        assertThat(first).hasSize(50)
        assertThat(second).hasSize(5)
        val expected = jdbc.queryForList("SELECT id FROM wardrobe_item WHERE owner_id = ? ORDER BY id", Long::class.java, owner.id)
        assertThat((first + second).map { number(it, "id") }).isEqualTo(expected)
        assertThat(firstResponse.headers().allValues("X-Total-Count")).isEmpty()
        assertThat(rows(request("GET", "$path?page=1&size=1", token))).containsExactly(first[1])
        assertThat(rows(request("GET", "$path?page=2&size=50", token))).isEmpty()
        assertThat(rows(request("GET", "$path?page=${Int.MAX_VALUE}&size=1", token))).isEmpty()
        for (query in INVALID_PAGES) assertThat(request("GET", "$path?$query", token).statusCode()).isEqualTo(400)
    }

    @Test
    fun `missing access and mismatched owner item or photo return empty 404 without S3`() {
        val owner = user()
        val other = user()
        val stylist = user(UserRole.STYLIST)
        val token = login(stylist)
        val ownerToken = login(owner)
        val item = create(ownerToken)
        val second = create(ownerToken)
        val otherItem = create(login(other))
        val photo = photo(item)
        val otherPhoto = photo(otherItem)
        for (ownerId in listOf(owner.id!!, other.id!!, Long.MAX_VALUE)) {
            readPaths(ownerId, item, photo).forEach { notFound(request("GET", it, token)) }
        }
        grant(ownerToken, stylist)
        for (itemId in listOf(otherItem, Long.MAX_VALUE)) {
            readPaths(owner.id!!, itemId, photo).drop(1).forEach { notFound(request("GET", it, token)) }
        }
        for ((itemId, photoId) in listOf(second to photo, item to otherPhoto, item to Long.MAX_VALUE)) {
            notFound(request("GET", "${wardrobe(owner.id!!)}/$itemId/photos/$photoId/content", token))
        }
        readPaths(other.id!!, item, photo).forEach { notFound(request("GET", it, token)) }
        assertThat(request("DELETE", "$ACCESS/${stylist.id}", ownerToken).statusCode()).isEqualTo(204)
        readPaths(owner.id!!, item, photo).forEach { notFound(request("GET", it, token)) }
        verifyNoInteractions(storage)
    }

    @Test
    fun `stylist has no accepted mutation routes and cannot write through personal API`() {
        val owner = user()
        val stylist = user(UserRole.STYLIST)
        val ownerToken = login(owner)
        val token = login(stylist)
        val item = create(ownerToken)
        val photo = photo(item)
        grant(ownerToken, stylist)
        val base = wardrobe(owner.id!!)
        for ((method, suffix) in listOf("POST" to "", "PUT" to "/$item", "DELETE" to "/$item?version=1",
            "POST" to "/$item/photos", "DELETE" to "/$item/photos/$photo")) {
            val upload = method == "POST" && suffix.endsWith("/photos")
            val body = when {
                upload -> "--photo-boundary\r\nContent-Disposition: form-data; name=\"photos\"; filename=\"photo.png\"\r\n" +
                    "Content-Type: image/png\r\n\r\nbytes\r\n--photo-boundary--\r\n"
                method == "DELETE" -> null
                else -> ITEM.dropLast(1) + ",\"version\":1}"
            }
            val contentType = if (upload) "multipart/form-data; boundary=photo-boundary" else "application/json"
            assertThat(request(method, "$base$suffix", token, body, contentType).statusCode()).isIn(404, 405)
            assertThat(request(method, "$ITEMS$suffix", token, body, contentType).statusCode()).isEqualTo(403)
        }
        assertThat(request("GET", "$ITEMS/$item", ownerToken).statusCode()).isEqualTo(200)
        assertThat(rows(request("GET", "$ITEMS/$item/photos", ownerToken))).hasSize(1)
        verifyNoInteractions(storage)
    }

    @ParameterizedTest
    @ValueSource(strings = ["revoke", "owner-block", "owner-role", "stylist-block", "stylist-role", "photo-delete", "item-delete"])
    fun `changes committed during S3 download prevent bytes leaving the service`(mutation: String) {
        val owner = user()
        val stylist = user(UserRole.STYLIST)
        val ownerToken = login(owner)
        val stylistToken = login(stylist)
        val item = create(ownerToken)
        val photo = photo(item)
        grant(ownerToken, stylist)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        doAnswer {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse()
            entered.countDown()
            assertThat(release.await(10, TimeUnit.SECONDS)).isTrue()
            photoBytes
        }.`when`(storage).get(anyString(), anyLong())
        Executors.newSingleThreadExecutor().use { executor ->
            val download = executor.submit(Callable {
                request("GET", "${wardrobe(owner.id!!)}/$item/photos/$photo/content", stylistToken)
            })
            try {
                assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue()
                when (mutation) {
                    "revoke" -> assertThat(request("DELETE", "$ACCESS/${stylist.id}", ownerToken).statusCode()).isEqualTo(204)
                    "owner-block" -> change(owner, UserRole.USER, UserStatus.BLOCKED)
                    "owner-role" -> change(owner, UserRole.ADMIN, UserStatus.ACTIVE)
                    "stylist-block" -> change(stylist, UserRole.STYLIST, UserStatus.BLOCKED)
                    "stylist-role" -> change(stylist, UserRole.ADMIN, UserStatus.ACTIVE)
                    "photo-delete" -> assertThat(request("DELETE", "$ITEMS/$item/photos/$photo", ownerToken).statusCode()).isEqualTo(204)
                    "item-delete" -> assertThat(request("DELETE", "$ITEMS/$item?version=1", ownerToken).statusCode()).isEqualTo(204)
                }
            } finally { release.countDown() }
            notFound(download.get(20, TimeUnit.SECONDS))
        }
        assertThat(grants(owner, stylist)).isEqualTo(if (mutation == "revoke") 0 else 1)
        clearInvocations(storage)
        if (mutation == "photo-delete" || mutation == "item-delete") {
            notFound(request("GET", "${wardrobe(owner.id!!)}/$item/photos/$photo/content", stylistToken))
            verifyNoInteractions(storage)
        }
    }

    private fun user(role: UserRole = UserRole.USER, status: UserStatus = UserStatus.ACTIVE): AppUser =
        users.create(UUID.randomUUID().toString(), passwordHash, role, status)

    private fun change(user: AppUser, role: UserRole, status: UserStatus) {
        val current = requireNotNull(users.findById(user.id!!))
        users.changeRoleAndStatus(user.id!!, current.version, role, status)
    }

    private fun login(user: AppUser): String {
        val response = request("POST", "/api/auth/login", body = """{"login":"${user.login}","password":"$PASSWORD"}""")
        assertThat(response.statusCode()).isEqualTo(200)
        return JsonPath.read(String(response.body()), "$.accessToken")
    }

    private fun grant(token: String, stylist: AppUser) {
        assertThat(request("PUT", "$ACCESS/${stylist.id}", token).statusCode()).isEqualTo(204)
    }

    private fun seedGrant(owner: AppUser, stylist: AppUser) {
        jdbc.update("INSERT INTO access_grant (owner_id, stylist_id) VALUES (?, ?)", owner.id, stylist.id)
    }

    private fun grants(owner: AppUser, stylist: AppUser): Long = jdbc.queryForObject(
        "SELECT count(*) FROM access_grant WHERE owner_id = ? AND stylist_id = ?", Long::class.java, owner.id, stylist.id,
    )!!

    private fun identity(user: AppUser): Map<String, Any> = mapOf("id" to user.id!!.toInt(), "login" to user.login)

    private fun create(token: String): Long {
        val response = request("POST", ITEMS, token, ITEM)
        assertThat(response.statusCode()).isEqualTo(201)
        return number(JsonPath.read(String(response.body()), "$"), "id")
    }

    private fun photo(itemId: Long): Long = jdbc.queryForObject("""
        INSERT INTO wardrobe_item_photo (wardrobe_item_id, s3_key, content_type, size_bytes, created_at)
        VALUES (?, ?, 'image/png', ?, ?) RETURNING id
    """.trimIndent(), Long::class.java, itemId, UUID.randomUUID().toString(), photoBytes.size,
        Timestamp.from(TestTimeConfiguration.FIXED_TIME))!!

    private fun wardrobe(ownerId: Long) = "$CLIENTS/$ownerId/wardrobe/items"

    private fun readPaths(ownerId: Long, itemId: Long, photoId: Long): List<String> {
        val base = wardrobe(ownerId)
        return listOf(base, "$base/$itemId", "$base/$itemId/photos", "$base/$itemId/photos/$photoId/content")
    }

    private fun notFound(response: HttpResponse<ByteArray>) {
        assertThat(response.statusCode()).isEqualTo(404)
        assertThat(response.body()).isEmpty()
    }

    private fun rows(response: HttpResponse<ByteArray>): List<Map<String, Any>> {
        assertThat(response.statusCode()).isEqualTo(200)
        return JsonPath.read(String(response.body()), "$")
    }

    private fun number(row: Map<String, Any>, key: String) = (row.getValue(key) as Number).toLong()

    private fun request(method: String, path: String, token: String? = null, body: String? = null,
        contentType: String = "application/json"): HttpResponse<ByteArray> {
        val request = HttpRequest.newBuilder(URI("http://localhost:$port$path"))
            .method(method, body?.let(HttpRequest.BodyPublishers::ofString) ?: HttpRequest.BodyPublishers.noBody())
        if (body != null) request.header("Content-Type", contentType)
        if (token != null) request.header("Authorization", "Bearer $token")
        return client.send(request.build(), HttpResponse.BodyHandlers.ofByteArray())
    }

    companion object {
        private const val PASSWORD = "stylist-password"
        private const val ACCESS = "/api/me/stylist-access"
        private const val CLIENTS = "/api/stylist/clients"
        private const val ITEMS = "/api/wardrobe/items"
        private const val ITEM = """{"name":"Shirt","categoryId":1,"color":"White","material":"Cotton"}"""
        private val INVALID_PAGES = listOf("page=-1", "size=0", "size=-1", "size=51", "page=abc", "size=abc",
            "page=1.5", "size=1.5", "page=2147483648", "size=2147483648", "page=2147483647&size=2")
        @Container @JvmStatic val postgres = PostgreSQLContainer("postgres:18-alpine")
        @JvmStatic @DynamicPropertySource
        fun postgresProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
        }
    }
}
