package com.andrey.cashflow

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class Accounts(private val sink: Sink, private val clock: () -> Long = System::currentTimeMillis) {

    @Volatile var startingBalance = 0L
    @Volatile var maxBalance = 0L

    private val byId = ConcurrentHashMap<UUID, Account>()
    private val players = ConcurrentHashMap<String, Account>()
    private val named = ConcurrentHashMap<String, Account>()
    private val banks = ConcurrentHashMap<String, Account>()

    fun load(list: Collection<Account>) {
        byId.clear()
        players.clear()
        named.clear()
        banks.clear()
        list.forEach { byId[it.id] = it; index(it) }
    }

    fun all(): Collection<Account> = byId.values

    fun get(id: UUID): Account? = byId[id]

    fun player(name: String): Account? = players[name.lowercase()]

    fun named(name: String, create: Boolean): Account? {
        named[name.lowercase()]?.let { return it }
        if (!create) return null
        return getOrCreate(namedId(name), name, Kind.NAMED)
    }

    fun bank(name: String): Account? = banks[name.lowercase()]

    fun banks(): List<Account> = banks.values.toList()

    fun getOrCreate(id: UUID, name: String, kind: Kind): Account {
        byId[id]?.let { return it }
        var made = false
        val account = byId.computeIfAbsent(id) { made = true; Account(id, name, kind, startingBalance) }
        if (made) {
            index(account)
            sink.created(account)
        }
        return account
    }

    fun createBank(name: String, owner: UUID): Account? {
        if (banks.containsKey(name.lowercase())) return null
        var made = false
        val account = byId.computeIfAbsent(bankId(name)) { made = true; Account(it, name, Kind.BANK, 0, owner = owner) }
        if (!made) return null
        index(account)
        sink.created(account)
        return account
    }

    fun delete(account: Account) {
        if (!byId.remove(account.id, account)) return
        unindex(account)
        sink.deleted(account)
    }

    fun join(id: UUID, name: String, hidden: Boolean): Account {
        val account = getOrCreate(id, name, Kind.PLAYER)
        var changed = false
        synchronized(account) {
            if (account.kind != Kind.PLAYER) {
                unindex(account)
                account.kind = Kind.PLAYER
                changed = true
            }
            if (account.name != name) {
                players.remove(account.name.lowercase(), account)
                account.name = name
                changed = true
            }
            if (account.hidden != hidden) {
                account.hidden = hidden
                changed = true
            }
            players[name.lowercase()] = account
        }
        if (changed) sink.updated(account)
        return account
    }

    fun setPaymentsOff(account: Account, off: Boolean) {
        account.paymentsOff = off
        sink.updated(account)
    }

    fun setMessagesOff(account: Account, off: Boolean) {
        account.messagesOff = off
        sink.updated(account)
    }

    fun deposit(account: Account, cents: Long, type: EntryType, other: String?, source: String?): Result {
        val next = add(account, cents) ?: return Result.OverMax
        record(account, cents, next, type, other, source)
        return Result.Success(next)
    }

    fun withdraw(account: Account, cents: Long, type: EntryType, other: String?, source: String?): Result {
        val next = take(account, cents) ?: return Result.NotEnough
        record(account, -cents, next, type, other, source)
        return Result.Success(next)
    }

    fun set(account: Account, cents: Long, type: EntryType, other: String?): Result {
        if (maxBalance > 0 && cents > maxBalance) return Result.OverMax
        val old = account.balance.getAndSet(cents)
        record(account, cents - old, cents, type, other, null)
        return Result.Success(cents)
    }

    fun transfer(from: Account, to: Account, cents: Long): Result {
        val left = take(from, cents) ?: return Result.NotEnough
        val received = add(to, cents)
        if (received == null) {
            from.balance.addAndGet(cents)
            return Result.OverMax
        }
        record(from, -cents, left, EntryType.PAY_SENT, to.name, null)
        record(to, cents, received, EntryType.PAY_RECEIVED, from.name, null)
        return Result.Success(left)
    }

    fun applyRemote(id: UUID, delta: Long) {
        byId[id]?.balance?.addAndGet(delta)
    }

    fun applyRemote(data: Packet.AccountData) {
        if (byId[data.id] == null) {
            val account = Account(data.id, data.name, data.kind, data.balance, data.paymentsOff, data.messagesOff, data.hidden, data.owner)
            if (byId.putIfAbsent(data.id, account) == null) {
                index(account)
                return
            }
        }
        val account = byId[data.id] ?: return
        synchronized(account) {
            unindex(account)
            account.name = data.name
            account.kind = data.kind
            account.paymentsOff = data.paymentsOff
            account.messagesOff = data.messagesOff
            account.hidden = data.hidden
            account.owner = data.owner
            index(account)
        }
    }

    fun removeRemote(id: UUID) {
        byId.remove(id)?.let(::unindex)
    }

    fun reloadBalances(balances: Map<UUID, Long>) {
        balances.forEach { (id, balance) -> byId[id]?.balance?.set(balance) }
    }

    private fun add(account: Account, cents: Long): Long? {
        while (true) {
            val current = account.balance.get()
            val next = runCatching { Math.addExact(current, cents) }.getOrNull() ?: return null
            if (maxBalance > 0 && next > maxBalance && cents > 0) return null
            if (account.balance.compareAndSet(current, next)) return next
        }
    }

    private fun take(account: Account, cents: Long): Long? {
        while (true) {
            val current = account.balance.get()
            if (current < cents) return null
            val next = current - cents
            if (account.balance.compareAndSet(current, next)) return next
        }
    }

    private fun record(account: Account, amount: Long, balance: Long, type: EntryType, other: String?, source: String?) {
        sink.changed(account, Entry(account.id, clock(), amount, balance, type, other, source))
    }

    private fun index(account: Account) {
        val key = account.name.lowercase()
        when (account.kind) {
            Kind.PLAYER -> players[key] = account
            Kind.NAMED -> named[key] = account
            Kind.BANK -> banks[key] = account
            Kind.UNKNOWN -> {}
        }
    }

    private fun unindex(account: Account) {
        val key = account.name.lowercase()
        when (account.kind) {
            Kind.PLAYER -> players.remove(key, account)
            Kind.NAMED -> named.remove(key, account)
            Kind.BANK -> banks.remove(key, account)
            Kind.UNKNOWN -> {}
        }
    }

    companion object {
        fun namedId(name: String): UUID = UUID.nameUUIDFromBytes("CashFlow:${name.lowercase()}".toByteArray(Charsets.UTF_8))
        fun bankId(name: String): UUID = UUID.nameUUIDFromBytes("CashFlow-bank:${name.lowercase()}".toByteArray(Charsets.UTF_8))
    }
}
