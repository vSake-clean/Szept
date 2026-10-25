package pl.szept.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * Baza wiadomości. [encrypt] = true: zaszyfrowana SQLCipherem, gdy telefon to umożliwia
 * (stała `SQLiteDatabase.CONFLICT_*` mają te same wartości w obu silnikach).
 */
class Db(context: Context, name: String, encrypt: Boolean = false) : Schema {

    override val version = 9
    private val helper: DbHelper
    /** Czy baza jest zaszyfrowana w telefonie. */
    val encrypted: Boolean

    init {
        val (h, e) = DbCrypto.helper(context, name, this, encrypt)
        helper = h
        encrypted = e
    }

    /** Licznik zmian — UI odświeża się, gdy się zmieni. */
    val changes = MutableStateFlow(0L)
    private fun changed() { changes.value = changes.value + 1 }

    /** Wersja schematu zapisana w pliku. */
    val dbVersion: Int get() = helper.readable.version

    fun close() = helper.close()

    /**
     * Spójna kopia wszystkich danych do innej bazy (pustej, o tym samym schemacie) — na potrzeby kopii zapasowej.
     * Kopiowane są tylko kolumny istniejące w obu bazach.
     */
    /**
     * Kopiuje wszystkie wiersze do zwykłej bazy [path] (o tym samym schemacie), np. do kopii zapasowej.
     * Bez ATTACH na żywym połączeniu — ono w Androidzie wyłącza WAL i koliduje z trwającymi transakcjami.
     */
    fun copyInto(path: String) {
        val target = android.database.sqlite.SQLiteDatabase.openDatabase(path, null, android.database.sqlite.SQLiteDatabase.OPEN_READWRITE)
        try {
            fun targetColumns(t: String): Set<String> =
                target.rawQuery("PRAGMA table_info(\"$t\")", null).use { c -> buildSet { while (c.moveToNext()) add(c.getString(1)) } }
            val tables = q("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' AND name!='android_metadata'") { c ->
                buildList { while (c.moveToNext()) add(c.getString(0)) }
            }
            target.beginTransaction()
            try {
                for (t in tables) {
                    val theirs = targetColumns(t)
                    val cols = columns("main", t).filter { it in theirs }
                    if (cols.isEmpty()) continue
                    target.delete("\"$t\"", null, null)
                    q("SELECT " + cols.joinToString(",") { "\"$it\"" } + " FROM \"$t\"") { c ->
                        val cv = ContentValues()
                        while (c.moveToNext()) {
                            cv.clear()
                            for (i in cols.indices) when (c.getType(i)) {
                                Cursor.FIELD_TYPE_NULL -> cv.putNull(cols[i])
                                Cursor.FIELD_TYPE_INTEGER -> cv.put(cols[i], c.getLong(i))
                                Cursor.FIELD_TYPE_FLOAT -> cv.put(cols[i], c.getDouble(i))
                                Cursor.FIELD_TYPE_BLOB -> cv.put(cols[i], c.getBlob(i))
                                else -> cv.put(cols[i], c.getString(i))
                            }
                            target.insertWithOnConflict("\"$t\"", null, cv, android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE)
                        }
                    }
                }
                target.setTransactionSuccessful()
            } finally { target.endTransaction() }
        } finally {
            target.close()
        }
    }

    private fun columns(schema: String, table: String): List<String> =
        q("PRAGMA $schema.table_info(\"$table\")") { c -> buildList { while (c.moveToNext()) add(c.getString(1)) } }

    /** Po przywróceniu kopii na innym telefonie: ścieżki plików w bazie wskazują nowe katalogi. */
    fun fixPaths(oldMedia: String, newMedia: String, oldSched: String, newSched: String) {
        if (oldMedia.isNotEmpty() && oldMedia != newMedia) {
            for (t in listOf("messages", "stories")) {
                w.execSQL("UPDATE $t SET path = ? || substr(path, ?) WHERE path LIKE ?", arrayOf<Any?>(newMedia, oldMedia.length + 1, "$oldMedia%"))
            }
        }
        // niedokończone wysyłanie plików nie przenosi się między telefonami — można je ponowić
        w.execSQL("DELETE FROM uploads")
        w.execSQL("UPDATE messages SET status=-1 WHERE mine=1 AND status=1 AND id IN (SELECT msg FROM pending)")
        w.execSQL("DELETE FROM pending")
        if (oldSched.isNotEmpty() && oldSched != newSched) {
            val rows = scheduled()
            for (s in rows) {
                val p = s.att?.optString("path") ?: continue
                if (p.startsWith(oldSched)) addScheduled(s.copy(att = s.att.put("path", newSched + p.substring(oldSched.length))))
            }
        }
        changed()
    }

    override fun create(db: Sql) {
        db.execSQL("CREATE TABLE contacts(pk TEXT PRIMARY KEY, name TEXT NOT NULL, added INTEGER, is_contact INTEGER DEFAULT 0, blocked INTEGER DEFAULT 0, alias TEXT DEFAULT '', status TEXT DEFAULT '', bio TEXT DEFAULT '', avatar TEXT DEFAULT '', banner TEXT DEFAULT '', prof_ts INTEGER DEFAULT 0)")
        db.execSQL("CREATE TABLE chats(id TEXT PRIMARY KEY, is_group INTEGER, peer TEXT, name TEXT, last_ts INTEGER, last_text TEXT, unread INTEGER DEFAULT 0, xmode INTEGER DEFAULT 0, ttl INTEGER DEFAULT 10, muted INTEGER DEFAULT 0, pinned INTEGER DEFAULT 0, archived INTEGER DEFAULT 0, hidden INTEGER DEFAULT 0, draft TEXT DEFAULT '')")
        db.execSQL("CREATE TABLE grps(id TEXT PRIMARY KEY, name TEXT, ver INTEGER, admins TEXT, members TEXT, token TEXT, admins_only INTEGER, state INTEGER, removed TEXT DEFAULT '[]', icon TEXT DEFAULT '', color INTEGER DEFAULT 0, descr TEXT DEFAULT '', approve INTEGER DEFAULT 0, invites TEXT DEFAULT '[]')")
        db.execSQL("CREATE TABLE messages(id TEXT PRIMARY KEY, chat TEXT, sender TEXT, ts INTEGER, kind TEXT, body TEXT, media TEXT, path TEXT, status INTEGER, xmode INTEGER, ttl INTEGER, opened INTEGER DEFAULT 0, expires INTEGER DEFAULT 0, progress INTEGER DEFAULT 0, mine INTEGER, reply TEXT, edited INTEGER DEFAULT 0, extra TEXT)")
        db.execSQL("CREATE INDEX msg_chat ON messages(chat, ts)")
        db.execSQL("CREATE INDEX msg_exp ON messages(expires)")
        db.execSQL("CREATE TABLE tombstones(id TEXT PRIMARY KEY, by TEXT, ts INTEGER)")
        db.execSQL("CREATE TABLE seen(id TEXT PRIMARY KEY, ts INTEGER)")
        db.execSQL("CREATE TABLE outbox(id TEXT PRIMARY KEY, json TEXT, msg TEXT, ok TEXT DEFAULT '', created INTEGER)")
        db.execSQL("CREATE TABLE wraps(msg TEXT, recipient TEXT, event TEXT, PRIMARY KEY(msg, recipient, event))")
        db.execSQL("CREATE TABLE uploads(media TEXT PRIMARY KEY, msg TEXT, path TEXT, key TEXT, chunks INTEGER, created INTEGER, done TEXT DEFAULT '{}', ids TEXT DEFAULT '')")
        db.execSQL("CREATE TABLE downloads(media TEXT PRIMARY KEY, msg TEXT, meta TEXT, tries INTEGER DEFAULT 0, created INTEGER)")
        db.execSQL("CREATE TABLE pending(msg TEXT PRIMARY KEY, inner TEXT, recipients TEXT)")
        createV4Tables(db)
        createV5Tables(db)
        createV6(db)
        createV7(db)
        createV8(db)
        createV9(db)
    }

    /** v9: wyniki gier zapisane w tym telefonie (rekordy, tabela „Łącznie”). */
    private fun createV9(db: Sql) {
        db.execSQL("CREATE TABLE IF NOT EXISTS gscores(id INTEGER PRIMARY KEY AUTOINCREMENT, game TEXT, score REAL, ts INTEGER, ctx TEXT DEFAULT '', label TEXT DEFAULT '')")
        db.execSQL("CREATE INDEX IF NOT EXISTS gscores_game ON gscores(game, ts)")
    }

    /** Wersja 6: znaczek weryfikacji, oznaczenie „usunięte u rozmówcy”. */
    /** v8: ruchy w grach w czacie. */
    private fun createV8(db: Sql) {
        db.execSQL("CREATE TABLE IF NOT EXISTS gmoves(game TEXT, sender TEXT, seq INTEGER, mv TEXT, ts INTEGER, PRIMARY KEY(game, sender, seq))")
    }

    /** v7: moderacja w grupach (wyciszeni, zbanowani). */
    private fun createV7(db: Sql) {
        db.execSQL("ALTER TABLE grps ADD COLUMN moder TEXT DEFAULT '{}'")
    }

    private fun createV6(db: Sql) {
        db.execSQL("ALTER TABLE contacts ADD COLUMN verified INTEGER DEFAULT 0")
        db.execSQL("ALTER TABLE messages ADD COLUMN delothers INTEGER DEFAULT 0")
    }

    /** Wersja 5: klucze urządzeń (rotacja kluczy / drugie urządzenie). */
    private fun createV5Tables(db: Sql) {
        db.execSQL("CREATE TABLE IF NOT EXISTS prekeys(pk TEXT, dev TEXT, pub TEXT, ts INTEGER, PRIMARY KEY(pk, dev))")
    }

    /** Tabele dodane w wersji 4 (reakcje, ankiety, odczyty w grupach, przypięte, zaplanowane, relacje…). */
    private fun createV4Tables(db: Sql) {
        db.execSQL("CREATE TABLE IF NOT EXISTS reactions(msg TEXT, pk TEXT, emoji TEXT, ts INTEGER, PRIMARY KEY(msg, pk))")
        db.execSQL("CREATE TABLE IF NOT EXISTS votes(msg TEXT, pk TEXT, sel TEXT, ts INTEGER, PRIMARY KEY(msg, pk))")
        db.execSQL("CREATE TABLE IF NOT EXISTS reads(msg TEXT, pk TEXT, st INTEGER, ts INTEGER, PRIMARY KEY(msg, pk))")
        db.execSQL("CREATE TABLE IF NOT EXISTS pins(chat TEXT, msg TEXT, by TEXT, ts INTEGER, PRIMARY KEY(chat, msg))")
        db.execSQL("CREATE TABLE IF NOT EXISTS scheduled(id TEXT PRIMARY KEY, chat TEXT, at INTEGER, kind TEXT, body TEXT, att TEXT, xmode INTEGER, ttl INTEGER, reply TEXT, created INTEGER)")
        db.execSQL("CREATE TABLE IF NOT EXISTS stories(id TEXT PRIMARY KEY, pk TEXT, ts INTEGER, kind TEXT, body TEXT, media TEXT, path TEXT, bg INTEGER DEFAULT 0, seen INTEGER DEFAULT 0, expires INTEGER)")
        db.execSQL("CREATE TABLE IF NOT EXISTS story_views(story TEXT, pk TEXT, ts INTEGER, PRIMARY KEY(story, pk))")
        db.execSQL("CREATE TABLE IF NOT EXISTS joinreq(gid TEXT, pk TEXT, name TEXT, tok TEXT, ts INTEGER, PRIMARY KEY(gid, pk))")
        db.execSQL("CREATE TABLE IF NOT EXISTS live(msg TEXT PRIMARY KEY, chat TEXT, until INTEGER, last INTEGER DEFAULT 0, lat REAL DEFAULT 0, lon REAL DEFAULT 0)")
    }

    override fun upgrade(db: Sql, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE chats ADD COLUMN muted INTEGER DEFAULT 0")
            db.execSQL("ALTER TABLE grps ADD COLUMN removed TEXT DEFAULT '[]'")
            db.execSQL("ALTER TABLE messages ADD COLUMN reply TEXT")
        }
        if (oldVersion < 3) {
            for (col in listOf("alias", "status", "bio", "avatar", "banner")) db.execSQL("ALTER TABLE contacts ADD COLUMN $col TEXT DEFAULT ''")
            db.execSQL("ALTER TABLE contacts ADD COLUMN prof_ts INTEGER DEFAULT 0")
            db.execSQL("ALTER TABLE grps ADD COLUMN icon TEXT DEFAULT ''")
            db.execSQL("ALTER TABLE grps ADD COLUMN color INTEGER DEFAULT 0")
        }
        if (oldVersion < 4) {
            db.execSQL("ALTER TABLE messages ADD COLUMN edited INTEGER DEFAULT 0")
            db.execSQL("ALTER TABLE messages ADD COLUMN extra TEXT")
            for (col in listOf("pinned", "archived", "hidden")) db.execSQL("ALTER TABLE chats ADD COLUMN $col INTEGER DEFAULT 0")
            db.execSQL("ALTER TABLE chats ADD COLUMN draft TEXT DEFAULT ''")
            db.execSQL("ALTER TABLE grps ADD COLUMN descr TEXT DEFAULT ''")
            db.execSQL("ALTER TABLE grps ADD COLUMN approve INTEGER DEFAULT 0")
            db.execSQL("ALTER TABLE grps ADD COLUMN invites TEXT DEFAULT '[]'")
            createV4Tables(db)
        }
        if (oldVersion < 5) createV5Tables(db)
        if (oldVersion < 6) createV6(db)
        if (oldVersion < 7) createV7(db)
        if (oldVersion < 8) createV8(db)
        if (oldVersion < 9) createV9(db)
    }

    private val w: Sql get() = helper.writable
    private inline fun <T> q(sql: String, vararg args: Any?, block: (Cursor) -> T): T =
        helper.readable.rawQuery(sql, args.map { it?.toString() }.toTypedArray()).use(block)

    private fun Cursor.str(c: String): String? = getColumnIndex(c).let { if (it < 0 || isNull(it)) null else getString(it) }
    private fun Cursor.lng(c: String): Long = getColumnIndex(c).let { if (it < 0 || isNull(it)) 0L else getLong(it) }
    private fun Cursor.int(c: String): Int = lng(c).toInt()

    // ---------------- kontakty ----------------

    fun upsertContact(pk: String, name: String?, makeContact: Boolean) {
        val existing = contact(pk)
        if (existing == null) {
            w.insert("contacts", null, ContentValues().apply {
                put("pk", pk); put("name", name?.takeIf { it.isNotBlank() } ?: ("Użytkownik " + pk.take(6)))
                put("added", System.currentTimeMillis()); put("is_contact", if (makeContact) 1 else 0)
            })
        } else {
            val cv = ContentValues()
            if (!name.isNullOrBlank() && name != existing.name) cv.put("name", name)
            if (makeContact && !existing.isContact) cv.put("is_contact", 1)
            if (cv.size() == 0) return
            w.update("contacts", cv, "pk=?", arrayOf(pk))
            if (cv.containsKey("name")) w.execSQL("UPDATE chats SET name=? WHERE id=?", arrayOf<Any?>(name, dmChatId(pk)))
        }
        changed()
    }

    fun contact(pk: String): Contact? = q("SELECT * FROM contacts WHERE pk=?", pk) { c ->
        if (c.moveToFirst()) c.toContact() else null
    }

    fun contacts(onlyContacts: Boolean): List<Contact> =
        q("SELECT * FROM contacts" + (if (onlyContacts) " WHERE is_contact=1" else "") + " ORDER BY name COLLATE NOCASE") { c ->
            buildList { while (c.moveToNext()) add(c.toContact()) }
        }

    fun setBlocked(pk: String, blocked: Boolean) {
        w.execSQL("UPDATE contacts SET blocked=? WHERE pk=?", arrayOf<Any?>(if (blocked) 1 else 0, pk)); changed()
    }

    fun removeContact(pk: String) { w.execSQL("UPDATE contacts SET is_contact=0 WHERE pk=?", arrayOf<Any?>(pk)); changed() }

    private fun Cursor.toContact() = Contact(
        str("pk")!!, str("name") ?: "", int("is_contact") == 1, int("blocked") == 1,
        str("alias") ?: "", str("status") ?: "", str("bio") ?: "", str("avatar") ?: "", str("banner") ?: "", lng("prof_ts"),
        int("verified") == 1,
    )

    fun setVerified(pk: String, on: Boolean) {
        w.execSQL("UPDATE contacts SET verified=? WHERE pk=?", arrayOf<Any?>(if (on) 1 else 0, pk)); changed()
    }

    /** Moja wiadomość usunięta u rozmówcy (u mnie zostaje z oznaczeniem). */
    fun setDeletedForOthers(id: String) {
        w.execSQL("UPDATE messages SET delothers=1 WHERE id=?", arrayOf<Any?>(id)); changed()
    }

    fun nameOf(pk: String): String = contact(pk)?.display ?: ("Użytkownik " + pk.take(6))

    /** Zapisuje profil innej osoby (z sieci), jeśli nowszy; zachowuje mój lokalny alias. */
    fun saveProfile(pk: String, name: String?, status: String?, bio: String?, avatar: String?, banner: String?, ts: Long) {
        val existing = contact(pk)
        if (existing != null && ts > 0 && ts < existing.profTs) return
        if (existing == null) upsertContact(pk, name, false)
        val cv = ContentValues()
        if (!name.isNullOrBlank()) cv.put("name", name.take(60))
        if (status != null) cv.put("status", status.take(120))
        if (bio != null) cv.put("bio", bio.take(500))
        if (avatar != null) cv.put("avatar", avatar.take(60_000))
        if (banner != null) cv.put("banner", banner.take(160_000))
        if (ts > 0) cv.put("prof_ts", ts)
        if (cv.size() > 0) w.update("contacts", cv, "pk=?", arrayOf(pk))
        val c = contact(pk)
        if (c != null) w.execSQL("UPDATE chats SET name=? WHERE id=?", arrayOf<Any?>(c.display, dmChatId(pk)))
        changed()
    }

    /** Mój lokalny alias dla kontaktu (zmiana nazwy osoby u siebie). */
    fun setAlias(pk: String, alias: String) {
        w.execSQL("UPDATE contacts SET alias=? WHERE pk=?", arrayOf<Any?>(alias.trim().take(60), pk))
        contact(pk)?.let { w.execSQL("UPDATE chats SET name=? WHERE id=?", arrayOf<Any?>(it.display, dmChatId(pk))) }
        changed()
    }

    /**
     * Streak: liczba kolejnych dni (do dziś lub wczoraj), w których OBOJE wysłaliście
     * w tym czacie co najmniej jedną wiadomość.
     */
    fun streakDays(chatId: String): Int {
        val tz = java.util.TimeZone.getDefault().getOffset(System.currentTimeMillis())
        val mineDays = HashSet<Long>(); val theirsDays = HashSet<Long>()
        q("SELECT ts, mine FROM messages WHERE chat=? AND kind!='system'", chatId) { c ->
            while (c.moveToNext()) {
                val day = (c.getLong(0) + tz) / 86_400_000L
                if (c.getInt(1) == 1) mineDays.add(day) else theirsDays.add(day)
            }
        }
        val today = (System.currentTimeMillis() + tz) / 86_400_000L
        var start = when { both(mineDays, theirsDays, today) -> today; both(mineDays, theirsDays, today - 1) -> today - 1; else -> return 0 }
        var n = 0
        while (both(mineDays, theirsDays, start)) { n++; start-- }
        return n
    }

    private fun both(a: Set<Long>, b: Set<Long>, day: Long) = day in a && day in b

    // ---------------- czaty ----------------

    fun ensureChat(id: String, isGroup: Boolean, peer: String?, name: String) {
        val cv = ContentValues().apply {
            put("id", id); put("is_group", if (isGroup) 1 else 0); put("peer", peer); put("name", name)
            put("last_ts", System.currentTimeMillis()); put("last_text", "")
        }
        if (w.insertWithOnConflict("chats", null, cv, SQLiteDatabase.CONFLICT_IGNORE) != -1L) changed()
    }

    fun renameChat(id: String, name: String) { w.execSQL("UPDATE chats SET name=? WHERE id=?", arrayOf<Any?>(name, id)); changed() }

    fun chat(id: String): Chat? = q("SELECT * FROM chats WHERE id=?", id) { c -> if (c.moveToFirst()) c.toChat() else null }

    /** Czaty: najpierw przypięte, potem od najnowszej wiadomości. */
    fun chats(): List<Chat> = q("SELECT * FROM chats ORDER BY pinned DESC, last_ts DESC") { c -> buildList { while (c.moveToNext()) add(c.toChat()) } }

    private fun Cursor.toChat() = Chat(
        str("id")!!, int("is_group") == 1, str("peer"), str("name") ?: "", lng("last_ts"), str("last_text") ?: "",
        int("unread"), int("xmode"), int("ttl"), int("muted") == 1,
        lng("pinned"), int("archived") == 1, int("hidden") == 1, str("draft") ?: "",
    )

    /** Przypięcie czatu na górze listy. */
    fun setChatPinned(id: String, on: Boolean) {
        w.execSQL("UPDATE chats SET pinned=? WHERE id=?", arrayOf<Any?>(if (on) System.currentTimeMillis() else 0, id)); changed()
    }

    fun setArchived(id: String, on: Boolean) { w.execSQL("UPDATE chats SET archived=? WHERE id=?", arrayOf<Any?>(if (on) 1 else 0, id)); changed() }
    fun setHidden(id: String, on: Boolean) { w.execSQL("UPDATE chats SET hidden=? WHERE id=?", arrayOf<Any?>(if (on) 1 else 0, id)); changed() }

    /** Szkic zapisujemy bez powiadamiania UI (żeby pisanie nie odświeżało całej rozmowy). */
    fun setDraft(id: String, text: String) { w.execSQL("UPDATE chats SET draft=? WHERE id=?", arrayOf<Any?>(text.take(10_000), id)) }

    fun touchChat(id: String, ts: Long, text: String, incUnread: Boolean) {
        w.execSQL(
            "UPDATE chats SET last_ts=MAX(last_ts, ?), last_text=?, unread=unread+? WHERE id=?",
            arrayOf<Any?>(ts, text, if (incUnread) 1 else 0, id),
        )
        changed()
    }

    fun setMuted(id: String, muted: Boolean) {
        w.execSQL("UPDATE chats SET muted=? WHERE id=?", arrayOf<Any?>(if (muted) 1 else 0, id)); changed()
    }

    fun markChatRead(id: String) { w.execSQL("UPDATE chats SET unread=0 WHERE id=?", arrayOf<Any?>(id)); changed() }

    fun setChatTimer(id: String, xmode: Int, ttl: Int) {
        w.execSQL("UPDATE chats SET xmode=?, ttl=? WHERE id=?", arrayOf<Any?>(xmode, ttl, id)); changed()
    }

    fun deleteChat(id: String): List<Message> {
        val msgs = q("SELECT * FROM messages WHERE chat=?", id) { c -> buildList { while (c.moveToNext()) add(c.toMessage()) } }
        for (t in listOf("reactions", "votes", "reads")) w.execSQL("DELETE FROM $t WHERE msg IN (SELECT id FROM messages WHERE chat=?)", arrayOf<Any?>(id))
        w.delete("pins", "chat=?", arrayOf(id))
        w.delete("live", "chat=?", arrayOf(id))
        w.delete("scheduled", "chat=?", arrayOf(id))
        w.delete("messages", "chat=?", arrayOf(id))
        w.delete("chats", "id=?", arrayOf(id))
        changed()
        return msgs
    }

    fun refreshChatPreview(id: String) {
        val last = q("SELECT * FROM messages WHERE chat=? ORDER BY ts DESC LIMIT 1", id) { c -> if (c.moveToFirst()) c.toMessage() else null }
        w.execSQL("UPDATE chats SET last_text=? WHERE id=?", arrayOf<Any?>(last?.let { preview(it) } ?: "", id))
        changed()
    }

    // ---------------- grupy ----------------

    fun group(id: String): Group? = q("SELECT * FROM grps WHERE id=?", id) { c -> if (c.moveToFirst()) c.toGroup() else null }

    fun groups(): List<Group> = q("SELECT * FROM grps") { c -> buildList { while (c.moveToNext()) add(c.toGroup()) } }

    fun saveGroup(g: Group) {
        val members = JSONArray().apply { g.members.forEach { put(JSONObject().put("pk", it.pk).put("n", it.name)) } }
        w.insertWithOnConflict("grps", null, ContentValues().apply {
            put("id", g.id); put("name", g.name); put("ver", g.ver); put("admins", JSONArray(g.admins).toString())
            put("members", members.toString()); put("token", g.token); put("admins_only", if (g.adminsOnly) 1 else 0)
            put("state", g.state); put("removed", JSONArray(g.removed.take(500)).toString())
            put("icon", g.icon.take(60_000)); put("color", g.color)
            put("descr", g.descr.take(1000)); put("approve", if (g.approve) 1 else 0)
            put("invites", JSONArray().apply { g.invites.take(50).forEach { put(it.toJson()) } }.toString())
            put("moder", g.moderJson().toString())
        }, SQLiteDatabase.CONFLICT_REPLACE)
        changed()
    }

    private fun moderMuted(js: String?): Map<String, Long> = try {
        val o = JSONObject(js ?: "{}").optJSONObject("mu") ?: JSONObject()
        o.keys().asSequence().associateWith { o.optLong(it) }
    } catch (e: Exception) { emptyMap() }

    private fun moderBanned(js: String?): List<String> = try {
        val a = JSONObject(js ?: "{}").optJSONArray("bn") ?: JSONArray()
        List(a.length()) { a.optString(it) }
    } catch (e: Exception) { emptyList() }

    private fun Cursor.toGroup(): Group {
        val adm = JSONArray(str("admins") ?: "[]")
        val mem = JSONArray(str("members") ?: "[]")
        val rem = JSONArray(str("removed") ?: "[]")
        return Group(
            str("id")!!, str("name") ?: "", int("ver"),
            List(adm.length()) { adm.getString(it) },
            List(mem.length()) { mem.getJSONObject(it).let { o -> Member(o.getString("pk"), o.optString("n")) } },
            str("token") ?: "", int("admins_only") == 1, int("state"),
            List(rem.length()) { rem.getString(it) },
            str("icon") ?: "", int("color"),
            str("descr") ?: "", int("approve") == 1, Invite.listFrom(JSONArray(str("invites") ?: "[]")),
            moderMuted(str("moder")), moderBanned(str("moder")),
        )
    }

    // ---------------- prośby o dołączenie ----------------

    fun addJoinReq(r: JoinReq) {
        w.insertWithOnConflict("joinreq", null, ContentValues().apply {
            put("gid", r.gid); put("pk", r.pk); put("name", r.name.take(60)); put("tok", r.tok.take(40)); put("ts", r.ts)
        }, SQLiteDatabase.CONFLICT_REPLACE)
        changed()
    }

    fun joinReqs(gid: String): List<JoinReq> = q("SELECT * FROM joinreq WHERE gid=? ORDER BY ts", gid) { c ->
        buildList { while (c.moveToNext()) add(JoinReq(c.str("gid")!!, c.str("pk")!!, c.str("name") ?: "", c.str("tok") ?: "", c.lng("ts"))) }
    }

    fun removeJoinReq(gid: String, pk: String) { w.delete("joinreq", "gid=? AND pk=?", arrayOf(gid, pk)); changed() }

    // ---------------- wiadomości ----------------

    fun insertMessage(m: Message): Boolean {
        val cv = ContentValues().apply {
            put("id", m.id); put("chat", m.chat); put("sender", m.sender); put("ts", m.ts); put("kind", m.kind)
            put("body", m.body); put("media", m.media?.toJson()?.toString()); put("path", m.path); put("status", m.status)
            put("xmode", m.xmode); put("ttl", m.ttl); put("opened", m.opened); put("expires", m.expires)
            put("progress", m.progress); put("mine", if (m.mine) 1 else 0); put("reply", m.reply?.toJson()?.toString())
            put("edited", m.edited); put("extra", m.ex.toJson())
        }
        val ok = w.insertWithOnConflict("messages", null, cv, SQLiteDatabase.CONFLICT_IGNORE) != -1L
        if (ok) changed()
        return ok
    }

    // ---------------- gry ----------------

    data class GameMove(val sender: String, val seq: Int, val mv: String, val ts: Long)

    fun addGameMove(game: String, sender: String, seq: Int, mv: String, ts: Long): Boolean {
        val r = w.insertWithOnConflict("gmoves", null, ContentValues().apply {
            put("game", game); put("sender", sender); put("seq", seq); put("mv", mv.take(64)); put("ts", ts)
        }, SQLiteDatabase.CONFLICT_IGNORE)
        if (r != -1L) changed()
        return r != -1L
    }

    fun gameMoves(game: String): List<GameMove> = q("SELECT * FROM gmoves WHERE game=? ORDER BY ts, sender, seq", game) { c ->
        buildList { while (c.moveToNext()) add(GameMove(c.str("sender") ?: "", c.int("seq"), c.str("mv") ?: "", c.lng("ts"))) }
    }

    /** Ruchy wielu gier naraz (gra → ruchy), np. do tabeli „Łącznie” całej rozmowy. */
    fun gameMovesOf(games: Collection<String>): Map<String, List<GameMove>> {
        if (games.isEmpty()) return emptyMap()
        val out = HashMap<String, MutableList<GameMove>>()
        for (part in games.chunked(400)) {
            q("SELECT * FROM gmoves WHERE game IN (" + part.joinToString(",") { "?" } + ") ORDER BY ts, sender, seq", *part.toTypedArray()) { c ->
                while (c.moveToNext()) out.getOrPut(c.str("game") ?: "") { mutableListOf() }.add(GameMove(c.str("sender") ?: "", c.int("seq"), c.str("mv") ?: "", c.lng("ts")))
            }
        }
        return out
    }

    /** Wiadomości z grą danego rodzaju w rozmowie (od najstarszej). */
    fun gameMessages(chat: String, type: String, limit: Int = 500): List<Message> =
        q("SELECT * FROM messages WHERE chat=? AND extra LIKE ? ORDER BY ts DESC LIMIT $limit", chat, "%\"g\":\"$type\"%") { c ->
            buildList { while (c.moveToNext()) add(c.toMessage()) }
        }.filter { m -> m.ex.game?.let { try { JSONObject(it).optString("g") == type } catch (e: Exception) { false } } == true }.reversed()

    /** Wynik gry zapisany w tym telefonie. [ctx]: „solo”, „ai” albo id rozmowy (wyzwanie). */
    data class GameScore(val id: Long, val game: String, val score: Double, val ts: Long, val ctx: String, val label: String)

    fun addGameScore(game: String, score: Double, ctx: String, label: String = "", ts: Long = System.currentTimeMillis()) {
        if (score.isNaN() || score.isInfinite()) return
        w.insert("gscores", null, ContentValues().apply {
            put("game", game.take(64)); put("score", score); put("ts", ts); put("ctx", ctx.take(80)); put("label", label.take(80))
        })
        changed()
    }

    fun gameScores(game: String, limit: Int = 2000): List<GameScore> =
        q("SELECT * FROM gscores WHERE game=? ORDER BY ts DESC LIMIT $limit", game) { c -> buildList { while (c.moveToNext()) add(c.toScore()) } }

    /** Podsumowanie wszystkich gier: gra → (liczba wyników, ostatni czas). */
    fun gameScoreCounts(): Map<String, Pair<Int, Long>> =
        q("SELECT game, COUNT(*) AS n, MAX(ts) AS last FROM gscores GROUP BY game") { c ->
            buildMap { while (c.moveToNext()) put(c.str("game") ?: "", c.int("n") to c.lng("last")) }
        }

    fun clearGameScores(game: String) { w.delete("gscores", "game=?", arrayOf(game)); changed() }

    private fun Cursor.toScore() = GameScore(lng("id"), str("game") ?: "", getColumnIndex("score").let { if (it < 0 || isNull(it)) 0.0 else getDouble(it) },
        lng("ts"), str("ctx") ?: "", str("label") ?: "")

    /** Wiadomości starsze niż [before] (do automatycznego czyszczenia). */
    fun messagesBefore(chat: String, before: Long, limit: Int = 300): List<Message> =
        q("SELECT * FROM messages WHERE chat=? AND ts<? ORDER BY ts LIMIT $limit", chat, before) { c -> buildList { while (c.moveToNext()) add(c.toMessage()) } }

    fun message(id: String): Message? = q("SELECT * FROM messages WHERE id=?", id) { c -> if (c.moveToFirst()) c.toMessage() else null }

    fun messages(chat: String, limit: Int): List<Message> {
        val now = System.currentTimeMillis()
        return q("SELECT * FROM (SELECT * FROM messages WHERE chat=? AND (expires=0 OR expires>?) ORDER BY ts DESC LIMIT ?) ORDER BY ts ASC", chat, now, limit) { c ->
            buildList { while (c.moveToNext()) add(c.toMessage()) }
        }
    }

    fun unreadIncoming(chat: String): List<Message> =
        q("SELECT * FROM messages WHERE chat=? AND mine=0 AND status IN (10,11,12) AND opened=0 AND xmode!=2", chat) { c ->
            buildList { while (c.moveToNext()) add(c.toMessage()) }
        }

    fun updateStatus(id: String, status: Int, onlyIfLower: Boolean = true) {
        if (onlyIfLower) w.execSQL("UPDATE messages SET status=? WHERE id=? AND (status<? OR status=-1)", arrayOf<Any?>(status, id, status))
        else w.execSQL("UPDATE messages SET status=? WHERE id=?", arrayOf<Any?>(status, id))
        changed()
    }

    fun setPath(id: String, path: String?, status: Int) {
        w.execSQL("UPDATE messages SET path=?, status=?, progress=100 WHERE id=?", arrayOf<Any?>(path, status, id)); changed()
    }

    fun setProgress(id: String, p: Int) { w.execSQL("UPDATE messages SET progress=? WHERE id=?", arrayOf<Any?>(p, id)); changed() }

    fun setOpened(id: String, opened: Long, expires: Long) {
        w.execSQL("UPDATE messages SET opened=?, expires=? WHERE id=?", arrayOf<Any?>(opened, expires, id)); changed()
    }

    fun setExpires(id: String, expires: Long) { w.execSQL("UPDATE messages SET expires=? WHERE id=?", arrayOf<Any?>(expires, id)); changed() }

    /** Czyści treść (np. kopia nadawcy wiadomości jednorazowej). */
    fun clearContent(id: String) {
        w.execSQL("UPDATE messages SET body='', path=NULL WHERE id=?", arrayOf<Any?>(id)); changed()
    }

    fun markOpenedFlag(id: String) { w.execSQL("UPDATE messages SET opened=? WHERE id=? AND opened=0", arrayOf<Any?>(System.currentTimeMillis(), id)); changed() }

    fun deleteMessage(id: String): Message? {
        val m = message(id) ?: return null
        w.delete("messages", "id=?", arrayOf(id))
        for (t in listOf("reactions", "votes", "reads", "pins", "live")) w.delete(t, "msg=?", arrayOf(id))
        changed()
        return m
    }

    /** Edycja treści (tylko nowsza edycja wygrywa). */
    fun editMessage(id: String, body: String, ts: Long): Boolean {
        val cv = ContentValues().apply { put("body", body); put("edited", ts) }
        val n = w.update("messages", cv, "id=? AND edited<?", arrayOf(id, ts.toString()))
        if (n > 0) changed()
        return n > 0
    }

    /** Podmiana treści bez oznaczania edycji (np. aktualizacja lokalizacji na żywo). */
    fun setBody(id: String, body: String) { w.execSQL("UPDATE messages SET body=? WHERE id=?", arrayOf<Any?>(body, id)); changed() }

    /** Ile wiadomości w czacie jest nowszych niż podany czas (do przewinięcia do znalezionej wiadomości). */
    fun countNewer(chat: String, ts: Long): Int = q("SELECT COUNT(*) FROM messages WHERE chat=? AND ts>?", chat, ts) { c -> if (c.moveToFirst()) c.getInt(0) else 0 }

    /**
     * Wyszukiwanie w treści (bez wiadomości czasowych/znikających). Porównanie bez wielkości liter
     * robimy w Kotlinie — SQLite nie zna polskich znaków (Ł/ł).
     */
    fun search(query: String, chat: String? = null, limit: Int = 200): List<Message> {
        val needle = query.trim()
        if (needle.length < 2) return emptyList()
        val sql = "SELECT * FROM messages WHERE kind IN ('text','image','video','file','poll','voice') AND xmode=0" +
            (if (chat != null) " AND chat=?" else "") + " ORDER BY ts DESC"
        val out = ArrayList<Message>()
        val args = if (chat != null) arrayOf<Any?>(chat) else arrayOf()
        q(sql, *args) { c ->
            while (c.moveToNext() && out.size < limit) {
                val body = c.str("body") ?: ""
                val text = if (c.str("kind") == Kind.POLL) Poll.parse(body)?.let { it.q + " " + it.opts.joinToString(" ") } ?: "" else body
                val name = if (c.str("kind") == Kind.FILE) c.str("media")?.let { MediaMeta.fromJson(JSONObject(it)).name } ?: "" else ""
                if (text.contains(needle, true) || name.contains(needle, true)) out.add(c.toMessage())
            }
        }
        return out
    }

    /** Media czatu (do galerii): zdjęcia/wideo, pliki, a także wiadomości z linkami. */
    fun chatMedia(chat: String, kinds: List<String>): List<Message> {
        val inList = kinds.joinToString(",") { "'" + it.replace("'", "") + "'" }
        return q("SELECT * FROM messages WHERE chat=? AND kind IN ($inList) AND xmode=0 ORDER BY ts DESC LIMIT 2000", chat) { c ->
            buildList { while (c.moveToNext()) add(c.toMessage()) }
        }
    }

    // ---------------- reakcje ----------------

    /** Zapisuje reakcję (pusta = usunięcie); starsza od zapisanej jest ignorowana. */
    fun setReaction(msg: String, pk: String, emoji: String, ts: Long) {
        val cur = q("SELECT ts FROM reactions WHERE msg=? AND pk=?", msg, pk) { c -> if (c.moveToFirst()) c.getLong(0) else null }
        if (cur != null && cur > ts) return
        if (emoji.isEmpty()) {
            // zachowujemy znacznik czasu, żeby spóźniona starsza reakcja nie wróciła
            w.insertWithOnConflict("reactions", null, ContentValues().apply { put("msg", msg); put("pk", pk); put("emoji", ""); put("ts", ts) }, SQLiteDatabase.CONFLICT_REPLACE)
        } else {
            w.insertWithOnConflict("reactions", null, ContentValues().apply { put("msg", msg); put("pk", pk); put("emoji", emoji); put("ts", ts) }, SQLiteDatabase.CONFLICT_REPLACE)
        }
        changed()
    }

    fun reactionsForChat(chat: String): Map<String, List<Reaction>> =
        q("SELECT r.* FROM reactions r JOIN messages m ON m.id=r.msg WHERE m.chat=? AND r.emoji!='' ORDER BY r.ts", chat) { c ->
            val out = LinkedHashMap<String, MutableList<Reaction>>()
            while (c.moveToNext()) {
                val r = Reaction(c.str("msg")!!, c.str("pk")!!, c.str("emoji")!!, c.lng("ts"))
                out.getOrPut(r.msg) { mutableListOf() }.add(r)
            }
            out
        }

    fun reaction(msg: String, pk: String): Reaction? = q("SELECT * FROM reactions WHERE msg=? AND pk=? AND emoji!=''", msg, pk) { c ->
        if (c.moveToFirst()) Reaction(c.str("msg")!!, c.str("pk")!!, c.str("emoji")!!, c.lng("ts")) else null
    }

    // ---------------- ankiety ----------------

    fun setVote(msg: String, pk: String, sel: List<Int>, ts: Long) {
        val cur = q("SELECT ts FROM votes WHERE msg=? AND pk=?", msg, pk) { c -> if (c.moveToFirst()) c.getLong(0) else null }
        if (cur != null && cur > ts) return
        w.insertWithOnConflict("votes", null, ContentValues().apply {
            put("msg", msg); put("pk", pk); put("sel", sel.joinToString(",")); put("ts", ts)
        }, SQLiteDatabase.CONFLICT_REPLACE)
        changed()
    }

    fun votesForChat(chat: String): Map<String, List<Vote>> =
        q("SELECT v.* FROM votes v JOIN messages m ON m.id=v.msg WHERE m.chat=? AND v.sel!=''", chat) { c ->
            val out = HashMap<String, MutableList<Vote>>()
            while (c.moveToNext()) {
                val sel = (c.str("sel") ?: "").split(',').mapNotNull { it.toIntOrNull() }
                val v = Vote(c.str("msg")!!, c.str("pk")!!, sel, c.lng("ts"))
                out.getOrPut(v.msg) { mutableListOf() }.add(v)
            }
            out
        }

    // ---------------- odczyty w grupach ----------------

    /** Status odczytu przez członka grupy (tylko rośnie). Bez UPSERT — Android 8–10 ma starsze SQLite. */
    @Synchronized
    fun setRead(msg: String, pk: String, st: Int, ts: Long) {
        val cur = q("SELECT st FROM reads WHERE msg=? AND pk=?", msg, pk) { c -> if (c.moveToFirst()) c.getInt(0) else null }
        if (cur != null && cur >= st) return
        w.insertWithOnConflict("reads", null, ContentValues().apply { put("msg", msg); put("pk", pk); put("st", st); put("ts", ts) }, SQLiteDatabase.CONFLICT_REPLACE)
        changed()
    }

    /** pk → (status, czas) */
    fun reads(msg: String): Map<String, Pair<Int, Long>> = q("SELECT * FROM reads WHERE msg=?", msg) { c ->
        buildMap { while (c.moveToNext()) put(c.str("pk")!!, c.int("st") to c.lng("ts")) }
    }

    // ---------------- przypięte wiadomości ----------------

    fun setPin(chat: String, msg: String, by: String, on: Boolean, ts: Long) {
        if (on) w.insertWithOnConflict("pins", null, ContentValues().apply { put("chat", chat); put("msg", msg); put("by", by); put("ts", ts) }, SQLiteDatabase.CONFLICT_REPLACE)
        else w.delete("pins", "chat=? AND msg=?", arrayOf(chat, msg))
        changed()
    }

    fun pins(chat: String): List<Pin> = q("SELECT p.* FROM pins p JOIN messages m ON m.id=p.msg WHERE p.chat=? ORDER BY p.ts DESC", chat) { c ->
        buildList { while (c.moveToNext()) add(Pin(c.str("chat")!!, c.str("msg")!!, c.str("by") ?: "", c.lng("ts"))) }
    }

    // ---------------- lokalizacja na żywo (moje sesje) ----------------

    data class Live(val msg: String, val chat: String, val until: Long, val last: Long, val lat: Double, val lon: Double)

    fun addLive(msg: String, chat: String, until: Long) {
        w.insertWithOnConflict("live", null, ContentValues().apply { put("msg", msg); put("chat", chat); put("until", until) }, SQLiteDatabase.CONFLICT_REPLACE)
        changed()
    }

    fun lives(): List<Live> = q("SELECT * FROM live") { c ->
        buildList { while (c.moveToNext()) add(Live(c.str("msg")!!, c.str("chat")!!, c.lng("until"), c.lng("last"), c.getDouble(c.getColumnIndexOrThrow("lat")), c.getDouble(c.getColumnIndexOrThrow("lon")))) }
    }

    fun setLiveLast(msg: String, ts: Long, lat: Double, lon: Double) {
        w.execSQL("UPDATE live SET last=?, lat=?, lon=? WHERE msg=?", arrayOf<Any?>(ts, lat, lon, msg))
    }

    fun removeLive(msg: String) { w.delete("live", "msg=?", arrayOf(msg)); changed() }

    // ---------------- zaplanowane ----------------

    fun addScheduled(s: Scheduled) {
        w.insertWithOnConflict("scheduled", null, ContentValues().apply {
            put("id", s.id); put("chat", s.chat); put("at", s.at); put("kind", s.kind); put("body", s.body)
            put("att", s.att?.toString()); put("xmode", s.xmode); put("ttl", s.ttl); put("reply", s.reply); put("created", s.created)
        }, SQLiteDatabase.CONFLICT_REPLACE)
        changed()
    }

    fun scheduled(chat: String? = null): List<Scheduled> =
        q("SELECT * FROM scheduled" + (if (chat != null) " WHERE chat=?" else "") + " ORDER BY at", *(if (chat != null) arrayOf<Any?>(chat) else arrayOf())) { c ->
            buildList {
                while (c.moveToNext()) add(
                    Scheduled(
                        c.str("id")!!, c.str("chat")!!, c.lng("at"), c.str("kind")!!, c.str("body") ?: "",
                        c.str("att")?.let { JSONObject(it) }, c.getColumnIndex("xmode").let { if (c.isNull(it)) null else c.getInt(it) },
                        c.getColumnIndex("ttl").let { if (c.isNull(it)) null else c.getInt(it) }, c.str("reply"), c.lng("created"),
                    ),
                )
            }
        }

    fun removeScheduled(id: String) { w.delete("scheduled", "id=?", arrayOf(id)); changed() }
    fun setScheduledAt(id: String, at: Long) { w.execSQL("UPDATE scheduled SET at=? WHERE id=?", arrayOf<Any?>(at, id)); changed() }

    // ---------------- relacje (24 h) ----------------

    fun insertStory(s: Story): Boolean {
        val ok = w.insertWithOnConflict("stories", null, ContentValues().apply {
            put("id", s.id); put("pk", s.pk); put("ts", s.ts); put("kind", s.kind); put("body", s.body)
            put("media", s.media?.toJson()?.toString()); put("path", s.path); put("bg", s.bg); put("seen", if (s.seen) 1 else 0); put("expires", s.expires)
        }, SQLiteDatabase.CONFLICT_IGNORE) != -1L
        if (ok) changed()
        return ok
    }

    private fun Cursor.toStory() = Story(
        str("id")!!, str("pk")!!, lng("ts"), str("kind") ?: Kind.TEXT, str("body") ?: "",
        str("media")?.let { MediaMeta.fromJson(JSONObject(it)) }, str("path"), int("bg"), int("seen") == 1, lng("expires"),
    )

    fun story(id: String): Story? = q("SELECT * FROM stories WHERE id=?", id) { c -> if (c.moveToFirst()) c.toStory() else null }

    fun stories(now: Long = System.currentTimeMillis()): List<Story> = q("SELECT * FROM stories WHERE expires>? ORDER BY ts", now) { c ->
        buildList { while (c.moveToNext()) add(c.toStory()) }
    }

    fun setStoryPath(id: String, path: String?) { w.execSQL("UPDATE stories SET path=? WHERE id=?", arrayOf<Any?>(path, id)); changed() }
    fun setStorySeen(id: String) { w.execSQL("UPDATE stories SET seen=1 WHERE id=?", arrayOf<Any?>(id)); changed() }

    fun deleteStory(id: String): Story? {
        val s = story(id) ?: return null
        w.delete("stories", "id=?", arrayOf(id)); w.delete("story_views", "story=?", arrayOf(id))
        changed()
        return s
    }

    fun expiredStories(now: Long): List<Story> = q("SELECT * FROM stories WHERE expires<=?", now) { c ->
        buildList { while (c.moveToNext()) add(c.toStory()) }
    }

    fun addStoryView(story: String, pk: String, ts: Long) {
        w.insertWithOnConflict("story_views", null, ContentValues().apply { put("story", story); put("pk", pk); put("ts", ts) }, SQLiteDatabase.CONFLICT_IGNORE)
        changed()
    }

    fun storyViews(story: String): List<Pair<String, Long>> = q("SELECT pk, ts FROM story_views WHERE story=? ORDER BY ts DESC", story) { c ->
        buildList { while (c.moveToNext()) add(c.getString(0) to c.getLong(1)) }
    }

    fun expiredMessages(now: Long): List<Message> =
        q("SELECT * FROM messages WHERE expires>0 AND expires<=?", now) { c -> buildList { while (c.moveToNext()) add(c.toMessage()) } }

    fun nextExpiry(): Long = q("SELECT MIN(expires) AS e FROM messages WHERE expires>0") { c -> if (c.moveToFirst()) c.lng("e") else 0L }

    /** Najnowsza pobrana wiadomość z nową wersją aplikacji (do banera na ekranie głównym). */
    fun latestUpdate(): Message? = q("SELECT * FROM messages WHERE kind='update' AND path IS NOT NULL ORDER BY ts DESC LIMIT 1") { c ->
        if (c.moveToFirst()) c.toMessage() else null
    }

    fun allMediaPaths(): Set<String> = q("SELECT path FROM messages WHERE path IS NOT NULL UNION SELECT path FROM stories WHERE path IS NOT NULL") { c ->
        buildSet { while (c.moveToNext()) c.str("path")?.let { add(it) } }
    } + scheduled().mapNotNull { it.att?.optString("path")?.ifEmpty { null } }

    private fun Cursor.toMessage() = Message(
        str("id")!!, str("chat")!!, str("sender")!!, lng("ts"), str("kind")!!, str("body") ?: "",
        str("media")?.let { MediaMeta.fromJson(JSONObject(it)) }, str("path"), int("status"), int("xmode"), int("ttl"),
        lng("opened"), lng("expires"), int("progress"), int("mine") == 1,
        str("reply")?.let { Reply.fromJson(JSONObject(it)) },
        lng("edited"), Extra.from(str("extra")?.let { try { JSONObject(it) } catch (e: Exception) { null } }),
        int("delothers") == 1,
    )

    // ---------------- nagrobki / deduplikacja ----------------

    fun addTombstone(id: String, by: String) {
        w.insertWithOnConflict("tombstones", null, ContentValues().apply {
            put("id", id); put("by", by); put("ts", System.currentTimeMillis())
        }, SQLiteDatabase.CONFLICT_IGNORE)
    }

    fun tombstone(id: String): String? = q("SELECT by FROM tombstones WHERE id=?", id) { c -> if (c.moveToFirst()) c.getString(0) else null }

    /** Zwraca true, jeśli zdarzenie jest nowe. */
    fun markSeen(eventId: String): Boolean =
        w.insertWithOnConflict("seen", null, ContentValues().apply { put("id", eventId); put("ts", System.currentTimeMillis()) },
            SQLiteDatabase.CONFLICT_IGNORE) != -1L

    fun isSeen(eventId: String): Boolean = q("SELECT 1 FROM seen WHERE id=?", eventId) { it.moveToFirst() }

    fun prune() {
        val old = System.currentTimeMillis() - 40L * 86400_000
        w.delete("seen", "ts<?", arrayOf(old.toString()))
        w.delete("tombstones", "ts<?", arrayOf(old.toString()))
        // koperty żyją w przekaźnikach do 21 dni — kolejka trzyma je tyle samo (+1 dzień), wcześniej ginęły po 7
        w.delete("outbox", "created<?", arrayOf((System.currentTimeMillis() - 22L * 86400_000).toString()))
        w.delete("uploads", "created<?", arrayOf(((System.currentTimeMillis() - 22L * 86400_000) / 1000).toString()))
        w.delete("wraps", "msg NOT IN (SELECT id FROM messages) AND msg NOT LIKE 'story:%'", null)
        w.delete("reactions", "msg NOT IN (SELECT id FROM messages)", null)
        w.delete("votes", "msg NOT IN (SELECT id FROM messages)", null)
        w.delete("reads", "msg NOT IN (SELECT id FROM messages)", null)
        w.delete("joinreq", "ts<?", arrayOf(old.toString()))
    }

    // ---------------- skrzynka nadawcza ----------------

    /** Koperta w kolejce (bez treści — treść [outboxJson] wczytujemy dopiero przy wysyłce, bo bywa duża). */
    data class OutRow(val id: String, val msg: String?, val ok: Set<String>, val created: Long)

    fun addOutbox(eventId: String, json: String, msg: String?) {
        w.insertWithOnConflict("outbox", null, ContentValues().apply {
            put("id", eventId); put("json", json); put("msg", msg); put("created", System.currentTimeMillis())
        }, SQLiteDatabase.CONFLICT_IGNORE)
    }

    fun outbox(): List<OutRow> = q("SELECT id, msg, ok, created FROM outbox ORDER BY created") { c ->
        // odporne na uszkodzone wiersze (wcześniej `!!` wywracało aplikację)
        buildList {
            while (c.moveToNext()) {
                val id = c.str("id") ?: continue
                add(OutRow(id, c.str("msg"), (c.str("ok") ?: "").split(' ').filter { it.isNotEmpty() }.toSet(), c.lng("created")))
            }
        }
    }

    /** Treść koperty; null = brak (wiersz uszkodzony lub usunięty). */
    fun outboxJson(id: String): String? = try {
        q("SELECT json FROM outbox WHERE id=?", id) { c -> if (c.moveToFirst() && !c.isNull(0)) c.getString(0) else null }
    } catch (e: Exception) { null }

    fun outboxRow(id: String): OutRow? = outbox().firstOrNull { it.id == id }

    @Synchronized
    fun outboxAddRelay(id: String, entry: String): Set<String> {
        val cur = q("SELECT ok FROM outbox WHERE id=?", id) { c -> if (c.moveToFirst()) c.getString(0) else null } ?: return emptySet()
        val set = cur.split(' ').filter { it.isNotEmpty() }.toMutableSet()
        set.add(entry)
        w.execSQL("UPDATE outbox SET ok=? WHERE id=?", arrayOf<Any?>(set.joinToString(" "), id))
        return set
    }

    fun outboxClearRejected(msg: String) {
        outbox().filter { it.msg == msg }.forEach { row ->
            w.execSQL("UPDATE outbox SET ok=? WHERE id=?", arrayOf<Any?>(row.ok.filter { !it.startsWith("!") }.joinToString(" "), row.id))
        }
    }

    fun removeOutbox(id: String) { w.delete("outbox", "id=?", arrayOf(id)) }
    fun removeOutboxForMsg(msg: String) { w.delete("outbox", "msg=?", arrayOf(msg)) }

    fun msgWrapsOk(msg: String): Pair<Int, Int> = q("SELECT ok FROM outbox WHERE msg=?", msg) { c ->
        var total = 0; var ok = 0
        while (c.moveToNext()) { total++; if ((c.getString(0) ?: "").split(' ').any { it.isNotEmpty() && !it.startsWith("!") }) ok++ }
        total to ok
    }

    fun addWrap(msg: String, recipient: String, event: String) {
        w.insertWithOnConflict("wraps", null, ContentValues().apply { put("msg", msg); put("recipient", recipient); put("event", event) },
            SQLiteDatabase.CONFLICT_IGNORE)
    }

    fun wraps(msg: String): List<Pair<String, String>> = q("SELECT recipient, event FROM wraps WHERE msg=?", msg) { c ->
        buildList { while (c.moveToNext()) add(c.getString(0) to c.getString(1)) }
    }

    // ---------------- klucze urządzeń ----------------

    data class Prekey(val pk: String, val dev: String, val pub: String, val ts: Long)

    /** Zapisuje ogłoszony klucz urządzenia (tylko nowszy); pusty klucz = urządzenie bez rotacji (usuwa wpis). */
    @Synchronized
    fun setPrekey(pk: String, dev: String, pub: String, ts: Long): Boolean {
        val cur = q("SELECT ts FROM prekeys WHERE pk=? AND dev=?", pk, dev) { c -> if (c.moveToFirst()) c.getLong(0) else null }
        if (cur != null && cur >= ts) return false
        if (pub.isEmpty()) { w.delete("prekeys", "pk=? AND dev=?", arrayOf(pk, dev)); return true }
        w.insertWithOnConflict("prekeys", null, ContentValues().apply { put("pk", pk); put("dev", dev); put("pub", pub); put("ts", ts) },
            SQLiteDatabase.CONFLICT_REPLACE)
        return true
    }

    fun prekeys(pk: String): List<Prekey> = q("SELECT * FROM prekeys WHERE pk=?", pk) { c ->
        buildList { while (c.moveToNext()) add(Prekey(c.str("pk")!!, c.str("dev")!!, c.str("pub")!!, c.lng("ts"))) }
    }

    fun removePrekeys(pk: String) { w.delete("prekeys", "pk=?", arrayOf(pk)) }

    // ---------------- wysyłanie mediów ----------------

    data class Upload(val media: String, val msg: String, val path: String, val key: String, val chunks: Int, val created: Long, val done: JSONObject, val ids: String)

    fun addUpload(u: Upload) {
        w.insertWithOnConflict("uploads", null, ContentValues().apply {
            put("media", u.media); put("msg", u.msg); put("path", u.path); put("key", u.key); put("chunks", u.chunks)
            put("created", u.created); put("done", u.done.toString()); put("ids", u.ids)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun uploads(): List<Upload> = q("SELECT * FROM uploads") { c ->
        buildList { while (c.moveToNext()) add(Upload(c.str("media")!!, c.str("msg")!!, c.str("path")!!, c.str("key")!!, c.int("chunks"), c.lng("created"), JSONObject(c.str("done") ?: "{}"), c.str("ids") ?: "")) }
    }

    fun upload(media: String) = uploads().firstOrNull { it.media == media }
    fun uploadForMsg(msg: String) = uploads().firstOrNull { it.msg == msg }

    @Synchronized
    fun setUploadDone(media: String, relay: String, count: Int) {
        val u = upload(media) ?: return
        u.done.put(relay, count)
        w.execSQL("UPDATE uploads SET done=? WHERE media=?", arrayOf<Any?>(u.done.toString(), media))
    }

    fun setUploadIds(media: String, ids: String) { w.execSQL("UPDATE uploads SET ids=? WHERE media=?", arrayOf<Any?>(ids, media)) }
    fun removeUpload(media: String) { w.delete("uploads", "media=?", arrayOf(media)) }

    // ---------------- pobieranie mediów ----------------

    data class Download(val media: String, val msg: String, val meta: MediaMeta, val tries: Int, val created: Long)

    fun addDownload(msg: String, meta: MediaMeta) {
        w.insertWithOnConflict("downloads", null, ContentValues().apply {
            put("media", meta.id); put("msg", msg); put("meta", meta.toJson().toString()); put("created", System.currentTimeMillis())
        }, SQLiteDatabase.CONFLICT_IGNORE)
    }

    fun downloads(): List<Download> = q("SELECT * FROM downloads") { c ->
        buildList {
            while (c.moveToNext()) {
                // uszkodzony wpis pomijamy (zamiast wywracać aplikację)
                val d = try { Download(c.str("media")!!, c.str("msg")!!, MediaMeta.fromJson(JSONObject(c.str("meta")!!)), c.int("tries"), c.lng("created")) } catch (e: Exception) { null }
                if (d != null) add(d)
            }
        }
    }

    fun bumpDownload(media: String) { w.execSQL("UPDATE downloads SET tries=tries+1 WHERE media=?", arrayOf<Any?>(media)) }
    fun removeDownload(media: String) { w.delete("downloads", "media=?", arrayOf(media)) }
    fun removeDownloadForMsg(msg: String) { w.delete("downloads", "msg=?", arrayOf(msg)) }

    // ---------------- wiadomości czekające na upload ----------------

    fun addPending(msg: String, inner: String, recipients: List<String>) {
        w.insertWithOnConflict("pending", null, ContentValues().apply {
            put("msg", msg); put("inner", inner); put("recipients", recipients.joinToString(","))
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun pending(msg: String): Pair<String, List<String>>? = q("SELECT * FROM pending WHERE msg=?", msg) { c ->
        if (c.moveToFirst()) c.str("inner")!! to c.str("recipients")!!.split(',').filter { it.isNotEmpty() } else null
    }

    fun removePending(msg: String) { w.delete("pending", "msg=?", arrayOf(msg)) }

    fun hasPending(): Boolean = q("SELECT 1 FROM pending LIMIT 1") { it.moveToFirst() }

    companion object {
        /** „Wiadomość czasowa”, „Zdjęcie czasowe”… */
        fun timedLabel(kind: String) = when (kind) {
            Kind.IMAGE -> "Zdjęcie czasowe"
            Kind.VIDEO -> "Wideo czasowe"
            Kind.VOICE -> "Głosówka czasowa"
            Kind.FILE -> "Plik czasowy"
            else -> "Wiadomość czasowa"
        }

        private val spoilerRx = Regex("""\|\|(.+?)\|\|""")
        /** Spoiler (||tekst||) nie może wyciec w powiadomieniu ani na liście rozmów. */
        fun hideSpoilers(t: String) = t.replace(spoilerRx) { "▒".repeat(it.groupValues[1].length.coerceIn(3, 8)) }

        fun preview(m: Message): String = when {
            // treści czasowych/znikających nie pokazujemy na liście czatów ani w powiadomieniach
            m.xmode == XMode.AFTER_OPEN -> "⏱ " + timedLabel(m.kind)
            m.xmode == XMode.AFTER_SEND -> "⏱ Wiadomość znikająca"
            m.kind == Kind.TEXT && m.ex.game != null -> m.body.substringBefore(" — ")
            m.kind == Kind.TEXT -> hideSpoilers(m.body)
            m.kind == Kind.IMAGE -> "📷 Zdjęcie" + if (m.body.isNotEmpty()) ": " + hideSpoilers(m.body) else ""
            m.kind == Kind.VOICE -> "🎤 Wiadomość głosowa"
            m.kind == Kind.VIDEO -> "🎬 Wideo" + if (m.body.isNotEmpty()) ": " + m.body else ""
            m.kind == Kind.FILE -> "📎 " + (m.media?.name ?: "Plik")
            m.kind == Kind.POLL -> "📊 " + (Poll.parse(m.body)?.q?.ifBlank { null } ?: "Ankieta")
            m.kind == Kind.LOCATION -> if ((Loc.parse(m.body)?.live ?: 0L) > 0) "📍 Lokalizacja na żywo" else "📍 Lokalizacja"
            m.kind == Kind.UPDATE -> "⬆️ Nowa wersja Szept ${m.body}"
            else -> m.body
        }
    }
}
