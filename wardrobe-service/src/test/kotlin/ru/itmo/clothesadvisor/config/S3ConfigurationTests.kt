package ru.itmo.clothesadvisor.config

import java.net.http.HttpClient
import java.time.Clock
import java.time.Duration
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import ru.itmo.clothesadvisor.client.iam.IamTokenProvider
import ru.itmo.clothesadvisor.storage.wardrobe.S3PhotoStorage
import tools.jackson.databind.json.JsonMapper

class S3ConfigurationTests {
    @Test
    fun `configuration starts without reading the key and wires the adapter with safe HTTP settings`() {

        // given
        val runner =
            ApplicationContextRunner()
                .withUserConfiguration(
                    S3Configuration::class.java,
                    IamConfiguration::class.java,
                    S3PhotoStorage::class.java,
                )
                .withBean(Clock::class.java, { Clock.systemUTC() })
                .withBean(JsonMapper::class.java, { JsonMapper.builder().build() })
                .withPropertyValues(
                    "app.s3.endpoint=https://storage.yandexcloud.net",
                    "app.s3.bucket=test-bucket",
                    "app.iam.service-account-key-file=/nonexistent/private-key.json",
                )

        // when
        runner.run { context ->

            // then
            assertThat(context).hasNotFailed()
            assertThat(context).hasSingleBean(S3PhotoStorage::class.java)
            assertThat(context).hasSingleBean(IamTokenProvider::class.java)
            val client = context.getBean(HttpClient::class.java)
            assertThat(client.followRedirects()).isEqualTo(HttpClient.Redirect.NEVER)
            assertThat(client.connectTimeout()).contains(Duration.ofSeconds(5))
        }
    }

    @ParameterizedTest
    @ValueSource(
        strings =
            [
                "not a url private-value",
                "ftp://private-host",
                "https://user:private-value@host",
                "https://host?token=private-value",
                "https://host#private-value",
            ],
    )
    fun `invalid endpoint errors omit their values and causes`(endpoint: String) {

        // given
        val bucket = "bucket"

        // when
        val failure = catchThrowable { S3Properties(endpoint, bucket) }

        // then
        assertThat(failure).isInstanceOfSatisfying(IllegalArgumentException::class.java) {
            assertThat(it.message).doesNotContain("private", endpoint)
            assertThat(it.cause).isNull()
        }
    }
}
