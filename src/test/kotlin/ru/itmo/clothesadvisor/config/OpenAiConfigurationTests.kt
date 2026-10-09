package ru.itmo.clothesadvisor.config

import java.net.http.HttpClient
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyStore
import java.time.Clock
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.boot.convert.ApplicationConversionService
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import ru.itmo.clothesadvisor.client.ai.AiOutfitException
import ru.itmo.clothesadvisor.client.ai.ChatGptCredentialFixture
import ru.itmo.clothesadvisor.client.ai.OpenAiOutfitClient
import tools.jackson.databind.json.JsonMapper

class OpenAiConfigurationTests {
    @TempDir lateinit var temporary: Path

    @Test
    fun `PKCS12 trust is isolated to the OpenAI client and never follows redirects`() {

        // given
        val global = SSLContext.getDefault()
        val property = System.getProperty("javax.net.ssl.trustStore")
        val managers =
            TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply {
                init(null as KeyStore?)
            }
        val publicCa =
            (managers.trustManagers.first { it is X509TrustManager } as X509TrustManager)
                .acceptedIssuers
                .first()
        val store = temporary.resolve("public-ca.p12")
        val keys =
            KeyStore.getInstance("PKCS12").apply {
                load(null, "changeit".toCharArray())
                setCertificateEntry("public-ca", publicCa)
            }
        Files.newOutputStream(store).use { keys.store(it, "changeit".toCharArray()) }
        val configuration = OpenAiConfiguration()

        // when
        val dedicated = requireNotNull(configuration.httpClient(store.toString()))
        dedicated.use {

            // then
            assertThat(it.sslContext()).isNotSameAs(global)
            assertThat(it.followRedirects()).isEqualTo(HttpClient.Redirect.NEVER)
            assertThat(SSLContext.getDefault()).isSameAs(global)
            assertThat(System.getProperty("javax.net.ssl.trustStore")).isEqualTo(property)

            // when
            requireNotNull(configuration.httpClient("")).use { ordinary ->

                // then
                assertThat(ordinary.sslContext()).isSameAs(global)
            }
        }
    }

    @Test
    fun `invalid explicit truststore keeps Spring context available and disables only AI`() {

        // given
        val credentials =
            ChatGptCredentialFixture(Files.createDirectory(temporary.resolve("credentials")))
        val invalid = temporary.resolve("invalid.p12")
        Files.writeString(invalid, "not a truststore")
        val runner =
            ApplicationContextRunner()
                .withUserConfiguration(OpenAiConfiguration::class.java)
                .withInitializer {
                    it.beanFactory.conversionService =
                        ApplicationConversionService.getSharedInstance()
                }
                .withBean(JsonMapper::class.java, { credentials.mapper })
                .withBean(Clock::class.java, { credentials.clock })
                .withPropertyValues(
                    "app.openai.credentials-directory=${credentials.directory}",
                    "app.openai.model=test-model",
                )

        // when
        runner.withPropertyValues("app.openai.trust-store=").run { context ->

            // then
            assertThat(context).hasNotFailed()

            // when: availability check must succeed in this context
            context.getBean(OpenAiOutfitClient::class.java).requireAvailable()
        }
        for (path in listOf(invalid, temporary.resolve("missing.p12"))) {

            // given: an invalid or missing explicit truststore

            // when
            runner.withPropertyValues("app.openai.trust-store=$path").run { context ->

                // then
                assertThat(context).hasNotFailed()
                val client = context.getBean(OpenAiOutfitClient::class.java)

                // when
                val failure = catchThrowable { client.requireAvailable() }

                // then
                assertThat(failure).isInstanceOfSatisfying(AiOutfitException::class.java) {
                    assertThat(it.status).isEqualTo(503)
                    assertThat(it.message).isNull()
                    assertThat(it.cause).isNull()
                }
            }
        }
    }
}
