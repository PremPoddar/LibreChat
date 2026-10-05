package com.prempoddar.librechat.ui

import com.prempoddar.librechat.AppGraph
import com.prempoddar.librechat.data.toModel
import com.prempoddar.librechat.engine.ChatMessage
import com.prempoddar.librechat.engine.Conversation
import com.prempoddar.librechat.engine.Group
import com.prempoddar.librechat.engine.Person
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** What the screens read, straight from the database as live flows. */
object Data {
    fun conversations(): Flow<List<Conversation>> = AppGraph.dao.observeConversations().map { l -> l.map { it.toModel() } }
    fun conversation(id: String): Flow<Conversation?> = AppGraph.dao.observeConversation(id).map { it?.toModel() }
    fun messages(conv: String): Flow<List<ChatMessage>> = AppGraph.dao.observeMessages(conv).map { l -> l.map { it.toModel() } }
    fun people(): Flow<List<Person>> = AppGraph.dao.observePeople().map { l -> l.map { it.toModel() } }
    fun person(id: String): Flow<Person?> = AppGraph.dao.observePerson(id).map { it?.toModel() }
    fun group(id: String): Flow<Group?> = AppGraph.dao.observeGroup(id).map { it?.toModel() }
    fun search(q: String): Flow<List<ChatMessage>> =
        AppGraph.dao.search(q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")).map { l -> l.map { it.toModel() } }
}

fun ago(at: Long, now: Long = System.currentTimeMillis()): String {
    val s = (now - at) / 1000
    return when {
        s < 45 -> "now"
        s < 3600 -> "${s / 60}m"
        s < 86_400 -> "${s / 3600}h"
        else -> "${s / 86_400}d"
    }
}

fun clock(at: Long): String = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(at))

/** "1a2b 3c4d 5e6f 7a8b" — easier to read out than 16 characters in a row. */
fun groupedId(id: String) = id.chunked(4).joinToString(" ")

object ConvTimers {
    val choices = listOf(0L, 3_600L, 86_400L, 604_800L)
    fun label(seconds: Long) = when (seconds) {
        0L -> "Off"
        3_600L -> "1 hour"
        86_400L -> "1 day"
        else -> "1 week"
    }
}
