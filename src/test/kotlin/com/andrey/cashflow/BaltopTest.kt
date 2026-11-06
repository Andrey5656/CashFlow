package com.andrey.cashflow

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BaltopTest {

    private val accounts = Accounts(object : Sink {
        override fun created(account: Account) {}
        override fun changed(account: Account, entry: Entry) {}
        override fun updated(account: Account) {}
        override fun deleted(account: Account) {}
    })
    private val baltop = Baltop(accounts)

    private fun player(name: String, cents: Long, hidden: Boolean = false): Account {
        val account = accounts.join(UUID.randomUUID(), name, hidden)
        account.balance.set(cents)
        return account
    }

    @Test
    fun ranksPlayersByBalance() {
        player("low", 100)
        val top = player("top", 900)
        player("mid", 500)
        baltop.rebuild()
        assertEquals(listOf("top", "mid", "low"), baltop.page(1, 10).map { it.name })
        assertEquals(Baltop.Row(1, top.id, "top", 900), baltop.page(1, 10).first())
        assertEquals(1, baltop.rank(top.id))
    }

    @Test
    fun leavesOutHiddenPlayersAndOtherAccounts() {
        player("staff", 9999, hidden = true)
        player("986", 100)
        accounts.getOrCreate(UUID.randomUUID(), "ghost", Kind.UNKNOWN).balance.set(5000)
        accounts.named("town", create = true)!!.balance.set(5000)
        accounts.createBank("bank", UUID.randomUUID())!!.balance.set(5000)
        baltop.rebuild()
        assertEquals(listOf("986"), baltop.page(1, 10).map { it.name })
    }

    @Test
    fun breaksTiesByName() {
        player("b", 100)
        player("a", 100)
        baltop.rebuild()
        assertEquals(listOf("a", "b"), baltop.page(1, 10).map { it.name })
    }

    @Test
    fun pagesAreTenLong() {
        repeat(25) { player("p$it", 1000L - it) }
        baltop.rebuild()
        assertEquals(3, baltop.pages(10))
        assertEquals(10, baltop.page(2, 10).size)
        assertEquals(11, baltop.page(2, 10).first().rank)
        assertEquals(5, baltop.page(3, 10).size)
        assertEquals(0, baltop.page(4, 10).size)
        assertEquals("p0", baltop.at(1)?.name)
        assertNull(baltop.at(26))
    }

    @Test
    fun emptyListHasOnePage() {
        baltop.rebuild()
        assertEquals(1, baltop.pages(10))
    }

    @Test
    fun usesSnapshotUntilRebuilt() {
        val account = player("986", 100)
        baltop.rebuild()
        account.balance.set(999)
        assertEquals(100, baltop.page(1, 10).single().balance)
    }
}
