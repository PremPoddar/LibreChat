package com.prempoddar.librechat.backup

import android.content.Context
import android.content.Intent
import com.prempoddar.librechat.AppGraph
import com.prempoddar.librechat.data.LocalKeys
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Moving LibreChat to a new phone. Everything that makes up your LibreChat (identity, contacts,
 * chats, sessions, media, settings) goes into one file sealed with a passphrase you choose. The
 * keys that open the database normally never leave this phone's Keystore; in a backup they are
 * protected by the passphrase instead, and on restore they are locked into the new phone's Keystore.
 */
object ChatBackup {
    private const val KEYS = "keys/"
    private const val DB = "db/librechat.db"
    private const val FILES = "files/"

    /** Folders under the app's files kept in a backup: received media and settings. */
    private val KEPT = listOf("media", "datastore")

    suspend fun backup(context: Context, out: OutputStream, passphrase: CharArray) = withContext(Dispatchers.IO) {
        // Fold the write-ahead log into the database file so one file holds everything.
        AppGraph.database.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(TRUNCATE)").use { it.moveToFirst() }
        BackupCipher.seal(out, passphrase).use { sealed ->
            ZipOutputStream(sealed).use { zip ->
                LocalKeys.all(context).forEach { (name, secret) -> zip.putNextEntry(ZipEntry(KEYS + name)); zip.write(secret); zip.closeEntry() }
                zip.putNextEntry(ZipEntry(DB)); context.getDatabasePath("librechat.db").inputStream().use { it.copyTo(zip) }; zip.closeEntry()
                KEPT.forEach { folder ->
                    File(context.filesDir, folder).walkTopDown().filter { it.isFile }.forEach { f ->
                        zip.putNextEntry(ZipEntry(FILES + f.relativeTo(context.filesDir).path)); f.inputStream().use { it.copyTo(zip) }; zip.closeEntry()
                    }
                }
            }
        }
    }

    /**
     * Checks the whole backup first (passphrase, every chunk), then replaces this phone's LibreChat
     * with it. The app must restart afterwards; [restart] does that.
     */
    suspend fun restore(context: Context, input: InputStream, passphrase: CharArray) = withContext(Dispatchers.IO) {
        val staging = File(context.cacheDir, "restore").apply { deleteRecursively(); mkdirs() }
        val keys = HashMap<String, ByteArray>()
        var hasDb = false
        try {
            ZipInputStream(BackupCipher.open(input, passphrase)).use { zip ->
                while (true) {
                    val e = zip.nextEntry ?: break
                    if (e.isDirectory) continue
                    when {
                        e.name.startsWith(KEYS) -> keys[e.name.removePrefix(KEYS).filter { it.isLetterOrDigit() || it == '_' || it == '-' }] = zip.readBytes()
                        e.name == DB -> { File(staging, "librechat.db").outputStream().use { zip.copyTo(it) }; hasDb = true }
                        e.name.startsWith(FILES) -> {
                            // Only inside the kept folders, and never upward: a backup can't write elsewhere.
                            val rel = e.name.removePrefix(FILES)
                            val parts = rel.split('/')
                            if (parts.size < 2 || parts[0] !in KEPT || parts.any { it == ".." || it.isEmpty() }) continue
                            File(staging, "files/$rel").apply { parentFile?.mkdirs() }.outputStream().use { zip.copyTo(it) }
                        }
                    }
                }
            }
            if (!hasDb || "db" !in keys) throw BackupCipher.Damaged()
            // Everything checked out: stop using the current data and put the backup in its place.
            com.prempoddar.librechat.android.MeshService.stop(context)
            AppGraph.database.close()
            val dbFile = context.getDatabasePath("librechat.db")
            listOf("", "-wal", "-shm", "-journal").forEach { File(dbFile.path + it).delete() }
            File(staging, "librechat.db").copyTo(dbFile, overwrite = true)
            KEPT.forEach { File(context.filesDir, it).deleteRecursively() }
            File(staging, "files").takeIf { it.isDirectory }?.copyRecursively(context.filesDir, overwrite = true)
            keys.forEach { (name, secret) -> LocalKeys.put(context, name, secret) }
        } finally {
            staging.deleteRecursively()
        }
    }

    /** Starts LibreChat afresh on the restored data. */
    fun restart(context: Context) {
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)!!
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        context.startActivity(intent)
        Runtime.getRuntime().exit(0)
    }
}
