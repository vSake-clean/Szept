package pl.szept.nostr

import kotlin.math.abs

/**
 * Zegar skorygowany względem przekaźników.
 * Przekaźniki odrzucają zdarzenia z czasem > 15 min w przyszłości, a odbiorcy filtrują po czasie —
 * telefon ze źle ustawioną godziną nie mógłby wysyłać ani być odbierany. Przesunięcie liczymy
 * z nagłówka HTTP „Date” przy każdym połączeniu (mediana z kilku przekaźników).
 */
object Clock {
    @Volatile var offsetMs = 0L
        private set
    private val samples = ArrayDeque<Long>()

    fun now(): Long = System.currentTimeMillis() + offsetMs
    fun nowSec(): Long = now() / 1000

    @Synchronized
    fun addSample(serverMs: Long, localMs: Long) {
        samples.addLast(serverMs - localMs)
        while (samples.size > 9) samples.removeFirst()
        if (samples.size < 2) return
        val median = samples.sorted()[samples.size / 2]
        // drobne różnice (nagłówek ma rozdzielczość 1 s) ignorujemy
        offsetMs = if (abs(median) < 10_000) 0 else median
    }

    val sampleCount: Int @Synchronized get() = samples.size

    @Synchronized
    fun reset() { samples.clear(); offsetMs = 0 }
}
