package com.prempoddar.librechat.engine

import com.prempoddar.librechat.crypto.PublicIdentity
import com.prempoddar.librechat.crypto.PublicPrekey

enum class ConvKind { PUBLIC, DIRECT, GROUP }

object ConvIds {
    const val PUBLIC = "public"
    fun direct(peer: String) = "dm:$peer"
    fun group(id: String) = "group:$id"
    fun kind(conv: String) = when {
        conv == PUBLIC -> ConvKind.PUBLIC
        conv.startsWith("dm:") -> ConvKind.DIRECT
        else -> ConvKind.GROUP
    }
    fun peerOf(conv: String) = conv.removePrefix("dm:")
    fun groupOf(conv: String) = conv.removePrefix("group:")
}

/** How I relate to someone. */
enum class PersonState {
    /** Seen on the mesh; no conversation yet. */
    STRANGER,
    /** They messaged me first and I haven't accepted. */
    REQUEST,
    ACCEPTED,
    BLOCKED,
}

data class Person(
    val peerId: String,
    val identity: ByteArray,
    val name: String,
    val prekeyId: Int? = null,
    val prekey: ByteArray? = null,
    val prekeySignature: ByteArray? = null,
    val lastSeen: Long = 0,
    /** 0 = direct neighbour, else how many relays away when last heard. */
    val hops: Int = Int.MAX_VALUE,
    val wifiAware: Boolean = false,
    val state: PersonState = PersonState.STRANGER,
    val verified: Boolean = false,
) {
    val publicIdentity: PublicIdentity get() = PublicIdentity.fromBytes(identity)!!
    val publicPrekey: PublicPrekey? get() = if (prekeyId != null && prekey != null && prekeySignature != null) PublicPrekey(prekeyId, prekey, prekeySignature) else null
}

data class Conversation(
    val id: String,
    val kind: ConvKind,
    val title: String,
    val pinned: Boolean = false,
    val mutedUntil: Long = 0,
    /** Disappearing-message timer; 0 = off. */
    val timerSeconds: Long = 0,
    val lastActivity: Long = 0,
    val unread: Int = 0,
    /** A message request waiting for my answer. */
    val request: Boolean = false,
    val preview: String = "",
)

data class Group(
    val id: String,
    val name: String,
    val admin: String,
    val members: List<String>,
    val version: Long,
    /** False once I've left or been removed. */
    val active: Boolean = true,
)

enum class MsgStatus { SENDING, SENT, DELIVERED, READ, FAILED, RECEIVED, SEEN }

enum class MsgKind { TEXT, PHOTO, VOICE, SYSTEM }

data class MediaInfo(
    val mime: String,
    val size: Long,
    val durationMs: Long,
    val width: Int,
    val height: Int,
    val thumbnail: ByteArray,
    val key: ByteArray,
    val digest: ByteArray,
    val transferId: String,
    val chunks: Int,
    val received: Int = 0,
    /** Local decrypted file, once complete. */
    val path: String? = null,
)

data class ChatMessage(
    val id: String,
    val convId: String,
    val sender: String,
    val mine: Boolean,
    val kind: MsgKind,
    val text: String,
    val at: Long,
    val status: MsgStatus,
    val replyTo: String? = null,
    val expiresAt: Long = 0,
    val deleted: Boolean = false,
    val reactions: Map<String, List<String>> = emptyMap(),
    val media: MediaInfo? = null,
)

/** One of my direct packets waiting for its delivery acknowledgement. */
data class OutboxEntry(
    val packetKey: String,
    val packet: ByteArray,
    val messageId: String?,
    val peer: String,
    val createdAt: Long,
    val lastSentAt: Long,
)

/** Something to send to a person once we know their prekey (they haven't been heard yet). */
data class PendingContent(val id: Long, val peer: String, val content: ByteArray, val messageId: String?)
