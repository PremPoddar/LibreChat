package com.prempoddar.librechat.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.prempoddar.librechat.engine.ConvKind
import com.prempoddar.librechat.engine.MsgKind
import com.prempoddar.librechat.engine.MsgStatus
import com.prempoddar.librechat.engine.PersonState

@Entity(tableName = "kv")
class KeyValueEntity(@PrimaryKey val key: String, val value: ByteArray)

@Entity(tableName = "people")
data class PersonEntity(
    @PrimaryKey val peerId: String,
    val identity: ByteArray,
    val name: String,
    val prekeyId: Int?,
    val prekey: ByteArray?,
    val prekeySignature: ByteArray?,
    val lastSeen: Long,
    val hops: Int,
    val wifiAware: Boolean,
    val state: PersonState,
    val verified: Boolean,
)

@Entity(tableName = "sessions")
class SessionEntity(@PrimaryKey val peer: String, val blob: ByteArray)

/** [peer] is empty for my own sender key in the group. */
@Entity(tableName = "sender_keys", primaryKeys = ["groupId", "peer"])
class SenderKeyEntity(val groupId: String, val peer: String, val blob: ByteArray)

@Entity(tableName = "key_shares")
class KeyShareEntity(@PrimaryKey val groupId: String, val peers: String)

@Entity(tableName = "chat_groups")
data class GroupEntity(
    @PrimaryKey val id: String,
    val name: String,
    val admin: String,
    val members: String,
    val version: Long,
    val active: Boolean,
)

@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey val id: String,
    val kind: ConvKind,
    val title: String,
    val pinned: Boolean,
    val mutedUntil: Long,
    val timerSeconds: Long,
    val lastActivity: Long,
    val unread: Int,
    val request: Boolean,
    val preview: String,
)

@Entity(tableName = "messages", indices = [Index("convId"), Index("expiresAt"), Index("mediaTransfer")])
data class MessageEntity(
    @PrimaryKey val id: String,
    val convId: String,
    val sender: String,
    val mine: Boolean,
    val kind: MsgKind,
    val text: String,
    val at: Long,
    val status: MsgStatus,
    val replyTo: String?,
    val expiresAt: Long,
    val deleted: Boolean,
    val reactions: String,
    val mediaMime: String?,
    val mediaSize: Long?,
    val mediaDuration: Long?,
    val mediaWidth: Int?,
    val mediaHeight: Int?,
    val mediaThumb: ByteArray?,
    val mediaKey: ByteArray?,
    val mediaDigest: ByteArray?,
    val mediaTransfer: String?,
    val mediaChunks: Int?,
    val mediaReceived: Int?,
    val mediaPath: String?,
)

@Entity(tableName = "outbox")
class OutboxEntity(
    @PrimaryKey val packetKey: String,
    val packet: ByteArray,
    val messageId: String?,
    val peer: String,
    val createdAt: Long,
    val lastSentAt: Long,
)

@Entity(tableName = "pending")
class PendingEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val peer: String,
    val content: ByteArray,
    val messageId: String?,
)
