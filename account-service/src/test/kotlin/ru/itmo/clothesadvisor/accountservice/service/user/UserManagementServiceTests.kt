package ru.itmo.clothesadvisor.accountservice.service.user

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import reactor.core.publisher.Mono
import reactor.test.StepVerifier
import ru.itmo.clothesadvisor.accountservice.service.auth.PasswordService

internal class UserManagementServiceTests {
    private val passwords = mock(PasswordService::class.java)
    private val admins = mock(AdminUserService::class.java)
    private val service = UserManagementService(passwords, admins)

    @Test
    fun `empty bootstrap performs no hashing or database access`() {
        StepVerifier.create(service.bootstrap("", "")).verifyComplete()
        StepVerifier.create(service.bootstrap(" ", " ")).verifyComplete()
        verifyNoInteractions(passwords, admins)
    }

    @Test
    fun `bootstrap drops potentially sensitive infrastructure error causes`() {
        `when`(passwords.encode("initial-password")).thenReturn(Mono.just("encoded-secret"))
        `when`(admins.bootstrap("admin", "encoded-secret"))
            .thenReturn(Mono.error(IllegalStateException("initial-password encoded-secret")))

        StepVerifier.create(service.bootstrap("admin", "initial-password")).expectErrorSatisfies {
            assertThat(it).isInstanceOf(BootstrapAdminException::class.java)
            assertThat(it.message).isEqualTo("Initial administrator setup failed")
            assertThat(it.cause).isNull()
            assertThat(it.stackTraceToString()).doesNotContain("initial-password", "encoded-secret")
        }.verify()
    }
}
