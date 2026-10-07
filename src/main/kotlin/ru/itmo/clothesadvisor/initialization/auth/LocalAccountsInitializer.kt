package ru.itmo.clothesadvisor.initialization.auth

import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.context.annotation.Profile
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Component
import ru.itmo.clothesadvisor.security.auth.PasswordRules
import ru.itmo.clothesadvisor.model.user.UserRole
import ru.itmo.clothesadvisor.service.user.AppUserService

@Component
@Profile("local")
internal class LocalAccountsInitializer(
    private val users: AppUserService,
    private val encoder: PasswordEncoder,
    @Value("\${LOCAL_USER_PASSWORD:}") private val userPassword: String,
    @Value("\${LOCAL_STYLIST_PASSWORD:}") private val stylistPassword: String,
    @Value("\${LOCAL_ADMIN_PASSWORD:}") private val adminPassword: String,
) : ApplicationRunner {
    override fun run(args: ApplicationArguments) {
        val accounts = listOf(
            Triple("user", UserRole.USER, userPassword),
            Triple("stylist", UserRole.STYLIST, stylistPassword),
            Triple("admin", UserRole.ADMIN, adminPassword),
        )
        require(accounts.all { (_, _, password) -> password.isNotBlank() && PasswordRules.isWithinBcryptLimit(password) }) {
            "Local account passwords must be nonblank and contain at most 72 UTF-8 bytes"
        }
        accounts.forEach { (login, role, password) ->
            if (users.findByLogin(login) == null) users.create(login, requireNotNull(encoder.encode(password)), role)
        }
    }
}
