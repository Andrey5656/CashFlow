package com.andrey.cashflow

import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

enum class Kind(val id: Int) {
    PLAYER(0), UNKNOWN(1), NAMED(2), BANK(3);

    companion object {
        fun of(id: Int): Kind = entries.first { it.id == id }
    }
}

enum class EntryType(val id: Int) {
    PAY_SENT(0), PAY_RECEIVED(1), ADMIN_GIVE(2), ADMIN_TAKE(3), ADMIN_SET(4), PLUGIN_DEPOSIT(5), PLUGIN_WITHDRAW(6), ROLLBACK(7);

    companion object {
        fun of(id: Int): EntryType = entries.first { it.id == id }
    }
}

class Account(
    val id: UUID,
    name: String,
    kind: Kind,
    balance: Long,
    paymentsOff: Boolean = false,
    messagesOff: Boolean = false,
    hidden: Boolean = false,
    owner: UUID? = null,
) {
    val balance = AtomicLong(balance)
    val initial = balance
    @Volatile var name = name
    @Volatile var kind = kind
    @Volatile var paymentsOff = paymentsOff
    @Volatile var messagesOff = messagesOff
    @Volatile var hidden = hidden
    @Volatile var owner = owner
}

data class Entry(
    val account: UUID,
    val time: Long,
    val amount: Long,
    val balance: Long,
    val type: EntryType,
    val other: String?,
    val source: String?,
)

data class Notice(val account: UUID, val payer: String, val amount: Long, val time: Long)

sealed interface Result {
    data class Success(val balance: Long) : Result
    data object NotEnough : Result
    data object OverMax : Result
}

interface Sink {
    fun created(account: Account)
    fun changed(account: Account, entry: Entry)
    fun updated(account: Account)
    fun deleted(account: Account)
}
