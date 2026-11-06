package com.andrey.cashflow

import org.bukkit.configuration.file.FileConfiguration

data class SoundSetting(val sound: String, val volume: Float, val pitch: Float)

class Settings(config: FileConfiguration, messages: FileConfiguration) {

    val decimals = config.getInt("currency.decimals", 2).coerceIn(0, 2)
    val singular = config.getString("currency.singular", "Dollar")!!
    val plural = config.getString("currency.plural", "Dollars")!!
    val shortFormat = !config.getString("currency.format", "short").equals("full", ignoreCase = true)
    val money = config.getString("currency.money", "<#00FF00>$</#00FF00> <amount>")!!
    val startingBalance = cents(config.getDouble("starting-balance", 0.0))
    val maxBalance = cents(config.getDouble("max-balance", 0.0))
    val payMinimum = cents(config.getDouble("pay.minimum", 1.0))
    val payCooldown = (config.getDouble("pay.cooldown", 1.0) * 1000).toLong().coerceAtLeast(0)
    val logDays = config.getInt("log-days", 30).coerceAtLeast(1)
    val sounds: Map<String, SoundSetting> = listOf("pay-sent", "pay-received", "error").mapNotNull { key ->
        config.getConfigurationSection("sounds.$key")?.let { section ->
            val sound = section.getString("sound").orEmpty()
            if (sound.isEmpty()) null else key to SoundSetting(sound, section.getDouble("volume", 1.0).toFloat(), section.getDouble("pitch", 1.0).toFloat())
        }
    }.toMap()
    val messages: Map<String, String> = messages.getKeys(true).filter { messages.isString(it) }.associateWith { messages.getString(it)!! }

    private fun cents(value: Double): Long = (Money.fromDouble(value) ?: 0L).coerceAtLeast(0)
}
