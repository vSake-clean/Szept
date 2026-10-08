package pl.szept.core

import pl.szept.crypto.isHex64
import pl.szept.crypto.isPubKey
import java.net.URLDecoder
import java.net.URLEncoder

/** Kody/linki do dodawania kontaktów i dołączania do grup. */
object Links {
    sealed class Parsed {
        data class ContactCode(val pk: String, val name: String) : Parsed()
        data class GroupInvite(val gid: String, val token: String, val admins: List<String>, val name: String) : Parsed()
        /** Lista przekaźników do dodania (np. własny przekaźnik znajomego). */
        data class Relays(val urls: List<String>) : Parsed()
        /** Sieć Wi-Fi utworzona przez telefon z Szeptem (tryb bez internetu). */
        data class Lan(val ssid: String, val pass: String, val host: String?, val port: Int) : Parsed()
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    private fun dec(s: String) = try { URLDecoder.decode(s, "UTF-8") } catch (e: Exception) { s }

    fun contact(pk: String, name: String) = "szept://u/$pk?n=${enc(name)}"

    fun group(gid: String, token: String, admins: List<String>, name: String) =
        "szept://g/$gid?t=$token&a=${admins.take(5).joinToString(",")}&n=${enc(name)}"

    fun relays(urls: List<String>) = "szept://r?u=" + enc(urls.joinToString(","))

    fun parse(text: String): Parsed? {
        val t = text.trim()
        val idx = t.indexOf("szept://")
        if (idx < 0) {
            val hex = t.lowercase()
            return if (hex.isPubKey()) Parsed.ContactCode(hex, "") else null
        }
        val link = t.substring(idx).split(Regex("\\s"))[0]
        val rest = link.removePrefix("szept://")
        val path = rest.substringBefore('?')
        val query = rest.substringAfter('?', "").split('&').mapNotNull {
            val k = it.substringBefore('=', ""); if (k.isEmpty()) null else k to dec(it.substringAfter('='))
        }.toMap()
        if (path == "lan" || path == "lan/") {
            val ssid = query["s"] ?: return null
            if (ssid.isEmpty() || ssid.length > 32) return null
            val host = query["h"]?.takeIf { h -> h.length in 7..15 && h.all { it.isDigit() || it == '.' } }
            val port = query["o"]?.toIntOrNull()?.takeIf { it in 1..65535 } ?: 4869
            return Parsed.Lan(ssid, (query["p"] ?: "").take(63), host, port)
        }
        if (path == "r" || path == "r/") {
            val urls = (query["u"] ?: "").split(',').map { it.trim() }
                .filter { (it.startsWith("wss://") || it.startsWith("ws://")) && it.length in 8..200 && !it.contains(' ') }.distinct().take(20)
            return if (urls.isEmpty()) null else Parsed.Relays(urls)
        }
        val parts = path.split('/')
        if (parts.size < 2) return null
        return when (parts[0]) {
            "u" -> parts[1].lowercase().takeIf { it.isHex64() }?.let { Parsed.ContactCode(it, query["n"] ?: "") }
            "g" -> {
                val admins = (query["a"] ?: "").split(',').map { it.lowercase() }.filter { it.isHex64() }
                val gid = parts[1]
                val tok = query["t"] ?: ""
                if (gid.length < 8 || admins.isEmpty() || tok.isEmpty()) null
                else Parsed.GroupInvite(gid, tok, admins, query["n"] ?: "Grupa")
            }
            else -> null
        }
    }
}
