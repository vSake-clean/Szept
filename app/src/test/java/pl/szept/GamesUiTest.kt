package pl.szept

import android.graphics.Bitmap
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.printToString
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import pl.szept.core.GameCatalog
import pl.szept.crypto.Crypto
import pl.szept.crypto.hex
import pl.szept.data.dmChatId
import pl.szept.ui.MainActivity
import pl.szept.ui.Screen
import pl.szept.ui.games.Play
import pl.szept.ui.games.SOLO_GAMES
import java.io.File

/**
 * Gry w interfejsie: menu spinacza, wybór gry, dymki gier w rozmowie, gry na pełnym ekranie i tabela „Łącznie”.
 * Zmienna środowiskowa SZEPT_SHOTS=katalog zapisuje zrzuty ekranów (do obejrzenia).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h760dp-xhdpi")
class GamesUiTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    private val shots = System.getenv("SZEPT_SHOTS")?.let { File(it).apply { mkdirs() } }

    /** Gra na pełnym ekranie to stan globalny — każdy test zaczyna bez niej. */
    @org.junit.Before fun noGame() { Play.req = null }

    /** Zrzut wszystkich okien (aktywność, okna dialogowe) — rysowanie widoków do bitmapy. */
    private fun snap(name: String) {
        val dir = shots ?: return
        rule.waitForIdle()
        rule.runOnUiThread {
            val wmg = Class.forName("android.view.WindowManagerGlobal")
            val inst = wmg.getMethod("getInstance").invoke(null)
            val f = wmg.getDeclaredField("mViews").apply { isAccessible = true }
            @Suppress("UNCHECKED_CAST") val views = (f.get(inst) as List<android.view.View>).filter { it.isShown && it.width > 0 && it.height > 0 }
            views.forEachIndexed { i, v ->
                val bmp = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
                v.draw(android.graphics.Canvas(bmp))
                File(dir, "$name${if (views.size > 1) "-$i" else ""}.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 90, it) }
            }
        }
    }

    private fun waitText(t: String, timeout: Long = 10_000) {
        val end = System.currentTimeMillis() + timeout
        while (System.currentTimeMillis() < end) {
            rule.waitForIdle()
            if (rule.onAllNodesWithText(t, substring = true).fetchSemanticsNodes().isNotEmpty()) return
            Thread.sleep(150)
        }
        snap("blad-brak-tekstu")
        println("BRAK TEKSTU: $t\n" + rule.onAllNodes(isRoot(), useUnmergedTree = true).printToString(Int.MAX_VALUE))
        throw AssertionError("Brak tekstu: $t")
    }

    private fun until(what: String, timeout: Long = 10_000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + timeout
        while (!cond()) {
            check(System.currentTimeMillis() < end) { "Nie spełniono: $what" }
            rule.waitForIdle(); Thread.sleep(120)
        }
    }

    @Test
    fun gamesEverywhere() {
        waitText("Zaczynamy")
        rule.onNode(hasText("Twoje imię", substring = true)).performTextInput("Tester")
        rule.onNodeWithText("Zaczynamy").performClick()
        waitText("Nie masz jeszcze rozmów")
        val core = App.core
        val peer = Crypto.publicKey(Crypto.newPrivateKey()).hex()
        core.addContact(peer, "Kasia")
        val chat = dmChatId(peer)
        waitText("Kasia")
        rule.onNodeWithText("Kasia").performClick()

        // ---- spinacz → Gra (ikona jak inne pozycje) ----
        rule.onNodeWithContentDescription("Załącz").performClick()
        waitText("Lokalizacja")
        snap("01-spinacz")
        rule.onNodeWithText("Gra").performClick()
        waitText("Zagraj")
        waitText("Szukaj gry")
        snap("02-wybor-gry")
        rule.onNode(hasText("Szukaj gry") and androidx.compose.ui.test.hasSetTextAction()).performTextInput("czwórki")
        waitText("Wrzucaj krążki")
        rule.onNodeWithText("Czwórki").performClick()
        waitText("Wyślij do rozmowy")
        snap("03-szczegoly")
        rule.onNodeWithText("Wyślij do rozmowy").performClick()
        until("gra wysłana") { core.db.gameMessages(chat, "c4").isNotEmpty() }

        // ---- różne gry w rozmowie ----
        kotlinx.coroutines.runBlocking {
            for (t in listOf("chess", "hangman", "quick", "snake", "wyr", "dice", "battleship", "roulette")) core.startGame(chat, t)
        }
        val c4 = core.db.gameMessages(chat, "c4").single()
        core.gameMove(c4.id, "3")
        until("ruch zapisany") { core.db.gameMoves(c4.id).isNotEmpty() }
        val snake = core.db.gameMessages(chat, "snake").single()
        core.gameMove(snake.id, "17")
        core.db.addGameMove(snake.id, peer, 0, "23", System.currentTimeMillis())
        until("wynik w wyzwaniu") { core.db.gameMoves(snake.id).size == 2 }
        snap("04-czat")
        for ((k, t) in listOf("Ruletka" to "Ruletka", "Statki" to "Statki", "Kosci" to "Rzut kośćmi", "CoWolisz" to "Co wolisz", "Pytanie" to "Pytanie na czas", "Wyzwanie" to "Wyzwanie", "Wisielec" to "Wisielec w grupie", "Szachy" to "Szachy")) {
            rule.onNode(androidx.compose.ui.test.hasScrollAction()).performScrollToNode(hasText(t, substring = true))
            snap("05-czat-$k")
        }

        // ---- gry na pełnym ekranie ----
        for (id in listOf("2048", "sudoku", "tetris", "chess", "wordle", "mines", "blackjack", "yahtzee", "c4", "mancala", "morris", "battleship", "eggs", "maze")) {
            rule.runOnUiThread { Play.start(rule.activity, id, seed = 42) }
            rule.waitForIdle()
            Thread.sleep(300)
            snap("10-gra-$id")
            rule.runOnUiThread { Play.req = null }
        }

        // ---- wynik i „Łącznie” ----
        listOf(1200.0, 3400.0, 800.0).forEach { core.db.addGameScore("2048", it, "solo") }
        rule.runOnUiThread { rule.activity.nav.home() }
        rule.runOnUiThread { Play.start(rule.activity, "2048", seed = 1) }
        waitText("Przesuwaj palcem")
        rule.onNodeWithText("Łącznie").performClick()
        waitText("najlepszy wynik")
        snap("20-lacznie")
        assertTrue(rule.onAllNodesWithText("3400 pkt", substring = true).fetchSemanticsNodes().isNotEmpty())
        rule.runOnUiThread { Play.req = null }

        // ---- ekran „Gry” ----
        rule.runOnUiThread { rule.activity.nav.home(); rule.activity.nav.go(Screen.Games) }
        waitText("Moje wyniki")
        snap("30-gry")

        // wszystkie gry z katalogu (poza Pythonem) dają się uruchomić albo zagrać w rozmowie
        for (g in GameCatalog.BUILTIN) assertTrue(g.id, !g.solo || SOLO_GAMES.containsKey(g.id))
    }

    @Test
    fun ticTacToeAgainstComputer() {
        waitText("Zaczynamy")
        rule.onNode(hasText("Twoje imię", substring = true)).performTextInput("Gracz")
        rule.onNodeWithText("Zaczynamy").performClick()
        waitText("Nie masz jeszcze rozmów")
        val core = App.core
        rule.runOnUiThread { Play.start(rule.activity, "ttt", seed = 7) }
        waitText("Ty (✕) vs komputer")
        val end = System.currentTimeMillis() + 30_000
        while (core.db.gameScores("ttt").isEmpty()) {
            if (System.currentTimeMillis() > end) {
                snap("blad-ttt")
                println(rule.onAllNodes(isRoot(), useUnmergedTree = true).printToString(Int.MAX_VALUE))
                error("gra się nie skończyła")
            }
            rule.waitForIdle()
            // ruch na pierwsze wolne pole, gdy jest moja kolej
            val free = (1..9).firstOrNull { n ->
                val nodes = rule.onAllNodes(androidx.compose.ui.test.hasContentDescription("Pole $n", substring = false)).fetchSemanticsNodes()
                nodes.isNotEmpty() && nodes[0].config.contains(androidx.compose.ui.semantics.SemanticsActions.OnClick) &&
                    !nodes[0].config.contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled)
            }
            if (free != null && rule.onAllNodesWithText("Twój ruch", substring = true).fetchSemanticsNodes().isNotEmpty()) {
                rule.onNodeWithContentDescription("Pole $free").performClick()
            }
            // „komputer myśli” czeka chwilę (delay) — w teście przesuwamy zegar ręcznie
            rule.mainClock.advanceTimeBy(600)
            Thread.sleep(100)
        }
        val score = core.db.gameScores("ttt").single()
        assertTrue(score.score in listOf(0.0, 0.5, 1.0))
        assertTrue(score.ctx == "ai")
        rule.mainClock.advanceTimeBy(2000)
        waitText("Zagraj jeszcze raz")
        snap("40-ttt-wynik")
    }
}
