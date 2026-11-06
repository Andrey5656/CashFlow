package com.andrey.cashflow

import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StorageTest {

    private val folder: Path = Files.createTempDirectory("cashflow-test")
    private val storage = Storage.sqlite(folder.resolve("cashflow.db").toFile())
    private var network = false
    private val saver = Saver(storage) { network }
    private var now = 1_000L
    private val accounts = Accounts(saver) { now }

    init {
        storage.createTables()
    }

    @AfterTest
    fun close() {
        storage.close()
        folder.toFile().deleteRecursively()
    }

    private fun sql(statement: String) {
        DriverManager.getConnection("jdbc:sqlite:" + folder.resolve("cashflow.db")).use { it.createStatement().use { s -> s.execute(statement) } }
    }

    private fun reload(): Map<UUID, Account> = storage.loadAccounts().associateBy { it.id }

    @Test
    fun savesNewAccountsWithEveryField() {
        accounts.startingBalance = 500
        val player = accounts.join(UUID.randomUUID(), "986", true)
        accounts.setPaymentsOff(player, true)
        accounts.setMessagesOff(player, true)
        val owner = UUID.randomUUID()
        val bank = accounts.createBank("Vault", owner)!!
        saver.flush()
        val loaded = reload()
        val saved = loaded.getValue(player.id)
        assertEquals("986", saved.name)
        assertEquals(Kind.PLAYER, saved.kind)
        assertEquals(500, saved.balance.get())
        assertTrue(saved.paymentsOff && saved.messagesOff && saved.hidden)
        assertEquals(owner, loaded.getValue(bank.id).owner)
        assertEquals(Kind.BANK, loaded.getValue(bank.id).kind)
    }

    @Test
    fun savesBalanceFromMemory() {
        val player = accounts.join(UUID.randomUUID(), "986", false)
        accounts.deposit(player, 1000, EntryType.ADMIN_GIVE, "Console", null)
        accounts.withdraw(player, 300, EntryType.PLUGIN_WITHDRAW, null, "Shop")
        saver.flush()
        assertEquals(700, reload().getValue(player.id).balance.get())
    }

    @Test
    fun networkModeAddsChangesToTheSavedBalance() {
        network = true
        val player = accounts.join(UUID.randomUUID(), "986", false)
        saver.flush()
        sql("UPDATE cashflow_accounts SET balance = balance + 5000 WHERE id = '${player.id}'")
        accounts.deposit(player, 1000, EntryType.ADMIN_GIVE, "Console", null)
        accounts.withdraw(player, 300, EntryType.PLUGIN_WITHDRAW, null, "Shop")
        saver.flush()
        assertEquals(5700, reload().getValue(player.id).balance.get())
    }

    @Test
    fun networkModeSavesNewAccountsWithTheirFirstBalance() {
        network = true
        accounts.startingBalance = 100
        val player = accounts.join(UUID.randomUUID(), "986", false)
        accounts.deposit(player, 50, EntryType.ADMIN_GIVE, "Console", null)
        saver.flush()
        assertEquals(150, reload().getValue(player.id).balance.get())
    }

    @Test
    fun savesNameChanges() {
        val id = UUID.randomUUID()
        accounts.join(id, "Alex", false)
        saver.flush()
        accounts.join(id, "Alexa", false)
        saver.flush()
        assertEquals("Alexa", reload().getValue(id).name)
    }

    @Test
    fun deletesAccounts() {
        val bank = accounts.createBank("Vault", UUID.randomUUID())!!
        saver.flush()
        accounts.delete(bank)
        saver.flush()
        assertNull(reload()[bank.id])
    }

    @Test
    fun historyIsNewestFirstAndPaged() {
        val player = accounts.join(UUID.randomUUID(), "986", false)
        repeat(15) {
            now = 1_000L + it
            accounts.deposit(player, 100L + it, EntryType.PLUGIN_DEPOSIT, null, "Shop")
        }
        saver.flush()
        assertEquals(15, storage.countHistory(player.id))
        val first = storage.history(player.id, 0, 10)
        assertEquals(10, first.size)
        assertEquals(114, first.first().amount)
        assertEquals("Shop", first.first().source)
        val second = storage.history(player.id, 10, 10)
        assertEquals(5, second.size)
        assertEquals(100, second.last().amount)
    }

    @Test
    fun rollbackTargetIsBalanceAtThatTime() {
        val player = accounts.join(UUID.randomUUID(), "986", false)
        now = 100; accounts.deposit(player, 1000, EntryType.ADMIN_GIVE, "Console", null)
        now = 200; accounts.withdraw(player, 400, EntryType.PLUGIN_WITHDRAW, null, "Shop")
        now = 300; accounts.withdraw(player, 500, EntryType.PLUGIN_WITHDRAW, null, "Shop")
        saver.flush()
        assertEquals(1000, storage.balanceAt(player.id, 150))
        assertEquals(600, storage.balanceAt(player.id, 250))
        assertEquals(0, storage.balanceAt(player.id, 50))
        assertNull(storage.balanceAt(player.id, 300))
    }

    @Test
    fun noticesAreTakenOnce() {
        val id = UUID.randomUUID()
        saver.notice(Notice(id, "986", 100, 1))
        saver.notice(Notice(id, "Steve", 250, 2))
        saver.flush()
        assertEquals(listOf(Notice(id, "986", 100, 1), Notice(id, "Steve", 250, 2)), storage.takeNotices(id))
        assertTrue(storage.takeNotices(id).isEmpty())
    }

    @Test
    fun purgesOldLogLines() {
        val player = accounts.join(UUID.randomUUID(), "986", false)
        now = 100; accounts.deposit(player, 1, EntryType.ADMIN_GIVE, "Console", null)
        now = 900; accounts.deposit(player, 2, EntryType.ADMIN_GIVE, "Console", null)
        saver.flush()
        storage.purgeLog(500)
        assertEquals(listOf(2L), storage.history(player.id, 0, 10).map { it.amount })
    }

    @Test
    fun failedWriteIsRetried() {
        val player = accounts.join(UUID.randomUUID(), "986", false)
        saver.flush()
        sql("ALTER TABLE cashflow_log RENAME TO cashflow_log_gone")
        accounts.deposit(player, 1000, EntryType.ADMIN_GIVE, "Console", null)
        assertFailsWith<Exception> { saver.flush() }
        assertEquals(0, reload().getValue(player.id).balance.get())
        sql("ALTER TABLE cashflow_log_gone RENAME TO cashflow_log")
        saver.flush()
        assertEquals(1000, reload().getValue(player.id).balance.get())
        assertEquals(1, storage.countHistory(player.id))
    }

    @Test
    fun loadsBalancesOnly() {
        val player = accounts.join(UUID.randomUUID(), "986", false)
        accounts.deposit(player, 1234, EntryType.ADMIN_GIVE, "Console", null)
        saver.flush()
        assertEquals(mapOf(player.id to 1234L), storage.loadBalances())
    }
}
