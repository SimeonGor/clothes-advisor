package ru.itmo.clothesadvisor.accountservice.config

import org.springframework.boot.ApplicationRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.ConfigurableEnvironment
import org.springframework.core.env.StandardEnvironment
import ru.itmo.clothesadvisor.accountservice.service.user.UserManagementService

@Configuration(proxyBeanMethods = false)
internal class BootstrapAdminConfiguration {
    @Bean
    fun bootstrapAdmin(
        users: UserManagementService,
        environment: ConfigurableEnvironment,
    ): ApplicationRunner {
        // Read the raw environment source: credentials are not SpEL or property templates.
        val variables = environment.propertySources[StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME]
        val login = variables?.getProperty("APP_BOOTSTRAP_ADMIN_LOGIN") as? String ?: ""
        val password = variables?.getProperty("APP_BOOTSTRAP_ADMIN_PASSWORD") as? String ?: ""
        return ApplicationRunner {
            // Startup thread only: readiness follows completion of all runners.
            users.bootstrap(login, password).block()
        }
    }
}
