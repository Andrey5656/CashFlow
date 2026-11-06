package com.andrey.cashflow

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import java.io.File
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.Types
import java.util.UUID

class Batch(
    val deleted: Collection<UUID>,
    val accounts: Collection<Account>,
    val changes: Map<Account, Long>,
    val entries: List<Entry>,
    val notices: List<Notice>,
)

class Storage(private val source: HikariDataSource, private val mysql: Boolean) : AutoCloseable {

    fun createTables() {
        connection { c -> c.createStatement().use { s -> tables().forEach(s::execute) } }
    }

    fun loadAccounts(): List<Account> = connection { c ->
        c.prepareStatement("SELECT id, name, kind, balance, payments_off, messages_off, hidden, owner FROM cashflow_accounts").use { ps ->
            ps.executeQuery().use { rs ->
                val list = ArrayList<Account>()
                while (rs.next()) {
                    list += Account(
                        UUID.fromString(rs.getString(1)),
                        rs.getString(2),
                        Kind.of(rs.getInt(3)),
                        rs.getLong(4),
                        rs.getBoolean(5),
                        rs.getBoolean(6),
                        rs.getBoolean(7),
                        rs.getString(8)?.let(UUID::fromString),
                    )
                }
                list
            }
        }
    }

    fun loadBalances(): Map<UUID, Long> = connection { c ->
        c.prepareStatement("SELECT id, balance FROM cashflow_accounts").use { ps ->
            ps.executeQuery().use { rs ->
                val map = HashMap<UUID, Long>()
                while (rs.next()) map[UUID.fromString(rs.getString(1))] = rs.getLong(2)
                map
            }
        }
    }

    fun write(batch: Batch, network: Boolean) {
        connection { c ->
            c.autoCommit = false
            try {
                if (batch.deleted.isNotEmpty()) {
                    c.prepareStatement("DELETE FROM cashflow_accounts WHERE id = ?").use { ps ->
                        batch.deleted.forEach { ps.setString(1, it.toString()); ps.addBatch() }
                        ps.executeBatch()
                    }
                }
                if (batch.accounts.isNotEmpty()) {
                    val insert = (if (mysql) "INSERT IGNORE INTO" else "INSERT OR IGNORE INTO") +
                        " cashflow_accounts (id, name, kind, balance, payments_off, messages_off, hidden, owner) VALUES (?, ?, ?, ?, ?, ?, ?, ?)"
                    c.prepareStatement(insert).use { ps ->
                        for (account in batch.accounts) {
                            ps.setString(1, account.id.toString())
                            ps.setString(2, account.name.take(64))
                            ps.setInt(3, account.kind.id)
                            ps.setLong(4, if (network) account.initial else account.balance.get())
                            ps.setBoolean(5, account.paymentsOff)
                            ps.setBoolean(6, account.messagesOff)
                            ps.setBoolean(7, account.hidden)
                            ps.setNullableString(8, account.owner?.toString())
                            ps.addBatch()
                        }
                        ps.executeBatch()
                    }
                    c.prepareStatement("UPDATE cashflow_accounts SET name = ?, kind = ?, payments_off = ?, messages_off = ?, hidden = ?, owner = ? WHERE id = ?").use { ps ->
                        for (account in batch.accounts) {
                            ps.setString(1, account.name.take(64))
                            ps.setInt(2, account.kind.id)
                            ps.setBoolean(3, account.paymentsOff)
                            ps.setBoolean(4, account.messagesOff)
                            ps.setBoolean(5, account.hidden)
                            ps.setNullableString(6, account.owner?.toString())
                            ps.setString(7, account.id.toString())
                            ps.addBatch()
                        }
                        ps.executeBatch()
                    }
                }
                if (batch.changes.isNotEmpty()) {
                    val update = if (network) "UPDATE cashflow_accounts SET balance = balance + ? WHERE id = ?" else "UPDATE cashflow_accounts SET balance = ? WHERE id = ?"
                    c.prepareStatement(update).use { ps ->
                        for ((account, delta) in batch.changes) {
                            ps.setLong(1, if (network) delta else account.balance.get())
                            ps.setString(2, account.id.toString())
                            ps.addBatch()
                        }
                        ps.executeBatch()
                    }
                }
                if (batch.entries.isNotEmpty()) {
                    c.prepareStatement("INSERT INTO cashflow_log (account, time, amount, balance, type, other, source) VALUES (?, ?, ?, ?, ?, ?, ?)").use { ps ->
                        for (entry in batch.entries) {
                            ps.setString(1, entry.account.toString())
                            ps.setLong(2, entry.time)
                            ps.setLong(3, entry.amount)
                            ps.setLong(4, entry.balance)
                            ps.setInt(5, entry.type.id)
                            ps.setNullableString(6, entry.other?.take(64))
                            ps.setNullableString(7, entry.source?.take(64))
                            ps.addBatch()
                        }
                        ps.executeBatch()
                    }
                }
                if (batch.notices.isNotEmpty()) {
                    c.prepareStatement("INSERT INTO cashflow_notices (account, payer, amount, time) VALUES (?, ?, ?, ?)").use { ps ->
                        for (notice in batch.notices) {
                            ps.setString(1, notice.account.toString())
                            ps.setString(2, notice.payer.take(64))
                            ps.setLong(3, notice.amount)
                            ps.setLong(4, notice.time)
                            ps.addBatch()
                        }
                        ps.executeBatch()
                    }
                }
                c.commit()
            } catch (e: Exception) {
                c.rollback()
                throw e
            } finally {
                c.autoCommit = true
            }
        }
    }

    fun history(account: UUID, offset: Int, limit: Int): List<Entry> = connection { c ->
        c.prepareStatement("SELECT time, amount, balance, type, other, source FROM cashflow_log WHERE account = ? ORDER BY time DESC, id DESC LIMIT ? OFFSET ?").use { ps ->
            ps.setString(1, account.toString())
            ps.setInt(2, limit)
            ps.setInt(3, offset)
            ps.executeQuery().use { rs ->
                val list = ArrayList<Entry>()
                while (rs.next()) {
                    list += Entry(account, rs.getLong(1), rs.getLong(2), rs.getLong(3), EntryType.of(rs.getInt(4)), rs.getString(5), rs.getString(6))
                }
                list
            }
        }
    }

    fun countHistory(account: UUID): Int = connection { c ->
        c.prepareStatement("SELECT COUNT(*) FROM cashflow_log WHERE account = ?").use { ps ->
            ps.setString(1, account.toString())
            ps.executeQuery().use { rs -> if (rs.next()) rs.getInt(1) else 0 }
        }
    }

    fun balanceAt(account: UUID, time: Long): Long? = connection { c ->
        val firstAfter = c.prepareStatement("SELECT balance, amount FROM cashflow_log WHERE account = ? AND time > ? ORDER BY time ASC, id ASC LIMIT 1").use { ps ->
            ps.setString(1, account.toString())
            ps.setLong(2, time)
            ps.executeQuery().use { rs -> if (rs.next()) rs.getLong(1) - rs.getLong(2) else null }
        } ?: return@connection null
        c.prepareStatement("SELECT balance FROM cashflow_log WHERE account = ? AND time <= ? ORDER BY time DESC, id DESC LIMIT 1").use { ps ->
            ps.setString(1, account.toString())
            ps.setLong(2, time)
            ps.executeQuery().use { rs -> if (rs.next()) rs.getLong(1) else firstAfter }
        }
    }

    fun takeNotices(account: UUID): List<Notice> = connection { c ->
        c.autoCommit = false
        try {
            val ids = ArrayList<Long>()
            val notices = ArrayList<Notice>()
            c.prepareStatement("SELECT id, payer, amount, time FROM cashflow_notices WHERE account = ? ORDER BY id").use { ps ->
                ps.setString(1, account.toString())
                ps.executeQuery().use { rs ->
                    while (rs.next()) {
                        ids += rs.getLong(1)
                        notices += Notice(account, rs.getString(2), rs.getLong(3), rs.getLong(4))
                    }
                }
            }
            if (ids.isNotEmpty()) {
                c.prepareStatement("DELETE FROM cashflow_notices WHERE id = ?").use { ps ->
                    ids.forEach { ps.setLong(1, it); ps.addBatch() }
                    ps.executeBatch()
                }
            }
            c.commit()
            notices
        } catch (e: Exception) {
            c.rollback()
            throw e
        } finally {
            c.autoCommit = true
        }
    }

    fun purgeLog(before: Long) {
        connection { c ->
            c.prepareStatement("DELETE FROM cashflow_log WHERE time < ?").use { ps ->
                ps.setLong(1, before)
                ps.executeUpdate()
            }
        }
    }

    override fun close() {
        source.close()
    }

    private fun <T> connection(block: (Connection) -> T): T = source.connection.use(block)

    private fun PreparedStatement.setNullableString(index: Int, value: String?) {
        if (value == null) setNull(index, Types.VARCHAR) else setString(index, value)
    }

    private fun tables(): List<String> = if (mysql) listOf(
        "CREATE TABLE IF NOT EXISTS cashflow_accounts (id CHAR(36) NOT NULL PRIMARY KEY, name VARCHAR(64) NOT NULL, kind TINYINT NOT NULL, balance BIGINT NOT NULL, payments_off BOOLEAN NOT NULL DEFAULT FALSE, messages_off BOOLEAN NOT NULL DEFAULT FALSE, hidden BOOLEAN NOT NULL DEFAULT FALSE, owner CHAR(36) NULL)",
        "CREATE TABLE IF NOT EXISTS cashflow_log (id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, account CHAR(36) NOT NULL, time BIGINT NOT NULL, amount BIGINT NOT NULL, balance BIGINT NOT NULL, type TINYINT NOT NULL, other VARCHAR(64) NULL, source VARCHAR(64) NULL, INDEX cashflow_log_account (account, time), INDEX cashflow_log_time (time))",
        "CREATE TABLE IF NOT EXISTS cashflow_notices (id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, account CHAR(36) NOT NULL, payer VARCHAR(64) NOT NULL, amount BIGINT NOT NULL, time BIGINT NOT NULL, INDEX cashflow_notices_account (account))",
    ) else listOf(
        "CREATE TABLE IF NOT EXISTS cashflow_accounts (id CHAR(36) NOT NULL PRIMARY KEY, name VARCHAR(64) NOT NULL, kind TINYINT NOT NULL, balance BIGINT NOT NULL, payments_off BOOLEAN NOT NULL DEFAULT 0, messages_off BOOLEAN NOT NULL DEFAULT 0, hidden BOOLEAN NOT NULL DEFAULT 0, owner CHAR(36) NULL)",
        "CREATE TABLE IF NOT EXISTS cashflow_log (id INTEGER PRIMARY KEY AUTOINCREMENT, account CHAR(36) NOT NULL, time BIGINT NOT NULL, amount BIGINT NOT NULL, balance BIGINT NOT NULL, type TINYINT NOT NULL, other VARCHAR(64) NULL, source VARCHAR(64) NULL)",
        "CREATE INDEX IF NOT EXISTS cashflow_log_account ON cashflow_log (account, time)",
        "CREATE INDEX IF NOT EXISTS cashflow_log_time ON cashflow_log (time)",
        "CREATE TABLE IF NOT EXISTS cashflow_notices (id INTEGER PRIMARY KEY AUTOINCREMENT, account CHAR(36) NOT NULL, payer VARCHAR(64) NOT NULL, amount BIGINT NOT NULL, time BIGINT NOT NULL)",
        "CREATE INDEX IF NOT EXISTS cashflow_notices_account ON cashflow_notices (account)",
    )

    companion object {
        fun sqlite(file: File): Storage {
            val config = HikariConfig()
            config.poolName = "CashFlow"
            config.driverClassName = "org.sqlite.JDBC"
            config.jdbcUrl = "jdbc:sqlite:" + file.absolutePath
            config.maximumPoolSize = 1
            config.addDataSourceProperty("journal_mode", "WAL")
            config.addDataSourceProperty("busy_timeout", "5000")
            return Storage(HikariDataSource(config), false)
        }

        fun mysql(host: String, port: Int, database: String, username: String, password: String): Storage {
            val config = HikariConfig()
            config.poolName = "CashFlow"
            config.driverClassName = "com.mysql.cj.jdbc.Driver"
            config.jdbcUrl = "jdbc:mysql://$host:$port/$database?rewriteBatchedStatements=true"
            config.username = username
            config.password = password
            config.maximumPoolSize = 3
            return Storage(HikariDataSource(config), true)
        }
    }
}
