package com.andrey.cashflow

import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.bukkit.command.TabExecutor
import org.bukkit.entity.Player

class EcoCommand(private val plugin: CashFlow) : TabExecutor {

    private val text get() = plugin.text
    private val settings get() = plugin.settings
    private val accounts get() = plugin.accounts

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<String>): Boolean {
        if (!sender.hasPermission(PERMISSION)) {
            text.error(sender, "no-permission")
            return true
        }
        val silent = args.any { it.equals("-s", ignoreCase = true) }
        val rest = args.filterNot { it.equals("-s", ignoreCase = true) }
        when (rest.getOrNull(0)?.lowercase()) {
            "give", "take", "set" -> change(sender, rest, silent)
            "reset" -> reset(sender, rest, silent)
            "history" -> history(sender, rest)
            "rollback" -> rollback(sender, rest)
            "reload" -> {
                plugin.reloadSettings()
                text.send(sender, "reload")
            }
            else -> text.error(sender, "usage-eco")
        }
        return true
    }

    override fun onTabComplete(sender: CommandSender, command: Command, label: String, args: Array<String>): List<String> {
        if (!sender.hasPermission(PERMISSION)) return emptyList()
        val sub = args[0].lowercase()
        val options = when (args.size) {
            1 -> SUBCOMMANDS
            2 -> if (sub in SUBCOMMANDS && sub != "reload") plugin.server.onlinePlayers.map { it.name } else emptyList()
            3 -> if (sub == "reset") listOf("-s") else emptyList()
            4 -> if (sub in listOf("give", "take", "set")) listOf("-s") else emptyList()
            else -> emptyList()
        }
        return options.filter { it.startsWith(args.last(), ignoreCase = true) }
    }

    private fun admin(sender: CommandSender): String = if (sender is Player) sender.name else "Console"

    private fun change(sender: CommandSender, args: List<String>, silent: Boolean) {
        if (args.size != 3) return text.error(sender, "usage-eco")
        val sub = args[0].lowercase()
        val target = accounts.player(args[1]) ?: return text.error(sender, "not-found")
        val amount = Money.parse(args[2], settings.decimals, allowZero = sub == "set") ?: return text.error(sender, "invalid-amount")
        val player = text.tag("player", target.name)
        val result = when (sub) {
            "give" -> accounts.deposit(target, amount, EntryType.ADMIN_GIVE, admin(sender), null)
            "take" -> accounts.withdraw(target, amount, EntryType.ADMIN_TAKE, admin(sender), null)
            else -> accounts.set(target, amount, EntryType.ADMIN_SET, admin(sender))
        }
        when (result) {
            Result.NotEnough -> text.error(sender, "eco-not-enough", player, text.moneyTag(target.balance.get()))
            Result.OverMax -> text.error(sender, "max-balance", player)
            is Result.Success -> {
                text.send(sender, "eco-$sub", player, text.moneyTag(amount))
                if (!silent) tell(target, if (sub == "give") "eco-received" else if (sub == "take") "eco-taken" else "eco-set-target", amount)
            }
        }
    }

    private fun reset(sender: CommandSender, args: List<String>, silent: Boolean) {
        if (args.size != 2) return text.error(sender, "usage-eco")
        val target = accounts.player(args[1]) ?: return text.error(sender, "not-found")
        val amount = settings.startingBalance
        when (accounts.set(target, amount, EntryType.ADMIN_SET, admin(sender))) {
            is Result.Success -> {
                text.send(sender, "eco-reset", text.tag("player", target.name), text.moneyTag(amount))
                if (!silent) tell(target, "eco-set-target", amount)
            }
            else -> text.error(sender, "max-balance", text.tag("player", target.name))
        }
    }

    private fun tell(target: Account, key: String, amount: Long) {
        val online = plugin.server.getPlayer(target.id) ?: return
        online.scheduler.run(plugin, { text.send(online, key, text.moneyTag(amount)) }, null)
    }

    private fun history(sender: CommandSender, args: List<String>) {
        if (args.size !in 2..3) return text.error(sender, "usage-eco")
        val target = accounts.player(args[1]) ?: return text.error(sender, "not-found")
        val page = (args.getOrNull(2)?.toIntOrNull() ?: 1).coerceAtLeast(1)
        Commands.showHistory(plugin, sender, target.id, target.name, page)
    }

    private fun rollback(sender: CommandSender, args: List<String>) {
        if (args.size != 3) return text.error(sender, "usage-eco")
        val target = accounts.player(args[1]) ?: return text.error(sender, "not-found")
        val duration = Time.parse(args[2]) ?: return text.error(sender, "invalid-time")
        val admin = admin(sender)
        val player = text.tag("player", target.name)
        plugin.database.execute {
            plugin.flush()
            val balance = runCatching { plugin.storage.balanceAt(target.id, System.currentTimeMillis() - duration) }
                .onFailure { plugin.logger.warning("Could not load the log: ${it.message}") }
                .getOrElse { return@execute }
            if (balance == null) return@execute text.run(sender) { text.error(sender, "rollback-none", player) }
            val result = accounts.set(target, balance, EntryType.ROLLBACK, admin)
            text.run(sender) {
                if (result is Result.Success) text.send(sender, "rollback", player, text.moneyTag(balance))
                else text.error(sender, "max-balance", player)
            }
        }
    }

    companion object {
        private const val PERMISSION = "cashflow.admin"
        private val SUBCOMMANDS = listOf("give", "take", "set", "reset", "history", "rollback", "reload")
    }
}
