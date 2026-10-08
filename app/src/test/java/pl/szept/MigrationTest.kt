package pl.szept

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import pl.szept.data.Db
import pl.szept.data.Reply

/** Aktualizacja z wersji 1.0: stara baza musi się otworzyć i zachować dane. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class MigrationTest {
    @Test
    fun upgradeFromV1() {
        val ctx = RuntimeEnvironment.getApplication()
        val name = "mig_${System.nanoTime()}.db"
        val file = ctx.getDatabasePath(name).apply { parentFile?.mkdirs() }
        // schemat dokładnie jak w wersji 1.0
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.execSQL("CREATE TABLE contacts(pk TEXT PRIMARY KEY, name TEXT NOT NULL, added INTEGER, is_contact INTEGER DEFAULT 0, blocked INTEGER DEFAULT 0)")
            db.execSQL("CREATE TABLE chats(id TEXT PRIMARY KEY, is_group INTEGER, peer TEXT, name TEXT, last_ts INTEGER, last_text TEXT, unread INTEGER DEFAULT 0, xmode INTEGER DEFAULT 0, ttl INTEGER DEFAULT 10)")
            db.execSQL("CREATE TABLE grps(id TEXT PRIMARY KEY, name TEXT, ver INTEGER, admins TEXT, members TEXT, token TEXT, admins_only INTEGER, state INTEGER)")
            db.execSQL("CREATE TABLE messages(id TEXT PRIMARY KEY, chat TEXT, sender TEXT, ts INTEGER, kind TEXT, body TEXT, media TEXT, path TEXT, status INTEGER, xmode INTEGER, ttl INTEGER, opened INTEGER DEFAULT 0, expires INTEGER DEFAULT 0, progress INTEGER DEFAULT 0, mine INTEGER)")
            db.execSQL("CREATE INDEX msg_chat ON messages(chat, ts)")
            db.execSQL("CREATE INDEX msg_exp ON messages(expires)")
            db.execSQL("CREATE TABLE tombstones(id TEXT PRIMARY KEY, by TEXT, ts INTEGER)")
            db.execSQL("CREATE TABLE seen(id TEXT PRIMARY KEY, ts INTEGER)")
            db.execSQL("CREATE TABLE outbox(id TEXT PRIMARY KEY, json TEXT, msg TEXT, ok TEXT DEFAULT '', created INTEGER)")
            db.execSQL("CREATE TABLE wraps(msg TEXT, recipient TEXT, event TEXT, PRIMARY KEY(msg, recipient, event))")
            db.execSQL("CREATE TABLE uploads(media TEXT PRIMARY KEY, msg TEXT, path TEXT, key TEXT, chunks INTEGER, created INTEGER, done TEXT DEFAULT '{}', ids TEXT DEFAULT '')")
            db.execSQL("CREATE TABLE downloads(media TEXT PRIMARY KEY, msg TEXT, meta TEXT, tries INTEGER DEFAULT 0, created INTEGER)")
            db.execSQL("CREATE TABLE pending(msg TEXT PRIMARY KEY, inner TEXT, recipients TEXT)")
            db.execSQL("INSERT INTO contacts VALUES('${"a".repeat(64)}','Ala',1,1,0)")
            db.execSQL("INSERT INTO chats VALUES('d:${"a".repeat(64)}',0,'${"a".repeat(64)}','Ala',1000,'cześć',2,0,10)")
            db.execSQL("INSERT INTO grps VALUES('g1','Ekipa',3,'[\"${"a".repeat(64)}\"]','[{\"pk\":\"${"a".repeat(64)}\",\"n\":\"Ala\"}]','tok',0,0)")
            db.execSQL("INSERT INTO messages(id,chat,sender,ts,kind,body,status,xmode,ttl,mine) VALUES('m1','d:${"a".repeat(64)}','${"a".repeat(64)}',1000,'text','cześć',10,0,10,0)")
            db.version = 1
        }
        val db = Db(ctx, name)
        val chat = db.chat("d:${"a".repeat(64)}")
        assertNotNull(chat); assertEquals(2, chat!!.unread); assertFalse(chat.muted)
        assertEquals("", db.contact("${"a".repeat(64)}")?.status)
        val g = db.group("g1")!!
        assertEquals("Ekipa", g.name); assertTrue(g.removed.isEmpty()); assertEquals(1, g.members.size)
        val m = db.message("m1")!!
        assertEquals("cześć", m.body); assertNull(m.reply)
        // nowe kolumny działają po migracji
        db.setMuted(chat.id, true)
        assertTrue(db.chat(chat.id)!!.muted)
        db.saveGroup(g.copy(removed = listOf("b".repeat(64))))
        assertEquals(listOf("b".repeat(64)), db.group("g1")!!.removed)
        db.insertMessage(m.copy(id = "m2", reply = Reply("m1", m.sender, "cześć", "text")))
        assertEquals("m1", db.message("m2")!!.reply!!.id)
        assertEquals(7, db.dbVersion)
        // v7: moderacja w grupach — zapis i odczyt
        val g0 = db.group("g1")!!
        db.saveGroup(g0.copy(muted = mapOf("c".repeat(64) to Long.MAX_VALUE), banned = listOf("b".repeat(64))))
        assertEquals(listOf("b".repeat(64)), db.group("g1")!!.banned)
        assertTrue(db.group("g1")!!.isMuted("c".repeat(64), System.currentTimeMillis()))
        // wersja 4: przypięte/archiwum/szkice, opis grupy, reakcje, ankiety, relacje
        val c4 = db.chat(chat.id)!!
        assertEquals(0L, c4.pinned); assertFalse(c4.archived); assertFalse(c4.hidden); assertEquals("", c4.draft)
        db.setChatPinned(chat.id, true); db.setArchived(chat.id, true); db.setDraft(chat.id, "szkic")
        db.chat(chat.id)!!.let { assertTrue(it.pinned > 0); assertTrue(it.archived); assertEquals("szkic", it.draft) }
        assertEquals("", db.group("g1")!!.descr); assertFalse(db.group("g1")!!.approve)
        db.setReaction("m1", "b".repeat(64), "👍", 10)
        assertEquals("👍", db.reactionsForChat(chat.id)["m1"]!!.single().emoji)
        assertEquals(0L, db.message("m1")!!.edited)
        assertTrue(db.editMessage("m1", "cześć!", 5))
        assertEquals("cześć!", db.message("m1")!!.body)
        assertTrue(db.stories().isEmpty())
        // wersja 5: klucze urządzeń
        val pk = "c".repeat(64)
        assertTrue(db.setPrekey(pk, "dev1", "d".repeat(64), 100))
        assertFalse("starsze ogłoszenie nie nadpisuje", db.setPrekey(pk, "dev1", "e".repeat(64), 50))
        assertEquals("d".repeat(64), db.prekeys(pk).single().pub)
        assertTrue(db.setPrekey(pk, "dev1", "", 200))
        assertTrue(db.prekeys(pk).isEmpty())
        // wersja 6: znaczek weryfikacji, „usunięte u rozmówcy”
        db.setVerified("${"a".repeat(64)}", true)
        assertTrue(db.contact("${"a".repeat(64)}")!!.verified)
        db.setDeletedForOthers("m1")
        assertTrue(db.message("m1")!!.delOthers)
        println("MIGRACJA OK")
    }
}
