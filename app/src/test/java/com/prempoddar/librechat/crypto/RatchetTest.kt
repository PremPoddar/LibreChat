package com.prempoddar.librechat.crypto

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class RatchetTest {
    private val sk = Random.bytes(32)
    private val ad = "ad".toByteArray()

    private fun pair(): Pair<RatchetState, RatchetState> {
        val bobKey = X25519KeyPair.generate()
        return DoubleRatchet.initAlice(sk, bobKey.public, ad) to DoubleRatchet.initBob(sk, bobKey, ad)
    }

    private class Party(var state: RatchetState) {
        fun send(text: String): RatchetMessage = DoubleRatchet.encrypt(state, text.toByteArray()).also { state = it.first }.second
        fun recv(m: RatchetMessage): String = DoubleRatchet.decrypt(state, m).also { state = it.first }.second.decodeToString()
    }

    @Test fun pingPong() {
        val (a, b) = pair().let { Party(it.first) to Party(it.second) }
        repeat(10) { i ->
            assertThat(b.recv(a.send("a$i"))).isEqualTo("a$i")
            assertThat(a.recv(b.send("b$i"))).isEqualTo("b$i")
        }
    }

    @Test fun outOfOrderWithinChain() {
        val (a, b) = pair().let { Party(it.first) to Party(it.second) }
        val msgs = (1..5).map { a.send("m$it") }
        listOf(4, 1, 0, 3, 2).forEach { assertThat(b.recv(msgs[it])).isEqualTo("m${it + 1}") }
    }

    @Test fun oldChainMessageArrivesAfterRatchetStep() {
        val (a, b) = pair().let { Party(it.first) to Party(it.second) }
        val a1 = a.send("a1"); val a2 = a.send("a2")
        assertThat(b.recv(a2)).isEqualTo("a2")
        assertThat(a.recv(b.send("b1"))).isEqualTo("b1")
        val a3 = a.send("a3")
        assertThat(b.recv(a3)).isEqualTo("a3")
        assertThat(b.recv(a1)).isEqualTo("a1")
    }

    @Test fun replayIsRejected() {
        val (a, b) = pair().let { Party(it.first) to Party(it.second) }
        val m = a.send("once")
        b.recv(m)
        assertThrows(CryptoException::class.java) { b.recv(m) }
    }

    @Test fun tamperingFailsAndLeavesStateUsable() {
        val (a, b) = pair().let { Party(it.first) to Party(it.second) }
        val m = a.send("hello")
        val bad = m.copy(ciphertext = m.ciphertext.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() })
        assertThrows(CryptoException::class.java) { b.recv(bad) }
        val badHeader = m.copy(n = m.n + 1)
        assertThrows(CryptoException::class.java) { b.recv(badHeader) }
        assertThat(b.recv(m)).isEqualTo("hello")
    }

    @Test fun tooManySkippedIsRejected() {
        val (a, b) = pair().let { Party(it.first) to Party(it.second) }
        repeat(DoubleRatchet.MAX_SKIP + 1) { a.send("x") }
        assertThrows(CryptoException::class.java) { b.recv(a.send("late")) }
    }

    @Test fun stateSurvivesSerialisation() {
        val (a, b) = pair().let { Party(it.first) to Party(it.second) }
        val skippedMsg = a.send("skipped")
        b.recv(a.send("one"))
        a.recv(b.send("two"))
        b.state = RatchetState.fromBytes(b.state.toBytes())
        a.state = RatchetState.fromBytes(a.state.toBytes())
        assertThat(b.recv(a.send("three"))).isEqualTo("three")
        assertThat(b.recv(skippedMsg)).isEqualTo("skipped")
    }

    @Test fun messageCodecRoundTrips() {
        val (a, _) = pair().let { Party(it.first) to Party(it.second) }
        val m = a.send("x")
        assertThat(RatchetMessage.fromBytes(m.toBytes())).isEqualTo(m)
    }
}
