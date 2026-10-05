package com.prempoddar.librechat.mesh

import com.google.common.truth.Truth.assertThat
import com.prempoddar.librechat.protocol.Packet
import com.prempoddar.librechat.protocol.PacketType
import org.junit.Test

class RouterTest {
    private var now = 0L
    private val me = "aaaaaaaaaaaaaaaa"
    private val router = Router(me, ackVerifier = { true }, clock = { now })

    private fun pkt(type: PacketType = PacketType.PUBLIC, from: String = "bbbbbbbbbbbbbbbb", to: String = "", ttl: Int = Packet.DEFAULT_TTL) =
        Packet(type = type, sender = from, recipient = to, ttl = ttl, timestamp = 0, payload = byteArrayOf(1))

    private fun announce(from: String) = pkt(PacketType.ANNOUNCE, from = from)

    @Test fun broadcastIsDeliveredAndForwardedToOtherLinks() {
        listOf("L1", "L2", "L3").forEach { router.linkUp(it, LinkSpeed.BLE) }
        val p = pkt()
        val out = router.receive("L1", p)
        assertThat(out.deliver).isEqualTo(p)
        assertThat(out.sends.map { it.link }).containsExactly("L2", "L3")
        assertThat(out.sends.all { it.packet.ttl == Packet.DEFAULT_TTL - 1 }).isTrue()
    }

    @Test fun duplicatesAreDropped() {
        router.linkUp("L1", LinkSpeed.BLE); router.linkUp("L2", LinkSpeed.BLE)
        val p = pkt()
        router.receive("L1", p)
        val again = router.receive("L2", p.copy(ttl = 3))
        assertThat(again.deliver).isNull()
        assertThat(again.sends).isEmpty()
    }

    @Test fun lastHopIsNotForwarded() {
        router.linkUp("L1", LinkSpeed.BLE); router.linkUp("L2", LinkSpeed.BLE)
        val out = router.receive("L1", pkt(ttl = 1))
        assertThat(out.deliver).isNotNull()
        assertThat(out.sends).isEmpty()
    }

    @Test fun directMessagesForMeAreNotForwardedAndOthersAreNotDelivered() {
        router.linkUp("L1", LinkSpeed.BLE); router.linkUp("L2", LinkSpeed.BLE)
        val mine = router.receive("L1", pkt(PacketType.DIRECT, to = me))
        assertThat(mine.deliver).isNotNull()
        assertThat(mine.sends).isEmpty()
        val notMine = router.receive("L1", pkt(PacketType.DIRECT, to = "cccccccccccccccc"))
        assertThat(notMine.deliver).isNull()
        assertThat(notMine.sends.map { it.link }).containsExactly("L2")
    }

    @Test fun unicastsToDirectNeighbourPreferringFastLink() {
        listOf("L1", "L2", "L3").forEach { router.linkUp(it, LinkSpeed.BLE) }
        router.receive("L2", announce("cccccccccccccccc"))
        router.linkUp("W1", LinkSpeed.WIFI)
        router.receive("W1", announce("cccccccccccccccc").copy(id = byteArrayOf(9, 9, 9, 9, 9, 9, 9, 9)))
        val out = router.receive("L1", pkt(PacketType.DIRECT, to = "cccccccccccccccc"))
        assertThat(out.sends.map { it.link }).containsExactly("W1")
    }

    @Test fun relayedAnnounceDoesNotMakeANeighbour() {
        router.linkUp("L1", LinkSpeed.BLE); router.linkUp("L2", LinkSpeed.BLE)
        router.receive("L2", announce("cccccccccccccccc").copy(ttl = 5))
        assertThat(router.neighbours()).isEmpty()
        val out = router.receive("L1", pkt(PacketType.DIRECT, to = "cccccccccccccccc"))
        assertThat(out.sends.map { it.link }).containsExactly("L2")
    }

    @Test fun storedPacketsReplayOnceToNewNeighbour() {
        router.linkUp("L1", LinkSpeed.BLE)
        val p = pkt(PacketType.DIRECT, to = "dddddddddddddddd")
        router.receive("L1", p)
        router.linkUp("L9", LinkSpeed.BLE)
        val replay = router.receive("L9", announce("eeeeeeeeeeeeeeee"))
        assertThat(replay.sends.filter { it.link == "L9" }.map { it.packet.key }).contains(p.key)
        // Same neighbour reconnecting doesn't get it twice.
        router.linkDown("L9"); router.linkUp("L10", LinkSpeed.BLE)
        val again = router.receive("L10", announce("eeeeeeeeeeeeeeee").copy(id = byteArrayOf(5, 5, 5, 5, 5, 5, 5, 5)))
        assertThat(again.sends.filter { it.link == "L10" }.map { it.packet.key }).doesNotContain(p.key)
    }

    @Test fun storedPacketsExpire() {
        router.linkUp("L1", LinkSpeed.BLE)
        val p = pkt(PacketType.DIRECT, to = "dddddddddddddddd")
        router.receive("L1", p)
        now += Router.STORE_FOR_MS + 1
        router.tick()
        router.linkUp("L2", LinkSpeed.BLE)
        assertThat(router.receive("L2", announce("eeeeeeeeeeeeeeee")).sends.map { it.packet.key }).doesNotContain(p.key)
    }

    @Test fun ackPurgesStoredCopies() {
        router.linkUp("L1", LinkSpeed.BLE)
        val p = pkt(PacketType.DIRECT, to = "dddddddddddddddd")
        router.receive("L1", p)
        val ack = pkt(PacketType.ACK, from = "dddddddddddddddd", to = "bbbbbbbbbbbbbbbb")
        router.receive("L1", ack, ackedIds = listOf(p.key))
        router.linkUp("L2", LinkSpeed.BLE)
        assertThat(router.receive("L2", announce("eeeeeeeeeeeeeeee")).sends.map { it.packet.key }).doesNotContain(p.key)
    }

    @Test fun originatedPacketsGoEverywhereAndAreNotRedelivered() {
        router.linkUp("L1", LinkSpeed.BLE); router.linkUp("L2", LinkSpeed.BLE)
        val p = pkt(from = me)
        assertThat(router.send(p).map { it.link }).containsExactly("L1", "L2")
        assertThat(router.receive("L1", p).deliver).isNull()
    }

    @Test fun announcesAreNotStored() {
        router.linkUp("L1", LinkSpeed.BLE)
        val a = announce("cccccccccccccccc")
        router.receive("L1", a)
        router.linkUp("L2", LinkSpeed.BLE)
        assertThat(router.receive("L2", announce("eeeeeeeeeeeeeeee")).sends.map { it.packet.key }).doesNotContain(a.key)
    }

    @Test fun repeatedDirectMessageForMeIsFlaggedForReAck() {
        router.linkUp("L1", LinkSpeed.BLE)
        val p = pkt(PacketType.DIRECT, to = me)
        assertThat(router.receive("L1", p).duplicate).isFalse()
        val again = router.receive("L1", p)
        assertThat(again.duplicate).isTrue()
        assertThat(again.deliver).isEqualTo(p)
    }
}
