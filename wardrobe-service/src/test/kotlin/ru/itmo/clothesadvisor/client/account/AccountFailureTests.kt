package ru.itmo.clothesadvisor.client.account

import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeoutException
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import ru.itmo.clothesadvisor.model.AccessDeniedException
import ru.itmo.clothesadvisor.model.EntityNotFoundException
import ru.itmo.clothesadvisor.model.access.AccountUnavailableException

class AccountFailureTests {
    @Test
    fun `wrapped domain denials retain their type without upstream details`() {
        // given
        val denials = listOf(EntityNotFoundException("private detail"),
            AccessDeniedException("private detail"))

        denials.forEach { denial ->
            val cause = ExecutionException(IllegalStateException("private wrapper", denial))

            // when
            val failures = listOf(accountFailure(cause), accountFailure(cause))

            // then
            failures.forEach { failure ->
                assertThat(failure).isExactlyInstanceOf(denial.javaClass).isNotSameAs(denial)
                assertThat(failure!!.message).isIn(null, "Access denied")
                assertThat(failure.cause).isNull()
            }
            assertThat(failures[0]).isNotSameAs(failures[1])
        }
    }

    @Test
    fun `unknown technical failures always produce fresh empty unavailable exceptions`() {
        // given
        val causes = listOf(
            IOException("private IO"), SocketTimeoutException("private timeout"),
            TimeoutException("private timeout"), IllegalStateException("private detail"),
            AccountUnavailableException(),
        )

        causes.forEach { cause ->
            val wrapped = ExecutionException(cause)

            // when
            val failures = listOf(accountFailure(wrapped), accountFailure(wrapped))

            // then
            failures.forEach { failure ->
                assertThat(failure).isExactlyInstanceOf(AccountUnavailableException::class.java)
                    .isNotSameAs(cause)
                assertThat(failure!!.message).isNull()
                assertThat(failure.cause).isNull()
            }
            assertThat(failures[0]).isNotSameAs(failures[1])
        }
    }

    @Test
    fun `breaker ignores only domain denials including wrapped causes`() {
        // given
        val predicate = AccountDomainFailurePredicate()
        val cases = listOf(
            EntityNotFoundException() to true,
            AccessDeniedException() to true,
            ExecutionException(AccessDeniedException()) to true,
            ExecutionException(EntityNotFoundException()) to true,
            AccountUnavailableException() to false,
            IOException("private detail") to false,
            ExecutionException(AccountUnavailableException()) to false,
        )

        cases.forEach { (cause, expected) ->
            // when
            val ignored = predicate.test(cause)

            // then
            assertThat(ignored).isEqualTo(expected)
        }
    }
}
