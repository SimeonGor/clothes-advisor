package ru.itmo.clothesadvisor.service.preferences

import java.sql.Timestamp
import java.util.concurrent.Callable
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.ArgumentMatchers.anyString
import org.mockito.ArgumentMatchers.eq
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
import ru.itmo.clothesadvisor.dto.preferences.PutStylePreferencesRequest
import ru.itmo.clothesadvisor.dto.user.CurrentUser
import ru.itmo.clothesadvisor.model.user.UserRole
import ru.itmo.clothesadvisor.repository.preferences.StylePreferencesRepository

@SpringBootTest
@Import(TestTimeConfiguration::class)
class StylePreferencesPersistenceTests : PostgresIntegrationTest() {
    @Autowired private lateinit var service: StylePreferencesService
    @Autowired private lateinit var jdbc: JdbcTemplate
    @Autowired private lateinit var transactions: PlatformTransactionManager
    @MockitoSpyBean private lateinit var repository: StylePreferencesRepository
    private val owner = CurrentUser(7, "owner", UserRole.USER)

    @Test
    fun `history retains exact previous snapshots and database owns technical timestamps`() {
        // given: deliberately different creation and modification dates
        service.put(owner, PutStylePreferencesRequest("Blue", "Wool", "Casual"))
        jdbc.update("UPDATE style_preferences SET created_at = '2025-01-01T00:00:00Z', modified_at = '2025-02-01T00:00:00Z' WHERE owner_id = 7")
        val previous = current()
        val before = jdbc.queryForObject("SELECT CURRENT_TIMESTAMP", Timestamp::class.java)!!

        // when
        val updated = service.put(owner, PutStylePreferencesRequest("Green", version = 1))
        val after = jdbc.queryForObject("SELECT CURRENT_TIMESTAMP", Timestamp::class.java)!!
        val saved = current()
        val archived = history().single()

        // then
        assertThat(archived - "archived_at").isEqualTo(previous)
        assertThat(archived["archived_at"]).isEqualTo(saved["modified_at"])
        assertThat(updated.createdAt).isEqualTo((previous.getValue("created_at") as Timestamp).toInstant())
        assertThat(updated.modifiedAt).isEqualTo((saved.getValue("modified_at") as Timestamp).toInstant())
        assertThat(updated.modifiedAt).isBetween(before.toInstant(), after.toInstant())
        assertThat(updated.version).isEqualTo(2)

        // when
        val unchanged = service.put(owner, PutStylePreferencesRequest("Green", version = 2))
        val stale = catchThrowable { service.put(owner, PutStylePreferencesRequest("Green", version = 1)) }

        // then
        assertThat(unchanged).isEqualTo(updated)
        assertThat(stale).isInstanceOf(OptimisticLockingFailureException::class.java)
        assertThat(current()).isEqualTo(saved)
        assertThat(history()).containsExactly(archived)

        // when
        service.put(owner, PutStylePreferencesRequest(version = 2))
        jdbc.update("DELETE FROM style_preferences WHERE owner_id = 7")

        // then
        assertThat(history().map { it - "archived_at" }).containsExactly(previous, saved)
    }

    @Test
    fun `history insert failure rolls back current row`() {
        // given
        val original = service.put(owner, PutStylePreferencesRequest("Blue"))
        jdbc.update(
            """
            INSERT INTO style_preferences_history
                (owner_id, version, preferred_colors_and_materials, avoidances, additional_wishes, created_at, modified_at)
            SELECT owner_id, version, preferred_colors_and_materials, avoidances, additional_wishes, created_at, modified_at
            FROM style_preferences WHERE owner_id = 7
            """,
        )
        val existing = history()

        // when
        val failure = catchThrowable { service.put(owner, PutStylePreferencesRequest("Green", version = 1)) }

        // then
        assertThat(failure).isInstanceOf(DataIntegrityViolationException::class.java)
        assertThat(service.get(owner)).isEqualTo(original)
        assertThat(history()).isEqualTo(existing)
    }

    @Test
    fun `outer rollback restores current row and removes newly written history`() {
        // given
        val original = service.put(owner, PutStylePreferencesRequest("Blue"))

        // when
        val failure = catchThrowable {
            TransactionTemplate(transactions).executeWithoutResult {
                service.put(owner, PutStylePreferencesRequest("Green", version = 1))
                assertThat(history()).hasSize(1)
                error("forced rollback")
            }
        }

        // then
        assertThat(failure).isInstanceOf(IllegalStateException::class.java).hasMessage("forced rollback")
        assertThat(service.get(owner)).isEqualTo(original)
        assertThat(history()).isEmpty()
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `concurrent writes have one winner without losing previous state`(update: Boolean) {
        // given
        if (update) service.put(owner, PutStylePreferencesRequest("Original"))
        val original = if (update) current() else null
        val barrier = CyclicBarrier(2)
        val synchronize = AtomicBoolean(true)
        if (update) {
            doAnswer { call ->
                val row = mockingDetails(call.mock).mockCreationSettings.defaultAnswer.answer(call)
                if (synchronize.get()) barrier.await(10, TimeUnit.SECONDS)
                row
            }.`when`(repository).findByOwnerId(7)
        } else {
            doAnswer { call ->
                barrier.await(10, TimeUnit.SECONDS)
                mockingDetails(call.mock).mockCreationSettings.defaultAnswer.answer(call)
            }.`when`(repository).create(eq(7L), anyString(), anyString(), anyString())
        }
        val executor = Executors.newFixedThreadPool(2)
        try {
            // when
            val attempts = listOf("First", "Second").map { text ->
                executor.submit(Callable {
                    val failure = catchThrowable {
                        TransactionTemplate(transactions).apply { timeout = 20 }.executeWithoutResult {
                            service.put(owner, PutStylePreferencesRequest(text, version = if (update) 1 else null))
                        }
                    }
                    text to failure
                })
            }.map { it.get(30, TimeUnit.SECONDS) }
            synchronize.set(false)

            // then
            val winner = attempts.single { it.second == null }.first
            assertThat(attempts.single { it.second != null }.second)
                .isInstanceOf(OptimisticLockingFailureException::class.java)
            val result = service.get(owner)
            assertThat(result.preferredColorsAndMaterials).isEqualTo(winner)
            assertThat(result.version).isEqualTo(if (update) 2L else 1L)
            if (update) assertThat(history().single() - "archived_at").isEqualTo(original)
            else assertThat(history()).isEmpty()
        } finally {
            synchronize.set(false)
            executor.shutdownNow()
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue()
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["preferred_colors_and_materials", "avoidances", "additional_wishes"])
    fun `database bounds apply to current and archived text`(column: String) {
        // given
        service.put(owner, PutStylePreferencesRequest())
        service.put(owner, PutStylePreferencesRequest("Blue", version = 1))

        // when
        val currentFailure = catchThrowable {
            jdbc.update("UPDATE style_preferences SET $column = ? WHERE owner_id = 7", "x".repeat(2001))
        }
        val historyFailure = catchThrowable {
            jdbc.update("UPDATE style_preferences_history SET $column = ? WHERE owner_id = 7", "x".repeat(2001))
        }

        // then
        assertThat(currentFailure).isInstanceOf(DataIntegrityViolationException::class.java)
        assertThat(historyFailure).isInstanceOf(DataIntegrityViolationException::class.java)
    }

    @ParameterizedTest
    @ValueSource(strings = ["style_preferences", "style_preferences_history"])
    fun `database rejects nonpositive versions`(table: String) {
        // given
        service.put(owner, PutStylePreferencesRequest())
        service.put(owner, PutStylePreferencesRequest("Blue", version = 1))

        // when
        val failure = catchThrowable { jdbc.update("UPDATE $table SET version = 0 WHERE owner_id = 7") }

        // then
        assertThat(failure).isInstanceOf(DataIntegrityViolationException::class.java)
    }

    private fun current() = jdbc.queryForMap("SELECT * FROM style_preferences WHERE owner_id = 7")

    private fun history() = jdbc.queryForList(
        "SELECT * FROM style_preferences_history WHERE owner_id = 7 ORDER BY version",
    )
}
