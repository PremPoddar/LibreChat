package com.prempoddar.librechat.crypto

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.protobuf.ProtoBuf

/** One encrypted message: the ratchet header (sender's ratchet key, previous chain length, number) and ciphertext. */
@Serializable
data class RatchetMessage(val dh: ByteArray, val pn: Int, val n: Int, val ciphertext: ByteArray) {
    fun headerBytes(): ByteArray = Bytes.concat(dh, Bytes.u32(pn), Bytes.u32(n))

    @OptIn(ExperimentalSerializationApi::class)
    fun toBytes(): ByteArray = ProtoBuf.encodeToByteArray(this)

    override fun equals(other: Any?) = other is RatchetMessage && dh.contentEquals(other.dh) && pn == other.pn &&
        n == other.n && ciphertext.contentEquals(other.ciphertext)

    override fun hashCode() = dh.contentHashCode() * 31 + n

    companion object {
        @OptIn(ExperimentalSerializationApi::class)
        fun fromBytes(bytes: ByteArray): RatchetMessage = ProtoBuf.decodeFromByteArray(bytes)
    }
}

@Serializable
data class SkippedKey(val dh: ByteArray, val n: Int, val key: ByteArray)

/**
 * Double Ratchet state (Signal's specification). Immutable: every operation returns a new state,
 * so a message that fails to decrypt can never damage the session.
 */
@Serializable
data class RatchetState(
    val dhSelf: ByteArray,
    val dhRemote: ByteArray? = null,
    val rootKey: ByteArray,
    val sendChain: ByteArray? = null,
    val recvChain: ByteArray? = null,
    val ns: Int = 0,
    val nr: Int = 0,
    val pn: Int = 0,
    val skipped: List<SkippedKey> = emptyList(),
    val ad: ByteArray,
) {
    @OptIn(ExperimentalSerializationApi::class)
    fun toBytes(): ByteArray = ProtoBuf.encodeToByteArray(this)

    companion object {
        @OptIn(ExperimentalSerializationApi::class)
        fun fromBytes(bytes: ByteArray): RatchetState = ProtoBuf.decodeFromByteArray(bytes)
    }
}

object DoubleRatchet {
    const val MAX_SKIP = 1000
    const val MAX_STORED_SKIPPED = 2000
    private val RATCHET_INFO = "LibreChat_Ratchet".toByteArray()
    private val MESSAGE_INFO = "LibreChat_MessageKeys".toByteArray()

    fun initAlice(sharedSecret: ByteArray, bobRatchetKey: ByteArray, ad: ByteArray): RatchetState {
        val self = X25519KeyPair.generate()
        val (rk, cks) = kdfRoot(sharedSecret, X25519.agree(self.private, bobRatchetKey))
        return RatchetState(dhSelf = self.private, dhRemote = bobRatchetKey, rootKey = rk, sendChain = cks, ad = ad)
    }

    fun initBob(sharedSecret: ByteArray, bobRatchetKey: X25519KeyPair, ad: ByteArray): RatchetState =
        RatchetState(dhSelf = bobRatchetKey.private, rootKey = sharedSecret, ad = ad)

    fun encrypt(state: RatchetState, plaintext: ByteArray): Pair<RatchetState, RatchetMessage> {
        val chain = state.sendChain ?: throw CryptoException("no sending chain yet")
        val (nextChain, mk) = kdfChain(chain)
        val self = X25519KeyPair.fromPrivate(state.dhSelf)
        val header = RatchetMessage(self.public, state.pn, state.ns, ByteArray(0))
        val ciphertext = seal(mk, plaintext, Bytes.concat(state.ad, header.headerBytes()))
        return state.copy(sendChain = nextChain, ns = state.ns + 1) to header.copy(ciphertext = ciphertext)
    }

    fun decrypt(state: RatchetState, msg: RatchetMessage): Pair<RatchetState, ByteArray> {
        val ad = Bytes.concat(state.ad, msg.headerBytes())

        // A message from a chain we've already moved past, whose key we kept.
        val stored = state.skipped.indexOfFirst { it.n == msg.n && it.dh.contentEquals(msg.dh) }
        if (stored >= 0) {
            val plain = open(state.skipped[stored].key, msg.ciphertext, ad) ?: throw CryptoException("bad message")
            return state.copy(skipped = state.skipped.filterIndexed { i, _ -> i != stored }) to plain
        }

        var s = state
        if (s.dhRemote == null || !msg.dh.contentEquals(s.dhRemote)) {
            s = skipTo(s, msg.pn)
            s = ratchetStep(s, msg.dh)
        }
        s = skipTo(s, msg.n)
        val (nextChain, mk) = kdfChain(s.recvChain ?: throw CryptoException("no receiving chain"))
        val plain = open(mk, msg.ciphertext, ad) ?: throw CryptoException("bad message")
        return s.copy(recvChain = nextChain, nr = s.nr + 1) to plain
    }

    private fun skipTo(state: RatchetState, until: Int): RatchetState {
        val chain = state.recvChain ?: return state
        if (until < state.nr) return state
        if (until - state.nr > MAX_SKIP) throw CryptoException("too many skipped messages")
        var ck = chain
        val added = ArrayList<SkippedKey>()
        for (n in state.nr until until) {
            val (next, mk) = kdfChain(ck)
            added += SkippedKey(state.dhRemote!!, n, mk)
            ck = next
        }
        val all = (state.skipped + added).takeLast(MAX_STORED_SKIPPED)
        return state.copy(recvChain = ck, nr = until, skipped = all)
    }

    private fun ratchetStep(state: RatchetState, remote: ByteArray): RatchetState {
        val (rk1, ckr) = kdfRoot(state.rootKey, X25519.agree(state.dhSelf, remote))
        val self = X25519KeyPair.generate()
        val (rk2, cks) = kdfRoot(rk1, X25519.agree(self.private, remote))
        return state.copy(
            dhSelf = self.private, dhRemote = remote, rootKey = rk2,
            sendChain = cks, recvChain = ckr, pn = state.ns, ns = 0, nr = 0,
        )
    }

    private fun kdfRoot(rootKey: ByteArray, dhOut: ByteArray): Pair<ByteArray, ByteArray> {
        val out = Hkdf.derive(dhOut, rootKey, RATCHET_INFO, 64)
        return out.copyOfRange(0, 32) to out.copyOfRange(32, 64)
    }

    private fun kdfChain(chainKey: ByteArray): Pair<ByteArray, ByteArray> =
        Hmac.sha256(chainKey, byteArrayOf(2)) to Hmac.sha256(chainKey, byteArrayOf(1))

    private fun seal(mk: ByteArray, plaintext: ByteArray, ad: ByteArray): ByteArray {
        val keys = Hkdf.derive(mk, ByteArray(32), MESSAGE_INFO, 44)
        return Aead.seal(keys.copyOfRange(0, 32), keys.copyOfRange(32, 44), plaintext, ad)
    }

    private fun open(mk: ByteArray, ciphertext: ByteArray, ad: ByteArray): ByteArray? {
        val keys = Hkdf.derive(mk, ByteArray(32), MESSAGE_INFO, 44)
        return Aead.open(keys.copyOfRange(0, 32), keys.copyOfRange(32, 44), ciphertext, ad)
    }
}
