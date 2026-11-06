package com.andrey.cashflow

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder
import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.bukkit.command.TabExecutor
import org.bukkit.entity.Player
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.ceil

class Commands(private val plugin: CashFlow) : TabExecutor {

    private val text get() = plugin.text
    private val settings get() = plugin.settings
    private val accounts get() = plugin.accounts
    private val cooldowns = ConcurrentHashMap<UUID, Long>()

    fun forget(player: UUID) {
        cooldowns.remove(player)
    }

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<String>): Boolean {
        when (command.name) {
            "balance" -> balance(sender, args)
            "pay" -> pay(sender, args)
            "paytoggle" -> payToggle(sender)
            "paymsgtoggle" -> payMsgToggle(sender)
            "baltop" -> baltop(sender, args)
            "history" -> history(sender, args)
        }
        return true
    }

    override fun onTabComplete(sender: CommandSender, command: Command, label: String, args: Array<String>): List<String> {
        if (args.size != 1) return emptyList()
        return when (command.name) {
            "balance" -> if (sender.hasPermission("cashflow.balance.others")) names(sender, args[0]) else emptyList()
            "pay" -> names(sender, args[0])
            else -> emptyList()
        }
    }

    private fun names(sender: CommandSender, prefix: String): List<String> =
        plugin.server.onlinePlayers
            .filter { it != sender && (sender !is Player || sender.canSee(it)) && it.name.startsWith(prefix, ignoreCase = true) }
            .map { it.name }

    private fun allowed(sender: CommandSender, permission: String): Boolean {
        if (sender.hasPermission(permission)) return true
        text.error(sender, "no-permission")
        return false
    }

    private fun balance(sender: CommandSender, args: Array<String>) {
        if (!allowed(sender, "cashflow.balance")) return
        if (args.isEmpty()) {
            val player = sender as? Player ?: return text.error(sender, "usage-balance")
            val account = accounts.get(player.uniqueId) ?: return
            text.send(sender, "balance", text.moneyTag(account.balance.get()))
            return
        }
        if (!allowed(sender, "cashflow.balance.others")) return
        val target = accounts.player(args[0]) ?: return text.error(sender, "not-found")
        text.send(sender, "balance-other", text.tag("player", target.name), text.moneyTag(target.balance.get()))
    }

    private fun pay(sender: CommandSender, args: Array<String>) {
        if (!allowed(sender, "cashflow.pay")) return
        val player = sender as? Player ?: return text.error(sender, "players-only")
        if (args.size != 2) return text.error(sender, "usage-pay")
        val target = accounts.player(args[0]) ?: return text.error(sender, "not-found")
        if (target.id == player.uniqueId) return text.error(sender, "pay-self")
        val amount = Money.parse(args[1], settings.decimals) ?: return text.error(sender, "invalid-amount")
        if (amount < settings.payMinimum) return text.error(sender, "pay-minimum", text.moneyTag(settings.payMinimum))
        val now = System.currentTimeMillis()
        val last = cooldowns[player.uniqueId]
        if (last != null && now - last < settings.payCooldown) {
            val seconds = ceil((settings.payCooldown - (now - last)) / 1000.0).toInt()
            return text.error(sender, "pay-cooldown", text.tag("seconds", seconds.toString()))
        }
        if (target.paymentsOff) return text.error(sender, "pay-off", text.tag("player", target.name))
        val payer = accounts.get(player.uniqueId) ?: accounts.join(player.uniqueId, player.name, player.hasPermission(CashFlow.HIDDEN))
        when (accounts.transfer(payer, target, amount)) {
            Result.NotEnough -> text.error(sender, "no-money")
            Result.OverMax -> text.error(sender, "max-balance", text.tag("player", target.name))
            is Result.Success -> {
                cooldowns[player.uniqueId] = now
                text.send(player, "pay-sent", text.tag("player", target.name), text.moneyTag(amount))
                text.actionBar(player, "pay-sent-action-bar", text.tag("player", target.name), text.moneyTag(amount))
                text.sound(player, "pay-sent")
                plugin.deliverPayment(target, player.name, amount)
            }
        }
    }

    private fun payToggle(sender: CommandSender) {
        if (!allowed(sender, "cashflow.paytoggle")) return
        val player = sender as? Player ?: return text.error(sender, "players-only")
        val account = accounts.get(player.uniqueId) ?: return
        accounts.setPaymentsOff(account, !account.paymentsOff)
        text.send(sender, if (account.paymentsOff) "paytoggle-off" else "paytoggle-on")
    }

    private fun payMsgToggle(sender: CommandSender) {
        if (!allowed(sender, "cashflow.paymsgtoggle")) return
        val player = sender as? Player ?: return text.error(sender, "players-only")
        val account = accounts.get(player.uniqueId) ?: return
        accounts.setMessagesOff(account, !account.messagesOff)
        text.send(sender, if (account.messagesOff) "paymsgtoggle-off" else "paymsgtoggle-on")
    }

    private fun baltop(sender: CommandSender, args: Array<String>) {
        if (!allowed(sender, "cashflow.baltop")) return
        val baltop = plugin.baltop
        val pages = baltop.pages(PAGE)
        val page = (args.getOrNull(0)?.toIntOrNull() ?: 1).coerceIn(1, pages)
        val rows = baltop.page(page, PAGE)
        text.send(sender, "baltop-header", text.tag("page", page.toString()), text.tag("pages", pages.toString()))
        if (rows.isEmpty()) text.send(sender, "baltop-empty")
        rows.forEach {
            text.send(sender, "baltop-line", text.tag("rank", it.rank.toString()), text.tag("player", it.name), text.moneyTag(it.balance))
        }
        if (sender is Player) {
            val rank = baltop.rank(sender.uniqueId)
            val row = rank?.let(baltop::at)
            if (row != null) text.send(sender, "baltop-self", text.tag("rank", row.rank.toString()), text.moneyTag(row.balance))
        }
        if (pages > 1) {
            val previous = if (page > 1) arrow("baltop-previous", page - 1) else Component.empty()
            val next = if (page < pages) arrow("baltop-next", page + 1) else Component.empty()
            text.send(
                sender,
                "baltop-page",
                text.tag("page", page.toString()),
                text.tag("pages", pages.toString()),
                Placeholder.component("previous", previous),
                Placeholder.component("next", next),
            )
        }
    }

    private fun arrow(key: String, page: Int): Component =
        (text.render(key) ?: Component.empty()).clickEvent(ClickEvent.runCommand("/baltop $page"))

    private fun history(sender: CommandSender, args: Array<String>) {
        if (!allowed(sender, "cashflow.history")) return
        val player = sender as? Player ?: return text.error(sender, "players-only")
        val page = (args.getOrNull(0)?.toIntOrNull() ?: 1).coerceAtLeast(1)
        showHistory(plugin, sender, player.uniqueId, null, page)
    }

    companion object {
        const val PAGE = 10

        fun showHistory(plugin: CashFlow, sender: CommandSender, account: UUID, owner: String?, page: Int) {
            val text = plugin.text
            plugin.database.execute {
                plugin.flush()
                val loaded = runCatching {
                    val total = plugin.storage.countHistory(account)
                    val pages = maxOf(1, (total + PAGE - 1) / PAGE)
                    val shown = page.coerceAtMost(pages)
                    Triple(shown, pages, plugin.storage.history(account, (shown - 1) * PAGE, PAGE))
                }.onFailure { plugin.logger.warning("Could not load history: ${it.message}") }.getOrNull() ?: return@execute
                val (shown, pages, entries) = loaded
                val now = System.currentTimeMillis()
                text.run(sender) {
                    val header = if (owner == null) "history-header" else "history-other-header"
                    text.send(sender, header, text.tag("player", owner.orEmpty()), text.tag("page", shown.toString()), text.tag("pages", pages.toString()))
                    if (entries.isEmpty()) text.send(sender, "history-empty")
                    entries.forEach { entry -> text.historyLine(entry, owner, now)?.let(sender::sendMessage) }
                }
            }
        }
    }
}
