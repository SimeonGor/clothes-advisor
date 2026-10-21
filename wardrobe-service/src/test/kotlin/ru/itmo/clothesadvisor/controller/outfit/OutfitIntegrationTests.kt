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
import org.assertj.core.api.Assertions.catchThrowable
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
import ru.itmo.clothesadvisor.dto.wardrobe.CreateWardrobeItemRequest
import ru.itmo.clothesadvisor.model.outfit.OutfitItem
import ru.itmo.clothesadvisor.model.outfit.OutfitItemId
import ru.itmo.clothesadvisor.model.user.AppUser
import ru.itmo.clothesadvisor.model.user.UserRole
import ru.itmo.clothesadvisor.model.user.UserStatus
import ru.itmo.clothesadvisor.repository.outfit.OutfitItemRepository
import ru.itmo.clothesadvisor.repository.outfit.OutfitRepository
import ru.itmo.clothesadvisor.repository.wardrobe.WardrobeItemRepository
import ru.itmo.clothesadvisor.service.access.AccessGrantService
import ru.itmo.clothesadvisor.service.outfit.OutfitService
import ru.itmo.clothesadvisor.service.user.AppUserService
import ru.itmo.clothesadvisor.service.wardrobe.WardrobeItemService
import ru.itmo.clothesadvisor.storage.wardrobe.S3PhotoStorage

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestTimeConfiguration::class)
class OutfitIntegrationTests : PostgresIntegrationTest() {
    @LocalServerPort private var port: Int = 0
    @Autowired private lateinit var users: AppUserService
    @Autowired private lateinit var items: WardrobeItemService
    @Autowired private lateinit var outfits: OutfitService
    @Autowired private lateinit var access: AccessGrantService
    @Autowired private lateinit var jdbc: JdbcTemplate
    @Autowired private lateinit var transactionManager: PlatformTransactionManager
    @Autowired private lateinit var composition: OutfitItemRepository
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
        // given
        val owner = user()
        val actorId = actorId(owner)
        val ids = List(2) { item(owner) }.reversed()
        val name = "  Winter " + "x".repeat(291)

        // when
        val created =
            request(
                "POST",
                actorId = actorId,
                body =
                    body(ids, name).dropLast(1) +
                        ",\"ownerId\":9223372036854775807,\"authorId\":9223372036854775807,\"source\":\"AI\"}",
            )

        // then
        assertThat(created.statusCode()).isEqualTo(201)
        val original = dto(created)
        val id = number(original, "id")
        assertThat(created.headers().firstValue("Location")).hasValue("$OUTFITS/$id")
        assertThat(original.keys)
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
        assertThat(number(original, "ownerId")).isEqualTo(owner.id)
        assertThat(number(original, "authorId")).isEqualTo(owner.id)
        assertThat(original["source"]).isEqualTo("USER")
        assertThat(original["name"]).isEqualTo(name)
        assertThat(original["createdAt"])
            .isEqualTo(
                jdbc
                    .queryForObject(
                        "SELECT created_at FROM outfit WHERE id = ?",
                        java.sql.Timestamp::class.java,
                        id,
                    )!!
                    .toInstant()
                    .toString(),
            )
        assertThat(number(original, "likes")).isZero()
        assertThat(number(original, "dislikes")).isZero()
        assertThat(created.body())
            .contains("\"temperatureC\":$TEMPERATURE", "\"windSpeedMps\":$WIND")
        assertThat((original["itemIds"] as List<*>).map { (it as Number).toLong() }).isEqualTo(ids)
        val expectedWeather = OutfitWeatherDto(BigDecimal(TEMPERATURE), 1, BigDecimal(WIND))

        // when
        val storedOutfit = outfits.get(owner.id!!, id)

        // then
        assertThat(storedOutfit.weather).isEqualTo(expectedWeather)
        assertThat(
                jdbc.queryForObject(
                    "SELECT temperature_c FROM outfit_weather WHERE outfit_id = ?",
                    BigDecimal::class.java,
                    id,
                ),
            )
            .isEqualByComparingTo(TEMPERATURE)
        assertThat(
                jdbc.queryForObject(
                    "SELECT wind_speed_mps FROM outfit_weather WHERE outfit_id = ?",
                    BigDecimal::class.java,
                    id,
                ),
            )
            .isEqualByComparingTo(WIND)

        // when
        val read = request("GET", "$OUTFITS/$id", actorId)

        // then
        assertThat(dto(read)).isEqualTo(original)
        assertThat(read.body()).contains("\"temperatureC\":$TEMPERATURE", "\"windSpeedMps\":$WIND")

        // when
        val update = request("PUT", "$OUTFITS/$id", actorId, body(ids))

        // then
        assertThat(update.statusCode()).isEqualTo(405)

        // when
        val patch = request("PATCH", "$OUTFITS/$id", actorId, "{}")

        // then
        assertThat(patch.statusCode()).isEqualTo(405)

        // when
        val listed = request("GET", actorId = actorId)

        // then
        assertThat(listed.headers().firstValue("X-Total-Count")).hasValue("1")
        assertThat(rows(listed)).containsExactly(original)

        // when
        val deleted = request("DELETE", "$OUTFITS/$id", actorId)

        // then
        assertThat(deleted.statusCode()).isEqualTo(204)

        // when
        val missing = request("GET", "$OUTFITS/$id", actorId)

        // then
        assertEmpty(missing, 404)

        // when
        val repeatedDelete = request("DELETE", "$OUTFITS/$id", actorId)

        // then
        assertEmpty(repeatedDelete, 404)
        assertThat(count("outfit_item", "outfit_id", id)).isZero()
        assertThat(count("outfit_weather", "outfit_id", id)).isZero()
        ids.forEach { itemId ->

            // when
            val retainedItem = items.get(owner.id!!, itemId)

            // then
            assertThat(retainedItem.version).isEqualTo(1)
        }
    }

    @Test
    fun `stylist creates and reads authorized client outfits but cannot edit delete or choose authorship`() {
        // given
        val owner = user()
        val stylist = user(UserRole.STYLIST)
        access.grant(owner.id!!, stylist.id!!)
        val actorId = actorId(stylist)
        val path = stylistPath(owner)
        val item = item(owner)

        // when
        val created =
            request(
                "POST",
                path,
                actorId,
                body(listOf(item)).dropLast(1) +
                    ",\"ownerId\":${stylist.id},\"authorId\":${owner.id},\"source\":\"USER\"}",
            )

        // then
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

        // when
        val read = request("GET", "$path/$id", actorId)

        // then
        assertThat(dto(read)).isEqualTo(original)

        // when
        val listed = request("GET", path, actorId)

        // then
        assertThat(rows(listed)).containsExactly(original)

        // when
        val deletion = request("DELETE", "$path/$id", actorId)

        // then
        assertThat(deletion.statusCode()).isEqualTo(405)

        // when
        val update = request("PUT", "$path/$id", actorId, body(listOf(item)))

        // then
        assertThat(update.statusCode()).isEqualTo(405)

        // given
        val ownerActorId = actorId(owner)

        // when
        val ownerRead = request("GET", "$OUTFITS/$id", ownerActorId)

        // then
        assertThat(dto(ownerRead)).isEqualTo(original - "myRating")

        // when
        users.changeRoleAndStatus(stylist.id!!, stylist.version, UserRole.USER, UserStatus.ACTIVE)
        val readAfterRoleChange = request("GET", "$OUTFITS/$id", ownerActorId)

        // then
        assertThat(dto(readAfterRoleChange)["source"]).isEqualTo("STYLIST")

        // when
        val deniedRead = request("GET", "$path/$id", actorId)

        // then
        assertThat(deniedRead.statusCode()).isEqualTo(404)

        // when
        val ownerDeletion = request("DELETE", "$OUTFITS/$id", ownerActorId)

        // then
        assertThat(ownerDeletion.statusCode()).isEqualTo(204)
    }

    @Test
    fun `foreign missing and ungranted resources are indistinguishable and create nothing`() {
        // given
        val owner = user()
        val foreign = user()
        val stylist = user(UserRole.STYLIST)
        val ownerActorId = actorId(owner)
        val foreignActorId = actorId(foreign)
        val stylistActorId = actorId(stylist)
        val ownItem = item(owner)
        val foreignItem = item(foreign)
        val id =
            number(dto(request("POST", actorId = ownerActorId, body = body(listOf(ownItem)))), "id")
        for (target in listOf(id, Long.MAX_VALUE)) {

            // when
            val foreignRead = request("GET", "$OUTFITS/$target", foreignActorId)

            // then
            assertEmpty(foreignRead, 404)

            // when
            val foreignDeletion = request("DELETE", "$OUTFITS/$target", foreignActorId)

            // then
            assertEmpty(foreignDeletion, 404)

            // when
            val stylistRead = request("GET", "${stylistPath(owner)}/$target", stylistActorId)

            // then
            assertEmpty(stylistRead, 404)
        }
        for (ids in
            listOf(listOf(foreignItem), listOf(ownItem, foreignItem), listOf(Long.MAX_VALUE))) {

            // when
            val response = request("POST", actorId = ownerActorId, body = body(ids))

            // then
            assertEmpty(response, 404)
        }
        for (path in listOf(stylistPath(owner), "/api/stylist/clients/${Long.MAX_VALUE}/outfits")) {

            // when
            val listed = request("GET", path, stylistActorId)

            // then
            assertEmpty(listed, 404)

            // when
            val creation = request("POST", path, stylistActorId, body(listOf(ownItem)))

            // then
            assertEmpty(creation, 404)
        }

        // given
        access.grant(foreign.id!!, stylist.id!!)

        // when
        val foreignClientRead = request("GET", "${stylistPath(foreign)}/$id", stylistActorId)

        // then
        assertEmpty(foreignClientRead, 404)

        // when
        val foreignList = request("GET", actorId = foreignActorId)

        // then
        assertThat(rows(foreignList)).isEmpty()
        assertThat(count("outfit", "owner_id", owner.id!!)).isEqualTo(1)
        assertThat(count("outfit", "owner_id", foreign.id!!)).isZero()
    }

    @Test
    fun `every stylist route rechecks grants and both client and stylist current state`() {
        // given
        val owner = user()
        val stylist = user(UserRole.STYLIST)
        var currentOwner = owner
        var currentStylist = stylist
        val actorId = actorId(stylist)
        val ids = listOf(item(owner))
        access.grant(owner.id!!, stylist.id!!)
        val path = stylistPath(owner)
        val id = number(dto(request("POST", path, actorId, body(ids))), "id")
        fun requestStylistRoutes() =
            listOf(
                request("GET", path, actorId),
                request("GET", "$path/$id", actorId),
                request("POST", path, actorId, body(ids)),
            )

        fun assertDenied(responses: List<HttpResponse<String>>, status: Int) {
            for (response in responses) {
                assertThat(response.statusCode()).isEqualTo(status)
                if (status == 404) assertThat(response.body()).isEmpty()
            }
            assertThat(count("outfit", "owner_id", owner.id!!)).isEqualTo(1)
        }

        // when
        access.revoke(owner.id!!, stylist.id!!)
        val revokedResponses = requestStylistRoutes()

        // then
        assertDenied(revokedResponses, 404)

        // when
        access.grant(owner.id!!, stylist.id!!)
        currentOwner =
            users.changeRoleAndStatus(
                owner.id!!,
                currentOwner.version,
                UserRole.USER,
                UserStatus.BLOCKED,
            )
        val blockedOwnerResponses = requestStylistRoutes()

        // then
        assertDenied(blockedOwnerResponses, 404)

        // when
        currentOwner =
            users.changeRoleAndStatus(
                owner.id!!,
                currentOwner.version,
                UserRole.STYLIST,
                UserStatus.ACTIVE,
            )
        val changedOwnerRoleResponses = requestStylistRoutes()

        // then
        assertDenied(changedOwnerRoleResponses, 404)

        // when
        users.changeRoleAndStatus(
            owner.id!!,
            currentOwner.version,
            UserRole.USER,
            UserStatus.ACTIVE,
        )
        val restoredOwnerResponse = request("GET", path, actorId)

        // then
        assertThat(restoredOwnerResponse.statusCode()).isEqualTo(200)

        // when
        currentStylist =
            users.changeRoleAndStatus(
                stylist.id!!,
                currentStylist.version,
                UserRole.STYLIST,
                UserStatus.BLOCKED,
            )
        val blockedStylistResponses = requestStylistRoutes()

        // then
        assertDenied(blockedStylistResponses, 403)

        // when
        users.changeRoleAndStatus(
            stylist.id!!,
            currentStylist.version,
            UserRole.USER,
            UserStatus.ACTIVE,
        )
        val changedStylistRoleResponses = requestStylistRoutes()

        // then
        assertDenied(changedStylistRoleResponses, 404)
    }

    @Test
    fun `outfit owner endpoints require an active actor regardless of current role`() {
        // given
        val owner = user()
        val actorId = actorId(owner)
        val ids = listOf(item(owner))
        val id = number(dto(request("POST", actorId = actorId, body = body(ids))), "id")
        var current =
            users.changeRoleAndStatus(owner.id!!, owner.version, owner.role, UserStatus.BLOCKED)
        for ((credential, status) in listOf(null to 400, actorId to 403)) {
            for ((method, path) in
                listOf(
                    "GET" to OUTFITS,
                    "POST" to OUTFITS,
                    "GET" to "$OUTFITS/$id",
                    "DELETE" to "$OUTFITS/$id",
                )) {

                // when
                val response =
                    request(method, path, credential, if (method == "POST") body(ids) else null)

                // then
                assertThat(response.statusCode()).isEqualTo(status)
            }
        }

        // when
        current =
            users.changeRoleAndStatus(
                owner.id!!,
                current.version,
                UserRole.STYLIST,
                UserStatus.ACTIVE,
            )
        val restoredOwnerResponse = request("GET", actorId = actorId)

        // then
        assertThat(restoredOwnerResponse.statusCode()).isEqualTo(200)
        assertThat(count("outfit", "owner_id", current.id!!)).isEqualTo(1)
    }

    @Test
    fun `invalid composition name and weather never write any part of an outfit`() {
        // given
        val owner = user()
        val actorId = actorId(owner)
        val item = item(owner)
        val fiftyOne = List(51) { item(owner) }
        val tables = listOf("outfit", "outfit_item", "outfit_weather")
        val before = tables.map {
            jdbc.queryForObject("SELECT count(*) FROM $it", Long::class.java)
        }
        val valid = body(listOf(item))
        val invalid =
            listOf(
                "{",
                "{}",
                body(emptyList()),
                body(fiftyOne),
                body(listOf(item, item)),
                body(listOf(0)),
                body(listOf(-1)),
                body(listOf(item), " "),
                body(listOf(item), "x".repeat(301)),
                valid.replace("[$item]", "[null]"),
                valid.replace("[$item]", "null"),
                valid.replace("[$item]", "[9223372036854775808]"),
                valid.replace("[$item]", "[\"bad\"]"),
                valid.replace("\"Daily\"", "null"),
                valid.replace(TEMPERATURE, "null"),
                valid.replace(TEMPERATURE, "\"cold\""),
                valid.replace(WIND, "-0.001"),
                valid.replace(WIND, "null"),
                valid.replace("\"precipitationTypeId\":1", "\"precipitationTypeId\":null"),
                valid.replace(
                    "\"precipitationTypeId\":1",
                    "\"precipitationTypeId\":${Long.MAX_VALUE}",
                ),
                """{"name":"Daily","itemIds":[$item],"weather":null}""",
            )
        for (invalidBody in invalid) {

            // when
            val response = request("POST", actorId = actorId, body = invalidBody)

            // then
            assertThat(response.statusCode()).describedAs(invalidBody).isEqualTo(400)
        }
        assertThat(count("outfit", "owner_id", owner.id!!)).isZero()
        assertThat(tables.map { jdbc.queryForObject("SELECT count(*) FROM $it", Long::class.java) })
            .isEqualTo(before)

        // when
        val boundaryCreation =
            request("POST", actorId = actorId, body = body(fiftyOne.take(50)).replace(WIND, "0"))

        // then
        assertThat(boundaryCreation.statusCode()).isEqualTo(201)
    }

    @Test
    fun `lists page in descending ID order with owner scoped totals for both callers`() {
        // given
        val owner = user()
        val stylist = user(UserRole.STYLIST)
        val ownerActorId = actorId(owner)
        val stylistActorId = actorId(stylist)
        val input = input(listOf(item(owner)))
        jdbc.insertAccessGrant(owner.id!!, stylist.id!!)
        val expected =
            List(53) {
                    jdbc.insertOutfit(owner.id!!, listOf(requireNotNull(input.itemIds.single())))
                }
                .reversed()
        val foreign = user()
        jdbc.insertOutfit(foreign.id!!, listOf(item(foreign)))
        for ((path, actorId) in
            listOf(OUTFITS to ownerActorId, stylistPath(owner) to stylistActorId)) {

            // when
            val first = request("GET", path, actorId)
            val second = request("GET", "$path?page=1&size=50", actorId)

            // then
            assertThat(first.headers().firstValue("X-Total-Count")).hasValue("53")
            assertThat(second.headers().firstValue("X-Total-Count")).hasValue("53")
            assertThat(rows(first)).hasSize(50)
            assertThat(rows(second)).hasSize(3)
            assertThat((rows(first) + rows(second)).map { number(it, "id") }).isEqualTo(expected)

            // when
            val one = request("GET", "$path?page=1&size=1", actorId)

            // then
            assertThat(rows(one).map { number(it, "id") }).containsExactly(expected[1])

            // when
            val empty = request("GET", "$path?page=${Int.MAX_VALUE}&size=1", actorId)

            // then
            assertThat(rows(empty)).isEmpty()
            assertThat(empty.headers().firstValue("X-Total-Count")).hasValue("53")
            for (query in
                listOf(
                    "page=-1",
                    "size=0",
                    "size=51",
                    "page=abc",
                    "size=1.5",
                    "page=2147483648",
                    "page=${Int.MAX_VALUE}&size=2",
                )) {

                // when
                val response = request("GET", "$path?$query", actorId)

                // then
                assertThat(response.statusCode()).isEqualTo(400)
            }
        }
    }

    @Test
    fun `referenced item delete conflicts and rolls back photo metadata and history until outfit deleted`() {
        // given
        val owner = user()
        val actorId = actorId(owner)
        val item = item(owner)
        jdbc.update(
            """INSERT INTO wardrobe_item_photo (wardrobe_item_id, s3_key, content_type, size_bytes, created_at)
            VALUES (?, ?, 'image/png', 1, ?)""",
            item,
            UUID.randomUUID().toString(),
            java.sql.Timestamp.from(TestTimeConfiguration.FIXED_TIME),
        )
        val outfit = outfits.create(owner.id!!, input(listOf(item)))

        // when
        val referencedItemDeletion =
            request("DELETE", "/api/wardrobe/items/$item?version=1", actorId)

        // then
        assertEmpty(referencedItemDeletion, 409)

        // when
        val retainedItem = items.get(owner.id!!, item)

        // then
        assertThat(retainedItem.version).isEqualTo(1)
        assertThat(count("wardrobe_item_photo", "wardrobe_item_id", item)).isEqualTo(1)
        assertThat(count("wardrobe_item_history", "wardrobe_item_id", item)).isZero()

        // when
        val outfitDeletion = request("DELETE", "$OUTFITS/${outfit.id}", actorId)

        // then
        assertThat(outfitDeletion.statusCode()).isEqualTo(204)

        // when
        val unreferencedItemDeletion =
            request("DELETE", "/api/wardrobe/items/$item?version=1", actorId)

        // then
        assertThat(unreferencedItemDeletion.statusCode()).isEqualTo(204)
        assertThat(count("wardrobe_item_photo", "wardrobe_item_id", item)).isZero()
        assertThat(count("wardrobe_item_history", "wardrobe_item_id", item)).isEqualTo(1)
    }

    @Test
    fun `outer rollback removes written outfit composition and weather together`() {
        // given
        val owner = user()
        val ids = List(2) { item(owner) }
        var id = 0L

        // when
        val failure = catchThrowable {
            TransactionTemplate(transactionManager).executeWithoutResult {
                id = outfits.create(owner.id!!, input(ids)).id
                assertThat(count("outfit_item", "outfit_id", id)).isEqualTo(2)
                assertThat(count("outfit_weather", "outfit_id", id)).isEqualTo(1)
                error("forced rollback")
            }
        }

        // then
        assertThat(failure)
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessage("forced rollback")
        assertThat(count("outfit", "id", id)).isZero()
        assertThat(count("outfit_item", "outfit_id", id)).isZero()
        assertThat(count("outfit_weather", "outfit_id", id)).isZero()
        ids.forEach { itemId ->

            // when
            val retainedItem = items.get(owner.id!!, itemId)

            // then
            assertThat(retainedItem.version).isEqualTo(1)
        }
    }

    @Test
    fun `composition batch rejects an existing assigned identity and rolls back earlier inserts`() {
        // given
        val owner = user()
        val existingItem = item(owner)
        val newItem = item(owner)
        val outfitId = jdbc.insertOutfit(owner.id!!, listOf(existingItem))

        // when
        val failure = catchThrowable {
            TransactionTemplate(transactionManager).executeWithoutResult {
                composition.insertAll(
                    listOf(
                        OutfitItem(OutfitItemId(outfitId, newItem), 1),
                        OutfitItem(OutfitItemId(outfitId, existingItem), 2),
                    ),
                )
            }
        }

        // then
        assertThat(failure).isInstanceOf(DataIntegrityViolationException::class.java)
        assertThat(
                jdbc.queryForList(
                    "SELECT wardrobe_item_id, position FROM outfit_item WHERE outfit_id = ?",
                    outfitId,
                ),
            )
            .containsExactly(
                mapOf<String, Any>("wardrobe_item_id" to existingItem, "position" to 0),
            )
    }

    @Test
    fun `concurrent outfit deletes serialize with one not found and preserve wardrobe items`() {
        // given
        val owner = user()
        val actorId = actorId(owner)
        val ids = List(2) { item(owner) }
        val outfit = outfits.create(owner.id!!, input(ids))
        val loaded = CountDownLatch(1)
        val release = CountDownLatch(1)
        val secondLock = CountDownLatch(1)
        val firstRead = AtomicBoolean(true)
        doAnswer { call ->
                val first = firstRead.compareAndSet(true, false)
                if (!first) secondLock.countDown()
                val entity =
                    mockingDetails(outfitRepository).mockCreationSettings.defaultAnswer.answer(call)
                if (first) {
                    assertThat(entity).isNotNull()
                    loaded.countDown()
                    assertThat(release.await(10, TimeUnit.SECONDS)).isTrue()
                }
                entity
            }
            .`when`(outfitRepository)
            .findLockedByIdAndOwnerId(outfit.id, owner.id!!)

        // when
        Executors.newFixedThreadPool(2).use { executor ->
            val firstDelete =
                executor.submit(Callable { request("DELETE", "$OUTFITS/${outfit.id}", actorId) })
            try {
                assertThat(loaded.await(10, TimeUnit.SECONDS)).isTrue()
                val secondDelete =
                    executor.submit(
                        Callable { request("DELETE", "$OUTFITS/${outfit.id}", actorId) },
                    )
                assertThat(secondLock.await(10, TimeUnit.SECONDS)).isTrue()
                release.countDown()
                val firstDeletionResponse = firstDelete.get(30, TimeUnit.SECONDS)

                // then
                assertEmpty(firstDeletionResponse, 204)

                // when
                val secondDeletionResponse = secondDelete.get(30, TimeUnit.SECONDS)

                // then
                assertEmpty(secondDeletionResponse, 404)
            } finally {
                release.countDown()
            }
        }

        // then
        assertThat(count("outfit", "id", outfit.id)).isZero()
        assertThat(count("outfit_item", "outfit_id", outfit.id)).isZero()
        assertThat(count("outfit_weather", "outfit_id", outfit.id)).isZero()
        ids.forEach { itemId ->

            // when
            val retainedItem = items.get(owner.id!!, itemId)

            // then
            assertThat(retainedItem.version).isEqualTo(1)
        }

        // when
        val repeatedDeletion = request("DELETE", "$OUTFITS/${outfit.id}", actorId)

        // then
        assertEmpty(repeatedDeletion, 404)
    }

    @Test
    fun `outfit committed after deleter loaded item prevents stale delete`() {
        // given
        val owner = user()
        val item = item(owner)
        val loaded = CountDownLatch(1)
        val created = CountDownLatch(1)

        // when
        Executors.newSingleThreadExecutor().use { executor ->
            val deletion =
                executor.submit(
                    Callable {
                        try {
                            TransactionTemplate(transactionManager)
                                .apply { timeout = 20 }
                                .executeWithoutResult {
                                    assertThat(
                                            wardrobe.findByIdAndOwnerId(item, owner.id!!)!!.version,
                                        )
                                        .isEqualTo(1)
                                    loaded.countDown()
                                    assertThat(created.await(10, TimeUnit.SECONDS)).isTrue()
                                    items.delete(owner.id!!, item, 1)
                                }
                            null
                        } catch (failure: Exception) {
                            failure
                        }
                    },
                )
            try {
                assertThat(loaded.await(10, TimeUnit.SECONDS)).isTrue()
                outfits.create(owner.id!!, input(listOf(item)))
            } finally {
                created.countDown()
            }
            val deletionFailure = deletion.get(30, TimeUnit.SECONDS)

            // then
            assertThat(deletionFailure).isInstanceOf(DataIntegrityViolationException::class.java)
        }

        // when
        val retainedItem = items.get(owner.id!!, item)

        // then
        assertThat(retainedItem.version).isEqualTo(1)
        assertThat(count("wardrobe_item_history", "wardrobe_item_id", item)).isZero()

        // when
        val retainedOutfits = outfits.list(owner.id!!, PageRequest.of(0, 50))

        // then
        assertThat(retainedOutfits.totalElements).isEqualTo(1)
    }

    @Test
    fun `delete committed after ownership validation makes creation conflict and roll back all parts`() {
        // given
        val owner = user()
        val actorId = actorId(owner)
        val item = item(owner)
        val validated = CountDownLatch(1)
        val deleted = CountDownLatch(1)
        doAnswer { call ->
                val count = mockingDetails(wardrobe).mockCreationSettings.defaultAnswer.answer(call)
                validated.countDown()
                assertThat(deleted.await(10, TimeUnit.SECONDS)).isTrue()
                count
            }
            .`when`(wardrobe)
            .countByOwnerIdAndIdIn(owner.id!!, listOf(item))

        // when
        Executors.newSingleThreadExecutor().use { executor ->
            val creation =
                executor.submit(
                    Callable { request("POST", actorId = actorId, body = body(listOf(item))) },
                )
            try {
                assertThat(validated.await(10, TimeUnit.SECONDS)).isTrue()
                items.delete(owner.id!!, item, 1)
            } finally {
                deleted.countDown()
            }
            val creationResponse = creation.get(30, TimeUnit.SECONDS)

            // then
            assertEmpty(creationResponse, 409)
        }

        // then
        assertThat(count("outfit", "owner_id", owner.id!!)).isZero()
        assertThat(count("outfit_item", "wardrobe_item_id", item)).isZero()
        assertThat(count("wardrobe_item_history", "wardrobe_item_id", item)).isEqualTo(1)
    }

    @Test
    fun `list retains a complete snapshot when outfit is deleted before component reads`() {
        // given
        val owner = user()
        val actorId = actorId(owner)
        val original = outfits.create(owner.id!!, input(listOf(item(owner))))
        val loaded = CountDownLatch(1)
        val deleted = CountDownLatch(1)
        doAnswer { call ->
                val page =
                    mockingDetails(outfitRepository).mockCreationSettings.defaultAnswer.answer(call)
                loaded.countDown()
                assertThat(deleted.await(10, TimeUnit.SECONDS)).isTrue()
                page
            }
            .`when`(outfitRepository)
            .findAllByOwnerIdOrderByIdDesc(owner.id!!, PageRequest.of(0, 50))

        // when
        Executors.newSingleThreadExecutor().use { executor ->
            val reading = executor.submit(Callable { request("GET", actorId = actorId) })
            try {
                assertThat(loaded.await(10, TimeUnit.SECONDS)).isTrue()
                outfits.delete(owner.id!!, original.id)
            } finally {
                deleted.countDown()
            }
            val response = reading.get(30, TimeUnit.SECONDS)

            // then
            assertThat(response.headers().firstValue("X-Total-Count")).hasValue("1")
            val row = rows(response).single()
            assertThat(number(row, "id")).isEqualTo(original.id)
            assertThat(row["itemIds"] as List<*>).hasSize(1)
            assertThat(response.body())
                .contains("\"temperatureC\":$TEMPERATURE", "\"windSpeedMps\":$WIND")
        }

        // when
        val missingOutfit = request("GET", "$OUTFITS/${original.id}", actorId)

        // then
        assertEmpty(missingOutfit, 404)
    }

    private fun user(role: UserRole = UserRole.USER): AppUser =
        jdbc.insertUser(UUID.randomUUID().toString(), "!", role)

    private fun item(owner: AppUser): Long =
        jdbc.insertItem(owner.id!!, CreateWardrobeItemRequest("Shirt", 1, "White", "Cotton")).id

    private fun actorId(user: AppUser): String = requireNotNull(user.id).toString()

    private fun stylistPath(owner: AppUser) = "/api/stylist/clients/${owner.id}/outfits"

    private fun input(ids: List<Long>) =
        CreateOutfitRequest(
            "Daily",
            ids,
            OutfitWeatherDto(BigDecimal(TEMPERATURE), 1, BigDecimal(WIND)),
        )

    private fun body(ids: List<Long>, name: String = "Daily") =
        """{"name":"$name","itemIds":[${ids.joinToString(",")}],"weather":{"temperatureC":$TEMPERATURE,"precipitationTypeId":1,"windSpeedMps":$WIND}}"""

    private fun request(
        method: String,
        path: String = OUTFITS,
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

    private fun dto(response: HttpResponse<String>): Map<String, Any> =
        JsonPath.read(response.body(), "$")

    private fun rows(response: HttpResponse<String>): List<Map<String, Any>> {
        assertThat(response.statusCode()).isEqualTo(200)
        return JsonPath.read(response.body(), "$")
    }

    private fun number(row: Map<String, Any>, key: String): Long =
        (row.getValue(key) as Number).toLong()

    private fun count(table: String, column: String, id: Long): Long =
        jdbc.queryForObject("SELECT count(*) FROM $table WHERE $column = ?", Long::class.java, id)!!

    private fun assertEmpty(response: HttpResponse<String>, status: Int) {
        assertThat(response.statusCode()).isEqualTo(status)
        assertThat(response.body()).isEmpty()
    }

    companion object {
        private const val OUTFITS = "/api/outfits"
        private const val TEMPERATURE = "-123.123456789123456789"
        private const val WIND = "1.123456789123456789"
    }
}
