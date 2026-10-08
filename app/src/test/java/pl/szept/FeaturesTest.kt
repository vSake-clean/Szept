package pl.szept

import android.app.Application
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
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
import pl.szept.core.Core
import pl.szept.core.Links
import pl.szept.data.Db
import pl.szept.data.Extra
import pl.szept.data.Invite
import pl.szept.data.Kind
import pl.szept.data.Loc
import pl.szept.data.Message
import pl.szept.data.Poll
import pl.szept.data.St
import pl.szept.data.Story
import pl.szept.data.XMode
import pl.szept.ui.AlbumRow
import pl.szept.ui.groupAlbums
import pl.szept.ui.plural

/** Funkcje 1.6 bez sieci: modele, baza, logika lokalna. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class FeaturesTest {
    private val ctx get() = RuntimeEnvironment.getApplication()
    private fun msg(id: String, chat: String, body: String, ts: Long, kind: String = Kind.TEXT, xmode: Int = 0, ex: Extra = Extra.NONE, sender: String = "a".repeat(64)) =
        Message(id, chat, sender, ts, kind, body, null, null, St.RECEIVED, xmode, 10, 0, 0, 0, false, null, 0, ex)

    @Test
    fun models() {
        val p = Poll.parse(Poll("Gdzie?", listOf("Kino", "Park", " "), true).toJson())!!
        assertEquals(listOf("Kino", "Park"), p.opts); assertTrue(p.multi)
        assertNull("ankieta z 1 odpowiedzią jest odrzucana", Poll.parse(Poll("?", listOf("a"), false).toJson()))
        val l = Loc.parse(Loc(52.1, 21.0, 5f, 1000, 10).toJson())!!
        assertEquals(52.1, l.lat, 1e-9); assertTrue(l.isLive(999)); assertFalse(l.isLive(1000))
        assertNull("współrzędne spoza zakresu", Loc.parse("""{"lat":120,"lon":0}"""))
        val e = Extra(fwd = true, mentions = listOf("x"), album = "al1", albumIdx = 2, albumN = 3)
        assertEquals(e, Extra.from(org.json.JSONObject(e.toJson()!!)))
        assertNull(Extra.NONE.toJson())
        val inv = Invite("t", 2000, 2, 1, "b")
        assertTrue(inv.valid(1000)); assertFalse(inv.valid(2001)); assertFalse(inv.copy(used = 2).valid(1000))
        assertTrue(Invite("t", 0, 0, 99, "b").valid(Long.MAX_VALUE))
        val g = pl.szept.data.Group("g", "G", 1, emptyList(), emptyList(), "main", false, 0, invites = listOf(inv))
        assertTrue(g.validToken("main", 5000)); assertTrue(g.validToken("t", 1500)); assertFalse(g.validToken("t", 3000)); assertFalse(g.validToken("", 0))
        assertEquals("1 głos", plural(1, "głos", "głosy", "głosów"))
        assertEquals("3 głosy", plural(3, "głos", "głosy", "głosów"))
        assertEquals("12 głosów", plural(12, "głos", "głosy", "głosów"))
        assertEquals("22 głosy", plural(22, "głos", "głosy", "głosów"))
    }

    @Test
    fun relayLinks() {
        val link = Links.relays(listOf("wss://a.example", "ws://192.168.1.20:8080"))
        val r = Links.parse("Przekaźniki: $link") as Links.Parsed.Relays
        assertEquals(listOf("wss://a.example", "ws://192.168.1.20:8080"), r.urls)
        assertNull(Links.parse("szept://r?u=http%3A%2F%2Fzly"))
    }

    @Test
    fun dbFeatures() {
        val db = Db(ctx, "f_${System.nanoTime()}.db")
        val chat = "d:" + "a".repeat(64)
        db.ensureChat(chat, false, "a".repeat(64), "Ala")
        db.insertMessage(msg("m1", chat, "Zażółć gęślą jaźń", 1000))
        db.insertMessage(msg("m2", chat, "inna wiadomość", 2000))
        db.insertMessage(msg("m3", chat, "ZAŻÓŁĆ czasowa", 3000, xmode = XMode.AFTER_OPEN))
        db.insertMessage(msg("p1", chat, Poll("Pizza?", listOf("Tak", "Nie"), false).toJson(), 4000, Kind.POLL))
        // wyszukiwanie bez wielkości liter, także z polskimi znakami; bez wiadomości czasowych
        assertEquals(listOf("m1"), db.search("zażółć").map { it.id })
        assertEquals(listOf("p1"), db.search("pizza").map { it.id })
        assertTrue("klucze JSON ankiety nie są wyszukiwane", db.search("mul").isEmpty())
        assertEquals(3, db.countNewer(chat, 1500))
        // reakcje: nowsza wygrywa, pusta = usunięcie
        db.setReaction("m1", "b".repeat(64), "👍", 10)
        db.setReaction("m1", "b".repeat(64), "❤️", 5)
        assertEquals("👍", db.reaction("m1", "b".repeat(64))!!.emoji)
        db.setReaction("m1", "b".repeat(64), "", 20)
        assertNull(db.reaction("m1", "b".repeat(64)))
        db.setReaction("m1", "b".repeat(64), "😂", 15)
        assertNull("spóźniona starsza reakcja nie wraca", db.reaction("m1", "b".repeat(64)))
        // głosy
        db.setVote("p1", "c".repeat(64), listOf(1), 10)
        assertEquals(listOf(1), db.votesForChat(chat)["p1"]!!.single().sel)
        db.setVote("p1", "c".repeat(64), emptyList(), 20)
        assertTrue(db.votesForChat(chat)["p1"].isNullOrEmpty())
        // odczyty tylko rosną
        db.setRead("m2", "d".repeat(64), St.READ, 100)
        db.setRead("m2", "d".repeat(64), St.DELIVERED, 200)
        assertEquals(St.READ, db.reads("m2")["d".repeat(64)]!!.first)
        // przypięte znikają razem z wiadomością
        db.setPin(chat, "m2", "a".repeat(64), true, 5)
        assertEquals("m2", db.pins(chat).single().msg)
        db.deleteMessage("m2")
        assertTrue(db.pins(chat).isEmpty())
        // relacje wygasają
        db.insertStory(Story("s1", "a".repeat(64), 1, Kind.TEXT, "hej", null, null, 0, false, System.currentTimeMillis() + 60_000))
        db.insertStory(Story("s2", "a".repeat(64), 1, Kind.TEXT, "stara", null, null, 0, false, System.currentTimeMillis() - 1))
        assertEquals(listOf("s1"), db.stories().map { it.id })
        assertEquals(listOf("s2"), db.expiredStories(System.currentTimeMillis()).map { it.id })
        // szkic nie odświeża interfejsu, ale się zapisuje
        val before = db.changes.value
        db.setDraft(chat, "niedokończone")
        assertEquals(before, db.changes.value)
        assertEquals("niedokończone", db.chat(chat)!!.draft)
    }

    @Test
    fun albums() {
        val chat = "g:x"
        val al = Extra(album = "A1")
        val newestFirst = listOf(
            msg("t", chat, "po", 50),
            msg("a3", chat, "", 40, Kind.IMAGE, ex = al.copy(albumIdx = 2)),
            msg("a2", chat, "", 30, Kind.IMAGE, ex = al.copy(albumIdx = 1)),
            msg("a1", chat, "podpis", 20, Kind.IMAGE, ex = al.copy(albumIdx = 0)),
            msg("solo", chat, "", 10, Kind.IMAGE, ex = Extra(album = "B")),
        )
        val rows = groupAlbums(newestFirst)
        assertEquals(3, rows.size)
        val album = rows[1] as AlbumRow
        assertEquals(listOf("a1", "a2", "a3"), album.msgs.map { it.id })
        assertEquals("al:a1", album.key)
        assertTrue("pojedyncze zdjęcie z albumu = zwykła wiadomość", rows[2] is Message)
    }

    @Test
    fun scheduledAndHidden() = runBlocking {
        val c = Core(ctx, "sched" + System.nanoTime())
        c.createIdentity("Planista")
        c.appVisible = true
        c.start()
        val solo = c.createGroup("Sama", emptyList())
        val at = System.currentTimeMillis() + 1500
        val s = c.schedule(solo, at, Kind.TEXT, "Dzień dobry o czasie", extra = Extra(mentions = listOf("all")))
        assertEquals(1, c.db.scheduled(solo).size)
        assertTrue("jeszcze nie wysłana", c.db.messages(solo, 50).none { it.body == "Dzień dobry o czasie" })
        val end = System.currentTimeMillis() + 15_000
        while (c.db.messages(solo, 50).none { it.body == "Dzień dobry o czasie" }) {
            assertTrue("zaplanowana nie wyszła", System.currentTimeMillis() < end); delay(200)
        }
        assertTrue(System.currentTimeMillis() >= at)
        assertEquals(listOf("all"), c.db.messages(solo, 50).first { it.body == "Dzień dobry o czasie" }.ex.mentions)
        assertTrue(c.db.scheduled(solo).isEmpty())
        assertNotNull(s.id)
        // anulowanie
        val s2 = c.schedule(solo, System.currentTimeMillis() + 60_000, Kind.TEXT, "nie wyślę")
        c.cancelScheduled(s2.id).join()
        assertTrue(c.db.scheduled(solo).isEmpty())

        // ukryte czaty
        assertFalse(c.hasHiddenPin)
        c.setHiddenPin("2468")
        assertTrue(c.verifyHiddenPin("2468")); assertFalse(c.verifyHiddenPin("1111"))
        c.setHidden(solo, true)
        assertTrue(c.db.chat(solo)!!.hidden)
        c.hiddenRevealed.value = true
        c.appVisible = false
        assertFalse("po wyjściu z aplikacji ukryte znów schowane", c.hiddenRevealed.value)
        c.setHiddenPin(null)
        assertFalse("wyłączenie PIN-u odkrywa czaty", c.db.chat(solo)!!.hidden)

        // edycja i ankieta lokalnie
        val m = c.sendText(solo, "Pierwotna")
        c.editMessage(m.id, "Poprawiona")
        assertEquals("Poprawiona", c.db.message(m.id)!!.body)
        assertTrue(c.db.message(m.id)!!.edited > 0)
        val poll = c.sendPoll(solo, Poll("Kiedy?", listOf("Dziś", "Jutro"), false))
        c.vote(poll.id, listOf(1, 0)).join()
        assertEquals("jedna odpowiedź w ankiecie jednokrotnego wyboru", listOf(1), c.db.votesForChat(solo)[poll.id]!!.single().sel)
        c.react(m.id, "🔥").join()
        assertEquals("🔥", c.db.reaction(m.id, c.myPub)!!.emoji)
        c.react(m.id, "🔥").join()
        assertNull(c.db.reaction(m.id, c.myPub))
        c.pinMessage(m.id, true).join()
        assertEquals(m.id, c.db.pins(solo).single().msg)
        c.stop()
        println("FEATURES UNIT OK")
    }
}
