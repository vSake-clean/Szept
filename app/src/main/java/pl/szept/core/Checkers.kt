package pl.szept.core

import kotlin.random.Random

/**
 * Warcaby 8×8 (zasady jak w warcabach polskich, na mniejszej planszy): pionki biją także do tyłu,
 * damki chodzą i biją po przekątnych o dowolną liczbę pól, bicie jest obowiązkowe i trzeba wybrać
 * bicie największej liczby pionków. Gracz A (jasne) zaczyna z dołu planszy.
 * Ruch: pola przejścia oddzielone „-”, np. „40-33” albo „44-30-16” (indeks = wiersz*8 + kolumna, wiersz 0 na górze).
 */
object Checkers : Duel<Checkers.S> {
    /** 1 = pionek A, 2 = pionek B, 3 = damka A, 4 = damka B. */
    data class S(val b: IntArray, val turn: Int, val win: Int?, val quiet: Int = 0, val last: List<Int> = emptyList())

    data class Seq(val path: List<Int>, val captured: List<Int>) {
        override fun toString() = path.joinToString("-")
    }

    override val id = "checkers"

    fun owner(p: Int) = when (p) { 1, 3 -> 0; 2, 4 -> 1; else -> -1 }
    fun isKing(p: Int) = p >= 3

    override fun start(seed: Long): S {
        val b = IntArray(64)
        for (r in 0..7) for (c in 0..7) if ((r + c) % 2 == 1) {
            if (r <= 2) b[r * 8 + c] = 2
            if (r >= 5) b[r * 8 + c] = 1
        }
        return S(b, 0, null)
    }

    override fun canMove(s: S, side: Int) = s.win == null && s.turn == side

    private val DIRS = listOf(-1 to -1, -1 to 1, 1 to -1, 1 to 1)

    private fun captures(b: IntArray, start: Int, cur: Int, piece: Int, captured: List<Int>, path: List<Int>, out: MutableList<Seq>) {
        val side = owner(piece)
        var extended = false
        val r0 = cur / 8; val c0 = cur % 8
        for ((dr, dc) in DIRS) {
            if (!isKing(piece)) {
                val mr = r0 + dr; val mc = c0 + dc; val lr = r0 + 2 * dr; val lc = c0 + 2 * dc
                if (lr !in 0..7 || lc !in 0..7) continue
                val mid = mr * 8 + mc; val land = lr * 8 + lc
                if (owner(b[mid]) == 1 - side && mid !in captured && (b[land] == 0 || land == start)) {
                    extended = true
                    captures(b, start, land, piece, captured + mid, path + land, out)
                }
            } else {
                var r = r0 + dr; var c = c0 + dc
                while (r in 0..7 && c in 0..7 && (b[r * 8 + c] == 0 || r * 8 + c == start)) { r += dr; c += dc }
                if (r !in 0..7 || c !in 0..7) continue
                val mid = r * 8 + c
                if (owner(b[mid]) != 1 - side || mid in captured) continue
                r += dr; c += dc
                while (r in 0..7 && c in 0..7 && (b[r * 8 + c] == 0 || r * 8 + c == start)) {
                    extended = true
                    captures(b, start, r * 8 + c, piece, captured + mid, path + (r * 8 + c), out)
                    r += dr; c += dc
                }
            }
        }
        if (!extended && captured.isNotEmpty()) out.add(Seq(path, captured))
    }

    fun legal(s: S, side: Int = s.turn): List<Seq> {
        val caps = mutableListOf<Seq>()
        for (i in 0..63) if (owner(s.b[i]) == side) captures(s.b, i, i, s.b[i], emptyList(), listOf(i), caps)
        if (caps.isNotEmpty()) {
            val max = caps.maxOf { it.captured.size }
            return caps.filter { it.captured.size == max }.distinctBy { it.toString() }
        }
        val out = mutableListOf<Seq>()
        val fwd = if (side == 0) -1 else 1
        for (i in 0..63) {
            val p = s.b[i]
            if (owner(p) != side) continue
            val r0 = i / 8; val c0 = i % 8
            if (!isKing(p)) {
                for (dc in listOf(-1, 1)) { val r = r0 + fwd; val c = c0 + dc; if (r in 0..7 && c in 0..7 && s.b[r * 8 + c] == 0) out.add(Seq(listOf(i, r * 8 + c), emptyList())) }
            } else for ((dr, dc) in DIRS) {
                var r = r0 + dr; var c = c0 + dc
                while (r in 0..7 && c in 0..7 && s.b[r * 8 + c] == 0) { out.add(Seq(listOf(i, r * 8 + c), emptyList())); r += dr; c += dc }
            }
        }
        return out
    }

    override fun apply(s: S, side: Int, mv: String): S? {
        if (s.win != null || side != s.turn) return null
        val seq = legal(s, side).firstOrNull { it.toString() == mv } ?: return null
        val b = s.b.copyOf()
        val from = seq.path.first(); val to = seq.path.last()
        var piece = b[from]
        b[from] = 0
        seq.captured.forEach { b[it] = 0 }
        if (!isKing(piece) && ((side == 0 && to / 8 == 0) || (side == 1 && to / 8 == 7))) piece += 2
        b[to] = piece
        val quiet = if (seq.captured.isNotEmpty() || !isKing(s.b[from])) 0 else s.quiet + 1
        val ns = S(b, 1 - side, null, quiet, seq.path)
        val win = when {
            legal(ns, 1 - side).isEmpty() -> side
            quiet >= 50 -> 2
            else -> null
        }
        return ns.copy(win = win)
    }

    override fun winner(s: S) = s.win

    private fun eval(s: S, me: Int): Int {
        s.win?.let { return when (it) { me -> 100_000; 2 -> 0; else -> -100_000 } }
        var v = 0
        for (i in 0..63) {
            val p = s.b[i]; if (p == 0) continue
            val o = owner(p)
            val adv = if (isKing(p)) 0 else if (o == 0) 7 - i / 8 else i / 8
            val x = (if (isKing(p)) 300 else 100) + adv * 3
            v += if (o == me) x else -x
        }
        return v
    }

    private fun search(s: S, depth: Int, me: Int, alpha0: Int, beta0: Int): Int {
        if (depth == 0 || s.win != null) return eval(s, me)
        var alpha = alpha0; var beta = beta0
        val moves = legal(s)
        if (s.turn == me) {
            var v = Int.MIN_VALUE
            for (m in moves) { v = maxOf(v, search(apply(s, s.turn, m.toString())!!, depth - 1, me, alpha, beta)); alpha = maxOf(alpha, v); if (alpha >= beta) break }
            return v
        }
        var v = Int.MAX_VALUE
        for (m in moves) { v = minOf(v, search(apply(s, s.turn, m.toString())!!, depth - 1, me, alpha, beta)); beta = minOf(beta, v); if (alpha >= beta) break }
        return v
    }

    override fun ai(s: S, side: Int, rnd: Random): String? {
        val moves = legal(s, side)
        if (moves.isEmpty()) return null
        val scored = moves.map { it to search(apply(s, side, it.toString())!!, 3, side, Int.MIN_VALUE, Int.MAX_VALUE) }
        val top = scored.maxOf { it.second }
        return scored.filter { it.second == top }.random(rnd).first.toString()
    }
}
