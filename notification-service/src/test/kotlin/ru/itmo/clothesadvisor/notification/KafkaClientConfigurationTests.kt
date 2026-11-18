package ru.itmo.clothesadvisor.notification

import java.nio.file.Path
import kotlin.io.path.writeText
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.kafka.core.ConsumerFactory

class KafkaClientConfigurationTests {
    @Test
    fun `only credentials from the client file reach the consumer factory`(@TempDir directory: Path) {
        // given
        val credentials = directory.resolve("client.properties")
        credentials.writeText(
            """
            bootstrap.servers=ignored:9092
            security.protocol=PLAINTEXT
            max.poll.records=-1
            sasl.jaas.config=test-jaas
            ssl.truststore.certificates=test-ca
            """.trimIndent(),
        )

        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(KafkaAutoConfiguration::class.java))
            .withInitializer(ConfigDataApplicationContextInitializer())
            .withPropertyValues(
                "spring.config.location=file:../config-repository/notification-service/application.yml",
                "spring.config.additional-location=${credentials.toUri()}",
            )
            .run { context ->
                // when
                val config = context.getBean(ConsumerFactory::class.java).configurationProperties
                val brokers = context.environment.getRequiredProperty("spring.kafka.bootstrap-servers").split(',')
                val pollLimit = context.environment.getRequiredProperty("spring.kafka.consumer.max-poll-records", Int::class.java)

                // then
                assertThat(brokers).doesNotContain("ignored:9092")
                assertThat(pollLimit).isPositive()
                assertThat(config["bootstrap.servers"]).isEqualTo(brokers)
                assertThat(config["security.protocol"]).isEqualTo("SASL_SSL")
                assertThat(config["max.poll.records"]).isEqualTo(pollLimit)
                assertThat(config["sasl.jaas.config"]).isEqualTo("test-jaas")
                assertThat(config["ssl.truststore.certificates"]).isEqualTo("test-ca")
            }
    }
}
