package pl.szept.core

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import pl.szept.call.IceServer
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Wbudowana konfiguracja sieci: serwery TURN (rozmowy przez sieć komórkową) i klucz API Metered.
 * Dane są zaszyfrowane w aplikacji (AES-GCM) i odszyfrowywane dopiero w pamięci.
 * Świeże dane logowania Metered są pobierane przez API i trzymane zaszyfrowane kluczem z Android Keystore.
 */
object NetConfig {
    private val cfg: JSONObject? by lazy {
        try {
            val blob = pl.szept.BuildConfig.NET_CFG
            if (blob.isEmpty()) return@lazy null
            val pad = MessageDigest.getInstance("SHA-256").digest("szept/siec/2.9".toByteArray())
            val masked = Base64.getDecoder().decode(pl.szept.BuildConfig.NET_K)
            val key = ByteArray(32) { (masked[it].toInt() xor pad[it].toInt()).toByte() }
            val raw = Base64.getDecoder().decode(blob)
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, raw, 0, 12))
            c.updateAAD("szept-net".toByteArray())
            JSONObject(String(c.doFinal(raw, 12, raw.size - 12), Charsets.UTF_8))
        } catch (e: Exception) { null }
    }

    /** Wbudowane serwery TURN (każdy z własnym loginem). */
    fun builtInTurn(): List<IceServer> {
        val arr = cfg?.optJSONArray("turns") ?: return emptyList()
        return (0 until arr.length()).flatMap { i ->
            val o = arr.optJSONObject(i) ?: return@flatMap emptyList()
            val u = o.optJSONArray("u") ?: return@flatMap emptyList()
            (0 until u.length()).map { IceServer(u.optString(it), o.optString("n"), o.optString("p")) }
                .filter { it.url.startsWith("turn:") || it.url.startsWith("turns:") }
        }
    }

    val hasMetered: Boolean get() = cfg?.optString("mk").orEmpty().isNotEmpty() && cfg?.optString("mu").orEmpty().startsWith("https://")

    private const val PREF = "net_cache"
    private const val MAX_AGE = 6 * 3600_000L

    /** Dane z API Metered zapisane w telefonie (zaszyfrowane kluczem z Android Keystore). */
    fun cachedMetered(ctx: Context): List<IceServer> {
        val p = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val sealed = p.getString("metered", null) ?: return emptyList()
        val json = pl.szept.data.DbCrypto.open(sealed)?.let { String(it) } ?: return emptyList()
        return try { parseIce(JSONArray(json)) } catch (e: Exception) { emptyList() }
    }

    private val http by lazy { OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build() }

    /** Odświeża dane logowania Metered (co kilka godzin). */
    suspend fun refreshMetered(ctx: Context, force: Boolean = false) = withContext(Dispatchers.IO) {
        if (!hasMetered) return@withContext
        val p = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        if (!force && System.currentTimeMillis() - p.getLong("metered_at", 0) < MAX_AGE) return@withContext
        try {
            val url = (cfg!!.optString("mu").toHttpUrlOrNull() ?: return@withContext).newBuilder()
                .addQueryParameter("apiKey", cfg!!.optString("mk")).build()
            val body = http.newCall(Request.Builder().url(url).build()).execute().use { r -> if (r.isSuccessful) r.body?.string() else null } ?: return@withContext
            val arr = JSONArray(body)
            if (parseIce(arr).isEmpty()) return@withContext
            val sealed = pl.szept.data.DbCrypto.seal(arr.toString().toByteArray()) ?: return@withContext
            p.edit().putString("metered", sealed).putLong("metered_at", System.currentTimeMillis()).apply()
        } catch (e: Exception) { CrashLog.note("TURN: nie udało się pobrać danych Metered (${e.javaClass.simpleName})") }
    }

    /** Format WebRTC: [{urls: "…"|[…], username, credential}]. */
    fun parseIce(arr: JSONArray): List<IceServer> = (0 until arr.length()).flatMap { i ->
        val o = arr.optJSONObject(i) ?: return@flatMap emptyList()
        val urls = o.optJSONArray("urls")?.let { a -> List(a.length()) { a.optString(it) } } ?: listOf(o.optString("urls"))
        urls.filter { it.startsWith("turn:") || it.startsWith("turns:") }.map { IceServer(it, o.optString("username"), o.optString("credential")) }
    }
}
