package refresher

import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.security.KeyStore
import javax.crypto.spec.SecretKeySpec
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

internal class TrustStoreTest {
    @TempDir lateinit var temporary: Path

    private fun records(name: String) = Records(Files.createDirectory(temporary.toRealPath().resolve(name),
        PosixFilePermissions.asFileAttribute(ownerDirectory)))
    private fun encode(store: KeyStore) = ByteArrayOutputStream().also { store.store(it, "changeit".toCharArray()) }.toByteArray()
    private fun store() = KeyStore.getInstance("PKCS12").apply { load(null, "changeit".toCharArray()) }

    @Test fun `trust-only PKCS12 is copied and loaded while absent optional trust is allowed`() {
        // given: a public certificate from the JDK, no account or credential files
        val source = records("private")
        val output = records("output")
        val defaults = KeyStore.getInstance(Path.of(System.getProperty("java.home"), "lib", "security", "cacerts").toFile(), "changeit".toCharArray())
        val store = store()
        val certificate = defaults.getCertificate(defaults.aliases().nextElement())
        repeat(100) { store.setCertificateEntry("synthetic-trust-$it", certificate) }
        val bytes = encode(store)
        assertTrue(bytes.size > MAX_BYTES)
        source.writeCertificates("trust.p12", bytes)
        Files.setPosixFilePermissions(source.directory.resolve("trust.p12"), PosixFilePermissions.fromString("rw-r--r--"))
        // when / then
        assertNull(initializeTrust(source, output, ""))
        assertNotNull(initializeTrust(source, output, "/run/chatgpt/trust.p12"))
        assertArrayEquals(bytes, output.certificates("trust.p12"))
        assertEquals(ownerFile, Files.getPosixFilePermissions(output.directory.resolve("trust.p12")))
    }

    @Test fun `key entries empty malformed stores and unsafe paths are rejected before publication`() {
        // given
        val source = records("private")
        val output = records("output")
        val privateStore = store().apply {
            setKeyEntry("private-key", SecretKeySpec(ByteArray(32) { 1 }, "AES"), "changeit".toCharArray(), null)
        }
        // when / then
        for (bytes in listOf(encode(privateStore), encode(store()), "invalid-pkcs12".toByteArray())) {
            source.writeBytes("trust.p12", bytes)
            assertThrows(Failure::class.java) { initializeTrust(source, output, "/run/chatgpt/trust.p12") }
            assertNull(output.bytes("trust.p12"))
        }
        for (path in listOf("/tmp/trust.p12", "/run/chatgpt/../trust.p12", "/run/chatgpt/credentials.json")) {
            assertThrows(Failure::class.java) { initializeTrust(source, output, path) }
        }
    }
}
