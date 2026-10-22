package com.prempoddar.librechat.engine

import com.prempoddar.librechat.crypto.Identity
import com.prempoddar.librechat.crypto.SenderKeyState
import com.prempoddar.librechat.crypto.SessionSet
import com.prempoddar.librechat.crypto.SignedPrekey

class InMemoryStore(private val name: String) : EngineStore {
    private var identity: Identity? = null
    private var prekeys: List<SignedPrekey> = emptyList()
    val people = LinkedHashMap<String, Person>()
    private val sessions = HashMap<String, SessionSet>()
    private val own = HashMap<String, SenderKeyState>()
    private val theirs = HashMap<Pair<String, String>, SenderKeyState>()
    private val shared = HashMap<String, Set<String>>()
    val groups = HashMap<String, Group>()
    val conversations = LinkedHashMap<String, Conversation>()
    val messages = LinkedHashMap<String, ChatMessage>()
    private val outbox = LinkedHashMap<String, OutboxEntry>()
    private val pending = ArrayList<PendingContent>()
    private var pendingId = 0L

    override suspend fun identity() = identity
    override suspend fun saveIdentity(identity: Identity) { this.identity = identity }
    override suspend fun prekeys() = prekeys
    override suspend fun savePrekeys(prekeys: List<SignedPrekey>) { this.prekeys = prekeys }
    override suspend fun myName() = name
    override suspend fun person(id: String) = people[id]
    override suspend fun savePerson(person: Person) { people[person.peerId] = person }
    override suspend fun people() = people.values.toList()
    private val successors = HashMap<String, String>()
    override suspend fun successor(peer: String) = successors[peer]
    override suspend fun saveSuccessor(peer: String, successor: String?) { if (successor == null) successors.remove(peer) else successors[peer] = successor }
    override suspend fun sessions(peer: String) = sessions[peer]
    override suspend fun saveSessions(peer: String, sessions: SessionSet) { this.sessions[peer] = sessions }
    override suspend fun ownSenderKey(group: String) = own[group]
    override suspend fun saveOwnSenderKey(group: String, state: SenderKeyState?) { if (state == null) own.remove(group) else own[group] = state }
    override suspend fun senderKey(group: String, peer: String) = theirs[group to peer]
    override suspend fun saveSenderKey(group: String, peer: String, state: SenderKeyState?) { if (state == null) theirs.remove(group to peer) else theirs[group to peer] = state }
    override suspend fun keySharedWith(group: String) = shared[group].orEmpty()
    override suspend fun saveKeySharedWith(group: String, peers: Set<String>) { shared[group] = peers }
    override suspend fun group(id: String) = groups[id]
    override suspend fun saveGroup(group: Group) { groups[group.id] = group }
    override suspend fun conversation(id: String) = conversations[id]
    override suspend fun saveConversation(conversation: Conversation) { conversations[conversation.id] = conversation }
    override suspend fun deleteConversation(id: String) { conversations.remove(id) }
    override suspend fun moveMessages(from: String, to: String) { messages.replaceAll { _, m -> if (m.convId == from) m.copy(convId = to) else m } }
    override suspend fun message(id: String) = messages[id]
    override suspend fun saveMessage(message: ChatMessage) { messages[message.id] = message }
    override suspend fun deleteMessage(id: String) { messages.remove(id) }
    override suspend fun unreadIncoming(conv: String) = messages.values.filter { it.convId == conv && !it.mine && it.status == MsgStatus.RECEIVED }
    override suspend fun expiredMessages(now: Long) = messages.values.filter { it.expiresAt in 1..now }
    override suspend fun incompleteMedia() = messages.values.filter { it.media != null && it.media!!.path == null && !it.mine }
    override suspend fun outbox() = outbox.values.toList()
    override suspend fun saveOutbox(entry: OutboxEntry) { outbox[entry.packetKey] = entry }
    override suspend fun deleteOutbox(packetKey: String) = outbox.remove(packetKey)
    override suspend fun pending(peer: String) = pending.filter { it.peer == peer }
    override suspend fun addPending(peer: String, content: ByteArray, messageId: String?) { pending += PendingContent(++pendingId, peer, content, messageId) }
    override suspend fun deletePending(id: Long) { pending.removeAll { it.id == id } }

    fun inConv(conv: String) = messages.values.filter { it.convId == conv && it.kind != MsgKind.SYSTEM }
}

class InMemoryFiles : MediaFiles {
    val blobs = HashMap<String, ByteArray>()
    val files = HashMap<String, ByteArray>()
    override fun saveBlob(transferId: String, blob: ByteArray) { blobs[transferId] = blob }
    override fun blob(transferId: String) = blobs[transferId]
    override fun saveFile(messageId: String, mime: String, bytes: ByteArray): String = "mem://$messageId".also { files[it] = bytes }
    override fun delete(path: String) { files.remove(path) }
}
