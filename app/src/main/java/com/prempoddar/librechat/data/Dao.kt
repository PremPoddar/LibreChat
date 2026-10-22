package com.prempoddar.librechat.data

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.prempoddar.librechat.engine.MsgStatus
import kotlinx.coroutines.flow.Flow

@Dao
interface ChatDao {
    @Query("SELECT * FROM kv WHERE `key` = :key") suspend fun kv(key: String): KeyValueEntity?
    @Upsert suspend fun putKv(e: KeyValueEntity)
    @Query("SELECT * FROM kv WHERE `key` = :key") fun observeKv(key: String): Flow<KeyValueEntity?>
    @Query("DELETE FROM kv WHERE `key` = :key") suspend fun deleteKv(key: String)

    @Query("SELECT * FROM people WHERE peerId = :id") suspend fun person(id: String): PersonEntity?
    @Query("SELECT * FROM people WHERE peerId = :id") fun observePerson(id: String): Flow<PersonEntity?>
    @Query("SELECT * FROM people ORDER BY lastSeen DESC") fun observePeople(): Flow<List<PersonEntity>>
    @Upsert suspend fun putPerson(e: PersonEntity)
    @Query("SELECT * FROM people") suspend fun people(): List<PersonEntity>

    @Query("SELECT * FROM sessions WHERE peer = :peer") suspend fun session(peer: String): SessionEntity?
    @Upsert suspend fun putSession(e: SessionEntity)

    @Query("SELECT * FROM sender_keys WHERE groupId = :group AND peer = :peer") suspend fun senderKey(group: String, peer: String): SenderKeyEntity?
    @Upsert suspend fun putSenderKey(e: SenderKeyEntity)
    @Query("DELETE FROM sender_keys WHERE groupId = :group AND peer = :peer") suspend fun deleteSenderKey(group: String, peer: String)
    @Query("SELECT * FROM key_shares WHERE groupId = :group") suspend fun keyShare(group: String): KeyShareEntity?
    @Upsert suspend fun putKeyShare(e: KeyShareEntity)

    @Query("SELECT * FROM chat_groups WHERE id = :id") suspend fun group(id: String): GroupEntity?
    @Query("SELECT * FROM chat_groups WHERE id = :id") fun observeGroup(id: String): Flow<GroupEntity?>
    @Upsert suspend fun putGroup(e: GroupEntity)

    @Query("SELECT * FROM conversations WHERE id = :id") suspend fun conversation(id: String): ConversationEntity?
    @Query("SELECT * FROM conversations WHERE id = :id") fun observeConversation(id: String): Flow<ConversationEntity?>
    @Query("SELECT * FROM conversations ORDER BY (id = 'public') DESC, pinned DESC, lastActivity DESC") fun observeConversations(): Flow<List<ConversationEntity>>
    @Upsert suspend fun putConversation(e: ConversationEntity)
    @Query("DELETE FROM conversations WHERE id = :id") suspend fun deleteConversation(id: String)

    @Query("SELECT * FROM messages WHERE id = :id") suspend fun message(id: String): MessageEntity?
    @Query("SELECT * FROM messages WHERE convId = :conv ORDER BY at ASC") fun observeMessages(conv: String): Flow<List<MessageEntity>>
    @Query("SELECT * FROM messages WHERE convId = :conv AND mine = 0 AND status = :status") suspend fun withStatus(conv: String, status: MsgStatus): List<MessageEntity>
    @Query("SELECT * FROM messages WHERE expiresAt > 0 AND expiresAt <= :now") suspend fun expired(now: Long): List<MessageEntity>
    @Query("SELECT * FROM messages WHERE mediaTransfer IS NOT NULL AND mediaPath IS NULL AND mine = 0 AND deleted = 0") suspend fun incompleteMedia(): List<MessageEntity>
    @Query("SELECT * FROM messages WHERE convId = :conv AND mediaPath IS NOT NULL") suspend fun mediaIn(conv: String): List<MessageEntity>
    @Query("SELECT * FROM messages WHERE deleted = 0 AND kind != 'SYSTEM' AND text LIKE '%' || :q || '%' ESCAPE '\\' ORDER BY at DESC LIMIT 100")
    fun search(q: String): Flow<List<MessageEntity>>
    @Upsert suspend fun putMessage(e: MessageEntity)
    @Query("DELETE FROM messages WHERE id = :id") suspend fun deleteMessage(id: String)
    @Query("DELETE FROM messages WHERE convId = :conv") suspend fun clearConversation(conv: String)
    @Query("UPDATE messages SET convId = :to WHERE convId = :from") suspend fun moveMessages(from: String, to: String)

    @Query("SELECT * FROM outbox") suspend fun outbox(): List<OutboxEntity>
    @Query("SELECT * FROM outbox WHERE packetKey = :key") suspend fun outboxEntry(key: String): OutboxEntity?
    @Upsert suspend fun putOutbox(e: OutboxEntity)
    @Query("DELETE FROM outbox WHERE packetKey = :key") suspend fun deleteOutbox(key: String)

    @Query("SELECT * FROM pending WHERE peer = :peer ORDER BY id") suspend fun pending(peer: String): List<PendingEntity>
    @Upsert suspend fun putPending(e: PendingEntity)
    @Query("DELETE FROM pending WHERE id = :id") suspend fun deletePending(id: Long)
}
