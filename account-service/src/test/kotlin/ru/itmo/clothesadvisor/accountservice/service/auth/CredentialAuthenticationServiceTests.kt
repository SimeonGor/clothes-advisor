package ru.itmo.clothesadvisor.accountservice.service.auth

import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.verifyNoMoreInteractions
import org.mockito.Mockito.`when`
import org.springframework.dao.DataAccessResourceFailureException
import reactor.core.publisher.Mono
import reactor.test.StepVerifier
import ru.itmo.clothesadvisor.accountservice.config.PasswordConfiguration
import ru.itmo.clothesadvisor.accountservice.model.user.AppUser
import ru.itmo.clothesadvisor.accountservice.model.user.UserRole
import ru.itmo.clothesadvisor.accountservice.model.user.UserStatus
import ru.itmo.clothesadvisor.accountservice.repository.user.AppUserRepository
import tools.jackson.databind.json.JsonMapper

class CredentialAuthenticationServiceTests {
    private val repository = mock(AppUserRepository::class.java)
    private val passwords = PasswordService(PasswordConfiguration().passwordEncoder())
    private val service = CredentialAuthenticationService(repository, passwords)

    @Test
    fun `authentication is deferred and returns the stored active user`() {

        // given
        val saved = user()
        `when`(repository.findByLogin("alice")).thenReturn(Mono.just(saved))

        // when
        val result = service.authenticate("alice", PASSWORD)

        // then
        verifyNoInteractions(repository)
        StepVerifier.create(result).assertNext { assertThat(it).isSameAs(saved) }.verifyComplete()
        verify(repository).findByLogin("alice")
        verifyNoMoreInteractions(repository)
    }

    @Test
    fun `missing wrong blocked placeholder and malformed credentials have the same safe error`() {

        // given
        `when`(repository.findByLogin("missing")).thenReturn(Mono.empty())
        `when`(repository.findByLogin("alice")).thenReturn(Mono.just(user()))
        `when`(repository.findByLogin("blocked")).thenReturn(Mono.just(user(status = UserStatus.BLOCKED)))
        `when`(repository.findByLogin("placeholder")).thenReturn(Mono.just(user(hash = "!")))
        `when`(repository.findByLogin("malformed")).thenReturn(
            Mono.just(user(hash = "{pbkdf2-sha256-600000}invalid")),
        )
        val attempts = listOf("missing" to PASSWORD, "alice" to "wrong password",
            "blocked" to PASSWORD, "placeholder" to PASSWORD, "malformed" to PASSWORD,
            "alice" to "p".repeat(129))

        for ((login, password) in attempts) {

            // when
            val result = service.authenticate(login, password)

            // then
            StepVerifier.create(result).expectErrorSatisfies {
                assertThat(it).isExactlyInstanceOf(InvalidCredentialsException::class.java)
                assertThat(it.message).isEqualTo("Invalid credentials")
                assertThat(it.cause).isNull()
                assertThat(it.toString()).doesNotContain(password, HASH)
            }.verify()
        }
    }

    @Test
    fun `database failure is not converted to invalid credentials`() {

        // given
        val failure = DataAccessResourceFailureException("Database unavailable")
        `when`(repository.findByLogin("alice")).thenReturn(Mono.error(failure))
        val passwords = mock(PasswordService::class.java)
        val service = CredentialAuthenticationService(repository, passwords)

        // when
        val result = service.authenticate("alice", PASSWORD)

        // then
        StepVerifier.create(result).expectErrorMatches { it === failure }.verify()
        verifyNoInteractions(passwords)
    }

    @Test
    fun `user JSON and string representation exclude the password hash`() {

        // given
        val saved = user()
        val mapper = JsonMapper.builder().findAndAddModules().build()

        // when
        val json = mapper.writeValueAsString(saved)

        // then
        assertThat(json).doesNotContain("passwordHash", HASH, PASSWORD)
        assertThat(mapper.readTree(json).get("login").asString()).isEqualTo("alice")
        assertThat(saved.toString()).doesNotContain(HASH, PASSWORD)
    }

    private fun user(hash: String = HASH, status: UserStatus = UserStatus.ACTIVE): AppUser {
        val now = Instant.parse("2026-01-01T01:02:03Z")
        return AppUser(7, "alice", hash, UserRole.USER, status, 1, now, now)
    }

    companion object {
        private const val PASSWORD = "correct password"
        private val HASH = requireNotNull(PasswordConfiguration().passwordEncoder().encode(PASSWORD))
    }
}
