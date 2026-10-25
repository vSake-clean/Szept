package pl.szept.core

import kotlin.random.Random

/** Generatory łamigłówek — z ziarna, więc w wyzwaniu każdy dostaje ten sam układ. */
object Puzzles {

    // ---------------- sudoku ----------------

    private fun canPlace(g: IntArray, i: Int, v: Int): Boolean {
        val r = i / 9; val c = i % 9
        for (k in 0 until 9) if (g[r * 9 + k] == v || g[k * 9 + c] == v) return false
        val br = r / 3 * 3; val bc = c / 3 * 3
        for (dr in 0 until 3) for (dc in 0 until 3) if (g[(br + dr) * 9 + bc + dc] == v) return false
        return true
    }

    private fun fill(g: IntArray, rnd: Random): Boolean {
        val i = g.indexOfFirst { it == 0 }
        if (i < 0) return true
        for (v in (1..9).shuffled(rnd)) if (canPlace(g, i, v)) { g[i] = v; if (fill(g, rnd)) return true; g[i] = 0 }
        return false
    }

    /** Liczba rozwiązań (do [limit]). */
    fun countSolutions(g: IntArray, limit: Int = 2): Int {
        var best = -1; var bestN = 10
        for (i in 0 until 81) if (g[i] == 0) {
            val n = (1..9).count { canPlace(g, i, it) }
            if (n < bestN) { best = i; bestN = n; if (n == 0) return 0 }
        }
        if (best < 0) return 1
        var total = 0
        for (v in 1..9) if (canPlace(g, best, v)) {
            g[best] = v
            total += countSolutions(g, limit - total)
            g[best] = 0
            if (total >= limit) break
        }
        return total
    }

    /** Sudoku z jednym rozwiązaniem: (zadanie z zerami w pustych polach, rozwiązanie). */
    fun sudoku(seed: Long, holes: Int = 48): Pair<IntArray, IntArray> {
        val rnd = Games.rng(seed, "sudoku")
        val sol = IntArray(81)
        fill(sol, rnd)
        val puzzle = sol.copyOf()
        var removed = 0
        for (i in (0 until 81).shuffled(rnd)) {
            if (removed >= holes) break
            val keep = puzzle[i]
            puzzle[i] = 0
            if (countSolutions(puzzle.copyOf()) != 1) puzzle[i] = keep else removed++
        }
        return puzzle to sol
    }

    // ---------------- labirynt ----------------

    /** Labirynt [w]×[h]: dla każdego pola bity otwartych przejść (1 = góra, 2 = prawo, 4 = dół, 8 = lewo). */
    fun maze(seed: Long, w: Int, h: Int): IntArray {
        val rnd = Games.rng(seed, "maze")
        val cells = IntArray(w * h)
        val seen = BooleanArray(w * h)
        val stack = ArrayDeque<Int>()
        stack.addLast(0); seen[0] = true
        val dirs = listOf(Triple(0, -1, 1), Triple(1, 0, 2), Triple(0, 1, 4), Triple(-1, 0, 8))
        while (stack.isNotEmpty()) {
            val cur = stack.last()
            val x = cur % w; val y = cur / w
            val opts = dirs.filter { (dx, dy, _) -> val nx = x + dx; val ny = y + dy; nx in 0 until w && ny in 0 until h && !seen[ny * w + nx] }
            if (opts.isEmpty()) { stack.removeLast(); continue }
            val (dx, dy, bit) = opts[rnd.nextInt(opts.size)]
            val n = (y + dy) * w + x + dx
            cells[cur] = cells[cur] or bit
            cells[n] = cells[n] or when (bit) { 1 -> 4; 2 -> 8; 4 -> 1; else -> 2 }
            seen[n] = true
            stack.addLast(n)
        }
        return cells
    }

    // ---------------- piętnastka, zgaś światła, zalewanie ----------------

    /** Piętnastka rozwiązywalna: losowe ruchy od ułożonej (0 = puste pole). */
    fun fifteen(seed: Long): IntArray {
        val rnd = Games.rng(seed, "fifteen")
        val b = IntArray(16) { (it + 1) % 16 }
        var blank = 15; var prev = -1
        repeat(240) {
            val r = blank / 4; val c = blank % 4
            val n = listOfNotNull(if (r > 0) blank - 4 else null, if (r < 3) blank + 4 else null, if (c > 0) blank - 1 else null, if (c < 3) blank + 1 else null).filter { it != prev }
            val pick = n[rnd.nextInt(n.size)]
            b[blank] = b[pick]; b[pick] = 0; prev = blank; blank = pick
        }
        return b
    }

    fun lightsToggle(b: BooleanArray, n: Int, i: Int) {
        val r = i / n; val c = i % n
        for ((dr, dc) in listOf(0 to 0, -1 to 0, 1 to 0, 0 to -1, 0 to 1)) {
            val rr = r + dr; val cc = c + dc
            if (rr in 0 until n && cc in 0 until n) b[rr * n + cc] = !b[rr * n + cc]
        }
    }

    /** Zgaś światła: losowe naciśnięcia od zgaszonej planszy — zawsze da się rozwiązać. */
    fun lights(seed: Long, n: Int = 5): BooleanArray {
        val rnd = Games.rng(seed, "lights")
        val b = BooleanArray(n * n)
        while (b.none { it }) repeat(10 + rnd.nextInt(6)) { lightsToggle(b, n, rnd.nextInt(n * n)) }
        return b
    }

    fun flood(seed: Long, n: Int = 12, colors: Int = 6): IntArray {
        val rnd = Games.rng(seed, "flood")
        return IntArray(n * n) { rnd.nextInt(colors) }
    }

    /** Zalewanie: zmienia kolor obszaru połączonego z lewym górnym polem. */
    fun floodFill(b: IntArray, n: Int, color: Int) {
        val old = b[0]
        if (old == color) return
        val stack = ArrayDeque<Int>(); stack.add(0)
        val seen = BooleanArray(b.size)
        while (stack.isNotEmpty()) {
            val i = stack.removeLast()
            if (seen[i] || b[i] != old) continue
            seen[i] = true; b[i] = color
            val r = i / n; val c = i % n
            if (r > 0) stack.add(i - n); if (r < n - 1) stack.add(i + n); if (c > 0) stack.add(i - 1); if (c < n - 1) stack.add(i + 1)
        }
    }

    // ---------------- saper ----------------

    data class Mines(val w: Int, val h: Int, val mines: Set<Int>, val start: Int) {
        fun count(i: Int): Int {
            val r = i / w; val c = i % w; var n = 0
            for (dr in -1..1) for (dc in -1..1) { val rr = r + dr; val cc = c + dc; if ((dr != 0 || dc != 0) && rr in 0 until h && cc in 0 until w && (rr * w + cc) in mines) n++ }
            return n
        }
        fun neighbors(i: Int): List<Int> {
            val r = i / w; val c = i % w
            return buildList { for (dr in -1..1) for (dc in -1..1) { val rr = r + dr; val cc = c + dc; if ((dr != 0 || dc != 0) && rr in 0 until h && cc in 0 until w) add(rr * w + cc) } }
        }
    }

    /** Pole startowe (zaznaczone) jest puste i bez min wokół — wszyscy zaczynają tak samo. */
    fun mines(seed: Long, w: Int = 9, h: Int = 13, count: Int = 20): Mines {
        val rnd = Games.rng(seed, "mines")
        val start = (h / 2) * w + w / 2 + (rnd.nextInt(3) - 1)
        val sr = start / w; val sc = start % w
        val free = (0 until w * h).filter { i -> kotlin.math.abs(i / w - sr) > 1 || kotlin.math.abs(i % w - sc) > 1 }
        return Mines(w, h, free.shuffled(rnd).take(count).toSet(), start)
    }

    // ---------------- magazynier (Sokoban) ----------------

    /** Poziomy sprawdzone programem (każdy da się rozwiązać). # ściana, . cel, $ skrzynia, @ gracz, * skrzynia na celu. */
    val SOKOBAN: List<String> = listOf(
        "#######\n#     #\n# \$ \$ #\n#. @ .#\n#######",
        "  ####\n###  #\n#  \$ #\n# #. ##\n#  \$ .#\n### @ #\n  #####",
        "########\n#  .   #\n# \$##\$ #\n#  @  .#\n########",
        " #####\n #   ###\n # \$   #\n##.##\$ #\n#  .#  #\n# @    #\n########",
        "#######\n#.  # #\n#.\$   #\n## \$\$@#\n #  . #\n ######",
        "  #####\n###   #\n#.@\$  #\n### \$.#\n#.##\$ #\n# # . ##\n#\$ *\$\$.#\n#   .  #\n########",
        "########\n#   #  #\n# \$  \$ #\n#. ##  #\n#.  \$@ #\n#.  #  #\n########",
        " ######\n #    #\n #\$## #\n # .  ##\n##.#\$  #\n#  @   #\n#      #\n########",
        "  ######\n  #    #\n### \$\$ #\n#  .#  #\n# #. \$ #\n#  .@ ##\n########",
        "#########\n#   #   #\n# \$ \$ \$ #\n#  ...  #\n### @ ###\n  #####",
        " #######\n##  .  #\n#  \$#\$ #\n# . @ .#\n#  \$#  #\n##     #\n #######",
        "########\n#..    #\n#..\$\$  #\n### \$\$ #\n  #  @ #\n  ######",
    )

    data class Soko(val w: Int, val h: Int, val walls: Set<Int>, val goals: Set<Int>, val boxes: Set<Int>, val player: Int) {
        val solved get() = boxes == goals
    }

    fun sokoban(level: Int): Soko {
        val rows = SOKOBAN[level].split("\n")
        val w = rows.maxOf { it.length }; val h = rows.size
        val walls = HashSet<Int>(); val goals = HashSet<Int>(); val boxes = HashSet<Int>(); var p = 0
        rows.forEachIndexed { y, r ->
            r.padEnd(w).forEachIndexed { x, ch ->
                val i = y * w + x
                when (ch) { '#' -> walls.add(i); '.' -> goals.add(i); '$' -> boxes.add(i); '*' -> { boxes.add(i); goals.add(i) }; '@' -> p = i; '+' -> { p = i; goals.add(i) } }
            }
        }
        return Soko(w, h, walls, goals, boxes, p)
    }

    /** Ruch gracza (dx, dy); null = niemożliwy. */
    fun sokoMove(s: Soko, dx: Int, dy: Int): Soko? {
        val x = s.player % s.w; val y = s.player / s.w
        val nx = x + dx; val ny = y + dy
        if (nx !in 0 until s.w || ny !in 0 until s.h) return null
        val n = ny * s.w + nx
        if (n in s.walls) return null
        if (n in s.boxes) {
            val bx = nx + dx; val by = ny + dy
            if (bx !in 0 until s.w || by !in 0 until s.h) return null
            val b = by * s.w + bx
            if (b in s.walls || b in s.boxes) return null
            return s.copy(boxes = s.boxes - n + b, player = n)
        }
        return s.copy(player = n)
    }
}
