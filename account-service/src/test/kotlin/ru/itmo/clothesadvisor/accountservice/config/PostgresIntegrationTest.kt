package ru.itmo.clothesadvisor.accountservice.config

import org.junit.jupiter.api.AfterEach
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.postgresql.PostgreSQLContainer

@ActiveProfiles("test")
abstract class PostgresIntegrationTest {
    protected val jdbc = JdbcTemplate(
        DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password),
    )

    @AfterEach
    fun cleanupDatabase() {
        jdbc.dataSource!!.connection.use { connection ->
            check(connection.metaData.url == postgres.jdbcUrl) {
                "Cleanup is restricted to the test container"
            }
        }
        jdbc.execute("TRUNCATE TABLE access_grant, app_user_history, app_user RESTART IDENTITY")
    }

    companion object {
        val postgres = PostgreSQLContainer("postgres:18-alpine").apply { start() }

        @JvmStatic
        @DynamicPropertySource
        fun postgresProperties(registry: DynamicPropertyRegistry) {
            registry.add("app.jwt.private-key") { TestJwt.privateKeyLocation }
            registry.add("app.jwt.public-key") { TestJwt.publicKeyLocation }
            registry.add("spring.r2dbc.url") {
                "r2dbc:postgresql://${postgres.host}:${postgres.firstMappedPort}/" +
                    postgres.databaseName
            }
            registry.add("spring.r2dbc.username", postgres::getUsername)
            registry.add("spring.r2dbc.password", postgres::getPassword)
            registry.add("spring.flyway.url", postgres::getJdbcUrl)
            registry.add("spring.flyway.user", postgres::getUsername)
            registry.add("spring.flyway.password", postgres::getPassword)
        }
    }
}
