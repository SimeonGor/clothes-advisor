package ru.itmo.clothesadvisor.accountservice

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.runApplication
import ru.itmo.clothesadvisor.accountservice.config.AuthenticationProperties

@SpringBootApplication
@EnableConfigurationProperties(AuthenticationProperties::class)
class AccountServiceApplication

fun main(args: Array<String>) {
    runApplication<AccountServiceApplication>(*args)
}
