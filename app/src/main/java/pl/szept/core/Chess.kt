package pl.szept.core

import kotlin.math.abs
import kotlin.random.Random

/**
 * Szachy: pełne zasady (roszada, bicie w przelocie, promocja, mat, pat, 50 ruchów, trzykrotne powtórzenie,
 * za mało materiału). Gracz A gra białymi. Ruch zapisany jak „e2e4”, promocja „e7e8q”.
 * Pole: indeks = rząd*8 + kolumna, rząd 0 = pierwszy rząd białych.
 */
object Chess : Duel<Chess.S> {
    const val P = 1; const val N = 2; const val B = 3; const val R = 4; const val Q = 5; const val K = 6

    data class S(
        val b: IntArray,
        /** 0 = białe, 1 = czarne. */
        val turn: Int,
        /** Bity roszady: 1 białe krótka, 2 białe długa, 4 czarne krótka, 8 czarne długa. */
        val castle: Int,
        val ep: Int,
        val half: Int,
        val history: List<String>,
        val result: Int?,
        val lastFrom: Int = -1,
        val lastTo: Int = -1,
        val reason: String = "",
    ) {
        val check get() = Chess.inCheck(b, turn == 0)
    }

    data class Mv(val from: Int, val to: Int, val promo: Int = 0) {
        override fun toString() = sq(from) + sq(to) + when (promo) { Q -> "q"; R -> "r"; B -> "b"; N -> "n"; else -> "" }
    }

    override val id = "chess"

    fun sq(i: Int) = "" + ('a' + i % 8) + ('1' + i / 8)
    fun parseSq(s: String): Int? {
        if (s.length != 2) return null
        val f = s[0] - 'a'; val r = s[1] - '1'
        return if (f in 0..7 && r in 0..7) r * 8 + f else null
    }

    fun parse(mv: String): Mv? {
        if (mv.length !in 4..5) return null
        val from = parseSq(mv.substring(0, 2)) ?: return null
        val to = parseSq(mv.substring(2, 4)) ?: return null
        val promo = when (mv.getOrNull(4)) { null -> 0; 'q' -> Q; 'r' -> R; 'b' -> B; 'n' -> N; else -> return null }
        return Mv(from, to, promo)
    }

    override fun start(seed: Long): S {
        val b = IntArray(64)
        val back = intArrayOf(R, N, B, Q, K, B, N, R)
        for (f in 0..7) { b[f] = back[f]; b[8 + f] = P; b[48 + f] = -P; b[56 + f] = -back[f] }
        val s = S(b, 0, 15, -1, 0, emptyList(), null)
        return s.copy(history = listOf(key(s)))
    }

    /** Pozycja z zapisu FEN (do testów i zadań szachowych). */
    fun fromFen(fen: String): S {
        val parts = fen.trim().split(" ")
        val b = IntArray(64)
        var r = 7; var f = 0
        for (ch in parts[0]) when {
            ch == '/' -> { r--; f = 0 }
            ch.isDigit() -> f += ch - '0'
            else -> {
                val k = when (ch.lowercaseChar()) { 'p' -> P; 'n' -> N; 'b' -> B; 'r' -> R; 'q' -> Q; else -> K }
                b[r * 8 + f] = if (ch.isUpperCase()) k else -k; f++
            }
        }
        val turn = if (parts.getOrNull(1) == "b") 1 else 0
        val cs = parts.getOrNull(2) ?: "-"
        val castle = (if ('K' in cs) 1 else 0) or (if ('Q' in cs) 2 else 0) or (if ('k' in cs) 4 else 0) or (if ('q' in cs) 8 else 0)
        val ep = parts.getOrNull(3)?.let { parseSq(it) } ?: -1
        val s = S(b, turn, castle, ep, parts.getOrNull(4)?.toIntOrNull() ?: 0, emptyList(), null)
        return s.copy(history = listOf(key(s)))
    }

    /** Liczba pozycji po [depth] półruchach (test poprawności generatora ruchów). */
    fun perft(s: S, depth: Int): Long = if (depth == 0) 1 else legalMoves(s).sumOf { perft(make(s, it), depth - 1) }

    private fun key(s: S) = s.b.joinToString(",") + "|" + s.turn + "|" + s.castle + "|" + s.ep

    override fun canMove(s: S, side: Int) = s.result == null && s.turn == side

    private val KN = listOf(-17, -15, -10, -6, 6, 10, 15, 17)
    private val KG = listOf(-9, -8, -7, -1, 1, 7, 8, 9)
    private val DIAG = listOf(-9, -7, 7, 9)
    private val ORTH = listOf(-8, -1, 1, 8)

    private fun onBoard(from: Int, to: Int, maxFileStep: Int) = to in 0..63 && abs(to % 8 - from % 8) <= maxFileStep

    /** Czy pole [sq] jest atakowane przez białe ([byWhite]) albo czarne. */
    fun attacked(b: IntArray, sq: Int, byWhite: Boolean): Boolean {
        val sgn = if (byWhite) 1 else -1
        // pionki
        val pr = if (byWhite) -8 else 8
        for (df in listOf(-1, 1)) {
            val from = sq + pr + df
            if (onBoard(sq, from, 1) && from in 0..63 && abs(from / 8 - sq / 8) == 1 && b[from] == sgn * P) return true
        }
        for (d in KN) { val f = sq + d; if (onBoard(sq, f, 2) && b[f] == sgn * N) return true }
        for (d in KG) { val f = sq + d; if (onBoard(sq, f, 1) && b[f] == sgn * K) return true }
        for (d in DIAG) {
            var f = sq; while (true) { val n = f + d; if (!onBoard(f, n, 1)) break; f = n; val p = b[f]; if (p != 0) { if (p == sgn * B || p == sgn * Q) return true; break } }
        }
        for (d in ORTH) {
            var f = sq; while (true) { val n = f + d; if (!onBoard(f, n, if (d == 1 || d == -1) 1 else 0)) break; f = n; val p = b[f]; if (p != 0) { if (p == sgn * R || p == sgn * Q) return true; break } }
        }
        return false
    }

    fun inCheck(b: IntArray, white: Boolean): Boolean {
        val k = b.indexOf(if (white) K else -K)
        return k >= 0 && attacked(b, k, !white)
    }

    /** Ruchy pseudo-legalne (bez sprawdzania szacha). */
    private fun pseudo(s: S): List<Mv> {
        val out = ArrayList<Mv>(48)
        val white = s.turn == 0
        val sgn = if (white) 1 else -1
        val b = s.b
        for (from in 0..63) {
            val p = b[from] * sgn
            if (p <= 0) continue
            when (p) {
                P -> {
                    val dir = if (white) 8 else -8
                    val startRank = if (white) 1 else 6
                    val lastRank = if (white) 7 else 0
                    val one = from + dir
                    fun add(to: Int) { if (to / 8 == lastRank) for (pr in listOf(Q, R, B, N)) out.add(Mv(from, to, pr)) else out.add(Mv(from, to)) }
                    if (one in 0..63 && b[one] == 0) {
                        add(one)
                        val two = one + dir
                        if (from / 8 == startRank && b[two] == 0) out.add(Mv(from, two))
                    }
                    for (df in listOf(-1, 1)) {
                        val to = one + df
                        if (to !in 0..63 || abs(to % 8 - from % 8) != 1) continue
                        if (b[to] * sgn < 0) add(to) else if (to == s.ep) out.add(Mv(from, to))
                    }
                }
                N -> for (d in KN) { val to = from + d; if (onBoard(from, to, 2) && b[to] * sgn <= 0) out.add(Mv(from, to)) }
                K -> {
                    for (d in KG) { val to = from + d; if (onBoard(from, to, 1) && b[to] * sgn <= 0) out.add(Mv(from, to)) }
                    val home = if (white) 4 else 60
                    if (from == home && !attacked(b, home, !white)) {
                        val kBit = if (white) 1 else 4; val qBit = if (white) 2 else 8
                        if (s.castle and kBit != 0 && b[home + 1] == 0 && b[home + 2] == 0 && b[home + 3] == sgn * R &&
                            !attacked(b, home + 1, !white) && !attacked(b, home + 2, !white)) out.add(Mv(from, home + 2))
                        if (s.castle and qBit != 0 && b[home - 1] == 0 && b[home - 2] == 0 && b[home - 3] == 0 && b[home - 4] == sgn * R &&
                            !attacked(b, home - 1, !white) && !attacked(b, home - 2, !white)) out.add(Mv(from, home - 2))
                    }
                }
                else -> {
                    val dirs = when (p) { B -> DIAG; R -> ORTH; else -> DIAG + ORTH }
                    for (d in dirs) {
                        var f = from
                        while (true) {
                            val n = f + d
                            if (!onBoard(f, n, if (d == 8 || d == -8) 0 else 1)) break
                            f = n
                            if (b[f] * sgn > 0) break
                            out.add(Mv(from, f))
                            if (b[f] != 0) break
                        }
                    }
                }
            }
        }
        return out
    }

    /** Plansza po ruchu (bez sprawdzania poprawności) i nowe prawa roszady / pole bicia w przelocie. */
    private fun make(s: S, m: Mv): S {
        val b = s.b.copyOf()
        val p = b[m.from]
        val white = p > 0
        val kind = abs(p)
        var ep = -1
        val capture = b[m.to] != 0 || (kind == P && m.to == s.ep)
        if (kind == P && m.to == s.ep) b[m.to + if (white) -8 else 8] = 0
        if (kind == P && abs(m.to - m.from) == 16) ep = (m.from + m.to) / 2
        b[m.to] = if (kind == P && (m.to / 8 == 7 || m.to / 8 == 0)) (if (white) 1 else -1) * (if (m.promo != 0) m.promo else Q) else p
        b[m.from] = 0
        if (kind == K && abs(m.to - m.from) == 2) {
            if (m.to > m.from) { b[m.from + 1] = b[m.from + 3]; b[m.from + 3] = 0 } else { b[m.from - 1] = b[m.from - 4]; b[m.from - 4] = 0 }
        }
        var c = s.castle
        for (sq in listOf(m.from, m.to)) when (sq) { 4 -> c = c and 3.inv(); 60 -> c = c and 12.inv(); 7 -> c = c and 1.inv(); 0 -> c = c and 2.inv(); 63 -> c = c and 4.inv(); 56 -> c = c and 8.inv() }
        return s.copy(b = b, turn = 1 - s.turn, castle = c, ep = ep, half = if (kind == P || capture) 0 else s.half + 1, lastFrom = m.from, lastTo = m.to)
    }

    fun legalMoves(s: S): List<Mv> = pseudo(s).filter { m -> !inCheck(make(s, m).b, s.turn == 0) }

    private fun insufficient(b: IntArray): Boolean {
        val pieces = b.filter { it != 0 && abs(it) != K }
        if (pieces.isEmpty()) return true
        if (pieces.size == 1 && abs(pieces[0]) in listOf(N, B)) return true
        return false
    }

    override fun apply(s: S, side: Int, mv: String): S? {
        if (s.result != null || side != s.turn) return null
        val m0 = parse(mv) ?: return null
        val legal = legalMoves(s)
        val m = legal.firstOrNull { it.from == m0.from && it.to == m0.to && (it.promo == m0.promo || (m0.promo == 0 && it.promo == Q)) } ?: return null
        var ns = make(s, m)
        ns = ns.copy(history = s.history + key(ns))
        val replies = legalMoves(ns)
        val inCheckNow = inCheck(ns.b, ns.turn == 0)
        val k = key(ns)
        return when {
            replies.isEmpty() && inCheckNow -> ns.copy(result = side, reason = "Szach mat")
            replies.isEmpty() -> ns.copy(result = 2, reason = "Pat")
            ns.half >= 100 -> ns.copy(result = 2, reason = "50 ruchów bez bicia")
            insufficient(ns.b) -> ns.copy(result = 2, reason = "Za mało figur")
            ns.history.count { it == k } >= 3 -> ns.copy(result = 2, reason = "Trzykrotne powtórzenie")
            else -> ns
        }
    }

    override fun winner(s: S) = s.result

    // ---------------- komputer ----------------

    private val VAL = intArrayOf(0, 100, 320, 330, 500, 900, 20_000)
    private val PST_P = intArrayOf(
        0, 0, 0, 0, 0, 0, 0, 0, 5, 10, 10, -20, -20, 10, 10, 5, 5, -5, -10, 0, 0, -10, -5, 5, 0, 0, 0, 20, 20, 0, 0, 0,
        5, 5, 10, 25, 25, 10, 5, 5, 10, 10, 20, 30, 30, 20, 10, 10, 50, 50, 50, 50, 50, 50, 50, 50, 0, 0, 0, 0, 0, 0, 0, 0,
    )
    private val PST_N = intArrayOf(
        -50, -40, -30, -30, -30, -30, -40, -50, -40, -20, 0, 5, 5, 0, -20, -40, -30, 5, 10, 15, 15, 10, 5, -30, -30, 0, 15, 20, 20, 15, 0, -30,
        -30, 5, 15, 20, 20, 15, 5, -30, -30, 0, 10, 15, 15, 10, 0, -30, -40, -20, 0, 0, 0, 0, -20, -40, -50, -40, -30, -30, -30, -30, -40, -50,
    )

    private fun eval(b: IntArray): Int {
        var v = 0
        for (i in 0..63) {
            val p = b[i]; if (p == 0) continue
            val k = abs(p)
            val rel = if (p > 0) i else (7 - i / 8) * 8 + i % 8
            var x = VAL[k]
            when (k) { P -> x += PST_P[rel]; N -> x += PST_N[rel]; B, Q -> x += if (i % 8 in 2..5 && i / 8 in 2..5) 10 else 0 }
            v += if (p > 0) x else -x
        }
        return v
    }

    private fun order(s: S, ms: List<Mv>) = ms.sortedByDescending { m -> val t = abs(s.b[m.to]); if (t != 0) VAL[t] * 10 - VAL[abs(s.b[m.from])] else if (m.promo != 0) 5000 else 0 }

    private fun search(s: S, depth: Int, alpha0: Int, beta: Int): Int {
        if (depth == 0) return (if (s.turn == 0) 1 else -1) * eval(s.b)
        val moves = legalMoves(s)
        if (moves.isEmpty()) return if (inCheck(s.b, s.turn == 0)) -50_000 - depth else 0
        var alpha = alpha0
        for (m in order(s, moves)) {
            val v = -search(make(s, m), depth - 1, -beta, -alpha)
            if (v >= beta) return v
            if (v > alpha) alpha = v
        }
        return alpha
    }

    override fun ai(s: S, side: Int, rnd: Random): String? {
        if (s.result != null || s.turn != side) return null
        val moves = legalMoves(s)
        if (moves.isEmpty()) return null
        val scored = order(s, moves).map { m -> m to -search(make(s, m), 2, -1_000_000, 1_000_000) + rnd.nextInt(8) }
        return scored.maxBy { it.second }.first.toString()
    }

    /** Znak figury do wyświetlenia (bez emoji). */
    fun glyph(p: Int): String = when (p) {
        K -> "♔"; Q -> "♕"; R -> "♖"; B -> "♗"; N -> "♘"; P -> "♙"
        -K -> "♚"; -Q -> "♛"; -R -> "♜"; -B -> "♝"; -N -> "♞"; -P -> "♟"
        else -> ""
    } + "︎"
}
