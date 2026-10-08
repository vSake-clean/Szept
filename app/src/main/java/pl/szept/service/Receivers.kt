package pl.szept.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import pl.szept.App

/** Po włączeniu telefonu automatycznie uruchamia odbieranie wiadomości. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!App.core.hasIdentity) return
        App.core.start()
        MessengerService.start(context)
        SyncWorker.schedule(context)
    }
}

class KeepAliveReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!App.core.hasIdentity) return
        val pending = goAsync()
        val wl = context.getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "szept:keepalive").apply { acquire(30_000) }
        App.core.keepalive()
        MessengerService.start(context)
        MessengerService.scheduleKeepAlive(context)
        CoroutineScope(Dispatchers.IO + pl.szept.core.CrashLog.handler).launch {
            delay(15_000)   // czas na pobranie zaległych wiadomości
            if (wl.isHeld) wl.release()
            pending.finish()
        }
    }
}
