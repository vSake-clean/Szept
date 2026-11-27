package pl.szept.core

import pl.szept.tr
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Lista gier: nazwa, opis, kategoria, jak się w nią gra (sam, wyzwanie w rozmowie, we dwoje, w grupie)
 * i jaki ma wynik. Gry w Pythonie (rozsyłane przez administratora) dochodzą z [PyGames].
 */
object GameCatalog {

    enum class Cat(val label: String) {
        ARCADE(tr("Zręcznościowe")), PUZZLE(tr("Logiczne")), BRAIN(tr("Pamięć i refleks")), WORDS(tr("Słowa i quizy")),
        CARDS(tr("Karty i kości")), BOARD(tr("Planszowe")), PARTY(tr("Imprezowe")), PYTHON(tr("Python")),
    }

    /** Jak gra trafia do rozmowy. */
    enum class Chat {
        /** Tylko sam. */
        NONE,
        /** Wyzwanie: każdy gra u siebie (ten sam układ), wyniki się porównują. */
        CHALLENGE,
        /** We dwoje, ruch po ruchu. */
        VERSUS,
        /** W grupie: wszyscy naraz (pytanie, losowanie, głosowanie…). */
        GROUP,
    }

    /** Rodzaj wyniku. [higher] = więcej znaczy lepiej. Czas zapisujemy w milisekundach. */
    data class Score(val kind: Kind, val higher: Boolean, val forms: List<String> = emptyList(), val decimals: Int = 0) {
        enum class Kind { NONE, NUMBER, TIME, WDL }

        val has get() = kind != Kind.NONE

        fun fmt(v: Double): String = when (kind) {
            Kind.NONE -> ""
            Kind.WDL -> when { v >= 0.99 -> tr("Wygrana"); v >= 0.49 -> tr("Remis"); else -> tr("Przegrana") }
            Kind.TIME -> fmtTime(v)
            Kind.NUMBER -> {
                val n = if (decimals == 0) v.roundToLong().toString() else String.format(pl.szept.I18n.locale, "%.${decimals}f", v)
                if (forms.isEmpty()) n else "$n " + unitFor(v)
            }
        }

        private fun unitFor(v: Double): String {
            if (forms.size < 3 || decimals > 0 || v != Math.floor(v)) return forms.getOrElse(1) { forms.firstOrNull() ?: "" }
            return plural(abs(v).toLong(), forms[0], forms[1], forms[2])
        }

        /** Czy [a] jest lepsze niż [b]. */
        fun better(a: Double, b: Double) = if (higher) a > b else a < b

        fun best(values: Collection<Double>): Double? = if (higher) values.maxOrNull() else values.minOrNull()

        /** Ranking: najlepsze najpierw. */
        fun <T> sort(items: List<T>, value: (T) -> Double): List<T> = if (higher) items.sortedByDescending(value) else items.sortedBy(value)

        companion object {
            val NONE = Score(Kind.NONE, true)
            val WDL = Score(Kind.WDL, true)
            val POINTS = Score(Kind.NUMBER, true, listOf(tr("pkt"), tr("pkt"), tr("pkt")))
            val TIME_LOW = Score(Kind.TIME, false)
            val TIME_HIGH = Score(Kind.TIME, true)
            val MS = Score(Kind.NUMBER, false, listOf("ms", "ms", "ms"))
            val MOVES = Score(Kind.NUMBER, false, listOf(tr("ruch"), tr("ruchy"), tr("ruchów")))
            val TRIES = Score(Kind.NUMBER, false, listOf(tr("próba"), tr("próby"), tr("prób")))
            val LEVEL = Score(Kind.NUMBER, true, listOf(tr("poziom"), tr("poziomy"), tr("poziomów")))
            val MISTAKES = Score(Kind.NUMBER, false, listOf(tr("błąd"), tr("błędy"), tr("błędów")))
            val PEGS = Score(Kind.NUMBER, false, listOf(tr("pionek"), tr("pionki"), tr("pionków")))
            val COINS = Score(Kind.NUMBER, true, listOf(tr("żeton"), tr("żetony"), tr("żetonów")))
            val METERS = Score(Kind.NUMBER, true, listOf("m", "m", "m"), 1)
        }
    }

    data class Info(
        val id: String,
        val name: String,
        val desc: String,
        val cat: Cat,
        /** Można zagrać samemu (na pełnym ekranie). */
        val solo: Boolean,
        val chat: Chat,
        val score: Score,
        /** Kto gra, np. „1”, „2”, „2+”, „grupa”. */
        val players: String,
        /** Zagraj sam = z komputerem (gry we dwoje). */
        val ai: Boolean = false,
        /** Gra w Pythonie (pygame). */
        val python: Boolean = false,
        /** Tylko w rozmowie: liczy się tylko w grupie (np. „Kto z nas…?”). */
        val groupOnly: Boolean = false,
    ) {
        val inChat get() = chat != Chat.NONE
    }

    private fun solo(id: String, name: String, cat: Cat, score: Score, desc: String) =
        Info(id, name, desc, cat, solo = true, chat = Chat.CHALLENGE, score = score, players = "1+")

    private fun versus(id: String, name: String, desc: String, ai: Boolean = true) =
        Info(id, name, desc, Cat.BOARD, solo = ai, chat = Chat.VERSUS, score = Score.WDL, players = "2", ai = ai)

    private fun group(id: String, name: String, cat: Cat, desc: String, score: Score = Score.NONE, players: String = "1+", groupOnly: Boolean = false) =
        Info(id, name, desc, cat, solo = false, chat = Chat.GROUP, score = score, players = players, groupOnly = groupOnly)

    /** Wbudowane gry (bez Pythona rozsyłanego przez administratora). */
    val BUILTIN: List<Info> = listOf(
        // ---- zręcznościowe ----
        solo("snake", tr("Wąż"), Cat.ARCADE, Score.POINTS, tr("Zbieraj jabłka i rośnij. Nie uderz w ścianę ani w siebie. Steruj przesunięciem palca.")),
        solo("flappy", tr("Ptaszek"), Cat.ARCADE, Score.POINTS, tr("Dotykaj, żeby podlecieć, i przelatuj przez szczeliny między rurami.")),
        solo("tetris", tr("Klocki"), Cat.ARCADE, Score.POINTS, tr("Układaj spadające klocki w pełne rzędy. Przesuń palcem, dotknij, aby obrócić, przesuń w dół, aby zrzucić.")),
        solo("breakout", tr("Arkanoid"), Cat.ARCADE, Score.POINTS, tr("Odbijaj piłkę paletką i rozbij wszystkie cegły. Masz 3 życia.")),
        solo("pong", tr("Odbijanka"), Cat.ARCADE, Score.POINTS, tr("Nie pozwól piłce spaść — każde odbicie to punkt, a piłka coraz szybsza.")),
        solo("dodge", tr("Unik"), Cat.ARCADE, Score.TIME_HIGH, tr("Przesuwaj kulkę i unikaj spadających bloków. Liczy się, jak długo przetrwasz.")),
        solo("shooter", tr("Kosmiczna obrona"), Cat.ARCADE, Score.POINTS, tr("Statek strzela sam — przesuwaj go palcem, zestrzeliwuj meteory i nie daj się trafić.")),
        solo("runner", tr("Biegacz"), Cat.ARCADE, Score.POINTS, tr("Dotknij, żeby przeskoczyć przeszkody. Bieg jest coraz szybszy.")),
        solo("jumper", tr("Skoczek"), Cat.ARCADE, Score.POINTS, tr("Skacz po platformach coraz wyżej. Przechyl palcem w lewo i prawo.")),
        solo("stack", tr("Wieża"), Cat.ARCADE, Score.POINTS, tr("Dotknij, by upuścić przesuwający się blok dokładnie na poprzedni. Co wystaje — odpada.")),
        solo("balloons", tr("Balony"), Cat.ARCADE, Score.POINTS, tr("Przebijaj balony, zanim odlecą. Unikaj czarnych! 30 sekund.")),
        solo("mole", tr("Kret"), Cat.ARCADE, Score.POINTS, tr("Uderzaj krety, które wychodzą z dziur. 30 sekund.")),
        solo("aim", tr("Celownik"), Cat.ARCADE, Score.TIME_LOW, tr("Trafiaj w 20 celów jak najszybciej.")),
        solo("tapper", tr("Klikacz"), Cat.ARCADE, Score.POINTS, tr("Ile razy dotkniesz ekranu w 10 sekund?")),
        solo("eggs", tr("Łap jajka"), Cat.ARCADE, Score.POINTS, tr("Jak w starym „Wilku i zającu”: łap jajka do koszyka. Trzy stłuczone i koniec.")),
        // ---- logiczne ----
        solo("2048", "2048", Cat.PUZZLE, Score.POINTS, tr("Przesuwaj kafelki i łącz takie same liczby. Dojdziesz do 2048?")),
        solo("mines", tr("Saper"), Cat.PUZZLE, Score.TIME_LOW, tr("Odkryj wszystkie pola bez min. Przytrzymaj, aby postawić flagę.")),
        solo("sudoku", tr("Sudoku"), Cat.PUZZLE, Score.TIME_LOW, tr("Uzupełnij planszę cyframi 1–9 tak, by nie powtarzały się w wierszu, kolumnie i kwadracie.")),
        solo("fifteen", tr("Piętnastka"), Cat.PUZZLE, Score.MOVES, tr("Ułóż kafelki od 1 do 15, przesuwając je na puste miejsce.")),
        solo("lights", tr("Zgaś światła"), Cat.PUZZLE, Score.MOVES, tr("Dotknięcie przełącza lampkę i jej sąsiadów. Zgaś wszystkie.")),
        solo("flood", tr("Zalewanie"), Cat.PUZZLE, Score.MOVES, tr("Zmieniaj kolor lewego górnego pola, aż cała plansza będzie w jednym kolorze.")),
        solo("maze", tr("Labirynt"), Cat.PUZZLE, Score.TIME_LOW, tr("Doprowadź kulkę do wyjścia. Przesuwaj palcem.")),
        solo("hanoi", tr("Wieże Hanoi"), Cat.PUZZLE, Score.MOVES, tr("Przenieś wieżę na prawy słupek. Większy krążek nie może leżeć na mniejszym.")),
        solo("peg", tr("Samotnik"), Cat.PUZZLE, Score.PEGS, tr("Przeskakuj pionkiem nad sąsiadem, który znika. Zostaw jak najmniej pionków.")),
        solo("sokoban", tr("Magazynier"), Cat.PUZZLE, Score.MOVES, tr("Wepchnij wszystkie skrzynie na oznaczone miejsca (Sokoban).")),
        solo("mastermind", tr("Łamacz kodów"), Cat.PUZZLE, Score.TRIES, tr("Odgadnij kod z 4 kolorów. Po każdej próbie dostajesz podpowiedź.")),
        solo("match3", tr("Klejnoty"), Cat.PUZZLE, Score.POINTS, tr("Zamieniaj sąsiednie klejnoty, układając trzy w rzędzie. Masz 25 ruchów.")),
        solo("lines", tr("Kulki"), Cat.PUZZLE, Score.POINTS, tr("Przestawiaj kulki i układaj 5 w linii. Po każdym ruchu pojawiają się nowe.")),
        // ---- pamięć i refleks ----
        solo("memory", tr("Pary"), Cat.BRAIN, Score.MOVES, tr("Odkrywaj po dwie karty i znajdź wszystkie pary.")),
        solo("simon", tr("Simon"), Cat.BRAIN, Score.LEVEL, tr("Powtarzaj coraz dłuższą sekwencję kolorów.")),
        solo("digits", tr("Pamięć cyfr"), Cat.BRAIN, Score.LEVEL, tr("Zapamiętaj liczbę i wpisz ją. Co poziom — o cyfrę więcej.")),
        solo("chimp", tr("Test szympansa"), Cat.BRAIN, Score.LEVEL, tr("Zapamiętaj, gdzie są liczby, i dotykaj ich po kolei — po pierwszym dotknięciu znikają.")),
        solo("reflex", tr("Refleks"), Cat.BRAIN, Score.MS, tr("Dotknij, gdy ekran zrobi się zielony. Średnia z 5 prób.")),
        solo("schulte", tr("Tabela Schultego"), Cat.BRAIN, Score.TIME_LOW, tr("Dotykaj liczb od 1 do 25 jak najszybciej.")),
        solo("shade", tr("Inny odcień"), Cat.BRAIN, Score.LEVEL, tr("Znajdź pole w nieco innym kolorze. Z każdym poziomem trudniej.")),
        solo("stopwatch", tr("Stoper 10 s"), Cat.BRAIN, Score.MS, tr("Zatrzymaj stoper dokładnie na 10,00 s — licznik znika po 3 sekundach.")),
        solo("stroop", tr("Kolory"), Cat.BRAIN, Score.POINTS, tr("Wybierz KOLOR napisu, nie to, co jest napisane. 30 sekund.")),
        solo("math", tr("Szybkie liczenie"), Cat.BRAIN, Score.POINTS, tr("Rozwiąż jak najwięcej działań w 60 sekund.")),
        // ---- słowa i quizy ----
        solo("wordle", tr("Literaki"), Cat.WORDS, Score.TRIES, tr("Odgadnij pięcioliterowe słowo w 6 próbach. Kolory podpowiadają, które litery pasują.")),
        solo("hangsolo", tr("Wisielec"), Cat.WORDS, Score.MISTAKES, tr("Zgaduj litery słowa. Im mniej błędów, tym lepiej.")),
        solo("anagram", tr("Anagramy"), Cat.WORDS, Score.POINTS, tr("Ułóż słowo z pomieszanych liter. 60 sekund.")),
        solo("typing", tr("Szybkie pisanie"), Cat.WORDS, Score.TIME_LOW, tr("Przepisz zdanie bez błędów jak najszybciej.")),
        solo("quiz", tr("Quiz wiedzy"), Cat.WORDS, Score.POINTS, tr("10 pytań z różnych dziedzin. Szybka dobra odpowiedź daje więcej punktów.")),
        solo("truefalse", tr("Prawda czy fałsz"), Cat.WORDS, Score.POINTS, tr("Oceniaj zdania: prawda czy fałsz? 60 sekund.")),
        // ---- karty i kości ----
        solo("blackjack", tr("Oczko"), Cat.CARDS, Score.COINS, tr("Dobieraj karty, żeby zbliżyć się do 21, i pokonaj krupiera. 10 rozdań.")),
        solo("higherlower", tr("Wyższa czy niższa"), Cat.CARDS, Score.POINTS, tr("Zgadnij, czy następna karta będzie wyższa, czy niższa. Ile trafisz z rzędu?")),
        solo("slots", tr("Jednoręki bandyta"), Cat.CARDS, Score.COINS, tr("20 zakręceń — ile żetonów zostanie?")),
        solo("yahtzee", tr("Generał"), Cat.CARDS, Score.POINTS, tr("Gra w kości: 13 kolejek, trzy rzuty w kolejce, wybierz najlepszą kategorię.")),
        solo("guessnum", tr("Zgadnij liczbę"), Cat.CARDS, Score.TRIES, tr("Komputer wybrał liczbę od 1 do 1000. Zgadnij ją w jak najmniejszej liczbie prób.")),
        // ---- planszowe (we dwoje, sam — z komputerem) ----
        versus("ttt", tr("Kółko i krzyżyk"), tr("Kto pierwszy ułoży trzy w rzędzie.")),
        versus("c4", tr("Czwórki"), tr("Wrzucaj krążki do kolumn — wygrywa, kto pierwszy ułoży cztery w linii.")),
        versus("gomoku", tr("Pięć w rzędzie"), tr("Kółko i krzyżyk na dużej planszy: wygrywa pięć w linii.")),
        versus("reversi", tr("Reversi"), tr("Otaczaj pionki przeciwnika, by zmieniły kolor. Na koniec liczy się, kogo jest więcej.")),
        versus("checkers", tr("Warcaby"), tr("Bicie obowiązkowe, damki chodzą po przekątnych o dowolną liczbę pól.")),
        versus("chess", tr("Szachy"), tr("Pełne zasady: roszada, bicie w przelocie, promocja, mat i pat.")),
        versus("dots", tr("Kropki i kreski"), tr("Rysuj kreski; kto zamknie kwadrat, dostaje punkt i rusza się jeszcze raz.")),
        versus("nim", tr("Zapałki"), tr("Bierz 1–3 zapałki. Kto weźmie ostatnią, przegrywa.")),
        versus("mancala", tr("Kalaha"), tr("Afrykańska gra w kamyki: rozsiewaj je po dołkach i zbierz najwięcej w swoim magazynie.")),
        versus("morris", tr("Młynek"), tr("Ustawiaj pionki i układaj młynki (trzy w linii), by zbijać pionki przeciwnika.")),
        versus("battleship", tr("Statki"), tr("Ustaw flotę i strzelaj na zmianę. Kto pierwszy zatopi wszystkie statki?")),
        Info("rps", tr("Kamień, papier, nożyce"), tr("Każdy wybiera w tajemnicy — wynik widać, gdy wszyscy zagrają."), Cat.BOARD, true, Chat.VERSUS, Score.WDL, "2+", ai = true),
        // ---- imprezowe (w rozmowie) ----
        group("dice", tr("Rzut kośćmi"), Cat.PARTY, tr("Dwie kości, wynik widzą wszyscy."), Score.POINTS),
        group("fast", tr("Kto szybciej?"), Cat.PARTY, tr("Test refleksu — każdy próbuje raz, wygrywa najszybszy."), Score.MS),
        group("hangman", tr("Wisielec w grupie"), Cat.WORDS, tr("Ty wymyślasz słowo, a reszta zgaduje litery. Kto odgadnie całe, wygrywa."), Score.POINTS, "2+"),
        group("guessgroup", tr("Zgadnij liczbę w grupie"), Cat.PARTY, tr("Telefon losuje liczbę 1–100. Wszyscy zgadują na zmianę, podpowiedzi: więcej / mniej.")),
        group("emoji", tr("Emoji-zagadki"), Cat.WORDS, tr("Odgadnij tytuł filmu, przysłowie lub rzecz z emoji. Pierwszy poprawny wygrywa.")),
        group("quick", tr("Pytanie na czas"), Cat.WORDS, tr("Jedno pytanie z czterema odpowiedziami. Liczy się poprawna i najszybsza odpowiedź."), Score.MS),
        group("typerace", tr("Kto pierwszy napisze"), Cat.WORDS, tr("Na ekranie pojawia się słowo — kto pierwszy przepisze je bez błędu?"), Score.MS),
        group("wyr", tr("Co wolisz?"), Cat.PARTY, tr("Dwie opcje — każdy wybiera, a potem widać, kto co wybrał.")),
        group("never", tr("Nigdy przenigdy"), Cat.PARTY, tr("Każdy odpowiada: „ja tak” albo „ja nigdy”.")),
        group("truth", tr("Prawda czy wyzwanie"), Cat.PARTY, tr("Losowa osoba dostaje pytanie albo wyzwanie."), players = "2+"),
        group("whoof", tr("Kto z nas…?"), Cat.PARTY, tr("Głosujcie, kto z grupy najbardziej pasuje do opisu."), players = "3+", groupOnly = true),
        group("ball8", tr("Magiczna kula"), Cat.PARTY, tr("Zadaj pytanie i potrząśnij kulą — odpowiedź zobaczą wszyscy.")),
        group("coin", tr("Rzut monetą"), Cat.CARDS, tr("Orzeł czy reszka?")),
        group("pick", tr("Losowanie"), Cat.PARTY, tr("Losowa osoba z rozmowy albo liczba — np. kto płaci za pizzę.")),
        group("card", tr("Losowa karta"), Cat.CARDS, tr("Każdy ciągnie kartę z tej samej talii — najwyższa wygrywa."), Score.POINTS),
        group("roulette", tr("Ruletka"), Cat.CARDS, tr("Każdy obstawia czerwone, czarne, parzyste albo nieparzyste — kręcimy kołem.")),
        group("dicewar", tr("Kto więcej wyrzuci?"), Cat.CARDS, tr("Każdy rzuca trzema kośćmi — wygrywa najwyższy wynik."), Score.POINTS),
        group("bjgroup", tr("Oczko w grupie"), Cat.CARDS, tr("Każdy dobiera karty, by zbliżyć się do 21. Wygrywa najbliższy, kto nie przekroczy."), Score.POINTS),
    )

    /** Wszystkie gry: wbudowane + w Pythonie. */
    fun all(): List<Info> = BUILTIN + PyGames.infos()

    fun byId(id: String): Info? = BUILTIN.firstOrNull { it.id == id } ?: PyGames.info(id)

    /** Gry do wyboru w rozmowie (w prywatnej bez tych, które mają sens tylko w grupie). */
    fun forChat(isGroup: Boolean): List<Info> = all().filter { it.inChat && (isGroup || !it.groupOnly) }

    fun forSolo(): List<Info> = all().filter { it.solo }

    fun fmtTime(ms: Double): String {
        val t = ms.coerceAtLeast(0.0)
        return if (t < 60_000) String.format(pl.szept.I18n.locale, tr("%.2f s"), t / 1000.0)
        else {
            val s = (t / 1000.0)
            String.format(pl.szept.I18n.locale, "%d:%04.1f", (s / 60).toInt(), s % 60).replace('.', ',')
        }
    }

    fun plural(n: Long, one: String, few: String, many: String): String = when {
        n == 1L -> one
        n % 10 in 2..4 && n % 100 !in 12..14 -> few
        else -> many
    }
}
