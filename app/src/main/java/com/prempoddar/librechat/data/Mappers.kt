package com.prempoddar.librechat.data

import com.prempoddar.librechat.engine.ChatMessage
import com.prempoddar.librechat.engine.Conversation
import com.prempoddar.librechat.engine.Group
import com.prempoddar.librechat.engine.MediaInfo
import com.prempoddar.librechat.engine.Person
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val json = Json

fun PersonEntity.toModel() = Person(peerId, identity, name, prekeyId, prekey, prekeySignature, lastSeen, hops, wifiAware, state, verified)
fun Person.toEntity() = PersonEntity(peerId, identity, name, prekeyId, prekey, prekeySignature, lastSeen, hops, wifiAware, state, verified)

fun GroupEntity.toModel() = Group(id, name, admin, members.split(',').filter { it.isNotEmpty() }, version, active)
fun Group.toEntity() = GroupEntity(id, name, admin, members.joinToString(","), version, active)

fun ConversationEntity.toModel() = Conversation(id, kind, title, pinned, mutedUntil, timerSeconds, lastActivity, unread, request, preview)
fun Conversation.toEntity() = ConversationEntity(id, kind, title, pinned, mutedUntil, timerSeconds, lastActivity, unread, request, preview)

fun MessageEntity.toModel() = ChatMessage(
    id = id, convId = convId, sender = sender, mine = mine, kind = kind, text = text, at = at, status = status,
    replyTo = replyTo, expiresAt = expiresAt, deleted = deleted,
    reactions = runCatching { json.decodeFromString<Map<String, List<String>>>(reactions) }.getOrDefault(emptyMap()),
    media = if (mediaTransfer == null) null else MediaInfo(
        mediaMime ?: "", mediaSize ?: 0, mediaDuration ?: 0, mediaWidth ?: 0, mediaHeight ?: 0, mediaThumb ?: ByteArray(0),
        mediaKey ?: ByteArray(0), mediaDigest ?: ByteArray(0), mediaTransfer, mediaChunks ?: 0, mediaReceived ?: 0, mediaPath, mediaName.orEmpty(),
    ),
)

fun ChatMessage.toEntity() = MessageEntity(
    id = id, convId = convId, sender = sender, mine = mine, kind = kind, text = text, at = at, status = status,
    replyTo = replyTo, expiresAt = expiresAt, deleted = deleted, reactions = json.encodeToString(reactions),
    mediaMime = media?.mime, mediaSize = media?.size, mediaDuration = media?.durationMs, mediaWidth = media?.width,
    mediaHeight = media?.height, mediaThumb = media?.thumbnail, mediaKey = media?.key, mediaDigest = media?.digest,
    mediaTransfer = media?.transferId, mediaChunks = media?.chunks, mediaReceived = media?.received, mediaPath = media?.path, mediaName = media?.name,
)
