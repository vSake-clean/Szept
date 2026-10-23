package pl.szept.service

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import pl.szept.App
import pl.szept.R
import pl.szept.call.CallManager
import pl.szept.ui.MainActivity

/** Kanały i powiadomienia rozmów. */
object CallNotifications {
    const val CH_RING = "calls_ring"
    const val CH_ONGOING = "calls"
    const val RING_ID = 4100
    const val ONGOING_ID = 4101
    const val ACTION_DECLINE = "pl.szept.action.CALL_DECLINE"
    const val ACTION_HANGUP = "pl.szept.action.CALL_HANGUP"
    const val ACTION_GDECLINE = "pl.szept.action.GCALL_DECLINE"
    const val ACTION_GLEAVE = "pl.szept.action.GCALL_LEAVE"

    fun channels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_RING, "Połączenia przychodzące", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Dzwonek przy połączeniu głosowym lub wideo"
            setSound(null, null)       // dzwonek odtwarza aplikacja (w pętli)
            enableVibration(false)
        })
        nm.createNotificationChannel(NotificationChannel(CH_ONGOING, "Trwająca rozmowa", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Powiadomienie w trakcie rozmowy"
            setShowBadge(false)
        })
    }

    private fun activity(ctx: Context, code: Int, extra: String) = PendingIntent.getActivity(
        ctx, code, Intent(ctx, MainActivity::class.java).putExtra(extra, 1).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun broadcast(ctx: Context, code: Int, action: String) = PendingIntent.getBroadcast(
        ctx, code, Intent(ctx, CallActionReceiver::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** Dzwonek: powiadomienie pełnoekranowe + dźwięk i wibracje. */
    fun ring(ctx: Context, call: CallManager.Call?, name: String) {
        if (call == null) { stopRing(ctx); return }
        ringNotify(ctx, name, if (call.video) "Rozmowa wideo w Szepcie" else "Połączenie głosowe w Szepcie", "call", "call_answer")
    }

    /** Dzwoni rozmowa grupowa. */
    fun ringGroup(ctx: Context, ring: pl.szept.call.GroupCallManager.Ring?, caller: String) {
        if (ring == null) { stopRing(ctx); return }
        if (ring.isRoom) ringNotify(ctx, caller, if (ring.video) "Rozmowa wideo przychodząca" else "Połączenie przychodzące", "gcall", "gcall_join")
        else ringNotify(ctx, ring.groupName, "$caller zaprasza do rozmowy grupowej" + if (ring.video) " (wideo)" else "", "gcall", "gcall_join")
    }

    fun stopRing(ctx: Context) {
        Ringer.stop(ctx)
        ctx.getSystemService(NotificationManager::class.java).cancel(RING_ID)
    }

    private fun ringNotify(ctx: Context, name: String, text: String, openExtra: String, answerExtra: String) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        channels(ctx)
        val full = activity(ctx, 30, openExtra)
        val answer = activity(ctx, 31, answerExtra)
        val decline = broadcast(ctx, 32, if (openExtra == "gcall") ACTION_GDECLINE else ACTION_DECLINE)
        val person = Person.Builder().setName(name).setImportant(true).build()
        fun base() = NotificationCompat.Builder(ctx, CH_RING)
            .setSmallIcon(R.drawable.ic_stat_msg)
            .setContentTitle(name)
            .setContentText(text)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setFullScreenIntent(full, true)
            .setContentIntent(full)
            .setTimeoutAfter(CallManager.RING_MS + 5_000)
        try {
            nm.notify(RING_ID, base().setStyle(NotificationCompat.CallStyle.forIncomingCall(person, decline, answer)).build())
        } catch (e: Exception) {
            // starsze/nietypowe systemy: zwykłe powiadomienie z przyciskami
            Log.w("Szept", "dzwonek (styl połączenia)", e)
            runCatching {
                nm.notify(RING_ID, base().addAction(0, "Odrzuć", decline).addAction(0, "Odbierz", answer).build())
            }
        }
        Ringer.start(ctx)
    }

    fun ongoing(ctx: Context, name: String, video: Boolean) = NotificationCompat.Builder(ctx, CH_ONGOING)
        .setSmallIcon(R.drawable.ic_stat_msg)
        .setContentTitle(name)
        .setContentText(if (video) "Rozmowa wideo" else "Rozmowa")
        .setCategory(NotificationCompat.CATEGORY_CALL)
        .setOngoing(true)
        .setUsesChronometer(true)
        .setContentIntent(activity(ctx, 33, "call"))
        .addAction(0, "Rozłącz", broadcast(ctx, 34, ACTION_HANGUP))
        .build()
}

/** Dzwonek w pętli z wibracjami (zgodnie z trybem dźwięku telefonu). */
object Ringer {
    private var tone: Ringtone? = null
    private var vibrating = false

    @Synchronized
    fun start(ctx: Context) {
        if (tone != null || vibrating) return
        val am = ctx.getSystemService(AudioManager::class.java)
        val mode = am?.ringerMode ?: AudioManager.RINGER_MODE_NORMAL
        if (mode == AudioManager.RINGER_MODE_NORMAL) {
            try {
                tone = RingtoneManager.getRingtone(ctx, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE))?.apply {
                    audioAttributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
                    if (Build.VERSION.SDK_INT >= 28) isLooping = true
                    play()
                }
            } catch (e: Exception) { Log.w("Szept", "dzwonek", e) }
        }
        if (mode != AudioManager.RINGER_MODE_SILENT) {
            ctx.getSystemService(Vibrator::class.java)?.let { v ->
                v.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 800, 800), 0))
                vibrating = true
            }
        }
    }

    @Synchronized
    fun stop(ctx: Context) {
        runCatching { tone?.stop() }
        tone = null
        if (vibrating) { runCatching { ctx.getSystemService(Vibrator::class.java)?.cancel() }; vibrating = false }
    }
}

/** Dźwięk rozmowy: tryb komunikacji, głośnik/słuchawka. */
object CallAudio {
    private var active = false
    private var prevMode = AudioManager.MODE_NORMAL
    val speaker = MutableStateFlow(false)

    fun start(ctx: Context, speakerOn: Boolean) {
        val am = ctx.getSystemService(AudioManager::class.java) ?: return
        if (!active) {
            active = true
            prevMode = am.mode
            am.mode = AudioManager.MODE_IN_COMMUNICATION
        }
        setSpeaker(ctx, speakerOn)
    }

    @Suppress("DEPRECATION")
    fun setSpeaker(ctx: Context, on: Boolean) {
        val am = ctx.getSystemService(AudioManager::class.java) ?: return
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                if (on) am.availableCommunicationDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }?.let { am.setCommunicationDevice(it) }
                else am.clearCommunicationDevice()
            } else am.isSpeakerphoneOn = on
        } catch (e: Exception) { Log.w("Szept", "głośnik", e) }
        speaker.value = on
    }

    @Suppress("DEPRECATION")
    fun stop(ctx: Context) {
        if (!active) return
        active = false
        val am = ctx.getSystemService(AudioManager::class.java) ?: return
        try { if (Build.VERSION.SDK_INT >= 31) am.clearCommunicationDevice() else am.isSpeakerphoneOn = false } catch (_: Exception) {}
        am.mode = prevMode.takeIf { it != AudioManager.MODE_IN_COMMUNICATION } ?: AudioManager.MODE_NORMAL
        speaker.value = false
    }
}

/** Usługa w trakcie rozmowy: mikrofon (i kamera) działają także po wyjściu z aplikacji. */
class CallService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main + pl.szept.core.CrashLog.handler)

    companion object {
        fun start(ctx: Context) {
            try { ContextCompat.startForegroundService(ctx, Intent(ctx, CallService::class.java)) } catch (e: Exception) { Log.w("Szept", "usługa rozmowy", e) }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        CallNotifications.channels(this)
        val core = App.real
        val call = core.calls.state.value?.takeIf { it.active }
        val group = core.groupCalls.state.value?.takeIf { it.phase == pl.szept.call.GroupCallManager.Phase.ACTIVE }
        // startForegroundService wymaga startForeground — także gdy rozmowa zdążyła się skończyć (inaczej system wywraca aplikację)
        if (call == null && group == null) {
            try { ServiceCompat.startForeground(this, CallNotifications.ONGOING_ID, CallNotifications.ongoing(this, "Rozmowa", false), if (Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0) } catch (_: Exception) {}
            stopSelf(); return
        }
        val video = call?.video ?: group!!.video
        var types = 0
        if (Build.VERSION.SDK_INT >= 30) {
            types = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            if (video && ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            }
        }
        val title = if (call != null) core.db.nameOf(call.peer) else pl.szept.ui.callTitle(group!!)
        val ok = try {
            ServiceCompat.startForeground(this, CallNotifications.ONGOING_ID, CallNotifications.ongoing(this, title, video), types); true
        } catch (e: Exception) {
            // np. Android 14 nie pozwala na typ „kamera” w tej chwili — próbujemy z samym mikrofonem
            Log.w("Szept", "usługa rozmowy", e)
            try {
                ServiceCompat.startForeground(this, CallNotifications.ONGOING_ID, CallNotifications.ongoing(this, title, video),
                    if (Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0); true
            } catch (e2: Exception) { pl.szept.core.CrashLog.record("Usługa rozmowy", e2); false }
        }
        if (!ok) { stopSelf(); return }
        // rozmowa głosowa w dwie osoby — przy uchu (słuchawka); grupa i wideo — głośnik
        CallAudio.start(this, speakerOn = video || (group != null && !(group.isRoom && group.room.size <= 2)))
        scope.launch {
            kotlinx.coroutines.flow.combine(core.calls.state, core.groupCalls.state) { c, g ->
                c?.active == true || g?.phase == pl.szept.call.GroupCallManager.Phase.ACTIVE
            }.collect { on -> if (!on) stopSelf() }
        }
    }

    override fun onDestroy() {
        CallAudio.stop(this)
        scope.cancel()
        super.onDestroy()
    }
}

/** Odrzuć / Rozłącz z powiadomienia. */
class CallActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO + pl.szept.core.CrashLog.handler).launch {
            try {
                when (intent.action) {
                    CallNotifications.ACTION_DECLINE -> App.real.calls.decline()
                    CallNotifications.ACTION_HANGUP -> { App.real.calls.hangup(); App.real.groupCalls.leave() }
                    CallNotifications.ACTION_GDECLINE -> App.real.groupCalls.decline()
                    CallNotifications.ACTION_GLEAVE -> App.real.groupCalls.leave()
                }
            } finally { pending.finish() }
        }
    }
}
