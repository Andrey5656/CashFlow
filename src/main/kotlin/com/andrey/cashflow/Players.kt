package com.andrey.cashflow

import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent

class Players(private val plugin: CashFlow, private val commands: Commands) : Listener {

    @EventHandler(priority = EventPriority.LOWEST)
    fun onJoin(event: PlayerJoinEvent) {
        val player = event.player
        val account = plugin.accounts.join(player.uniqueId, player.name, player.hasPermission(CashFlow.HIDDEN))
        plugin.network?.joined(player.uniqueId)
        plugin.database.execute {
            plugin.flush()
            val notices = runCatching { plugin.storage.takeNotices(player.uniqueId) }
                .onFailure { plugin.logger.warning("Could not load payment notices: ${it.message}") }
                .getOrNull() ?: return@execute
            if (notices.isEmpty() || account.messagesOff) return@execute
            val totals = LinkedHashMap<String, Long>()
            notices.forEach { totals.merge(it.payer, it.amount, Long::plus) }
            player.scheduler.run(plugin, {
                totals.forEach { (payer, amount) ->
                    plugin.text.send(player, "pay-offline", plugin.text.tag("player", payer), plugin.text.moneyTag(amount))
                }
            }, null)
        }
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        plugin.network?.quit(event.player.uniqueId)
        commands.forget(event.player.uniqueId)
    }
}
