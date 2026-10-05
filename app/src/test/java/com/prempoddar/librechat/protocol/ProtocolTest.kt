package com.prempoddar.librechat.protocol

import com.google.common.truth.Truth.assertThat
import com.prempoddar.librechat.crypto.Identity
import com.prempoddar.librechat.crypto.Random
import com.prempoddar.librechat.crypto.SignedPrekey
import org.junit.Test

class ProtocolTest {
    private val me = Identity.generate()

    @Test fun packetRoundTripAndRejectsGarbage() {
        val p = Packet(type = PacketType.DIRECT, sender = me.peerId.value, recipient = "abcdabcdabcdabcd", timestamp = 5, payload = byteArrayOf(1, 2))
        val back = Packet.fromBytes(p.toBytes())!!
        assertThat(back).isEqualTo(p)
        assertThat(back.recipient).isEqualTo("abcdabcdabcdabcd")
        assertThat(Packet.fromBytes(byteArrayOf(9, 9, 9))).isNull()
        assertThat(Packet.fromBytes(p.copy(ttl = 0).toBytes())).isNull()
    }

    @Test fun announceVerifies() {
        val spk = SignedPrekey.generate(me, 1, 0)
        val a = Announce(me.publicIdentity().toBytes(), 1, spk.keyPair.public, spk.signature, "Asha", 99, true).signed(me)
        val back = Announce.fromBytes(a.toBytes())!!
        assertThat(back.verify(me.peerId.value)).isEqualTo(me.publicIdentity())
        assertThat(back.copy(name = "Mallory").verify(me.peerId.value)).isNull()
        assertThat(back.verify("ffffffffffffffff")).isNull()
        val other = Identity.generate()
        val stolen = back.copy(identity = other.publicIdentity().toBytes())
        assertThat(stolen.verify(other.peerId.value)).isNull()
    }

    @Test fun publicPostSignatureCoversPacketIdAndTime() {
        val packet = Packet(type = PacketType.PUBLIC, sender = me.peerId.value, timestamp = 10, payload = ByteArray(0))
        val post = PublicPost(me.publicIdentity().toBytes(), "Asha", Content.Text("m1", "hi").toBytes()).signed(me, packet.id, 10)
        val real = packet.copy(payload = post.toBytes())
        assertThat(PublicPost.fromBytes(real.payload)!!.verify(real)).isNotNull()
        assertThat(post.verify(real.copy(timestamp = 11))).isNull()
        assertThat(post.verify(real.copy(id = Random.bytes(8)))).isNull()
    }

    @Test fun contentRoundTripsEveryKind() {
        val all = listOf(
            Content.Text("1", "hello", replyTo = "0", expiresIn = 3600),
            Content.Media("2", MediaKind.PHOTO, "image/jpeg", 1000, 0, 800, 600, byteArrayOf(1), Random.bytes(32), Random.bytes(32), "t1", 3),
            Content.Reaction("1", "🔥"),
            Content.Delete("1"),
            Content.Read(listOf("1", "2")),
            Content.Accept,
            Content.GroupState(Random.bytes(16), "Camp", me.peerId.value, listOf(GroupMemberInfo(me.publicIdentity().toBytes(), "Asha")), 1),
            Content.GroupLeave(Random.bytes(16)),
            Content.SenderKey(byteArrayOf(1, 2, 3)),
            Content.Timer(86_400),
        )
        all.forEach { c ->
            val back = Content.fromBytes(c.toBytes())
            assertThat(back).isNotNull()
            assertThat(back!!::class).isEqualTo(c::class)
        }
        assertThat((Content.fromBytes(all[0].toBytes()) as Content.Text).text).isEqualTo("hello")
        assertThat(Content.fromBytes(byteArrayOf(1, 2, 3))).isNull()
    }

    @Test fun ackSignature() {
        val ack = Ack(listOf(Random.bytes(8), Random.bytes(8))).signed(me)
        assertThat(Ack.fromBytes(ack.toBytes())!!.verify(me.signing.public)).isTrue()
        assertThat(ack.verify(Identity.generate().signing.public)).isFalse()
    }

    @Test fun fragmentsReassembleInAnyOrder() {
        val data = Random.bytes(5_000)
        val frames = Fragmenter.split(7, data, 180)
        assertThat(frames.size).isGreaterThan(20)
        val r = Reassembler()
        val shuffled = frames.shuffled()
        shuffled.dropLast(1).forEach { assertThat(r.accept(it)).isNull() }
        assertThat(r.accept(shuffled.last())).isEqualTo(data)
    }

    @Test fun incompletePacketsExpire() {
        var now = 0L
        val r = Reassembler(timeoutMs = 1000) { now }
        val frames = Fragmenter.split(1, Random.bytes(1000), 100)
        r.accept(frames[0])
        now = 5000
        frames.drop(1).forEach { r.accept(it) }
        // Frame 0 expired, so the packet can't complete.
        assertThat(r.accept(frames.last())).isNull()
    }

    @Test fun singleFramePassesStraightThrough() {
        val data = byteArrayOf(1, 2, 3)
        assertThat(Reassembler().accept(Fragmenter.split(3, data, 100).single())).isEqualTo(data)
    }
}
