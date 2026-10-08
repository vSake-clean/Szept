package pl.szept

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import pl.szept.core.Core
import pl.szept.service.AndroidNotifier
import pl.szept.service.MessengerService
import pl.szept.service.SyncWorker

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        pl.szept.core.CrashLog.init(this)
        core = newCore()
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) { core.appVisible = true; if (core.hasIdentity) core.keepalive() }
            override fun onStop(owner: LifecycleOwner) { core.appVisible = false }
        })
        if (core.hasIdentity) startBackground()
    }

    private fun newCore() = Core(this).also { c ->
        c.notifier = AndroidNotifier(this)
        c.wakeAt = { at -> MessengerService.scheduleWake(this, at) }
        c.calls.factory = pl.szept.call.WebRtcEngine.factory(this)
        c.groupCalls.factory = pl.szept.call.WebRtcSession.factory(this)
    }

    /**
     * Nowy rdzeń po usunięciu konta (kod awaryjny) albo po przywróceniu kopii zapasowej —
     * stary jest zatrzymywany razem z usługą w tle.
     */
    fun resetCore() {
        val visible = core.appVisible
        try { stopService(android.content.Intent(this, MessengerService::class.java)) } catch (_: Exception) {}
        try { core.stop() } catch (_: Exception) {}
        core = newCore()
        core.appVisible = visible
        if (core.hasIdentity) startBackground()
    }

    fun startBackground() {
        core.start()
        MessengerService.start(this)
        SyncWorker.schedule(this)
    }

    companion object {
        lateinit var core: Core
        /**
         * Praca, która nie może zginąć razem z ekranem (wysyłanie, przygotowanie zdjęć i wideo, zapisywanie).
         * Błędy trafiają do dziennika zamiast wyłączać aplikację.
         */
        val work = kotlinx.coroutines.CoroutineScope(
            kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main.immediate + pl.szept.core.CrashLog.handler,
        )
    }
}
