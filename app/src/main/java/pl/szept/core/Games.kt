package pl.szept.core

import pl.szept.data.Db

/**
 * Gry w czacie, grane wiadomościami (bez serwera). Stan gry wynika z ruchów zapisanych u każdego uczestnika,
 * ułożonych w tej samej kolejności (czas, potem klucz nadawcy) — więc wszyscy widzą ten sam wynik.
 */
object Games {
    const val TTT = "ttt"
    const val DICE = "dice"
    const val FAST = "fast"

    // ---------------- kółko i krzyżyk ----------------

    data class Ttt(
        val board: List<Char?>, val x: String, val o: String?, val next: String?,
        val winner: String?, val line: List<Int>, val draw: Boolean,
    ) {
        val over get() = winner != null || draw
    }

    private val LINES = listOf(
        listOf(0, 1, 2), listOf(3, 4, 5), listOf(6, 7, 8),
        listOf(0, 3, 6), listOf(1, 4, 7), listOf(2, 5, 8),
        listOf(0, 4, 8), listOf(2, 4, 6),
    )

    /**
     * Rozgrywka: zaczyna X (osoba, która wysłała grę). O to pierwsza inna osoba, która zrobi poprawny ruch.
     * Ruchy nie w swojej kolejce albo na zajęte pole są pomijane.
     */
    fun ttt(starter: String, moves: List<Db.GameMove>): Ttt {
        val board = arrayOfNulls<Char>(9)
        var o: String? = null
        var turnX = true
        var winner: String? = null
        var line = emptyList<Int>()
        for (m in moves) {
            if (winner != null || board.all { it != null }) break
            val cell = m.mv.toIntOrNull() ?: continue
            if (cell !in 0..8 || board[cell] != null) continue
            if (turnX) {
                if (m.sender != starter) continue
                board[cell] = 'X'
            } else {
                if (m.sender == starter) continue
                if (o == null) o = m.sender
                if (m.sender != o) continue
                board[cell] = 'O'
            }
            turnX = !turnX
            LINES.firstOrNull { l -> l.all { board[it] != null && board[it] == board[l[0]] } }?.let { l ->
                line = l; winner = if (board[l[0]] == 'X') starter else o
            }
        }
        val full = board.all { it != null }
        val next = when {
            winner != null || full -> null
            turnX -> starter
            else -> o   // null = każdy (poza X) może zagrać jako O
        }
        return Ttt(board.toList(), starter, o, next, winner, line, winner == null && full)
    }

    /** Czy [me] może teraz postawić znak na polu [cell]. */
    fun tttCanMove(s: Ttt, me: String, cell: Int): Boolean {
        if (s.over || cell !in 0..8 || s.board[cell] != null) return false
        val xTurn = s.board.count { it == 'X' } == s.board.count { it == 'O' }
        return if (xTurn) me == s.x else me != s.x && (s.o == null || s.o == me)
    }

    // ---------------- kto szybciej ----------------

    /** Wyniki „kto szybciej”: najlepszy (najkrótszy) czas reakcji każdej osoby, od najszybszej. Falstart = -1. */
    fun fast(moves: List<Db.GameMove>): List<Pair<String, Int>> =
        moves.groupBy { it.sender }.mapNotNull { (who, ms) ->
            val first = ms.minByOrNull { it.seq } ?: return@mapNotNull null
            who to (first.mv.toIntOrNull() ?: return@mapNotNull null)
        }.sortedWith(compareBy({ if (it.second < 0) Int.MAX_VALUE else it.second }, { it.first }))

    // ---------------- kości ----------------

    fun rollDice(n: Int = 2): List<Int> = List(n) { (pl.szept.crypto.Crypto.randomBytes(1)[0].toInt() and 0xff) % 6 + 1 }

    val DICE_FACES = listOf("⚀", "⚁", "⚂", "⚃", "⚄", "⚅")
}
