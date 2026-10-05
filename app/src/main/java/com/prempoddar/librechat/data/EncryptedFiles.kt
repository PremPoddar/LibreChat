package com.prempoddar.librechat.data

import android.content.Context
import com.prempoddar.librechat.crypto.Aead
import com.prempoddar.librechat.crypto.Bytes
import com.prempoddar.librechat.crypto.Hkdf
import com.prempoddar.librechat.crypto.Random
import com.prempoddar.librechat.engine.MediaFiles
import java.io.File

/**
 * Photos and voice notes on disk, each encrypted with its own key derived from a Keystore-wrapped
 * master secret. Sent blobs (already encrypted) are kept a day so missing pieces can be re-sent.
 */
class EncryptedFiles(private val context: Context) : MediaFiles {
    private val master by lazy { LocalKeys.secret(context, "media") }
    private val mediaDir = File(context.filesDir, "media").apply { mkdirs() }
    private val blobDir = File(context.cacheDir, "blobs").apply { mkdirs() }

    private fun keyFor(name: String) = Hkdf.derive(master, ByteArray(32), "LibreChat_file:$name".toByteArray(), Aead.KEY_SIZE)

    override fun saveBlob(transferId: String, blob: ByteArray) = File(blobDir, safe(transferId)).writeBytes(blob)

    override fun blob(transferId: String): ByteArray? = File(blobDir, safe(transferId)).takeIf { it.isFile }?.readBytes()

    override fun saveFile(messageId: String, mime: String, bytes: ByteArray): String {
        val name = safe(messageId)
        val nonce = Random.bytes(Aead.NONCE_SIZE)
        File(mediaDir, name).writeBytes(Bytes.concat(nonce, Aead.seal(keyFor(name), nonce, bytes, name.toByteArray())))
        return name
    }

    /** The decrypted contents of a stored file, or null if it's gone or damaged. */
    fun read(path: String): ByteArray? {
        val name = safe(path)
        val data = File(mediaDir, name).takeIf { it.isFile }?.readBytes() ?: return null
        if (data.size < Aead.NONCE_SIZE + Aead.TAG_SIZE) return null
        return Aead.open(keyFor(name), data.copyOfRange(0, Aead.NONCE_SIZE), data.copyOfRange(Aead.NONCE_SIZE, data.size), name.toByteArray())
    }

    /** A temporary plain copy for players that need a file (voice notes); deleted on next start. */
    fun playable(path: String, extension: String): File? {
        val bytes = read(path) ?: return null
        val dir = File(context.cacheDir, "play").apply { mkdirs() }
        return File(dir, "${safe(path)}.$extension").apply { writeBytes(bytes) }
    }

    override fun delete(path: String) {
        File(mediaDir, safe(path)).delete()
    }

    /** Drops old sent blobs and temporary plain copies. */
    fun cleanUp(now: Long = System.currentTimeMillis()) {
        blobDir.listFiles()?.filter { now - it.lastModified() > DAY_MS }?.forEach { it.delete() }
        File(context.cacheDir, "play").listFiles()?.forEach { it.delete() }
    }

    private fun safe(name: String) = name.filter { it.isLetterOrDigit() }.take(64).ifEmpty { "x" }

    private companion object {
        const val DAY_MS = 24L * 60 * 60 * 1000
    }
}
