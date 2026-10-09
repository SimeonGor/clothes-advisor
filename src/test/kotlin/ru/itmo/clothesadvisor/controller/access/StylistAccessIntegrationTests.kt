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
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.transaction.support.TransactionSynchronizationManager
import ru.itmo.clothesadvisor.config.PostgresIntegrationTest
import ru.itmo.clothesadvisor.config.TestTimeConfiguration
import ru.itmo.clothesadvisor.config.insertUser
import ru.itmo.clothesadvisor.model.user.AppUser
import ru.itmo.clothesadvisor.model.user.UserRole
import ru.itmo.clothesadvisor.model.user.UserStatus
import ru.itmo.clothesadvisor.service.user.AppUserService
import ru.itmo.clothesadvisor.storage.wardrobe.PhotoStorageUnavailableException
import ru.itmo.clothesadvisor.storage.wardrobe.S3PhotoStorage

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestTimeConfiguration::class)
@Execution(ExecutionMode.SAME_THREAD)
class StylistAccessIntegrationTests : PostgresIntegrationTest() {
    @LocalServerPort private var port: Int = 0
    @Autowired private lateinit var users: AppUserService
    @Autowired private lateinit var jdbc: JdbcTemplate
    @MockitoBean private lateinit var storage: S3PhotoStorage
    private val client = HttpClient.newHttpClient()
    private val passwordHash = "!"
    private val photoBytes = byteArrayOf(0, 1, 2, -1, 13, 10)

    @BeforeEach
    fun storageOutsideTransactions() {
        doAnswer {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse()
                photoBytes
            }
            .`when`(storage)
            .get(anyString(), anyLong())
    }

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
    fun `every access and wardrobe route enforces current actor and role`() {

        // given
        val owner = user()
        val stylist = user(UserRole.STYLIST)
        val ownerActorId = actorId(owner)
        val stylistActorId = actorId(stylist)
        val adminActorId = actorId(user(UserRole.ADMIN))
        val blocked = user()
        val blockedActorId = actorId(blocked)

        // given: the next role or status change
        change(blocked, UserRole.USER, UserStatus.BLOCKED)
        val item = create(ownerActorId)
        val photo = photo(item)
        for ((actorId, status) in listOf(null to 400, "invalid" to 400, blockedActorId to 403)) {

            // when
            val listStatus = request("GET", ACCESS, actorId).statusCode()

            // then
            assertThat(listStatus).isEqualTo(status)

            // when
            val grantStatus = request("PUT", "$ACCESS/${stylist.id}", actorId).statusCode()

            // then
            assertThat(grantStatus).isEqualTo(status)

            // when
            val revokeStatus = request("DELETE", "$ACCESS/${stylist.id}", actorId).statusCode()

            // then
            assertThat(revokeStatus).isEqualTo(status)
        }
        for (actorId in listOf(stylistActorId, adminActorId)) {

            // when
            val listStatus = request("GET", ACCESS, actorId).statusCode()

            // then
            assertThat(listStatus).isEqualTo(200)

            // when
            val grantStatus = request("PUT", "$ACCESS/${stylist.id}", actorId).statusCode()

            // then
            assertThat(grantStatus).isEqualTo(204)

            // when
            val revokeStatus = request("DELETE", "$ACCESS/${stylist.id}", actorId).statusCode()

            // then
            assertThat(revokeStatus).isEqualTo(204)
        }

        // given
        grant(ownerActorId, stylist)

        // given: the next role or status change
        change(stylist, UserRole.STYLIST, UserStatus.BLOCKED)
        for (actorId in listOf(null, "invalid")) {
            for (path in listOf(CLIENTS) + readPaths(owner.id!!, item, photo)) {

                // when
                val invalidActorStatus = request("GET", path, actorId).statusCode()

                // then
                assertThat(invalidActorStatus).describedAs(path).isEqualTo(400)
            }
        }

        // when
        val blockedStylistStatus = request("GET", CLIENTS, stylistActorId).statusCode()

        // then
        assertThat(blockedStylistStatus).isEqualTo(403)
        for (actorId in listOf(ownerActorId, adminActorId)) {

            // when
            val clientListStatus = request("GET", CLIENTS, actorId).statusCode()

            // then
            assertThat(clientListStatus).isEqualTo(200)
            readPaths(owner.id!!, item, photo).forEach {

                // when
                val unsharedWardrobeStatus = request("GET", it, actorId).statusCode()

                // then
                assertThat(unsharedWardrobeStatus).describedAs(it).isEqualTo(404)
            }
        }

        // given: the next role or status change
        change(stylist, UserRole.USER, UserStatus.ACTIVE)

        // when
        val changedRoleListStatus = request("GET", CLIENTS, stylistActorId).statusCode()

        // then
        assertThat(changedRoleListStatus).isEqualTo(200)
        readPaths(owner.id!!, item, photo).forEach {

            // when
            val changedRoleReadStatus = request("GET", it, stylistActorId).statusCode()

            // then
            assertThat(changedRoleReadStatus).describedAs(it).isEqualTo(404)
        }

        // given: the next role or status change
        change(owner, UserRole.STYLIST, UserStatus.ACTIVE)

        // when
        val ownerAccessListStatus = request("GET", ACCESS, ownerActorId).statusCode()

        // then
        assertThat(ownerAccessListStatus).isEqualTo(200)
        verifyNoInteractions(storage)
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

    @ParameterizedTest
    @ValueSource(strings = ["owner-block", "owner-role", "stylist-block", "stylist-role"])
    fun `grant survives account changes and access resumes after restoration`(change: String) {

        // given
        val owner = user()
        val stylist = user(UserRole.STYLIST)
        val ownerActorId = actorId(owner)
        val stylistActorId = actorId(stylist)
        val item = create(ownerActorId)
        val photo = photo(item)
        grant(ownerActorId, stylist)
        val affected = if (change.startsWith("owner")) owner else stylist
        val originalRole = affected.role

        // given: the next role or status change
        change(
            affected,
            if (change.endsWith("role")) UserRole.ADMIN else originalRole,
            if (change.endsWith("block")) UserStatus.BLOCKED else UserStatus.ACTIVE,
        )
        val expected = if (change == "stylist-block") 403 else 404
        readPaths(owner.id!!, item, photo).forEach {

            // when
            val response = request("GET", it, stylistActorId)

            // then
            assertThat(response.statusCode()).isEqualTo(expected)
            if (expected == 404) assertThat(response.body()).isEmpty()
        }
        if (affected === owner) {

            // when
            val hiddenClients = rows(request("GET", CLIENTS, stylistActorId))

            // then
            assertThat(hiddenClients).isEmpty()
        } else {

            // when
            val retainedGrants = rows(request("GET", ACCESS, ownerActorId))

            // then
            assertThat(retainedGrants).containsExactly(identity(stylist))
        }
        verifyNoInteractions(storage)
        assertThat(grants(owner, stylist)).isEqualTo(1)

        // given: the next role or status change
        change(affected, originalRole, UserStatus.ACTIVE)

        // when
        val restoredGrants = rows(request("GET", ACCESS, ownerActorId))

        // then
        assertThat(restoredGrants).containsExactly(identity(stylist))

        // when
        val restoredClients = rows(request("GET", CLIENTS, stylistActorId))

        // then
        assertThat(restoredClients).containsExactly(identity(owner))
        readPaths(owner.id!!, item, photo).forEach {

            // when
            val restoredReadStatus = request("GET", it, stylistActorId).statusCode()

            // then
            assertThat(restoredReadStatus).isEqualTo(200)
        }
    }

    @Test
    fun `stylist reads the same private wardrobe and photo DTOs and bytes without modifying history`() {

        // given
        val owner = user()
        val stylist = user(UserRole.STYLIST)
        val ownerActorId = actorId(owner)
        val stylistActorId = actorId(stylist)
        val item = create(ownerActorId)
        val photo = photo(item)
        grant(ownerActorId, stylist)
        val base = wardrobe(owner.id!!)
        for (suffix in listOf("", "/$item", "/$item/photos")) {

            // when
            val own = request("GET", "$ITEMS$suffix", ownerActorId)

            // when
            val shared = request("GET", "$base$suffix", stylistActorId)

            // then
            assertThat(shared.statusCode()).isEqualTo(200)
            assertThat(shared.body()).isEqualTo(own.body())
        }

        // when
        val content = request("GET", "$base/$item/photos/$photo/content", stylistActorId)

        // then
        assertThat(content.statusCode()).isEqualTo(200)
        assertThat(content.body()).isEqualTo(photoBytes)
        assertThat(content.headers().firstValue("Content-Type")).hasValue("image/png")
        assertThat(content.headers().firstValue("Cache-Control")).hasValue("no-store")
        assertThat(content.headers().firstValue("X-Content-Type-Options")).hasValue("nosniff")
        assertThat(
                jdbc.queryForObject(
                    "SELECT version FROM wardrobe_item WHERE id = ?",
                    Long::class.java,
                    item,
                ),
            )
            .isEqualTo(1)
        assertThat(
                jdbc.queryForObject(
                    "SELECT count(*) FROM wardrobe_item_history WHERE wardrobe_item_id = ?",
                    Long::class.java,
                    item,
                ),
            )
            .isZero()
        assertThat(
                jdbc.queryForObject(
                    "SELECT count(*) FROM app_user_history WHERE user_id IN (?, ?)",
                    Long::class.java,
                    owner.id,
                    stylist.id,
                ),
            )
            .isZero()

        // given: storage fails on the next download
        doAnswer { throw PhotoStorageUnavailableException() }
            .`when`(storage)
            .get(anyString(), anyLong())

        // when
        val unavailable = request("GET", "$base/$item/photos/$photo/content", stylistActorId)

        // then
        assertThat(unavailable.statusCode()).isEqualTo(503)
        assertThat(unavailable.body()).isEmpty()
    }

    @Test
    fun `shared wardrobe list retains pagination bounds and owner filtering`() {

        // given
        val owner = user()
        val stylist = user(UserRole.STYLIST)
        val actorId = actorId(stylist)
        grant(actorId(owner), stylist)
        val now = Timestamp.from(TestTimeConfiguration.FIXED_TIME)
        jdbc.update(
            """
            INSERT INTO wardrobe_item (owner_id, category_id, name, color, material, version, created_at, modified_at)
            SELECT ?, 1, 'Shirt ' || n, 'White', 'Cotton', 1, ?, ? FROM generate_series(1, 55) n
            """
                .trimIndent(),
            owner.id,
            now,
            now,
        )
        create(actorId(user()))
        val path = wardrobe(owner.id!!)

        // when
        val firstResponse = request("GET", path, actorId)
        val first = rows(firstResponse)
        val second = rows(request("GET", "$path?page=1&size=50", actorId))

        // then
        assertThat(first).hasSize(50)
        assertThat(second).hasSize(5)
        val expected =
            jdbc.queryForList(
                "SELECT id FROM wardrobe_item WHERE owner_id = ? ORDER BY id",
                Long::class.java,
                owner.id,
            )
        assertThat((first + second).map { number(it, "id") }).isEqualTo(expected)
        assertThat(firstResponse.headers().allValues("X-Total-Count")).isEmpty()

        // when
        val singleItemPage = rows(request("GET", "$path?page=1&size=1", actorId))

        // then
        assertThat(singleItemPage).containsExactly(first[1])

        // when
        val emptyPage = rows(request("GET", "$path?page=2&size=50", actorId))

        // then
        assertThat(emptyPage).isEmpty()

        // when
        val maximumOffsetPage = rows(request("GET", "$path?page=${Int.MAX_VALUE}&size=1", actorId))

        // then
        assertThat(maximumOffsetPage).isEmpty()
        for (query in INVALID_PAGES) {

            // given: invalid pagination

            // when
            val invalidPageStatus = request("GET", "$path?$query", actorId).statusCode()

            // then
            assertThat(invalidPageStatus).isEqualTo(400)
        }
    }

    @Test
    fun `missing access and mismatched owner item or photo return empty 404 without S3`() {

        // given
        val owner = user()
        val other = user()
        val stylist = user(UserRole.STYLIST)
        val actorId = actorId(stylist)
        val ownerActorId = actorId(owner)
        val item = create(ownerActorId)
        val second = create(ownerActorId)
        val otherItem = create(actorId(other))
        val photo = photo(item)
        val otherPhoto = photo(otherItem)
        for (ownerId in listOf(owner.id!!, other.id!!, Long.MAX_VALUE)) {
            readPaths(ownerId, item, photo).forEach {

                // when
                val missingAccess = request("GET", it, actorId)

                // then
                assertNotFound(missingAccess)
            }
        }

        // given
        grant(ownerActorId, stylist)
        for (itemId in listOf(otherItem, Long.MAX_VALUE)) {
            readPaths(owner.id!!, itemId, photo).drop(1).forEach {

                // when
                val foreignItem = request("GET", it, actorId)

                // then
                assertNotFound(foreignItem)
            }
        }
        for ((itemId, photoId) in
            listOf(second to photo, item to otherPhoto, item to Long.MAX_VALUE)) {

            // when
            val mismatchedPhoto =
                request("GET", "${wardrobe(owner.id!!)}/$itemId/photos/$photoId/content", actorId)

            // then
            assertNotFound(mismatchedPhoto)
        }
        readPaths(other.id!!, item, photo).forEach {

            // when
            val otherOwner = request("GET", it, actorId)

            // then
            assertNotFound(otherOwner)
        }

        // when
        val revokeStatus = request("DELETE", "$ACCESS/${stylist.id}", ownerActorId).statusCode()

        // then
        assertThat(revokeStatus).isEqualTo(204)
        readPaths(owner.id!!, item, photo).forEach {

            // when
            val revokedAccess = request("GET", it, actorId)

            // then
            assertNotFound(revokedAccess)
        }
        verifyNoInteractions(storage)
    }

    @Test
    fun `stylist has no client mutation routes and personal API hides foreign items`() {

        // given
        val owner = user()
        val stylist = user(UserRole.STYLIST)
        val ownerActorId = actorId(owner)
        val actorId = actorId(stylist)
        val item = create(ownerActorId)
        val photo = photo(item)
        grant(ownerActorId, stylist)
        val base = wardrobe(owner.id!!)
        for ((method, suffix) in
            listOf(
                "POST" to "",
                "PUT" to "/$item",
                "DELETE" to "/$item?version=1",
                "POST" to "/$item/photos",
                "DELETE" to "/$item/photos/$photo",
            )) {
            val upload = method == "POST" && suffix.endsWith("/photos")
            val body =
                when {
                    upload ->
                        "--photo-boundary\r\nContent-Disposition: form-data; name=\"photos\"; filename=\"photo.png\"\r\n" +
                            "Content-Type: image/png\r\n\r\nbytes\r\n--photo-boundary--\r\n"
                    method == "DELETE" -> null
                    else -> ITEM.dropLast(1) + ",\"version\":1}"
                }
            val contentType =
                if (upload) "multipart/form-data; boundary=photo-boundary" else "application/json"

            // when
            val sharedMutationStatus =
                request(method, "$base$suffix", actorId, body, contentType).statusCode()

            // then
            assertThat(sharedMutationStatus).isIn(404, 405)

            // when
            val personalMutationStatus =
                request(method, "$ITEMS$suffix", actorId, body, contentType).statusCode()

            // then
            assertThat(personalMutationStatus)
                .isEqualTo(if (method == "POST" && suffix.isEmpty()) 201 else 404)
        }

        // when
        val ownerReadStatus = request("GET", "$ITEMS/$item", ownerActorId).statusCode()

        // then
        assertThat(ownerReadStatus).isEqualTo(200)

        // when
        val preservedPhotos = rows(request("GET", "$ITEMS/$item/photos", ownerActorId))

        // then
        assertThat(preservedPhotos).hasSize(1)
        verifyNoInteractions(storage)
    }

    @ParameterizedTest
    @ValueSource(
        strings =
            [
                "revoke",
                "owner-block",
                "owner-role",
                "stylist-block",
                "stylist-role",
                "photo-delete",
                "item-delete",
            ],
    )
    fun `changes committed during S3 download prevent bytes leaving the service`(mutation: String) {

        // given
        val owner = user()
        val stylist = user(UserRole.STYLIST)
        val ownerActorId = actorId(owner)
        val stylistActorId = actorId(stylist)
        val item = create(ownerActorId)
        val photo = photo(item)
        grant(ownerActorId, stylist)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        doAnswer {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse()
                entered.countDown()
                assertThat(release.await(10, TimeUnit.SECONDS)).isTrue()
                photoBytes
            }
            .`when`(storage)
            .get(anyString(), anyLong())
        Executors.newSingleThreadExecutor().use { executor ->

            // when
            val download =
                executor.submit(
                    Callable {
                        request(
                            "GET",
                            "${wardrobe(owner.id!!)}/$item/photos/$photo/content",
                            stylistActorId,
                        )
                    },
                )
            try {
                assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue()
                when (mutation) {
                    "revoke" -> {

                        // when
                        val revokeStatus =
                            request("DELETE", "$ACCESS/${stylist.id}", ownerActorId).statusCode()

                        // then
                        assertThat(revokeStatus).isEqualTo(204)
                    }
                    "owner-block" -> change(owner, UserRole.USER, UserStatus.BLOCKED)
                    "owner-role" -> change(owner, UserRole.ADMIN, UserStatus.ACTIVE)
                    "stylist-block" -> change(stylist, UserRole.STYLIST, UserStatus.BLOCKED)
                    "stylist-role" -> change(stylist, UserRole.ADMIN, UserStatus.ACTIVE)
                    "photo-delete" -> {

                        // when
                        val deletePhotoStatus =
                            request("DELETE", "$ITEMS/$item/photos/$photo", ownerActorId)
                                .statusCode()

                        // then
                        assertThat(deletePhotoStatus).isEqualTo(204)
                    }
                    "item-delete" -> {

                        // when
                        val deleteItemStatus =
                            request("DELETE", "$ITEMS/$item?version=1", ownerActorId).statusCode()

                        // then
                        assertThat(deleteItemStatus).isEqualTo(204)
                    }
                }
            } finally {
                release.countDown()
            }

            // then
            assertNotFound(download.get(20, TimeUnit.SECONDS))
        }
        assertThat(grants(owner, stylist)).isEqualTo(if (mutation == "revoke") 0 else 1)
        clearInvocations(storage)
        if (mutation == "photo-delete" || mutation == "item-delete") {

            // when
            val deletedContent =
                request(
                    "GET",
                    "${wardrobe(owner.id!!)}/$item/photos/$photo/content",
                    stylistActorId,
                )

            // then
            assertNotFound(deletedContent)
            verifyNoInteractions(storage)
        }
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

    private fun grant(actorId: String, stylist: AppUser) {

        // when
        val grantStatus = request("PUT", "$ACCESS/${stylist.id}", actorId).statusCode()

        // then
        assertThat(grantStatus).isEqualTo(204)
    }

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

    private fun create(actorId: String): Long {

        // when
        val response = request("POST", ITEMS, actorId, ITEM)

        // then
        assertThat(response.statusCode()).isEqualTo(201)
        return number(JsonPath.read(String(response.body()), "$"), "id")
    }

    private fun photo(itemId: Long): Long =
        jdbc.queryForObject(
            """
            INSERT INTO wardrobe_item_photo (wardrobe_item_id, s3_key, content_type, size_bytes, created_at)
            VALUES (?, ?, 'image/png', ?, ?) RETURNING id
            """
                .trimIndent(),
            Long::class.java,
            itemId,
            UUID.randomUUID().toString(),
            photoBytes.size,
            Timestamp.from(TestTimeConfiguration.FIXED_TIME),
        )!!

    private fun wardrobe(ownerId: Long) = "$CLIENTS/$ownerId/wardrobe/items"

    private fun readPaths(ownerId: Long, itemId: Long, photoId: Long): List<String> {
        val base = wardrobe(ownerId)
        return listOf(
            base,
            "$base/$itemId",
            "$base/$itemId/photos",
            "$base/$itemId/photos/$photoId/content",
        )
    }

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

    private fun number(row: Map<String, Any>, key: String) = (row.getValue(key) as Number).toLong()

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
        private const val ITEMS = "/api/wardrobe/items"
        private const val ITEM =
            """{"name":"Shirt","categoryId":1,"color":"White","material":"Cotton"}"""
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
