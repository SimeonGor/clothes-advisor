package ru.itmo.clothesadvisor.client.account

import feign.Request
import feign.Retryer
import feign.codec.Decoder
import feign.codec.ErrorDecoder
import java.io.IOException
import java.net.URI
import java.util.concurrent.TimeUnit
import org.springframework.cloud.openfeign.FeignBuilderCustomizer
import org.springframework.context.annotation.Bean
import ru.itmo.clothesadvisor.dto.account.AccountUserResponse
import ru.itmo.clothesadvisor.model.AccessDeniedException
import ru.itmo.clothesadvisor.model.EntityNotFoundException
import ru.itmo.clothesadvisor.model.access.AccountUnavailableException
import ru.itmo.clothesadvisor.model.user.UserRole
import ru.itmo.clothesadvisor.model.user.UserStatus
import tools.jackson.core.JacksonException
import tools.jackson.core.StreamReadFeature
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.json.JsonMapper

// Registered only in the Feign child context, never through component scanning.
internal class AccountClientConfiguration {
    private val mapper = JsonMapper.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .build()

    @Bean
    fun accountDecoder() = Decoder { response, type ->
        try {
            if (type == Void.TYPE) {
                val empty = response.body()?.asInputStream()?.use { it.read() == -1 } ?: true
                if (response.status() != 204 || !empty) throw AccountUnavailableException()
                return@Decoder null
            }
            if (response.status() != 200) throw AccountUnavailableException()

            val body = response.body()?.asInputStream()?.use { mapper.readTree(it) }
            if (body == null || !body.isObject || body.size() != 4) {
                throw AccountUnavailableException()
            }
            val id = body.path("id")
            val login = body.path("login")
            val role = body.path("role")
            val status = body.path("status")
            if (!id.isIntegralNumber || !id.canConvertToLong() || id.longValue() <= 0 ||
                !login.isString || login.asString().isBlank() || !role.isString || !status.isString
            ) throw AccountUnavailableException()

            val requestedId = URI.create(response.request().url()).path
                .substringAfterLast('/').toLongOrNull()
            if (id.longValue() != requestedId) throw AccountUnavailableException()
            val userRole = UserRole.entries.firstOrNull { it.name == role.asString() }
                ?: throw AccountUnavailableException()
            val userStatus = UserStatus.entries.firstOrNull { it.name == status.asString() }
                ?: throw AccountUnavailableException()
            AccountUserResponse(id.longValue(), login.asString(), userRole, userStatus)
        } catch (_: JacksonException) {
            throw AccountUnavailableException()
        } catch (_: IOException) {
            throw AccountUnavailableException()
        } catch (_: IllegalArgumentException) {
            throw AccountUnavailableException()
        }
    }

    @Bean
    fun accountErrorDecoder() = ErrorDecoder { methodKey, response ->
        try {
            if (response.status() == 404) {
                val empty = response.body()?.asInputStream()?.use { it.read() == -1 } ?: true
                return@ErrorDecoder if (empty) EntityNotFoundException()
                else AccountUnavailableException()
            }
            if (response.status() != 403 ||
                !methodKey.startsWith("AccountClient#requireAdminAccess(")
            ) return@ErrorDecoder AccountUnavailableException()

            val body = response.body()?.asInputStream()?.use { mapper.readTree(it) }
            val forbidden = body != null && body.isObject && body.size() == 1 &&
                body.path("code").isString && body.path("code").asString() == "forbidden"
            if (forbidden) AccessDeniedException() else AccountUnavailableException()
        } catch (_: JacksonException) {
            AccountUnavailableException()
        } catch (_: IOException) {
            AccountUnavailableException()
        }
    }

    @Bean
    fun accountDecodeVoid() = FeignBuilderCustomizer { it.decodeVoid() }

    @Bean
    fun accountOptions() = Request.Options(2, TimeUnit.SECONDS, 5, TimeUnit.SECONDS, false)

    @Bean
    fun accountRetryer(): Retryer = Retryer.NEVER_RETRY

    @Bean
    fun accountFallbackFactory() = AccountFallbackFactory()
}
