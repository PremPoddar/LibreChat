package com.prempoddar.librechat.data

import com.prempoddar.librechat.crypto.Identity
import com.prempoddar.librechat.crypto.SenderKeyState
import com.prempoddar.librechat.crypto.SessionSet
import com.prempoddar.librechat.crypto.SignedPrekey
import com.prempoddar.librechat.engine.ChatMessage
import com.prempoddar.librechat.engine.Conversation
import com.prempoddar.librechat.engine.EngineStore
import com.prempoddar.librechat.engine.Group
import com.prempoddar.librechat.engine.MsgStatus
import com.prempoddar.librechat.engine.OutboxEntry
import com.prempoddar.librechat.engine.PendingContent
import com.prempoddar.librechat.engine.Person
import java.nio.ByteBuffer

/** The messenger's storage, in the encrypted database. */
class RoomStore(private val dao: ChatDao, private val name: suspend () -> String) : EngineStore {

    override suspend fun identity() = dao.kv("identity")?.let { Identity.fromBytes(it.value) }
    override suspend fun saveIdentity(identity: Identity) = dao.putKv(KeyValueEntity("identity", identity.toBytes()))

    override suspend fun prekeys(): List<SignedPrekey> {
        val blob = dao.kv("prekeys")?.value ?: return emptyList()
        val b = ByteBuffer.wrap(blob)
        return List(b.int) {
            val bytes = ByteArray(b.int).also { b.get(it) }
            SignedPrekey.fromBytes(bytes)
        }
    }

    override suspend fun savePrekeys(prekeys: List<SignedPrekey>) {
        val parts = prekeys.map { it.toBytes() }
        val b = ByteBuffer.allocate(4 + parts.sumOf { 4 + it.size }).putInt(parts.size)
        parts.forEach { b.putInt(it.size).put(it) }
        dao.putKv(KeyValueEntity("prekeys", b.array()))
    }

    override suspend fun myName() = name()

    override suspend fun person(id: String) = dao.person(id)?.toModel()
    override suspend fun savePerson(person: Person) = dao.putPerson(person.toEntity())
    override suspend fun people() = dao.people().map { it.toModel() }
    override suspend fun successor(peer: String) = dao.kv(successorKey(peer))?.value?.toString(Charsets.UTF_8)
    override suspend fun saveSuccessor(peer: String, successor: String?) =
        if (successor == null) dao.deleteKv(successorKey(peer)) else dao.putKv(KeyValueEntity(successorKey(peer), successor.toByteArray()))

    override suspend fun sessions(peer: String) = dao.session(peer)?.let { SessionSet.fromBytes(it.blob) }
    override suspend fun saveSessions(peer: String, sessions: SessionSet) = dao.putSession(SessionEntity(peer, sessions.toBytes()))

    override suspend fun ownSenderKey(group: String) = senderKey(group, "")
    override suspend fun saveOwnSenderKey(group: String, state: SenderKeyState?) = saveSenderKey(group, "", state)
    override suspend fun senderKey(group: String, peer: String) = dao.senderKey(group, peer)?.let { SenderKeyState.fromBytes(it.blob) }
    override suspend fun saveSenderKey(group: String, peer: String, state: SenderKeyState?) {
        if (state == null) dao.deleteSenderKey(group, peer) else dao.putSenderKey(SenderKeyEntity(group, peer, state.toBytes()))
    }
    override suspend fun keySharedWith(group: String) = dao.keyShare(group)?.peers?.split(',')?.filter { it.isNotEmpty() }?.toSet().orEmpty()
    override suspend fun saveKeySharedWith(group: String, peers: Set<String>) = dao.putKeyShare(KeyShareEntity(group, peers.joinToString(",")))

    override suspend fun group(id: String) = dao.group(id)?.toModel()
    override suspend fun saveGroup(group: Group) = dao.putGroup(group.toEntity())

    override suspend fun conversation(id: String) = dao.conversation(id)?.toModel()
    override suspend fun saveConversation(conversation: Conversation) = dao.putConversation(conversation.toEntity())
    override suspend fun deleteConversation(id: String) = dao.deleteConversation(id)
    override suspend fun moveMessages(from: String, to: String) = dao.moveMessages(from, to)

    override suspend fun message(id: String) = dao.message(id)?.toModel()
    override suspend fun saveMessage(message: ChatMessage) = dao.putMessage(message.toEntity())
    override suspend fun deleteMessage(id: String) = dao.deleteMessage(id)
    override suspend fun unreadIncoming(conv: String) = dao.withStatus(conv, MsgStatus.RECEIVED).map { it.toModel() }
    override suspend fun expiredMessages(now: Long) = dao.expired(now).map { it.toModel() }
    override suspend fun incompleteMedia() = dao.incompleteMedia().map { it.toModel() }

    override suspend fun outbox() = dao.outbox().map { OutboxEntry(it.packetKey, it.packet, it.messageId, it.peer, it.createdAt, it.lastSentAt) }
    override suspend fun saveOutbox(entry: OutboxEntry) =
        dao.putOutbox(OutboxEntity(entry.packetKey, entry.packet, entry.messageId, entry.peer, entry.createdAt, entry.lastSentAt))
    override suspend fun deleteOutbox(packetKey: String): OutboxEntry? {
        val e = dao.outboxEntry(packetKey) ?: return null
        dao.deleteOutbox(packetKey)
        return OutboxEntry(e.packetKey, e.packet, e.messageId, e.peer, e.createdAt, e.lastSentAt)
    }

    override suspend fun pending(peer: String) = dao.pending(peer).map { PendingContent(it.id, it.peer, it.content, it.messageId) }
    override suspend fun addPending(peer: String, content: ByteArray, messageId: String?) = dao.putPending(PendingEntity(peer = peer, content = content, messageId = messageId))
    override suspend fun deletePending(id: Long) = dao.deletePending(id)

    companion object {
        fun successorKey(peer: String) = "successor:$peer"
    }
}
