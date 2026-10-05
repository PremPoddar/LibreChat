package com.prempoddar.librechat.crypto

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class SenderKeysTest {
    private val group = Random.bytes(16)

    @Test fun membersDecryptFromDistribution() {
        var mine = SenderKeys.create()
        var theirs = SenderKeys.fromDistribution(SenderKeyDistribution.fromBytes(SenderKeys.distribution(mine, group).toBytes()))
        repeat(5) { i ->
            val (next, msg) = SenderKeys.encrypt(mine, group, "hello $i".toByteArray())
            mine = next
            val (st, plain) = SenderKeys.decrypt(theirs, group, GroupMessage.fromBytes(msg.toBytes()))
            theirs = st
            assertThat(plain.decodeToString()).isEqualTo("hello $i")
        }
    }

    @Test fun outOfOrderAndJoiningLate() {
        var mine = SenderKeys.create()
        val early = SenderKeys.encrypt(mine, group, "before".toByteArray()).also { mine = it.first }.second
        val dist = SenderKeys.distribution(mine, group) // a new member only gets keys from here on
        val msgs = (1..4).map { SenderKeys.encrypt(mine, group, "m$it".toByteArray()).also { r -> mine = r.first }.second }
        var late = SenderKeys.fromDistribution(dist)
        listOf(3, 0, 2, 1).forEach { i ->
            val (s, p) = SenderKeys.decrypt(late, group, msgs[i]); late = s
            assertThat(p.decodeToString()).isEqualTo("m${i + 1}")
        }
        assertThrows(CryptoException::class.java) { SenderKeys.decrypt(late, group, early) }
    }

    @Test fun forgedOrWrongGroupRejected() {
        val mine = SenderKeys.create()
        val theirs = SenderKeys.fromDistribution(SenderKeys.distribution(mine, group))
        val (_, msg) = SenderKeys.encrypt(mine, group, "real".toByteArray())
        // A member who knows the chain key still can't sign as the sender.
        val forged = msg.copy(signature = Ed25519KeyPair.generate().sign(byteArrayOf(1)))
        assertThrows(CryptoException::class.java) { SenderKeys.decrypt(theirs, group, forged) }
        assertThrows(CryptoException::class.java) { SenderKeys.decrypt(theirs, Random.bytes(16), msg) }
        val other = SenderKeys.fromDistribution(SenderKeys.distribution(SenderKeys.create(), group))
        assertThrows(CryptoException::class.java) { SenderKeys.decrypt(other, group, msg) }
    }

    @Test fun replayRejected() {
        val mine = SenderKeys.create()
        var theirs = SenderKeys.fromDistribution(SenderKeys.distribution(mine, group))
        val (_, msg) = SenderKeys.encrypt(mine, group, "x".toByteArray())
        theirs = SenderKeys.decrypt(theirs, group, msg).first
        assertThrows(CryptoException::class.java) { SenderKeys.decrypt(theirs, group, msg) }
    }

    @Test fun stateSerialises() {
        val mine = SenderKeys.create()
        assertThat(SenderKeyState.fromBytes(mine.toBytes()).keyId).isEqualTo(mine.keyId)
    }

    @Test fun mediaRoundTripAndIntegrity() {
        val data = Random.bytes(100_000)
        val sealed = MediaCrypto.encrypt(data)
        assertThat(MediaCrypto.decrypt(sealed.key, sealed.blob, sealed.digest)).isEqualTo(data)
        val bad = sealed.blob.copyOf().also { it[50] = (it[50].toInt() xor 1).toByte() }
        assertThrows(CryptoException::class.java) { MediaCrypto.decrypt(sealed.key, bad, sealed.digest) }
    }
}
