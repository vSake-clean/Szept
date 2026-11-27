package pl.szept.core

import pl.szept.crypto.Crypto
import pl.szept.crypto.hex

/**
 * Kod bezpieczeństwa rozmowy: ten sam u obu osób, liczony z obu kluczy publicznych.
 * Gdy kody się zgadzają (porównane na żywo albo zeskanowane), nikt nie podmienił klucza —
 * np. w linku do kontaktu przesłanym przez inny komunikator.
 */
object SafetyCode {
    private const val VERSION = "szept-safety/1"

    private fun pair(a: String, b: String) = if (a < b) a to b else b to a

    /** 60 cyfr w 12 grupach po 5. */
    fun groups(a: String, b: String): List<String> {
        val (lo, hi) = pair(a, b)
        val bytes = Crypto.sha256("$VERSION|$lo|$hi|1".toByteArray()) + Crypto.sha256("$VERSION|$lo|$hi|2".toByteArray())
        return List(12) { g ->
            var v = 0L
            for (i in 0 until 5) v = (v shl 8) or (bytes[g * 5 + i].toLong() and 0xff)
            (v % 100_000).toString().padStart(5, '0')
        }
    }

    /** Zawartość kodu QR, który pokazuję rozmówcy. */
    fun qr(myPub: String, theirPub: String): String = "szept://verify?a=$myPub&c=" + token(myPub, theirPub)

    private fun token(a: String, b: String): String {
        val (lo, hi) = pair(a, b)
        return Crypto.sha256("$VERSION|qr|$lo|$hi".toByteArray()).hex().take(32)
    }

    enum class Result { MATCH, MISMATCH, OTHER_PERSON, INVALID }

    /** Sprawdza zeskanowany kod rozmówcy [theirPub]. */
    fun check(scanned: String, myPub: String, theirPub: String): Result {
        val t = scanned.trim()
        if (!t.startsWith("szept://verify?")) return Result.INVALID
        val q = t.substringAfter('?').split('&').mapNotNull { p -> p.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }.toMap()
        val a = q["a"] ?: return Result.INVALID
        val c = q["c"] ?: return Result.INVALID
        if (a != theirPub) return Result.OTHER_PERSON
        return if (c == token(myPub, theirPub)) Result.MATCH else Result.MISMATCH
    }
}
