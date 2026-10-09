package ru.itmo.clothesadvisor.config

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class IamConfigurationTests {
    @ParameterizedTest
    @ValueSource(
        strings =
            [
                "http://127.0.0.1:123/tokens",
                "http://localhost:123/tokens",
                "http://[::1]:123/tokens",
                "https://iam.api.cloud.yandex.net/iam/v1/tokens",
            ],
    )
    fun `IAM permits HTTPS and explicit local HTTP endpoints`(endpoint: String) {

        // given
        val keyFile = "/nonexistent/key.json"

        // when
        val properties = IamProperties(keyFile, endpoint)

        // then
        assertThat(properties.endpointUri.toString()).isEqualTo(endpoint)
    }

    @ParameterizedTest
    @ValueSource(
        strings =
            [
                "http://private-host/tokens",
                "ftp://private-host/tokens",
                "https://user:private-password@host/tokens",
                "https://host/tokens?secret=private",
                "https://host/tokens#private",
                "private invalid URI",
            ],
    )
    fun `unsafe IAM endpoint is rejected without reflecting its value`(endpoint: String) {

        // given
        val keyFile = "/nonexistent/key.json"

        // when
        val failure = catchThrowable { IamProperties(keyFile, endpoint) }

        // then
        assertThat(failure).isInstanceOfSatisfying(IllegalArgumentException::class.java) {
            assertThat(it.message).doesNotContain("private", endpoint)
            assertThat(it.cause).isNull()
        }
    }
}
