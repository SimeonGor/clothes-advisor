package ru.itmo.clothesadvisor.service

import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.Test
import reactor.core.scheduler.Schedulers

class BlockingOperationsTests {
    @Test
    fun `call is deferred and executes on bounded elastic for each subscription`() {
        // given
        val calls = AtomicInteger()
        val result = blockingCall {
            assertThat(Schedulers.isInNonBlockingThread()).isFalse()
            assertThat(Thread.currentThread().name).startsWith("boundedElastic-")
            calls.incrementAndGet()
        }
        assertThat(calls.get()).isZero()

        // when
        val first = result.subscribeOn(Schedulers.parallel()).block(Duration.ofSeconds(5))
        val second = result.subscribeOn(Schedulers.parallel()).block(Duration.ofSeconds(5))

        // then
        assertThat(first).isEqualTo(1)
        assertThat(second).isEqualTo(2)
    }

    @Test
    fun `action is deferred and completes without a value on bounded elastic`() {
        // given
        val worker = AtomicReference<String>()
        val result = blockingAction {
            assertThat(Schedulers.isInNonBlockingThread()).isFalse()
            worker.set(Thread.currentThread().name)
        }
        assertThat(worker.get()).isNull()

        // when
        val value = result.subscribeOn(Schedulers.parallel()).block(Duration.ofSeconds(5))

        // then
        assertThat(value).isNull()
        assertThat(worker.get()).startsWith("boundedElastic-")
    }

    @Test
    fun `call preserves the operation error`() {
        // given
        val error = IllegalStateException("call failed")
        val result = blockingCall<String> { throw error }

        // when
        val failure = catchThrowable { result.block(Duration.ofSeconds(5)) }

        // then
        assertThat(failure).isSameAs(error)
    }

    @Test
    fun `action preserves the operation error`() {
        // given
        val error = IllegalStateException("action failed")
        val result = blockingAction { throw error }

        // when
        val failure = catchThrowable { result.block(Duration.ofSeconds(5)) }

        // then
        assertThat(failure).isSameAs(error)
    }
}
