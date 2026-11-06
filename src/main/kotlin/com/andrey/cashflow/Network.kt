package com.andrey.cashflow

import redis.clients.jedis.JedisPool
import redis.clients.jedis.JedisPoolConfig
import redis.clients.jedis.JedisPubSub
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class Network(private val plugin: CashFlow, host: String, port: Int, password: String) : Sink {

    private val id = UUID.randomUUID().toString()
    private val pool = JedisPool(JedisPoolConfig(), host, port, 2000, password.ifEmpty { null })
    private val publisher = Executors.newSingleThreadExecutor { Thread(it, "CashFlow Redis").apply { isDaemon = true } }
    private val elsewhere = ConcurrentHashMap<UUID, String>()
    private val here = ConcurrentHashMap.newKeySet<UUID>()
    private val listener = Thread(::listen, "CashFlow Redis Listener").apply { isDaemon = true }
    @Volatile private var running = true
    @Volatile private var subscriber: JedisPubSub? = null
    private val down = AtomicBoolean(false)

    fun start() = listener.start()

    fun stop() {
        running = false
        runCatching { subscriber?.unsubscribe() }
        publisher.shutdown()
        publisher.awaitTermination(5, TimeUnit.SECONDS)
        runCatching { pool.resource.use { it.publish(CHANNEL, Packets.encode(Packet.Online(id, emptyList()))) } }
        pool.close()
    }

    fun isOnlineElsewhere(player: UUID): Boolean = elsewhere.containsKey(player)

    fun joined(player: UUID) {
        here += player
        publish(Packet.Join(id, player))
    }

    fun quit(player: UUID) {
        here -= player
        publish(Packet.Quit(id, player))
    }

    fun paid(target: UUID, payer: String, amount: Long) = publish(Packet.Paid(id, target, payer, amount))

    override fun created(account: Account) = publish(data(account, account.initial))

    override fun changed(account: Account, entry: Entry) = publish(Packet.Change(id, account.id, entry.amount))

    override fun updated(account: Account) = publish(data(account, account.balance.get()))

    override fun deleted(account: Account) = publish(Packet.Removed(id, account.id))

    private fun data(account: Account, balance: Long) = Packet.AccountData(
        id, account.id, account.name, account.kind, balance, account.paymentsOff, account.messagesOff, account.hidden, account.owner,
    )

    private fun publish(packet: Packet) {
        if (publisher.isShutdown) return
        publisher.execute {
            runCatching { pool.resource.use { it.publish(CHANNEL, Packets.encode(packet)) } }
                .onFailure { lost(it.message) }
        }
    }

    private fun lost(reason: String?) {
        if (down.compareAndSet(false, true)) plugin.logger.warning("Lost the Redis connection, trying again every 5 seconds: $reason")
    }

    private fun online(): Packet.Online = Packet.Online(id, here.toList())

    private fun listen() {
        var first = true
        while (running) {
            try {
                pool.resource.use { jedis ->
                    val pubSub = object : JedisPubSub() {
                        override fun onSubscribe(channel: String, subscribedChannels: Int) {
                            val reconnected = down.getAndSet(false)
                            if (reconnected) plugin.logger.info("Connected to Redis again.")
                            if (reconnected || !first) plugin.resync()
                            first = false
                            publish(Packet.Hello(id))
                            publish(online())
                        }

                        override fun onMessage(channel: String, message: String) {
                            val packet = Packets.decode(message) ?: return
                            if (packet.server != id) handle(packet)
                        }
                    }
                    subscriber = pubSub
                    jedis.subscribe(pubSub, CHANNEL)
                }
            } catch (e: Exception) {
                if (!running) return
                lost(e.message)
                try {
                    Thread.sleep(5000)
                } catch (_: InterruptedException) {
                    return
                }
            }
        }
    }

    private fun handle(packet: Packet) {
        when (packet) {
            is Packet.Change -> plugin.accounts.applyRemote(packet.account, packet.delta)
            is Packet.AccountData -> plugin.accounts.applyRemote(packet)
            is Packet.Removed -> plugin.accounts.removeRemote(packet.id)
            is Packet.Paid -> plugin.showPaidFromNetwork(packet.target, packet.payer, packet.amount)
            is Packet.Join -> elsewhere[packet.id] = packet.server
            is Packet.Quit -> elsewhere.remove(packet.id, packet.server)
            is Packet.Online -> {
                elsewhere.entries.removeIf { it.value == packet.server }
                packet.ids.forEach { elsewhere[it] = packet.server }
            }
            is Packet.Hello -> {
                publish(online())
                plugin.resync()
            }
        }
    }

    companion object {
        private const val CHANNEL = "cashflow"
    }
}
