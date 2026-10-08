package ru.itmo.clothesadvisor.config

import java.net.URI
import java.net.http.HttpClient
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyStore
import java.time.Clock
import java.time.Duration
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import ru.itmo.clothesadvisor.client.ai.OpenAiOutfitClient
import ru.itmo.clothesadvisor.client.ai.ChatGptCredentials
import tools.jackson.databind.json.JsonMapper

@Configuration
internal class OpenAiConfiguration {
    @Bean(destroyMethod = "close")
    fun openAiOutfitClient(
        mapper: JsonMapper,
        clock: Clock,
        @Value("\${app.openai.credentials-directory:}") directory: String,
        @Value("\${app.openai.model:}") model: String,
        @Value("\${app.openai.endpoint:https://api.openai.com/v1/responses}") endpoint: URI,
        @Value("\${app.openai.timeout:60s}") timeout: Duration,
        @Value("\${app.openai.trust-store:}") trustStore: String,
    ) = OpenAiOutfitClient(httpClient(trustStore), mapper, ChatGptCredentials(mapper, clock, directory), model, endpoint, timeout)

    internal fun httpClient(trustStore: String): HttpClient? = try {
        val builder = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
        if (trustStore.isNotBlank()) {
            val keys = KeyStore.getInstance("PKCS12")
            Files.newInputStream(Path.of(trustStore)).use { keys.load(it, "changeit".toCharArray()) }
            val managers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            managers.init(keys)
            builder.sslContext(SSLContext.getInstance("TLS").apply { init(null, managers.trustManagers, null) })
        }
        builder.build()
    } catch (_: java.io.IOException) { null }
      catch (_: java.security.GeneralSecurityException) { null }
      catch (_: java.nio.file.InvalidPathException) { null }
      catch (_: SecurityException) { null }
}
