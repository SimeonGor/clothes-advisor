package ru.itmo.clothesadvisor.config

import java.math.BigDecimal
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
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.boot.convert.ApplicationConversionService
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.core.env.MapPropertySource
import ru.itmo.clothesadvisor.client.ai.AiSelectionException
import ru.itmo.clothesadvisor.client.ai.ChatGptCredentialFixture
import ru.itmo.clothesadvisor.client.ai.OpenAiSelectionClient
import ru.itmo.clothesadvisor.client.ai.SelectionGoal
import ru.itmo.clothesadvisor.dto.ai.ItemSelectionRequest
import ru.itmo.clothesadvisor.dto.ai.ItemSelectionWeather
import tools.jackson.databind.json.JsonMapper

class OpenAiConfigurationTests {
    @TempDir lateinit var temporary: Path

    @ParameterizedTest
    @ValueSource(strings = ["Keep \${known.prompt.value} literally", "Keep \${undefined.prompt.value} literally"])
    fun `startup preserves literal prompt placeholders and property source precedence`(text: String) {
        // given
        val runner = ApplicationContextRunner()
            .withUserConfiguration(OpenAiConfiguration::class.java)
            .withInitializer {
                it.beanFactory.conversionService = ApplicationConversionService.getSharedInstance()
                it.environment.propertySources.addLast(MapPropertySource("fallback", mapOf(
                    "app.openai.prompt.version" to 1,
                    "app.openai.prompt.text" to "Lower priority instructions",
                )))
            }
            .withBean(JsonMapper::class.java, { JsonMapper() })
            .withBean(Clock::class.java, { Clock.systemUTC() })
            .withPropertyValues(
                "spring.cloud.config.enabled=false",
                "known.prompt.value=expanded value",
                "app.openai.prompt.version=2",
                "app.openai.prompt.text=$text",
            )

        // when
        runner.run { context ->
            // then
            assertThat(context).hasNotFailed()
            assertThat(context).hasSingleBean(OpenAiSelectionClient::class.java)
            assertThat(context.getBean("outfitPromptSettings", SelectionPromptSettings::class.java).snapshot())
                .isEqualTo(SelectionPrompt(2, text))
            assertThat(context.getBean("capsulePromptSettings", SelectionPromptSettings::class.java).snapshot()).isNull()
        }
    }

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
                    "spring.cloud.config.enabled=false",
                    "app.openai.credentials-directory=${credentials.directory}",
                    "app.openai.model=test-model",
                    "app.openai.prompt.version=1",
                    "app.openai.prompt.text=Select an outfit",
                )

        // when
        runner.withPropertyValues("app.openai.trust-store=").run { context ->

            // then
            assertThat(context).hasNotFailed()

            assertThat(context).hasSingleBean(OpenAiSelectionClient::class.java)
        }
        for (path in listOf(invalid, temporary.resolve("missing.p12"))) {

            // given: an invalid or missing explicit truststore

            // when
            runner.withPropertyValues("app.openai.trust-store=$path").run { context ->

                // then
                assertThat(context).hasNotFailed()
                val client = context.getBean(OpenAiSelectionClient::class.java)

                // when
                val selection = ItemSelectionRequest(
                    ItemSelectionWeather(BigDecimal.ZERO, BigDecimal.ZERO, "NONE", "None"),
                    emptyList(),
                )
                val failure = catchThrowable { client.select(SelectionGoal.OUTFIT, selection) }

                // then
                assertThat(failure).isInstanceOfSatisfying(AiSelectionException::class.java) {
                    assertThat(it.status).isEqualTo(503)
                    assertThat(it.message).isNull()
                    assertThat(it.cause).isNull()
                }
            }
        }
    }
}
