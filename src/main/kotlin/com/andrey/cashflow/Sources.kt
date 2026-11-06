package com.andrey.cashflow

import org.bukkit.plugin.java.JavaPlugin
import java.util.Optional
import java.util.concurrent.ConcurrentHashMap

class Sources(private val ignored: Set<ClassLoader>) {

    private val walker = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE)
    private val names = ConcurrentHashMap<ClassLoader, Optional<String>>()

    fun caller(): String = walker.walk { frames ->
        frames.map { it.declaringClass }
            .filter { it.classLoader != null && it.classLoader !in ignored }
            .map { name(it) }
            .filter { it.isPresent }
            .map { it.get() }
            .findFirst()
            .orElse("Unknown")
    }

    private fun name(type: Class<*>): Optional<String> = names.computeIfAbsent(type.classLoader) {
        Optional.ofNullable(runCatching { JavaPlugin.getProvidingPlugin(type).name }.getOrNull())
    }
}
