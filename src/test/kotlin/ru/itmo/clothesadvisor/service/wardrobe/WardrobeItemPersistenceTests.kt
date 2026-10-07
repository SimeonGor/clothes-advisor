package ru.itmo.clothesadvisor.service.wardrobe

import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import ru.itmo.clothesadvisor.config.TestTimeConfiguration
import ru.itmo.clothesadvisor.dto.wardrobe.CreateWardrobeItemRequest
import ru.itmo.clothesadvisor.dto.wardrobe.WardrobeItemResponse
import ru.itmo.clothesadvisor.dto.wardrobe.UpdateWardrobeItemRequest
import ru.itmo.clothesadvisor.model.user.UserRole
import ru.itmo.clothesadvisor.repository.wardrobe.WardrobeItemRepository
import ru.itmo.clothesadvisor.service.user.AppUserService

@Testcontainers
@SpringBootTest
@Import(TestTimeConfiguration::class)
class WardrobeItemPersistenceTests {
    @Autowired
    private lateinit var items: WardrobeItemService

    @Autowired
    private lateinit var repository: WardrobeItemRepository

    @Autowired
    private lateinit var users: AppUserService

    @Autowired
    private lateinit var jdbc: JdbcTemplate

    @Autowired
    private lateinit var transactionManager: PlatformTransactionManager

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `outer transaction rollback restores both current and history`(delete: Boolean) {
        val (owner, original) = createWardrobeItem()
        assertThatThrownBy {
            TransactionTemplate(transactionManager).executeWithoutResult {
                if (delete) items.delete(owner, original.id, 1)
                else items.update(owner, original.id, update("Changed"))
                repository.flush()
                assertThat(history(original.id)).hasSize(1)
                assertThat(jdbc.queryForObject("SELECT count(*) FROM garment WHERE id = ?", Long::class.java, original.id))
                    .isEqualTo(if (delete) 0L else 1L)
                error("forced rollback")
            }
        }.isInstanceOf(IllegalStateException::class.java).hasMessage("forced rollback")
        assertThat(items.get(owner, original.id)).isEqualTo(original)
        assertThat(history(original.id)).isEmpty()
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `history conflict rolls back flushed mutation and preserves the existing snapshot`(delete: Boolean) {
        val (owner, original) = createWardrobeItem()
        jdbc.update("""
            INSERT INTO garment_history (garment_id, version, name, category_id, color, material, modified_at, archived_at)
            SELECT id, version, 'Existing history', category_id, color, material, modified_at, modified_at
            FROM garment WHERE id = ?
        """.trimIndent(), original.id)
        val existing = history(original.id)
        assertThatThrownBy {
            if (delete) items.delete(owner, original.id, 1)
            else items.update(owner, original.id, update("Changed"))
        }.isInstanceOf(DataIntegrityViolationException::class.java)
        assertThat(items.get(owner, original.id)).isEqualTo(original)
        assertThat(history(original.id)).isEqualTo(existing)
    }

    @Test
    fun `concurrent updates retain one winner and one exact previous snapshot`() {
        val (owner, original) = createWardrobeItem()
        val loaded = CyclicBarrier(2)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val attempts = listOf("First", "Second").map { name ->
                executor.submit(Callable {
                    try {
                        TransactionTemplate(transactionManager).apply { timeout = 20 }.executeWithoutResult {
                            assertThat(repository.findByIdAndOwnerId(original.id, owner)!!.version).isEqualTo(1)
                            loaded.await(10, TimeUnit.SECONDS)
                            items.update(owner, original.id, update(name))
                        }
                        name to null
                    } catch (failure: Exception) {
                        name to failure
                    }
                })
            }.map { it.get(30, TimeUnit.SECONDS) }
            val winner = attempts.single { it.second == null }.first
            assertThat(attempts.single { it.second != null }.second)
                .isInstanceOf(OptimisticLockingFailureException::class.java)
            assertThat(items.get(owner, original.id)).isEqualTo(original.copy(name = winner, version = 2))
            assertOriginalHistory(original)
        } finally {
            executor.shutdownNow()
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue()
        }
    }

    @Test
    fun `stale managed delete after committed update fails before writing duplicate history`() {
        val (owner, original) = createWardrobeItem()
        val loaded = CyclicBarrier(2)
        val committed = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val updater = executor.submit(Callable {
                try {
                    TransactionTemplate(transactionManager).apply { timeout = 20 }.executeWithoutResult {
                        assertThat(repository.findByIdAndOwnerId(original.id, owner)!!.version).isEqualTo(1)
                        loaded.await(10, TimeUnit.SECONDS)
                        items.update(owner, original.id, update("Winner"))
                    }
                } finally {
                    committed.countDown()
                }
            })
            val deleter = executor.submit(Callable {
                try {
                    TransactionTemplate(transactionManager).apply { timeout = 20 }.executeWithoutResult {
                        assertThat(repository.findByIdAndOwnerId(original.id, owner)!!.version).isEqualTo(1)
                        loaded.await(10, TimeUnit.SECONDS)
                        assertThat(committed.await(10, TimeUnit.SECONDS)).isTrue()
                        items.delete(owner, original.id, 1)
                    }
                    null
                } catch (failure: Exception) {
                    failure
                }
            })
            updater.get(30, TimeUnit.SECONDS)
            assertThat(deleter.get(30, TimeUnit.SECONDS)).isInstanceOf(OptimisticLockingFailureException::class.java)
            assertThat(items.get(owner, original.id)).isEqualTo(original.copy(name = "Winner", version = 2))
            assertOriginalHistory(original)
        } finally {
            executor.shutdownNow()
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue()
        }
    }

    private fun createWardrobeItem(): Pair<Long, WardrobeItemResponse> {
        val owner = users.create(UUID.randomUUID().toString(), "test-hash", UserRole.USER).id!!
        return owner to items.create(owner, CreateWardrobeItemRequest("Shirt", 1, "White", "Cotton"))
    }

    private fun update(name: String) = UpdateWardrobeItemRequest(1, name, 1, "White", "Cotton")

    private fun assertOriginalHistory(original: WardrobeItemResponse) {
        assertThat(history(original.id)).containsExactly(mapOf(
            "version" to 1L, "name" to original.name, "category_id" to original.categoryId,
            "color" to original.color, "material" to original.material,
            "modified_at" to original.modifiedAt, "archived_at" to TestTimeConfiguration.FIXED_TIME,
        ))
    }

    private fun history(id: Long): List<Map<String, Any>> = jdbc.query(
        "SELECT * FROM garment_history WHERE garment_id = ? ORDER BY version",
        { row, _ -> mapOf(
            "version" to row.getLong("version"), "name" to row.getString("name"),
            "category_id" to row.getLong("category_id"), "color" to row.getString("color"),
            "material" to row.getString("material"), "modified_at" to row.getTimestamp("modified_at").toInstant(),
            "archived_at" to row.getTimestamp("archived_at").toInstant(),
        ) }, id,
    )

    companion object {
        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:18-alpine")

        @JvmStatic
        @DynamicPropertySource
        fun postgresProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
        }
    }
}
