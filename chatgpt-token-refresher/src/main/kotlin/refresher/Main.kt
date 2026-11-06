package refresher

import java.io.ByteArrayInputStream
import java.net.http.HttpClient
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyStore
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory

internal fun initializeTrust(privateRecords: Records, output: Records, configured: String): SSLContext? {
    if (configured.isBlank()) return null
    val path = Path.of(configured)
    if (path.parent != Path.of("/run/chatgpt") || !path.fileName.toString().endsWith(".p12")) fail("invalid_trust_store")
    val name = path.fileName.toString()
    try {
        val bytes = privateRecords.certificates(name) ?: fail("invalid_trust_store")
        val store = KeyStore.getInstance("PKCS12")
        store.load(ByteArrayInputStream(bytes), "changeit".toCharArray())
        val aliases = store.aliases().toList()
        if (aliases.isEmpty() || aliases.any { store.isKeyEntry(it) || !store.isCertificateEntry(it) }) fail("invalid_trust_store")
        val managers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        managers.init(store)
        val context = SSLContext.getInstance("TLS")
        context.init(null, managers.trustManagers, null)
        output.writeCertificates(name, bytes)
        return context
    } catch (_: Exception) { fail("invalid_trust_store") }
}

fun main() {
    val ready = Path.of("/tmp/chatgpt-refresher-ready")
    val stopping = AtomicBoolean(false)
    val finished = CountDownLatch(1)
    val mainThread = Thread.currentThread()
    // Never release a writer's lock from a shutdown thread while it may still write.
    val hook = Thread({ stopping.set(true); mainThread.interrupt(); finished.await() }, "refresher-shutdown")
    Runtime.getRuntime().addShutdownHook(hook)
    try {
        Files.deleteIfExists(ready)
        val privateRecords = Records(Path.of(System.getenv("CHATGPT_PRIVATE_DIR") ?: "/run/chatgpt-private"))
        val output = Records(Path.of(System.getenv("CHATGPT_OUTPUT_DIR") ?: "/run/chatgpt"))
        val context = initializeTrust(privateRecords, output, System.getenv("CHATGPT_TRUST_STORE") ?: "")
        val builder = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER)
        if (context != null) builder.sslContext(context)
        val refresher = Refresher(privateRecords, output, OAuth(builder.build()))
        var previousStatus: String? = null
        while (!stopping.get()) {
            val status = refresher.cycle()
            if (status != previousStatus) { println("ChatGPT credentials: $status"); previousStatus = status }
            // Initialization is independent of a valid login: the consumer retains its 503 behavior.
            if (!Files.exists(ready)) Files.createFile(ready)
            try { Thread.sleep(30_000) } catch (_: InterruptedException) { stopping.set(true) }
        }
    } catch (_: Exception) {
        System.err.println("ChatGPT refresher: initialization_failed")
    } finally {
        try { Files.deleteIfExists(ready) } finally {
            finished.countDown()
            try { Runtime.getRuntime().removeShutdownHook(hook) } catch (_: IllegalStateException) { /* stopping */ }
        }
    }
}
