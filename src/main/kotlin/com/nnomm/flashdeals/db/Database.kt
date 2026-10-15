package com.nnomm.flashdeals.db

import com.nnomm.flashdeals.AppConfig
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.flywaydb.core.Flyway
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

class Database(val dataSource: HikariDataSource) : AutoCloseable {

    /**
     * Runs [block] in one READ COMMITTED transaction on a pooled connection.
     * Correctness never depends on the isolation level alone: every
     * read-then-write path takes an explicit row lock (SELECT ... FOR UPDATE)
     * or relies on a unique constraint. See DESIGN.md, question 5.
     */
    fun <T> transaction(block: (Connection) -> T): T =
        dataSource.connection.use { conn ->
            conn.autoCommit = false
            conn.transactionIsolation = Connection.TRANSACTION_READ_COMMITTED
            try {
                val result = block(conn)
                conn.commit()
                result
            } catch (e: Throwable) {
                runCatching { conn.rollback() }
                throw e
            }
        }

    suspend fun <T> tx(block: (Connection) -> T): T = withContext(Dispatchers.IO) { transaction(block) }

    override fun close() = dataSource.close()

    companion object {
        fun connect(config: AppConfig): Database {
            val hikari = HikariConfig().apply {
                jdbcUrl = config.databaseUrl
                username = config.databaseUser
                password = config.databasePassword
                maximumPoolSize = config.dbPoolSize
                minimumIdle = 2
                connectionTimeout = 10_000
                poolName = "flashdeals-${config.instanceId}"
                // If this process hangs (not dies) mid-transaction, Postgres
                // kills the session and releases its locks, so an
                // Idempotency-Key can never stay blocked for more than ~30s.
                connectionInitSql =
                    "SET idle_in_transaction_session_timeout = '30s'; SET statement_timeout = '30s'"
            }
            return Database(HikariDataSource(hikari))
        }

        fun migrate(dataSource: DataSource) {
            // Flyway takes a Postgres advisory lock, so two instances starting
            // together apply each migration exactly once.
            Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load()
                .migrate()
        }
    }
}

// Small JDBC helpers so every query stays parameterised and readable.

fun Connection.prepare(sql: String, vararg params: Any?): PreparedStatement {
    val ps = prepareStatement(sql)
    params.forEachIndexed { i, p ->
        when (p) {
            null -> ps.setObject(i + 1, null)
            is UUID -> ps.setObject(i + 1, p)
            is Instant -> ps.setTimestamp(i + 1, Timestamp.from(p))
            is Int -> ps.setInt(i + 1, p)
            is Long -> ps.setLong(i + 1, p)
            is String -> ps.setString(i + 1, p)
            else -> ps.setObject(i + 1, p)
        }
    }
    return ps
}

fun <T> Connection.queryOne(sql: String, vararg params: Any?, map: (ResultSet) -> T): T? =
    prepare(sql, *params).use { ps ->
        ps.executeQuery().use { rs -> if (rs.next()) map(rs) else null }
    }

fun <T> Connection.queryList(sql: String, vararg params: Any?, map: (ResultSet) -> T): List<T> =
    prepare(sql, *params).use { ps ->
        ps.executeQuery().use { rs ->
            buildList { while (rs.next()) add(map(rs)) }
        }
    }

fun Connection.update(sql: String, vararg params: Any?): Int =
    prepare(sql, *params).use { it.executeUpdate() }

fun ResultSet.uuid(column: String): UUID = getObject(column, UUID::class.java)
fun ResultSet.instant(column: String): Instant = getTimestamp(column).toInstant()
