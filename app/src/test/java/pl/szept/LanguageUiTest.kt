package pl.szept

import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.printToString
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import pl.szept.ui.MainActivity
import pl.szept.ui.Screen

/**
 * Język (3.4): Ustawienia → Język przełącza cały interfejs na angielski i z powrotem,
 * kreator zaczyna się od wyboru języka (flagi), a konto zostaje bez zmian.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h800dp")
class LanguageUiTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    // wygląd jak po świeżej instalacji — ustawienia w pamięci zostają po poprzednich testach w tym samym procesie
    init { pl.szept.ui.UiPrefs.reload(org.robolectric.RuntimeEnvironment.getApplication()) }


    @After fun back() = I18n.set(rule.activity, "pl")

    private fun waitText(t: String, timeout: Long = 10_000) {
        val end = System.currentTimeMillis() + timeout
        while (System.currentTimeMillis() < end) {
            rule.waitForIdle()
            if (rule.onAllNodesWithText(t, substring = true).fetchSemanticsNodes().isNotEmpty()) return
            Thread.sleep(150)
        }
        println("BRAK TEKSTU: $t screen=" + rule.activity.nav.current + "\n" +
            rule.onAllNodes(androidx.compose.ui.test.isRoot(), useUnmergedTree = true).printToString(Int.MAX_VALUE))
        throw AssertionError("Brak tekstu: $t")
    }

    private fun androidx.compose.ui.test.junit4.AndroidComposeTestRule<*, *>.onAllNodesWithText(t: String, substring: Boolean) =
        onAllNodes(hasText(t, substring = substring))

    private fun go(s: Screen) = rule.runOnUiThread { rule.activity.nav.home(); rule.activity.nav.go(s) }

    @Test
    fun switchToEnglishAndBack() {
        waitText("Zaczynamy")
        rule.onNode(hasText("Twoje imię", substring = true)).performTextInput("Tester")
        rule.onNodeWithText("Zaczynamy").performClick()
        val core = App.core
        val end = System.currentTimeMillis() + 10_000
        while (!core.hasIdentity) { check(System.currentTimeMillis() < end) { "konto nie powstało" }; rule.waitForIdle(); Thread.sleep(100) }
        val id = core.myShortId

        // Ustawienia: wejście „Język / Language” i „Kreator: język i wygląd”
        go(Screen.Settings)
        waitText("Język / Language")
        waitText("Kreator: język i wygląd")

        // Język → English: cały interfejs po angielsku od razu
        go(Screen.Language)
        waitText("Wybierz język aplikacji")
        rule.onNodeWithText("English").performClick()
        check(I18n.lang == "en") { "język nie zmieniony" }
        waitText("Choose the app language")
        go(Screen.Settings)
        waitText("Most used")
        waitText("Privacy & security")
        go(Screen.Connection)
        waitText("Connection & battery")

        // kreator: pierwsza strona to wybór języka z flagami; wybór działa od razu
        go(Screen.Wizard)
        waitText("Choose a language")
        rule.onNodeWithText("Polski").performClick()
        waitText("Wybierz język")
        rule.onNodeWithText("Dalej").performClick()
        waitText("Witaj w Szepcie")
        waitText("Twoje konto, nazwa, ID, rozmowy i grupy zostają bez zmian.")
        rule.onNodeWithText("Pomiń").performClick()
        check(I18n.lang == "pl" && I18n.chosen(rule.activity) == "pl") { "język nie zapamiętany" }

        // konto bez zmian
        check(core.hasIdentity && core.myShortId == id && core.myName == "Tester") { "konto się zmieniło" }
        go(Screen.Settings)
        waitText("Najczęściej używane")
    }
}
