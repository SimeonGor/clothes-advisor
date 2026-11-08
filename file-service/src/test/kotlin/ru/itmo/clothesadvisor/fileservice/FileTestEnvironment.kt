package ru.itmo.clothesadvisor.fileservice

import org.testcontainers.postgresql.PostgreSQLContainer

internal object FileTestEnvironment {
    private val db = PostgreSQLContainer("postgres:18-alpine").apply { start() }

    fun arguments(): Array<String> {
        return arrayOf(
            "--server.port=0",
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
