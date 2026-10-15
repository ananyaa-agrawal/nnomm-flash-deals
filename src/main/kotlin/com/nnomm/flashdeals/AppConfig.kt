package com.nnomm.flashdeals

data class AppConfig(
    val port: Int,
    val databaseUrl: String,
    val databaseUser: String,
    val databasePassword: String,
    val jwtSecret: String,
    val webhookSecret: String,
    val claimTtlSeconds: Long,
    val expiryIntervalMillis: Long = 1_000,
    val dbPoolSize: Int = 20,
    val instanceId: String = "local",
) {
    companion object {
        fun fromEnv(env: Map<String, String> = System.getenv()): AppConfig {
            fun required(name: String) =
                env[name]?.takeIf { it.isNotBlank() } ?: error("Missing required environment variable $name")

            return AppConfig(
                port = env["PORT"]?.toInt() ?: 8080,
                databaseUrl = toJdbcUrl(required("DATABASE_URL")),
                databaseUser = required("DATABASE_USER"),
                databasePassword = required("DATABASE_PASSWORD"),
                jwtSecret = required("JWT_SECRET"),
                webhookSecret = required("WEBHOOK_SECRET"),
                claimTtlSeconds = env["CLAIM_TTL_SECONDS"]?.toLong() ?: 600,
                expiryIntervalMillis = env["EXPIRY_INTERVAL_MILLIS"]?.toLong() ?: 1_000,
                dbPoolSize = env["DB_POOL_SIZE"]?.toInt() ?: 20,
                instanceId = env["INSTANCE_ID"] ?: env["HOSTNAME"] ?: "local",
            ).also {
                require(it.claimTtlSeconds > 0) { "CLAIM_TTL_SECONDS must be positive" }
            }
        }

        /** Accepts either a JDBC URL or a libpq-style postgres:// URL. */
        fun toJdbcUrl(url: String): String = when {
            url.startsWith("jdbc:") -> url
            url.startsWith("postgres://") -> "jdbc:postgresql://" + url.removePrefix("postgres://")
            url.startsWith("postgresql://") -> "jdbc:$url"
            else -> url
        }
    }
}
