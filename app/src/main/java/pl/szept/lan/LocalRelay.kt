package pl.szept.lan

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import pl.szept.nostr.Clock
import pl.szept.nostr.Event
import java.util.concurrent.ConcurrentHashMap

/**
 * Przekaźnik Nostr działający w telefonie (sieć lokalna, bez internetu).
 * Przechowuje koperty (kind 1059), paczki mediów (4334) i prośby o usunięcie (5), aż odbiorca się pojawi.
 * Treść kopert jest zaszyfrowana end-to-end — telefon-przekaźnik jej nie zna.
 */
class LocalRelay(ctx: Context, dbName: String = "lan_relay.db", private val maxBytes: Long = 200L * 1024 * 1024) : WsServer.Handler {

    companion object {
        const val DEFAULT_PORT = 4869
        val KINDS = setOf(1059, 4334, 5)
        const val MAX_EVENT = 300_000
        const val MAX_LIMIT = 1000
        const val MAX_SUBS = 30
        /** Skąd przyszło zdarzenie. */
        const val ORIGIN_SELF = 0      // z tego telefonu
        const val ORIGIN_LAN = 1       // od innego telefonu w sieci lokalnej
        const val ORIGIN_COURIER = 2   // pobrane z internetu dla znajomych (tryb kuriera)
    }

    private class Helper(ctx: Context, name: String) : SQLiteOpenHelper(ctx, name, null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE ev(id TEXT PRIMARY KEY, pubkey TEXT NOT NULL, kind INTEGER NOT NULL, ca INTEGER NOT NULL, " +
                    "exp INTEGER NOT NULL DEFAULT 0, p TEXT, x TEXT, json TEXT NOT NULL, sz INTEGER NOT NULL, " +
                    "origin INTEGER NOT NULL DEFAULT 0, fwd INTEGER NOT NULL DEFAULT 0, rcv INTEGER NOT NULL)",
            )
            db.execSQL("CREATE INDEX ev_p ON ev(p, ca)")
            db.execSQL("CREATE INDEX ev_x ON ev(x)")
            db.execSQL("CREATE INDEX ev_fwd ON ev(fwd, origin)")
            db.execSQL("CREATE INDEX ev_rcv ON ev(rcv)")
        }
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}
    }

    private val helper = Helper(ctx.applicationContext, dbName)
    private val db: SQLiteDatabase get() = helper.writableDatabase
    private val subs = ConcurrentHashMap<Int, ConcurrentHashMap<String, List<JSONObject>>>()
    private val clients = ConcurrentHashMap<Int, WsServer.Conn>()
    private var server: WsServer? = null
    private var lastPrune = 0L
    /** Tajny znacznik w adresie, którym łączy się ten sam telefon (jego koperty nie wymagają przekazywania dalej). */
    val selfToken: String = pl.szept.crypto.Crypto.randomBytes(12).let { b -> b.joinToString("") { "%02x".format(it) } }
    val selfPath: String get() = "/?self=$selfToken"
    private fun isSelf(c: WsServer.Conn) = c.path == selfPath

    /** Wywoływane po zapisaniu nowego zdarzenia (np. do odświeżenia licznika w interfejsie). */
    var onStored: ((origin: Int) -> Unit)? = null

    val port: Int get() = server?.port ?: 0
    val clientCount: Int get() = clients.values.count { !isSelf(it) }
    val isRunning: Boolean get() = server != null

    @Synchronized
    fun start(port: Int = DEFAULT_PORT): Int {
        server?.let { return it.port }
        val s = WsServer(port, this)
        val p = s.start()
        server = s
        prune(force = true)
        return p
    }

    @Synchronized
    fun stop() {
        server?.stop(); server = null
        subs.clear(); clients.clear()
    }

    fun close() { stop(); helper.close() }

    // ---------------- WebSocket ----------------

    override fun onOpen(c: WsServer.Conn) { clients[c.id] = c }

    override fun onClose(c: WsServer.Conn) { clients.remove(c.id); subs.remove(c.id) }

    override fun onHttp(path: String, accept: String?): Pair<String, String>? =
        if (accept?.contains("application/nostr+json") == true) "application/nostr+json" to JSONObject()
            .put("name", "Szept (sieć lokalna)").put("description", "Przekaźnik w telefonie — tylko zaszyfrowane koperty Szept")
            .put("supported_nips", JSONArray(listOf(1, 9, 11, 40))).put("software", "szept").toString()
        else null

    override fun onText(c: WsServer.Conn, text: String) {
        val arr = try { JSONArray(text) } catch (e: Exception) { notice(c, "invalid: zły JSON"); return }
        when (arr.optString(0)) {
            "EVENT" -> {
                val o = arr.optJSONObject(1) ?: return
                val (ok, msg) = accept(o, if (isSelf(c)) ORIGIN_SELF else ORIGIN_LAN)
                c.send(JSONArray().put("OK").put(o.optString("id")).put(ok).put(msg).toString())
            }
            "REQ" -> {
                val subId = arr.optString(1)
                if (subId.isEmpty() || subId.length > 64) { notice(c, "invalid: subscription id"); return }
                val filters = (2 until arr.length()).mapNotNull { arr.optJSONObject(it) }
                val mine = subs.getOrPut(c.id) { ConcurrentHashMap() }
                if (mine.size >= MAX_SUBS && !mine.containsKey(subId)) {
                    c.send(JSONArray().put("CLOSED").put(subId).put("error: za dużo subskrypcji").toString()); return
                }
                mine[subId] = filters
                val sent = HashSet<String>()
                for (f in filters) for (json in query(f)) {
                    val id = JSONObject(json).optString("id")
                    if (sent.add(id)) c.send("[\"EVENT\"," + JSONObject.quote(subId) + "," + json + "]")
                }
                c.send(JSONArray().put("EOSE").put(subId).toString())
            }
            "CLOSE" -> subs[c.id]?.remove(arr.optString(1))
            else -> notice(c, "error: nieobsługiwane")
        }
    }

    private fun notice(c: WsServer.Conn, msg: String) { c.send(JSONArray().put("NOTICE").put(msg).toString()) }

    // ---------------- magazyn ----------------

    /** Przyjmuje zdarzenie (z sieci albo od kuriera). Zwraca (sukces, komunikat) jak przekaźnik Nostr. */
    fun accept(o: JSONObject, origin: Int): Pair<Boolean, String> {
        val ev = try { Event.fromJson(o) } catch (e: Exception) { return false to "invalid: złe zdarzenie" }
        val policy = ev.kind == pl.szept.core.Admin.POLICY_KIND && ev.pubkey == pl.szept.core.Admin.PUB
        if (ev.kind !in KINDS && !policy) return false to "blocked: ten przekaźnik przyjmuje tylko koperty Szept"
        val json = ev.toJson()
        if (json.length > MAX_EVENT) return false to "invalid: za duże"
        val now = Clock.nowSec()
        val exp = ev.tag("expiration")?.toLongOrNull() ?: 0L
        if (exp in 1..now) return false to "invalid: wygasło"
        if (ev.createdAt > now + 3600) return false to "invalid: data z przyszłości"
        synchronized(this) { if (has(ev.id)) return true to "duplicate: już jest" }
        if (!ev.verify()) return false to "invalid: zły podpis"
        synchronized(this) {
            if (has(ev.id)) return true to "duplicate: już jest"
            if (policy) {
                // zdarzenie zastępowalne: zostaje tylko najnowsza polityka wersji
                val newer = db.rawQuery("SELECT 1 FROM ev WHERE pubkey=? AND kind=? AND ca>=?", arrayOf(ev.pubkey, ev.kind.toString(), ev.createdAt.toString())).use { it.moveToFirst() }
                if (newer) return true to "duplicate: jest nowsza"
                db.delete("ev", "pubkey=? AND kind=?", arrayOf(ev.pubkey, ev.kind.toString()))
            }
            if (ev.kind == 5) {
                // NIP-09: autor usuwa swoje zdarzenia
                val ids = ev.tags.filter { it.size >= 2 && it[0] == "e" }.map { it[1] }
                for (chunk in ids.chunked(200)) {
                    if (chunk.isEmpty()) continue
                    db.delete("ev", "pubkey=? AND id IN (" + chunk.joinToString(",") { "?" } + ")", arrayOf(ev.pubkey) + chunk)
                }
            }
            val cv = ContentValues().apply {
                put("id", ev.id); put("pubkey", ev.pubkey); put("kind", ev.kind); put("ca", ev.createdAt)
                put("exp", if (exp > 0) exp else ev.createdAt + (if (policy) 3650L else 22L) * 86400L)
                put("p", ev.tag("p")); put("x", ev.tag("x")); put("json", json); put("sz", json.length)
                put("origin", origin); put("fwd", if (origin == ORIGIN_LAN) 0 else 1); put("rcv", System.currentTimeMillis())
            }
            db.insertWithOnConflict("ev", null, cv, SQLiteDatabase.CONFLICT_IGNORE)
        }
        broadcast(JSONObject(json), json)
        onStored?.invoke(origin)
        if (System.currentTimeMillis() - lastPrune > 10 * 60_000) prune()
        return true to ""
    }

    private fun has(id: String): Boolean =
        db.rawQuery("SELECT 1 FROM ev WHERE id=?", arrayOf(id)).use { it.moveToFirst() }

    private fun broadcast(o: JSONObject, json: String) {
        for ((connId, map) in subs) {
            val c = clients[connId] ?: continue
            for ((subId, filters) in map) {
                if (filters.any { matches(it, o) }) c.send("[\"EVENT\"," + JSONObject.quote(subId) + "," + json + "]")
            }
        }
    }

    /** Wyniki zapytania (najnowsze najpierw), jako gotowe JSON-y. */
    @Synchronized
    fun query(f: JSONObject): List<String> {
        val where = ArrayList<String>()
        val args = ArrayList<String>()
        fun inList(col: String, arr: JSONArray?) {
            if (arr == null) return
            val vals = List(arr.length()) { arr.optString(it) }.filter { it.isNotEmpty() }.take(500)
            if (vals.isEmpty()) { where.add("0"); return }
            where.add("$col IN (" + vals.joinToString(",") { "?" } + ")"); args.addAll(vals)
        }
        inList("id", f.optJSONArray("ids"))
        inList("pubkey", f.optJSONArray("authors"))
        f.optJSONArray("kinds")?.let { k ->
            val vals = List(k.length()) { k.optInt(it, -1) }.filter { it >= 0 }
            if (vals.isEmpty()) where.add("0") else where.add("kind IN (" + vals.joinToString(",") + ")")
        }
        inList("p", f.optJSONArray("#p"))
        inList("x", f.optJSONArray("#x"))
        if (f.has("since")) { where.add("ca >= ?"); args.add(f.optLong("since").toString()) }
        if (f.has("until")) { where.add("ca <= ?"); args.add(f.optLong("until").toString()) }
        where.add("exp > ?"); args.add(Clock.nowSec().toString())
        val limit = f.optInt("limit", 500).coerceIn(0, MAX_LIMIT)
        if (limit == 0) return emptyList()
        // pozostałe tagi (#e, #k…) sprawdzamy po odczycie
        val extraTags = f.keys().asSequence().filter { it.startsWith("#") && it != "#p" && it != "#x" }.toList()
        val out = ArrayList<String>()
        db.rawQuery(
            "SELECT json FROM ev WHERE " + where.joinToString(" AND ") + " ORDER BY ca DESC LIMIT " + (if (extraTags.isEmpty()) limit else limit * 4),
            args.toTypedArray(),
        ).use { c ->
            while (c.moveToNext() && out.size < limit) {
                val json = c.getString(0)
                if (extraTags.isEmpty() || matches(f, JSONObject(json))) out.add(json)
            }
        }
        return out
    }

    /** Dopasowanie filtra NIP-01 do zdarzenia. */
    fun matches(f: JSONObject, ev: JSONObject): Boolean {
        fun inArr(key: String, v: String): Boolean {
            val a = f.optJSONArray(key) ?: return true
            for (i in 0 until a.length()) if (a.optString(i) == v) return true
            return false
        }
        if (!inArr("ids", ev.optString("id"))) return false
        if (!inArr("authors", ev.optString("pubkey"))) return false
        f.optJSONArray("kinds")?.let { k ->
            val kind = ev.optInt("kind")
            if ((0 until k.length()).none { k.optInt(it, -1) == kind }) return false
        }
        val ca = ev.optLong("created_at")
        if (f.has("since") && ca < f.optLong("since")) return false
        if (f.has("until") && ca > f.optLong("until")) return false
        val tags = ev.optJSONArray("tags") ?: JSONArray()
        for (key in f.keys()) {
            if (!key.startsWith("#") || key.length != 2) continue
            val want = f.optJSONArray(key) ?: continue
            val name = key.substring(1)
            val found = (0 until tags.length()).any { i ->
                val t = tags.optJSONArray(i)
                t != null && t.length() >= 2 && t.optString(0) == name && (0 until want.length()).any { want.optString(it) == t.optString(1) }
            }
            if (!found) return false
        }
        return true
    }

    // ---------------- kurier i porządki ----------------

    /** Zdarzenia od innych telefonów, jeszcze niewysłane do internetu (dla kuriera). */
    @Synchronized
    fun pendingForward(limit: Int = 200): List<String> =
        db.rawQuery("SELECT json FROM ev WHERE fwd=0 AND exp > ? ORDER BY rcv LIMIT $limit", arrayOf(Clock.nowSec().toString())).use { c ->
            buildList { while (c.moveToNext()) add(c.getString(0)) }
        }

    @Synchronized
    fun markForwarded(id: String) { db.execSQL("UPDATE ev SET fwd=1 WHERE id=?", arrayOf(id)) }

    @Synchronized
    fun count(origin: Int? = null): Int =
        db.rawQuery(if (origin == null) "SELECT count(*) FROM ev" else "SELECT count(*) FROM ev WHERE origin=$origin", null)
            .use { if (it.moveToFirst()) it.getInt(0) else 0 }

    @Synchronized
    fun sizeBytes(): Long = db.rawQuery("SELECT coalesce(sum(sz),0) FROM ev", null).use { if (it.moveToFirst()) it.getLong(0) else 0 }

    @Synchronized
    fun clear() { db.delete("ev", null, null) }

    /** Usuwa wygasłe zdarzenia i najstarsze, gdy magazyn przekroczy limit. */
    @Synchronized
    fun prune(force: Boolean = false) {
        if (!force && System.currentTimeMillis() - lastPrune < 60_000) return
        lastPrune = System.currentTimeMillis()
        try {
            db.delete("ev", "exp <= ?", arrayOf(Clock.nowSec().toString()))
            var total = sizeBytes()
            while (total > maxBytes) {
                val n = db.delete("ev", "id IN (SELECT id FROM ev ORDER BY rcv LIMIT 200)", null)
                if (n == 0) break
                total = sizeBytes()
            }
        } catch (e: Exception) { Log.w("Szept", "lan prune", e) }
    }
}
