package com.prempoddar.librechat.crypto

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.protobuf.ProtoBuf

@OptIn(ExperimentalSerializationApi::class)
private inline fun <reified T> T.proto(): ByteArray = ProtoBuf.encodeToByteArray(this)

/** A member's sending chain in one group. Receivers hold the same thing without the private signing key. */
@Serializable
data class SenderKeyState(
    val keyId: Int,
    val chainKey: ByteArray,
    val iteration: Int,
    val signingPublic: ByteArray,
    val signingPrivate: ByteArray? = null,
    val skipped: List<SkippedKey> = emptyList(),
) {
    fun toBytes() = proto()

    companion object {
        @OptIn(ExperimentalSerializationApi::class)
        fun fromBytes(b: ByteArray): SenderKeyState = ProtoBuf.decodeFromByteArray(b)
    }
}

/** Sent to each member over their pairwise session so they can read my group messages. */
@Serializable
data class SenderKeyDistribution(
    val groupId: ByteArray,
    val keyId: Int,
    val chainKey: ByteArray,
    val iteration: Int,
    val signingPublic: ByteArray,
) {
    fun toBytes() = proto()

    companion object {
        @OptIn(ExperimentalSerializationApi::class)
        fun fromBytes(b: ByteArray): SenderKeyDistribution = ProtoBuf.decodeFromByteArray(b)
    }
}

@Serializable
data class GroupMessage(val keyId: Int, val iteration: Int, val ciphertext: ByteArray, val signature: ByteArray) {
    fun toBytes() = proto()

    override fun equals(other: Any?) = other is GroupMessage && keyId == other.keyId && iteration == other.iteration &&
        ciphertext.contentEquals(other.ciphertext) && signature.contentEquals(other.signature)
    override fun hashCode() = keyId * 31 + iteration

    companion object {
        @OptIn(ExperimentalSerializationApi::class)
        fun fromBytes(b: ByteArray): GroupMessage = ProtoBuf.decodeFromByteArray(b)
    }
}

/**
 * Group encryption with Sender Keys (as in Signal): each member encrypts once with their own hash
 * ratchet and signs, so one broadcast reaches the whole group. Forward secrecy within a chain;
 * keys are replaced whenever membership changes.
 */
object SenderKeys {
    private const val MAX_SKIP = 2000
    private val INFO = "LibreChat_GroupMessage".toByteArray()

    fun create(): SenderKeyState {
        val signing = Ed25519KeyPair.generate()
        val keyId = Random.bytes(4).fold(0) { acc, b -> (acc shl 8) or (b.toInt() and 0xFF) }
        return SenderKeyState(keyId, Random.bytes(32), 0, signing.public, signing.private)
    }

    fun distribution(own: SenderKeyState, groupId: ByteArray) =
        SenderKeyDistribution(groupId, own.keyId, own.chainKey, own.iteration, own.signingPublic)

    fun fromDistribution(d: SenderKeyDistribution) = SenderKeyState(d.keyId, d.chainKey, d.iteration, d.signingPublic)

    fun encrypt(own: SenderKeyState, groupId: ByteArray, plaintext: ByteArray): Pair<SenderKeyState, GroupMessage> {
        val priv = own.signingPrivate ?: throw CryptoException("not my sender key")
        val mk = Hmac.sha256(own.chainKey, byteArrayOf(1))
        val ct = seal(mk, plaintext, ad(groupId, own.keyId, own.iteration))
        val sig = Ed25519KeyPair.fromPrivate(priv).sign(signed(groupId, own.keyId, own.iteration, ct))
        val next = own.copy(chainKey = Hmac.sha256(own.chainKey, byteArrayOf(2)), iteration = own.iteration + 1)
        return next to GroupMessage(own.keyId, own.iteration, ct, sig)
    }

    fun decrypt(state: SenderKeyState, groupId: ByteArray, msg: GroupMessage): Pair<SenderKeyState, ByteArray> {
        if (msg.keyId != state.keyId) throw CryptoException("unknown sender key")
        if (!Ed25519.verify(state.signingPublic, signed(groupId, msg.keyId, msg.iteration, msg.ciphertext), msg.signature)) {
            throw CryptoException("bad group signature")
        }
        val ad = ad(groupId, msg.keyId, msg.iteration)
        val stored = state.skipped.indexOfFirst { it.n == msg.iteration }
        if (stored >= 0) {
            val plain = open(state.skipped[stored].key, msg.ciphertext, ad) ?: throw CryptoException("bad group message")
            return state.copy(skipped = state.skipped.filterIndexed { i, _ -> i != stored }) to plain
        }
        if (msg.iteration < state.iteration) throw CryptoException("old or replayed group message")
        if (msg.iteration - state.iteration > MAX_SKIP) throw CryptoException("too many skipped group messages")
        var ck = state.chainKey
        val skipped = state.skipped.toMutableList()
        for (i in state.iteration until msg.iteration) {
            skipped += SkippedKey(ByteArray(0), i, Hmac.sha256(ck, byteArrayOf(1)))
            ck = Hmac.sha256(ck, byteArrayOf(2))
        }
        val plain = open(Hmac.sha256(ck, byteArrayOf(1)), msg.ciphertext, ad) ?: throw CryptoException("bad group message")
        return state.copy(
            chainKey = Hmac.sha256(ck, byteArrayOf(2)),
            iteration = msg.iteration + 1,
            skipped = skipped.takeLast(MAX_SKIP),
        ) to plain
    }

    private fun ad(groupId: ByteArray, keyId: Int, iteration: Int) = Bytes.concat(groupId, Bytes.u32(keyId), Bytes.u32(iteration))
    private fun signed(groupId: ByteArray, keyId: Int, iteration: Int, ct: ByteArray) =
        Bytes.concat("LibreChat group".toByteArray(), ad(groupId, keyId, iteration), ct)

    private fun seal(mk: ByteArray, plaintext: ByteArray, ad: ByteArray): ByteArray {
        val k = Hkdf.derive(mk, ByteArray(32), INFO, 44)
        return Aead.seal(k.copyOfRange(0, 32), k.copyOfRange(32, 44), plaintext, ad)
    }

    private fun open(mk: ByteArray, ct: ByteArray, ad: ByteArray): ByteArray? {
        val k = Hkdf.derive(mk, ByteArray(32), INFO, 44)
        return Aead.open(k.copyOfRange(0, 32), k.copyOfRange(32, 44), ct, ad)
    }
}

/** Photos and voice notes: encrypted once with a random key that travels inside the encrypted message. */
object MediaCrypto {
    class Sealed(val key: ByteArray, val blob: ByteArray, val digest: ByteArray)

    fun encrypt(data: ByteArray): Sealed {
        val key = Random.bytes(Aead.KEY_SIZE)
        val nonce = Random.bytes(Aead.NONCE_SIZE)
        val blob = Bytes.concat(nonce, Aead.seal(key, nonce, data, ByteArray(0)))
        return Sealed(key, blob, Sha.sha256(blob))
    }

    fun decrypt(key: ByteArray, blob: ByteArray, digest: ByteArray): ByteArray {
        if (!Bytes.same(Sha.sha256(blob), digest)) throw CryptoException("media was altered")
        if (blob.size < Aead.NONCE_SIZE + Aead.TAG_SIZE) throw CryptoException("media too short")
        return Aead.open(key, blob.copyOfRange(0, Aead.NONCE_SIZE), blob.copyOfRange(Aead.NONCE_SIZE, blob.size), ByteArray(0))
            ?: throw CryptoException("media could not be decrypted")
    }
}
