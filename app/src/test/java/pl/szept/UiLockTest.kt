package pl.szept

import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import org.json.JSONObject
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import pl.szept.crypto.Crypto
import pl.szept.crypto.hex
import pl.szept.data.Kind
import pl.szept.data.Message
import pl.szept.data.St
import pl.szept.data.dmChatId
import pl.szept.ui.MainActivity

/** Ekran blokady: zły PIN, dobry PIN, ponowna blokada, kod awaryjny kasujący czaty. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class UiLockTest {
    @get:Rule val rule = createEmptyComposeRule()

    private fun waitText(t: String, timeout: Long = 10_000) {
        val end = System.currentTimeMillis() + timeout
        while (System.currentTimeMillis() < end) {
            rule.waitForIdle()
            if (rule.onAllNodesWithText(t, substring = true).fetchSemanticsNodes().isNotEmpty()) return
            Thread.sleep(100)
        }
        throw AssertionError("Brak tekstu: $t")
    }

    @Test
    fun lockAndWipe() {
        val core = App.core
        core.createIdentity("Tester")
        val peer = Crypto.publicKey(Crypto.newPrivateKey()).hex()
        core.addContact(peer, "Kasia")
        core.db.insertMessage(Message("m1", dmChatId(peer), peer, System.currentTimeMillis(), Kind.TEXT, "tajne", null, null, St.RECEIVED, 0, 0, 0, 0, 0, false))
        core.setPin("1234")
        core.setWipeCode("9999", JSONObject().put("scope", "all").put("block", "none").put("account", false))

        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            waitText("Szept jest zablokowany")
            check(rule.onAllNodesWithText("Kasia").fetchSemanticsNodes().isEmpty()) { "czaty widoczne przed odblokowaniem" }
            rule.onNode(hasText("PIN")).performTextInput("0000")
            rule.onNodeWithText("Odblokuj").performClick()
            waitText("Błędny PIN")
            rule.onNode(hasText("PIN")).performTextInput("1234")
            waitText("Kasia")                               // odblokowano → lista czatów
            sc.onActivity { it.relock() }
            waitText("Szept jest zablokowany")
            rule.onNode(hasText("PIN")).performTextInput("9999")   // kod awaryjny
            waitText("Nie masz jeszcze rozmów")             // czaty skasowane, aplikacja wygląda normalnie
            check(core.db.message("m1") == null) { "wiadomość nie została skasowana" }
            check(core.hasIdentity) { "konto nie powinno zniknąć (account=false)" }
        }
        println("LOCK OK")
    }
}
