package com.prempoddar.librechat.engine

import com.prempoddar.librechat.crypto.Identity
import com.prempoddar.librechat.crypto.SenderKeyState
import com.prempoddar.librechat.crypto.SessionSet
import com.prempoddar.librechat.crypto.SignedPrekey

/** Everything the messenger keeps. The app backs it with an encrypted database; tests with memory. */
interface EngineStore {
    suspend fun identity(): Identity?
    suspend fun saveIdentity(identity: Identity)
    suspend fun prekeys(): List<SignedPrekey>
    suspend fun savePrekeys(prekeys: List<SignedPrekey>)
    suspend fun myName(): String

    suspend fun person(id: String): Person?
    suspend fun savePerson(person: Person)

    suspend fun sessions(peer: String): SessionSet?
    suspend fun saveSessions(peer: String, sessions: SessionSet)

    suspend fun ownSenderKey(group: String): SenderKeyState?
    suspend fun saveOwnSenderKey(group: String, state: SenderKeyState?)
    suspend fun senderKey(group: String, peer: String): SenderKeyState?
    suspend fun saveSenderKey(group: String, peer: String, state: SenderKeyState?)
    suspend fun keySharedWith(group: String): Set<String>
    suspend fun saveKeySharedWith(group: String, peers: Set<String>)

    suspend fun group(id: String): Group?
    suspend fun saveGroup(group: Group)

    suspend fun conversation(id: String): Conversation?
    suspend fun saveConversation(conversation: Conversation)

    suspend fun message(id: String): ChatMessage?
    suspend fun saveMessage(message: ChatMessage)
    suspend fun deleteMessage(id: String)
    suspend fun unreadIncoming(conv: String): List<ChatMessage>
    suspend fun expiredMessages(now: Long): List<ChatMessage>
    suspend fun incompleteMedia(): List<ChatMessage>

    suspend fun outbox(): List<OutboxEntry>
    suspend fun saveOutbox(entry: OutboxEntry)
    suspend fun deleteOutbox(packetKey: String): OutboxEntry?

    suspend fun pending(peer: String): List<PendingContent>
    suspend fun addPending(peer: String, content: ByteArray, messageId: String?)
    suspend fun deletePending(id: Long)
}

/** Where media files live on the phone. */
interface MediaFiles {
    /** Keeps the encrypted blob so missing pieces can be re-sent; returns nothing. */
    fun saveBlob(transferId: String, blob: ByteArray)
    fun blob(transferId: String): ByteArray?
    /** Stores a decrypted file for display; returns its path. */
    fun saveFile(messageId: String, mime: String, bytes: ByteArray): String
    fun delete(path: String)
}
