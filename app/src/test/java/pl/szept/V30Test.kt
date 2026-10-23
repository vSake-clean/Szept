package pl.szept

import android.app.Application
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import pl.szept.core.Calc
import pl.szept.crypto.hex
import pl.szept.core.Core
import pl.szept.core.Games
import pl.szept.data.Db
import pl.szept.data.Kind
import pl.szept.data.Message
import pl.szept.data.St
import pl.szept.data.dmChatId
import pl.szept.lan.LocalRelay
import pl.szept.ui.TextFormat
import pl.szept.ui.TextFormat.Style

/** 3.0: gry, formatowanie, kalkulator, kody, automatyczne czyszczenie, „wyślij gdy online”. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class V30Test {
    private val ctx get() = RuntimeEnvironment.getApplication()

    private suspend fun waitFor(what: String, timeoutMs: Long = 20_000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (cond()) { println("✔ $what"); return }
            delay(150)
        }
        throw AssertionError("Timeout: $what")
    }

    private fun mv(who: String, cell: Int, ts: Long, seq: Int = 0) = Db.GameMove(who, seq, cell.toString(), ts)

    @Test
    fun ticTacToe() {
        // X (a) wygrywa górnym rzędem; ruch b poza kolejką i na zajęte pole są pomijane
        val s = Games.ttt("a", listOf(mv("a", 0, 1), mv("b", 0, 2), mv("b", 3, 3), mv("b", 4, 4), mv("a", 1, 5), mv("c", 5, 6), mv("b", 5, 7), mv("a", 2, 8)))
        assertEquals("b", s.o)
        assertEquals("a", s.winner)
        assertEquals(listOf(0, 1, 2), s.line)
        assertTrue(s.over)
        assertFalse(Games.tttCanMove(s, "b", 8))
        // na początku: X zaczyna, O może być każdy inny
        val s0 = Games.ttt("a", emptyList())
        assertTrue(Games.tttCanMove(s0, "a", 4)); assertFalse(Games.tttCanMove(s0, "b", 4))
        val s1 = Games.ttt("a", listOf(mv("a", 4, 1)))
        assertTrue(Games.tttCanMove(s1, "c", 0)); assertFalse(Games.tttCanMove(s1, "a", 0)); assertFalse(Games.tttCanMove(s1, "c", 4))
        // remis
        val order = listOf(0, 1, 2, 4, 3, 5, 7, 6, 8)
        val d = Games.ttt("a", order.mapIndexed { i, c -> mv(if (i % 2 == 0) "a" else "b", c, i.toLong()) })
        assertTrue(d.draw); assertNull(d.winner)
    }

    @Test
    fun fastAndDice() {
        val r = Games.fast(listOf(Db.GameMove("a", 0, "350", 1), Db.GameMove("b", 0, "-1", 2), Db.GameMove("c", 0, "210", 3), Db.GameMove("c", 1, "100", 4)))
        assertEquals(listOf("c" to 210, "a" to 350, "b" to -1), r)   // liczy się pierwsza próba, falstart na końcu
        repeat(50) { Games.rollDice().forEach { assertTrue(it in 1..6) } }
    }

    @Test
    fun formatting() {
        val p = TextFormat.parse("*gruby* i _kursywa_ ~skreśl~ `kod` ||tajne|| snake_case https://a.pl/x_y_z")
        fun st(t: String) = p.first { it.text == t }.styles
        assertEquals(setOf(Style.BOLD), st("gruby"))
        assertEquals(setOf(Style.ITALIC), st("kursywa"))
        assertEquals(setOf(Style.STRIKE), st("skreśl"))
        assertEquals(setOf(Style.CODE), st("kod"))
        assertEquals(setOf(Style.SPOILER), st("tajne"))
        assertTrue(p.joinToString("") { it.text }.contains("snake_case"))
        assertEquals("https://a.pl/x_y_z", p.first { it.link != null }.link)
        assertEquals("a ▒▒▒▒▒▒ b", TextFormat.plainPreview("a ||sekret|| b"))
        assertEquals("a ▒▒▒▒▒▒ b", Db.hideSpoilers("a ||sekret|| b"))
        assertEquals("2 * 3 * 4", TextFormat.parse("2 * 3 * 4").joinToString("") { it.text })
    }

    @Test
    fun calculator() {
        assertEquals("14", Calc.eval("2+3×4"))
        assertEquals("2,5", Calc.eval("5÷2"))
        assertEquals("-1", Calc.eval("2−3"))
        assertEquals("0,5", Calc.eval("50%"))
        assertEquals("0,333333333333", Calc.eval("1÷3"))
        assertEquals("7", Calc.eval("7+"))
        try { Calc.eval("1÷0"); throw AssertionError("dzielenie przez 0") } catch (e: Calc.CalcException) {}
    }

    @Test
    fun codes() {
        val c = Core(ctx, "codes" + System.nanoTime()).apply { createIdentity("Ja") }
        c.setPin("1234"); c.setDecoyPin("5555"); c.setCalcCode("2468")
        assertTrue(c.isDecoyPin("5555")); assertFalse(c.isDecoyPin("1234")); assertFalse(c.verifyPin("5555"))
        assertTrue(c.isCalcCode("2468")); assertFalse(c.isCalcCode("1234"))
        c.setPin(null)
        assertFalse(c.hasDecoyPin)   // bez PIN-u nie ma fałszywego PIN-u
        c.setCalcCode(null); assertFalse(c.hasCalcCode)
    }

    @Test
    fun autoClean() {
        val c = Core(ctx, "clean" + System.nanoTime()).apply { createIdentity("Ja") }
        val peer = pl.szept.crypto.Crypto.publicKey(pl.szept.crypto.Crypto.newPrivateKey()).hex()
        c.addContact(peer, "Ola")
        val chat = dmChatId(peer)
        val now = System.currentTimeMillis()
        fun msg(id: String, ageDays: Int) = Message(id, chat, peer, now - ageDays * 86400_000L, Kind.TEXT, id, null, null, St.RECEIVED, 0, 0, 0, 0, 0, false)
        c.db.insertMessage(msg("stara", 40)); c.db.insertMessage(msg("nowa", 2))
        c.setAutoClean(chat, 30)
        assertEquals(30, c.autoCleanDays(chat))
        c.runAutoClean(force = true)
        assertNull(c.db.message("stara"))
        assertTrue(c.db.message("nowa") != null)
        c.setAutoClean(chat, 0); assertEquals(0, c.autoCleanDays(chat))
    }

    private fun core(name: String, relay: String): Core = Core(ctx, name + System.nanoTime()).apply {
        createIdentity(name); setRelays(listOf(relay)); appVisible = true; start()
    }

    @Test
    fun whenOnlineAndGames() = runBlocking {
        val relay = LocalRelay(ctx, "v30_${System.nanoTime()}.db")
        val url = "ws://127.0.0.1:" + relay.start(0)
        val a = core("Ala", url)
        val b = core("Bartek", url)
        waitFor("połączeni") { a.connectedRelays().isNotEmpty() && b.connectedRelays().isNotEmpty() }
        a.addContact(b.myPub, "Bartek"); b.addContact(a.myPub, "Ala")
        val chatA = dmChatId(b.myPub); val chatB = dmChatId(a.myPub)

        // Bartek jest „w tle” — wiadomość czeka
        b.appVisible = false; b.sendPresence(force = true)
        waitFor("Bartek w tle") { a.seenOf(b.myPub)?.first == Core.Seen.IDLE }
        val s = a.scheduleWhenOnline(chatA, "Odezwij się")
        assertTrue(s != null && a.isWhenOnline(s))
        delay(1500)
        assertTrue(b.db.messages(chatB, 50).none { it.body == "Odezwij się" })
        // Bartek otwiera Szept → wiadomość wychodzi
        b.appVisible = true; b.sendPresence(force = true)
        waitFor("wiadomość doszła, gdy był online") { b.db.messages(chatB, 50).any { it.body == "Odezwij się" } }
        assertTrue(a.db.scheduled(chatA).isEmpty())

        // kółko i krzyżyk: Ala (X) zaczyna, Bartek (O) odpowiada
        val g = a.startGame(chatA, Games.TTT)
        waitFor("gra doszła") { b.db.message(g.id)?.ex?.game != null }
        a.gameMove(g.id, "4")
        waitFor("ruch X u Bartka") { Games.ttt(a.myPub, b.db.gameMoves(g.id)).board[4] == 'X' }
        b.gameMove(g.id, "0")
        waitFor("ruch O u Ali") { Games.ttt(a.myPub, a.db.gameMoves(g.id)).board[0] == 'O' }
        assertEquals(b.myPub, Games.ttt(a.myPub, a.db.gameMoves(g.id)).o)
        a.stop(); b.stop(); relay.stop()
    }
}
