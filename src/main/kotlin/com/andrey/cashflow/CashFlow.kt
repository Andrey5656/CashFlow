package com.andrey.cashflow

import net.milkbowl.vault.economy.Economy
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import org.bukkit.plugin.ServicePriority
import org.bukkit.plugin.java.JavaPlugin
import java.io.File
import java.io.InputStreamReader
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

class CashFlow : JavaPlugin() {

    @Volatile lateinit var settings: Settings
        private set
    lateinit var storage: Storage
        private set
    lateinit var saver: Saver
        private set
    lateinit var accounts: Accounts
        private set
    lateinit var baltop: Baltop
        private set
    lateinit var text: Text
        private set
    lateinit var database: ScheduledExecutorService
        private set
    var network: Network? = null
        private set
    private var economy: VaultEconomy? = null
    @Volatile private var failing = false

    override fun onEnable() {
        saveDefaultConfig()
        if (!File(dataFolder, "messages.yml").exists()) saveResource("messages.yml", false)
        settings = readSettings()
        text = Text(this)

        val mysql = config.getString("storage.type", "sqlite").equals("mysql", ignoreCase = true)
        storage = try {
            openStorage(mysql).also { it.createTables() }
        } catch (e: Exception) {
            logger.severe("Could not open the database: ${e.message}")
            server.pluginManager.disablePlugin(this)
            return
        }
        storage.purgeLog(System.currentTimeMillis() - settings.logDays * DAY)

        val networked = config.getBoolean("network.enabled", false) && mysql
        if (config.getBoolean("network.enabled", false) && !mysql) {
            logger.severe("Network sync needs storage type mysql. CashFlow runs without network sync.")
        }
        saver = Saver(storage) { networked }
        if (networked) {
            network = Network(
                this,
                config.getString("network.redis.host", "localhost")!!,
                config.getInt("network.redis.port", 6379),
                config.getString("network.redis.password", "")!!,
            )
        }
        val sinks = listOfNotNull<Sink>(saver, network)
        accounts = Accounts(object : Sink {
            override fun created(account: Account) = sinks.forEach { it.created(account) }
            override fun changed(account: Account, entry: Entry) = sinks.forEach { it.changed(account, entry) }
            override fun updated(account: Account) = sinks.forEach { it.updated(account) }
            override fun deleted(account: Account) = sinks.forEach { it.deleted(account) }
        })
        applyLimits()
        accounts.load(storage.loadAccounts())

        database = Executors.newSingleThreadScheduledExecutor { Thread(it, "CashFlow Database").apply { isDaemon = true } }
        database.scheduleWithFixedDelay(::flush, 500, 500, TimeUnit.MILLISECONDS)

        baltop = Baltop(accounts)
        baltop.rebuild()
        server.asyncScheduler.runAtFixedRate(this, { baltop.rebuild() }, 30, 30, TimeUnit.SECONDS)
        server.asyncScheduler.runAtFixedRate(this, {
            database.execute {
                runCatching { storage.purgeLog(System.currentTimeMillis() - settings.logDays * DAY) }
                    .onFailure { logger.warning("Could not clean the log: ${it.message}") }
            }
        }, 1, 1, TimeUnit.DAYS)

        val ignored = setOfNotNull(javaClass.classLoader, server.pluginManager.getPlugin("Vault")?.javaClass?.classLoader)
        economy = VaultEconomy(this, Sources(ignored)).also {
            server.servicesManager.register(Economy::class.java, it, this, ServicePriority.Highest)
        }

        val commands = Commands(this)
        listOf("balance", "pay", "paytoggle", "paymsgtoggle", "baltop", "history").forEach {
            getCommand(it)!!.setExecutor(commands)
            getCommand(it)!!.tabCompleter = commands
        }
        val eco = EcoCommand(this)
        getCommand("eco")!!.setExecutor(eco)
        getCommand("eco")!!.tabCompleter = eco
        server.pluginManager.registerEvents(Players(this, commands), this)

        server.onlinePlayers.forEach {
            accounts.join(it.uniqueId, it.name, it.hasPermission(HIDDEN))
            network?.joined(it.uniqueId)
        }
        network?.start()

        if (server.pluginManager.isPluginEnabled("PlaceholderAPI")) Placeholders(this).register()
    }

    override fun onDisable() {
        economy?.let { server.servicesManager.unregister(Economy::class.java, it) }
        network?.stop()
        if (::database.isInitialized) {
            database.shutdown()
            database.awaitTermination(10, TimeUnit.SECONDS)
        }
        if (::saver.isInitialized) {
            runCatching { saver.flush() }.onFailure { logger.severe("Could not save to the database on shutdown: ${it.message}") }
        }
        if (::storage.isInitialized) storage.close()
    }

    fun reloadSettings() {
        reloadConfig()
        settings = readSettings()
        applyLimits()
    }

    fun flush() {
        try {
            saver.flush()
            if (failing) logger.info("Saving to the database works again.")
            failing = false
        } catch (e: Exception) {
            if (!failing) logger.warning("Could not save to the database, trying again: ${e.message}")
            failing = true
        }
    }

    fun resync() {
        database.execute {
            flush()
            runCatching { accounts.reloadBalances(storage.loadBalances()) }
                .onFailure { logger.warning("Could not reload balances: ${it.message}") }
        }
    }

    fun deliverPayment(target: Account, payer: String, amount: Long) {
        val online = server.getPlayer(target.id)
        when {
            target.messagesOff -> {}
            online != null -> online.scheduler.run(this, { showPaid(online, payer, amount) }, null)
            network?.isOnlineElsewhere(target.id) == true -> network?.paid(target.id, payer, amount)
            else -> saver.notice(Notice(target.id, payer, amount, System.currentTimeMillis()))
        }
    }

    fun showPaidFromNetwork(target: UUID, payer: String, amount: Long) {
        val online = server.getPlayer(target) ?: return
        if (accounts.get(target)?.messagesOff == true) return
        online.scheduler.run(this, { showPaid(online, payer, amount) }, null)
    }

    private fun showPaid(player: Player, payer: String, amount: Long) {
        text.send(player, "pay-received", text.tag("player", payer), text.moneyTag(amount))
        text.actionBar(player, "pay-received-action-bar", text.tag("player", payer), text.moneyTag(amount))
        text.sound(player, "pay-received")
    }

    private fun applyLimits() {
        if (!::accounts.isInitialized) return
        accounts.startingBalance = settings.startingBalance
        accounts.maxBalance = settings.maxBalance
    }

    private fun readSettings(): Settings {
        val messages = YamlConfiguration.loadConfiguration(File(dataFolder, "messages.yml"))
        getResource("messages.yml")?.let { messages.setDefaults(YamlConfiguration.loadConfiguration(InputStreamReader(it, Charsets.UTF_8))) }
        return Settings(config, messages)
    }

    private fun openStorage(mysql: Boolean): Storage =
        if (mysql) Storage.mysql(
            config.getString("storage.mysql.host", "localhost")!!,
            config.getInt("storage.mysql.port", 3306),
            config.getString("storage.mysql.database", "minecraft")!!,
            config.getString("storage.mysql.username", "root")!!,
            config.getString("storage.mysql.password", "")!!,
        ) else Storage.sqlite(File(dataFolder, "cashflow.db"))

    companion object {
        const val HIDDEN = "cashflow.baltop.hidden"
        private const val DAY = 86_400_000L
    }
}
