package com.pinkdreams.config

import io.ktor.server.config.ApplicationConfig

data class DatabaseConfig(
    val jdbcUrl: String,
    val username: String,
    val password: String,
    val maximumPoolSize: Int = 10,
) {
    companion object {
        fun from(appConfig: AppConfig): DatabaseConfig {
            val jdbcUrl = System.getenv("DATABASE_URL")
                ?: throw IllegalStateException(
                    "DATABASE_URL environment variable is required (format: jdbc:postgresql://host:port/database)"
                )
            val username = System.getenv("DATABASE_USER")
                ?: throw IllegalStateException(
                    "DATABASE_USER environment variable is required"
                )
            val password = System.getenv("DATABASE_PASSWORD")
                ?: throw IllegalStateException(
                    "DATABASE_PASSWORD environment variable is required"
                )
            return DatabaseConfig(
                jdbcUrl = jdbcUrl,
                username = username,
                password = password,
                maximumPoolSize = System.getenv("DATABASE_POOL_SIZE")?.toIntOrNull() ?: 10,
            )
        }
    }
}
