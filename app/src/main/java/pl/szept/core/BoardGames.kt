package pl.szept.core

import pl.szept.data.Db
import kotlin.math.abs
import kotlin.random.Random

/**
 * Gry we dwoje grane ruch po ruchu. Stan wynika z listy ruchów (tak samo u wszystkich uczestników).
 * Gracz 0 (A) to osoba, która wysłała grę i zaczyna; gracz 1 (B) to pierwsza inna osoba, która wykona poprawny ruch.
 */
interface Duel<S> {
    val id: String
    fun start(seed: Long): S
    /** Czy gracz [side] może teraz wykonać ruch. */
    fun canMove(s: S, side: Int): Boolean
    /** Nowy stan po ruchu albo null, gdy ruch jest niedozwolony. */
    fun apply(s: S, side: Int, mv: String): S?
    /** null = gra trwa; 0/1 = wygrał gracz; 2 = remis. */
    fun winner(s: S): Int?
    /** Ruch komputera (null = brak ruchu). */
    fun ai(s: S, side: Int, rnd: Random): String?
}

object Duels {
    data class Played<S>(val state: S, val a: String, val b: String?, val winner: Int?, val moves: Int) {
        /** Strona osoby [pk] w tej grze (-1 = widz, a gdy B jeszcze nie ma — 1 dla każdego innego). */
        fun sideOf(pk: String): Int = when { pk == a -> 0; b == null || pk == b -> 1; else -> -1 }
        fun pkOf(side: Int): String? = if (side == 0) a else b
    }

    val ALL: Map<String, Duel<*>> by lazy {
        listOf<Duel<*>>(TttDuel, Connect4, Gomoku, Reversi, Checkers, Chess, DotsBoxes, Nim, Mancala, Morris, Battleship).associateBy { it.id }
    }

    fun <S> replay(d: Duel<S>, starter: String, seed: Long, moves: List<Db.GameMove>): Played<S> {
        var s = d.start(seed)
        var b: String? = null
        var n = 0
        for (m in moves) {
            if (d.winner(s) != null) break
            val side = when {
                m.sender == starter -> 0
                b == null -> 1
                m.sender == b -> 1
                else -> continue
            }
            if (!d.canMove(s, side)) continue
            val ns = d.apply(s, side, m.mv) ?: continue
            if (side == 1 && b == null) b = m.sender
            s = ns; n++
        }
        return Played(s, starter, b, d.winner(s), n)
    }
}

// ============================ kółko i krzyżyk ============================

object TttDuel : Duel<TttDuel.S> {
    data class S(val b: IntArray, val turn: Int, val win: Int?, val line: List<Int>)

    override val id = "ttt"
    private val LINES = listOf(
        listOf(0, 1, 2), listOf(3, 4, 5), listOf(6, 7, 8), listOf(0, 3, 6), listOf(1, 4, 7), listOf(2, 5, 8), listOf(0, 4, 8), listOf(2, 4, 6),
    )

    override fun start(seed: Long) = S(IntArray(9), 0, null, emptyList())
    override fun canMove(s: S, side: Int) = s.win == null && s.turn == side
    override fun apply(s: S, side: Int, mv: String): S? {
        val c = mv.toIntOrNull() ?: return null
        if (c !in 0..8 || s.b[c] != 0) return null
        val b = s.b.copyOf(); b[c] = side + 1
        val line = LINES.firstOrNull { l -> l.all { b[it] == side + 1 } }
        val win = when { line != null -> side; b.all { it != 0 } -> 2; else -> null }
        return S(b, 1 - side, win, line ?: emptyList())
    }
    override fun winner(s: S) = s.win

    override fun ai(s: S, side: Int, rnd: Random): String? {
        val free = (0..8).filter { s.b[it] == 0 }
        if (free.isEmpty()) return null
        if (free.size == 9) return listOf(0, 2, 4, 6, 8).random(rnd).toString()
        // czasem drobny błąd, żeby dało się wygrać
        if (rnd.nextInt(10) == 0) return free.random(rnd).toString()
        fun score(st: S, me: Int, depth: Int): Int {
            st.win?.let { return when (it) { 2 -> 0; me -> 10 - depth; else -> depth - 10 } }
            val moves = (0..8).filter { st.b[it] == 0 }
            val scores = moves.map { score(apply(st, st.turn, it.toString())!!, me, depth + 1) }
            return if (st.turn == me) scores.max() else scores.min()
        }
        val best = free.map { it to score(apply(s, side, it.toString())!!, side, 0) }
        val top = best.maxOf { it.second }
        return best.filter { it.second == top }.random(rnd).first.toString()
    }
}

// ============================ czwórki ============================

object Connect4 : Duel<Connect4.S> {
    const val W = 7
    const val H = 6
    /** b[r*W+c], wiersz 0 = góra. */
    data class S(val b: IntArray, val turn: Int, val win: Int?, val line: List<Int>, val last: Int = -1)

    override val id = "c4"
    override fun start(seed: Long) = S(IntArray(W * H), 0, null, emptyList())
    override fun canMove(s: S, side: Int) = s.win == null && s.turn == side

    fun dropRow(b: IntArray, c: Int): Int = (H - 1 downTo 0).firstOrNull { b[it * W + c] == 0 } ?: -1

    override fun apply(s: S, side: Int, mv: String): S? {
        val c = mv.toIntOrNull() ?: return null
        if (c !in 0 until W) return null
        val r = dropRow(s.b, c)
        if (r < 0) return null
        val b = s.b.copyOf(); b[r * W + c] = side + 1
        val line = lineAt(b, r, c)
        val win = when { line != null -> side; b.all { it != 0 } -> 2; else -> null }
        return S(b, 1 - side, win, line ?: emptyList(), r * W + c)
    }

    private fun lineAt(b: IntArray, r: Int, c: Int): List<Int>? {
        val p = b[r * W + c]
        for ((dr, dc) in listOf(0 to 1, 1 to 0, 1 to 1, 1 to -1)) {
            val cells = mutableListOf(r * W + c)
            for (sgn in listOf(1, -1)) {
                var rr = r + dr * sgn; var cc = c + dc * sgn
                while (rr in 0 until H && cc in 0 until W && b[rr * W + cc] == p) { cells.add(rr * W + cc); rr += dr * sgn; cc += dc * sgn }
            }
            if (cells.size >= 4) return cells
        }
        return null
    }

    override fun winner(s: S) = s.win

    private fun evalWindow(w: IntArray, me: Int): Int {
        val mine = w.count { it == me }; val theirs = w.count { it != 0 && it != me }; val empty = w.count { it == 0 }
        return when {
            mine == 4 -> 1000
            mine == 3 && empty == 1 -> 6
            mine == 2 && empty == 2 -> 2
            theirs == 3 && empty == 1 -> -8
            theirs == 2 && empty == 2 -> -1
            else -> 0
        }
    }

    private fun evaluate(b: IntArray, me: Int): Int {
        var sc = 0
        for (r in 0 until H) if (b[r * W + 3] == me) sc += 3
        val dirs = listOf(0 to 1, 1 to 0, 1 to 1, 1 to -1)
        for (r in 0 until H) for (c in 0 until W) for ((dr, dc) in dirs) {
            val er = r + 3 * dr; val ec = c + 3 * dc
            if (er !in 0 until H || ec !in 0 until W) continue
            sc += evalWindow(IntArray(4) { b[(r + it * dr) * W + c + it * dc] }, me)
        }
        return sc
    }

    private fun search(s: S, depth: Int, alpha0: Int, beta0: Int, me: Int): Int {
        s.win?.let { return when (it) { 2 -> 0; me -> 100_000 + depth; else -> -100_000 - depth } }
        if (depth == 0) return evaluate(s.b, me + 1)
        var alpha = alpha0; var beta = beta0
        val order = listOf(3, 2, 4, 1, 5, 0, 6).filter { dropRow(s.b, it) >= 0 }
        if (s.turn == me) {
            var v = Int.MIN_VALUE
            for (c in order) { v = maxOf(v, search(apply(s, s.turn, c.toString())!!, depth - 1, alpha, beta, me)); alpha = maxOf(alpha, v); if (alpha >= beta) break }
            return v
        } else {
            var v = Int.MAX_VALUE
            for (c in order) { v = minOf(v, search(apply(s, s.turn, c.toString())!!, depth - 1, alpha, beta, me)); beta = minOf(beta, v); if (alpha >= beta) break }
            return v
        }
    }

    override fun ai(s: S, side: Int, rnd: Random): String? {
        val cols = (0 until W).filter { dropRow(s.b, it) >= 0 }
        if (cols.isEmpty()) return null
        val scored = cols.map { it to search(apply(s, side, it.toString())!!, 4, Int.MIN_VALUE, Int.MAX_VALUE, side) }
        val top = scored.maxOf { it.second }
        return scored.filter { it.second >= top - 1 }.random(rnd).first.toString()
    }
}

// ============================ pięć w rzędzie ============================

object Gomoku : Duel<Gomoku.S> {
    const val N = 11
    data class S(val b: IntArray, val turn: Int, val win: Int?, val line: List<Int>, val last: Int = -1)

    override val id = "gomoku"
    override fun start(seed: Long) = S(IntArray(N * N), 0, null, emptyList())
    override fun canMove(s: S, side: Int) = s.win == null && s.turn == side

    override fun apply(s: S, side: Int, mv: String): S? {
        val i = mv.toIntOrNull() ?: return null
        if (i !in 0 until N * N || s.b[i] != 0) return null
        val b = s.b.copyOf(); b[i] = side + 1
        val line = line(b, i)
        val win = when { line != null -> side; b.all { it != 0 } -> 2; else -> null }
        return S(b, 1 - side, win, line ?: emptyList(), i)
    }

    private fun line(b: IntArray, i: Int): List<Int>? {
        val r = i / N; val c = i % N; val p = b[i]
        for ((dr, dc) in listOf(0 to 1, 1 to 0, 1 to 1, 1 to -1)) {
            val cells = mutableListOf(i)
            for (sgn in listOf(1, -1)) {
                var rr = r + dr * sgn; var cc = c + dc * sgn
                while (rr in 0 until N && cc in 0 until N && b[rr * N + cc] == p) { cells.add(rr * N + cc); rr += dr * sgn; cc += dc * sgn }
            }
            if (cells.size >= 5) return cells
        }
        return null
    }

    override fun winner(s: S) = s.win

    /** Ocena pola: jak długie linie powstaną dla gracza [p] (z wolnymi końcami). */
    private fun cellScore(b: IntArray, i: Int, p: Int): Int {
        val r = i / N; val c = i % N
        var total = 0
        for ((dr, dc) in listOf(0 to 1, 1 to 0, 1 to 1, 1 to -1)) {
            var count = 1; var open = 0
            for (sgn in listOf(1, -1)) {
                var rr = r + dr * sgn; var cc = c + dc * sgn
                while (rr in 0 until N && cc in 0 until N && b[rr * N + cc] == p) { count++; rr += dr * sgn; cc += dc * sgn }
                if (rr in 0 until N && cc in 0 until N && b[rr * N + cc] == 0) open++
            }
            total += when {
                count >= 5 -> 100_000
                count == 4 && open == 2 -> 10_000
                count == 4 && open == 1 -> 1_000
                count == 3 && open == 2 -> 900
                count == 3 && open == 1 -> 100
                count == 2 && open == 2 -> 60
                count == 2 && open == 1 -> 10
                else -> open
            }
        }
        return total
    }

    override fun ai(s: S, side: Int, rnd: Random): String? {
        val empty = (0 until N * N).filter { s.b[it] == 0 }
        if (empty.isEmpty()) return null
        if (empty.size == N * N) return (N * N / 2).toString()
        val near = empty.filter { i ->
            val r = i / N; val c = i % N
            (-2..2).any { dr -> (-2..2).any { dc -> val rr = r + dr; val cc = c + dc; rr in 0 until N && cc in 0 until N && s.b[rr * N + cc] != 0 } }
        }.ifEmpty { empty }
        val me = side + 1; val them = 2 - side
        val scored = near.map { it to cellScore(s.b, it, me) * 11 / 10 + cellScore(s.b, it, them) }
        val top = scored.maxOf { it.second }
        return scored.filter { it.second >= top * 95 / 100 }.random(rnd).first.toString()
    }
}

// ============================ reversi ============================

object Reversi : Duel<Reversi.S> {
    const val N = 8
    data class S(val b: IntArray, val turn: Int, val over: Boolean, val last: Int = -1, val passed: Boolean = false) {
        fun count(side: Int) = b.count { it == side + 1 }
    }

    override val id = "reversi"
    override fun start(seed: Long): S {
        val b = IntArray(N * N)
        b[3 * N + 3] = 2; b[4 * N + 4] = 2; b[3 * N + 4] = 1; b[4 * N + 3] = 1
        return S(b, 0, false)
    }

    private val DIRS = listOf(-1 to -1, -1 to 0, -1 to 1, 0 to -1, 0 to 1, 1 to -1, 1 to 0, 1 to 1)

    fun flips(b: IntArray, i: Int, side: Int): List<Int> {
        if (b[i] != 0) return emptyList()
        val me = side + 1; val them = 2 - side
        val r = i / N; val c = i % N
        val out = mutableListOf<Int>()
        for ((dr, dc) in DIRS) {
            val run = mutableListOf<Int>()
            var rr = r + dr; var cc = c + dc
            while (rr in 0 until N && cc in 0 until N && b[rr * N + cc] == them) { run.add(rr * N + cc); rr += dr; cc += dc }
            if (run.isNotEmpty() && rr in 0 until N && cc in 0 until N && b[rr * N + cc] == me) out.addAll(run)
        }
        return out
    }

    fun legal(b: IntArray, side: Int) = (0 until N * N).filter { flips(b, it, side).isNotEmpty() }

    override fun canMove(s: S, side: Int) = !s.over && s.turn == side

    override fun apply(s: S, side: Int, mv: String): S? {
        val i = mv.toIntOrNull() ?: return null
        if (i !in 0 until N * N) return null
        val f = flips(s.b, i, side)
        if (f.isEmpty()) return null
        val b = s.b.copyOf(); b[i] = side + 1; f.forEach { b[it] = side + 1 }
        val other = 1 - side
        return when {
            legal(b, other).isNotEmpty() -> S(b, other, false, i)
            legal(b, side).isNotEmpty() -> S(b, side, false, i, passed = true)
            else -> S(b, other, true, i)
        }
    }

    override fun winner(s: S): Int? {
        if (!s.over) return null
        val a = s.count(0); val bb = s.count(1)
        return when { a > bb -> 0; bb > a -> 1; else -> 2 }
    }

    private val WEIGHTS = intArrayOf(
        100, -20, 10, 5, 5, 10, -20, 100,
        -20, -50, -2, -2, -2, -2, -50, -20,
        10, -2, 1, 1, 1, 1, -2, 10,
        5, -2, 1, 0, 0, 1, -2, 5,
        5, -2, 1, 0, 0, 1, -2, 5,
        10, -2, 1, 1, 1, 1, -2, 10,
        -20, -50, -2, -2, -2, -2, -50, -20,
        100, -20, 10, 5, 5, 10, -20, 100,
    )

    private fun eval(s: S, me: Int): Int {
        if (s.over) return when (winner(s)) { me -> 10_000; 2 -> 0; else -> -10_000 }
        var v = 0
        for (i in 0 until N * N) when (s.b[i]) { me + 1 -> v += WEIGHTS[i]; 2 - me -> v -= WEIGHTS[i] }
        return v + 3 * (legal(s.b, me).size - legal(s.b, 1 - me).size)
    }

    private fun search(s: S, depth: Int, me: Int, alpha0: Int, beta0: Int): Int {
        if (depth == 0 || s.over) return eval(s, me)
        var alpha = alpha0; var beta = beta0
        val moves = legal(s.b, s.turn)
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
        val moves = legal(s.b, side)
        if (moves.isEmpty()) return null
        val scored = moves.map { it to search(apply(s, side, it.toString())!!, 2, side, Int.MIN_VALUE, Int.MAX_VALUE) }
        val top = scored.maxOf { it.second }
        return scored.filter { it.second == top }.random(rnd).first.toString()
    }
}

// ============================ kropki i kreski ============================

object DotsBoxes : Duel<DotsBoxes.S> {
    /** Liczba kwadratów w rzędzie i kolumnie. */
    const val N = 4
    /** Kreski poziome: N+1 rzędów po N; pionowe: N rzędów po N+1. */
    const val H_EDGES = (N + 1) * N
    const val EDGES = H_EDGES + N * (N + 1)

    data class S(val edges: IntArray, val boxes: IntArray, val turn: Int, val score: IntArray) {
        val over get() = edges.all { it != 0 }
    }

    override val id = "dots"
    override fun start(seed: Long) = S(IntArray(EDGES), IntArray(N * N), 0, IntArray(2))
    override fun canMove(s: S, side: Int) = !s.over && s.turn == side

    fun hEdge(r: Int, c: Int) = r * N + c
    fun vEdge(r: Int, c: Int) = H_EDGES + r * (N + 1) + c
    fun boxEdges(r: Int, c: Int) = listOf(hEdge(r, c), hEdge(r + 1, c), vEdge(r, c), vEdge(r, c + 1))

    /** Kwadraty przylegające do kreski. */
    fun boxesOf(e: Int): List<Int> = if (e < H_EDGES) {
        val r = e / N; val c = e % N
        listOfNotNull(if (r > 0) (r - 1) * N + c else null, if (r < N) r * N + c else null)
    } else {
        val k = e - H_EDGES; val r = k / (N + 1); val c = k % (N + 1)
        listOfNotNull(if (c > 0) r * N + c - 1 else null, if (c < N) r * N + c else null)
    }

    private fun sides(edges: IntArray, box: Int) = boxEdges(box / N, box % N).count { edges[it] != 0 }

    override fun apply(s: S, side: Int, mv: String): S? {
        val e = mv.toIntOrNull() ?: return null
        if (e !in 0 until EDGES || s.edges[e] != 0) return null
        val edges = s.edges.copyOf(); edges[e] = side + 1
        val boxes = s.boxes.copyOf(); val score = s.score.copyOf()
        var made = 0
        for (bx in boxesOf(e)) if (sides(edges, bx) == 4 && boxes[bx] == 0) { boxes[bx] = side + 1; made++ }
        score[side] += made
        return S(edges, boxes, if (made > 0) side else 1 - side, score)
    }

    override fun winner(s: S): Int? = if (!s.over) null else when {
        s.score[0] > s.score[1] -> 0; s.score[1] > s.score[0] -> 1; else -> 2
    }

    override fun ai(s: S, side: Int, rnd: Random): String? {
        val free = (0 until EDGES).filter { s.edges[it] == 0 }
        if (free.isEmpty()) return null
        free.filter { e -> boxesOf(e).any { sides(s.edges, it) == 3 } }.takeIf { it.isNotEmpty() }?.let { return it.random(rnd).toString() }
        val safe = free.filter { e -> boxesOf(e).none { sides(s.edges, it) == 2 } }
        if (safe.isNotEmpty()) return safe.random(rnd).toString()
        // trzeba coś oddać — wybieramy kreskę, po której przeciwnik zamknie najmniej kwadratów
        fun given(e: Int): Int {
            var st = apply(s, side, e.toString())!!
            var n = 0
            while (!st.over) {
                val take = (0 until EDGES).firstOrNull { x -> st.edges[x] == 0 && boxesOf(x).any { sides(st.edges, it) == 3 } } ?: break
                val before = st.score[1 - side]
                st = apply(st, 1 - side, take.toString())!!
                n += st.score[1 - side] - before
            }
            return n
        }
        return free.minBy { given(it) * 10 + rnd.nextInt(10) }.toString()
    }
}

// ============================ zapałki (Nim) ============================

object Nim : Duel<Nim.S> {
    data class S(val left: Int, val turn: Int, val lastTake: Int = 0, val loser: Int? = null)

    override val id = "nim"
    override fun start(seed: Long) = S(17 + (abs(seed) % 9).toInt(), 0)
    override fun canMove(s: S, side: Int) = s.loser == null && s.turn == side
    override fun apply(s: S, side: Int, mv: String): S? {
        val n = mv.toIntOrNull() ?: return null
        if (n !in 1..3 || n > s.left) return null
        val left = s.left - n
        return S(left, 1 - side, n, if (left == 0) side else null)
    }
    override fun winner(s: S) = s.loser?.let { 1 - it }
    override fun ai(s: S, side: Int, rnd: Random): String? {
        if (s.left <= 0) return null
        val take = (s.left - 1) % 4
        return (if (take in 1..3 && rnd.nextInt(8) != 0) take else (1..minOf(3, s.left)).random(rnd)).toString()
    }
}

// ============================ kalaha ============================

object Mancala : Duel<Mancala.S> {
    /** p[0..5] dołki A, p[6] magazyn A, p[7..12] dołki B, p[13] magazyn B. */
    data class S(val p: IntArray, val turn: Int, val over: Boolean, val last: Int = -1)

    override val id = "mancala"
    override fun start(seed: Long) = S(IntArray(14) { if (it == 6 || it == 13) 0 else 4 }, 0, false)
    override fun canMove(s: S, side: Int) = !s.over && s.turn == side
    fun store(side: Int) = if (side == 0) 6 else 13
    fun pit(side: Int, k: Int) = if (side == 0) k else 7 + k

    override fun apply(s: S, side: Int, mv: String): S? {
        val k = mv.toIntOrNull() ?: return null
        if (k !in 0..5) return null
        val start = pit(side, k)
        if (s.p[start] == 0) return null
        val p = s.p.copyOf()
        var stones = p[start]; p[start] = 0
        var i = start
        while (stones > 0) {
            i = (i + 1) % 14
            if (i == store(1 - side)) continue
            p[i]++; stones--
        }
        // ostatni kamień w pustym własnym dołku — zabiera też kamienie naprzeciwko
        val own = if (side == 0) i in 0..5 else i in 7..12
        if (own && p[i] == 1 && p[12 - i] > 0) {
            p[store(side)] += p[12 - i] + 1; p[12 - i] = 0; p[i] = 0
        }
        var turn = if (i == store(side)) side else 1 - side
        var over = false
        if ((0..5).all { p[it] == 0 } || (7..12).all { p[it] == 0 }) {
            for (j in 0..5) { p[6] += p[j]; p[j] = 0 }
            for (j in 7..12) { p[13] += p[j]; p[j] = 0 }
            over = true
        }
        if (over) turn = 1 - side
        return S(p, turn, over, start)
    }

    override fun winner(s: S): Int? = if (!s.over) null else when {
        s.p[6] > s.p[13] -> 0; s.p[13] > s.p[6] -> 1; else -> 2
    }

    private fun search(s: S, depth: Int, me: Int): Int {
        if (s.over || depth == 0) return (s.p[store(me)] - s.p[store(1 - me)]) * 10 + if (s.over) (winner(s)?.let { if (it == me) 1000 else if (it == 2) 0 else -1000 } ?: 0) else 0
        val moves = (0..5).filter { s.p[pit(s.turn, it)] > 0 }
        val vals = moves.map { search(apply(s, s.turn, it.toString())!!, depth - 1, me) }
        return if (s.turn == me) vals.max() else vals.min()
    }

    override fun ai(s: S, side: Int, rnd: Random): String? {
        val moves = (0..5).filter { s.p[pit(side, it)] > 0 }
        if (moves.isEmpty()) return null
        val scored = moves.map { it to search(apply(s, side, it.toString())!!, 4, side) }
        val top = scored.maxOf { it.second }
        return scored.filter { it.second == top }.random(rnd).first.toString()
    }
}

// ============================ młynek ============================

object Morris : Duel<Morris.S> {
    /**
     * 24 punkty: trzy kwadraty (zewnętrzny 0–7, środkowy 8–15, wewnętrzny 16–23), w każdym od lewego górnego rogu
     * zgodnie z ruchem wskazówek zegara: róg, środek boku, róg, …
     */
    val ADJ: List<List<Int>> = List(24) { i ->
        val sq = i / 8; val k = i % 8
        val out = mutableListOf(sq * 8 + (k + 1) % 8, sq * 8 + (k + 7) % 8)
        if (k % 2 == 1) { if (sq > 0) out.add(i - 8); if (sq < 2) out.add(i + 8) }
        out
    }
    val MILLS: List<List<Int>> = buildList {
        for (sq in 0..2) for (k in listOf(0, 2, 4, 6)) add(listOf(sq * 8 + k, sq * 8 + k + 1, sq * 8 + (k + 2) % 8))
        for (k in listOf(1, 3, 5, 7)) add(listOf(k, k + 8, k + 16))
    }

    data class S(val b: IntArray, val turn: Int, val hand: IntArray, val win: Int?, val quiet: Int = 0, val last: List<Int> = emptyList()) {
        fun onBoard(side: Int) = b.count { it == side + 1 }
    }

    override val id = "morris"
    override fun start(seed: Long) = S(IntArray(24), 0, intArrayOf(9, 9), null)
    override fun canMove(s: S, side: Int) = s.win == null && s.turn == side

    fun inMill(b: IntArray, i: Int) = b[i] != 0 && MILLS.any { m -> i in m && m.all { b[it] == b[i] } }

    /** Pionki przeciwnika, które wolno zbić (najpierw spoza młynków). */
    fun removable(b: IntArray, side: Int): List<Int> {
        val theirs = (0 until 24).filter { b[it] == 2 - side }
        return theirs.filter { !inMill(b, it) }.ifEmpty { theirs }
    }

    /** Ruchy bez bicia: „p12” (postaw) albo „3-11” (przesuń). */
    fun baseMoves(s: S, side: Int): List<String> {
        val empty = (0 until 24).filter { s.b[it] == 0 }
        if (s.hand[side] > 0) return empty.map { "p$it" }
        val mine = (0 until 24).filter { s.b[it] == side + 1 }
        val flying = mine.size == 3
        return mine.flatMap { f -> (if (flying) empty else ADJ[f].filter { s.b[it] == 0 }).map { "$f-$it" } }
    }

    /** Wszystkie dozwolone ruchy, z biciem („x5”), gdy powstaje młynek. */
    fun moves(s: S, side: Int): List<String> = baseMoves(s, side).flatMap { m ->
        val b = s.b.copyOf()
        val to = place(b, m, side)
        if (MILLS.any { mill -> to in mill && mill.all { b[it] == side + 1 } }) removable(b, side).map { "${m}x$it" }.ifEmpty { listOf(m) } else listOf(m)
    }

    private fun place(b: IntArray, m: String, side: Int): Int {
        return if (m.startsWith("p")) { val to = m.substring(1).toInt(); b[to] = side + 1; to }
        else { val (f, t) = m.split("-").map { it.toInt() }; b[f] = 0; b[t] = side + 1; t }
    }

    override fun apply(s: S, side: Int, mv: String): S? {
        if (mv !in moves(s, side)) return null
        val b = s.b.copyOf()
        val base = mv.substringBefore("x")
        val to = place(b, base, side)
        val hand = s.hand.copyOf()
        if (base.startsWith("p")) hand[side]--
        val removed = mv.substringAfter("x", "").toIntOrNull()
        if (removed != null) b[removed] = 0
        val quiet = if (removed != null) 0 else s.quiet + 1
        val other = 1 - side
        val ns = S(b, other, hand, null, quiet, listOfNotNull(base.substringBefore("-").removePrefix("p").toIntOrNull()?.takeIf { !base.startsWith("p") }, to, removed))
        val win = when {
            hand[other] == 0 && ns.onBoard(other) < 3 -> side
            moves(ns, other).isEmpty() -> side
            quiet >= 80 -> 2
            else -> null
        }
        return ns.copy(win = win)
    }

    override fun winner(s: S) = s.win

    private fun eval(s: S, me: Int): Int {
        s.win?.let { return when (it) { me -> 10_000; 2 -> 0; else -> -10_000 } }
        val material = (s.onBoard(me) + s.hand[me]) - (s.onBoard(1 - me) + s.hand[1 - me])
        var twos = 0
        for (m in MILLS) {
            val mine = m.count { s.b[it] == me + 1 }; val theirs = m.count { s.b[it] == 2 - me }; val empty = m.count { s.b[it] == 0 }
            if (mine == 2 && empty == 1) twos++
            if (theirs == 2 && empty == 1) twos--
        }
        return material * 100 + twos * 15
    }

    override fun ai(s: S, side: Int, rnd: Random): String? {
        val all = moves(s, side)
        if (all.isEmpty()) return null
        val scored = all.map { m ->
            val ns = apply(s, side, m)!!
            val reply = if (ns.win != null) eval(ns, side) else moves(ns, 1 - side).minOfOrNull { r -> eval(apply(ns, 1 - side, r)!!, side) } ?: eval(ns, side)
            m to reply
        }
        val top = scored.maxOf { it.second }
        return scored.filter { it.second == top }.random(rnd).first
    }
}

// ============================ statki ============================

object Battleship : Duel<Battleship.S> {
    const val N = 8
    val FLEET = listOf(4, 3, 3, 2, 2)

    /** fleets[side] = pola statków (null = jeszcze nie ustawione), shots[side] = strzały gracza. */
    data class S(val fleets: List<Set<Int>?>, val ships: List<List<List<Int>>>, val shots: List<List<Int>>, val turn: Int, val win: Int?) {
        val ready get() = fleets.all { it != null }
        fun hits(side: Int): List<Int> = shots[side].filter { it in (fleets[1 - side] ?: emptySet()) }
        fun sunk(side: Int): List<List<Int>> = ships[1 - side].filter { ship -> ship.all { it in shots[side] } }
    }

    override val id = "battleship"
    override fun start(seed: Long) = S(listOf(null, null), listOf(emptyList(), emptyList()), listOf(emptyList(), emptyList()), 0, null)

    override fun canMove(s: S, side: Int) = s.win == null && (if (!s.ready) s.fleets[side] == null else s.turn == side)

    /** Układ floty „P:12h,40v,…” → statki (listy pól) albo null, gdy niepoprawny (poza planszą, nachodzą lub stykają się). */
    fun parseFleet(code: String): List<List<Int>>? {
        if (!code.startsWith("P:")) return null
        val parts = code.removePrefix("P:").split(",")
        if (parts.size != FLEET.size) return null
        val ships = parts.mapIndexed { idx, p ->
            val start = p.dropLast(1).toIntOrNull() ?: return null
            val horiz = when (p.lastOrNull()) { 'h' -> true; 'v' -> false; else -> return null }
            if (start !in 0 until N * N) return null
            val r = start / N; val c = start % N; val len = FLEET[idx]
            if (horiz && c + len > N || !horiz && r + len > N) return null
            List(len) { if (horiz) start + it else start + it * N }
        }
        val occupied = HashSet<Int>()
        for (ship in ships) {
            for (cell in ship) {
                val r = cell / N; val c = cell % N
                for (dr in -1..1) for (dc in -1..1) {
                    val rr = r + dr; val cc = c + dc
                    if (rr in 0 until N && cc in 0 until N && (rr * N + cc) in occupied) return null
                }
            }
            occupied.addAll(ship)
        }
        return ships
    }

    fun randomFleet(rnd: Random): String {
        while (true) {
            val parts = mutableListOf<String>()
            for (len in FLEET) {
                val h = rnd.nextBoolean()
                val r = rnd.nextInt(if (h) N else N - len + 1); val c = rnd.nextInt(if (h) N - len + 1 else N)
                parts.add("${r * N + c}${if (h) 'h' else 'v'}")
            }
            val code = "P:" + parts.joinToString(",")
            if (parseFleet(code) != null) return code
        }
    }

    override fun apply(s: S, side: Int, mv: String): S? {
        if (!s.ready) {
            if (s.fleets[side] != null) return null
            val ships = parseFleet(mv) ?: return null
            val fleets = s.fleets.toMutableList().also { it[side] = ships.flatten().toSet() }
            val sh = s.ships.toMutableList().also { it[side] = ships }
            return s.copy(fleets = fleets, ships = sh, turn = 0)
        }
        if (!mv.startsWith("S:")) return null
        val cell = mv.removePrefix("S:").toIntOrNull() ?: return null
        if (cell !in 0 until N * N || cell in s.shots[side]) return null
        val shots = s.shots.toMutableList().also { it[side] = it[side] + cell }
        val hit = cell in s.fleets[1 - side]!!
        val ns = s.copy(shots = shots, turn = if (hit) side else 1 - side)
        val win = if (s.fleets[1 - side]!!.all { it in shots[side] }) side else null
        return ns.copy(win = win)
    }

    override fun winner(s: S) = s.win

    override fun ai(s: S, side: Int, rnd: Random): String? {
        if (!s.ready) return if (s.fleets[side] == null) randomFleet(rnd) else null
        val shots = s.shots[side].toSet()
        val enemy = s.fleets[1 - side]!!
        val sunkCells = s.ships[1 - side].filter { ship -> ship.all { it in shots } }.flatten().toSet()
        val open = shots.filter { it in enemy && it !in sunkCells }
        fun free(i: Int) = i in 0 until N * N && i !in shots
        if (open.isNotEmpty()) {
            // dobijanie: wzdłuż linii trafień, inaczej sąsiednie pola
            val cand = mutableListOf<Int>()
            if (open.size >= 2) {
                val sorted = open.sorted()
                val horiz = sorted.all { it / N == sorted[0] / N }
                if (horiz) { cand += listOf(sorted.first() - 1, sorted.last() + 1).filter { free(it) && it / N == sorted[0] / N } }
                else cand += listOf(sorted.first() - N, sorted.last() + N).filter { free(it) }
            }
            if (cand.isEmpty()) for (h in open) {
                val r = h / N; val c = h % N
                if (c > 0 && free(h - 1)) cand.add(h - 1)
                if (c < N - 1 && free(h + 1)) cand.add(h + 1)
                if (free(h - N)) cand.add(h - N)
                if (free(h + N)) cand.add(h + N)
            }
            if (cand.isNotEmpty()) return "S:" + cand.random(rnd)
        }
        // pola przy zatopionych statkach są na pewno puste
        val blocked = HashSet<Int>()
        for (c in sunkCells) for (dr in -1..1) for (dc in -1..1) { val r = c / N + dr; val cc = c % N + dc; if (r in 0 until N && cc in 0 until N) blocked.add(r * N + cc) }
        val options = (0 until N * N).filter { it !in shots && it !in blocked }
        val parity = options.filter { (it / N + it % N) % 2 == 0 }.ifEmpty { options }
        return if (parity.isEmpty()) null else "S:" + parity.random(rnd)
    }
}
