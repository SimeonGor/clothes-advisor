package ru.itmo.clothesadvisor.config

import java.net.URI
import java.net.http.HttpClient
import java.time.Duration
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@ConfigurationProperties("app.s3")
internal class S3Properties(
    val endpoint: String,
    val bucket: String,
    val iamToken: String,
) {
    init {
        require(bucket.isNotBlank() && iamToken.isNotBlank() && iamToken.all { it in '!'..'~' }) {
            "Object storage bucket and IAM token must be configured correctly"
        }
    }

    val endpointUri: URI = try {
        URI(endpoint).also {
            require(it.scheme in listOf("http", "https") && it.host != null && it.userInfo == null &&
                it.query == null && it.fragment == null)
        }
    } catch (_: IllegalArgumentException) {
        throw IllegalArgumentException("Object storage endpoint must be a valid HTTP(S) URL without credentials, query or fragment")
    } catch (_: java.net.URISyntaxException) {
        throw IllegalArgumentException("Object storage endpoint must be a valid HTTP(S) URL without credentials, query or fragment")
    }
}

@Configuration
@EnableConfigurationProperties(S3Properties::class)
internal class S3Configuration {
    @Bean(destroyMethod = "close")
    fun s3HttpClient(): HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build()
}
