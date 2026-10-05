package com.prempoddar.librechat.crypto

import java.nio.ByteBuffer
import java.util.Base64

/** A person on the mesh: the first 8 bytes of SHA-256 of their signing key, as 16 hex chars. */
@JvmInline
value class PeerId(val value: String) {
    override fun toString() = value

    companion object {
        val BROADCAST = PeerId("0000000000000000")
        fun of(signingKey: ByteArray): PeerId = PeerId(Sha.sha256(signingKey).copyOf(8).joinToString("") { "%02x".format(it) })
    }
}

/**
 * This phone's long-term keys: Ed25519 for signatures and X25519 for key agreement. The DH key is
 * signed by the signing key so nobody can pair a stolen signing key with their own DH key.
 */
class Identity(val signing: Ed25519KeyPair, val dh: X25519KeyPair) {
    val peerId: PeerId = PeerId.of(signing.public)

    fun publicIdentity() = PublicIdentity(signing.public, dh.public, signing.sign(PublicIdentity.dhBinding(dh.public)))

    fun toBytes(): ByteArray = Bytes.concat(signing.private, dh.private)

    companion object {
        fun generate() = Identity(Ed25519KeyPair.generate(), X25519KeyPair.generate())

        fun fromBytes(bytes: ByteArray): Identity {
            require(bytes.size == 64)
            return Identity(Ed25519KeyPair.fromPrivate(bytes.copyOfRange(0, 32)), X25519KeyPair.fromPrivate(bytes.copyOfRange(32, 64)))
        }
    }
}

data class PublicIdentity(val signingKey: ByteArray, val dhKey: ByteArray, val dhSignature: ByteArray) {
    val peerId: PeerId get() = PeerId.of(signingKey)

    fun isValid(): Boolean = Ed25519.verify(signingKey, dhBinding(dhKey), dhSignature)

    fun toBytes(): ByteArray = Bytes.concat(signingKey, dhKey, dhSignature)

    override fun equals(other: Any?) = other is PublicIdentity &&
        signingKey.contentEquals(other.signingKey) && dhKey.contentEquals(other.dhKey) && dhSignature.contentEquals(other.dhSignature)

    override fun hashCode() = signingKey.contentHashCode()

    companion object {
        const val SIZE = 32 + 32 + 64

        fun dhBinding(dhKey: ByteArray) = Bytes.concat("LibreChat identity dh".toByteArray(), dhKey)

        fun fromBytes(bytes: ByteArray): PublicIdentity? {
            if (bytes.size != SIZE) return null
            return PublicIdentity(bytes.copyOfRange(0, 32), bytes.copyOfRange(32, 64), bytes.copyOfRange(64, 128))
        }
    }
}

/** A medium-term X25519 key others use to start a session while this phone is offline. */
class SignedPrekey(val id: Int, val keyPair: X25519KeyPair, val signature: ByteArray, val createdAt: Long) {

    fun publicPart() = PublicPrekey(id, keyPair.public, signature)

    fun toBytes(): ByteArray = ByteBuffer.allocate(4 + 32 + 64 + 8)
        .putInt(id).put(keyPair.private).put(signature).putLong(createdAt).array()

    companion object {
        fun generate(identity: Identity, id: Int, now: Long): SignedPrekey {
            val kp = X25519KeyPair.generate()
            return SignedPrekey(id, kp, identity.signing.sign(PublicPrekey.signedBytes(id, kp.public)), now)
        }

        fun fromBytes(bytes: ByteArray): SignedPrekey {
            val b = ByteBuffer.wrap(bytes)
            val id = b.int
            val priv = ByteArray(32).also { b.get(it) }
            val sig = ByteArray(64).also { b.get(it) }
            return SignedPrekey(id, X25519KeyPair.fromPrivate(priv), sig, b.long)
        }
    }
}

data class PublicPrekey(val id: Int, val key: ByteArray, val signature: ByteArray) {
    fun verify(owner: PublicIdentity): Boolean = Ed25519.verify(owner.signingKey, signedBytes(id, key), signature)

    override fun equals(other: Any?) = other is PublicPrekey && id == other.id && key.contentEquals(other.key) && signature.contentEquals(other.signature)
    override fun hashCode() = id * 31 + key.contentHashCode()

    companion object {
        fun signedBytes(id: Int, key: ByteArray) = Bytes.concat("LibreChat prekey".toByteArray(), Bytes.u32(id), key)
    }
}

/**
 * The 60-digit number two people compare to know they hold each other's real keys. Same idea and
 * shape as Signal's: each side contributes 30 digits from an iterated SHA-512, lower half first.
 */
object SafetyNumber {
    private const val ITERATIONS = 5200

    fun of(a: PublicIdentity, b: PublicIdentity): String {
        val x = digits(a.signingKey)
        val y = digits(b.signingKey)
        val joined = if (x <= y) x + y else y + x
        return joined.chunked(5).joinToString(" ")
    }

    private fun digits(key: ByteArray): String {
        var hash = Sha.sha512(byteArrayOf(0, 0), key)
        repeat(ITERATIONS) { hash = Sha.sha512(hash, key) }
        return (0 until 6).joinToString("") { i ->
            var chunk = 0L
            for (j in 0 until 5) chunk = (chunk shl 8) or (hash[i * 5 + j].toLong() and 0xFF)
            "%05d".format(chunk % 100_000)
        }
    }
}

/** What the QR code holds: someone's public identity and display name. */
object ContactCard {
    private const val PREFIX = "librechat:"

    data class Parsed(val identity: PublicIdentity, val name: String)

    fun encode(identity: PublicIdentity, name: String): String {
        val enc = Base64.getUrlEncoder().withoutPadding()
        return PREFIX + enc.encodeToString(identity.toBytes()) + "?n=" + enc.encodeToString(name.toByteArray())
    }

    fun decode(text: String): Parsed? {
        if (!text.startsWith(PREFIX)) return null
        val body = text.removePrefix(PREFIX)
        val keyPart = body.substringBefore("?n=")
        val namePart = body.substringAfter("?n=", "")
        val dec = Base64.getUrlDecoder()
        val identity = runCatching { PublicIdentity.fromBytes(dec.decode(keyPart)) }.getOrNull() ?: return null
        if (!identity.isValid()) return null
        val name = runCatching { String(dec.decode(namePart)) }.getOrDefault("").take(32)
        return Parsed(identity, name)
    }
}
