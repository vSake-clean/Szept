package pl.szept

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
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
        resetDecoy()
        real = newCore()
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) { real.appVisible = true; if (real.hasIdentity) real.keepalive() }
            override fun onStop(owner: LifecycleOwner) { real.appVisible = false }
        })
        if (real.hasIdentity) startBackground()
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
        exitDecoy()
        val visible = real.appVisible
        try { stopService(android.content.Intent(this, MessengerService::class.java)) } catch (_: Exception) {}
        try { real.stop() } catch (_: Exception) {}
        real = newCore()
        real.appVisible = visible
        if (real.hasIdentity) startBackground()
    }

    fun startBackground() {
        real.start()
        MessengerService.start(this)
        SyncWorker.schedule(this)
    }

    companion object {
        /** Prawdziwe konto — z niego korzystają usługi w tle, powiadomienia i blokada. */
        lateinit var real: Core

        /** Konto widoczne w aplikacji: prawdziwe albo „pusty” Szept po wpisaniu fałszywego PIN-u. */
        val core: Core get() = if (decoyActive) decoyCore ?: real else real

        var decoyActive by androidx.compose.runtime.mutableStateOf(false)
            private set
        private var decoyCore: Core? = null
        private var savedNotifier: pl.szept.core.Notifier? = null

        /**
         * Fałszywy PIN: pokazuje osobne, puste konto (bez rozmów i kontaktów, bez połączenia z siecią).
         * Prawdziwe konto dalej odbiera wiadomości, ale nie pokazuje powiadomień, dopóki nie wpiszesz prawdziwego PIN-u.
         */
        fun enterDecoy(ctx: android.content.Context) {
            if (decoyActive) return
            val app = ctx.applicationContext
            val d = decoyCore ?: Core(app, "decoy").also { c ->
                c.calls.factory = pl.szept.call.WebRtcEngine.factory(app)
                c.groupCalls.factory = pl.szept.call.WebRtcSession.factory(app)
                decoyCore = c
            }
            if (!d.hasIdentity) d.createIdentity(real.myName)
            // wygląda jak zwykłe konto: łączy się z przekaźnikami (bez powiadomień)
            try { d.appVisible = true; d.start() } catch (e: Exception) { pl.szept.core.CrashLog.record("Pusty Szept", e) }
            savedNotifier = real.notifier
            real.notifier = null
            decoyActive = true
        }

        private fun resetDecoy() { decoyActive = false; decoyCore = null; savedNotifier = null }

        fun exitDecoy() {
            if (!decoyActive) return
            real.notifier = savedNotifier ?: real.notifier
            savedNotifier = null
            decoyActive = false
        }
        /**
         * Praca, która nie może zginąć razem z ekranem (wysyłanie, przygotowanie zdjęć i wideo, zapisywanie).
         * Błędy trafiają do dziennika zamiast wyłączać aplikację.
         */
        val work = kotlinx.coroutines.CoroutineScope(
            kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main.immediate + pl.szept.core.CrashLog.handler,
        )
    }
}
