package ru.itmo.clothesadvisor.client.ai

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.attribute.PosixFilePermissions
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tools.jackson.databind.json.JsonMapper

internal class ChatGptCredentialFixture(path: Path) {
    val directory: Path = path.toRealPath()
    val mapper: JsonMapper = JsonMapper.builder().build()
    val clock: Clock = Clock.fixed(Instant.parse("2026-10-01T00:00:00Z"), ZoneOffset.UTC)
    val values = mutableMapOf<String, Any>(
        "client_id" to "issued-test-client", "issuer" to "https://auth.openai.com", "subject" to "test-subject",
        "access_token" to "synthetic-test-token", "scopes" to listOf("resource.invoke", "chatgpt.tokens.use.direct"),
        "expires_at" to "2026-10-01T01:00:00Z",
    )

    init {
        Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"))
        write("registration.json", """{"client_id":"issued-test-client"}""")
        save()
    }

    fun save() = write("credentials.json", mapper.writeValueAsString(values))
    fun write(name: String, content: String) {
        val temporary = Files.createTempFile(directory, "synthetic-", ".json",
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
        Files.writeString(temporary, content)
        Files.move(temporary, directory.resolve(name), ATOMIC_MOVE, REPLACE_EXISTING)
    }
    fun reader() = ChatGptCredentials(mapper, clock, directory.toString())
}

class ChatGptCredentialsTests {
    @TempDir lateinit var temporary: Path

    @Test
    fun `each read observes latest atomically replaced token and leaves files unchanged`() {
        val fixture = ChatGptCredentialFixture(temporary)
        val reader = fixture.reader()
        val original = Files.readAllBytes(fixture.directory.resolve("credentials.json"))
        assertThat(reader.accessToken()).isEqualTo("synthetic-test-token")
        assertThat(Files.readAllBytes(fixture.directory.resolve("credentials.json"))).isEqualTo(original)
        fixture.values["access_token"] = "replacement-test-token"
        fixture.save()
        assertThat(reader.accessToken()).isEqualTo("replacement-test-token")
        fixture.values["expires_at"] = fixture.clock.instant().toString()
        fixture.save()
        unavailable { reader.accessToken() }
    }

    @Test
    fun `identity scope expiry and strict bounded JSON failures are sanitized`() {
        val fixture = ChatGptCredentialFixture(temporary)
        for ((key, value) in listOf(
            "issuer" to "https://untrusted.invalid", "subject" to " ", "client_id" to "another-client",
            "access_token" to "", "scopes" to listOf("resource.invoke"), "scopes" to "resource.invoke chatgpt.tokens.use.direct",
            "expires_at" to "2026-09-30T00:00:00Z", "expires_at" to "private invalid time",
        )) {
            val original = fixture.values.getValue(key)
            fixture.values[key] = value
            fixture.save()
            unavailable { fixture.reader().accessToken() }
            fixture.values[key] = original
        }
        val valid = fixture.mapper.writeValueAsString(fixture.values)
        for (invalid in listOf("private malformed credentials", "null", "$valid {}",
            valid.dropLast(1) + ",\"access_token\":\"duplicate\"}", valid + " ".repeat(65_537 - valid.length))) {
            fixture.write("credentials.json", invalid)
            unavailable { fixture.reader().accessToken() }
        }
        fixture.values["client_id"] = "dynamic_agent_client"
        fixture.save()
        fixture.write("registration.json", """{"client_id":"dynamic_agent_client"}""")
        unavailable { fixture.reader().accessToken() }
    }

    @Test
    fun `unconfigured missing public and symlinked storage is rejected`() {
        val fixture = ChatGptCredentialFixture(temporary)
        unavailable { ChatGptCredentials(fixture.mapper, fixture.clock, "").accessToken() }
        val credentials = fixture.directory.resolve("credentials.json")
        Files.delete(credentials)
        unavailable { fixture.reader().accessToken() }
        fixture.save()
        Files.setPosixFilePermissions(credentials, PosixFilePermissions.fromString("rw-r--r--"))
        unavailable { fixture.reader().accessToken() }
        fixture.save()
        Files.setPosixFilePermissions(fixture.directory, PosixFilePermissions.fromString("rwxr-xr-x"))
        unavailable { fixture.reader().accessToken() }
        Files.setPosixFilePermissions(fixture.directory, PosixFilePermissions.fromString("rwx------"))
        val target = fixture.directory.resolve("target.json")
        Files.move(credentials, target)
        Files.createSymbolicLink(credentials, target)
        unavailable { fixture.reader().accessToken() }
        Files.delete(credentials)
        Files.move(target, credentials)
        val link = fixture.directory.resolve("parent-link")
        Files.createSymbolicLink(link, fixture.directory)
        unavailable { ChatGptCredentials(fixture.mapper, fixture.clock, link.toString()).accessToken() }
    }

    private fun unavailable(action: () -> Any) {
        assertThatThrownBy { action() }.isInstanceOfSatisfying(AiOutfitException::class.java) {
            assertThat(it.status).isEqualTo(503)
            assertThat(it.message).isNull()
            assertThat(it.cause).isNull()
        }
    }
}
