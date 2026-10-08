package pl.szept.data

import org.json.JSONArray
import org.json.JSONObject

object Kind {
    const val TEXT = "text"
    const val IMAGE = "image"
    const val VOICE = "voice"
    const val VIDEO = "video"
    const val FILE = "file"
    const val SYSTEM = "system"
    const val UPDATE = "update"   // nowa wersja aplikacji rozesłana do znajomych
    const val POLL = "poll"       // ankieta (treść = JSON z pytaniem i odpowiedziami)
    const val LOCATION = "loc"    // lokalizacja jednorazowa lub na żywo (treść = JSON)
}

object St {
    const val QUEUED = 0
    const val UPLOADING = 1
    const val SENT = 2
    const val DELIVERED = 3
    const val READ = 4
    const val OPENED = 5
    const val FAILED = -1
    const val RECEIVED = 10
    const val DOWNLOADING = 11
    const val DOWNLOAD_FAILED = 12
}

/** Tryb wiadomości znikających. */
object XMode {
    const val OFF = 0
    const val AFTER_SEND = 1   // znika X s po wysłaniu (u wszystkich)
    const val AFTER_OPEN = 2   // ukryta do otwarcia, znika X s po otwarciu
}

object GroupState {
    const val ACTIVE = 0
    const val PENDING = 1      // wysłano prośbę o dołączenie
    const val REMOVED = 2
    const val LEFT = 3
    const val CLOSED = 4
}

data class Contact(
    val pk: String, val name: String, val isContact: Boolean, val blocked: Boolean,
    val alias: String = "", val status: String = "", val bio: String = "",
    val avatar: String = "", val banner: String = "", val profTs: Long = 0,
    /** Znaczek weryfikacji nadany przez administratora (sprawdzony podpis). */
    val verified: Boolean = false,
) {
    /** Nazwa pokazywana u mnie: mój lokalny alias ma pierwszeństwo nad nazwą wybraną przez tę osobę. */
    val display: String get() = alias.ifBlank { name }
    /** Stały, niezmienny identyfikator wyprowadzony z klucza publicznego. */
    val shortId: String get() = shortId(pk)
}

/** Krótki, czytelny identyfikator konta (niezmienny — pochodzi z klucza). Np. „SZPT-7QF3-K9A2”. */
fun shortId(pk: String): String {
    val a = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    val sb = StringBuilder("SZPT-")
    var acc = 0L
    for (i in 0 until 10) acc = acc * 31 + (pk.getOrElse(i) { '0' }.code.toLong())
    for (i in 0 until 8) { if (i == 4) sb.append('-'); sb.append(a[((acc ushr (i * 5)) and 31).toInt()]) }
    return sb.toString()
}

data class Chat(
    val id: String, val isGroup: Boolean, val peer: String?, val name: String,
    val lastTs: Long, val lastText: String, val unread: Int, val xmode: Int, val ttl: Int,
    val muted: Boolean = false,
    /** Przypięty na górze listy (czas przypięcia, 0 = nie). */
    val pinned: Long = 0,
    val archived: Boolean = false,
    /** Ukryty — widoczny dopiero po wpisaniu PIN-u ukrytych czatów. */
    val hidden: Boolean = false,
    /** Niewysłany szkic wiadomości. */
    val draft: String = "",
)

data class Member(val pk: String, val name: String)

data class Group(
    val id: String, val name: String, val ver: Int, val admins: List<String>, val members: List<Member>,
    val token: String, val adminsOnly: Boolean, val state: Int,
    /** Osoby usunięte z grupy (lub które ją opuściły) — ich wiadomości są odrzucane. */
    val removed: List<String> = emptyList(),
    /** Wygląd grupy: ikona (emoji lub obraz base64) i kolor akcentu (0 = domyślny). */
    val icon: String = "", val color: Int = 0,
    /** Opis grupy. */
    val descr: String = "",
    /** Nowe osoby z linku czekają na ręczne zatwierdzenie przez administratora. */
    val approve: Boolean = false,
    /** Dodatkowe linki zaproszenia z limitem czasu lub liczby użyć. */
    val invites: List<Invite> = emptyList(),
    /** Wyciszeni przez administratora grupy: klucz → do kiedy (Long.MAX_VALUE = na zawsze). */
    val muted: Map<String, Long> = emptyMap(),
    /** Zbanowani: usunięci i bez możliwości powrotu z linku. */
    val banned: List<String> = emptyList(),
) {
    fun isAdmin(pk: String) = pk in admins
    fun isMuted(pk: String, now: Long) = (muted[pk] ?: 0L) > now
    fun moderJson(): JSONObject = JSONObject().put("mu", JSONObject().apply { muted.forEach { (k, v) -> put(k, v) } }).put("bn", JSONArray(banned))
    fun isMember(pk: String) = members.any { it.pk == pk }

    /** Czy token z linku jest ważny (główny link albo niewygasłe zaproszenie z limitem). */
    fun validToken(tok: String, now: Long): Boolean = tok.isNotEmpty() && (tok == token || invites.any { it.token == tok && it.valid(now) })
}

/** Link zaproszenia z limitem: [exp] = koniec ważności (ms, 0 = bez limitu), [max] = maks. użyć (0 = bez limitu). */
data class Invite(val token: String, val exp: Long, val max: Int, val used: Int, val by: String) {
    fun valid(now: Long) = (exp == 0L || now < exp) && (max == 0 || used < max)
    fun toJson(): JSONObject = JSONObject().put("t", token).put("e", exp).put("m", max).put("u", used).put("b", by)

    companion object {
        fun fromJson(o: JSONObject) = Invite(o.optString("t").take(40), o.optLong("e"), o.optInt("m"), o.optInt("u"), o.optString("b").take(64))
        fun listFrom(a: org.json.JSONArray?): List<Invite> =
            if (a == null) emptyList() else List(a.length()) { fromJson(a.getJSONObject(it)) }.filter { it.token.isNotEmpty() }.take(50)
    }
}

data class MediaMeta(
    val id: String, val key: String, val size: Long, val chunks: Int, val mime: String, val sha: String,
    val name: String, val thumb: String?, val w: Int, val h: Int, val dur: Long,
    /** Przebieg głosówki (48 słupków, szesnastkowo). */
    val wave: String? = null,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("key", key); put("sz", size); put("c", chunks); put("mime", mime); put("h", sha)
        put("name", name); if (thumb != null) put("th", thumb); put("w", w); put("ht", h); put("dur", dur)
        if (wave != null) put("wf", wave)
    }

    companion object {
        private val ID_RX = Regex("^[0-9a-f]{32}$")
        private const val MAX_SIZE = 64L * 1024 * 1024
        private const val CHUNK_RAW = 44_000

        /**
         * Dane pliku od kogoś innego — sprawdzane, bo id staje się nazwą katalogu/pliku w telefonie
         * (np. „../../” mogłoby skasować dane aplikacji), a zły klucz wywracał pobieranie przy każdym starcie.
         */
        /** Z własnej bazy (bez sprawdzania — stare wpisy muszą dalej się wczytać). */
        fun fromJson(o: JSONObject) = MediaMeta(
            o.getString("id"), o.getString("key"), o.getLong("sz"), o.getInt("c"), o.optString("mime"),
            o.getString("h"), o.optString("name"), o.optString("th").ifEmpty { null }, o.optInt("w"),
            o.optInt("ht"), o.optLong("dur"), o.optString("wf").take(400).ifEmpty { null },
        )

        fun fromNetwork(o: JSONObject): MediaMeta {
            val m = MediaMeta(
                o.getString("id"), o.getString("key"), o.getLong("sz"), o.getInt("c"), o.optString("mime").take(100),
                o.getString("h"), o.optString("name").substringAfterLast('/').take(120), o.optString("th").ifEmpty { null }, o.optInt("w"),
                o.optInt("ht"), o.optLong("dur"), o.optString("wf").take(400).ifEmpty { null },
            )
            require(ID_RX.matches(m.id)) { "złe id pliku" }
            require(m.size in 0..MAX_SIZE && m.chunks in 0..(m.size / CHUNK_RAW + 2).toInt()) { "zły rozmiar pliku" }
            require(m.chunks > 0 || m.size == 0L) { "zły podział pliku" }
            require(m.sha.length == 64) { "zła suma" }
            val k = try { java.util.Base64.getDecoder().decode(m.key) } catch (e: Exception) { null }
            require(k != null && k.size == 32) { "zły klucz pliku" }
            return m
        }
    }
}

data class Message(
    val id: String, val chat: String, val sender: String, val ts: Long, val kind: String, val body: String,
    val media: MediaMeta?, val path: String?, val status: Int, val xmode: Int, val ttl: Int,
    val opened: Long, val expires: Long, val progress: Int, val mine: Boolean,
    val reply: Reply? = null,
    /** Czas ostatniej edycji (0 = nieedytowana). */
    val edited: Long = 0,
    val ex: Extra = Extra.NONE,
    /** Moja wiadomość, którą usunąłem u rozmówcy (u mnie została). */
    val delOthers: Boolean = false,
) {
    val hiddenUntilOpened get() = xmode == XMode.AFTER_OPEN && opened == 0L
}

/**
 * Dodatkowe informacje o wiadomości: przekazana dalej, wzmianki @, przynależność do albumu zdjęć.
 * W kopercie pola „fw”, „mn”, „al”, „ai”, „an”.
 */
data class Extra(
    val fwd: Boolean = false,
    val mentions: List<String> = emptyList(),
    val album: String? = null, val albumIdx: Int = 0, val albumN: Int = 0,
    /** Okrągła notatka wideo (kwadratowe wideo pokazywane w kółku). */
    val round: Boolean = false,
) {
    fun isEmpty() = this == NONE
    fun putInto(o: JSONObject): JSONObject {
        if (fwd) o.put("fw", 1)
        if (mentions.isNotEmpty()) o.put("mn", org.json.JSONArray(mentions))
        if (album != null) { o.put("al", album); o.put("ai", albumIdx); o.put("an", albumN) }
        if (round) o.put("vn", 1)
        return o
    }
    fun toJson(): String? = if (isEmpty()) null else putInto(JSONObject()).toString()

    companion object {
        val NONE = Extra()
        fun from(o: JSONObject?): Extra {
            if (o == null) return NONE
            val mn = o.optJSONArray("mn")
            val e = Extra(
                o.optInt("fw") == 1,
                if (mn == null) emptyList() else List(mn.length()) { mn.optString(it) }.filter { it.isNotEmpty() }.take(100),
                o.optString("al").take(32).ifEmpty { null }, o.optInt("ai").coerceIn(0, 99), o.optInt("an").coerceIn(0, 100),
                o.optInt("vn") == 1,
            )
            return if (e == NONE) NONE else e
        }
    }
}

data class Reaction(val msg: String, val pk: String, val emoji: String, val ts: Long)
data class Vote(val msg: String, val pk: String, val sel: List<Int>, val ts: Long)
data class Pin(val chat: String, val msg: String, val by: String, val ts: Long)
data class JoinReq(val gid: String, val pk: String, val name: String, val tok: String, val ts: Long)

/** Ankieta: pytanie, odpowiedzi, czy można wybrać kilka. */
data class Poll(val q: String, val opts: List<String>, val multi: Boolean) {
    fun toJson(): String = JSONObject().put("q", q).put("o", org.json.JSONArray(opts)).put("mul", multi).toString()

    companion object {
        fun parse(body: String): Poll? = try {
            val o = JSONObject(body)
            val a = o.getJSONArray("o")
            val opts = List(a.length()) { a.optString(it).take(100) }.filter { it.isNotBlank() }.take(12)
            if (opts.size < 2) null else Poll(o.optString("q").take(300), opts, o.optBoolean("mul"))
        } catch (e: Exception) { null }
    }
}

/** Lokalizacja: [live] = do kiedy udostępniana na żywo (ms, 0 = jednorazowa), [upd] = czas ostatniej aktualizacji. */
data class Loc(val lat: Double, val lon: Double, val acc: Float, val live: Long, val upd: Long, val ended: Boolean = false) {
    fun toJson(): String = JSONObject().put("lat", lat).put("lon", lon).put("acc", acc.toDouble()).put("live", live).put("upd", upd)
        .apply { if (ended) put("end", true) }.toString()
    fun isLive(now: Long) = live > 0 && !ended && now < live

    companion object {
        fun parse(body: String): Loc? = try {
            val o = JSONObject(body)
            val lat = o.getDouble("lat"); val lon = o.getDouble("lon")
            if (lat !in -90.0..90.0 || lon !in -180.0..180.0) null
            else Loc(lat, lon, o.optDouble("acc", 0.0).toFloat(), o.optLong("live"), o.optLong("upd"), o.optBoolean("end"))
        } catch (e: Exception) { null }
    }
}

/** Relacja (znika po 24 h). [kind] = Kind.TEXT albo Kind.IMAGE, [bg] = kolor tła relacji tekstowej. */
data class Story(
    val id: String, val pk: String, val ts: Long, val kind: String, val body: String, val media: MediaMeta?,
    val path: String?, val bg: Int, val seen: Boolean, val expires: Long,
)

/** Wiadomość zaplanowana do wysłania o [at]. Załącznik (JSON) jest już przygotowany w pamięci telefonu. */
data class Scheduled(
    val id: String, val chat: String, val at: Long, val kind: String, val body: String, val att: JSONObject?,
    val xmode: Int?, val ttl: Int?, val reply: String?, val created: Long,
)

/** Cytat wiadomości, na którą ktoś odpowiada. */
data class Reply(val id: String, val sender: String, val text: String, val kind: String) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("s", sender).put("t", text).put("k", kind)

    companion object {
        fun fromJson(o: JSONObject): Reply? {
            val id = o.optString("id"); val s = o.optString("s")
            if (id.isEmpty() || s.isEmpty()) return null
            return Reply(id.take(64), s.take(64), o.optString("t").take(200), o.optString("k", Kind.TEXT).take(10))
        }
    }
}

fun dmChatId(pk: String) = "d:$pk"
fun groupChatId(gid: String) = "g:$gid"
