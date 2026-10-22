package com.prempoddar.librechat.engine

import com.prempoddar.librechat.crypto.Bytes
import com.prempoddar.librechat.crypto.Bytes.hex
import com.prempoddar.librechat.crypto.CryptoException
import com.prempoddar.librechat.crypto.Identity
import com.prempoddar.librechat.crypto.MediaCrypto
import com.prempoddar.librechat.crypto.PublicIdentity
import com.prempoddar.librechat.crypto.Random
import com.prempoddar.librechat.crypto.SenderKeyDistribution
import com.prempoddar.librechat.crypto.SenderKeys
import com.prempoddar.librechat.crypto.Envelope
import com.prempoddar.librechat.crypto.GroupMessage
import com.prempoddar.librechat.crypto.Session
import com.prempoddar.librechat.crypto.SessionSet
import com.prempoddar.librechat.crypto.SignedPrekey
import com.prempoddar.librechat.mesh.LinkSpeed
import com.prempoddar.librechat.mesh.Router
import com.prempoddar.librechat.mesh.Send
import com.prempoddar.librechat.protocol.Ack
import com.prempoddar.librechat.protocol.Announce
import com.prempoddar.librechat.protocol.Content
import com.prempoddar.librechat.protocol.GroupMemberInfo
import com.prempoddar.librechat.protocol.MediaChunk
import com.prempoddar.librechat.protocol.MediaKind
import com.prempoddar.librechat.protocol.Packet
import com.prempoddar.librechat.protocol.PacketType
import com.prempoddar.librechat.protocol.PublicPost
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed interface MessengerEvent {
    data class NewMessage(val conversation: Conversation, val message: ChatMessage, val from: Person?) : MessengerEvent
}

/**
 * The messenger: turns what people do into encrypted packets and packets back into messages.
 * Transports feed it raw packets per link and send whatever it hands to [out]. Everything runs
 * under one lock, so stores never see two changes at once.
 */
class Messenger(
    private val store: EngineStore,
    private val files: MediaFiles,
    private val out: (link: String, bytes: ByteArray) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val lock = Mutex()
    private lateinit var identity: Identity
    private lateinit var router: Router

    /** Whether this phone can offer Wi-Fi Aware links; included in announcements. */
    @Volatile var wifiAware: Boolean = false

    private val _events = MutableSharedFlow<MessengerEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<MessengerEvent> = _events.asSharedFlow()

    private class Transfer(val total: Int, val parts: Array<ByteArray?>, var lastChunkAt: Long, var lastAskedAt: Long = 0)
    private val transfers = HashMap<String, Transfer>()
    /** Group packets that arrived before their sender's key. */
    private val waitingGroup = ArrayList<Pair<Long, Packet>>()
    /** My packets not yet handed to any link, and the message each belongs to. */
    private val unsent = HashMap<String, String>()

    private val _relays = kotlinx.coroutines.flow.MutableStateFlow<Map<String, String>>(emptyMap())
    /**
     * For people heard through the mesh: the neighbour whose link their latest announcement came
     * in on, the first hop on the way to them.
     */
    val relays: kotlinx.coroutines.flow.StateFlow<Map<String, String>> = _relays

    val me: String get() = identity.peerId.value
    val myIdentity: Identity get() = identity

    suspend fun start() = locked { }

    /** Runs [block] under the lock, creating keys and state first if this is the first call. */
    private suspend fun <T> locked(block: suspend () -> T): T = lock.withLock {
        if (!::identity.isInitialized) {
            identity = store.identity() ?: Identity.generate().also { store.saveIdentity(it) }
            router = Router(identity.peerId.value, ackVerifier = { true }, clock = clock)
            rotatePrekeys()
            ensureConversation(ConvIds.PUBLIC)
        }
        block()
    }

    // Links

    suspend fun onLinkUp(link: String, speed: LinkSpeed) = locked {
        router.linkUp(link, speed)
        // Introduce ourselves on the new link straight away.
        out(link, announcePacket().toBytes())
    }

    suspend fun onLinkDown(link: String) = locked { router.linkDown(link) }

    fun neighbours(): Map<String, List<String>> = router.neighbours()

    suspend fun announce() = locked { emit(router.send(announcePacket())) }

    private suspend fun announcePacket(): Packet {
        val prekey = store.prekeys().maxBy { it.id }
        val now = clock()
        val a = Announce(
            identity = identity.publicIdentity().toBytes(),
            prekeyId = prekey.id,
            prekey = prekey.keyPair.public,
            prekeySignature = prekey.signature,
            name = store.myName(),
            timestamp = now,
            wifiAware = wifiAware,
        ).signed(identity)
        return Packet(type = PacketType.ANNOUNCE, sender = me, timestamp = now, payload = a.toBytes())
    }

    // Incoming

    suspend fun onPacket(link: String, bytes: ByteArray) = locked {
        val packet = Packet.fromBytes(bytes) ?: return@locked
        var announce: Announce? = null
        if (packet.type == PacketType.ANNOUNCE) {
            // A forged announcement must not even be relayed or teach the router a neighbour.
            announce = Announce.fromBytes(packet.payload)?.takeIf { it.verify(packet.sender) != null } ?: return@locked
        }
        var acked = emptyList<String>()
        if (packet.type == PacketType.ACK) {
            val ack = Ack.fromBytes(packet.payload)
            val signer = store.person(packet.sender)
            if (ack != null && signer != null && ack.verify(signer.publicIdentity.signingKey)) acked = ack.ids.map { it.hex() }
        }
        val routed = router.receive(link, packet, acked)
        emit(routed.sends)
        val p = routed.deliver ?: return@locked
        if (routed.duplicate) {
            sendAck(p)
            return@locked
        }
        runCatching {
            when (p.type) {
                PacketType.ANNOUNCE -> onAnnounce(p, announce!!, link)
                PacketType.PUBLIC -> onPublic(p)
                PacketType.DIRECT -> onDirect(p)
                PacketType.GROUP -> onGroup(p)
                PacketType.MEDIA_CHUNK -> onChunk(p)
                PacketType.ACK -> onAck(acked)
            }
        }
    }

    private suspend fun onAnnounce(p: Packet, a: Announce, link: String) {
        val existing = store.person(p.sender)
        val relay = router.peerOn(link)?.takeIf { p.ttl < Packet.DEFAULT_TTL && it != p.sender }
        _relays.value = if (relay != null) _relays.value + (p.sender to relay) else _relays.value - p.sender
        if (existing == null) noticeReinstall(p.sender, a.name)
        val person = (existing ?: Person(p.sender, a.identity, a.name)).copy(
            name = a.name,
            prekeyId = a.prekeyId,
            prekey = a.prekey,
            prekeySignature = a.prekeySignature,
            lastSeen = clock(),
            hops = Packet.DEFAULT_TTL - p.ttl,
            wifiAware = a.wifiAware,
        )
        store.savePerson(person)
        store.conversation(ConvIds.direct(p.sender))?.let { if (it.title != a.name) store.saveConversation(it.copy(title = a.name)) }
        flushPending(p.sender)
    }

    /**
     * A brand-new identity with the same name as a contact who has gone quiet is most likely them
     * after reinstalling LibreChat (a reinstall makes new keys). Their old chat says so and offers
     * to move over; names aren't proof, so it's never done without the user.
     */
    private suspend fun noticeReinstall(newId: String, name: String) {
        val now = clock()
        val old = store.people().filter {
            it.peerId != newId && it.peerId != me && it.name.equals(name, ignoreCase = true) &&
                (it.state == PersonState.ACCEPTED || it.state == PersonState.REQUEST) &&
                now - it.lastSeen > REINSTALL_QUIET_MS && store.conversation(ConvIds.direct(it.peerId)) != null
        }.maxByOrNull { it.lastSeen } ?: return
        if (store.successor(old.peerId) == newId) return
        store.saveSuccessor(old.peerId, newId)
        system(ConvIds.direct(old.peerId), "A phone called $name is nearby with a new identity, as happens after reinstalling LibreChat.")
    }

    /**
     * Carries the chat with [old] over to their new identity [new] (see [noticeReinstall]), once the
     * user is sure it's them. Anything still waiting for the old identity goes to the new one; what
     * was already encrypted for the old keys can't be read by the new ones and is marked not sent.
     */
    suspend fun continueWith(old: String, new: String) = locked {
        val person = store.person(new) ?: return@locked
        val from = ConvIds.direct(old); val to = ConvIds.direct(new)
        val was = store.conversation(from)
        val there = store.conversation(to)
        store.moveMessages(from, to)
        val newer = if ((there?.lastActivity ?: 0) >= (was?.lastActivity ?: 0)) there else was
        store.saveConversation(
            (was ?: there ?: Conversation(to, ConvKind.DIRECT, person.name)).copy(
                id = to, title = person.name, request = false,
                unread = (was?.unread ?: 0) + (there?.unread ?: 0),
                lastActivity = maxOf(was?.lastActivity ?: 0, there?.lastActivity ?: 0),
                preview = newer?.preview.orEmpty(),
            ),
        )
        if (was != null) store.deleteConversation(from)
        store.savePerson(person.copy(state = PersonState.ACCEPTED))
        store.outbox().filter { it.peer == old }.forEach { e ->
            store.deleteOutbox(e.packetKey)
            e.messageId?.let { id -> store.message(id)?.let { m -> if (m.status == MsgStatus.SENDING || m.status == MsgStatus.SENT) store.saveMessage(m.copy(status = MsgStatus.FAILED)) } }
        }
        store.pending(old).forEach { p -> store.addPending(new, p.content, p.messageId); store.deletePending(p.id) }
        store.saveSuccessor(old, null)
        system(to, "Chat moved to ${person.name}'s new identity. Compare safety numbers to be sure it's them.")
        flushPending(new)
    }

    /** "That's not them": forget the suggestion. */
    suspend fun keepSeparate(old: String) = locked { store.saveSuccessor(old, null) }

    private suspend fun onPublic(p: Packet) {
        val post = PublicPost.fromBytes(p.payload) ?: return
        val id = post.verify(p) ?: return
        val person = store.person(p.sender) ?: Person(p.sender, id.toBytes(), post.name.take(32)).also { store.savePerson(it) }
        if (person.state == PersonState.BLOCKED) return
        val content = Content.fromBytes(post.content) ?: return
        if (content !is Content.Text && content !is Content.Reaction && content !is Content.Delete && content !is Content.Media) return
        if (content is Content.Media && content.chunks > MAX_PUBLIC_CHUNKS) return
        handleContent(p.sender, ConvIds.PUBLIC, content, p.timestamp)
    }

    private suspend fun onDirect(p: Packet) {
        if (p.recipient != me) return
        val known = store.person(p.sender)
        if (known?.state == PersonState.BLOCKED) return
        val env = runCatching { Envelope.fromBytes(p.payload) }.getOrNull() ?: return
        val prekeys = store.prekeys()
        val (sessions, plain, from) = try {
            SessionSet.receive(store.sessions(p.sender) ?: SessionSet(), identity, { id -> prekeys.firstOrNull { it.id == id } }, env)
        } catch (e: CryptoException) {
            return
        }
        if (from.peerId.value != p.sender) return // someone else's keys under this sender id
        store.saveSessions(p.sender, sessions)
        sendAck(p)
        if (known == null) store.savePerson(Person(p.sender, from.toBytes(), "Unknown ${p.sender.take(4)}"))
        val content = Content.fromBytes(plain) ?: return
        handleContent(p.sender, ConvIds.direct(p.sender), content, p.timestamp)
    }

    private suspend fun onGroup(p: Packet) {
        val gid = p.group.hex()
        val g = store.group(gid) ?: return
        if (!g.active || me !in g.members || p.sender !in g.members) return
        if (store.person(p.sender)?.state == PersonState.BLOCKED) return
        val msg = runCatching { GroupMessage.fromBytes(p.payload) }.getOrNull() ?: return
        val state = store.senderKey(gid, p.sender)
        if (state == null || state.keyId != msg.keyId) {
            waitingGroup += clock() to p
            if (waitingGroup.size > MAX_WAITING_GROUP) waitingGroup.removeAt(0)
            return
        }
        val (next, plain) = try {
            SenderKeys.decrypt(state, p.group, msg)
        } catch (e: CryptoException) {
            return
        }
        store.saveSenderKey(gid, p.sender, next)
        val content = Content.fromBytes(plain) ?: return
        if (content is Content.GroupState || content is Content.SenderKey) return
        handleContent(p.sender, ConvIds.group(gid), content, p.timestamp)
    }

    private suspend fun onChunk(p: Packet) {
        val forMe = p.recipient == me || (p.isBroadcast && p.group.isNotEmpty() && store.group(p.group.hex())?.let { it.active && me in it.members } == true) ||
            // Public pieces go to everyone, but only within the smaller public limit.
            (p.isBroadcast && p.group.isEmpty() && (MediaChunk.fromBytes(p.payload)?.total ?: Int.MAX_VALUE) <= MAX_PUBLIC_CHUNKS)
        if (!forMe) return
        val chunk = MediaChunk.fromBytes(p.payload) ?: return
        if (chunk.total !in 1..MAX_CHUNKS || chunk.index !in 0 until chunk.total) return
        val t = transfers.getOrPut(chunk.transferId) { Transfer(chunk.total, arrayOfNulls(chunk.total), clock()) }
        if (t.total != chunk.total) return
        t.parts[chunk.index] = chunk.bytes
        t.lastChunkAt = clock()
        updateProgress(chunk.transferId, t)
        if (t.parts.all { it != null }) finishTransfer(chunk.transferId)
    }

    private suspend fun updateProgress(transferId: String, t: Transfer) {
        val msg = store.incompleteMedia().firstOrNull { it.media?.transferId == transferId } ?: return
        val received = t.parts.count { it != null }
        if (msg.media!!.received != received) store.saveMessage(msg.copy(media = msg.media.copy(received = received)))
    }

    private suspend fun finishTransfer(transferId: String) {
        val t = transfers[transferId] ?: return
        if (t.parts.any { it == null }) return
        val msg = store.incompleteMedia().firstOrNull { it.media?.transferId == transferId } ?: return // message not here yet
        val media = msg.media!!
        val blob = Bytes.concat(*t.parts.map { it!! }.toTypedArray())
        val plain = try {
            MediaCrypto.decrypt(media.key, blob, media.digest)
        } catch (e: CryptoException) {
            transfers.remove(transferId)
            return
        }
        transfers.remove(transferId)
        val path = files.saveFile(msg.id, media.mime, plain)
        store.saveMessage(msg.copy(media = media.copy(received = media.chunks, path = path)))
    }

    private suspend fun onAck(ids: List<String>) {
        ids.forEach { key ->
            val entry = store.deleteOutbox(key) ?: return@forEach
            val id = entry.messageId ?: return@forEach
            val msg = store.message(id) ?: return@forEach
            if (msg.status == MsgStatus.SENDING || msg.status == MsgStatus.SENT) store.saveMessage(msg.copy(status = MsgStatus.DELIVERED))
        }
    }

    private suspend fun handleContent(sender: String, conv: String, content: Content, sentAt: Long) {
        val now = clock()
        val at = minOf(sentAt, now)
        when (content) {
            is Content.Text -> {
                if (store.message(content.id) != null || content.text.isBlank() || content.text.length > MAX_TEXT) return
                val msg = ChatMessage(
                    id = content.id, convId = conv, sender = sender, mine = false, kind = MsgKind.TEXT,
                    text = content.text, at = at, status = MsgStatus.RECEIVED, replyTo = content.replyTo,
                    expiresAt = if (content.expiresIn > 0) now + content.expiresIn * 1000 else 0,
                )
                received(conv, sender, msg)
            }
            is Content.Media -> {
                if (store.message(content.id) != null || content.chunks !in 1..MAX_CHUNKS) return
                val msg = ChatMessage(
                    id = content.id, convId = conv, sender = sender, mine = false,
                    kind = msgKind(content.kind),
                    text = content.caption.take(MAX_TEXT), at = at, status = MsgStatus.RECEIVED, replyTo = content.replyTo,
                    expiresAt = if (content.expiresIn > 0) now + content.expiresIn * 1000 else 0,
                    media = MediaInfo(
                        content.mime, content.size, content.durationMs, content.width, content.height, content.thumbnail,
                        content.key, content.digest, content.transferId, content.chunks, name = content.name.take(200),
                        received = transfers[content.transferId]?.parts?.count { it != null } ?: 0,
                    ),
                )
                received(conv, sender, msg)
                finishTransfer(content.transferId)
            }
            is Content.Reaction -> {
                val target = store.message(content.target)?.takeIf { it.convId == conv && !it.deleted } ?: return
                if (content.emoji.isBlank() || content.emoji.length > 16) return
                store.saveMessage(target.copy(reactions = toggle(target.reactions, content.emoji, sender, !content.remove)))
            }
            is Content.Delete -> {
                val target = store.message(content.target)?.takeIf { it.convId == conv && it.sender == sender } ?: return
                tombstone(target)
            }
            is Content.Read -> content.ids.forEach { id ->
                val m = store.message(id)?.takeIf { it.mine && it.convId == conv } ?: return@forEach
                if (m.status != MsgStatus.READ) store.saveMessage(m.copy(status = MsgStatus.READ))
            }
            is Content.Accept -> store.person(sender)?.let {
                if (it.state != PersonState.BLOCKED) store.savePerson(it.copy(state = PersonState.ACCEPTED))
            }
            is Content.GroupState -> onGroupState(sender, content)
            is Content.GroupLeave -> onGroupLeave(sender, content.groupId.hex())
            is Content.SenderKey -> onSenderKey(sender, content)
            is Content.Missing -> resendChunks(sender, content)
            is Content.Timer -> {
                if (content.seconds !in TIMER_CHOICES) return
                val c = ensureConversation(conv)
                store.saveConversation(c.copy(timerSeconds = content.seconds))
                system(conv, "${nameOf(sender)} ${timerText(content.seconds)}")
            }
        }
    }

    private suspend fun received(conv: String, sender: String, msg: ChatMessage) {
        val c = ensureConversation(conv)
        var request = c.request
        if (ConvIds.kind(conv) == ConvKind.DIRECT) {
            val person = store.person(sender)
            if (person != null && person.state == PersonState.STRANGER) {
                store.savePerson(person.copy(state = PersonState.REQUEST))
                request = true
            }
        }
        store.saveMessage(msg)
        val updated = c.copy(lastActivity = msg.at, unread = c.unread + 1, request = request, preview = preview(msg))
        store.saveConversation(updated)
        _events.tryEmit(MessengerEvent.NewMessage(updated, msg, store.person(sender)))
    }

    // Outgoing

    suspend fun sendText(conv: String, text: String, replyTo: String? = null): ChatMessage = locked {
        val c = ensureConversation(conv)
        val id = newId()
        val now = clock()
        val msg = ChatMessage(
            id, conv, me, true, MsgKind.TEXT, text.trim(), now, MsgStatus.SENDING, replyTo,
            expiresAt = if (c.timerSeconds > 0) now + c.timerSeconds * 1000 else 0,
        )
        store.saveMessage(msg)
        store.saveConversation(c.copy(lastActivity = now, preview = preview(msg)))
        deliver(conv, Content.Text(id, msg.text, replyTo, c.timerSeconds), id)
        store.message(id)!!
    }

    suspend fun sendMedia(
        conv: String,
        kind: MediaKind,
        mime: String,
        data: ByteArray,
        thumbnail: ByteArray,
        width: Int = 0,
        height: Int = 0,
        durationMs: Long = 0,
        caption: String = "",
        replyTo: String? = null,
        name: String = "",
    ): ChatMessage = locked {
        val c = ensureConversation(conv)
        val id = newId()
        val now = clock()
        val sealed = MediaCrypto.encrypt(data)
        val transferId = newId()
        val chunks = sealed.blob.toList().chunked(CHUNK_SIZE).map { it.toByteArray() }
        val limit = if (ConvIds.kind(conv) == ConvKind.PUBLIC) MAX_PUBLIC_CHUNKS else MAX_CHUNKS
        require(chunks.size <= limit) { "That's too big to send here (up to ${limit * CHUNK_SIZE / 1_000_000} MB)" }
        files.saveBlob(transferId, sealed.blob)
        val path = files.saveFile(id, mime, data)
        val media = MediaInfo(mime, data.size.toLong(), durationMs, width, height, thumbnail, sealed.key, sealed.digest, transferId, chunks.size, chunks.size, path, name)
        val msg = ChatMessage(
            id, conv, me, true, msgKind(kind), caption.trim(), now,
            MsgStatus.SENDING, replyTo, expiresAt = if (c.timerSeconds > 0) now + c.timerSeconds * 1000 else 0, media = media,
        )
        store.saveMessage(msg)
        store.saveConversation(c.copy(lastActivity = now, preview = preview(msg)))
        val content = Content.Media(
            id, kind, mime, media.size, durationMs, width, height, thumbnail, sealed.key, sealed.digest,
            transferId, chunks.size, caption.trim(), replyTo, c.timerSeconds, name,
        )
        deliver(conv, content, id)
        chunks.forEachIndexed { i, bytes -> sendChunk(conv, MediaChunk(transferId, i, chunks.size, bytes)) }
        store.message(id)!!
    }

    suspend fun react(messageId: String, emoji: String) = locked {
        val m = store.message(messageId)?.takeIf { !it.deleted } ?: return@locked
        val mineNow = m.reactions[emoji]?.contains(me) == true
        store.saveMessage(m.copy(reactions = toggle(m.reactions, emoji, me, !mineNow)))
        deliver(m.convId, Content.Reaction(messageId, emoji, remove = mineNow), null)
    }

    suspend fun deleteForEveryone(messageId: String) = locked {
        val m = store.message(messageId)?.takeIf { it.mine && !it.deleted } ?: return@locked
        tombstone(m)
        deliver(m.convId, Content.Delete(messageId), null)
    }

    suspend fun deleteForMe(messageId: String) = locked {
        val m = store.message(messageId) ?: return@locked
        m.media?.path?.let(files::delete)
        store.deleteMessage(messageId)
    }

    /** The conversation is on screen: clear unread and, for accepted DMs, tell them it was read. */
    suspend fun markRead(conv: String) = locked {
        val unread = store.unreadIncoming(conv)
        unread.forEach { store.saveMessage(it.copy(status = MsgStatus.SEEN)) }
        store.conversation(conv)?.let { if (it.unread != 0) store.saveConversation(it.copy(unread = 0)) }
        if (unread.isNotEmpty() && ConvIds.kind(conv) == ConvKind.DIRECT) {
            val peer = ConvIds.peerOf(conv)
            if (store.person(peer)?.state == PersonState.ACCEPTED) sendDirect(peer, Content.Read(unread.map { it.id }), null)
        }
    }

    suspend fun accept(peer: String) = locked {
        val p = store.person(peer) ?: return@locked
        store.savePerson(p.copy(state = PersonState.ACCEPTED))
        store.conversation(ConvIds.direct(peer))?.let { store.saveConversation(it.copy(request = false)) }
        sendDirect(peer, Content.Accept, null)
    }

    suspend fun block(peer: String) = locked {
        val p = store.person(peer) ?: return@locked
        store.savePerson(p.copy(state = PersonState.BLOCKED))
        store.conversation(ConvIds.direct(peer))?.let { store.saveConversation(it.copy(request = false)) }
    }

    suspend fun unblock(peer: String) = locked {
        val p = store.person(peer) ?: return@locked
        store.savePerson(p.copy(state = PersonState.ACCEPTED))
    }

    /** Opens (or creates) a direct conversation. */
    suspend fun openDirect(peer: String): Conversation = locked { ensureConversation(ConvIds.direct(peer)) }

    enum class VerifyResult { VERIFIED, ADDED, MISMATCH }

    /** A scanned contact card: matches a known person, adds a new verified contact, or doesn't match. */
    suspend fun verify(scanned: PublicIdentity, name: String): VerifyResult = locked {
        val peer = scanned.peerId.value
        val existing = store.person(peer)
        if (existing == null) {
            store.savePerson(Person(peer, scanned.toBytes(), name.ifBlank { "Unknown" }, state = PersonState.ACCEPTED, verified = true))
            return@locked VerifyResult.ADDED
        }
        if (!existing.identity.contentEquals(scanned.toBytes())) return@locked VerifyResult.MISMATCH
        store.savePerson(existing.copy(verified = true, state = if (existing.state == PersonState.BLOCKED) existing.state else PersonState.ACCEPTED))
        VerifyResult.VERIFIED
    }

    /** After comparing safety numbers by reading them out. */
    suspend fun setVerified(peer: String, verified: Boolean) = locked {
        store.person(peer)?.let { store.savePerson(it.copy(verified = verified)) }
    }

    suspend fun setTimer(conv: String, seconds: Long) = locked {
        require(seconds in TIMER_CHOICES)
        val c = ensureConversation(conv)
        store.saveConversation(c.copy(timerSeconds = seconds))
        system(conv, "You ${timerText(seconds)}")
        if (ConvIds.kind(conv) != ConvKind.PUBLIC) deliver(conv, Content.Timer(seconds), null)
    }

    // Groups

    suspend fun createGroup(name: String, members: List<String>): String = locked {
        val gid = Random.bytes(16).hex()
        val g = Group(gid, name.trim().take(40), me, (listOf(me) + members).distinct(), version = 1)
        store.saveGroup(g)
        ensureConversation(ConvIds.group(gid))
        system(ConvIds.group(gid), "You created ${g.name}")
        sendGroupState(g, g.members)
        gid
    }

    suspend fun updateGroup(gid: String, name: String? = null, add: List<String> = emptyList(), remove: List<String> = emptyList()) = locked {
        val g = store.group(gid)?.takeIf { it.active && it.admin == me } ?: return@locked
        val members = (g.members + add).distinct() - remove.toSet()
        val next = g.copy(name = name?.trim()?.take(40) ?: g.name, members = members.distinct(), version = g.version + 1)
        store.saveGroup(next)
        store.conversation(ConvIds.group(gid))?.let { store.saveConversation(it.copy(title = next.name)) }
        if (remove.isNotEmpty()) rotateMyKey(gid)
        // Removed people get the new state too, so they know they're out.
        sendGroupState(next, (next.members + remove).distinct())
        add.forEach { system(ConvIds.group(gid), "You added ${nameOf(it)}") }
        remove.forEach { system(ConvIds.group(gid), "You removed ${nameOf(it)}") }
        if (name != null && name != g.name) system(ConvIds.group(gid), "You renamed the group to ${next.name}")
    }

    suspend fun leaveGroup(gid: String) = locked {
        val g = store.group(gid)?.takeIf { it.active } ?: return@locked
        g.members.filter { it != me }.forEach { sendDirect(it, Content.GroupLeave(Bytes.unhex(gid)), null) }
        store.saveGroup(g.copy(active = false, members = g.members - me))
        store.saveOwnSenderKey(gid, null)
        system(ConvIds.group(gid), "You left")
    }

    private suspend fun sendGroupState(g: Group, to: List<String>) {
        val infos = g.members.mapNotNull { id ->
            if (id == me) GroupMemberInfo(identity.publicIdentity().toBytes(), store.myName())
            else store.person(id)?.let { GroupMemberInfo(it.identity, it.name) }
        }
        val state = Content.GroupState(Bytes.unhex(g.id), g.name, g.admin, infos, g.version)
        to.filter { it != me }.forEach { sendDirect(it, state, null) }
    }

    private suspend fun onGroupState(sender: String, gs: Content.GroupState) {
        val gid = gs.groupId.hex()
        val members = gs.members.mapNotNull { m -> PublicIdentity.fromBytes(m.identity)?.takeIf { it.isValid() }?.let { it to m.name } }
        val ids = members.map { it.first.peerId.value }
        val existing = store.group(gid)
        if (existing == null) {
            if (sender != gs.admin || me !in ids) return
        } else if (sender != existing.admin || gs.version <= existing.version) {
            return
        }
        // Learn the other members so their messages and keys can be checked.
        members.forEach { (id, name) ->
            val peer = id.peerId.value
            if (peer != me && store.person(peer) == null) store.savePerson(Person(peer, id.toBytes(), name.take(32)))
        }
        val next = Group(gid, gs.name.take(40), gs.admin, ids, gs.version, active = me in ids)
        store.saveGroup(next)
        val conv = ensureConversation(ConvIds.group(gid))
        store.saveConversation(conv.copy(title = next.name))
        if (existing == null) {
            system(ConvIds.group(gid), "${nameOf(sender)} added you to ${next.name}")
        } else {
            val removed = existing.members - ids.toSet()
            (ids - existing.members.toSet()).forEach { if (it != me) system(ConvIds.group(gid), "${nameOf(sender)} added ${nameOf(it)}") }
            removed.forEach { if (it != me) system(ConvIds.group(gid), "${nameOf(sender)} removed ${nameOf(it)}") }
            if (removed.isNotEmpty()) rotateMyKey(gid)
            if (!next.active) {
                store.saveOwnSenderKey(gid, null)
                system(ConvIds.group(gid), "${nameOf(sender)} removed you")
            }
        }
    }

    private suspend fun onGroupLeave(sender: String, gid: String) {
        val g = store.group(gid)?.takeIf { sender in it.members } ?: return
        val remaining = g.members - sender
        val admin = if (g.admin == sender) remaining.minOrNull() ?: g.admin else g.admin
        val next = g.copy(members = remaining, admin = admin, version = g.version + 1)
        store.saveGroup(next)
        store.saveSenderKey(gid, sender, null)
        rotateMyKey(gid)
        system(ConvIds.group(gid), "${nameOf(sender)} left")
    }

    private suspend fun onSenderKey(sender: String, content: Content.SenderKey) {
        val d = runCatching { SenderKeyDistribution.fromBytes(content.distribution) }.getOrNull() ?: return
        val gid = d.groupId.hex()
        val g = store.group(gid) ?: return
        if (sender !in g.members) return
        store.saveSenderKey(gid, sender, SenderKeys.fromDistribution(d))
        // Anything that was waiting for this key can be read now.
        val ready = waitingGroup.filter { it.second.sender == sender && it.second.group.hex() == gid }
        waitingGroup.removeAll(ready.toSet())
        ready.forEach { onGroup(it.second) }
    }

    private suspend fun rotateMyKey(gid: String) {
        store.saveOwnSenderKey(gid, null)
        store.saveKeySharedWith(gid, emptySet())
    }

    // Sending helpers

    private suspend fun deliver(conv: String, content: Content, messageId: String?) {
        when (ConvIds.kind(conv)) {
            ConvKind.PUBLIC -> sendPublic(content, messageId)
            ConvKind.DIRECT -> {
                val peer = ConvIds.peerOf(conv)
                val person = store.person(peer)
                if (person != null && person.state != PersonState.ACCEPTED && person.state != PersonState.BLOCKED) {
                    // Replying is accepting.
                    val wasRequest = person.state == PersonState.REQUEST
                    store.savePerson(person.copy(state = PersonState.ACCEPTED))
                    store.conversation(conv)?.let { store.saveConversation(it.copy(request = false)) }
                    if (wasRequest) sendDirect(peer, Content.Accept, null)
                }
                sendDirect(peer, content, messageId)
            }
            ConvKind.GROUP -> sendGroup(ConvIds.groupOf(conv), content, messageId)
        }
    }

    private suspend fun sendPublic(content: Content, messageId: String?) {
        val now = clock()
        val shell = Packet(type = PacketType.PUBLIC, sender = me, timestamp = now, payload = ByteArray(0))
        val post = PublicPost(identity.publicIdentity().toBytes(), store.myName(), content.toBytes()).signed(identity, shell.id, now)
        val packet = shell.copy(payload = post.toBytes())
        messageId?.let { unsent[packet.key] = it }
        emit(router.send(packet))
    }

    /** Encrypts for one person, or queues it until they've been heard (we need their prekey). */
    private suspend fun sendDirect(peer: String, content: Content, messageId: String?): Boolean {
        val person = store.person(peer) ?: return false
        if (person.state == PersonState.BLOCKED) return false
        var sessions = store.sessions(peer) ?: SessionSet()
        if (sessions.current == null) {
            val prekey = person.publicPrekey
            if (prekey == null) {
                store.addPending(peer, content.toBytes(), messageId)
                return false
            }
            sessions = try {
                sessions.withNew(Session.initiate(identity, person.publicIdentity, prekey))
            } catch (e: CryptoException) {
                return false
            }
        }
        val (next, env) = sessions.encrypt(content.toBytes())
        store.saveSessions(peer, next)
        val now = clock()
        val packet = Packet(type = PacketType.DIRECT, sender = me, recipient = peer, timestamp = now, payload = env.toBytes())
        store.saveOutbox(OutboxEntry(packet.key, packet.toBytes(), messageId, peer, now, now))
        messageId?.let { unsent[packet.key] = it }
        emit(router.send(packet))
        return true
    }

    private suspend fun sendGroup(gid: String, content: Content, messageId: String?) {
        val g = store.group(gid)?.takeIf { it.active } ?: return
        val gidBytes = Bytes.unhex(gid)
        var own = store.ownSenderKey(gid)
        if (own == null) {
            own = SenderKeys.create()
            store.saveOwnSenderKey(gid, own)
            store.saveKeySharedWith(gid, emptySet())
        }
        // Everyone who doesn't have my current key gets it first, over our pairwise session.
        val shared = store.keySharedWith(gid).toMutableSet()
        g.members.filter { it != me && it !in shared }.forEach { m ->
            sendDirect(m, Content.SenderKey(SenderKeys.distribution(own, gidBytes).toBytes()), null)
            shared += m
        }
        store.saveKeySharedWith(gid, shared)
        val (next, gm) = SenderKeys.encrypt(own, gidBytes, content.toBytes())
        store.saveOwnSenderKey(gid, next)
        val packet = Packet(type = PacketType.GROUP, sender = me, group = gidBytes, timestamp = clock(), payload = gm.toBytes())
        messageId?.let { unsent[packet.key] = it }
        emit(router.send(packet))
    }

    private suspend fun sendChunk(conv: String, chunk: MediaChunk, to: String? = null) {
        val packet = when {
            to != null -> Packet(type = PacketType.MEDIA_CHUNK, sender = me, recipient = to, timestamp = clock(), payload = chunk.toBytes())
            ConvIds.kind(conv) == ConvKind.DIRECT ->
                Packet(type = PacketType.MEDIA_CHUNK, sender = me, recipient = ConvIds.peerOf(conv), timestamp = clock(), payload = chunk.toBytes())
            ConvIds.kind(conv) == ConvKind.PUBLIC -> Packet(type = PacketType.MEDIA_CHUNK, sender = me, timestamp = clock(), payload = chunk.toBytes())
            else -> Packet(type = PacketType.MEDIA_CHUNK, sender = me, group = Bytes.unhex(ConvIds.groupOf(conv)), timestamp = clock(), payload = chunk.toBytes())
        }
        emit(router.send(packet))
    }

    private suspend fun resendChunks(sender: String, missing: Content.Missing) {
        val blob = files.blob(missing.transferId) ?: return
        val chunks = blob.toList().chunked(CHUNK_SIZE).map { it.toByteArray() }
        missing.indexes.filter { it in chunks.indices }.take(MAX_CHUNKS).forEach { i ->
            sendChunk("", MediaChunk(missing.transferId, i, chunks.size, chunks[i]), to = sender)
        }
    }

    private suspend fun flushPending(peer: String) {
        store.pending(peer).forEach { p ->
            val content = Content.fromBytes(p.content) ?: return@forEach
            if (sendDirect(peer, content, p.messageId)) store.deletePending(p.id)
        }
    }

    private suspend fun sendAck(p: Packet) {
        val ack = Ack(listOf(p.id)).signed(identity)
        emit(router.send(Packet(type = PacketType.ACK, sender = me, recipient = p.sender, timestamp = clock(), payload = ack.toBytes())))
    }

    private suspend fun emit(sends: List<Send>) {
        sends.forEach { out(it.link, it.packet.toBytes()) }
        // A message counts as sent once it has left this phone on at least one link.
        sends.map { it.packet.key }.distinct().forEach { key ->
            val id = unsent.remove(key) ?: return@forEach
            val m = store.message(id) ?: return@forEach
            if (m.status == MsgStatus.SENDING) store.saveMessage(m.copy(status = MsgStatus.SENT))
        }
    }

    // Upkeep

    /** Call every ~30 s: retries, expiry, prekey rotation, asking for missing media. */
    suspend fun tick() = locked {
        val now = clock()
        router.tick()
        store.outbox().forEach { e ->
            when {
                now - e.createdAt > OUTBOX_FOR_MS -> {
                    store.deleteOutbox(e.packetKey)
                    e.messageId?.let { id -> store.message(id)?.let { if (it.status == MsgStatus.SENDING || it.status == MsgStatus.SENT) store.saveMessage(it.copy(status = MsgStatus.FAILED)) } }
                }
                now - e.lastSentAt > RETRY_MS -> {
                    val p = Packet.fromBytes(e.packet) ?: return@forEach
                    store.saveOutbox(e.copy(lastSentAt = now))
                    emit(router.resend(p))
                }
            }
        }
        store.expiredMessages(now).forEach { m ->
            m.media?.path?.let(files::delete)
            store.deleteMessage(m.id)
        }
        waitingGroup.removeAll { now - it.first > OUTBOX_FOR_MS }
        transfers.forEach { (id, t) ->
            if (now - t.lastChunkAt > MISSING_AFTER_MS && now - t.lastAskedAt > MISSING_AFTER_MS) {
                val msg = store.incompleteMedia().firstOrNull { it.media?.transferId == id } ?: return@forEach
                if (ConvIds.kind(msg.convId) == ConvKind.PUBLIC) return@forEach
                t.lastAskedAt = now
                sendDirect(msg.sender, Content.Missing(id, t.parts.indices.filter { t.parts[it] == null }), null)
            }
        }
        rotatePrekeys()
    }

    private suspend fun rotatePrekeys() {
        val now = clock()
        val keys = store.prekeys()
        val newest = keys.maxByOrNull { it.id }
        if (newest != null && now - newest.createdAt < PREKEY_ROTATE_MS) return
        val fresh = SignedPrekey.generate(identity, (newest?.id ?: 0) + 1, now)
        // Old prekeys stay a while for messages already on their way.
        val keep = keys.filter { now - it.createdAt < PREKEY_KEEP_MS }
        store.savePrekeys(keep + fresh)
    }

    // Small helpers

    private suspend fun ensureConversation(conv: String): Conversation {
        store.conversation(conv)?.let { return it }
        val kind = ConvIds.kind(conv)
        val title = when (kind) {
            ConvKind.PUBLIC -> "Public"
            ConvKind.DIRECT -> store.person(ConvIds.peerOf(conv))?.name ?: "Unknown"
            ConvKind.GROUP -> store.group(ConvIds.groupOf(conv))?.name ?: "Group"
        }
        val c = Conversation(conv, kind, title, lastActivity = clock())
        store.saveConversation(c)
        return c
    }

    private suspend fun system(conv: String, text: String) {
        val now = clock()
        store.saveMessage(ChatMessage(newId(), conv, me, false, MsgKind.SYSTEM, text, now, MsgStatus.SEEN))
        store.conversation(conv)?.let { store.saveConversation(it.copy(lastActivity = now, preview = text)) }
    }

    private suspend fun tombstone(m: ChatMessage) {
        m.media?.path?.let(files::delete)
        store.saveMessage(m.copy(deleted = true, text = "", media = null, reactions = emptyMap()))
    }

    private suspend fun nameOf(peer: String): String = if (peer == me) "You" else store.person(peer)?.name ?: "Someone"

    private fun toggle(reactions: Map<String, List<String>>, emoji: String, who: String, on: Boolean): Map<String, List<String>> {
        val now = reactions[emoji].orEmpty().filter { it != who } + if (on) listOf(who) else emptyList()
        return (reactions + (emoji to now)).filterValues { it.isNotEmpty() }
    }

    private fun preview(m: ChatMessage): String = when (m.kind) {
        MsgKind.PHOTO -> if (m.text.isNotBlank()) "📷 ${m.text}" else "📷 Photo"
        MsgKind.VOICE -> "🎤 Voice note"
        MsgKind.FILE -> "📎 " + (m.media?.name?.ifBlank { null } ?: "File")
        else -> m.text
    }.take(120)

    private fun timerText(seconds: Long) = when (seconds) {
        0L -> "turned off disappearing messages"
        3_600L -> "set messages to disappear after 1 hour"
        86_400L -> "set messages to disappear after 1 day"
        else -> "set messages to disappear after 1 week"
    }

    private fun newId() = Random.bytes(12).hex()

    companion object {
        const val MAX_TEXT = 4_000
        const val CHUNK_SIZE = 8 * 1024
        const val MAX_CHUNKS = 2_000
        /** The public chat floods every phone in range, so it carries less: about 4 MB. */
        const val MAX_PUBLIC_CHUNKS = 500
        const val MAX_WAITING_GROUP = 500
        /** A contact quiet for this long whose name turns up with new keys has probably reinstalled. */
        const val REINSTALL_QUIET_MS = 2 * 60_000L
        const val RETRY_MS = 30_000L
        const val OUTBOX_FOR_MS = 24L * 60 * 60 * 1000
        const val MISSING_AFTER_MS = 60_000L
        const val PREKEY_ROTATE_MS = 7L * 24 * 60 * 60 * 1000
        const val PREKEY_KEEP_MS = 14L * 24 * 60 * 60 * 1000
        val TIMER_CHOICES = setOf(0L, 3_600L, 86_400L, 604_800L)
    }
}

private fun msgKind(kind: MediaKind) = when (kind) {
    MediaKind.PHOTO -> MsgKind.PHOTO
    MediaKind.VOICE -> MsgKind.VOICE
    MediaKind.FILE -> MsgKind.FILE
}
