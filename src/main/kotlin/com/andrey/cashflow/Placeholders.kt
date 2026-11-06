package com.andrey.cashflow

import me.clip.placeholderapi.expansion.PlaceholderExpansion
import org.bukkit.OfflinePlayer

class Placeholders(private val plugin: CashFlow) : PlaceholderExpansion() {

    override fun getIdentifier(): String = "cashflow"

    override fun getAuthor(): String = "Andrey"

    override fun getVersion(): String = plugin.pluginMeta.version

    override fun persist(): Boolean = true

    override fun onRequest(player: OfflinePlayer?, params: String): String? {
        val decimals = plugin.settings.decimals
        if (params.startsWith("top_")) {
            val parts = params.split('_', limit = 3)
            val row = parts.getOrNull(1)?.toIntOrNull()?.let(plugin.baltop::at)
            return when (parts.getOrNull(2)) {
                "name" -> row?.name.orEmpty()
                "balance" -> row?.let { Money.raw(it.balance) }.orEmpty()
                "balance_formatted" -> row?.let { Money.short(it.balance, decimals) }.orEmpty()
                "balance_commas" -> row?.let { Money.full(it.balance, decimals) }.orEmpty()
                else -> null
            }
        }
        val account = player?.let { plugin.accounts.get(it.uniqueId) }
        val balance = account?.balance?.get() ?: plugin.settings.startingBalance
        return when (params) {
            "balance" -> Money.raw(balance)
            "balance_formatted" -> Money.short(balance, decimals)
            "balance_commas" -> Money.full(balance, decimals)
            "rank" -> player?.let { plugin.baltop.rank(it.uniqueId)?.toString() } ?: "-"
            "paytoggle" -> if (account?.paymentsOff == true) "off" else "on"
            else -> null
        }
    }
}
