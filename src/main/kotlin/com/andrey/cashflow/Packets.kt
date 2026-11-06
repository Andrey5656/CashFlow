package com.andrey.cashflow

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.util.UUID

sealed interface Packet {
    val server: String

    data class Change(override val server: String, val account: UUID, val delta: Long) : Packet
    data class AccountData(
        override val server: String,
        val id: UUID,
        val name: String,
        val kind: Kind,
        val balance: Long,
        val paymentsOff: Boolean,
        val messagesOff: Boolean,
        val hidden: Boolean,
        val owner: UUID?,
    ) : Packet
    data class Removed(override val server: String, val id: UUID) : Packet
    data class Paid(override val server: String, val target: UUID, val payer: String, val amount: Long) : Packet
    data class Join(override val server: String, val id: UUID) : Packet
    data class Quit(override val server: String, val id: UUID) : Packet
    data class Online(override val server: String, val ids: List<UUID>) : Packet
    data class Hello(override val server: String) : Packet
}

object Packets {

    fun encode(packet: Packet): String {
        val json = JsonObject()
        json.addProperty("server", packet.server)
        when (packet) {
            is Packet.Change -> {
                json.addProperty("type", "change")
                json.addProperty("account", packet.account.toString())
                json.addProperty("delta", packet.delta)
            }
            is Packet.AccountData -> {
                json.addProperty("type", "account")
                json.addProperty("id", packet.id.toString())
                json.addProperty("name", packet.name)
                json.addProperty("kind", packet.kind.id)
                json.addProperty("balance", packet.balance)
                json.addProperty("paymentsOff", packet.paymentsOff)
                json.addProperty("messagesOff", packet.messagesOff)
                json.addProperty("hidden", packet.hidden)
                packet.owner?.let { json.addProperty("owner", it.toString()) }
            }
            is Packet.Removed -> {
                json.addProperty("type", "removed")
                json.addProperty("id", packet.id.toString())
            }
            is Packet.Paid -> {
                json.addProperty("type", "paid")
                json.addProperty("target", packet.target.toString())
                json.addProperty("payer", packet.payer)
                json.addProperty("amount", packet.amount)
            }
            is Packet.Join -> {
                json.addProperty("type", "join")
                json.addProperty("id", packet.id.toString())
            }
            is Packet.Quit -> {
                json.addProperty("type", "quit")
                json.addProperty("id", packet.id.toString())
            }
            is Packet.Online -> {
                json.addProperty("type", "online")
                val ids = JsonArray()
                packet.ids.forEach { ids.add(it.toString()) }
                json.add("ids", ids)
            }
            is Packet.Hello -> json.addProperty("type", "hello")
        }
        return json.toString()
    }

    fun decode(text: String): Packet? = runCatching {
        val json = JsonParser.parseString(text).asJsonObject
        val server = json["server"].asString
        fun uuid(key: String): UUID = UUID.fromString(json[key].asString)
        when (json["type"].asString) {
            "change" -> Packet.Change(server, uuid("account"), json["delta"].asLong)
            "account" -> Packet.AccountData(
                server,
                uuid("id"),
                json["name"].asString,
                Kind.of(json["kind"].asInt),
                json["balance"].asLong,
                json["paymentsOff"].asBoolean,
                json["messagesOff"].asBoolean,
                json["hidden"].asBoolean,
                json["owner"]?.let { UUID.fromString(it.asString) },
            )
            "removed" -> Packet.Removed(server, uuid("id"))
            "paid" -> Packet.Paid(server, uuid("target"), json["payer"].asString, json["amount"].asLong)
            "join" -> Packet.Join(server, uuid("id"))
            "quit" -> Packet.Quit(server, uuid("id"))
            "online" -> Packet.Online(server, json["ids"].asJsonArray.map { UUID.fromString(it.asString) })
            "hello" -> Packet.Hello(server)
            else -> null
        }
    }.getOrNull()
}
