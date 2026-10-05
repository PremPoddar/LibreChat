package com.prempoddar.librechat.data

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.prempoddar.librechat.crypto.Random
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

@Database(
    entities = [
        KeyValueEntity::class, PersonEntity::class, SessionEntity::class, SenderKeyEntity::class,
        KeyShareEntity::class, GroupEntity::class, ConversationEntity::class, MessageEntity::class,
        OutboxEntity::class, PendingEntity::class,
    ],
    version = 1,
)
abstract class ChatDatabase : RoomDatabase() {
    abstract fun dao(): ChatDao

    companion object {
        /** The database is SQLCipher-encrypted with a random key that only Android Keystore can unwrap. */
        fun open(context: Context): ChatDatabase {
            System.loadLibrary("sqlcipher")
            val key = LocalKeys.secret(context, "db")
            return Room.databaseBuilder(context, ChatDatabase::class.java, "librechat.db")
                .openHelperFactory(SupportOpenHelperFactory(key))
                .build()
        }
    }
}

/**
 * Random 32-byte secrets for local encryption, stored wrapped by a non-exportable Android
 * Keystore AES key. Copying the app's files to another device gets nothing readable.
 */
object LocalKeys {
    private const val ALIAS = "librechat-local"
    private const val PREFS = "local-keys"

    @Synchronized
    fun secret(context: Context, name: String): ByteArray {
        val prefs: SharedPreferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(name, null)?.let { return unwrap(Base64.decode(it, Base64.NO_WRAP)) }
        val secret = Random.bytes(32)
        prefs.edit().putString(name, Base64.encodeToString(wrap(secret), Base64.NO_WRAP)).apply()
        return secret
    }

    private fun keystoreKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    private fun wrap(secret: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, keystoreKey())
        return c.iv + c.doFinal(secret)
    }

    private fun unwrap(blob: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, keystoreKey(), GCMParameterSpec(128, blob.copyOfRange(0, 12)))
        return c.doFinal(blob.copyOfRange(12, blob.size))
    }
}
