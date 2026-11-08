package ru.itmo.clothesadvisor.fileservice

import java.nio.file.Files
import java.nio.file.Path
import org.testcontainers.postgresql.PostgreSQLContainer

internal object FileTestEnvironment {
    const val WARDROBE_PASSWORD = "synthetic-wardrobe-password"
    const val RECOMMENDATION_PASSWORD = "synthetic-recommendation-password"
    private val db = PostgreSQLContainer("postgres:18-alpine").apply { start() }

    fun arguments(directory: Path): Array<String> {
        val wardrobe = Files.writeString(directory.resolve("wardrobe"), "$WARDROBE_PASSWORD\n")
        val recommendation = Files.writeString(
            directory.resolve("recommendation"), "$RECOMMENDATION_PASSWORD\n",
        )
        return arrayOf(
            "--server.port=0",
            "--app.auth.wardrobe-password-file=$wardrobe",
            "--app.auth.recommendation-password-file=$recommendation",
            "--spring.r2dbc.url=r2dbc:postgresql://${db.host}:${db.firstMappedPort}/${db.databaseName}",
            "--spring.r2dbc.username=${db.username}",
            "--spring.r2dbc.password=${db.password}",
            "--spring.flyway.url=${db.jdbcUrl}",
            "--spring.flyway.user=${db.username}",
            "--spring.flyway.password=${db.password}",
            "--spring.r2dbc.pool.initial-size=1",
            "--spring.r2dbc.pool.max-size=1",
        )
    }
}
