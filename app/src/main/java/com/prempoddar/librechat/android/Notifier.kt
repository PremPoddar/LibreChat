package com.prempoddar.librechat.android

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.prempoddar.librechat.MainActivity
import com.prempoddar.librechat.R
import com.prempoddar.librechat.engine.ConvKind
import com.prempoddar.librechat.engine.MessengerEvent
import com.prempoddar.librechat.engine.MsgKind

/** Message notifications. Requests never show their text: the sender isn't accepted yet. */
class Notifier(private val context: Context) {
    private val nm = context.getSystemService(NotificationManager::class.java)

    init {
        nm.createNotificationChannel(NotificationChannel(MESSAGES, "Messages", NotificationManager.IMPORTANCE_HIGH))
        nm.createNotificationChannel(NotificationChannel(MESH, "Mesh", NotificationManager.IMPORTANCE_MIN).apply { setShowBadge(false) })
    }

    fun show(e: MessengerEvent.NewMessage) {
        val conv = e.conversation
        val who = e.from?.name ?: "Someone"
        val (title, text) = when {
            conv.request -> "Message request" to "$who wants to chat"
            conv.kind == ConvKind.GROUP -> conv.title to "$who: ${body(e)}"
            conv.kind == ConvKind.PUBLIC -> "Public" to "$who: ${body(e)}"
            else -> who to body(e)
        }
        val open = PendingIntent.getActivity(
            context, conv.id.hashCode(),
            Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_CONVERSATION, conv.id)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(context, MESSAGES)
            .setSmallIcon(R.drawable.ic_stat_signal)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .build()
        if (!Permissions.granted(context, Permissions.notifications)) return
        try {
            NotificationManagerCompat.from(context).notify(conv.id.hashCode(), n)
        } catch (e: SecurityException) {
            // Notifications were turned off for LibreChat after the check.
        }
    }

    fun cancel(convId: String) = nm.cancel(convId.hashCode())

    private fun body(e: MessengerEvent.NewMessage) = when (e.message.kind) {
        MsgKind.PHOTO -> "📷 Photo"
        MsgKind.VOICE -> "🎤 Voice note"
        else -> e.message.text.take(200)
    }

    companion object {
        const val MESSAGES = "messages"
        const val MESH = "mesh"
    }
}
