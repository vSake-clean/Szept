package pl.szept

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import pl.szept.core.Battleship
import pl.szept.core.Checkers
import pl.szept.core.Chess
import pl.szept.core.Connect4
import pl.szept.core.DotsBoxes
import pl.szept.core.Duel
import pl.szept.core.Duels
import pl.szept.core.GameCatalog
import pl.szept.core.GameData
import pl.szept.core.Games
import pl.szept.core.Gomoku
import pl.szept.core.Mancala
import pl.szept.core.Morris
import pl.szept.core.Nim
import pl.szept.core.Reversi
import pl.szept.core.TttDuel
import pl.szept.data.Db
import kotlin.random.Random

/** Zasady gier planszowych i gier w rozmowie. */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [34])
class GameEngineTest {
    private fun mv(who: String, m: String, ts: Long) = Db.GameMove(who, ts.toInt(), m, ts)

    private fun <S> play(d: Duel<S>, vararg moves: String): S {
        var s = d.start(1)
        var side = 0
        for (m in moves) {
            // gracz na ruchu (gry z dodatkowym ruchem sprawdzają same, kto teraz gra)
            side = if (d.canMove(s, 0)) 0 else 1
            s = d.apply(s, side, m) ?: error("ruch $m niedozwolony")
        }
        return s
    }

    @Test fun chessPerft() {
        val start = Chess.start(0)
        assertEquals(20L, Chess.perft(start, 1))
        assertEquals(400L, Chess.perft(start, 2))
        assertEquals(8902L, Chess.perft(start, 3))
        // „Kiwipete”: roszady, bicie w przelocie, promocje
        val kiwi = Chess.fromFen("r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1")
        assertEquals(48L, Chess.perft(kiwi, 1))
        assertEquals(2039L, Chess.perft(kiwi, 2))
        val p3 = Chess.fromFen("8/2p5/3p4/KP5r/1R3p1k/8/4P1P1/8 w - - 0 1")
        assertEquals(14L, Chess.perft(p3, 1))
        assertEquals(191L, Chess.perft(p3, 2))
        assertEquals(2812L, Chess.perft(p3, 3))
        val p4 = Chess.fromFen("r3k2r/Pppp1ppp/1b3nbN/nP6/BBP1P3/q4N2/Pp1P2PP/R2Q1RK1 w kq - 0 1")
        assertEquals(6L, Chess.perft(p4, 1))
        assertEquals(264L, Chess.perft(p4, 2))
        assertEquals(9467L, Chess.perft(p4, 3))
    }

    @Test fun chessMateAndStalemate() {
        val mate = play(Chess, "f2f3", "e7e5", "g2g4", "d8h4")
        assertEquals(1, Chess.winner(mate)); assertEquals("Szach mat", mate.reason)
        assertNull(Chess.apply(Chess.start(0), 0, "e2e5"))
        assertNull(Chess.apply(Chess.start(0), 1, "e7e5"))
        val stale = Chess.apply(Chess.fromFen("7k/8/5QK1/8/8/8/8/8 w - - 0 1"), 0, "f6f7")!!
        assertEquals(2, Chess.winner(stale)); assertEquals("Pat", stale.reason)
        // promocja domyślnie na hetmana
        val promo = Chess.apply(Chess.fromFen("8/P7/8/8/8/8/8/k6K w - - 0 1"), 0, "a7a8")!!
        assertEquals(Chess.Q, promo.b[56])
        assertNotNull(Chess.ai(Chess.start(0), 0, Random(1)))
    }

    @Test fun checkersCaptureRules() {
        val s0 = Checkers.start(0)
        assertEquals(7, Checkers.legal(s0).size)
        // bicie obowiązkowe i wybór najdłuższego bicia
        val b = IntArray(64)
        b[6 * 8 + 1] = 1          // pionek A na b2 (wiersz 6)
        b[5 * 8 + 2] = 2          // B do zbicia
        b[3 * 8 + 4] = 2          // drugi B — możliwe podwójne bicie
        b[0 * 8 + 1] = 2
        val s = Checkers.S(b, 0, null)
        val legal = Checkers.legal(s)
        assertEquals(1, legal.size)
        assertEquals(2, legal[0].captured.size)
        val after = Checkers.apply(s, 0, legal[0].toString())!!
        assertEquals(1, after.b.count { it == 2 })
        assertNull(Checkers.apply(s, 0, "49-42"))
        // pionek bije do tyłu
        val back = IntArray(64); back[2 * 8 + 3] = 1; back[3 * 8 + 4] = 2; back[0] = 2
        assertTrue(Checkers.legal(Checkers.S(back, 0, null)).any { it.captured.isNotEmpty() })
        // promocja na damkę
        val pr = IntArray(64); pr[1 * 8 + 2] = 1; pr[7 * 8 + 6] = 2
        assertEquals(3, Checkers.apply(Checkers.S(pr, 0, null), 0, "10-1")!!.b[1])
        assertNotNull(Checkers.ai(s0, 0, Random(2)))
    }

    @Test fun connect4AndAi() {
        val s = play(Connect4, "3", "0", "3", "0", "3", "0", "3")
        assertEquals(0, Connect4.winner(s))
        assertEquals(4, s.line.size)
        // komputer blokuje trzy w pionie
        val threat = play(Connect4, "0", "6", "0", "6", "0")
        assertEquals("0", Connect4.ai(threat, 1, Random(3)))
        // pełna kolumna
        var full = Connect4.start(0)
        repeat(6) { full = Connect4.apply(full, full.turn, "2")!! }
        assertNull(Connect4.apply(full, full.turn, "2"))
    }

    @Test fun reversiGomokuTtt() {
        val r = Reversi.start(0)
        assertEquals(4, Reversi.legal(r.b, 0).size)
        val r1 = Reversi.apply(r, 0, "19")!!
        assertEquals(4, r1.count(0)); assertEquals(1, r1.count(1))
        assertNotNull(Reversi.ai(r1, 1, Random(1)))
        val g = play(Gomoku, "0", "20", "1", "21", "2", "22", "3", "23", "4")
        assertEquals(0, Gomoku.winner(g))
        val t = play(TttDuel, "0", "3", "1", "4", "2")
        assertEquals(0, TttDuel.winner(t))
        // komputer w kółku i krzyżyku nie przegrywa w oczywisty sposób
        val danger = play(TttDuel, "0", "4", "1")
        assertTrue((0 until 20).count { TttDuel.ai(danger, 1, Random(it)) == "2" } >= 15)
    }

    @Test fun dotsNimMancala() {
        // zamknięcie kwadratu daje punkt i kolejny ruch
        var d = DotsBoxes.start(0)
        val e = DotsBoxes.boxEdges(0, 0)
        d = DotsBoxes.apply(d, 0, e[0].toString())!!
        d = DotsBoxes.apply(d, 1, e[1].toString())!!
        d = DotsBoxes.apply(d, 0, e[2].toString())!!
        d = DotsBoxes.apply(d, 1, e[3].toString())!!
        assertEquals(1, d.score[1]); assertEquals(1, d.turn)
        val n = play(Nim, "3", "3")
        assertTrue(n.left > 0)
        var nim = Nim.S(2, 0)
        nim = Nim.apply(nim, 0, "1")!!; nim = Nim.apply(nim, 1, "1")!!
        assertEquals(0, Nim.winner(nim))
        // kalaha: ostatni kamień w magazynie = jeszcze jeden ruch
        val m = Mancala.apply(Mancala.start(0), 0, "2")!!
        assertEquals(0, m.turn); assertEquals(1, m.p[6])
        assertNotNull(Mancala.ai(m, 0, Random(1)))
    }

    @Test fun morrisMill() {
        var s = Morris.start(0)
        s = Morris.apply(s, 0, "p0")!!; s = Morris.apply(s, 1, "p8")!!
        s = Morris.apply(s, 0, "p1")!!; s = Morris.apply(s, 1, "p9")!!
        // młynek 0-1-2: trzeba zbić pionek
        assertTrue(Morris.moves(s, 0).any { it == "p2x8" })
        s = Morris.apply(s, 0, "p2x8")!!
        assertEquals(0, s.b[8])
        assertEquals(1, s.onBoard(1))
        assertNotNull(Morris.ai(s, 1, Random(1)))
    }

    @Test fun battleshipFleet() {
        val code = Battleship.randomFleet(Random(7))
        assertNotNull(Battleship.parseFleet(code))
        assertNull(Battleship.parseFleet("P:0h,8h,24h,40h,56h"))  // stykające się statki
        var s = Battleship.start(0)
        s = Battleship.apply(s, 1, "P:0h,16h,32h,48h,52h")!!
        assertFalse(s.ready)
        s = Battleship.apply(s, 0, "P:0v,2v,4v,6v,46h")!!
        assertTrue(s.ready)
        // trafienie = strzelasz dalej
        s = Battleship.apply(s, 0, "S:0")!!
        assertEquals(0, s.turn)
        s = Battleship.apply(s, 0, "S:63")!!
        assertEquals(1, s.turn)
        repeat(20) { if (Battleship.winner(s) == null) s = Battleship.apply(s, s.turn, Battleship.ai(s, s.turn, Random(it))!!)!! }
    }

    @Test fun replayAssignsSecondPlayer() {
        val moves = listOf(mv("a", "3", 1), mv("c", "3", 2), mv("c", "4", 3), mv("b", "2", 4), mv("a", "3", 5))
        val p = Duels.replay(Connect4, "a", 0, moves)
        assertEquals("c", p.b)
        assertEquals(3, p.moves)
        assertEquals(-1, p.sideOf("b"))
    }

    @Test fun chatGroupGames() {
        val h = Games.hangman("a", "kot", listOf(mv("b", "L:K", 1), mv("a", "L:O", 2), mv("c", "L:X", 3), mv("c", "L:O", 4), mv("b", "L:T", 5)))
        assertTrue(h.solved); assertEquals("b", h.winner); assertEquals(1, h.wrong)
        val g = Games.guesses(42, listOf(mv("a", "50", 1), mv("b", "40", 2), mv("c", "42", 3), mv("a", "1", 4)))
        assertEquals(listOf(-1, 1, 0), g.map { it.hint })
        assertEquals("b", Games.firstCorrect("Król Lew", listOf(mv("a", "krol", 1), mv("b", "krol lew!", 2)))?.sender)
        assertEquals(21, Games.bjValue(listOf(12, 8)))   // as + 10
        assertEquals(14, Games.bjValue(listOf(12, 25, 0)))  // as + as + 2 = 11 + 1 + 2
        assertTrue(Games.rouletteWins("r", 1)); assertFalse(Games.rouletteWins("b", 0)); assertTrue(Games.rouletteWins("17", 17))
        val pts = Games.rpsPoints(listOf("a" to "r", "b" to "s", "c" to "s"))
        assertEquals(2, pts["a"]); assertEquals(0, pts["b"])
        val draws = Games.cardDraws(9, listOf(mv("a", "d", 1), mv("b", "d", 2)))
        assertEquals(2, draws.map { it.second }.distinct().size)
        // wynik wyzwania: mniejszy czas lepszy
        val rank = Games.challengeRanking(GameCatalog.Score.TIME_LOW, listOf(mv("a", "5000", 1), mv("b", "4000", 2), mv("a", "3000", 3)))
        assertEquals("a" to 3000.0, rank.first())
        val o = Games.outcome("a", JSONObject().put("g", "c4").put("s", 1), listOf(mv("a", "3", 1), mv("b", "0", 2), mv("a", "3", 3), mv("b", "0", 4), mv("a", "3", 5), mv("b", "0", 6), mv("a", "3", 7)))!!
        assertEquals(setOf("a"), o.winners)
    }

    @Test fun catalogIsConsistent() {
        val ids = GameCatalog.BUILTIN.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        assertTrue(GameCatalog.BUILTIN.size >= 75)
        for (d in Duels.ALL.keys) assertEquals(GameCatalog.Chat.VERSUS, GameCatalog.byId(d)?.chat)
        assertEquals("12,35 s", GameCatalog.Score.TIME_LOW.fmt(12_346.0))
        assertEquals("3 ruchy", GameCatalog.Score.MOVES.fmt(3.0))
        assertEquals("5 ruchów", GameCatalog.Score.MOVES.fmt(5.0))
        assertTrue(GameData.WORDS5.all { it.length == 5 })
        assertTrue(GameData.QUIZ.all { it.o.size == 4 && it.c in 0..3 })
        val g = Games.newGame(GameCatalog.byId("hangman")!!, JSONObject().put("w", "Żółw 1!"), listOf("a", "b"))
        assertEquals("ŻÓŁW", g.optString("w"))
        assertFalse(Games.fallbackText(GameCatalog.byId("hangman")!!, g).contains("ŻÓŁW"))
    }
}
