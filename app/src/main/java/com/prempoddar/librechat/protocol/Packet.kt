@file:OptIn(ExperimentalSerializationApi::class)

package com.prempoddar.librechat.protocol

import com.prempoddar.librechat.crypto.Bytes
import com.prempoddar.librechat.crypto.Bytes.hex
import com.prempoddar.librechat.crypto.Ed25519
import com.prempoddar.librechat.crypto.Identity
import com.prempoddar.librechat.crypto.PeerId
import com.prempoddar.librechat.crypto.PublicIdentity
import com.prempoddar.librechat.crypto.PublicPrekey
import com.prempoddar.librechat.crypto.Random
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.protobuf.ProtoBuf
import kotlinx.serialization.protobuf.ProtoNumber
import java.nio.ByteBuffer

enum class PacketType { ANNOUNCE, PUBLIC, DIRECT, GROUP, MEDIA_CHUNK, ACK }

/**
 * What travels between phones. Relays read only the routing fields; the payload is signed or
 * encrypted by the sender. `ttl` is lowered at every hop, so nothing signs over it.
 */
@Serializable
data class Packet(
    @ProtoNumber(1) val version: Int = VERSION,
    @ProtoNumber(2) val type: PacketType,
    @ProtoNumber(3) val id: ByteArray = Random.bytes(8),
    @ProtoNumber(4) val ttl: Int = DEFAULT_TTL,
    @ProtoNumber(5) val sender: String,
    /** Empty for broadcasts. */
    @ProtoNumber(6) val recipient: String = "",
    @ProtoNumber(7) val group: ByteArray = ByteArray(0),
    @ProtoNumber(8) val timestamp: Long,
    @ProtoNumber(9) val payload: ByteArray,
) {
    val key: String get() = id.hex()
    val isBroadcast: Boolean get() = recipient.isEmpty()

    fun toBytes(): ByteArray = ProtoBuf.encodeToByteArray(this)

    override fun equals(other: Any?) = other is Packet && id.contentEquals(other.id) && ttl == other.ttl &&
        type == other.type && payload.contentEquals(other.payload)
    override fun hashCode() = id.contentHashCode()

    companion object {
        const val VERSION = 1
        const val DEFAULT_TTL = 7

        fun fromBytes(bytes: ByteArray): Packet? = runCatching { ProtoBuf.decodeFromByteArray<Packet>(bytes) }
            .getOrNull()?.takeIf { it.version == VERSION && it.id.size == 8 && it.ttl in 1..DEFAULT_TTL }
    }
}

/** "I'm here": keys, name and whether this phone can do Wi-Fi Aware. Signed by the identity key. */
@Serializable
data class Announce(
    @ProtoNumber(1) val identity: ByteArray,
    @ProtoNumber(2) val prekeyId: Int,
    @ProtoNumber(3) val prekey: ByteArray,
    @ProtoNumber(4) val prekeySignature: ByteArray,
    @ProtoNumber(5) val name: String,
    @ProtoNumber(6) val timestamp: Long,
    @ProtoNumber(7) val wifiAware: Boolean = false,
    @ProtoNumber(8) val signature: ByteArray = ByteArray(0),
) {
    val publicIdentity: PublicIdentity? get() = PublicIdentity.fromBytes(identity)
    val publicPrekey: PublicPrekey get() = PublicPrekey(prekeyId, prekey, prekeySignature)

    private fun signedBytes(): ByteArray = Bytes.concat(
        "LibreChat announce".toByteArray(), identity, Bytes.u32(prekeyId), prekey, prekeySignature,
        name.toByteArray(), ByteBuffer.allocate(8).putLong(timestamp).array(), byteArrayOf(if (wifiAware) 1 else 0),
    )

    fun signed(me: Identity) = copy(signature = me.signing.sign(signedBytes()))

    /** Valid when the identity, prekey and announcement signatures all check out and match [sender]. */
    fun verify(sender: String): PublicIdentity? {
        val id = publicIdentity ?: return null
        if (id.peerId.value != sender || !id.isValid()) return null
        if (!publicPrekey.verify(id)) return null
        if (!Ed25519.verify(id.signingKey, signedBytes(), signature)) return null
        if (name.isBlank() || name.length > 32) return null
        return id
    }

    fun toBytes(): ByteArray = ProtoBuf.encodeToByteArray(this)

    companion object {
        fun fromBytes(b: ByteArray): Announce? = runCatching { ProtoBuf.decodeFromByteArray<Announce>(b) }.getOrNull()
    }
}

/** A public-chat message: readable by all, signed so nobody can post as someone else. */
@Serializable
data class PublicPost(
    @ProtoNumber(1) val identity: ByteArray,
    @ProtoNumber(2) val name: String,
    @ProtoNumber(3) val content: ByteArray,
    @ProtoNumber(4) val signature: ByteArray = ByteArray(0),
) {
    private fun signedBytes(packetId: ByteArray, timestamp: Long) = Bytes.concat(
        "LibreChat public".toByteArray(), packetId, ByteBuffer.allocate(8).putLong(timestamp).array(),
        identity, name.toByteArray(), content,
    )

    fun signed(me: Identity, packetId: ByteArray, timestamp: Long) = copy(signature = me.signing.sign(signedBytes(packetId, timestamp)))

    fun verify(packet: Packet): PublicIdentity? {
        val id = PublicIdentity.fromBytes(identity) ?: return null
        if (id.peerId.value != packet.sender) return null
        return id.takeIf { Ed25519.verify(it.signingKey, signedBytes(packet.id, packet.timestamp), signature) }
    }

    fun toBytes(): ByteArray = ProtoBuf.encodeToByteArray(this)

    companion object {
        fun fromBytes(b: ByteArray): PublicPost? = runCatching { ProtoBuf.decodeFromByteArray<PublicPost>(b) }.getOrNull()
    }
}

/** A piece of an encrypted media blob. Not secret by itself: the key travels in the message. */
@Serializable
data class MediaChunk(
    @ProtoNumber(1) val transferId: String,
    @ProtoNumber(2) val index: Int,
    @ProtoNumber(3) val total: Int,
    @ProtoNumber(4) val bytes: ByteArray,
) {
    fun toBytes(): ByteArray = ProtoBuf.encodeToByteArray(this)

    companion object {
        fun fromBytes(b: ByteArray): MediaChunk? = runCatching { ProtoBuf.decodeFromByteArray<MediaChunk>(b) }.getOrNull()
    }
}

/** "I received these packets", signed by the recipient so relays can trust it and drop their copies. */
@Serializable
data class Ack(
    @ProtoNumber(1) val ids: List<ByteArray>,
    @ProtoNumber(2) val signature: ByteArray = ByteArray(0),
) {
    private fun signedBytes() = Bytes.concat("LibreChat ack".toByteArray(), *ids.toTypedArray())

    fun signed(me: Identity) = copy(signature = me.signing.sign(signedBytes()))
    fun verify(signingKey: ByteArray) = Ed25519.verify(signingKey, signedBytes(), signature)

    fun toBytes(): ByteArray = ProtoBuf.encodeToByteArray(this)

    companion object {
        fun fromBytes(b: ByteArray): Ack? = runCatching { ProtoBuf.decodeFromByteArray<Ack>(b) }.getOrNull()
    }
}

fun PeerId.isMe(other: String) = value == other
