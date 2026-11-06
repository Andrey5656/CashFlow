package com.andrey.cashflow

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.SoundCategory
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import kotlin.math.abs

class Text(private val plugin: CashFlow) {

    private val mini = MiniMessage.miniMessage()
    private val settings get() = plugin.settings

    fun amount(cents: Long): String =
        if (settings.shortFormat) Money.short(cents, settings.decimals) else Money.full(cents, settings.decimals)

    fun money(cents: Long): Component = mini.deserialize(settings.money, Placeholder.unparsed("amount", amount(cents)))

    fun plainMoney(cents: Long): String = PlainTextComponentSerializer.plainText().serialize(money(cents))

    fun moneyTag(cents: Long): TagResolver = Placeholder.component("money", money(cents))

    fun tag(name: String, value: String): TagResolver = Placeholder.unparsed(name, value)

    fun render(key: String, vararg tags: TagResolver): Component? {
        val raw = settings.messages[key]
        if (raw.isNullOrEmpty()) return null
        return mini.deserialize(raw, *tags)
    }

    fun send(to: CommandSender, key: String, vararg tags: TagResolver) {
        render(key, *tags)?.let(to::sendMessage)
    }

    fun actionBar(to: Player, key: String, vararg tags: TagResolver) {
        render(key, *tags)?.let(to::sendActionBar)
    }

    fun error(to: CommandSender, key: String, vararg tags: TagResolver) {
        send(to, key, *tags)
        if (to is Player) sound(to, "error")
    }

    fun sound(player: Player, key: String) {
        val sound = settings.sounds[key] ?: return
        player.playSound(player, sound.sound, SoundCategory.MASTER, sound.volume, sound.pitch)
    }

    fun run(target: CommandSender, block: () -> Unit) {
        if (target is Player) target.scheduler.run(plugin, { block() }, null)
        else plugin.server.globalRegionScheduler.execute(plugin, block)
    }

    fun historyLine(entry: Entry, owner: String?, now: Long): Component? {
        val section = if (owner == null) "history" else "history-other"
        val shown = if (entry.type == EntryType.ADMIN_SET || entry.type == EntryType.ROLLBACK) entry.balance else abs(entry.amount)
        val inner = render(
            "$section." + entry.type.name.lowercase().replace('_', '-'),
            tag("other", entry.other ?: entry.source ?: "Unknown"),
            tag("player", owner.orEmpty()),
            moneyTag(shown),
        ) ?: return null
        return render("history-line", tag("time", Time.ago(now - entry.time)), Placeholder.component("entry", inner))
    }
}
