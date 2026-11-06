package com.andrey.cashflow

import java.util.UUID

class Baltop(private val accounts: Accounts) {

    data class Row(val rank: Int, val id: UUID, val name: String, val balance: Long)

    @Volatile private var rows: List<Row> = emptyList()
    @Volatile private var ranks: Map<UUID, Int> = emptyMap()

    fun rebuild() {
        val sorted = accounts.all()
            .filter { it.kind == Kind.PLAYER && !it.hidden }
            .map { Row(0, it.id, it.name, it.balance.get()) }
            .sortedWith(compareByDescending<Row> { it.balance }.thenBy { it.name.lowercase() })
        val built = sorted.mapIndexed { index, row -> row.copy(rank = index + 1) }
        rows = built
        ranks = built.associate { it.id to it.rank }
    }

    fun page(page: Int, size: Int): List<Row> {
        val snapshot = rows
        val from = (page - 1) * size
        if (page < 1 || from >= snapshot.size) return emptyList()
        return snapshot.subList(from, minOf(from + size, snapshot.size))
    }

    fun pages(size: Int): Int = maxOf(1, (rows.size + size - 1) / size)

    fun rank(id: UUID): Int? = ranks[id]

    fun at(rank: Int): Row? = rows.getOrNull(rank - 1)
}
