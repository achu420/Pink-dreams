package com.pinkdreams.config

data class AppConfig(
    val host: String = "0.0.0.0",
    val port: Int = 8080,
    val environment: String = "development",
) {
    companion object {
        fun load(): AppConfig {
            val envHost = System.getenv("PINKDREAMS_HOST")
            val envPort = System.getenv("PINKDREAMS_PORT")
            val envEnvironment = System.getenv("PINKDREAMS_ENV")

            return AppConfig(
                host = envHost ?: "0.0.0.0",
                port = envPort?.toIntOrNull() ?: 8080,
                environment = envEnvironment ?: "development",
            )
        }
    }
}
