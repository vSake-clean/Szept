package pl.szept.core

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import pl.szept.crypto.Crypto
import pl.szept.crypto.hex
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * GIF-y: moja kolekcja zapisana tylko w telefonie (własne i zapisane z rozmów) oraz wyszukiwarka KLIPY.
 * Wysłany GIF idzie do rozmówcy jak zdjęcie — zaszyfrowany, przez przekaźniki (KLIPY nie wie, komu go wysłano).
 */
object Gifs {
    /** GIF z wyszukiwarki: mały podgląd do siatki i większa wersja do wysłania. */
    data class Remote(val id: String, val title: String, val preview: String, val full: String, val w: Int, val h: Int)
    data class Page(val items: List<Remote>, val hasNext: Boolean)

    const val MAX_GIF = 8L * 1024 * 1024
    private const val API = "https://api.klipy.com/api/v1"

    private val http by lazy {
        OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("szept_gifs", Context.MODE_PRIVATE)

    // ---------------- moja kolekcja ----------------

    fun dir(ctx: Context) = File(ctx.filesDir, "gifs").apply { mkdirs() }

    /** Zapisane GIF-y, najnowsze (ostatnio użyte) pierwsze. */
    fun saved(ctx: Context): List<File> =
        (dir(ctx).listFiles { f -> f.name.endsWith(".gif") } ?: emptyArray()).sortedByDescending { it.lastModified() }

    fun isGif(f: File): Boolean = try {
        f.inputStream().use { s -> val b = ByteArray(4); s.read(b) == 4 && String(b, Charsets.US_ASCII) == "GIF8" }
    } catch (e: Exception) { false }

    /** Dodaje plik do kolekcji (ten sam GIF tylko raz). Zwraca zapisany plik. */
    fun save(ctx: Context, src: File): File {
        if (!isGif(src)) throw Core.SendException("To nie jest GIF")
        if (src.length() > MAX_GIF) throw Core.SendException("GIF jest za duży (max 8 MB)")
        val name = sha(src).take(24) + ".gif"
        val out = File(dir(ctx), name)
        if (!out.exists()) src.copyTo(out)
        out.setLastModified(System.currentTimeMillis())
        return out
    }

    /** Dodaje GIF wybrany z telefonu. */
    suspend fun saveFromUri(ctx: Context, uri: Uri): File = withContext(Dispatchers.IO) {
        val tmp = File(ctx.cacheDir, "gif_in_${System.nanoTime()}.gif")
        try {
            ctx.contentResolver.openInputStream(uri)?.use { i -> tmp.outputStream().use { o -> copyLimited(i, o) } }
                ?: throw Core.SendException("Nie można odczytać pliku")
            save(ctx, tmp)
        } finally { tmp.delete() }
    }

    fun isSaved(ctx: Context, f: File): Boolean = isGif(f) && File(dir(ctx), sha(f).take(24) + ".gif").exists()

    fun remove(ctx: Context, f: File) { if (f.parentFile == dir(ctx)) f.delete() }

    /** Oznacza jako ostatnio użyty (na początek listy). */
    fun touch(f: File) { f.setLastModified(System.currentTimeMillis()) }

    /** Załącznik do wysłania (kopia w pamięci podręcznej — oryginał zostaje w kolekcji). */
    suspend fun attachment(ctx: Context, src: File): Core.Attachment = withContext(Dispatchers.IO) {
        if (!isGif(src)) throw Core.SendException("To nie jest GIF")
        if (src.length() > MAX_GIF) throw Core.SendException("GIF jest za duży (max 8 MB)")
        val out = File(ctx.cacheDir, "gif_${System.nanoTime()}.gif")
        src.copyTo(out, overwrite = true)
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(out.absolutePath, o)
        val first = BitmapFactory.decodeFile(out.absolutePath)
        Core.Attachment(out, "image/gif", "animacja.gif", first?.let { MediaUtil.thumbB64(it, 48, 40) }, o.outWidth, o.outHeight, 0)
    }

    private fun sha(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { s -> val buf = ByteArray(64 * 1024); while (true) { val n = s.read(buf); if (n < 0) break; md.update(buf, 0, n) } }
        return md.digest().hex()
    }

    private fun copyLimited(i: java.io.InputStream, o: java.io.OutputStream) {
        val buf = ByteArray(64 * 1024); var total = 0L
        while (true) {
            val n = i.read(buf); if (n < 0) break
            total += n; if (total > MAX_GIF) throw Core.SendException("GIF jest za duży (max 8 MB)")
            o.write(buf, 0, n)
        }
    }

    // ---------------- KLIPY ----------------

    /**
     * Klucz aplikacji KLIPY (bezpłatny, z klipy.com): wpisany w telefonie, rozesłany przez administratora ([fromAdmin])
     * albo wbudowany w aplikację.
     */
    fun klipyKey(ctx: Context, fromAdmin: String? = null): String =
        prefs(ctx).getString("klipy_key", null)?.takeIf { it.isNotBlank() } ?: fromAdmin?.takeIf { it.isNotBlank() } ?: pl.szept.BuildConfig.KLIPY_KEY

    fun setKlipyKey(ctx: Context, key: String) { prefs(ctx).edit().putString("klipy_key", key.trim()).apply() }

    /** Losowy identyfikator dla KLIPY (wymagany przez API) — niezwiązany z kontem w Szepcie. */
    private fun customerId(ctx: Context): String = prefs(ctx).getString("klipy_cid", null) ?: Crypto.randomBytes(12).hex().also {
        prefs(ctx).edit().putString("klipy_cid", it).apply()
    }

    /** Popularne (pusta fraza) albo wyniki wyszukiwania. */
    suspend fun klipy(ctx: Context, key: String, query: String, page: Int = 1, base: String = API): Page = withContext(Dispatchers.IO) {
        if (key.isBlank()) throw Core.SendException("Brak klucza KLIPY")
        val q = query.trim()
        val url = "$base/$key/gifs/${if (q.isEmpty()) "trending" else "search"}".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
            .addQueryParameter("per_page", "24")
            .addQueryParameter("customer_id", customerId(ctx))
            .addQueryParameter("locale", "pl_PL")
            .addQueryParameter("rating", "pg-13")
            .apply { if (q.isNotEmpty()) addQueryParameter("q", q.take(80)) }
            .build()
        val body = http.newCall(Request.Builder().url(url).build()).execute().use { r ->
            val b = r.body?.string() ?: ""
            if (!r.isSuccessful) {
                val msg = runCatching { JSONObject(b).optJSONObject("errors")?.optJSONArray("message")?.optString(0) }.getOrNull()
                throw Core.SendException(if (r.code == 401 || r.code == 403 || msg?.contains("key", true) == true) "Nieprawidłowy klucz KLIPY" else "KLIPY: błąd ${r.code}")
            }
            b
        }
        parse(body)
    }

    /** Odpowiedź KLIPY: {"result":true,"data":{"data":[…],"has_next":true}}; elementy reklamowe pomijamy. */
    fun parse(body: String): Page {
        val root = JSONObject(body)
        if (!root.optBoolean("result", true)) {
            val msg = root.optJSONObject("errors")?.optJSONArray("message")?.optString(0) ?: "błąd"
            throw Core.SendException(if (msg.contains("key", true)) "Nieprawidłowy klucz KLIPY" else "KLIPY: $msg")
        }
        val data = root.optJSONObject("data") ?: JSONObject()
        val arr = data.optJSONArray("data") ?: JSONArray()
        val out = ArrayList<Remote>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optString("type") == "ad") continue
            val small = pick(o, listOf("sm", "xs", "md")) ?: continue
            val big = pick(o, listOf("md", "sm", "hd")) ?: small
            out += Remote(
                o.optString("slug").ifEmpty { o.optString("id") }.ifEmpty { small.first },
                o.optString("title"), small.first, big.first, big.second, big.third,
            )
        }
        return Page(out, data.optBoolean("has_next", false))
    }

    /** Adres GIF-a w danym rozmiarze: file.{sm|md|…}.gif.url (albo files.gif.url w starszym formacie). */
    private fun pick(o: JSONObject, sizes: List<String>): Triple<String, Int, Int>? {
        val file = o.optJSONObject("file") ?: o.optJSONObject("files")
        if (file != null) {
            for (s in sizes) {
                val g = file.optJSONObject(s)?.optJSONObject("gif") ?: continue
                val u = g.optString("url"); if (u.startsWith("https://")) return Triple(u, g.optInt("width"), g.optInt("height"))
            }
            file.optJSONObject("gif")?.let { g -> g.optString("url").takeIf { it.startsWith("https://") }?.let { return Triple(it, g.optInt("width"), g.optInt("height")) } }
        }
        return null
    }

    /** Pobiera GIF (z pamięcią podręczną). */
    suspend fun download(ctx: Context, url: String): File = withContext(Dispatchers.IO) {
        val d = File(ctx.cacheDir, "klipy").apply { mkdirs() }
        val f = File(d, MessageDigest.getInstance("SHA-256").digest(url.toByteArray()).hex().take(24) + ".gif")
        if (f.exists() && f.length() > 0) return@withContext f.also { it.setLastModified(System.currentTimeMillis()) }
        val tmp = File(d, f.name + ".part")
        http.newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (!r.isSuccessful) throw Core.SendException("Nie udało się pobrać GIF-a")
            r.body!!.byteStream().use { i -> tmp.outputStream().use { o -> copyLimited(i, o) } }
        }
        tmp.renameTo(f)
        // pamięć podręczna do ~40 MB
        val all = d.listFiles()?.sortedByDescending { it.lastModified() } ?: emptyList()
        var sum = 0L
        all.forEach { x -> sum += x.length(); if (sum > 40L * 1024 * 1024) x.delete() }
        f
    }
}
