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
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import pl.szept.App
import pl.szept.R
import pl.szept.core.Core
import pl.szept.core.LocationHelper

/**
 * Usługa pierwszoplanowa — utrzymuje połączenia z przekaźnikami, żeby wiadomości
 * docierały także wtedy, gdy aplikacja jest zamknięta. Android wymaga dla niej powiadomienia:
 * jest stałe (nie odświeża się przy każdej zmianie połączenia), a przycisk „Ukryj” prowadzi
 * do ustawień, gdzie można je wyłączyć — usługa działa dalej. W trybie oszczędzania baterii
 * usługa w ogóle nie działa (wiadomości sprawdza budzik i WorkManager), więc nie ma powiadomienia.
 */
class MessengerService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main + pl.szept.core.CrashLog.handler)
    private var liveWatch: Job? = null
    private var locationOn = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        AndroidNotifier.createChannels(this)
        try {
            ServiceCompat.startForeground(this, NOTIF_ID, buildNotification(false), fgsTypes(false))
        } catch (e: Exception) {
            Log.w("Szept", "nie można uruchomić usługi pierwszoplanowej", e)
            stopSelf()
            return
        }
        App.real.start()
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
                ServiceCompat.startForeground(this, NOTIF_ID, buildNotification(true), fgsTypes(true))
            } catch (e: Exception) { Log.w("Szept", "usługa lokalizacji", e) }
            locationOn = LocationHelper.startTracking(this) { l -> App.real.onLocation(l.latitude, l.longitude, l.accuracy) }
        } else {
            LocationHelper.stopTracking(this)
            locationOn = false
            // tryb oszczędzania: usługa była potrzebna tylko do lokalizacji na żywo
            if (!needed()) { stopSelf(); return }
            try { ServiceCompat.startForeground(this, NOTIF_ID, buildNotification(false), fgsTypes(false)) } catch (_: Exception) {}
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!needed()) { stopSelf(); return START_NOT_STICKY }
        App.real.keepalive()
        return START_STICKY
    }

    override fun onDestroy() {
        LocationHelper.stopTracking(this)
        scope.cancel()
        super.onDestroy()
    }

    private fun buildNotification(live: Boolean): Notification {
        val b = NotificationCompat.Builder(this, if (live) AndroidNotifier.CH_LIVE else AndroidNotifier.CH_SERVICE)
            .setSmallIcon(R.drawable.ic_stat_msg)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setContentIntent(AndroidNotifier.openChatIntent(this, null))
        if (live) return b.setContentTitle("Szept: udostępniasz lokalizację na żywo").setContentText("Dotknij, aby otworzyć Szept").build()
        return b.setContentTitle("Szept działa w tle")
            .setContentText("Wiadomości i rozmowy przychodzą od razu. To powiadomienie możesz ukryć.")
            .addAction(0, "Ukryj", PendingIntent.getActivity(this, 11, hideIntent(this), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            .build()
    }

    companion object {
        const val NOTIF_ID = 1

        /** Czy usługa jest potrzebna: w trybie oszczędzania tylko na czas lokalizacji na żywo. */
        fun needed(): Boolean = App.real.bgMode != Core.BG_SAVER || App.real.liveActive.value

        /** Ustawienia kanału „Działanie w tle” — tam można wyłączyć stałe powiadomienie (usługa działa dalej). */
        fun hideIntent(ctx: Context): Intent =
            Intent(android.provider.Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, ctx.packageName)
                .putExtra(android.provider.Settings.EXTRA_CHANNEL_ID, AndroidNotifier.CH_SERVICE)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        /** Czy użytkownik ukrył stałe powiadomienie (wyłączony kanał albo wszystkie powiadomienia Szepta). */
        fun notificationHidden(ctx: Context): Boolean {
            val nm = ctx.getSystemService(android.app.NotificationManager::class.java) ?: return false
            if (!nm.areNotificationsEnabled()) return true
            return nm.getNotificationChannel(AndroidNotifier.CH_SERVICE)?.importance == android.app.NotificationManager.IMPORTANCE_NONE
        }

        /** Uruchamia usługę albo — w trybie oszczędzania — zatrzymuje ją i zostawia budzik co ~10 min. */
        fun start(ctx: Context) {
            if (!needed()) {
                try { ctx.stopService(Intent(ctx, MessengerService::class.java)) } catch (_: Exception) {}
                scheduleKeepAlive(ctx)
                return
            }
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
