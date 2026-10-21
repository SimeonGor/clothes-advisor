package ru.itmo.clothesadvisor.service.wardrobe

import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mockingDetails
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import ru.itmo.clothesadvisor.config.PostgresIntegrationTest
import ru.itmo.clothesadvisor.config.TestTimeConfiguration
import ru.itmo.clothesadvisor.config.insertUser
import ru.itmo.clothesadvisor.dto.wardrobe.CreateWardrobeItemRequest
import ru.itmo.clothesadvisor.dto.wardrobe.UpdateWardrobeItemRequest
import ru.itmo.clothesadvisor.dto.wardrobe.WardrobeItemResponse
import ru.itmo.clothesadvisor.model.user.UserRole
import ru.itmo.clothesadvisor.model.wardrobe.WardrobeItem
import ru.itmo.clothesadvisor.repository.wardrobe.WardrobeItemRepository
import ru.itmo.clothesadvisor.service.user.AppUserService

@SpringBootTest
@Import(TestTimeConfiguration::class)
class WardrobeItemPersistenceTests : PostgresIntegrationTest() {
    @Autowired private lateinit var items: WardrobeItemService

    @MockitoSpyBean private lateinit var repository: WardrobeItemRepository

    @Autowired private lateinit var users: AppUserService

    @Autowired private lateinit var jdbc: JdbcTemplate

    @Autowired private lateinit var transactionManager: PlatformTransactionManager

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `outer transaction rollback restores both current and history`(delete: Boolean) {

        // given
        val (owner, original) = createWardrobeItem()

        // when
        val rollbackFailure = catchThrowable {
            TransactionTemplate(transactionManager).executeWithoutResult {
                if (delete) items.delete(owner, original.id, 1)
                else items.update(owner, original.id, update("Changed"))

                assertThat(history(original.id)).hasSize(1)
                assertThat(
                        jdbc.queryForObject(
                            "SELECT count(*) FROM wardrobe_item WHERE id = ?",
                            Long::class.java,
                            original.id,
                        ),
                    )
                    .isEqualTo(if (delete) 0L else 1L)
                error("forced rollback")
            }
        }

        // then
        assertThat(rollbackFailure)
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessage("forced rollback")

        // when
        val restoredItem = items.get(owner, original.id)

        // then
        assertThat(restoredItem).isEqualTo(original)
        assertThat(history(original.id)).isEmpty()
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `history conflict rolls back written mutation and preserves the existing snapshot`(
        delete: Boolean,
    ) {

        // given
        val (owner, original) = createWardrobeItem()
        jdbc.update(
            """
            INSERT INTO wardrobe_item_history (wardrobe_item_id, version, name, category_id, color, material, modified_at, archived_at)
            SELECT id, version, 'Existing history', category_id, color, material, modified_at, modified_at
            FROM wardrobe_item WHERE id = ?
            """
                .trimIndent(),
            original.id,
        )
        val existing = history(original.id)

        // when
        val historyConflict = catchThrowable {
            if (delete) items.delete(owner, original.id, 1)
            else items.update(owner, original.id, update("Changed"))
        }

        // then
        assertThat(historyConflict).isInstanceOf(DataIntegrityViolationException::class.java)

        // when
        val preservedItem = items.get(owner, original.id)

        // then
        assertThat(preservedItem).isEqualTo(original)
        assertThat(history(original.id)).isEqualTo(existing)
    }

    @Test
    fun `concurrent updates retain one winner and one exact previous snapshot`() {

        // given
        val (owner, original) = createWardrobeItem()
        val loaded = CyclicBarrier(2)
        val synchronizeReads = AtomicBoolean(true)
        doAnswer { call ->
                val item =
                    mockingDetails(call.mock).mockCreationSettings.defaultAnswer.answer(call)
                        as WardrobeItem
                if (synchronizeReads.get()) {
                    assertThat(item.version).isEqualTo(1)
                    loaded.await(10, TimeUnit.SECONDS)
                }
                item
            }
            .`when`(repository)
            .findByIdAndOwnerId(original.id, owner)
        val executor = Executors.newFixedThreadPool(2)
        try {

            // when
            val attempts =
                listOf("First", "Second")
                    .map { name ->
                        executor.submit(
                            Callable {
                                try {
                                    TransactionTemplate(transactionManager)
                                        .apply { timeout = 20 }
                                        .executeWithoutResult {
                                            items.update(owner, original.id, update(name))
                                        }
                                    name to null
                                } catch (failure: Exception) {
                                    name to failure
                                }
                            },
                        )
                    }
                    .map { it.get(30, TimeUnit.SECONDS) }
            synchronizeReads.set(false)

            // then
            val winner = attempts.single { it.second == null }.first
            assertThat(attempts.single { it.second != null }.second)
                .isInstanceOf(OptimisticLockingFailureException::class.java)

            // when
            val saved = items.get(owner, original.id)

            // then
            assertThat(saved)
                .isEqualTo(original.copy(name = winner, version = 2, modifiedAt = saved.modifiedAt))
            assertOriginalHistory(original)
        } finally {
            executor.shutdownNow()
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue()
        }
    }

    @Test
    fun `stale delete after committed update fails before writing duplicate history`() {

        // given
        val (owner, original) = createWardrobeItem()
        val loaded = CountDownLatch(1)
        val committed = CountDownLatch(1)
        val firstRead = AtomicBoolean(true)
        doAnswer { call ->
                val item =
                    mockingDetails(call.mock).mockCreationSettings.defaultAnswer.answer(call)
                        as WardrobeItem
                if (firstRead.compareAndSet(true, false)) {
                    assertThat(item.version).isEqualTo(1)
                    loaded.countDown()
                    assertThat(committed.await(10, TimeUnit.SECONDS)).isTrue()
                }
                item
            }
            .`when`(repository)
            .findByIdAndOwnerId(original.id, owner)
        val executor = Executors.newSingleThreadExecutor()
        try {

            // when
            val deleter =
                executor.submit(
                    Callable {
                        try {
                            TransactionTemplate(transactionManager)
                                .apply { timeout = 20 }
                                .executeWithoutResult {
                                    items.delete(owner, original.id, 1)
                                }
                            null
                        } catch (failure: Exception) {
                            failure
                        }
                    },
                )
            assertThat(loaded.await(10, TimeUnit.SECONDS)).isTrue()
            items.update(owner, original.id, update("Winner"))
            committed.countDown()

            // then
            assertThat(deleter.get(30, TimeUnit.SECONDS))
                .isInstanceOf(OptimisticLockingFailureException::class.java)

            // when
            val saved = items.get(owner, original.id)

            // then
            assertThat(saved)
                .isEqualTo(
                    original.copy(name = "Winner", version = 2, modifiedAt = saved.modifiedAt),
                )
            assertOriginalHistory(original)
        } finally {
            committed.countDown()
            executor.shutdownNow()
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue()
        }
    }

    private fun createWardrobeItem(): Pair<Long, WardrobeItemResponse> {
        val owner = jdbc.insertUser(UUID.randomUUID().toString(), "test-hash", UserRole.USER).id!!
        return owner to
            items.create(owner, CreateWardrobeItemRequest("Shirt", 1, "White", "Cotton"))
    }

    private fun update(name: String) = UpdateWardrobeItemRequest(1, name, 1, "White", "Cotton")

    private fun assertOriginalHistory(original: WardrobeItemResponse) {
        assertThat(history(original.id))
            .containsExactly(
                mapOf(
                    "version" to 1L,
                    "name" to original.name,
                    "category_id" to original.categoryId,
                    "color" to original.color,
                    "material" to original.material,
                    "modified_at" to original.modifiedAt,
                    "archived_at" to
                        jdbc
                            .queryForObject(
                                "SELECT modified_at FROM wardrobe_item WHERE id = ?",
                                java.sql.Timestamp::class.java,
                                original.id,
                            )!!
                            .toInstant(),
                ),
            )
    }

    private fun history(id: Long): List<Map<String, Any>> =
        jdbc.query(
            "SELECT * FROM wardrobe_item_history WHERE wardrobe_item_id = ? ORDER BY version",
            { row, _ ->
                mapOf(
                    "version" to row.getLong("version"),
                    "name" to row.getString("name"),
                    "category_id" to row.getLong("category_id"),
                    "color" to row.getString("color"),
                    "material" to row.getString("material"),
                    "modified_at" to row.getTimestamp("modified_at").toInstant(),
                    "archived_at" to row.getTimestamp("archived_at").toInstant(),
                )
            },
            id,
        )
}
