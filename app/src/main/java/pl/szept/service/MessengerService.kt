package pl.szept.service

import android.app.AlarmManager
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import pl.szept.App
import pl.szept.R
import pl.szept.core.Core
import pl.szept.core.LocationHelper
import pl.szept.nostr.Relay

/**
 * Usługa pierwszoplanowa — utrzymuje połączenia z przekaźnikami, żeby wiadomości
 * docierały także wtedy, gdy aplikacja jest zamknięta.
 */
class MessengerService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main + pl.szept.core.CrashLog.handler)
    private var watch: Job? = null
    private var liveWatch: Job? = null
    private var locationOn = false
    private var lastOk = 0
    private var lastTotal = 0
    private var lastTrimmed = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        AndroidNotifier.createChannels(this)
        try {
            ServiceCompat.startForeground(this, NOTIF_ID, buildNotification(0, 0), fgsTypes(false))
        } catch (e: Exception) {
            Log.w("Szept", "nie można uruchomić usługi pierwszoplanowej", e)
            stopSelf()
            return
        }
        App.real.start()
        watch = scope.launch {
            combine(App.real.relayStates, App.real.trimmed) { states, trimmed -> states to trimmed }.collectLatest { (states, trimmed) ->
                val ok = states.values.count { it == Relay.State.CONNECTED }
                lastOk = ok; lastTotal = states.size; lastTrimmed = trimmed
                try {
                    NotificationManagerCompat.from(this@MessengerService).notify(NOTIF_ID, buildNotification(ok, states.size, trimmed))
                } catch (e: SecurityException) { }
            }
        }
        // udostępnianie lokalizacji na żywo: odbiornik GPS działa tylko wtedy
        liveWatch = scope.launch { App.real.liveActive.collect { on -> setLive(on) } }
        scheduleKeepAlive(this)
    }

    private fun fgsTypes(location: Boolean): Int = when {
        Build.VERSION.SDK_INT >= 34 -> ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or (if (location) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0)
        Build.VERSION.SDK_INT >= 29 && location -> ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        else -> 0
    }

    private fun setLive(on: Boolean) {
        if (on == locationOn) return
        if (on) {
            try {
                ServiceCompat.startForeground(this, NOTIF_ID, buildNotification(lastOk, lastTotal, lastTrimmed, true), fgsTypes(true))
            } catch (e: Exception) { Log.w("Szept", "usługa lokalizacji", e) }
            locationOn = LocationHelper.startTracking(this) { l -> App.real.onLocation(l.latitude, l.longitude, l.accuracy) }
        } else {
            LocationHelper.stopTracking(this)
            locationOn = false
            try { ServiceCompat.startForeground(this, NOTIF_ID, buildNotification(lastOk, lastTotal, lastTrimmed), fgsTypes(false)) } catch (_: Exception) {}
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        App.real.keepalive()
        return START_STICKY
    }

    override fun onDestroy() {
        LocationHelper.stopTracking(this)
        scope.cancel()
        super.onDestroy()
    }

    private fun buildNotification(ok: Int, total: Int, trimmed: Boolean = false, live: Boolean = locationOn || App.real.liveActive.value): Notification =
        NotificationCompat.Builder(this, AndroidNotifier.CH_SERVICE)
            .setSmallIcon(R.drawable.ic_stat_msg)
            .setContentTitle(if (live) "Szept: udostępniasz lokalizację na żywo" else "Szept odbiera wiadomości")
            .setContentText(
                when {
                    total == 0 -> "Łączenie…"
                    trimmed && App.real.bgMode == Core.BG_SAVER -> "Tryb oszczędny · sprawdzanie co ok. 10–15 min"
                    trimmed -> "Tryb zrównoważony · $ok na żywo, pełna synchronizacja co ok. 10 min"
                    else -> "Połączono z $ok/$total przekaźnikami"
                },
            )
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setContentIntent(AndroidNotifier.openChatIntent(this, null))
            .build()

    companion object {
        const val NOTIF_ID = 1

        fun start(ctx: Context) {
            try {
                ContextCompat.startForegroundService(ctx, Intent(ctx, MessengerService::class.java))
            } catch (e: Exception) {
                Log.w("Szept", "start usługi zablokowany: ${e.message}")
            }
        }

        /** Budzik na godzinę wysyłki wiadomości zaplanowanej. */
        fun scheduleWake(ctx: Context, at: Long) {
            val am = ctx.getSystemService(AlarmManager::class.java) ?: return
            val pi = PendingIntent.getBroadcast(
                ctx, 8, Intent(ctx, KeepAliveReceiver::class.java).putExtra("sched", true),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }

        /** Co ~10 minut budzi telefon (nawet w trybie Doze), by sprawdzić połączenia. */
        fun scheduleKeepAlive(ctx: Context) {
            val am = ctx.getSystemService(AlarmManager::class.java) ?: return
            val pi = PendingIntent.getBroadcast(
                ctx, 7, Intent(ctx, KeepAliveReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + 10 * 60_000L, pi)
        }
    }
}
