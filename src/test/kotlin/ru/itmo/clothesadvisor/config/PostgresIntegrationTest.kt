package ru.itmo.clothesadvisor.config

import org.junit.jupiter.api.AfterEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.ActiveProfiles
import org.testcontainers.postgresql.PostgreSQLContainer

@ActiveProfiles("test")
abstract class PostgresIntegrationTest {
    @Autowired private lateinit var cleanupJdbc: JdbcTemplate

    @AfterEach
    fun cleanupDatabase() = resetMutableData()

    protected fun resetMutableData() {
        cleanupJdbc.dataSource!!.connection.use { connection ->
            check(connection.metaData.url == postgres.jdbcUrl) { "Cleanup is restricted to the test container" }
        }
        cleanupJdbc.execute("""
            TRUNCATE TABLE outfit_rating_history, outfit_rating, outfit_item, outfit_weather, outfit, access_grant,
                wardrobe_item_photo, wardrobe_item_history, wardrobe_item, app_user_history, app_user RESTART IDENTITY
        """)
        cleanupJdbc.update("DELETE FROM wardrobe_category WHERE id NOT IN (1, 2, 3, 4, 5, 6)")
        cleanupJdbc.update("DELETE FROM precipitation_type WHERE id NOT IN (1, 2, 3, 4)")
    }

    companion object {
        val postgres = PostgreSQLContainer("postgres:18-alpine").apply { start() }

        @JvmStatic
        @DynamicPropertySource
        fun postgresProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
        }
    }
}
