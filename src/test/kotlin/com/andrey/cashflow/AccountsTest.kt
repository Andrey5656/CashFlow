package com.andrey.cashflow

import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class AccountsTest {

    private class RecordingSink : Sink {
        val created = ConcurrentLinkedQueue<Account>()
        val entries = ConcurrentLinkedQueue<Entry>()
        val updated = ConcurrentLinkedQueue<Account>()
        val deleted = ConcurrentLinkedQueue<Account>()
        override fun created(account: Account) { created += account }
        override fun changed(account: Account, entry: Entry) { entries += entry }
        override fun updated(account: Account) { updated += account }
        override fun deleted(account: Account) { deleted += account }
    }

    private val sink = RecordingSink()
    private val accounts = Accounts(sink) { 1000L }

    private fun player(name: String, cents: Long = 0): Account {
        val account = accounts.join(UUID.randomUUID(), name, false)
        if (cents > 0) accounts.set(account, cents, EntryType.ADMIN_SET, "test")
        sink.entries.clear()
        return account
    }

    @Test
    fun depositRecordsEntryWithBalanceAfter() {
        val account = player("986", 500)
        val result = accounts.deposit(account, 250, EntryType.PLUGIN_DEPOSIT, null, "Shop")
        assertEquals(Result.Success(750), result)
        assertEquals(Entry(account.id, 1000L, 250, 750, EntryType.PLUGIN_DEPOSIT, null, "Shop"), sink.entries.single())
    }

    @Test
    fun withdrawFailsWhenNotEnoughMoney() {
        val account = player("986", 100)
        assertEquals(Result.NotEnough, accounts.withdraw(account, 101, EntryType.PLUGIN_WITHDRAW, null, "Shop"))
        assertEquals(100, account.balance.get())
        assertTrue(sink.entries.isEmpty())
    }

    @Test
    fun withdrawRecordsNegativeAmount() {
        val account = player("986", 100)
        assertEquals(Result.Success(40), accounts.withdraw(account, 60, EntryType.PLUGIN_WITHDRAW, null, "Shop"))
        assertEquals(-60, sink.entries.single().amount)
    }

    @Test
    fun depositFailsAboveMaxBalance() {
        accounts.maxBalance = 1000
        val account = player("986", 900)
        assertEquals(Result.OverMax, accounts.deposit(account, 101, EntryType.ADMIN_GIVE, "Console", null))
        assertEquals(Result.Success(1000), accounts.deposit(account, 100, EntryType.ADMIN_GIVE, "Console", null))
    }

    @Test
    fun setRecordsDifference() {
        val account = player("986", 500)
        assertEquals(Result.Success(200), accounts.set(account, 200, EntryType.ADMIN_SET, "Console"))
        assertEquals(-300, sink.entries.single().amount)
        assertEquals(200, sink.entries.single().balance)
    }

    @Test
    fun setFailsAboveMaxBalance() {
        accounts.maxBalance = 1000
        val account = player("986")
        assertEquals(Result.OverMax, accounts.set(account, 1001, EntryType.ADMIN_SET, "Console"))
    }

    @Test
    fun concurrentWithdrawsNeverOverdraw() {
        val account = player("986", 500)
        val pool = Executors.newFixedThreadPool(16)
        val start = CountDownLatch(1)
        val wins = AtomicInteger()
        repeat(1000) {
            pool.execute {
                start.await()
                if (accounts.withdraw(account, 1, EntryType.PLUGIN_WITHDRAW, null, "Shop") is Result.Success) wins.incrementAndGet()
            }
        }
        start.countDown()
        pool.shutdown()
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS))
        assertEquals(500, wins.get())
        assertEquals(0, account.balance.get())
        assertEquals(500, sink.entries.size)
    }

    @Test
    fun concurrentTransfersKeepTotal() {
        val a = player("a", 10_000)
        val b = player("b", 10_000)
        val pool = Executors.newFixedThreadPool(16)
        val start = CountDownLatch(1)
        repeat(2000) { i ->
            pool.execute {
                start.await()
                if (i % 2 == 0) accounts.transfer(a, b, 7) else accounts.transfer(b, a, 5)
            }
        }
        start.countDown()
        pool.shutdown()
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS))
        assertEquals(20_000, a.balance.get() + b.balance.get())
        assertEquals(10_000 - 7000 + 5000, a.balance.get())
    }

    @Test
    fun transferRecordsBothSides() {
        val payer = player("986", 500)
        val receiver = player("Steve")
        assertEquals(Result.Success(400), accounts.transfer(payer, receiver, 100))
        val (sent, received) = sink.entries.toList()
        assertEquals(Entry(payer.id, 1000L, -100, 400, EntryType.PAY_SENT, "Steve", null), sent)
        assertEquals(Entry(receiver.id, 1000L, 100, 100, EntryType.PAY_RECEIVED, "986", null), received)
    }

    @Test
    fun transferRefundsWhenReceiverIsFull() {
        accounts.maxBalance = 1000
        val payer = player("986", 500)
        val receiver = player("Steve", 950)
        assertEquals(Result.OverMax, accounts.transfer(payer, receiver, 100))
        assertEquals(500, payer.balance.get())
        assertEquals(950, receiver.balance.get())
        assertTrue(sink.entries.isEmpty())
    }

    @Test
    fun transferFailsWithoutMoney() {
        val payer = player("986", 50)
        val receiver = player("Steve")
        assertEquals(Result.NotEnough, accounts.transfer(payer, receiver, 100))
        assertEquals(0, receiver.balance.get())
    }

    @Test
    fun newAccountsStartWithStartingBalance() {
        accounts.startingBalance = 2500
        val account = accounts.getOrCreate(UUID.randomUUID(), "Bob", Kind.UNKNOWN)
        assertEquals(2500, account.balance.get())
        assertSame(account, sink.created.single())
    }

    @Test
    fun concurrentCreatesMakeOneAccount() {
        val id = UUID.randomUUID()
        val pool = Executors.newFixedThreadPool(16)
        val start = CountDownLatch(1)
        repeat(100) { pool.execute { start.await(); accounts.getOrCreate(id, "Bob", Kind.UNKNOWN) } }
        start.countDown()
        pool.shutdown()
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS))
        assertEquals(1, sink.created.size)
    }

    @Test
    fun playerLookupIgnoresCase() {
        val account = player("Notch")
        assertSame(account, accounts.player("nOtCh"))
    }

    @Test
    fun joinUpdatesNameAndKind() {
        val id = UUID.randomUUID()
        accounts.getOrCreate(id, id.toString(), Kind.UNKNOWN)
        assertNull(accounts.player("Alex"))
        val account = accounts.join(id, "Alex", false)
        assertEquals(Kind.PLAYER, account.kind)
        assertSame(account, accounts.player("alex"))
        accounts.join(id, "Alexa", true)
        assertNull(accounts.player("alex"))
        assertSame(account, accounts.player("alexa"))
        assertTrue(account.hidden)
        assertTrue(sink.updated.isNotEmpty())
    }

    @Test
    fun joinWithSameNameAndFlagsDoesNotUpdate() {
        val account = player("Alex")
        sink.updated.clear()
        accounts.join(account.id, "Alex", false)
        assertTrue(sink.updated.isEmpty())
    }

    @Test
    fun unknownAccountsAreNotFoundByPlayerName() {
        accounts.getOrCreate(UUID.randomUUID(), "Ghost", Kind.UNKNOWN)
        assertNull(accounts.player("Ghost"))
    }

    @Test
    fun namedAccountsHaveTheirOwnLookup() {
        val town = accounts.named("town-spawn", create = true)!!
        assertEquals(Kind.NAMED, town.kind)
        assertSame(town, accounts.named("TOWN-SPAWN", create = false))
        assertNull(accounts.player("town-spawn"))
        assertNull(accounts.named("missing", create = false))
    }

    @Test
    fun banksAreCreatedOnceAndDeleted() {
        val owner = UUID.randomUUID()
        val bank = accounts.createBank("Vault", owner)!!
        assertEquals(Kind.BANK, bank.kind)
        assertEquals(owner, bank.owner)
        assertNull(accounts.createBank("vault", owner))
        assertSame(bank, accounts.bank("VAULT"))
        assertEquals(listOf("Vault"), accounts.banks().map { it.name })
        accounts.delete(bank)
        assertNull(accounts.bank("vault"))
        assertNull(accounts.get(bank.id))
        assertSame(bank, sink.deleted.single())
    }

    @Test
    fun remoteAccountDataCreatesAccountWithoutRecording() {
        val id = UUID.randomUUID()
        accounts.applyRemote(Packet.AccountData("s2", id, "Steve", Kind.PLAYER, 700, paymentsOff = true, messagesOff = true, hidden = false, owner = null))
        val account = accounts.player("steve")!!
        assertEquals(id, account.id)
        assertEquals(700, account.balance.get())
        assertTrue(account.paymentsOff && account.messagesOff)
        assertTrue(sink.created.isEmpty())
    }

    @Test
    fun remoteAccountDataUpdatesExistingAccountButNotBalance() {
        val account = player("Steve", 500)
        accounts.applyRemote(Packet.AccountData("s2", account.id, "Steven", Kind.PLAYER, 0, paymentsOff = true, messagesOff = false, hidden = true, owner = null))
        assertSame(account, accounts.player("steven"))
        assertNull(accounts.player("steve"))
        assertEquals(500, account.balance.get())
        assertTrue(account.paymentsOff && account.hidden)
        assertTrue(sink.updated.isEmpty())
    }

    @Test
    fun remoteRemovalDeletesWithoutRecording() {
        val bank = accounts.createBank("Vault", UUID.randomUUID())!!
        accounts.removeRemote(bank.id)
        assertNull(accounts.bank("vault"))
        assertTrue(sink.deleted.isEmpty())
    }

    @Test
    fun reloadingBalancesReplacesMemory() {
        val account = player("986", 500)
        accounts.reloadBalances(mapOf(account.id to 1200L))
        assertEquals(1200, account.balance.get())
    }

    @Test
    fun remoteChangesApplyWithoutRecording() {
        val account = player("986", 500)
        accounts.applyRemote(account.id, -200)
        assertEquals(300, account.balance.get())
        assertTrue(sink.entries.isEmpty())
    }
}
