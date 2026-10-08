package pl.szept

import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToString
import org.json.JSONObject
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import pl.szept.crypto.B64
import pl.szept.crypto.Crypto
import pl.szept.crypto.hex
import pl.szept.data.Kind
import pl.szept.data.MediaMeta
import pl.szept.data.Message
import pl.szept.data.St
import pl.szept.data.XMode
import pl.szept.data.dmChatId
import pl.szept.ui.MainActivity
import java.io.ByteArrayOutputStream

/** Przejście przez interfejs w Robolectric — wyłapuje awarie ekranów. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 34, 35], qualifiers = "w411dp-h891dp")
class UiTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    private fun waitText(t: String, timeout: Long = 10_000) {
        val end = System.currentTimeMillis() + timeout
        while (System.currentTimeMillis() < end) {
            rule.waitForIdle()
            if (rule.onAllNodesWithText(t, substring = true).fetchSemanticsNodes().isNotEmpty()) return
            Thread.sleep(150)
        }
        run {
            val e = AssertionError("Brak tekstu: $t")
            println("BRAK TEKSTU: $t\n" + rule.onRoot(useUnmergedTree = true).printToString())
            throw e
        }
    }

    private fun waitGone(t: String, timeout: Long = 10_000) {
        val end = System.currentTimeMillis() + timeout
        while (System.currentTimeMillis() < end) {
            rule.waitForIdle()
            if (rule.onAllNodesWithText(t, substring = true).fetchSemanticsNodes().isEmpty()) return
            Thread.sleep(150)
        }
        throw AssertionError("Nadal widać: $t")
    }

    private fun androidx.compose.ui.test.junit4.AndroidComposeTestRule<*, *>.waitUntilExists(desc: String, timeout: Long = 10_000) {
        val end = System.currentTimeMillis() + timeout
        while (System.currentTimeMillis() < end) {
            waitForIdle()
            if (onAllNodes(androidx.compose.ui.test.hasContentDescription(desc)).fetchSemanticsNodes().isNotEmpty()) return
            Thread.sleep(150)
        }
        println("BRAK: $desc\n" + onRoot(useUnmergedTree = true).printToString())
        throw AssertionError("Brak elementu: $desc")
    }

    private fun thumb(): String {
        val bmp = android.graphics.Bitmap.createBitmap(40, 30, android.graphics.Bitmap.Config.ARGB_8888)
        val bos = ByteArrayOutputStream(); bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 50, bos)
        return B64.enc(bos.toByteArray())
    }

    @Test
    fun walkThroughScreens() {
        // powitanie
        waitText("Zaczynamy")
        rule.onNode(hasText("Twoje imię", substring = true)).performTextInput("Tester")
        rule.onNodeWithText("Zaczynamy").performClick()
        waitText("Nie masz jeszcze rozmów")

        // mój kod
        rule.onNodeWithContentDescription("Mój kod").performClick()
        waitText("Wyślij link")
        rule.runOnUiThread { rule.activity.nav.back() }

        // dodaj kontakt
        rule.onNodeWithContentDescription("Dodaj kontakt").performClick()
        waitText("Skanuj kod QR")
        val peerPriv = Crypto.newPrivateKey(); val peer = Crypto.publicKey(peerPriv).hex()
        rule.onNode(hasText("szept://", substring = true)).performTextInput("szept://u/$peer?n=Kasia")
        waitText("Kontakt: Kasia")
        rule.onNodeWithText("Dodaj kontakt").performClick()
        waitText("Kasia")
        waitText("szyfrowanie end-to-end")

        // wstaw różne typy wiadomości do czatu
        val core = App.core
        val chat = dmChatId(peer)
        val now = System.currentTimeMillis()
        fun media(kind: String) = MediaMeta(Crypto.randomBytes(8).hex(), B64.enc(Crypto.randomBytes(32)), 1234, 1, "x/y", "00", "plik.pdf", thumb(), 40, 30, 5000)
        val msgs = listOf(
            Message("t1", chat, peer, now, Kind.TEXT, "Cześć, co słychać?", null, null, St.RECEIVED, 0, 0, 0, 0, 0, false),
            Message("t2", chat, core.myPub, now + 1, Kind.TEXT, "Wszystko OK", null, null, St.READ, 0, 0, now, 0, 0, true),
            Message("i1", chat, peer, now + 2, Kind.IMAGE, "fotka", media(Kind.IMAGE), null, St.DOWNLOADING, 0, 0, 0, 0, 40, false),
            Message("v1", chat, peer, now + 3, Kind.VOICE, "", media(Kind.VOICE), null, St.DOWNLOADING, 0, 0, 0, 0, 0, false),
            Message("w1", chat, peer, now + 4, Kind.VIDEO, "", media(Kind.VIDEO), null, St.DOWNLOAD_FAILED, 0, 0, 0, 0, 0, false),
            Message("f1", chat, core.myPub, now + 5, Kind.FILE, "", media(Kind.FILE), null, St.UPLOADING, 0, 0, now, 0, 30, true),
            Message("o1", chat, peer, now + 6, Kind.TEXT, "sekret", null, null, St.RECEIVED, XMode.AFTER_OPEN, 10, 0, 0, 0, false),
            Message("x1", chat, peer, now + 7, Kind.TEXT, "zniknę", null, null, St.RECEIVED, XMode.AFTER_SEND, 30, 0, now + 30_000, 0, false),
            Message("s1", chat, core.myPub, now + 8, Kind.SYSTEM, "Wiadomość systemowa", null, null, St.RECEIVED, 0, 0, 1, 0, 0, false),
            Message("o2", chat, core.myPub, now + 9, Kind.IMAGE, "", null, null, St.OPENED, XMode.AFTER_OPEN, 10, now, now + 60_000, 0, true),
        )
        msgs.forEach { core.db.insertMessage(it) }
        waitText("Wiadomość czasowa")
        waitText("Wiadomość systemowa")
        waitText("Otwarto")
        rule.onNode(hasScrollAction()).performScrollToNode(hasText("Wszystko OK"))
        waitText("Wszystko OK")
        waitText("Pobieranie")
        waitText("Nie udało się pobrać")
        rule.onNode(hasScrollAction()).performScrollToIndex(0)

        // wiadomość czasowa: treść ukryta, czas nie biegnie, dopóki jej nie dotkniesz
        check(rule.onAllNodesWithText("sekret").fetchSemanticsNodes().isEmpty()) { "treść czasowej widoczna przed dotknięciem" }
        check(core.db.message("o1")!!.expires == 0L) { "czas nie powinien biec przed otwarciem" }
        rule.onNodeWithText("Dotknij, aby przeczytać", substring = true).performClick()
        waitText("sekret")
        val endOpen = System.currentTimeMillis() + 5_000
        while (core.db.message("o1")?.expires == 0L) {
            check(System.currentTimeMillis() < endOpen) { "odliczanie nie ruszyło" }
            rule.waitForIdle(); Thread.sleep(100)
        }

        // ustawienie czatu: wiadomości czasowe
        rule.onNodeWithContentDescription("Więcej").performClick()
        rule.onAllNodesWithText("Wiadomości czasowe", substring = true).onLast().performClick()
        waitText("Treść widać dopiero po dotknięciu")
        rule.onNodeWithText("Treść widać dopiero po dotknięciu", substring = true).performClick()
        rule.onNodeWithText("Zapisz").performClick()
        waitText("widać je po dotknięciu")

        // wysłanie tekstu (trafia do kolejki)
        rule.onNode(hasText("Wiadomość")).performTextInput("Testowa wiadomość")
        rule.onNodeWithContentDescription("Wyślij (przytrzymaj: czasowa lub zaplanuj)").performClick()
        val endSend = System.currentTimeMillis() + 10_000
        while (core.db.messages(chat, 100).none { it.mine && it.kind == Kind.TEXT && it.xmode == XMode.AFTER_OPEN && it.ts > now + 100 }) {
            check(System.currentTimeMillis() < endSend) { "wiadomość nie została zapisana" }
            rule.waitForIdle(); Thread.sleep(150)
        }

        // wyłącz timer czatu
        rule.onNodeWithContentDescription("Więcej").performClick()
        rule.onAllNodesWithText("Wiadomości czasowe", substring = true).onLast().performClick()
        waitText("Wyłączone")
        rule.onNodeWithText("Wyłączone").performClick()
        rule.onNodeWithText("Zapisz").performClick()
        val endTimer = System.currentTimeMillis() + 10_000
        while (core.db.chat(chat)?.xmode != XMode.OFF) {
            check(System.currentTimeMillis() < endTimer) { "timer nie wyłączony" }
            rule.waitForIdle(); Thread.sleep(150)
        }
        rule.onNode(hasScrollAction()).performScrollToNode(hasText("Dzisiaj"))
        waitText("Dzisiaj")

        // odpowiedź na wiadomość
        rule.onNode(hasScrollAction()).performScrollToNode(hasText("Wszystko OK"))
        rule.onNodeWithText("Wszystko OK").performTouchInput { longClick() }
        waitText("Odpowiedz")
        rule.onNodeWithText("Odpowiedz").performClick()
        waitText("Odpowiadasz: Ty")
        rule.onNode(hasText("Wiadomość")).performTextInput("To moja odpowiedź")
        rule.onNodeWithContentDescription("Wyślij (przytrzymaj: czasowa lub zaplanuj)").performClick()
        val endReply = System.currentTimeMillis() + 10_000
        while (core.db.messages(chat, 100).none { it.body == "To moja odpowiedź" && it.reply?.id == "t2" }) {
            check(System.currentTimeMillis() < endReply) { "odpowiedź nie zapisana" }
            rule.waitForIdle(); Thread.sleep(150)
        }

        // przytrzymanie Wyślij = ta jedna wiadomość jako czasowa
        rule.onNode(hasText("Wiadomość")).performTextInput("Zobaczysz mnie po dotknięciu")
        rule.onNodeWithContentDescription("Wyślij (przytrzymaj: czasowa lub zaplanuj)").performTouchInput { longClick() }
        waitText("30 s po otwarciu")
        rule.onNodeWithText("30 s po otwarciu", substring = true).performClick()
        val endEph = System.currentTimeMillis() + 10_000
        while (core.db.messages(chat, 100).none { it.mine && it.kind == Kind.TEXT && it.xmode == XMode.AFTER_OPEN && it.ttl == 30 }) {
            check(System.currentTimeMillis() < endEph) { "czasowa nie zapisana" }
            rule.waitForIdle(); Thread.sleep(150)
        }
        check(core.db.chat(chat)?.xmode == XMode.OFF) { "ustawienie czatu nie powinno się zmienić" }

        // głosówki: gesty mikrofonu
        org.robolectric.Shadows.shadowOf(org.robolectric.RuntimeEnvironment.getApplication()).grantPermissions(android.Manifest.permission.RECORD_AUDIO)
        val mic = rule.onNodeWithContentDescription("Nagraj głosówkę")
        // przytrzymaj i przesuń w lewo = anuluj
        mic.performTouchInput { down(center) }
        waitText("przesuń, aby anulować")
        mic.performTouchInput { moveBy(androidx.compose.ui.geometry.Offset(-700f, 0f)) }
        mic.performTouchInput { up() }
        waitGone("przesuń, aby anulować")
        // krótkie dotknięcie = nagrywanie bez trzymania
        mic.performTouchInput { down(center); up() }
        rule.waitUntilExists("Wyślij nagranie")
        waitText("Czasowa")
        rule.onNodeWithContentDescription("Anuluj nagranie").performClick()
        rule.waitUntilExists("Nagraj głosówkę")
        // przytrzymaj i przesuń w górę = blokada
        rule.onNodeWithContentDescription("Nagraj głosówkę").performTouchInput { down(center) }
        waitText("przesuń, aby anulować")
        rule.onNodeWithContentDescription("Nagraj głosówkę").performTouchInput { moveBy(androidx.compose.ui.geometry.Offset(0f, -400f)) }
        rule.waitUntilExists("Wyślij nagranie")
        rule.onRoot().performTouchInput { up() }   // mikrofon zniknął — palec puszczony nad czatem
        rule.waitUntilExists("Wyślij nagranie")    // nagrywanie trwa dalej bez trzymania
        rule.onNodeWithContentDescription("Anuluj nagranie").performClick()
        // przytrzymaj i puść = wyślij (w Robolectric nie powstaje plik — aplikacja nie może się wywrócić)
        rule.waitUntilExists("Nagraj głosówkę")
        rule.onNodeWithContentDescription("Nagraj głosówkę").performTouchInput { down(center) }
        waitText("przesuń, aby anulować")
        Thread.sleep(900)
        rule.onNodeWithContentDescription("Nagraj głosówkę").performTouchInput { up() }
        waitGone("przesuń, aby anulować")

        // informacje o kontakcie
        rule.runOnUiThread { rule.activity.nav.go(pl.szept.ui.Screen.ContactInfo(peer)) }
        waitText("Odcisk klucza")

        // nowa grupa
        rule.runOnUiThread { rule.activity.nav.home(); rule.activity.nav.go(pl.szept.ui.Screen.NewGroup) }
        waitText("Nazwa grupy")
        rule.onNode(hasText("Nazwa grupy")).performTextInput("Ekipa")
        rule.onNodeWithText("Kasia").performClick()
        rule.onNodeWithText("Utwórz", useUnmergedTree = true).performClick()
        waitText("Utworzono grupę")
        rule.runOnUiThread { rule.activity.nav.go(pl.szept.ui.Screen.GroupInfo(core.db.groups().first().id)) }
        waitText("Link zaproszenia")
        // wyciszenie grupy
        val gid = core.db.groups().first().id
        rule.onNode(hasScrollAction()).performScrollToNode(hasText("Wycisz powiadomienia"))
        rule.onNode(isToggleable() and androidx.compose.ui.test.hasAnySibling(hasText("Wycisz powiadomienia")), useUnmergedTree = true).performClick()
        val endMute = System.currentTimeMillis() + 10_000
        while (core.db.chat("g:$gid")?.muted != true) {
            check(System.currentTimeMillis() < endMute) { "wyciszenie nie zapisane" }
            rule.waitForIdle(); Thread.sleep(150)
        }
        rule.onNode(hasScrollAction()).performScrollToNode(hasText("Link zaproszenia"))
        rule.onNodeWithText("Link zaproszenia").performClick()
        waitText("Wyślij link")

        // ustawienia
        rule.runOnUiThread { rule.activity.nav.home(); rule.activity.nav.go(pl.szept.ui.Screen.Settings) }
        waitText("Osoby")
        waitText("Prywatność i bezpieczeństwo")
        rule.onNode(hasScrollAction()).performScrollToNode(hasText("Szept ${BuildConfig.VERSION_NAME}"))
        waitText("Szept ${BuildConfig.VERSION_NAME}")
        // połączenie i bateria
        rule.runOnUiThread { rule.activity.nav.go(pl.szept.ui.Screen.Connection) }
        waitText("Oszczędzanie baterii")
        rule.onNodeWithText("Oszczędzanie baterii").performClick()
        check(core.bgMode == pl.szept.core.Core.BG_SAVER) { "tryb tła nie zmieniony" }
        rule.onNodeWithText("Normalnie (zalecane)").performClick()
        check(core.bgMode == pl.szept.core.Core.BG_BALANCED)
        rule.onNodeWithText("Dla zaawansowanych: przekaźniki").performClick()
        rule.onNode(hasScrollAction()).performScrollToNode(hasText("Przywróć domyślne"))
        waitText("Przywróć domyślne")

        // mój profil
        rule.runOnUiThread { rule.activity.nav.home(); rule.activity.nav.go(pl.szept.ui.Screen.MyProfile) }
        waitText("Twój stały ID")
        waitText(core.myShortId)
        rule.onNode(hasText("Status", substring = true)).performTextInput("🎮 gram")
        rule.onNodeWithText("Zapisz i wyślij znajomym").performClick()
        val endProf = System.currentTimeMillis() + 5_000
        while (core.myStatus != "🎮 gram") { check(System.currentTimeMillis() < endProf) { "status nie zapisany" }; rule.waitForIdle(); Thread.sleep(100) }
        // osoby
        rule.runOnUiThread { rule.activity.nav.home(); rule.activity.nav.go(pl.szept.ui.Screen.People) }
        waitText("Kasia")
        // prywatność i blokada
        rule.runOnUiThread { rule.activity.nav.home(); rule.activity.nav.go(pl.szept.ui.Screen.Security) }
        waitText("Ustaw PIN")
        waitText("Kod awaryjny")
        // kod dostępu
        rule.runOnUiThread { rule.activity.nav.home(); rule.activity.nav.go(pl.szept.ui.Screen.AccessCode) }
        rule.onNodeWithText("Pokaż kod dostępu").performClick()
        waitText(core.myPriv.joinToString("") { "%02x".format(it) }.take(16))

        // nowa rozmowa
        rule.runOnUiThread { rule.activity.nav.home(); rule.activity.nav.go(pl.szept.ui.Screen.NewChat) }
        waitText("Nowa grupa")
        rule.runOnUiThread { rule.activity.nav.home() }
        waitText("Ekipa")
        println("UI OK")
    }
}
