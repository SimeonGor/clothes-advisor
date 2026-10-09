package ru.itmo.clothesadvisor.config

import java.net.URI
import java.time.Clock
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import ru.itmo.clothesadvisor.client.iam.IAM_TOKEN_ENDPOINT
import ru.itmo.clothesadvisor.client.iam.IamTokenClient
import ru.itmo.clothesadvisor.client.iam.IamTokenProvider
import tools.jackson.databind.json.JsonMapper

@ConfigurationProperties("app.iam")
internal class IamProperties(
    val serviceAccountKeyFile: String = "",
    endpoint: String = IAM_TOKEN_ENDPOINT,
) {
    val endpointUri: URI =
        try {
            val uri = URI(endpoint)
            val usesHttps = uri.scheme == "https"
            val usesLocalHttp =
                uri.scheme == "http" && uri.host in listOf("127.0.0.1", "localhost", "[::1]")
            require(
                uri.host != null &&
                    (usesHttps || usesLocalHttp) &&
                    uri.userInfo == null &&
                    uri.query == null &&
                    uri.fragment == null,
            )
            uri
        } catch (_: Exception) {
            throw IllegalArgumentException(
                "IAM endpoint must use HTTPS or local HTTP without credentials, query or fragment",
            )
        }
}

@Configuration
@EnableConfigurationProperties(IamProperties::class)
internal class IamConfiguration {
    @Bean(destroyMethod = "close")
    fun iamTokenClient(
        properties: IamProperties,
        mapper: JsonMapper,
        clock: Clock,
    ): IamTokenClient =
        IamTokenClient(properties.serviceAccountKeyFile, mapper, clock, properties.endpointUri)

    @Bean
    fun iamTokenProvider(client: IamTokenClient, clock: Clock): IamTokenProvider =
        IamTokenProvider(client, clock)
}
