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
    private const val MAX = 64 * 1024

    fun init(ctx: Context) {
        file = File(ctx.filesDir, "bledy.log")
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            record("AWARIA (wątek ${t.name})", e)
            prev?.uncaughtException(t, e)
        }
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
            val entry = "=== $ts · Szept $ver · Android ${android.os.Build.VERSION.RELEASE} · $what\n" + Log.getStackTraceString(e).take(6000) + "\n"
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
    fun clear() { file?.delete() }
}
