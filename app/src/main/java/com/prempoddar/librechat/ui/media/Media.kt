package com.prempoddar.librechat.ui.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.exifinterface.media.ExifInterface
import com.prempoddar.librechat.AppGraph
import java.io.ByteArrayOutputStream
import java.io.File

class PreparedPhoto(val bytes: ByteArray, val thumbnail: ByteArray, val width: Int, val height: Int)

object Photos {
    // About 150–250 KB: big enough to look good, small enough to cross the mesh in seconds.
    private const val MAX_SIDE = 1280
    private const val THUMB_SIDE = 48
    private val cache = object : LruCache<String, ImageBitmap>(24) {}

    /** Shrinks a picked photo to at most 1600 px, upright, as JPEG — mesh links are slow. */
    fun prepare(context: Context, uri: Uri): PreparedPhoto? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) } ?: return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2
        val raw = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return null
        val rotation = context.contentResolver.openInputStream(uri)?.use {
            when (ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        } ?: 0f
        val scale = minOf(1f, MAX_SIDE.toFloat() / maxOf(raw.width, raw.height))
        val m = Matrix().apply { postScale(scale, scale); postRotate(rotation) }
        val photo = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true)
        val thumbScale = THUMB_SIDE.toFloat() / maxOf(photo.width, photo.height)
        val thumb = Bitmap.createScaledBitmap(photo, maxOf(1, (photo.width * thumbScale).toInt()), maxOf(1, (photo.height * thumbScale).toInt()), true)
        return PreparedPhoto(jpeg(photo, 75), jpeg(thumb, 50), photo.width, photo.height)
    }

    private fun jpeg(b: Bitmap, quality: Int) = ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.JPEG, quality, it) }.toByteArray()

    /** A stored photo, decrypted and decoded (cached). */
    fun load(path: String): ImageBitmap? {
        cache.get(path)?.let { return it }
        val bytes = AppGraph.files.read(path) ?: return null
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()?.also { cache.put(path, it) }
    }

    fun decode(bytes: ByteArray): ImageBitmap? =
        if (bytes.isEmpty()) null else BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
}

/** Voice notes: AAC at a low bitrate, mono — about 4 KB a second. */
class VoiceRecorder(private val context: Context) {
    private var recorder: MediaRecorder? = null
    private var file: File? = null
    private var startedAt = 0L

    fun start(): Boolean = runCatching {
        val f = File(context.cacheDir, "voice-${System.nanoTime()}.m4a")
        val r = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(context) else @Suppress("DEPRECATION") MediaRecorder()
        r.setAudioSource(MediaRecorder.AudioSource.MIC)
        r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        r.setAudioChannels(1)
        r.setAudioSamplingRate(22_050)
        r.setAudioEncodingBitRate(32_000)
        r.setMaxDuration(MAX_MS)
        r.setOutputFile(f.absolutePath)
        r.prepare()
        r.start()
        recorder = r; file = f; startedAt = System.currentTimeMillis()
    }.isSuccess

    /** The recording and its length, or null if it was too short to keep. */
    fun stop(): Pair<ByteArray, Long>? {
        val r = recorder ?: return null
        val f = file
        recorder = null
        val ms = System.currentTimeMillis() - startedAt
        val ok = runCatching { r.stop() }.isSuccess
        r.release()
        val bytes = f?.takeIf { ok && ms >= 700 }?.readBytes()
        f?.delete()
        return bytes?.let { it to minOf(ms, MAX_MS.toLong()) }
    }

    fun cancel() {
        runCatching { recorder?.stop() }
        recorder?.release(); recorder = null
        file?.delete()
    }

    companion object {
        const val MAX_MS = 120_000
    }
}

/** Plays one voice note at a time. */
object VoicePlayer {
    private var player: MediaPlayer? = null
    var playingPath: String? = null
        private set

    fun toggle(path: String, onDone: () -> Unit): Boolean {
        val wasThis = playingPath == path
        stop()
        if (wasThis) return false
        val file = AppGraph.files.playable(path, "m4a") ?: return false
        player = MediaPlayer().apply {
            setDataSource(file.absolutePath)
            setOnCompletionListener { stop(); onDone() }
            prepare()
            start()
        }
        playingPath = path
        return true
    }

    fun position(): Int = runCatching { player?.currentPosition ?: 0 }.getOrDefault(0)

    fun stop() {
        runCatching { player?.stop() }
        player?.release()
        player = null
        playingPath = null
    }
}
