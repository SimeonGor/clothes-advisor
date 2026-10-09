package ru.itmo.clothesadvisor.config

import java.net.http.HttpClient
import java.time.Duration
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import ru.itmo.clothesadvisor.storage.wardrobe.S3PhotoStorage

class S3ConfigurationTests {
    @Test
    fun `configuration binds IAM and wires the adapter with redirects disabled and a connect timeout`() {

        // given
        val runner =
            ApplicationContextRunner()
                .withUserConfiguration(S3Configuration::class.java, S3PhotoStorage::class.java)
                .withPropertyValues(
                    "app.s3.endpoint=https://storage.yandexcloud.net",
                    "app.s3.bucket=test-bucket",
                    "app.s3.iam-token=synthetic-test-token",
                )

        // when
        runner.run { context ->

            // then
            assertThat(context).hasNotFailed()
            assertThat(context).hasSingleBean(S3PhotoStorage::class.java)
            val client = context.getBean(HttpClient::class.java)
            assertThat(client.followRedirects()).isEqualTo(HttpClient.Redirect.NEVER)
            assertThat(client.connectTimeout()).contains(Duration.ofSeconds(5))
        }
    }

    @Test
    fun `invalid endpoint and credential errors omit their values and causes`() {
        for (endpoint in
            listOf(
                "not a url private-value",
                "ftp://private-host",
                "https://user:private-value@host",
                "https://host?token=private-value",
                "https://host#private-value",
            )) {

            // given: an invalid endpoint

            // when
            val failure = catchThrowable { S3Properties(endpoint, "bucket", "private-token") }

            // then
            assertThat(failure).isInstanceOfSatisfying(IllegalArgumentException::class.java) {
                assertThat(it.message).doesNotContain("private", endpoint)
                assertThat(it.cause).isNull()
            }
        }
        for (token in listOf("", "private-token\r\nInjected: value", "private token")) {

            // given: an invalid token

            // when
            val failure = catchThrowable {
                S3Properties("https://storage.yandexcloud.net", "bucket", token)
            }

            // then
            assertThat(failure).isInstanceOfSatisfying(IllegalArgumentException::class.java) {
                assertThat(it.message).doesNotContain("private", "Injected")
                assertThat(it.cause).isNull()
            }
        }
    }
}
