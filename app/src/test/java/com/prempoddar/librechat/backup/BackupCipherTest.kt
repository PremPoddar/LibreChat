package com.prempoddar.librechat.backup

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.random.Random

class BackupCipherTest {
    private val pass = "correct horse battery".toCharArray()
    private val data = Random(1).nextBytes(BackupCipher.CHUNK * 2 + 12345) // three chunks

    private fun sealed(): ByteArray = ByteArrayOutputStream().also { o -> BackupCipher.seal(o, pass, rounds = 20_000).use { it.write(data) } }.toByteArray()
    private fun open(bytes: ByteArray, p: CharArray = pass) = BackupCipher.open(ByteArrayInputStream(bytes), p).readBytes()

    @Test fun opensWithThePassphrase() {
        assertThat(open(sealed())).isEqualTo(data)
    }

    @Test fun refusesTheWrongPassphrase() {
        assertThat(runCatching { open(sealed(), "wrong".toCharArray()) }.exceptionOrNull()).isInstanceOf(BackupCipher.WrongPassphrase::class.java)
    }

    @Test fun refusesAChangedByte() {
        val b = sealed(); b[b.size / 2] = (b[b.size / 2].toInt() xor 1).toByte()
        assertThat(runCatching { open(b) }.exceptionOrNull()).isInstanceOf(BackupCipher.Damaged::class.java)
    }

    @Test fun refusesAFileCutShort() {
        val b = sealed()
        // Cut exactly after the first two chunks: every remaining chunk is intact, but the end is missing.
        val first = 4 + 1 + 16 + 4 + 8
        fun chunkEnd(at: Int) = at + 4 + ((b[at].toInt() and 0xFF shl 24) or (b[at + 1].toInt() and 0xFF shl 16) or (b[at + 2].toInt() and 0xFF shl 8) or (b[at + 3].toInt() and 0xFF))
        val cut = b.copyOf(chunkEnd(chunkEnd(first)))
        assertThat(runCatching { open(cut) }.exceptionOrNull()).isInstanceOf(BackupCipher.Damaged::class.java)
    }

    @Test fun anEmptyBackupStillSeals() {
        val b = ByteArrayOutputStream().also { o -> BackupCipher.seal(o, pass, rounds = 20_000).close() }.toByteArray()
        assertThat(open(b)).isEmpty()
    }
}
