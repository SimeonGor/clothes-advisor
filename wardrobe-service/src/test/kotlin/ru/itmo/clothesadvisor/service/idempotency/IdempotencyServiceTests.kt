package ru.itmo.clothesadvisor.service.idempotency

import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.Mockito.RETURNS_DEFAULTS
import org.mockito.Mockito.any
import org.mockito.Mockito.mock
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.SimpleTransactionStatus
import ru.itmo.clothesadvisor.dto.wardrobe.WardrobeItemPhotoResponse
import ru.itmo.clothesadvisor.model.idempotency.IdempotencyCommand
import ru.itmo.clothesadvisor.model.idempotency.IdempotencyOperation
import ru.itmo.clothesadvisor.model.idempotency.IdempotencyOperation.CREATE_OUTFIT
import ru.itmo.clothesadvisor.repository.idempotency.IdempotencyCommandRepository
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.json.JsonMapper

class IdempotencyServiceTests {
    private val rows = ConcurrentHashMap<List<Any>, IdempotencyCommand>()
    private val sequence = AtomicLong()
    private var failureRecordingError: Throwable? = null
    private val commands = mock(IdempotencyCommandRepository::class.java) { call ->
        when (call.method.name) {
            "claim" -> {
                val scope = call.arguments.take(3)
                val row = IdempotencyCommand(sequence.incrementAndGet(), scope[0] as Long,
                    scope[1] as String, scope[2] as String, call.getArgument(3), "RUNNING",
                    null, null, Instant.EPOCH, Instant.EPOCH)
                if (rows.putIfAbsent(scope, row) == null) row else null
            }
            "findCommand" -> rows[call.arguments.toList()]
            "succeed", "fail" -> {
                if (call.method.name == "fail") failureRecordingError?.let { throw it }
                val entry = rows.entries.single { it.value.id == call.getArgument<Long>(0) }
                if (entry.value.state != "RUNNING") 0 else {
                    entry.setValue(if (call.method.name == "fail") entry.value.copy(state = "FAILED")
                        else entry.value.copy(state = "SUCCEEDED", responseJson = call.getArgument(1), responseStatus = call.getArgument(2)))
                    1
                }
            }
            else -> RETURNS_DEFAULTS.answer(call)
        }
    }
    private val transactions = mock(PlatformTransactionManager::class.java).also {
        `when`(it.getTransaction(any())).thenAnswer { SimpleTransactionStatus() }
    }
    private val mapper = JsonMapper.builder().findAndAddModules().build()
    private val service = IdempotencyService(commands, mapper, transactions)
    private val stringType = object : TypeReference<String>() {}
    private fun execute(key: String? = "key", payload: Any = listOf(7, "request"),
        actor: Long = 7, operation: IdempotencyOperation = CREATE_OUTFIT,
        files: List<ByteArray> = emptyList(), action: () -> String = { "saved" }): String =
        service.execute(actor, operation, key, payload, stringType, files) { it.commit(action) }

    @Test
    fun `no key retains direct action without command persistence`() {
        // given
        var calls = 0

        // when
        repeat(2) { execute(key = null) { (++calls).toString() } }

        // then
        assertThat(calls).isEqualTo(2)
        verifyNoInteractions(commands, transactions)
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "a\nb", "ключ"])
    fun `invalid key is rejected before persistence`(key: String) {
        // given: invalid key from the parameterized source

        // when
        val failure = catchThrowable { execute(key) }

        // then
        assertThat(failure).isInstanceOfSatisfying(IdempotencyException::class.java) {
            assertThat(it.code).isEqualTo("invalid_idempotency_key")
        }
        verifyNoInteractions(commands, transactions)
    }

    @Test
    fun `key length is limited to 255 printable characters`() {
        // given: an empty command store

        // when
        val accepted = execute("a".repeat(255))
        val rejected = catchThrowable { execute("a".repeat(256)) }

        // then
        assertThat(accepted).isEqualTo("saved")
        assertThat(rejected).isInstanceOf(IdempotencyException::class.java)
        assertThat(rows).hasSize(1)
    }

    @Test
    fun `successful typed photo snapshot survives service recreation with exact timestamps`() {
        // given
        val type = object : TypeReference<List<WardrobeItemPhotoResponse>>() {}
        val photos = listOf(WardrobeItemPhotoResponse(11, 7, "image/png", 3, Instant.parse("2026-01-02T03:04:05.123456Z")))
        val first = service.execute(7, CREATE_OUTFIT, "key", 7, type) { it.commit { photos } }
        val restarted = IdempotencyService(commands, mapper, transactions)

        // when
        val replay = restarted.execute(7, CREATE_OUTFIT, "key", 7, type) { error("Must replay") }

        // then
        assertThat(replay).isEqualTo(first)
        assertThat(replay.single().createdAt).isEqualTo(photos.single().createdAt)
    }

    @Test
    fun `payload target and ordered file boundaries participate in fingerprint`() {
        // given
        execute(files = listOf(byteArrayOf(1, 2), byteArrayOf(3)))

        // when
        val failures = listOf(
            catchThrowable { execute(payload = listOf(8, "request")) },
            catchThrowable { execute(files = listOf(byteArrayOf(1), byteArrayOf(2, 3))) },
            catchThrowable { execute(files = listOf(byteArrayOf(3), byteArrayOf(1, 2))) },
        )

        // then
        failures.forEach { assertThat((it as IdempotencyException).code).isEqualTo("idempotency_key_reused") }
        assertThat(execute(files = listOf(byteArrayOf(1, 2), byteArrayOf(3))) { error("Must replay") }).isEqualTo("saved")
    }

    @Test
    fun `actor and operation have separate command namespaces`() {
        // given
        execute()

        // when
        execute(actor = 8)
        execute(operation = IdempotencyOperation.CREATE_AI_OUTFIT)

        // then
        assertThat(rows).hasSize(3)
    }

    @Test
    fun `failed action never repeats and original failure survives failure recording outage`() {
        // given
        val original = IllegalStateException("action failed")
        val recording = IllegalStateException("database unavailable")
        failureRecordingError = recording

        // when
        val first = catchThrowable { execute { throw original } }
        val replay = catchThrowable { execute { error("Must not execute") } }

        // then
        assertThat(first).isSameAs(original)
        assertThat(first.suppressed).containsExactly(recording)
        assertThat((replay as IdempotencyException).code).isEqualTo("idempotency_in_progress")
    }

    @Test
    fun `failed state blocks same key while explicit new key executes`() {
        // given
        catchThrowable { execute { error("failed") } }

        // when
        val replay = catchThrowable { execute { error("Must not execute") } }
        val newAttempt = execute(key = "new")

        // then
        assertThat((replay as IdempotencyException).code).isEqualTo("idempotency_failed")
        assertThat(newAttempt).isEqualTo("saved")
    }

    @Test
    fun `concurrent request rejects immediately while action is running`() {
        // given
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        Executors.newSingleThreadExecutor().use { executor ->
            val running = executor.submit<String> {
                execute { entered.countDown(); check(release.await(10, TimeUnit.SECONDS)); "saved" }
            }
            try {
                check(entered.await(10, TimeUnit.SECONDS))

                // when
                val duplicate = catchThrowable { execute { error("Must not execute") } }

                // then
                assertThat((duplicate as IdempotencyException).code).isEqualTo("idempotency_in_progress")
            } finally {
                release.countDown()
            }
            assertThat(running.get(10, TimeUnit.SECONDS)).isEqualTo("saved")
            assertThat(rows).hasSize(1)
        }
    }
}
