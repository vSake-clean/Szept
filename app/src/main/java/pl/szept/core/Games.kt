package pl.szept.core

import org.json.JSONArray
import org.json.JSONObject
import pl.szept.data.Db
import kotlin.random.Random

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

    // ================= nowe gry w rozmowie =================

    /** Losowa liczba z bezpiecznego źródła (ziarno gry, losowania). */
    fun secureLong(): Long = pl.szept.crypto.Crypto.randomBytes(6).fold(0L) { a, b -> (a shl 8) or (b.toLong() and 0xff) }
    fun secureInt(bound: Int): Int = (secureLong() % bound).toInt()

    /** Generator zależny tylko od ziarna gry i [salt] — u wszystkich ten sam. */
    fun rng(seed: Long, salt: String = ""): Random = Random(seed xor salt.hashCode().toLong() * 0x9E3779B97F4A7C15uL.toLong())

    /** Dane nowej gry (JSON w wiadomości). [extra] — wybory osoby zaczynającej (np. własne słowo w wisielcu). */
    fun newGame(info: GameCatalog.Info, extra: JSONObject?, members: List<String>): JSONObject {
        val o = JSONObject().put("g", info.id)
        val seed = secureLong()
        when (info.id) {
            DICE -> o.put("v", JSONArray(rollDice()))
            FAST -> {}
            "hangman" -> {
                val custom = extra?.optString("w")?.let(::cleanWord)?.takeIf { it.length in 3..20 }
                o.put("w", custom ?: GameData.WORDS[secureInt(GameData.WORDS.size)])
                extra?.optString("h")?.trim()?.take(60)?.takeIf { it.isNotEmpty() }?.let { o.put("h", it) }
            }
            "guessgroup" -> o.put("n", 1 + secureInt(100))
            "emoji" -> GameData.EMOJI[secureInt(GameData.EMOJI.size)].let { (e, a, k) -> o.put("e", e).put("a", a).put("k", k) }
            "quick" -> GameData.QUIZ[secureInt(GameData.QUIZ.size)].let { q -> o.put("q", q.q).put("o", JSONArray(q.o)).put("c", q.c) }
            "typerace" -> o.put("w", GameData.WORDS[secureInt(GameData.WORDS.size)])
            "wyr" -> {
                val a = extra?.optString("a")?.trim()?.take(80).orEmpty(); val b = extra?.optString("b")?.trim()?.take(80).orEmpty()
                val pair = if (a.isNotEmpty() && b.isNotEmpty()) a to b else GameData.WYR[secureInt(GameData.WYR.size)]
                o.put("a", pair.first).put("b", pair.second)
            }
            "never" -> o.put("q", GameData.NEVER[secureInt(GameData.NEVER.size)])
            "truth" -> {
                val dare = extra?.optString("t")?.let { it == "w" } ?: (secureInt(2) == 1)
                val list = if (dare) GameData.DARES else GameData.TRUTHS
                o.put("who", members[secureInt(members.size)]).put("t", if (dare) "w" else "p").put("q", list[secureInt(list.size)])
            }
            "whoof" -> o.put("q", extra?.optString("q")?.trim()?.take(120)?.takeIf { it.isNotEmpty() } ?: GameData.WHO[secureInt(GameData.WHO.size)])
            "ball8" -> {
                extra?.optString("q")?.trim()?.take(200)?.takeIf { it.isNotEmpty() }?.let { o.put("q", it) }
                o.put("a", GameData.BALL8[secureInt(GameData.BALL8.size)])
            }
            "coin" -> o.put("v", secureInt(2))
            "pick" -> {
                val max = extra?.optInt("max", 0) ?: 0
                if (max >= 2) o.put("max", max.coerceAtMost(1_000_000)).put("n", 1 + secureInt(max.coerceAtMost(1_000_000)))
                else o.put("who", members[secureInt(members.size)])
            }
            else -> o.put("s", seed)
        }
        return o
    }

    /** Treść wiadomości dla starszych wersji Szepta i podglądu na liście rozmów (bez tajemnic gry). */
    fun fallbackText(info: GameCatalog.Info, game: JSONObject): String = when (info.id) {
        DICE -> game.optJSONArray("v")?.let { a -> List(a.length()) { a.optInt(it) } }?.let { v -> "🎲 Rzut kośćmi: ${v.joinToString(" i ")} (razem ${v.sum()})" } ?: "🎲 Rzut kośćmi"
        "coin" -> "🪙 Rzut monetą — " + if (game.optInt("v") == 0) "orzeł" else "reszka"
        "ball8" -> "🎱 Magiczna kula — " + game.optString("a")
        else -> "🎮 ${info.name} — zagraj w aktualnej wersji Szepta"
    }

    /** Słowo do wisielca: tylko litery, wielkie. */
    fun cleanWord(w: String): String = w.trim().uppercase().filter { it.isLetter() }

    /** Porównanie odpowiedzi bez wielkości liter, polskich znaków i interpunkcji. */
    fun norm(s: String): String {
        val map = mapOf('ą' to 'a', 'ć' to 'c', 'ę' to 'e', 'ł' to 'l', 'ń' to 'n', 'ó' to 'o', 'ś' to 's', 'ź' to 'z', 'ż' to 'z')
        return s.lowercase().map { map[it] ?: it }.filter { it.isLetterOrDigit() }.joinToString("")
    }

    /** Pierwszy ruch każdej osoby (gry, w których każdy odpowiada raz). */
    fun firstMoves(moves: List<Db.GameMove>): List<Db.GameMove> = moves.groupBy { it.sender }.mapNotNull { (_, ms) -> ms.minByOrNull { it.seq } }.sortedWith(compareBy({ it.ts }, { it.sender }))

    /** Ostatni ruch każdej osoby (głosowania — można zmienić zdanie). */
    fun lastMoves(moves: List<Db.GameMove>): Map<String, Db.GameMove> = moves.groupBy { it.sender }.mapValues { (_, ms) -> ms.maxBy { it.seq } }

    // ---------------- wyzwanie (ten sam układ, porównanie wyników) ----------------

    /** Wszystkie wyniki w wyzwaniu: osoba → lista wyników (w kolejności prób). */
    fun challengeResults(moves: List<Db.GameMove>): Map<String, List<Double>> =
        moves.groupBy { it.sender }.mapValues { (_, ms) -> ms.sortedBy { it.seq }.mapNotNull { it.mv.toDoubleOrNull()?.takeIf { v -> !v.isNaN() && !v.isInfinite() } } }
            .filterValues { it.isNotEmpty() }

    /** Ranking wyzwania: osoba i jej najlepszy wynik, od najlepszego. */
    fun challengeRanking(score: GameCatalog.Score, moves: List<Db.GameMove>): List<Pair<String, Double>> =
        score.sort(challengeResults(moves).map { (pk, v) -> pk to score.best(v)!! }) { it.second }

    // ---------------- wisielec w grupie ----------------

    data class Hangman(val word: String, val guessed: Set<Char>, val wrong: Int, val solved: Boolean, val winner: String?, val lost: Boolean, val tried: List<Pair<String, String>>) {
        val masked get() = word.map { if (it in guessed) it else '_' }.joinToString(" ")
        val over get() = solved || lost
    }
    const val HANGMAN_LIVES = 8

    fun hangman(starter: String, word: String, moves: List<Db.GameMove>): Hangman {
        val w = cleanWord(word)
        val guessed = HashSet<Char>(); var wrong = 0; var winner: String? = null; var solved = false
        val tried = mutableListOf<Pair<String, String>>()
        for (m in moves) {
            if (solved || wrong >= HANGMAN_LIVES || m.sender == starter) continue
            when {
                m.mv.startsWith("L:") -> {
                    val c = m.mv.removePrefix("L:").uppercase().firstOrNull()?.takeIf { it.isLetter() } ?: continue
                    if (c in guessed || tried.any { it.second == c.toString() }) continue
                    tried.add(m.sender to c.toString())
                    if (c in w) { guessed.add(c); if (w.all { it in guessed }) { solved = true; winner = m.sender } } else wrong++
                }
                m.mv.startsWith("W:") -> {
                    val guess = cleanWord(m.mv.removePrefix("W:"))
                    tried.add(m.sender to guess)
                    if (guess == w) { solved = true; winner = m.sender; guessed.addAll(w.toSet()) } else wrong++
                }
            }
        }
        return Hangman(w, guessed, wrong, solved, winner, !solved && wrong >= HANGMAN_LIVES, tried)
    }

    // ---------------- zgadywanie liczby ----------------

    data class Guess(val who: String, val n: Int, val hint: Int)

    /** Strzały w kolejności z podpowiedzią (−1 = szukana jest mniejsza, 1 = większa, 0 = trafione). */
    fun guesses(target: Int, moves: List<Db.GameMove>): List<Guess> {
        val out = mutableListOf<Guess>()
        for (m in moves) {
            val n = m.mv.toIntOrNull() ?: continue
            val h = target.compareTo(n).coerceIn(-1, 1)
            out.add(Guess(m.sender, n, h))
            if (h == 0) break
        }
        return out
    }

    // ---------------- odpowiedź: kto pierwszy dobrze ----------------

    /** Pierwsza osoba z poprawną odpowiedzią (emoji-zagadki). */
    fun firstCorrect(answer: String, moves: List<Db.GameMove>): Db.GameMove? = moves.firstOrNull { norm(it.mv) == norm(answer) }

    /** Szybki quiz: odpowiedzi „indeks:ms” → osoba, odpowiedź, czas (pierwsza odpowiedź każdej osoby). */
    fun quickAnswers(moves: List<Db.GameMove>): List<Triple<String, Int, Int>> = firstMoves(moves).mapNotNull { m ->
        val parts = m.mv.split(":")
        val a = parts.getOrNull(0)?.toIntOrNull() ?: return@mapNotNull null
        val ms = parts.getOrNull(1)?.toIntOrNull() ?: return@mapNotNull null
        Triple(m.sender, a, ms.coerceIn(0, 999_999))
    }

    // ---------------- karty ----------------

    /** Karta 0..51: wartość 2..14 (as = 14) i kolor 0..3. */
    fun cardRank(c: Int) = c % 13 + 2
    fun cardSuit(c: Int) = c / 13
    fun cardName(c: Int): String {
        val r = when (val v = cardRank(c)) { 11 -> "J"; 12 -> "Q"; 13 -> "K"; 14 -> "A"; else -> v.toString() }
        return r + listOf("♠", "♥", "♦", "♣")[cardSuit(c)]
    }
    fun cardRed(c: Int) = cardSuit(c) == 1 || cardSuit(c) == 2

    fun deck(rnd: Random): List<Int> = (0 until 52).shuffled(rnd)

    /** „Losowa karta”: każdy ciągnie po kolei z tej samej talii. */
    fun cardDraws(seed: Long, moves: List<Db.GameMove>): List<Pair<String, Int>> {
        val d = deck(rng(seed, "card"))
        return firstMoves(moves).take(52).mapIndexed { i, m -> m.sender to d[i] }
    }

    /** Wartość ręki w oczko (as 11 albo 1, figury 10). */
    fun bjValue(cards: List<Int>): Int {
        var total = 0; var aces = 0
        for (c in cards) { val r = cardRank(c); total += when { r == 14 -> { aces++; 11 }; r >= 10 -> 10; else -> r } }
        while (total > 21 && aces > 0) { total -= 10; aces-- }
        return total
    }

    data class BjHand(val cards: List<Int>, val stood: Boolean) {
        val value get() = bjValue(cards)
        val bust get() = value > 21
        val done get() = stood || value >= 21
    }

    /** Oczko w grupie: każda osoba ma własną talię z ziarna gry; ruchy „h” (dobierz) i „s” (pas). */
    fun bjHands(seed: Long, moves: List<Db.GameMove>): Map<String, BjHand> = moves.groupBy { it.sender }.mapValues { (pk, ms) ->
        val d = deck(rng(seed, "bj:$pk"))
        var n = 2; var stood = false
        for (m in ms.sortedBy { it.seq }) {
            if (stood || bjValue(d.take(n)) >= 21) break
            when (m.mv) { "h" -> n++; "s" -> stood = true; "start" -> {} }
        }
        BjHand(d.take(n), stood)
    }

    /** Karty, które dostanie [pk] (przed pierwszym ruchem — dwie). */
    fun bjStart(seed: Long, pk: String): BjHand = BjHand(deck(rng(seed, "bj:$pk")).take(2), false)

    // ---------------- ruletka ----------------

    val ROULETTE_RED = setOf(1, 3, 5, 7, 9, 12, 14, 16, 18, 19, 21, 23, 25, 27, 30, 32, 34, 36)

    fun rouletteNumber(seed: Long) = rng(seed, "roulette").nextInt(37)

    /** Czy zakład („r”, „b”, „e”, „o” albo liczba) wygrywa przy wyniku [n]. */
    fun rouletteWins(bet: String, n: Int): Boolean = when (bet) {
        "r" -> n in ROULETTE_RED
        "b" -> n != 0 && n !in ROULETTE_RED
        "e" -> n != 0 && n % 2 == 0
        "o" -> n % 2 == 1
        else -> bet.toIntOrNull() == n
    }

    /** Zakłady (ostatni każdej osoby przed zakręceniem) i czy koło już zakręcono. */
    fun roulette(starter: String, moves: List<Db.GameMove>): Pair<Map<String, String>, Boolean> {
        val bets = LinkedHashMap<String, String>()
        for (m in moves) {
            if (m.sender == starter && m.mv == "spin") return bets to true
            if (m.mv.startsWith("B:")) bets[m.sender] = m.mv.removePrefix("B:").take(3)
        }
        return bets to false
    }

    // ---------------- kamień, papier, nożyce ----------------

    /** Wybory (pierwszy każdej osoby) i punkty: wygrana z każdą osobą = 1 pkt. */
    fun rps(moves: List<Db.GameMove>): List<Pair<String, String>> = firstMoves(moves).filter { it.mv in setOf("r", "p", "s") }.map { it.sender to it.mv }

    fun rpsBeats(a: String, b: String) = (a == "r" && b == "s") || (a == "s" && b == "p") || (a == "p" && b == "r")

    fun rpsPoints(picks: List<Pair<String, String>>): Map<String, Int> =
        picks.associate { (pk, a) -> pk to picks.count { (other, b) -> other != pk && rpsBeats(a, b) } }

    // ---------------- kto więcej wyrzuci ----------------

    fun diceWar(moves: List<Db.GameMove>): List<Pair<String, List<Int>>> = firstMoves(moves).mapNotNull { m ->
        val v = m.mv.split(",").mapNotNull { it.toIntOrNull()?.takeIf { x -> x in 1..6 } }
        if (v.size == 3) m.sender to v else null
    }

    // ================= wynik gry (do tabeli „Łącznie” rozmowy) =================

    /** Kto grał, kto wygrał, remis, wyniki liczbowe. */
    data class Outcome(val players: Set<String>, val winners: Set<String>, val draw: Boolean = false, val scores: Map<String, Double> = emptyMap(), val finished: Boolean = true)

    /** Wynik jednej gry w rozmowie (null = gra bez zwycięzcy, np. „Co wolisz?”). */
    fun outcome(starter: String, game: JSONObject, moves: List<Db.GameMove>): Outcome? {
        val type = game.optString("g")
        val info = GameCatalog.byId(type) ?: return null
        val seed = game.optLong("s")
        Duels.ALL[type]?.let { d ->
            @Suppress("UNCHECKED_CAST")
            val p = Duels.replay(d as Duel<Any>, starter, seed, moves)
            val players = setOfNotNull(p.a, p.b)
            return when (p.winner) {
                null -> Outcome(players, emptySet(), finished = false)
                2 -> Outcome(players, emptySet(), draw = true)
                else -> Outcome(players, setOfNotNull(p.pkOf(p.winner)))
            }
        }
        if (info.chat == GameCatalog.Chat.CHALLENGE) {
            val rank = challengeRanking(info.score, moves)
            if (rank.isEmpty()) return null
            val best = rank.first().second
            return Outcome(rank.map { it.first }.toSet(), rank.filter { it.second == best }.map { it.first }.toSet(), scores = rank.toMap())
        }
        fun winnersOf(scores: Map<String, Double>, higher: Boolean = true): Outcome? {
            if (scores.isEmpty()) return null
            val best = if (higher) scores.values.max() else scores.values.min()
            return Outcome(scores.keys, scores.filterValues { it == best }.keys, scores = scores)
        }
        return when (type) {
            DICE -> game.optJSONArray("v")?.let { a -> Outcome(setOf(starter), emptySet(), scores = mapOf(starter to (0 until a.length()).sumOf { a.optInt(it) }.toDouble())) }
            FAST -> winnersOf(fast(moves).filter { it.second >= 0 }.associate { it.first to it.second.toDouble() }, higher = false)
                ?.let { o -> o.copy(players = o.players + fast(moves).map { it.first }) }
            "rps" -> {
                val picks = rps(moves)
                if (picks.size < 2) null else winnersOf(rpsPoints(picks).mapValues { it.value.toDouble() })?.let { o ->
                    if (o.winners.size == picks.size) o.copy(winners = emptySet(), draw = true) else o
                }
            }
            "hangman" -> {
                val h = hangman(starter, game.optString("w"), moves)
                val players = h.tried.map { it.first }.toSet()
                if (!h.over) Outcome(players, emptySet(), finished = false) else Outcome(players, setOfNotNull(h.winner))
            }
            "guessgroup" -> {
                val g = guesses(game.optInt("n"), moves)
                val win = g.lastOrNull()?.takeIf { it.hint == 0 }?.who
                Outcome(g.map { it.who }.toSet(), setOfNotNull(win), finished = win != null, scores = g.groupBy { it.who }.mapValues { it.value.size.toDouble() })
            }
            "emoji" -> {
                val first = firstCorrect(game.optString("a"), moves)
                Outcome(moves.map { it.sender }.toSet(), setOfNotNull(first?.sender), finished = first != null)
            }
            "quick" -> {
                val ans = quickAnswers(moves)
                val good = ans.filter { it.second == game.optInt("c") }
                Outcome(ans.map { it.first }.toSet(), setOfNotNull(good.minByOrNull { it.third }?.first), scores = good.associate { it.first to it.third.toDouble() })
            }
            "typerace" -> winnersOf(firstMoves(moves).mapNotNull { m -> m.mv.toIntOrNull()?.let { m.sender to it.toDouble() } }.toMap(), higher = false)
            "card" -> winnersOf(cardDraws(seed, moves).associate { it.first to cardRank(it.second).toDouble() })
            "dicewar" -> winnersOf(diceWar(moves).associate { it.first to it.second.sum().toDouble() })
            "bjgroup" -> {
                val hands = bjHands(seed, moves)
                val ok = hands.filterValues { it.done && !it.bust }.mapValues { it.value.value.toDouble() }
                winnersOf(ok)?.copy(players = hands.keys) ?: if (hands.isNotEmpty()) Outcome(hands.keys, emptySet()) else null
            }
            "roulette" -> {
                val (bets, spun) = roulette(starter, moves)
                if (!spun || bets.isEmpty()) null else {
                    val n = rouletteNumber(seed)
                    Outcome(bets.keys, bets.filterValues { rouletteWins(it, n) }.keys)
                }
            }
            else -> null
        }
    }
}
