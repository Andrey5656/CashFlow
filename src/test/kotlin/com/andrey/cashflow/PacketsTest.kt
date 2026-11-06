package com.andrey.cashflow

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PacketsTest {

    private val id = UUID.randomUUID()

    private fun roundTrip(packet: Packet) = assertEquals(packet, Packets.decode(Packets.encode(packet)))

    @Test
    fun encodesEveryPacket() {
        roundTrip(Packet.Change("s1", id, -250))
        roundTrip(Packet.AccountData("s1", id, "986", Kind.PLAYER, 1500, paymentsOff = true, messagesOff = false, hidden = true, owner = null))
        roundTrip(Packet.AccountData("s1", id, "Vault", Kind.BANK, 0, paymentsOff = false, messagesOff = false, hidden = false, owner = UUID.randomUUID()))
        roundTrip(Packet.Removed("s1", id))
        roundTrip(Packet.Paid("s1", id, "986", 100))
        roundTrip(Packet.Join("s1", id))
        roundTrip(Packet.Quit("s1", id))
        roundTrip(Packet.Online("s1", listOf(id, UUID.randomUUID())))
        roundTrip(Packet.Online("s1", emptyList()))
        roundTrip(Packet.Hello("s1"))
    }

    @Test
    fun ignoresBrokenMessages() {
        assertNull(Packets.decode("not json"))
        assertNull(Packets.decode("{\"type\":\"nope\",\"server\":\"s1\"}"))
        assertNull(Packets.decode("{\"type\":\"change\",\"server\":\"s1\"}"))
    }
}
