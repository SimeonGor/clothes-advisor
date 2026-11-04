package ru.itmo.clothesadvisor.accountservice.service.auth

import java.util.UUID
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import reactor.core.publisher.Mono
import reactor.core.scheduler.Schedulers
import ru.itmo.clothesadvisor.accountservice.config.PasswordConfiguration

@Service
internal class PasswordService(private val encoder: PasswordEncoder) {
    private val encodedFormat = Regex("\\{${PasswordConfiguration.FORMAT_ID}\\}[0-9a-fA-F]{96}")
    private val dummyHash = Mono.fromCallable { encoder.encode(UUID.randomUUID().toString()) }
        .subscribeOn(Schedulers.boundedElastic())
        .cache()

    fun encode(password: String): Mono<String> = Mono.fromCallable {
        require(password.length in 12..128 && password.isNotBlank()) {
            "Password must contain 12 to 128 characters and must not be blank"
        }
        encoder.encode(password)
    }.subscribeOn(Schedulers.boundedElastic())

    fun matches(password: String, passwordHash: String?): Mono<Boolean> = Mono.defer {
        if (password.length > 128) {
            return@defer Mono.just(false)
        }
        val supported = passwordHash != null && encodedFormat.matches(passwordHash)
        dummyHash.flatMap { dummy ->
            Mono.fromCallable {
                val matched = encoder.matches(password, if (supported) passwordHash else dummy)
                supported && matched
            }.subscribeOn(Schedulers.boundedElastic())
        }
    }
}
