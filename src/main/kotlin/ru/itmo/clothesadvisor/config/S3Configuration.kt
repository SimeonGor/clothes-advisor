package ru.itmo.clothesadvisor.config

import java.net.URI
import java.time.Duration
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client

@ConfigurationProperties("app.s3")
internal class S3Properties(
    val endpoint: String,
    val region: String,
    val bucket: String,
    val accessKeyId: String,
    val secretAccessKey: String,
)

@Configuration
@EnableConfigurationProperties(S3Properties::class)
internal class S3Configuration {
    @Bean
    fun s3Client(properties: S3Properties): S3Client {
        require(listOf(properties.bucket, properties.region, properties.accessKeyId, properties.secretAccessKey)
            .all { it.isNotBlank() }) { "S3 bucket, region and credentials must be configured" }
        val endpoint = try {
            URI(properties.endpoint).also {
                require(it.scheme in listOf("http", "https") && it.host != null && it.userInfo == null)
            }
        } catch (_: IllegalArgumentException) {
            throw IllegalArgumentException("S3 endpoint must be a valid HTTP(S) URL without credentials")
        } catch (_: java.net.URISyntaxException) {
            throw IllegalArgumentException("S3 endpoint must be a valid HTTP(S) URL without credentials")
        }
        return S3Client.builder()
            .endpointOverride(endpoint)
            .region(Region.of(properties.region))
            .credentialsProvider(StaticCredentialsProvider.create(
                AwsBasicCredentials.create(properties.accessKeyId, properties.secretAccessKey),
            ))
            .serviceConfiguration { it.pathStyleAccessEnabled(true) }
            .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
            .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
            .httpClientBuilder(UrlConnectionHttpClient.builder()
                .connectionTimeout(Duration.ofSeconds(5)).socketTimeout(Duration.ofSeconds(30)))
            .overrideConfiguration { it.apiCallTimeout(Duration.ofSeconds(60)) }
            .build()
    }
}
