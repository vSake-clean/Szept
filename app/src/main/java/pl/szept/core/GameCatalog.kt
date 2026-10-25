package pl.szept.core

import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Lista gier: nazwa, opis, kategoria, jak się w nią gra (sam, wyzwanie w rozmowie, we dwoje, w grupie)
 * i jaki ma wynik. Gry w Pythonie (rozsyłane przez administratora) dochodzą z [PyGames].
 */
object GameCatalog {

    enum class Cat(val label: String) {
        ARCADE("Zręcznościowe"), PUZZLE("Logiczne"), BRAIN("Pamięć i refleks"), WORDS("Słowa i quizy"),
        CARDS("Karty i kości"), BOARD("Planszowe"), PARTY("Imprezowe"), PYTHON("Python"),
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
            Kind.WDL -> when { v >= 0.99 -> "Wygrana"; v >= 0.49 -> "Remis"; else -> "Przegrana" }
            Kind.TIME -> fmtTime(v)
            Kind.NUMBER -> {
                val n = if (decimals == 0) v.roundToLong().toString() else String.format(Locale("pl"), "%.${decimals}f", v)
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
            val POINTS = Score(Kind.NUMBER, true, listOf("pkt", "pkt", "pkt"))
            val TIME_LOW = Score(Kind.TIME, false)
            val TIME_HIGH = Score(Kind.TIME, true)
            val MS = Score(Kind.NUMBER, false, listOf("ms", "ms", "ms"))
            val MOVES = Score(Kind.NUMBER, false, listOf("ruch", "ruchy", "ruchów"))
            val TRIES = Score(Kind.NUMBER, false, listOf("próba", "próby", "prób"))
            val LEVEL = Score(Kind.NUMBER, true, listOf("poziom", "poziomy", "poziomów"))
            val MISTAKES = Score(Kind.NUMBER, false, listOf("błąd", "błędy", "błędów"))
            val PEGS = Score(Kind.NUMBER, false, listOf("pionek", "pionki", "pionków"))
            val COINS = Score(Kind.NUMBER, true, listOf("żeton", "żetony", "żetonów"))
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
        solo("snake", "Wąż", Cat.ARCADE, Score.POINTS, "Zbieraj jabłka i rośnij. Nie uderz w ścianę ani w siebie. Steruj przesunięciem palca."),
        solo("flappy", "Ptaszek", Cat.ARCADE, Score.POINTS, "Dotykaj, żeby podlecieć, i przelatuj przez szczeliny między rurami."),
        solo("tetris", "Klocki", Cat.ARCADE, Score.POINTS, "Układaj spadające klocki w pełne rzędy. Przesuń palcem, dotknij, aby obrócić, przesuń w dół, aby zrzucić."),
        solo("breakout", "Arkanoid", Cat.ARCADE, Score.POINTS, "Odbijaj piłkę paletką i rozbij wszystkie cegły. Masz 3 życia."),
        solo("pong", "Odbijanka", Cat.ARCADE, Score.POINTS, "Nie pozwól piłce spaść — każde odbicie to punkt, a piłka coraz szybsza."),
        solo("dodge", "Unik", Cat.ARCADE, Score.TIME_HIGH, "Przesuwaj kulkę i unikaj spadających bloków. Liczy się, jak długo przetrwasz."),
        solo("shooter", "Kosmiczna obrona", Cat.ARCADE, Score.POINTS, "Statek strzela sam — przesuwaj go palcem, zestrzeliwuj meteory i nie daj się trafić."),
        solo("runner", "Biegacz", Cat.ARCADE, Score.POINTS, "Dotknij, żeby przeskoczyć przeszkody. Bieg jest coraz szybszy."),
        solo("jumper", "Skoczek", Cat.ARCADE, Score.POINTS, "Skacz po platformach coraz wyżej. Przechyl palcem w lewo i prawo."),
        solo("stack", "Wieża", Cat.ARCADE, Score.POINTS, "Dotknij, by upuścić przesuwający się blok dokładnie na poprzedni. Co wystaje — odpada."),
        solo("balloons", "Balony", Cat.ARCADE, Score.POINTS, "Przebijaj balony, zanim odlecą. Unikaj czarnych! 30 sekund."),
        solo("mole", "Kret", Cat.ARCADE, Score.POINTS, "Uderzaj krety, które wychodzą z dziur. 30 sekund."),
        solo("aim", "Celownik", Cat.ARCADE, Score.TIME_LOW, "Trafiaj w 20 celów jak najszybciej."),
        solo("tapper", "Klikacz", Cat.ARCADE, Score.POINTS, "Ile razy dotkniesz ekranu w 10 sekund?"),
        solo("eggs", "Łap jajka", Cat.ARCADE, Score.POINTS, "Jak w starym „Wilku i zającu”: łap jajka do koszyka. Trzy stłuczone i koniec."),
        // ---- logiczne ----
        solo("2048", "2048", Cat.PUZZLE, Score.POINTS, "Przesuwaj kafelki i łącz takie same liczby. Dojdziesz do 2048?"),
        solo("mines", "Saper", Cat.PUZZLE, Score.TIME_LOW, "Odkryj wszystkie pola bez min. Przytrzymaj, aby postawić flagę."),
        solo("sudoku", "Sudoku", Cat.PUZZLE, Score.TIME_LOW, "Uzupełnij planszę cyframi 1–9 tak, by nie powtarzały się w wierszu, kolumnie i kwadracie."),
        solo("fifteen", "Piętnastka", Cat.PUZZLE, Score.MOVES, "Ułóż kafelki od 1 do 15, przesuwając je na puste miejsce."),
        solo("lights", "Zgaś światła", Cat.PUZZLE, Score.MOVES, "Dotknięcie przełącza lampkę i jej sąsiadów. Zgaś wszystkie."),
        solo("flood", "Zalewanie", Cat.PUZZLE, Score.MOVES, "Zmieniaj kolor lewego górnego pola, aż cała plansza będzie w jednym kolorze."),
        solo("maze", "Labirynt", Cat.PUZZLE, Score.TIME_LOW, "Doprowadź kulkę do wyjścia. Przesuwaj palcem."),
        solo("hanoi", "Wieże Hanoi", Cat.PUZZLE, Score.MOVES, "Przenieś wieżę na prawy słupek. Większy krążek nie może leżeć na mniejszym."),
        solo("peg", "Samotnik", Cat.PUZZLE, Score.PEGS, "Przeskakuj pionkiem nad sąsiadem, który znika. Zostaw jak najmniej pionków."),
        solo("sokoban", "Magazynier", Cat.PUZZLE, Score.MOVES, "Wepchnij wszystkie skrzynie na oznaczone miejsca (Sokoban)."),
        solo("mastermind", "Łamacz kodów", Cat.PUZZLE, Score.TRIES, "Odgadnij kod z 4 kolorów. Po każdej próbie dostajesz podpowiedź."),
        solo("match3", "Klejnoty", Cat.PUZZLE, Score.POINTS, "Zamieniaj sąsiednie klejnoty, układając trzy w rzędzie. Masz 25 ruchów."),
        solo("lines", "Kulki", Cat.PUZZLE, Score.POINTS, "Przestawiaj kulki i układaj 5 w linii. Po każdym ruchu pojawiają się nowe."),
        // ---- pamięć i refleks ----
        solo("memory", "Pary", Cat.BRAIN, Score.MOVES, "Odkrywaj po dwie karty i znajdź wszystkie pary."),
        solo("simon", "Simon", Cat.BRAIN, Score.LEVEL, "Powtarzaj coraz dłuższą sekwencję kolorów."),
        solo("digits", "Pamięć cyfr", Cat.BRAIN, Score.LEVEL, "Zapamiętaj liczbę i wpisz ją. Co poziom — o cyfrę więcej."),
        solo("chimp", "Test szympansa", Cat.BRAIN, Score.LEVEL, "Zapamiętaj, gdzie są liczby, i dotykaj ich po kolei — po pierwszym dotknięciu znikają."),
        solo("reflex", "Refleks", Cat.BRAIN, Score.MS, "Dotknij, gdy ekran zrobi się zielony. Średnia z 5 prób."),
        solo("schulte", "Tabela Schultego", Cat.BRAIN, Score.TIME_LOW, "Dotykaj liczb od 1 do 25 jak najszybciej."),
        solo("shade", "Inny odcień", Cat.BRAIN, Score.LEVEL, "Znajdź pole w nieco innym kolorze. Z każdym poziomem trudniej."),
        solo("stopwatch", "Stoper 10 s", Cat.BRAIN, Score.MS, "Zatrzymaj stoper dokładnie na 10,00 s — licznik znika po 3 sekundach."),
        solo("stroop", "Kolory", Cat.BRAIN, Score.POINTS, "Wybierz KOLOR napisu, nie to, co jest napisane. 30 sekund."),
        solo("math", "Szybkie liczenie", Cat.BRAIN, Score.POINTS, "Rozwiąż jak najwięcej działań w 60 sekund."),
        // ---- słowa i quizy ----
        solo("wordle", "Literaki", Cat.WORDS, Score.TRIES, "Odgadnij pięcioliterowe słowo w 6 próbach. Kolory podpowiadają, które litery pasują."),
        solo("hangsolo", "Wisielec", Cat.WORDS, Score.MISTAKES, "Zgaduj litery słowa. Im mniej błędów, tym lepiej."),
        solo("anagram", "Anagramy", Cat.WORDS, Score.POINTS, "Ułóż słowo z pomieszanych liter. 60 sekund."),
        solo("typing", "Szybkie pisanie", Cat.WORDS, Score.TIME_LOW, "Przepisz zdanie bez błędów jak najszybciej."),
        solo("quiz", "Quiz wiedzy", Cat.WORDS, Score.POINTS, "10 pytań z różnych dziedzin. Szybka dobra odpowiedź daje więcej punktów."),
        solo("truefalse", "Prawda czy fałsz", Cat.WORDS, Score.POINTS, "Oceniaj zdania: prawda czy fałsz? 60 sekund."),
        // ---- karty i kości ----
        solo("blackjack", "Oczko", Cat.CARDS, Score.COINS, "Dobieraj karty, żeby zbliżyć się do 21, i pokonaj krupiera. 10 rozdań."),
        solo("higherlower", "Wyższa czy niższa", Cat.CARDS, Score.POINTS, "Zgadnij, czy następna karta będzie wyższa, czy niższa. Ile trafisz z rzędu?"),
        solo("slots", "Jednoręki bandyta", Cat.CARDS, Score.COINS, "20 zakręceń — ile żetonów zostanie?"),
        solo("yahtzee", "Generał", Cat.CARDS, Score.POINTS, "Gra w kości: 13 kolejek, trzy rzuty w kolejce, wybierz najlepszą kategorię."),
        solo("guessnum", "Zgadnij liczbę", Cat.CARDS, Score.TRIES, "Komputer wybrał liczbę od 1 do 1000. Zgadnij ją w jak najmniejszej liczbie prób."),
        // ---- planszowe (we dwoje, sam — z komputerem) ----
        versus("ttt", "Kółko i krzyżyk", "Kto pierwszy ułoży trzy w rzędzie."),
        versus("c4", "Czwórki", "Wrzucaj krążki do kolumn — wygrywa, kto pierwszy ułoży cztery w linii."),
        versus("gomoku", "Pięć w rzędzie", "Kółko i krzyżyk na dużej planszy: wygrywa pięć w linii."),
        versus("reversi", "Reversi", "Otaczaj pionki przeciwnika, by zmieniły kolor. Na koniec liczy się, kogo jest więcej."),
        versus("checkers", "Warcaby", "Bicie obowiązkowe, damki chodzą po przekątnych o dowolną liczbę pól."),
        versus("chess", "Szachy", "Pełne zasady: roszada, bicie w przelocie, promocja, mat i pat."),
        versus("dots", "Kropki i kreski", "Rysuj kreski; kto zamknie kwadrat, dostaje punkt i rusza się jeszcze raz."),
        versus("nim", "Zapałki", "Bierz 1–3 zapałki. Kto weźmie ostatnią, przegrywa."),
        versus("mancala", "Kalaha", "Afrykańska gra w kamyki: rozsiewaj je po dołkach i zbierz najwięcej w swoim magazynie."),
        versus("morris", "Młynek", "Ustawiaj pionki i układaj młynki (trzy w linii), by zbijać pionki przeciwnika."),
        versus("battleship", "Statki", "Ustaw flotę i strzelaj na zmianę. Kto pierwszy zatopi wszystkie statki?"),
        Info("rps", "Kamień, papier, nożyce", "Każdy wybiera w tajemnicy — wynik widać, gdy wszyscy zagrają.", Cat.BOARD, true, Chat.VERSUS, Score.WDL, "2+", ai = true),
        // ---- imprezowe (w rozmowie) ----
        group("dice", "Rzut kośćmi", Cat.PARTY, "Dwie kości, wynik widzą wszyscy.", Score.POINTS),
        group("fast", "Kto szybciej?", Cat.PARTY, "Test refleksu — każdy próbuje raz, wygrywa najszybszy.", Score.MS),
        group("hangman", "Wisielec w grupie", Cat.WORDS, "Ty wymyślasz słowo, a reszta zgaduje litery. Kto odgadnie całe, wygrywa.", Score.POINTS, "2+"),
        group("guessgroup", "Zgadnij liczbę w grupie", Cat.PARTY, "Telefon losuje liczbę 1–100. Wszyscy zgadują na zmianę, podpowiedzi: więcej / mniej."),
        group("emoji", "Emoji-zagadki", Cat.WORDS, "Odgadnij tytuł filmu, przysłowie lub rzecz z emoji. Pierwszy poprawny wygrywa."),
        group("quick", "Pytanie na czas", Cat.WORDS, "Jedno pytanie z czterema odpowiedziami. Liczy się poprawna i najszybsza odpowiedź.", Score.MS),
        group("typerace", "Kto pierwszy napisze", Cat.WORDS, "Na ekranie pojawia się słowo — kto pierwszy przepisze je bez błędu?", Score.MS),
        group("wyr", "Co wolisz?", Cat.PARTY, "Dwie opcje — każdy wybiera, a potem widać, kto co wybrał."),
        group("never", "Nigdy przenigdy", Cat.PARTY, "Każdy odpowiada: „ja tak” albo „ja nigdy”."),
        group("truth", "Prawda czy wyzwanie", Cat.PARTY, "Losowa osoba dostaje pytanie albo wyzwanie.", players = "2+"),
        group("whoof", "Kto z nas…?", Cat.PARTY, "Głosujcie, kto z grupy najbardziej pasuje do opisu.", players = "3+", groupOnly = true),
        group("ball8", "Magiczna kula", Cat.PARTY, "Zadaj pytanie i potrząśnij kulą — odpowiedź zobaczą wszyscy."),
        group("coin", "Rzut monetą", Cat.CARDS, "Orzeł czy reszka?"),
        group("pick", "Losowanie", Cat.PARTY, "Losowa osoba z rozmowy albo liczba — np. kto płaci za pizzę."),
        group("card", "Losowa karta", Cat.CARDS, "Każdy ciągnie kartę z tej samej talii — najwyższa wygrywa.", Score.POINTS),
        group("roulette", "Ruletka", Cat.CARDS, "Każdy obstawia czerwone, czarne, parzyste albo nieparzyste — kręcimy kołem."),
        group("dicewar", "Kto więcej wyrzuci?", Cat.CARDS, "Każdy rzuca trzema kośćmi — wygrywa najwyższy wynik.", Score.POINTS),
        group("bjgroup", "Oczko w grupie", Cat.CARDS, "Każdy dobiera karty, by zbliżyć się do 21. Wygrywa najbliższy, kto nie przekroczy.", Score.POINTS),
    )

    /** Wszystkie gry: wbudowane + w Pythonie. */
    fun all(): List<Info> = BUILTIN + PyGames.infos()

    fun byId(id: String): Info? = BUILTIN.firstOrNull { it.id == id } ?: PyGames.info(id)

    /** Gry do wyboru w rozmowie (w prywatnej bez tych, które mają sens tylko w grupie). */
    fun forChat(isGroup: Boolean): List<Info> = all().filter { it.inChat && (isGroup || !it.groupOnly) }

    fun forSolo(): List<Info> = all().filter { it.solo }

    fun fmtTime(ms: Double): String {
        val t = ms.coerceAtLeast(0.0)
        return if (t < 60_000) String.format(Locale("pl"), "%.2f s", t / 1000.0)
        else {
            val s = (t / 1000.0)
            String.format(Locale("pl"), "%d:%04.1f", (s / 60).toInt(), s % 60).replace('.', ',')
        }
    }

    fun plural(n: Long, one: String, few: String, many: String): String = when {
        n == 1L -> one
        n % 10 in 2..4 && n % 100 !in 12..14 -> few
        else -> many
    }
}
