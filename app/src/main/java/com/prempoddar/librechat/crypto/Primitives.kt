package com.prempoddar.librechat.crypto

import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.digests.SHA512Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.macs.HMac
import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.math.ec.rfc7748.X25519 as BcX25519
import org.bouncycastle.math.ec.rfc8032.Ed25519 as BcEd25519
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * The few primitives LibreChat's protocol is built from, all from BouncyCastle's lightweight
 * API so they behave the same on every Android version and on the JVM in tests.
 */

class CryptoException(message: String) : Exception(message)

object Bytes {
    fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }

    fun unhex(s: String): ByteArray {
        require(s.length % 2 == 0) { "odd hex length" }
        return ByteArray(s.length / 2) { i -> s.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    }

    fun concat(vararg parts: ByteArray): ByteArray {
        val out = ByteArray(parts.sumOf { it.size })
        var at = 0
        parts.forEach { it.copyInto(out, at); at += it.size }
        return out
    }

    fun u32(v: Int): ByteArray = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())

    /** Constant-time equality. */
    fun same(a: ByteArray, b: ByteArray): Boolean = MessageDigest.isEqual(a, b)
}

object Random {
    private val rng = SecureRandom()
    fun bytes(n: Int): ByteArray = ByteArray(n).also(rng::nextBytes)
}

class X25519KeyPair private constructor(val private: ByteArray, val public: ByteArray) {
    companion object {
        fun generate(): X25519KeyPair = fromPrivate(Random.bytes(BcX25519.SCALAR_SIZE))

        fun fromPrivate(private: ByteArray): X25519KeyPair {
            require(private.size == BcX25519.SCALAR_SIZE)
            val public = ByteArray(BcX25519.POINT_SIZE)
            BcX25519.scalarMultBase(private, 0, public, 0)
            return X25519KeyPair(private.copyOf(), public)
        }
    }
}

object X25519 {
    /** Diffie-Hellman. Refuses low-order public keys, which would give an all-zero secret. */
    fun agree(private: ByteArray, public: ByteArray): ByteArray {
        if (private.size != 32 || public.size != 32) throw CryptoException("bad X25519 key size")
        val out = ByteArray(32)
        if (!BcX25519.calculateAgreement(private, 0, public, 0, out, 0)) throw CryptoException("weak X25519 public key")
        return out
    }
}

class Ed25519KeyPair private constructor(val private: ByteArray, val public: ByteArray) {
    fun sign(message: ByteArray): ByteArray {
        val sig = ByteArray(BcEd25519.SIGNATURE_SIZE)
        BcEd25519.sign(private, 0, message, 0, message.size, sig, 0)
        return sig
    }

    companion object {
        fun generate(): Ed25519KeyPair = fromPrivate(Random.bytes(BcEd25519.SECRET_KEY_SIZE))

        fun fromPrivate(private: ByteArray): Ed25519KeyPair {
            require(private.size == BcEd25519.SECRET_KEY_SIZE)
            val public = ByteArray(BcEd25519.PUBLIC_KEY_SIZE)
            BcEd25519.generatePublicKey(private, 0, public, 0)
            return Ed25519KeyPair(private.copyOf(), public)
        }
    }
}

object Ed25519 {
    fun verify(public: ByteArray, message: ByteArray, signature: ByteArray): Boolean {
        if (public.size != BcEd25519.PUBLIC_KEY_SIZE || signature.size != BcEd25519.SIGNATURE_SIZE) return false
        return runCatching { BcEd25519.verify(signature, 0, public, 0, message, 0, message.size) }.getOrDefault(false)
    }
}

object Hkdf {
    fun derive(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        val gen = HKDFBytesGenerator(SHA256Digest())
        gen.init(HKDFParameters(ikm, salt, info))
        return ByteArray(length).also { gen.generateBytes(it, 0, length) }
    }
}

object Hmac {
    fun sha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = HMac(SHA256Digest())
        mac.init(KeyParameter(key))
        mac.update(data, 0, data.size)
        return ByteArray(mac.macSize).also { mac.doFinal(it, 0) }
    }
}

object Sha {
    fun sha256(data: ByteArray): ByteArray = SHA256Digest().run {
        update(data, 0, data.size)
        ByteArray(digestSize).also { doFinal(it, 0) }
    }

    fun sha512(vararg parts: ByteArray): ByteArray = SHA512Digest().run {
        parts.forEach { update(it, 0, it.size) }
        ByteArray(digestSize).also { doFinal(it, 0) }
    }
}

/** ChaCha20-Poly1305 (RFC 8439). Output is ciphertext followed by the 16-byte tag. */
object Aead {
    const val KEY_SIZE = 32
    const val NONCE_SIZE = 12
    const val TAG_SIZE = 16

    fun seal(key: ByteArray, nonce: ByteArray, plaintext: ByteArray, ad: ByteArray): ByteArray {
        val c = ChaCha20Poly1305()
        c.init(true, AEADParameters(KeyParameter(key), TAG_SIZE * 8, nonce, ad))
        val out = ByteArray(c.getOutputSize(plaintext.size))
        val n = c.processBytes(plaintext, 0, plaintext.size, out, 0)
        c.doFinal(out, n)
        return out
    }

    /** Null when the key, nonce, associated data or ciphertext doesn't match. */
    fun open(key: ByteArray, nonce: ByteArray, sealed: ByteArray, ad: ByteArray): ByteArray? {
        if (sealed.size < TAG_SIZE) return null
        return runCatching {
            val c = ChaCha20Poly1305()
            c.init(false, AEADParameters(KeyParameter(key), TAG_SIZE * 8, nonce, ad))
            val out = ByteArray(c.getOutputSize(sealed.size))
            val n = c.processBytes(sealed, 0, sealed.size, out, 0)
            val m = c.doFinal(out, n)
            out.copyOf(n + m)
        }.getOrNull()
    }
}
