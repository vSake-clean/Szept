package pl.szept.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import pl.szept.App
import pl.szept.R
import pl.szept.core.Core
import pl.szept.core.Notifier
import pl.szept.ui.MainActivity

class AndroidNotifier(private val ctx: Context) : Notifier {
    override fun onCallRing(call: pl.szept.call.CallManager.Call?, name: String) = CallNotifications.ring(ctx, call, name)
    override fun onGroupRing(ring: pl.szept.call.GroupCallManager.Ring?, callerName: String) = CallNotifications.ringGroup(ctx, ring, callerName)

    companion object {
        const val CH_MSG = "messages"
        const val CH_SERVICE = "service"
        const val KEY_REPLY = "reply_text"
        const val ACTION_REPLY = "pl.szept.action.REPLY"
        const val ACTION_READ = "pl.szept.action.READ"

        fun createChannels(ctx: Context) {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CH_MSG, "Wiadomości", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Nowe wiadomości"
                enableVibration(true)
            })
            nm.createNotificationChannel(NotificationChannel(CH_SERVICE, "Działanie w tle", NotificationManager.IMPORTANCE_MIN).apply {
                description = "Stałe powiadomienie, dzięki któremu Szept odbiera wiadomości w tle"
                setShowBadge(false)
            })
        }

        fun chatChannelId(chatId: String) = "chat_" + Integer.toHexString(chatId.hashCode())

        /** Otwiera systemowe ustawienia kanału powiadomień (dźwięk, wibracje…). */
        fun openChannelSettings(ctx: Context, channelId: String) {
            try {
                ctx.startActivity(
                    Intent(android.provider.Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                        .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, ctx.packageName)
                        .putExtra(android.provider.Settings.EXTRA_CHANNEL_ID, channelId)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            } catch (e: Exception) {
                try {
                    ctx.startActivity(Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, ctx.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (_: Exception) {}
            }
        }

        fun hasChatChannel(ctx: Context, chatId: String) =
            ctx.getSharedPreferences("ui", Context.MODE_PRIVATE).getBoolean("chan:$chatId", false) &&
                ctx.getSystemService(NotificationManager::class.java).getNotificationChannel(chatChannelId(chatId)) != null

        /** Osobny kanał (własny dźwięk) dla jednej rozmowy — tworzy go i otwiera jego ustawienia. */
        fun openChatSound(ctx: Context, chatId: String, name: String) {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            val id = chatChannelId(chatId)
            if (nm.getNotificationChannel(id) == null) {
                nm.createNotificationChannel(NotificationChannel(id, "Rozmowa: $name", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Osobne powiadomienia tej rozmowy"
                    enableVibration(true)
                    if (android.os.Build.VERSION.SDK_INT >= 30) setConversationId(CH_MSG, chatId)
                })
            }
            ctx.getSharedPreferences("ui", Context.MODE_PRIVATE).edit().putBoolean("chan:$chatId", true).apply()
            openChannelSettings(ctx, id)
        }

        /** Przywraca wspólny dźwięk wiadomości dla rozmowy. */
        fun resetChatSound(ctx: Context, chatId: String) {
            ctx.getSystemService(NotificationManager::class.java).deleteNotificationChannel(chatChannelId(chatId))
            ctx.getSharedPreferences("ui", Context.MODE_PRIVATE).edit().remove("chan:$chatId").apply()
        }

        fun openChatIntent(ctx: Context, chatId: String?): PendingIntent {
            val i = Intent(ctx, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                if (chatId != null) putExtra("chat", chatId)
            }
            return PendingIntent.getActivity(ctx, chatId?.hashCode() ?: 0, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }
    }

    init { createChannels(ctx) }

    private class Line(val sender: String, val text: String, val ts: Long)
    private class Conv(var title: String, var isGroup: Boolean, val lines: MutableList<Line> = mutableListOf())

    private val convs = HashMap<String, Conv>()

    override fun onMessage(chatId: String, chatName: String, sender: String, text: String, isGroup: Boolean) {
        val conv = synchronized(convs) {
            convs.getOrPut(chatId) { Conv(chatName, isGroup) }.apply {
                title = chatName; this.isGroup = isGroup
                lines.add(Line(sender, text, System.currentTimeMillis()))
                while (lines.size > 8) lines.removeAt(0)
            }
        }
        post(chatId, conv)
    }

    private fun post(chatId: String, conv: Conv) {
        val me = Person.Builder().setName("Ty").setKey("me").build()
        val style = NotificationCompat.MessagingStyle(me)
        if (conv.isGroup) {
            style.conversationTitle = conv.title
            style.isGroupConversation = true
        }
        val snapshot = synchronized(convs) { conv.lines.toList() }
        snapshot.forEach { l ->
            style.addMessage(l.text, l.ts, Person.Builder().setName(l.sender).setKey(l.sender).build())
        }
        val code = chatId.hashCode()
        val reply = NotificationCompat.Action.Builder(
            R.drawable.ic_stat_msg, "Odpowiedz",
            PendingIntent.getBroadcast(
                ctx, code, Intent(ctx, NotificationActionReceiver::class.java).setAction(ACTION_REPLY).putExtra("chat", chatId),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            ),
        ).addRemoteInput(RemoteInput.Builder(KEY_REPLY).setLabel("Odpowiedz…").build())
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
            .setShowsUserInterface(false)
            .build()
        val read = NotificationCompat.Action.Builder(
            R.drawable.ic_stat_msg, "Przeczytane",
            PendingIntent.getBroadcast(
                ctx, code + 1, Intent(ctx, NotificationActionReceiver::class.java).setAction(ACTION_READ).putExtra("chat", chatId),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            ),
        ).setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_MARK_AS_READ)
            .setShowsUserInterface(false)
            .build()
        val last = snapshot.lastOrNull()
        val channel = if (hasChatChannel(ctx, chatId)) chatChannelId(chatId) else CH_MSG
        val n = NotificationCompat.Builder(ctx, channel)
            .setSmallIcon(R.drawable.ic_stat_msg)
            .setContentTitle(if (conv.isGroup) conv.title else last?.sender ?: conv.title)
            .setContentText(last?.text ?: "")
            .setStyle(style)
            .setNumber(snapshot.size)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(openChatIntent(ctx, chatId))
            .addAction(reply)
            .addAction(read)
            .build()
        try { NotificationManagerCompat.from(ctx).notify(code, n) } catch (e: SecurityException) { }
    }

    override fun cancel(chatId: String) {
        synchronized(convs) { convs.remove(chatId) }
        NotificationManagerCompat.from(ctx).cancel(chatId.hashCode())
    }
}

/** Odpowiedź i „przeczytane” prosto z powiadomienia. */
class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val chatId = intent.getStringExtra("chat") ?: return
        if (!App.real.hasIdentity) return
        when (intent.action) {
            AndroidNotifier.ACTION_READ -> App.real.markChatRead(chatId)
            AndroidNotifier.ACTION_REPLY -> {
                val text = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(AndroidNotifier.KEY_REPLY)?.toString()?.trim()
                if (text.isNullOrEmpty()) return
                val pending = goAsync()
                CoroutineScope(Dispatchers.IO + pl.szept.core.CrashLog.handler).launch {
                    try {
                        App.real.start()
                        App.real.sendText(chatId, text)
                        App.real.markChatRead(chatId)
                        App.real.keepalive()
                    } catch (e: Core.SendException) {
                        App.real.notifier?.onMessage(chatId, App.real.db.chat(chatId)?.name ?: "", "Szept", "Nie wysłano: ${e.message}", false)
                    } finally {
                        pending.finish()
                    }
                }
            }
        }
    }
}
