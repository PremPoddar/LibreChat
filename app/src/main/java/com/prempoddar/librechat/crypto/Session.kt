package com.prempoddar.librechat.crypto

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.protobuf.ProtoBuf

/** X3DH without one-time prekeys: the recipient's signed prekey stands in, so no server is needed. */
object X3dh {
    private val INFO = "LibreChat_X3DH".toByteArray()
    private val F = ByteArray(32) { 0xFF.toByte() }

    class Started(val sharedSecret: ByteArray, val ephemeral: ByteArray, val ad: ByteArray)

    fun initiate(me: Identity, them: PublicIdentity, prekey: PublicPrekey): Started {
        if (!them.isValid()) throw CryptoException("their identity key is not signed correctly")
        if (!prekey.verify(them)) throw CryptoException("their prekey is not signed by them")
        val ek = X25519KeyPair.generate()
        val dh1 = X25519.agree(me.dh.private, prekey.key)
        val dh2 = X25519.agree(ek.private, them.dhKey)
        val dh3 = X25519.agree(ek.private, prekey.key)
        return Started(kdf(dh1, dh2, dh3), ek.public, ad(me.signing.public, them.signingKey))
    }

    fun respond(me: Identity, prekey: SignedPrekey, them: PublicIdentity, ephemeral: ByteArray): Pair<ByteArray, ByteArray> {
        if (!them.isValid()) throw CryptoException("their identity key is not signed correctly")
        val dh1 = X25519.agree(prekey.keyPair.private, them.dhKey)
        val dh2 = X25519.agree(me.dh.private, ephemeral)
        val dh3 = X25519.agree(prekey.keyPair.private, ephemeral)
        return kdf(dh1, dh2, dh3) to ad(them.signingKey, me.signing.public)
    }

    private fun kdf(vararg dh: ByteArray) = Hkdf.derive(Bytes.concat(F, *dh), ByteArray(32), INFO, 32)
    private fun ad(initiator: ByteArray, responder: ByteArray) = Bytes.concat(initiator, responder)
}

/** Sent with every message of a new session until the other side replies, so a lost first message isn't fatal. */
@Serializable
data class PrekeyHeader(val identity: ByteArray, val ephemeral: ByteArray, val prekeyId: Int) {
    override fun equals(other: Any?) = other is PrekeyHeader && identity.contentEquals(other.identity) &&
        ephemeral.contentEquals(other.ephemeral) && prekeyId == other.prekeyId
    override fun hashCode() = ephemeral.contentHashCode()
}

@Serializable
data class Envelope(val prekey: PrekeyHeader? = null, val message: RatchetMessage) {
    @OptIn(ExperimentalSerializationApi::class)
    fun toBytes(): ByteArray = ProtoBuf.encodeToByteArray(this)

    companion object {
        @OptIn(ExperimentalSerializationApi::class)
        fun fromBytes(bytes: ByteArray): Envelope = ProtoBuf.decodeFromByteArray(bytes)
    }
}

@Serializable
data class Session(
    val ratchet: RatchetState,
    val remoteIdentity: ByteArray,
    val pendingPrekey: PrekeyHeader? = null,
    /** The ephemeral key that started this session; spots replayed first messages. */
    val baseEphemeral: ByteArray,
) {
    val remote: PublicIdentity get() = PublicIdentity.fromBytes(remoteIdentity)!!

    fun encrypt(plaintext: ByteArray): Pair<Session, Envelope> {
        val (r, msg) = DoubleRatchet.encrypt(ratchet, plaintext)
        return copy(ratchet = r) to Envelope(pendingPrekey, msg)
    }

    fun decrypt(env: Envelope): Pair<Session, ByteArray> {
        if (env.prekey != null && !env.prekey.identity.contentEquals(remoteIdentity)) throw CryptoException("different sender")
        val (r, plain) = DoubleRatchet.decrypt(ratchet, env.message)
        // Hearing back proves they have the session; stop attaching the prekey header.
        return copy(ratchet = r, pendingPrekey = null) to plain
    }

    companion object {
        fun initiate(me: Identity, them: PublicIdentity, prekey: PublicPrekey): Session {
            val x = X3dh.initiate(me, them, prekey)
            return Session(
                ratchet = DoubleRatchet.initAlice(x.sharedSecret, prekey.key, x.ad),
                remoteIdentity = them.toBytes(),
                pendingPrekey = PrekeyHeader(me.publicIdentity().toBytes(), x.ephemeral, prekey.id),
                baseEphemeral = x.ephemeral,
            )
        }

        fun respond(me: Identity, prekeys: (Int) -> SignedPrekey?, env: Envelope): Triple<Session, ByteArray, PublicIdentity> {
            val header = env.prekey ?: throw CryptoException("no session and no prekey header")
            val them = PublicIdentity.fromBytes(header.identity) ?: throw CryptoException("bad identity")
            val prekey = prekeys(header.prekeyId) ?: throw CryptoException("unknown prekey ${header.prekeyId}")
            val (sk, ad) = X3dh.respond(me, prekey, them, header.ephemeral)
            val fresh = Session(DoubleRatchet.initBob(sk, prekey.keyPair, ad), them.toBytes(), null, header.ephemeral)
            val (session, plain) = fresh.decrypt(env)
            return Triple(session, plain, them)
        }
    }
}

/**
 * The sessions with one person, newest first. Several can exist when both sides start at once;
 * any of them can decrypt, and the one that last worked is used for sending.
 */
@Serializable
data class SessionSet(val sessions: List<Session> = emptyList()) {
    val current: Session? get() = sessions.firstOrNull()

    fun withNew(session: Session) = SessionSet((listOf(session) + sessions).take(MAX))

    fun encrypt(plaintext: ByteArray): Pair<SessionSet, Envelope> {
        val (s, env) = (current ?: throw CryptoException("no session")).encrypt(plaintext)
        return SessionSet(listOf(s) + sessions.drop(1)) to env
    }

    @OptIn(ExperimentalSerializationApi::class)
    fun toBytes(): ByteArray = ProtoBuf.encodeToByteArray(this)

    companion object {
        private const val MAX = 3

        @OptIn(ExperimentalSerializationApi::class)
        fun fromBytes(bytes: ByteArray): SessionSet = ProtoBuf.decodeFromByteArray(bytes)

        /** Decrypts with an existing session or, for a first message, starts a new one. */
        fun receive(set: SessionSet, me: Identity, prekeys: (Int) -> SignedPrekey?, env: Envelope): Triple<SessionSet, ByteArray, PublicIdentity> {
            set.sessions.forEachIndexed { i, s ->
                val result = runCatching { s.decrypt(env) }.getOrNull() ?: return@forEachIndexed
                val (updated, plain) = result
                val rest = set.sessions.filterIndexed { j, _ -> j != i }
                return Triple(SessionSet(listOf(updated) + rest), plain, updated.remote)
            }
            val header = env.prekey ?: throw CryptoException("could not decrypt")
            if (set.sessions.any { it.baseEphemeral.contentEquals(header.ephemeral) }) throw CryptoException("replayed message")
            val (session, plain, them) = Session.respond(me, prekeys, env)
            return Triple(set.withNew(session), plain, them)
        }
    }
}
