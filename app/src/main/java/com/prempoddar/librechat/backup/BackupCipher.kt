package com.prempoddar.librechat.backup

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Seals a backup with a passphrase: a key stretched from it (PBKDF2-SHA256), then AES-256-GCM in
 * 1 MB chunks, each numbered and the last one marked, so a wrong passphrase, a changed byte, a
 * reordered chunk or a file cut short are all refused rather than half-restored.
 *
 * File layout: "LCBK", version, salt (16), rounds (4), nonce base (8), then chunks of
 * [length (4)][ciphertext].
 */
object BackupCipher {
    private val MAGIC = "LCBK".toByteArray()
    private const val VERSION = 1
    const val ROUNDS = 600_000
    const val CHUNK = 1 shl 20

    class WrongPassphrase : Exception("That passphrase doesn't open this backup")
    class Damaged : Exception("This backup is damaged or incomplete")

    fun key(passphrase: CharArray, salt: ByteArray, rounds: Int): SecretKeySpec {
        val spec = PBEKeySpec(passphrase, salt, rounds, 256)
        val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        spec.clearPassword()
        return SecretKeySpec(bytes, "AES")
    }

    /** Writes the header to [out] and returns a stream that seals whatever is written to it. */
    fun seal(out: OutputStream, passphrase: CharArray, rounds: Int = ROUNDS): OutputStream {
        val rnd = SecureRandom()
        val salt = ByteArray(16).also(rnd::nextBytes)
        val nonce = ByteArray(8).also(rnd::nextBytes)
        val data = DataOutputStream(out)
        data.write(MAGIC); data.writeByte(VERSION); data.write(salt); data.writeInt(rounds); data.write(nonce)
        return Sealer(data, key(passphrase, salt, rounds), nonce)
    }

    /** Reads the header from [input] and returns a stream of the original bytes. */
    fun open(input: InputStream, passphrase: CharArray): InputStream {
        val data = DataInputStream(input)
        val magic = ByteArray(4); data.readFully(magic)
        if (!magic.contentEquals(MAGIC) || data.readUnsignedByte() != VERSION) throw Damaged()
        val salt = ByteArray(16); data.readFully(salt)
        val rounds = data.readInt()
        if (rounds !in 10_000..10_000_000) throw Damaged()
        val nonce = ByteArray(8); data.readFully(nonce)
        return Opener(data, key(passphrase, salt, rounds), nonce)
    }

    private fun iv(base: ByteArray, index: Int) = base + byteArrayOf((index ushr 24).toByte(), (index ushr 16).toByte(), (index ushr 8).toByte(), index.toByte())

    private class Sealer(private val out: DataOutputStream, private val key: SecretKeySpec, private val nonce: ByteArray) : OutputStream() {
        private val buffer = ByteArrayOutputStream(CHUNK)
        private var index = 0
        private var closed = false

        override fun write(b: Int) { buffer.write(b); if (buffer.size() >= CHUNK) flushChunk(false) }
        override fun write(b: ByteArray, off: Int, len: Int) {
            var o = off; var left = len
            while (left > 0) {
                val n = minOf(left, CHUNK - buffer.size())
                buffer.write(b, o, n); o += n; left -= n
                if (buffer.size() >= CHUNK) flushChunk(false)
            }
        }

        private fun flushChunk(last: Boolean) {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv(nonce, index++)))
            c.updateAAD(byteArrayOf(if (last) 1 else 0))
            val sealed = c.doFinal(buffer.toByteArray())
            buffer.reset()
            out.writeInt(sealed.size); out.write(sealed)
        }

        override fun close() {
            if (closed) return
            closed = true
            flushChunk(true)
            out.flush(); out.close()
        }
    }

    private class Opener(private val input: DataInputStream, private val key: SecretKeySpec, private val nonce: ByteArray) : InputStream() {
        private var chunk = ByteArray(0)
        private var pos = 0
        private var index = 0
        private var ended = false

        private fun next(): Boolean {
            if (ended) return false
            val len = try { input.readInt() } catch (e: EOFException) { throw Damaged() }
            if (len < 16 || len > CHUNK + 16) throw Damaged()
            val sealed = ByteArray(len)
            try { input.readFully(sealed) } catch (e: EOFException) { throw Damaged() }
            // The last chunk says so; anything else reaching the end means the file was cut short.
            for (last in listOf(false, true)) {
                val c = Cipher.getInstance("AES/GCM/NoPadding")
                c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv(nonce, index)))
                c.updateAAD(byteArrayOf(if (last) 1 else 0))
                val plain = try { c.doFinal(sealed) } catch (e: AEADBadTagException) { null } ?: continue
                index++; chunk = plain; pos = 0; ended = last
                return true
            }
            // The very first chunk failing is almost always the passphrase.
            throw if (index == 0) WrongPassphrase() else Damaged()
        }

        override fun read(): Int {
            while (pos >= chunk.size) if (!next()) return -1
            return chunk[pos++].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            while (pos >= chunk.size) if (!next()) return -1
            val n = minOf(len, chunk.size - pos)
            System.arraycopy(chunk, pos, b, off, n); pos += n
            return n
        }
    }
}
