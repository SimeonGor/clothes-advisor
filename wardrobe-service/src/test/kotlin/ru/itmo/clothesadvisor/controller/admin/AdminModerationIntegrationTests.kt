package ru.itmo.clothesadvisor.controller.admin

import com.jayway.jsonpath.JsonPath
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
import ru.itmo.clothesadvisor.config.PostgresIntegrationTest
import ru.itmo.clothesadvisor.config.TestTimeConfiguration
import ru.itmo.clothesadvisor.config.insertAccessGrant
import ru.itmo.clothesadvisor.config.insertItem
import ru.itmo.clothesadvisor.config.insertOutfit
import ru.itmo.clothesadvisor.config.insertRating
import ru.itmo.clothesadvisor.config.insertUser
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
    private val passwordHash = "!"
    private val objects = ConcurrentHashMap<String, ByteArray>()
    private val image = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47)

    @BeforeEach
    fun prepare() {
        doAnswer { call ->
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse()
                objects.getValue(call.getArgument(0))
            }
            .`when`(storage)
            .get(anyString(), anyLong())
    }

    @AfterEach fun closeClient() = client.close()

    @Test
    fun `moderation exposes existing DTOs and deletes photos and outfits preserving objects items and rating history`() {

        // given
        val fixtureState = fixture()
        addRatings(fixtureState)
        val actorId = actorId(fixtureState.admin)

        // when
        val item = dto(request("GET", itemPath(fixtureState), actorId))

        // then
        assertThat(item.keys)
            .containsExactlyInAnyOrder(
                "id",
                "name",
                "categoryId",
                "color",
                "material",
                "version",
                "createdAt",
                "modifiedAt",
            )

        // then

        // when
        val ownerItem =
            dto(
                request(
                    "GET",
                    "/api/wardrobe/items/${fixtureState.item}",
                    actorId(fixtureState.owner),
                ),
            )

        // then
        assertThat(item).isEqualTo(ownerItem)

        // when
        val listedItems = rows(request("GET", itemsPath(fixtureState), actorId))

        // then
        assertThat(listedItems).containsExactly(item)

        // when
        val photos = request("GET", itemPath(fixtureState) + "/photos", actorId)

        // then
        assertThat(rows(photos))
            .containsExactly(
                mapOf(
                    "id" to fixtureState.photo.toInt(),
                    "itemId" to fixtureState.item.toInt(),
                    "contentType" to "image/png",
                    "sizeBytes" to image.size,
                    "createdAt" to TestTimeConfiguration.FIXED_TIME.toString(),
                ),
            )

        // when
        val content = request("GET", photoPath(fixtureState) + "/content", actorId)

        // then
        assertThat(content.statusCode()).isEqualTo(200)

        // then
        assertThat(content.body()).isEqualTo(image)

        // then
        assertThat(content.headers().firstValue("Content-Type")).hasValue("image/png")

        // then
        assertThat(content.headers().firstValue("Cache-Control")).hasValue("no-store")

        // then
        assertThat(content.headers().firstValue("X-Content-Type-Options")).hasValue("nosniff")

        // when
        val outfit = dto(request("GET", outfitPath(fixtureState), actorId))

        // then
        assertThat(outfit.keys)
            .containsExactlyInAnyOrder(
                "id",
                "ownerId",
                "authorId",
                "source",
                "name",
                "itemIds",
                "weather",
                "createdAt",
                "likes",
                "dislikes",
            )

        // then

        // when
        val ownerOutfit =
            dto(request("GET", "/api/outfits/${fixtureState.outfit}", actorId(fixtureState.owner)))

        // then
        assertThat(outfit).isEqualTo(ownerOutfit)
        assertThat(number(outfit, "likes")).isEqualTo(1)
        assertThat(number(outfit, "dislikes")).isEqualTo(1)

        // when
        val allOutfits = request("GET", outfitsPath(fixtureState), actorId)

        // then
        assertThat(rows(allOutfits)).containsExactly(outfit)

        // then
        assertThat(allOutfits.headers().firstValue("X-Total-Count")).hasValue("1")

        // given: preserve all current and historical versions before deletion
        val expectedHistory = allRatingVersions(fixtureState)
        clearInvocations(storage)

        // when
        val deletedPhoto = request("DELETE", photoPath(fixtureState), actorId)

        // then
        assertEmpty(deletedPhoto, 204)

        // when
        val repeatedPhotoDeletion = request("DELETE", photoPath(fixtureState), actorId)

        // then
        assertEmpty(repeatedPhotoDeletion, 404)

        // when
        val deletedContent = request("GET", photoPath(fixtureState) + "/content", actorId)

        // then
        assertEmpty(deletedContent, 404)

        // when
        val remainingPhotos = rows(request("GET", itemPath(fixtureState) + "/photos", actorId))

        // then
        assertThat(remainingPhotos).isEmpty()

        // when
        val deletedOutfit = request("DELETE", outfitPath(fixtureState), actorId)

        // then
        assertEmpty(deletedOutfit, 204)

        // when
        val repeatedOutfitDeletion = request("DELETE", outfitPath(fixtureState), actorId)

        // then
        assertEmpty(repeatedOutfitDeletion, 404)

        // when
        val missingOutfit = request("GET", outfitPath(fixtureState), actorId)

        // then
        assertEmpty(missingOutfit, 404)

        // when
        val remainingOutfits = rows(request("GET", outfitsPath(fixtureState), actorId))

        // then
        assertThat(remainingOutfits).isEmpty()
        assertDeletedOutfit(fixtureState, expectedHistory)

        // when
        val preservedItem = dto(request("GET", itemPath(fixtureState), actorId))

        // then
        assertThat(preservedItem).isEqualTo(item)
        assertThat(count("wardrobe_item_history")).isZero()
        assertThat(count("wardrobe_item_photo")).isZero()
        verifyNoInteractions(storage)
    }

    @Test
    fun `lists page in existing order with default and maximum sizes and reject malformed paging`() {

        // given
        val fixtureState = fixture()
        val itemIds = listOf(fixtureState.item) + (1..50).map { item(fixtureState.owner) }
        val outfitIds =
            listOf(fixtureState.outfit) +
                (1..50).map { outfit(fixtureState.owner, fixtureState.item) }
        val actorId = actorId(fixtureState.admin)
        for ((path, ids) in
            listOf(
                itemsPath(fixtureState) to itemIds,
                outfitsPath(fixtureState) to outfitIds.reversed(),
            )) {

            // when
            val first = request("GET", path, actorId)

            // then
            assertThat(rows(first).map { number(it, "id") }).containsExactlyElementsOf(ids.take(50))

            // when
            val secondPageIds =
                rows(request("GET", "$path?page=1&size=50", actorId)).map { number(it, "id") }

            // then
            assertThat(secondPageIds).containsExactlyElementsOf(ids.drop(50))

            // when
            val singleEntryIds =
                rows(request("GET", "$path?page=1&size=1", actorId)).map { number(it, "id") }

            // then
            assertThat(singleEntryIds).containsExactly(ids[1])

            // when
            val empty = request("GET", "$path?page=100", actorId)

            // then
            assertThat(rows(empty)).isEmpty()
            if (path == outfitsPath(fixtureState)) {

                // then
                assertThat(first.headers().firstValue("X-Total-Count")).hasValue("51")

                // then
                assertThat(empty.headers().firstValue("X-Total-Count")).hasValue("51")
            } else {

                // then
                assertThat(first.headers().firstValue("X-Total-Count")).isEmpty()
            }
            for (query in
                listOf(
                    "page=-1",
                    "size=0",
                    "size=51",
                    "page=no",
                    "size=no",
                    "page=2147483647&size=50",
                )) {

                // when
                val invalidPageStatus = request("GET", "$path?$query", actorId).statusCode()

                // then
                assertThat(invalidPageStatus).`as`("$path?$query").isEqualTo(400)
            }
        }
        verifyNoInteractions(storage)
    }

    @ParameterizedTest
    @CsvSource(
        "USER, ACTIVE",
        "USER, BLOCKED",
        "STYLIST, ACTIVE",
        "STYLIST, BLOCKED",
        "ADMIN, ACTIVE",
        "ADMIN, BLOCKED",
    )
    fun `owner current role and status do not restrict any moderation route`(
        role: UserRole,
        status: UserStatus,
    ) {

        // given
        val fixtureState = fixture()

        // given: the actor or owner has the next role and status
        users.changeRoleAndStatus(fixtureState.owner.id!!, 1, role, status)
        val actorId = actorId(fixtureState.admin)
        assertThat(count("access_grant")).isZero()
        for ((method, path) in routes(fixtureState)) {

            // when
            val moderationStatus = request(method, path, actorId).statusCode()

            // then
            assertThat(moderationStatus)
                .`as`("$method $path")
                .isEqualTo(if (method == "GET") 200 else 204)
        }
    }

    @Test
    fun `all eight routes require active admin using current role and status of existing actorId`() {

        // given
        val fixtureState = fixture()
        fun denied(actorId: String?, status: Int) {
            routes(fixtureState).forEach { (method, path) ->

                // when
                val deniedStatus = request(method, path, actorId).statusCode()

                // then
                assertThat(deniedStatus).`as`("$method $path").isEqualTo(status)
            }
        }
        denied(null, 400)
        denied("invalid-actorId", 400)
        denied(actorId(fixtureState.owner), 403)
        denied(actorId(user(UserRole.STYLIST)), 403)
        val actorId = actorId(fixtureState.admin)

        // given: the actor or owner has the next role and status
        users.changeRoleAndStatus(fixtureState.admin.id!!, 1, UserRole.ADMIN, UserStatus.BLOCKED)
        denied(actorId, 403)

        // given: the actor or owner has the next role and status
        users.changeRoleAndStatus(fixtureState.admin.id!!, 2, UserRole.USER, UserStatus.ACTIVE)
        denied(actorId, 403)

        // given: the actor or owner has the next role and status
        users.changeRoleAndStatus(fixtureState.admin.id!!, 3, UserRole.ADMIN, UserStatus.ACTIVE)

        // when
        val restoredAdminStatus = request("GET", outfitPath(fixtureState), actorId).statusCode()

        // then
        assertThat(restoredAdminStatus).isEqualTo(200)
        assertThat(count("wardrobe_item_photo")).isEqualTo(1)
        assertThat(count("outfit")).isEqualTo(1)
        verifyNoInteractions(storage)
    }

    @Test
    fun `missing owner and mislinked content are hidden and unsupported mutations are absent`() {

        // given
        val fixtureState = fixture()
        val other = fixture()
        val actorId = actorId(fixtureState.admin)
        for (ownerId in listOf(Long.MAX_VALUE, other.owner.id!!)) {
            for ((method, path) in routes(fixtureState)) {
                if (
                    ownerId != Long.MAX_VALUE &&
                        path in listOf(itemsPath(fixtureState), outfitsPath(fixtureState))
                )
                    continue

                // when
                val mislinkedOwner =
                    request(
                        method,
                        path.replace("/users/${fixtureState.owner.id}/", "/users/$ownerId/"),
                        actorId,
                    )

                // then
                assertEmpty(mislinkedOwner, 404)
            }
        }
        for (photoId in listOf(other.photo, Long.MAX_VALUE)) {

            // when
            val mislinkedContent =
                request("GET", itemPath(fixtureState) + "/photos/$photoId/content", actorId)

            // then
            assertEmpty(mislinkedContent, 404)

            // when
            val mislinkedDeletion =
                request("DELETE", itemPath(fixtureState) + "/photos/$photoId", actorId)

            // then
            assertEmpty(mislinkedDeletion, 404)
        }

        // given
        val sibling = item(fixtureState.owner)

        // when
        val siblingContent =
            request(
                "GET",
                itemsPath(fixtureState) + "/$sibling/photos/${fixtureState.photo}/content",
                actorId,
            )

        // then
        assertEmpty(siblingContent, 404)

        // when
        val siblingDeletion =
            request(
                "DELETE",
                itemsPath(fixtureState) + "/$sibling/photos/${fixtureState.photo}",
                actorId,
            )

        // then
        assertEmpty(siblingDeletion, 404)
        for ((method, path) in
            listOf(
                "POST" to itemsPath(fixtureState),
                "PUT" to itemPath(fixtureState),
                "DELETE" to itemPath(fixtureState),
                "POST" to itemPath(fixtureState) + "/photos",
                "PUT" to photoPath(fixtureState),
                "POST" to outfitsPath(fixtureState),
                "PUT" to outfitPath(fixtureState),
                "POST" to outfitPath(fixtureState) + "/rating",
                "PUT" to outfitPath(fixtureState) + "/rating",
                "DELETE" to outfitPath(fixtureState) + "/rating",
                "GET" to outfitPath(fixtureState) + "/ratings/history",
            )) {

            // when
            val unsupportedMethodStatus =
                request(method, path, actorId, if (method in listOf("POST", "PUT")) "{}" else null)
                    .statusCode()

            // then
            assertThat(unsupportedMethodStatus).`as`("$method $path").isIn(404, 405)
        }
        assertThat(count("wardrobe_item_photo")).isEqualTo(2)
        assertThat(count("outfit")).isEqualTo(2)
        verifyNoInteractions(storage)
    }

    @ParameterizedTest
    @CsvSource("USER, ACTIVE", "ADMIN, BLOCKED")
    fun `download rechecks administrator after storage with no surrounding transaction`(
        role: UserRole,
        status: UserStatus,
    ) {

        // given
        val fixtureState = fixture()
        val actorId = actorId(fixtureState.admin)
        doAnswer {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse()

                // given: the actor or owner has the next role and status
                users.changeRoleAndStatus(fixtureState.admin.id!!, 1, role, status)
                image
            }
            .`when`(storage)
            .get(anyString(), anyLong())

        // when
        val response = request("GET", photoPath(fixtureState) + "/content", actorId)

        // then
        assertForbidden(response)
        assertThat(count("wardrobe_item_photo")).isEqualTo(1)
    }

    @Test
    fun `download rechecks metadata and keeps existing storage failure status`() {

        // given
        val fixtureState = fixture()
        val actorId = actorId(fixtureState.admin)
        doAnswer {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse()
                throw PhotoStorageUnavailableException()
            }
            .`when`(storage)
            .get(anyString(), anyLong())

        // when
        val unavailableDownload = request("GET", photoPath(fixtureState) + "/content", actorId)

        // then
        assertEmpty(unavailableDownload, 503)

        // given
        doAnswer {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse()

                // when
                val deletionDuringDownload = request("DELETE", photoPath(fixtureState), actorId)

                // then
                assertEmpty(deletionDuringDownload, 204)
                image
            }
            .`when`(storage)
            .get(anyString(), anyLong())

        // when
        val revokedDownload = request("GET", photoPath(fixtureState) + "/content", actorId)

        // then
        assertEmpty(revokedDownload, 404)
    }

    @ParameterizedTest
    @CsvSource(
        "PHOTO, USER, ACTIVE",
        "PHOTO, ADMIN, BLOCKED",
        "OUTFIT, USER, ACTIVE",
        "OUTFIT, ADMIN, BLOCKED",
    )
    fun `delete rechecks fresh administrator permission after actual business row lock wait`(
        kind: String,
        role: UserRole,
        status: UserStatus,
    ) {

        // given
        val fixtureState = fixture()
        addRatings(fixtureState)
        val actorId = actorId(fixtureState.admin)
        val beforeHistory = ratingRows("outfit_rating_history", fixtureState)
        val beforeCurrent = ratingRows("outfit_rating", fixtureState)
        val locked = CountDownLatch(1)
        val enteringLock = CountDownLatch(1)
        val release = CountDownLatch(1)
        val table = if (kind == "PHOTO") "wardrobe_item" else "outfit"
        val id = if (kind == "PHOTO") fixtureState.item else fixtureState.outfit
        if (kind == "PHOTO") {
            doAnswer { call ->
                    enteringLock.countDown()
                    mockingDetails(itemRepository).mockCreationSettings.defaultAnswer.answer(call)
                }
                .`when`(itemRepository)
                .lockOwned(fixtureState.item, fixtureState.owner.id!!)
        } else {
            doAnswer { call ->
                    enteringLock.countDown()
                    mockingDetails(outfitRepository).mockCreationSettings.defaultAnswer.answer(call)
                }
                .`when`(outfitRepository)
                .findLockedByIdAndOwnerId(fixtureState.outfit, fixtureState.owner.id!!)
        }
        Executors.newFixedThreadPool(2).use { executor ->

            // when
            val holder =
                executor.submit(
                    Callable {
                        TransactionTemplate(transactionManager).executeWithoutResult {
                            jdbc.queryForList("SELECT id FROM $table WHERE id = ? FOR UPDATE", id)
                            locked.countDown()
                            assertThat(release.await(15, TimeUnit.SECONDS)).isTrue()
                        }
                    },
                )
            try {
                assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue()
                val deletion =
                    executor.submit(
                        Callable {
                            request(
                                "DELETE",
                                if (kind == "PHOTO") photoPath(fixtureState)
                                else outfitPath(fixtureState),
                                actorId,
                            )
                        },
                    )
                assertThat(enteringLock.await(10, TimeUnit.SECONDS)).isTrue()
                assertDatabaseLockWait(table)

                // given: the actor or owner has the next role and status
                users.changeRoleAndStatus(fixtureState.admin.id!!, 1, role, status)
                release.countDown()
                holder.get(20, TimeUnit.SECONDS)

                // then
                assertForbidden(deletion.get(20, TimeUnit.SECONDS))
            } finally {
                release.countDown()
            }
        }
        assertThat(count("wardrobe_item_photo")).isEqualTo(1)
        assertThat(count("outfit")).isEqualTo(1)
        assertThat(count("outfit_item")).isEqualTo(1)
        assertThat(count("outfit_weather")).isEqualTo(1)
        assertThat(ratingRows("outfit_rating", fixtureState)).isEqualTo(beforeCurrent)
        assertThat(ratingRows("outfit_rating_history", fixtureState)).isEqualTo(beforeHistory)
        verifyNoInteractions(storage)
    }

    @Test
    fun `owner and admin concurrent outfit deletes archive every version once`() {

        // given
        val fixtureState = fixture()
        addRatings(fixtureState)
        val expectedHistory = allRatingVersions(fixtureState)
        val ownerActorId = actorId(fixtureState.owner)
        val adminActorId = actorId(fixtureState.admin)
        val locked = CountDownLatch(1)
        val enteringSecondLock = CountDownLatch(1)
        val release = CountDownLatch(1)
        val first = AtomicBoolean(true)
        doAnswer { call ->
                val firstCall = first.compareAndSet(true, false)
                if (!firstCall) enteringSecondLock.countDown()
                val result =
                    mockingDetails(outfitRepository).mockCreationSettings.defaultAnswer.answer(call)
                if (firstCall) {
                    locked.countDown()
                    assertThat(release.await(15, TimeUnit.SECONDS)).isTrue()
                }
                result
            }
            .`when`(outfitRepository)
            .findLockedByIdAndOwnerId(fixtureState.outfit, fixtureState.owner.id!!)
        Executors.newFixedThreadPool(2).use { executor ->

            // when
            val owner =
                executor.submit(
                    Callable {
                        request("DELETE", "/api/outfits/${fixtureState.outfit}", ownerActorId)
                    },
                )
            try {
                assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue()
                val admin =
                    executor.submit(
                        Callable { request("DELETE", outfitPath(fixtureState), adminActorId) },
                    )
                assertThat(enteringSecondLock.await(10, TimeUnit.SECONDS)).isTrue()
                assertDatabaseLockWait("outfit")
                release.countDown()

                // then
                assertEmpty(owner.get(20, TimeUnit.SECONDS), 204)
                assertEmpty(admin.get(20, TimeUnit.SECONDS), 404)
            } finally {
                release.countDown()
            }
        }
        assertDeletedOutfit(fixtureState, expectedHistory)
        verifyNoInteractions(storage)
    }

    @Test
    fun `admin outer transaction rolls back written outfit deletion and newly archived ratings together`() {

        // given
        val fixtureState = fixture()
        addRatings(fixtureState)
        val actorId = actorId(fixtureState.admin)
        val beforeHistory = ratingRows("outfit_rating_history", fixtureState)
        val beforeCurrent = ratingRows("outfit_rating", fixtureState)
        doAnswer { call ->
                org.mockito.Mockito.mockingDetails(call.mock)
                    .mockCreationSettings
                    .defaultAnswer
                    .answer(call)
                assertThat(count("outfit")).isZero()
                throw DataIntegrityViolationException("Failure after delete")
            }
            .`when`(outfitRepository)
            .delete(
                org.mockito.ArgumentMatchers.any(
                    ru.itmo.clothesadvisor.model.outfit.Outfit::class.java,
                )
                    ?: ru.itmo.clothesadvisor.model.outfit.Outfit(
                        0,
                        0,
                        ru.itmo.clothesadvisor.model.outfit.OutfitSource.USER,
                        "fixture",
                        TestTimeConfiguration.FIXED_TIME,
                    ),
            )

        // when
        val rolledBackDeletion = request("DELETE", outfitPath(fixtureState), actorId)

        // then
        assertEmpty(rolledBackDeletion, 409)
        assertThat(count("outfit")).isEqualTo(1)
        assertThat(count("outfit_item")).isEqualTo(1)
        assertThat(count("outfit_weather")).isEqualTo(1)
        assertThat(ratingRows("outfit_rating", fixtureState)).isEqualTo(beforeCurrent)
        assertThat(ratingRows("outfit_rating_history", fixtureState)).isEqualTo(beforeHistory)
        verifyNoInteractions(storage)
    }

    private fun assertDatabaseLockWait(table: String) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        do {
            if (
                jdbc.queryForObject(
                    """SELECT EXISTS (SELECT 1 FROM pg_stat_activity
                WHERE datname = current_database() AND cardinality(pg_blocking_pids(pid)) > 0
                AND query LIKE ? AND wait_event_type = 'Lock')""",
                    Boolean::class.java,
                    "%$table%",
                ) == true
            )
                return
            Thread.yield()
        } while (System.nanoTime() < deadline)
        throw AssertionError(
            "Administrative delete never waited for the PostgreSQL $table row lock",
        )
    }

    private fun fixture(): Fixture {
        val owner = user()
        val item = item(owner)
        val key = UUID.randomUUID().toString()
        objects[key] = image
        val photo =
            jdbc.queryForObject(
                """INSERT INTO wardrobe_item_photo
            (wardrobe_item_id, s3_key, content_type, size_bytes, created_at) VALUES (?, ?, 'image/png', ?, ?) RETURNING id""",
                Long::class.java,
                item,
                key,
                image.size,
                Timestamp.from(TestTimeConfiguration.FIXED_TIME),
            )!!
        return Fixture(user(UserRole.ADMIN), owner, item, photo, outfit(owner, item))
    }

    private fun addRatings(fixtureState: Fixture) {
        val first = user(UserRole.STYLIST)
        val second = user(UserRole.STYLIST)
        for (stylist in listOf(first, second)) {
            jdbc.insertAccessGrant(fixtureState.owner.id!!, stylist.id!!)
            jdbc.insertRating(fixtureState.outfit, stylist.id!!)
        }
        jdbc.update(
            """INSERT INTO outfit_rating_history (outfit_id, stylist_id, vote, version, modified_at)
            SELECT outfit_id, stylist_id, vote, version, modified_at FROM outfit_rating
            WHERE outfit_id = ? AND stylist_id = ?""",
            fixtureState.outfit,
            first.id,
        )
        jdbc.update(
            """UPDATE outfit_rating SET vote = 'DISLIKE', version = 2, modified_at = CURRENT_TIMESTAMP
            WHERE outfit_id = ? AND stylist_id = ?""",
            fixtureState.outfit,
            first.id,
        )
        jdbc.update(
            "DELETE FROM access_grant WHERE owner_id = ? AND stylist_id IN (?, ?)",
            fixtureState.owner.id,
            first.id,
            second.id,
        )
        assertThat(count("access_grant")).isZero()
    }

    private fun ratingRows(table: String, fixtureState: Fixture): List<Map<String, Any?>> =
        jdbc.queryForList(
            "SELECT outfit_id, stylist_id, vote::text, version, modified_at FROM $table WHERE outfit_id = ? ORDER BY stylist_id, version",
            fixtureState.outfit,
        )

    private fun allRatingVersions(fixtureState: Fixture) =
        ratingRows("outfit_rating_history", fixtureState) +
            ratingRows("outfit_rating", fixtureState)

    private fun assertDeletedOutfit(
        fixtureState: Fixture,
        expectedHistory: List<Map<String, Any?>>,
    ) {
        assertThat(count("outfit")).isZero()
        assertThat(count("outfit_item")).isZero()
        assertThat(count("outfit_weather")).isZero()
        assertThat(count("outfit_rating")).isZero()
        assertThat(count("wardrobe_item")).isEqualTo(1)
        assertThat(ratingRows("outfit_rating_history", fixtureState))
            .containsExactlyInAnyOrderElementsOf(expectedHistory)
        assertThat(expectedHistory).hasSize(3)
        assertThat(
                jdbc.queryForObject(
                    "SELECT bool_and(archived_at IS NOT NULL) FROM outfit_rating_history WHERE outfit_id = ?",
                    Boolean::class.java,
                    fixtureState.outfit,
                ),
            )
            .isTrue()
    }

    private fun user(role: UserRole = UserRole.USER) =
        jdbc.insertUser(UUID.randomUUID().toString(), passwordHash, role)

    private fun item(owner: AppUser) =
        jdbc.insertItem(owner.id!!, CreateWardrobeItemRequest("Shirt", 1, "White", "Cotton")).id

    private fun outfit(owner: AppUser, item: Long) = jdbc.insertOutfit(owner.id!!, listOf(item))

    private fun itemsPath(fixtureState: Fixture) =
        "/api/admin/users/${fixtureState.owner.id}/wardrobe/items"

    private fun itemPath(fixtureState: Fixture) = itemsPath(fixtureState) + "/${fixtureState.item}"

    private fun photoPath(fixtureState: Fixture) =
        itemPath(fixtureState) + "/photos/${fixtureState.photo}"

    private fun outfitsPath(fixtureState: Fixture) =
        "/api/admin/users/${fixtureState.owner.id}/outfits"

    private fun outfitPath(fixtureState: Fixture) =
        outfitsPath(fixtureState) + "/${fixtureState.outfit}"

    private fun routes(fixtureState: Fixture) =
        listOf(
            "GET" to itemsPath(fixtureState),
            "GET" to itemPath(fixtureState),
            "GET" to itemPath(fixtureState) + "/photos",
            "GET" to photoPath(fixtureState) + "/content",
            "GET" to outfitsPath(fixtureState),
            "GET" to outfitPath(fixtureState),
            "DELETE" to photoPath(fixtureState),
            "DELETE" to outfitPath(fixtureState),
        )

    private fun actorId(user: AppUser): String = requireNotNull(user.id).toString()

    private fun request(
        method: String,
        path: String,
        actorId: String? = null,
        body: String? = null,
    ): HttpResponse<ByteArray> {
        val request =
            HttpRequest.newBuilder(URI("http://localhost:$port$path"))
                .timeout(Duration.ofSeconds(30))
                .method(
                    method,
                    body?.let(HttpRequest.BodyPublishers::ofString)
                        ?: HttpRequest.BodyPublishers.noBody(),
                )
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

    private fun count(table: String) =
        jdbc.queryForObject("SELECT count(*) FROM $table", Long::class.java)!!

    private data class Fixture(
        val admin: AppUser,
        val owner: AppUser,
        val item: Long,
        val photo: Long,
        val outfit: Long,
    )

    companion object {}
}
