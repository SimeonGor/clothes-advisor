package ru.itmo.clothesadvisor.client.file

import java.nio.file.Files
import java.nio.file.Path
import org.springframework.beans.factory.annotation.Value
import org.springframework.cloud.client.loadbalancer.reactive.ReactorLoadBalancerExchangeFilterFunction
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpHeaders
import org.springframework.web.reactive.function.client.WebClient
import ru.itmo.clothesadvisor.config.LiveClientSettings

@Configuration(proxyBeanMethods = false)
internal class FileClientConfiguration {
    @Bean
    fun fileClient(
        builder: WebClient.Builder,
        loadBalancer: ReactorLoadBalancerExchangeFilterFunction,
        settings: LiveClientSettings,
        @Value("\${app.file-client.password-file:/run/secrets/file-wardrobe-password}") passwordFile: String,
    ): FileClient {
        val password = try {
            Files.readString(Path.of(passwordFile)).removeSuffix("\n").removeSuffix("\r")
        } catch (_: Exception) {
            throw IllegalStateException("File service account password is unreadable")
        }
        require(password.isNotBlank()) { "File service account password is blank" }
        val credentials = HttpHeaders.encodeBasicAuth("wardrobe-service", password, Charsets.UTF_8)
        val fileBuilder = builder.clone().baseUrl("http://file-service").filter(loadBalancer)
            .defaultHeaders { it.setBasicAuth(credentials) }
        return FileClient(fileBuilder, settings)
    }
}
