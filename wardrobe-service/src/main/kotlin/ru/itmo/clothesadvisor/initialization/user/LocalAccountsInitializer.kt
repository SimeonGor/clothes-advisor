package ru.itmo.clothesadvisor.initialization.user

import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import ru.itmo.clothesadvisor.model.user.UserRole
import ru.itmo.clothesadvisor.service.user.AppUserService

@Component
@Profile("local")
internal class LocalAccountsInitializer(
    private val users: AppUserService,
) : ApplicationRunner {
    override fun run(args: ApplicationArguments) {
        val accounts =
            listOf(
                "user" to UserRole.USER,
                "stylist" to UserRole.STYLIST,
                "admin" to UserRole.ADMIN,
            )
        accounts.forEach { (login, role) ->
            if (users.findByLogin(login) == null) users.create(login, "!", role)
        }
    }
}
