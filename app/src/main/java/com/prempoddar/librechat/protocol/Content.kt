@file:OptIn(ExperimentalSerializationApi::class)

package com.prempoddar.librechat.protocol

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.protobuf.ProtoBuf

@Serializable
data class GroupMemberInfo(val identity: ByteArray, val name: String)

/** Everything people say to each other. Inside encryption for DMs and groups; signed for Public. */
@Serializable
sealed class Content {
    @Serializable @SerialName("text")
    data class Text(val id: String, val text: String, val replyTo: String? = null, val expiresIn: Long = 0) : Content()

    @Serializable @SerialName("media")
    data class Media(
        val id: String,
        val kind: MediaKind,
        val mime: String,
        val size: Long,
        val durationMs: Long = 0,
        val width: Int = 0,
        val height: Int = 0,
        val thumbnail: ByteArray = ByteArray(0),
        val key: ByteArray,
        val digest: ByteArray,
        val transferId: String,
        val chunks: Int,
        val caption: String = "",
        val replyTo: String? = null,
        val expiresIn: Long = 0,
        /** The file's name, for files sent as files. */
        val name: String = "",
    ) : Content()

    @Serializable @SerialName("reaction")
    data class Reaction(val target: String, val emoji: String, val remove: Boolean = false) : Content()

    @Serializable @SerialName("delete")
    data class Delete(val target: String) : Content()

    @Serializable @SerialName("read")
    data class Read(val ids: List<String>) : Content()

    /** A message request was accepted. */
    @Serializable @SerialName("accept")
    data object Accept : Content()

    @Serializable @SerialName("group")
    data class GroupState(
        val groupId: ByteArray,
        val name: String,
        val admin: String,
        val members: List<GroupMemberInfo>,
        val version: Long,
    ) : Content()

    @Serializable @SerialName("leave")
    data class GroupLeave(val groupId: ByteArray) : Content()

    @Serializable @SerialName("sender_key")
    data class SenderKey(val distribution: ByteArray) : Content()

    /** Receiver asks for media pieces that never arrived. */
    @Serializable @SerialName("missing")
    data class Missing(val transferId: String, val indexes: List<Int>) : Content()

    @Serializable @SerialName("timer")
    data class Timer(val seconds: Long) : Content()

    fun toBytes(): ByteArray = ProtoBuf.encodeToByteArray(serializer(), this)

    companion object {
        fun fromBytes(b: ByteArray): Content? = runCatching { ProtoBuf.decodeFromByteArray<Content>(b) }.getOrNull()
    }
}

enum class MediaKind { PHOTO, VOICE, FILE }
