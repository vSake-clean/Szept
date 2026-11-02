package pl.szept.core

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineExceptionHandler
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Dziennik błędów w telefonie (bez wysyłania gdziekolwiek). Błędy w zadaniach w tle są zapisywane
 * zamiast wyłączać aplikację; prawdziwe awarie też trafiają tutaj, żeby można je było skopiować i przekazać.
 */
object CrashLog {
    @Volatile private var file: File? = null
    /** Znacznik „aplikacja się wyłączyła” — po ponownym otwarciu Szept proponuje wysłanie dziennika. */
    @Volatile private var crashFlag: File? = null
    private const val MAX = 64 * 1024

    fun init(ctx: Context) {
        file = File(ctx.filesDir, "bledy.log")
        crashFlag = File(ctx.filesDir, "awaria.znacznik")
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            if (isClosedSocketRace(t, e)) {
                // połączenie z przekaźnikiem i tak jest już zamykane (a potem nawiązywane od nowa) — to nie jest awaria
                note("Pominięto zamknięte połączenie w trakcie wysyłania (wątek ${t.name})")
                return@setDefaultUncaughtExceptionHandler
            }
            record("AWARIA (wątek ${t.name})", e)
            try { crashFlag?.writeText(System.currentTimeMillis().toString()) } catch (_: Throwable) { }
            prev?.uncaughtException(t, e)
        }
    }

    /**
     * Znany błąd OkHttp: gdy połączenie WebSocket zrywa się (np. przy zmianie sieci), wątek czytający zamyka
     * strumienie, a wątek wysyłający w tej samej chwili kończy pisać ramkę i dostaje IllegalStateException
     * „closed” zamiast IOException — OkHttp jej nie łapie, więc wyłączała całą aplikację.
     */
    fun isClosedSocketRace(t: Thread, e: Throwable): Boolean {
        if (e !is IllegalStateException || e.message != "closed") return false
        val frames = e.stackTrace
        val inOkio = frames.firstOrNull()?.className?.startsWith("okio.") == true
        val inWebSocket = frames.any { it.className.startsWith("okhttp3.internal.ws.") }
        return inOkio && (inWebSocket || t.name.startsWith("OkHttp"))
    }

    /** Dla zakresów korutyn: błąd zapisany w dzienniku, aplikacja działa dalej. */
    val handler = CoroutineExceptionHandler { _, e -> record("Błąd w tle", e) }

    @Synchronized
    fun record(what: String, e: Throwable) {
        Log.e("Szept", what, e)
        val f = file ?: return
        try {
            val ver = try { pl.szept.BuildConfig.VERSION_NAME } catch (_: Throwable) { "?" }
            val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
            val device = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}".trim()
            val entry = "=== $ts · Szept $ver · Android ${android.os.Build.VERSION.RELEASE} · $device · $what\n" + Log.getStackTraceString(e).take(6000) + "\n"
            val old = if (f.exists()) f.readText() else ""
            f.writeText((old + entry).takeLast(MAX))
        } catch (_: Throwable) { }
    }

    /** Krótka notatka diagnostyczna (np. przebieg łączenia rozmowy) — bez stosu wywołań. */
    @Synchronized
    fun note(text: String) {
        Log.i("Szept", text)
        val f = file ?: return
        try {
            val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
            val old = if (f.exists()) f.readText() else ""
            f.writeText((old + "--- $ts · $text\n").takeLast(MAX))
        } catch (_: Throwable) { }
    }

    fun read(): String = file?.takeIf { it.exists() }?.readText() ?: ""

    /** Czy od ostatniego otwarcia aplikacja wyłączyła się przez błąd (pyta tylko raz). */
    fun takeCrashNotice(): Boolean {
        val f = crashFlag ?: return false
        return try { f.exists().also { if (it) f.delete() } } catch (_: Throwable) { false }
    }
    fun clear() { file?.delete() }
}
