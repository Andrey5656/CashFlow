package com.andrey.cashflow

import net.milkbowl.vault.economy.Economy
import net.milkbowl.vault.economy.EconomyResponse
import net.milkbowl.vault.economy.EconomyResponse.ResponseType
import org.bukkit.Bukkit
import org.bukkit.OfflinePlayer

@Suppress("OVERRIDE_DEPRECATION")
class VaultEconomy(private val plugin: CashFlow, private val sources: Sources) : Economy {

    private val accounts get() = plugin.accounts
    private val settings get() = plugin.settings

    override fun isEnabled(): Boolean = plugin.isEnabled

    override fun getName(): String = "CashFlow"

    override fun hasBankSupport(): Boolean = true

    override fun fractionalDigits(): Int = settings.decimals

    override fun format(amount: Double): String = plugin.text.plainMoney(Money.fromDouble(amount) ?: 0)

    override fun currencyNamePlural(): String = settings.plural

    override fun currencyNameSingular(): String = settings.singular

    override fun hasAccount(playerName: String): Boolean = find(playerName) != null

    override fun hasAccount(player: OfflinePlayer): Boolean = find(player) != null

    override fun hasAccount(playerName: String, worldName: String?): Boolean = hasAccount(playerName)

    override fun hasAccount(player: OfflinePlayer, worldName: String?): Boolean = hasAccount(player)

    override fun getBalance(playerName: String): Double = Money.toDouble(balance(find(playerName)))

    override fun getBalance(player: OfflinePlayer): Double = Money.toDouble(balance(find(player)))

    override fun getBalance(playerName: String, world: String?): Double = getBalance(playerName)

    override fun getBalance(player: OfflinePlayer, world: String?): Double = getBalance(player)

    override fun has(playerName: String, amount: Double): Boolean = has(balance(find(playerName)), amount)

    override fun has(player: OfflinePlayer, amount: Double): Boolean = has(balance(find(player)), amount)

    override fun has(playerName: String, worldName: String?, amount: Double): Boolean = has(playerName, amount)

    override fun has(player: OfflinePlayer, worldName: String?, amount: Double): Boolean = has(player, amount)

    override fun withdrawPlayer(playerName: String, amount: Double): EconomyResponse = withdraw(obtain(playerName), amount)

    override fun withdrawPlayer(player: OfflinePlayer, amount: Double): EconomyResponse = withdraw(obtain(player), amount)

    override fun withdrawPlayer(playerName: String, worldName: String?, amount: Double): EconomyResponse = withdrawPlayer(playerName, amount)

    override fun withdrawPlayer(player: OfflinePlayer, worldName: String?, amount: Double): EconomyResponse = withdrawPlayer(player, amount)

    override fun depositPlayer(playerName: String, amount: Double): EconomyResponse = deposit(obtain(playerName), amount)

    override fun depositPlayer(player: OfflinePlayer, amount: Double): EconomyResponse = deposit(obtain(player), amount)

    override fun depositPlayer(playerName: String, worldName: String?, amount: Double): EconomyResponse = depositPlayer(playerName, amount)

    override fun depositPlayer(player: OfflinePlayer, worldName: String?, amount: Double): EconomyResponse = depositPlayer(player, amount)

    override fun createBank(name: String, player: String): EconomyResponse = createBank(name, obtain(player).id)

    override fun createBank(name: String, player: OfflinePlayer): EconomyResponse = createBank(name, player.uniqueId)

    override fun deleteBank(name: String): EconomyResponse {
        val bank = accounts.bank(name) ?: return failure(0.0, 0, "Bank does not exist")
        accounts.delete(bank)
        return EconomyResponse(0.0, 0.0, ResponseType.SUCCESS, null)
    }

    override fun bankBalance(name: String): EconomyResponse {
        val bank = accounts.bank(name) ?: return failure(0.0, 0, "Bank does not exist")
        return EconomyResponse(0.0, Money.toDouble(bank.balance.get()), ResponseType.SUCCESS, null)
    }

    override fun bankHas(name: String, amount: Double): EconomyResponse {
        val bank = accounts.bank(name) ?: return failure(amount, 0, "Bank does not exist")
        val balance = bank.balance.get()
        return if (has(balance, amount)) EconomyResponse(amount, Money.toDouble(balance), ResponseType.SUCCESS, null)
        else failure(amount, balance, "Insufficient funds")
    }

    override fun bankWithdraw(name: String, amount: Double): EconomyResponse {
        val bank = accounts.bank(name) ?: return failure(amount, 0, "Bank does not exist")
        return withdraw(bank, amount)
    }

    override fun bankDeposit(name: String, amount: Double): EconomyResponse {
        val bank = accounts.bank(name) ?: return failure(amount, 0, "Bank does not exist")
        return deposit(bank, amount)
    }

    override fun isBankOwner(name: String, playerName: String): EconomyResponse {
        val player = find(playerName) ?: return failure(0.0, 0, "Player does not exist")
        return owner(name, player.id)
    }

    override fun isBankOwner(name: String, player: OfflinePlayer): EconomyResponse = owner(name, player.uniqueId)

    override fun isBankMember(name: String, playerName: String): EconomyResponse = isBankOwner(name, playerName)

    override fun isBankMember(name: String, player: OfflinePlayer): EconomyResponse = isBankOwner(name, player)

    override fun getBanks(): List<String> = accounts.banks().map { it.name }

    override fun createPlayerAccount(playerName: String): Boolean {
        if (find(playerName) != null) return false
        obtain(playerName)
        return true
    }

    override fun createPlayerAccount(player: OfflinePlayer): Boolean {
        if (find(player) != null) return false
        obtain(player)
        return true
    }

    override fun createPlayerAccount(playerName: String, worldName: String?): Boolean = createPlayerAccount(playerName)

    override fun createPlayerAccount(player: OfflinePlayer, worldName: String?): Boolean = createPlayerAccount(player)

    private fun find(player: OfflinePlayer): Account? = accounts.get(player.uniqueId)

    private fun find(name: String): Account? =
        accounts.player(name) ?: Bukkit.getOfflinePlayerIfCached(name)?.let { accounts.get(it.uniqueId) } ?: accounts.named(name, create = false)

    private fun obtain(player: OfflinePlayer): Account {
        accounts.get(player.uniqueId)?.let { return it }
        val name = player.name
        val joined = name != null && (player.isOnline || player.hasPlayedBefore())
        return accounts.getOrCreate(player.uniqueId, name ?: player.uniqueId.toString(), if (joined) Kind.PLAYER else Kind.UNKNOWN)
    }

    private fun obtain(name: String): Account =
        accounts.player(name) ?: Bukkit.getOfflinePlayerIfCached(name)?.let(::obtain) ?: accounts.named(name, create = true)!!

    private fun balance(account: Account?): Long = account?.balance?.get() ?: settings.startingBalance

    private fun has(balance: Long, amount: Double): Boolean {
        val cents = Money.fromDouble(amount) ?: return false
        return balance >= cents
    }

    private fun withdraw(account: Account, amount: Double): EconomyResponse {
        val cents = Money.fromDouble(amount)
        if (cents == null || cents < 0) return failure(amount, account.balance.get(), "Cannot withdraw negative funds")
        if (cents == 0L) return EconomyResponse(amount, Money.toDouble(account.balance.get()), ResponseType.SUCCESS, null)
        return when (val result = accounts.withdraw(account, cents, EntryType.PLUGIN_WITHDRAW, null, sources.caller())) {
            is Result.Success -> EconomyResponse(amount, Money.toDouble(result.balance), ResponseType.SUCCESS, null)
            Result.NotEnough -> failure(amount, account.balance.get(), "Insufficient funds")
            Result.OverMax -> failure(amount, account.balance.get(), "Balance limit reached")
        }
    }

    private fun deposit(account: Account, amount: Double): EconomyResponse {
        val cents = Money.fromDouble(amount)
        if (cents == null || cents < 0) return failure(amount, account.balance.get(), "Cannot deposit negative funds")
        if (cents == 0L) return EconomyResponse(amount, Money.toDouble(account.balance.get()), ResponseType.SUCCESS, null)
        return when (val result = accounts.deposit(account, cents, EntryType.PLUGIN_DEPOSIT, null, sources.caller())) {
            is Result.Success -> EconomyResponse(amount, Money.toDouble(result.balance), ResponseType.SUCCESS, null)
            Result.NotEnough -> failure(amount, account.balance.get(), "Insufficient funds")
            Result.OverMax -> failure(amount, account.balance.get(), "Balance limit reached")
        }
    }

    private fun createBank(name: String, owner: java.util.UUID): EconomyResponse {
        val bank = accounts.createBank(name, owner) ?: return failure(0.0, 0, "Bank already exists")
        return EconomyResponse(0.0, Money.toDouble(bank.balance.get()), ResponseType.SUCCESS, null)
    }

    private fun owner(name: String, player: java.util.UUID): EconomyResponse {
        val bank = accounts.bank(name) ?: return failure(0.0, 0, "Bank does not exist")
        return if (bank.owner == player) EconomyResponse(0.0, Money.toDouble(bank.balance.get()), ResponseType.SUCCESS, null)
        else failure(0.0, bank.balance.get(), "Not the bank owner")
    }

    private fun failure(amount: Double, balance: Long, message: String): EconomyResponse =
        EconomyResponse(amount, Money.toDouble(balance), ResponseType.FAILURE, message)
}
