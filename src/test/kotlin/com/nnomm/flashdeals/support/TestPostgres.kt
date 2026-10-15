package com.nnomm.flashdeals.support

import com.nnomm.flashdeals.db.Database
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.PostgreSQLContainer

/**
 * One real PostgreSQL for the whole test run, never H2 or mocks.
 *
 * Picks, in order:
 *  1. TEST_DATABASE_URL / TEST_DATABASE_USER / TEST_DATABASE_PASSWORD (e.g. the compose database),
 *  2. Testcontainers (postgres:16-alpine) when Docker is available,
 *  3. an embedded PostgreSQL 16 binary (io.zonky), for machines without Docker.
 */
object TestPostgres {
    data class Connection(val url: String, val user: String, val password: String)

    val connection: Connection by lazy { start().also { migrate(it) } }

    /** A small pool the tests use to inspect and reset state directly. */
    val admin: Database by lazy { Database(pool(connection, size = 5, name = "test-admin")) }

    fun pool(c: Connection, size: Int, name: String) = HikariDataSource(HikariConfig().apply {
        jdbcUrl = c.url
        username = c.user
        password = c.password
        maximumPoolSize = size
        poolName = name
    })

    fun reset() = admin.transaction { conn ->
        conn.createStatement().use {
            it.execute("TRUNCATE payment_events, idempotency_keys, claims, deals")
        }
    }

    private fun migrate(c: Connection) = pool(c, 2, "test-migrate").use { Database.migrate(it) }

    private fun start(): Connection {
        System.getenv("TEST_DATABASE_URL")?.let { url ->
            return Connection(url, System.getenv("TEST_DATABASE_USER") ?: "postgres", System.getenv("TEST_DATABASE_PASSWORD") ?: "")
        }
        if (runCatching { DockerClientFactory.instance().isDockerAvailable }.getOrDefault(false)) {
            val container = PostgreSQLContainer("postgres:16-alpine")
                .withCommand("postgres", "-c", "max_connections=300")
            container.start()
            return Connection(container.jdbcUrl, container.username, container.password)
        }
        val embedded = EmbeddedPostgres.builder()
            .setServerConfig("max_connections", "300")
            .start()
        Runtime.getRuntime().addShutdownHook(Thread { embedded.close() })
        return Connection(embedded.getJdbcUrl("postgres", "postgres"), "postgres", "postgres")
    }
}
