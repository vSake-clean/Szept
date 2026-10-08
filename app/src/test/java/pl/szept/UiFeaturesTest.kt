package pl.szept

import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.test.printToString
import org.junit.Assert.assertTrue
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
import pl.szept.ui.Screen
import pl.szept.ui.UiPrefs

/** Nowe ekrany i funkcje 1.6 w interfejsie (Robolectric). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 34], qualifiers = "w320dp-h800dp")
class UiFeaturesTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    private fun waitText(t: String, timeout: Long = 10_000) {
        val end = System.currentTimeMillis() + timeout
        while (System.currentTimeMillis() < end) {
            rule.waitForIdle()
            if (rule.onAllNodesWithText(t, substring = true).fetchSemanticsNodes().isNotEmpty()) return
            Thread.sleep(150)
        }
        println("BRAK TEKSTU: $t\n" + rule.onAllNodes(androidx.compose.ui.test.isRoot(), useUnmergedTree = true).printToString())
        throw AssertionError("Brak tekstu: $t")
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

    private fun waitDesc(d: String, timeout: Long = 10_000) {
        val end = System.currentTimeMillis() + timeout
        while (System.currentTimeMillis() < end) {
            rule.waitForIdle()
            if (rule.onAllNodes(hasContentDescription(d, substring = true)).fetchSemanticsNodes().isNotEmpty()) return
            Thread.sleep(150)
        }
        println("BRAK: $d\n" + rule.onRoot(useUnmergedTree = true).printToString())
        throw AssertionError("Brak elementu: $d")
    }

    private fun until(what: String, timeout: Long = 10_000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + timeout
        while (!cond()) {
            check(System.currentTimeMillis() < end) { "Nie spełniono: $what" }
            rule.waitForIdle(); Thread.sleep(120)
        }
    }

    private fun go(s: Screen) = rule.runOnUiThread { rule.activity.nav.home(); rule.activity.nav.go(s) }

    @Test
    fun newFeatures() {
        waitText("Zaczynamy")
        rule.onNode(hasText("Twoje imię", substring = true)).performTextInput("Tester")
        rule.onNodeWithText("Zaczynamy").performClick()
        waitText("Nie masz jeszcze rozmów")
        waitText("Twoja relacja")
        val core = App.core
        val peer = Crypto.publicKey(Crypto.newPrivateKey()).hex()
        core.addContact(peer, "Kasia")
        val chat = dmChatId(peer)
        val now = System.currentTimeMillis()
        core.db.insertMessage(Message("k1", chat, peer, now, Kind.TEXT, "Cześć! Idziemy jutro do kina?", null, null, St.RECEIVED, 0, 0, 0, 0, 0, false))
        core.db.insertMessage(Message("k2", chat, peer, now + 1, Kind.TEXT, "Link: https://example.com/film", null, null, St.RECEIVED, 0, 0, 0, 0, 0, false))
        waitText("Kasia")

        // ---- rozmowa: reakcja ----
        rule.onNodeWithText("Kasia").performClick()
        waitText("Idziemy jutro do kina")
        rule.onNodeWithText("Cześć! Idziemy jutro do kina?").performTouchInput { longClick() }
        waitText("Odpowiedz")
        rule.onNodeWithText("👍").performClick()
        until("reakcja zapisana") { core.db.reaction("k1", core.myPub)?.emoji == "👍" }
        waitDesc("Reakcje: 👍 1")

        // ---- wysłanie, edycja ----
        rule.onNode(hasText("Wiadomość") and hasSetTextAction()).performTextInput("Jasne, o 18")
        rule.onNodeWithContentDescription("Wyślij (przytrzymaj: czasowa lub zaplanuj)").performClick()
        until("wiadomość wysłana") { core.db.messages(chat, 50).any { it.mine && it.body == "Jasne, o 18" } }
        waitText("Jasne, o 18")
        rule.onNodeWithText("Jasne, o 18").performTouchInput { longClick() }
        waitText("Edytuj")
        rule.onNodeWithText("Edytuj").performClick()
        waitText("Edytujesz wiadomość")
        rule.onNode(hasSetTextAction()).performTextClearance()
        rule.onNode(hasSetTextAction()).performTextInput("Jasne, o 19")
        rule.onNodeWithContentDescription("Zapisz zmianę").performClick()
        until("edycja zapisana") { core.db.messages(chat, 50).any { it.mine && it.body == "Jasne, o 19" && it.edited > 0 } }
        waitText("edytowano")

        // ---- przypięcie ----
        rule.onNodeWithText("Cześć! Idziemy jutro do kina?").performTouchInput { longClick() }
        waitText("Przypnij")
        rule.onNodeWithText("Przypnij").performClick()
        waitText("Przypięta wiadomość")

        // ---- ankieta ----
        rule.onNodeWithContentDescription("Załącz").performClick()
        waitText("Ankieta")
        rule.onNodeWithText("Ankieta").performClick()
        waitText("Nowa ankieta")
        rule.onNode(hasText("Pytanie")).performTextInput("Który film?")
        rule.onNode(hasText("Odpowiedź 1")).performTextInput("Komedia")
        rule.onNode(hasText("Odpowiedź 2")).performTextInput("Horror")
        rule.onNodeWithText("Wyślij").performClick()
        until("ankieta wysłana") { core.db.messages(chat, 50).any { it.kind == Kind.POLL } }
        waitGone("Nowa ankieta")
        waitText("📊 Który film?")
        rule.onNode(hasScrollAction()).performScrollToNode(hasText("Horror") and !hasSetTextAction())
        // (Robolectric 34: dotknięcie listy przy otwartej klawiaturze bywa gubione — używamy akcji dostępności)
        rule.onNode(hasText("Horror") and androidx.compose.ui.test.hasClickAction() and !hasSetTextAction())
            .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        val pollId = core.db.messages(chat, 50).first { it.kind == Kind.POLL }.id
        until("głos zapisany") { core.db.votesForChat(chat)[pollId]?.any { it.pk == core.myPub && it.sel == listOf(1) } == true }
        waitText("1 głos")

        // ---- szukanie w rozmowie ----
        rule.onNodeWithContentDescription("Więcej").performClick()
        rule.onNodeWithText("Szukaj w rozmowie").performClick()
        rule.onNode(hasText("Szukaj w rozmowie") and hasSetTextAction()).performTextInput("kina")
        waitText("1/1")
        rule.onNodeWithContentDescription("Zamknij wyszukiwanie").performClick()

        // ---- zaplanowana wiadomość ----
        rule.onNode(hasText("Wiadomość") and hasSetTextAction()).performTextInput("Przypomnienie na jutro")
        rule.onNodeWithContentDescription("Wyślij (przytrzymaj: czasowa lub zaplanuj)").performTouchInput { longClick() }
        waitText("Zaplanuj wysłanie")
        rule.onNodeWithText("🕓 Zaplanuj wysłanie…").performClick()
        waitText("Za godzinę")
        rule.onNodeWithText("Za godzinę").performClick()
        until("zaplanowana zapisana") { core.db.scheduled(chat).size == 1 }
        waitText("Zaplanowane: 1")
        rule.onNodeWithText("Zaplanowane: 1", substring = true).performClick()
        waitText("Przypomnienie na jutro")
        rule.onNodeWithText("Usuń").performClick()
        until("zaplanowana usunięta") { core.db.scheduled(chat).isEmpty() }
        rule.onNodeWithText("Zamknij").performClick()

        // ---- szkic zostaje po wyjściu z rozmowy ----
        rule.onNode(hasText("Wiadomość") and hasSetTextAction()).performTextInput("niedokończona myśl")
        waitText("niedokończona myśl")
        rule.runOnUiThread { rule.activity.nav.back() }
        until("szkic zapisany") { core.db.chat(chat)?.draft == "niedokończona myśl" }
        waitText("Szkic: niedokończona myśl")
        rule.onNodeWithText("Kasia").performClick()
        waitText("niedokończona myśl")
        rule.onNode(hasSetTextAction() and hasText("niedokończona myśl")).performTextClearance()
        rule.runOnUiThread { rule.activity.nav.back() }
        waitGone("Szkic:")

        // ---- multimedia i linki ----
        go(Screen.Media(chat))
        waitText("Linki (1)")
        rule.onNodeWithText("Linki (1)").performClick()
        waitText("https://example.com/film")

        // ---- przekazanie do grupy ----
        val g = kotlinx.coroutines.runBlocking { core.createGroup("Ekipa", listOf(peer)) }
        go(Screen.Forward("k1"))
        waitText("Przekaż do")
        rule.onNodeWithText("Ekipa").performClick()
        rule.onNodeWithText("Przekaż (1)", useUnmergedTree = true).performClick()
        until("przekazano do grupy") { core.db.messages(g, 50).any { it.ex.fwd && it.body.startsWith("Cześć!") } }

        // ---- lista rozmów: przypnij, archiwizuj ----
        rule.runOnUiThread { rule.activity.nav.home() }
        waitText("Kasia")
        rule.onNodeWithText("Kasia").performTouchInput { longClick() }
        waitText("Przypnij na górze")
        rule.onNodeWithText("📌 Przypnij na górze").performClick()
        until("czat przypięty") { (core.db.chat(chat)?.pinned ?: 0) > 0 }
        rule.onNodeWithText("Ekipa").performTouchInput { longClick() }
        waitText("Archiwizuj")
        rule.onNodeWithText("Archiwizuj").performClick()
        waitText("Zarchiwizowane (1)")
        rule.onNodeWithText("Zarchiwizowane (1)").performClick()
        waitText("Ekipa")
        rule.onNodeWithText("Ekipa").performTouchInput { longClick() }
        rule.onNodeWithText("Przywróć z archiwum").performClick()
        until("przywrócono") { core.db.chat(g)?.archived == false }

        // ---- ukryte czaty ----
        core.setHiddenPin("2468")
        rule.runOnUiThread { rule.activity.nav.home() }
        waitText("Kasia")
        rule.onNodeWithText("Kasia").performTouchInput { longClick() }
        rule.onNodeWithText("🔒 Ukryj czat").performClick()
        waitGone("Kasia")
        rule.onNodeWithContentDescription("Szukaj").performClick()
        rule.onNode(hasSetTextAction()).performTextInput("2468")
        waitText("Widzisz ukryte czaty")
        waitText("Kasia")

        // ---- wyszukiwarka globalna z przejściem do wiadomości ----
        rule.onNodeWithContentDescription("Szukaj").performClick()
        rule.onNode(hasSetTextAction()).performTextInput("kina")
        waitText("Wiadomości (2)")
        rule.onAllNodesWithText("Idziemy jutro", substring = true)[0].performClick()
        until("otwarto rozmowę z wyszukiwarki") { (rule.activity.nav.current as? Screen.Chat)?.focus != null }
        waitText("Idziemy jutro do kina")

        // ---- relacja tekstowa ----
        rule.runOnUiThread { rule.activity.nav.home() }
        rule.onNodeWithText("Twoja relacja").performClick()
        waitText("Nowa relacja")
        rule.onNodeWithText("✏️ Tekst").performClick()
        rule.onNode(hasSetTextAction()).performTextInput("Piękny dzień!")
        rule.onNodeWithText("Opublikuj").performClick()
        until("relacja zapisana") { core.db.stories().any { it.body == "Piękny dzień!" } }
        waitGone("Nowa relacja")
        rule.onNodeWithText("Twoja relacja").performClick()
        waitText("Piękny dzień!")
        waitText("wyświetle")
        rule.onNodeWithContentDescription("Zamknij").performClick()

        // ---- wiadomość do wielu ----
        go(Screen.Broadcast)
        waitText("Każda wybrana osoba")
        rule.onNode(hasText("Wiadomość") and hasSetTextAction()).performTextInput("Do wszystkich osobno")
        rule.onNodeWithText("Wszyscy").performClick()
        rule.onNodeWithText("Wyślij (1)", useUnmergedTree = true).performClick()
        until("wysłano do wielu") { core.db.messages(chat, 50).any { it.body == "Do wszystkich osobno" } }

        // ---- grupa: opis, zatwierdzanie, link z limitem ----
        go(Screen.GroupInfo(g.removePrefix("g:")))
        waitText("Dodaj opis grupy")
        rule.onNodeWithText("Dodaj opis grupy").performClick()
        rule.onNode(hasSetTextAction()).performTextInput("Kino co piątek")
        rule.onNodeWithText("Zapisz").performClick()
        until("opis zapisany") { core.db.group(g.removePrefix("g:"))?.descr == "Kino co piątek" }
        waitText("Kino co piątek")
        rule.onNode(androidx.compose.ui.test.isToggleable() and androidx.compose.ui.test.hasAnySibling(hasText("Zatwierdzaj nowe osoby")), useUnmergedTree = true).performClick()
        until("zatwierdzanie włączone") { core.db.group(g.removePrefix("g:"))?.approve == true }
        rule.onNodeWithText("Link zaproszenia").performClick()
        rule.onNodeWithText("Link z limitem czasu lub osób…").performClick()
        rule.onNodeWithText("Utwórz link").performClick()
        until("link z limitem") { core.db.group(g.removePrefix("g:"))?.invites?.size == 1 }
        waitText("użyto 0/1")
        rule.onNodeWithText("Zamknij").performClick()

        // ---- wygląd ----
        go(Screen.Appearance)
        waitText("Motyw")
        rule.onNodeWithText("Czarny (AMOLED, oszczędza baterię)").performClick()
        rule.onNodeWithText("Zieleń").performClick()
        rule.onNodeWithText("Duży").performClick()
        until("wygląd zapisany") { UiPrefs.theme == 3 && UiPrefs.accent == 1 && UiPrefs.fontScale == 1.15f }
        rule.onNode(hasScrollAction()).performScrollToNode(hasText("Podgląd"))
        waitText("Cześć! Jak leci?")

        // ---- własny przekaźnik ----
        go(Screen.OwnRelay)
        waitText("Krok po kroku")
        rule.onNode(hasScrollAction()).performScrollToNode(hasText("Udostępnij swoje przekaźniki"))
        waitText("Udostępnij swoje przekaźniki")

        // ---- prywatność: treść powiadomień ----
        go(Screen.Security)
        rule.onNode(hasScrollAction()).performScrollToNode(hasText("Tylko nadawca", substring = true))
        rule.onNodeWithText("Tylko nadawca", substring = true).performClick()
        until("prywatność powiadomień") { core.notifPrivacy == pl.szept.core.Core.NOTIF_SENDER }
        UiPrefs.theme = 0; UiPrefs.accent = 0; UiPrefs.fontScale = 1f
        println("UI 1.6 OK")
    }
    @Test
    fun v17Screens() {
        waitText("Zaczynamy")
        rule.onNode(hasText("Twoje imię", substring = true)).performTextInput("Tester")
        rule.onNodeWithText("Zaczynamy").performClick()
        waitText("Nie masz jeszcze rozmów")
        val core = App.core
        val peer = Crypto.publicKey(Crypto.newPrivateKey()).hex()
        core.addContact(peer, "Ola")
        val chat = dmChatId(peer)
        val now = System.currentTimeMillis()
        val video = java.io.File(core.mediaDir, "notatka.mp4").apply { parentFile?.mkdirs(); writeBytes(ByteArray(2048)) }
        val meta = pl.szept.data.MediaMeta("v1", "00", 2048, 1, "video/mp4", "00", "notatka.mp4", null, 384, 384, 12_000)
        core.db.insertMessage(Message("r1", chat, peer, now, Kind.VIDEO, "", meta, video.absolutePath, St.RECEIVED, 0, 0, 0, 0, 100, false,
            ex = pl.szept.data.Extra(round = true)))
        waitText("Ola")
        rule.onNodeWithText("Ola").performClick()
        waitDesc("Odtwórz notatkę")
        waitText("0:12")

        // ---- kopia zapasowa ----
        go(Screen.Backup)
        waitText("Utwórz kopię i zapisz")
        waitText("Rozmiar kopii")
        val fields = rule.onAllNodes(hasSetTextAction())
        fields[0].performTextInput("haslo-kopii")
        fields[1].performTextInput("haslo-inne")
        waitText("Hasła się różnią")
        fields[1].performTextClearance()
        fields[1].performTextInput("haslo-kopii")
        waitGone("Hasła się różnią")

        // ---- prywatność: stan szyfrowania bazy ----
        go(Screen.Security)
        rule.onNode(hasScrollAction()).performScrollToNode(hasText("Baza wiadomości", substring = true))
        waitText(if (core.db.encrypted) "Baza wiadomości zaszyfrowana" else "bez dodatkowego szyfrowania")
        println("UI 1.7 OK")
    }
    @Test
    fun v18Screens() {
        waitText("Zaczynamy")
        rule.onNode(hasText("Twoje imię", substring = true)).performTextInput("Tester")
        rule.onNodeWithText("Zaczynamy").performClick()
        waitText("Nie masz jeszcze rozmów")
        val core = App.core

        // ---- bez internetu ----
        go(Screen.Lan)
        waitText("Sieć lokalna")
        rule.onNode(androidx.compose.ui.test.isToggleable() and androidx.compose.ui.test.hasAnySibling(hasText("Sieć lokalna")), useUnmergedTree = true).performClick()
        until("przekaźnik w telefonie działa", 15_000) { core.lan.running.value }
        waitText("koperty w pamięci")
        rule.onNode(hasScrollAction()).performScrollToNode(hasText("Zabieraj wiadomości dla znajomych"))
        waitText("Utwórz sieć Wi-Fi")
        core.setLanEnabled(false)
        until("wyłączony") { !core.lan.running.value }

        // ---- moje urządzenia ----
        go(Screen.Devices)
        waitText("(to urządzenie)")
        waitText("Jak dodać urządzenie")

        // ---- rotacja kluczy ----
        go(Screen.Security)
        rule.onNode(hasScrollAction()).performScrollToNode(hasText("Nowy klucz co tydzień", substring = true))
        rule.onNode(androidx.compose.ui.test.isToggleable() and androidx.compose.ui.test.hasAnySibling(hasText("Nowy klucz co tydzień", substring = true)), useUnmergedTree = true).performClick()
        until("rotacja włączona", 15_000) { core.forwardSecrecy && core.prekeyCreated() > 0 }
        println("UI 1.8 OK")
    }
    @Test
    fun v19Calls() {
        waitText("Zaczynamy")
        rule.onNode(hasText("Twoje imię", substring = true)).performTextInput("Tester")
        rule.onNodeWithText("Zaczynamy").performClick()
        waitText("Nie masz jeszcze rozmów")
        val core = App.core
        core.setRelays(emptyList())
        org.robolectric.Shadows.shadowOf(rule.activity.application).grantPermissions(android.Manifest.permission.RECORD_AUDIO, android.Manifest.permission.CAMERA)
        val engineLog = java.util.Collections.synchronizedList(mutableListOf<String>())
        core.calls.factory = pl.szept.call.RtcFactory { _, _, l -> CallTest.FakeEngine(l, engineLog) }
        core.groupCalls.factory = pl.szept.call.RtcSessionFactory { _, _ -> GroupCallTest.FakeSession(engineLog) }
        val peer = Crypto.publicKey(Crypto.newPrivateKey()).hex()
        core.addContact(peer, "Zosia")
        waitText("Zosia")

        // przychodzące połączenie
        core.calls.onSignal(peer, org.json.JSONObject().put("t", "call").put("op", "offer").put("cid", "c1").put("sdp", "v=0 x")
            .put("vid", false).put("ts", pl.szept.nostr.Clock.now()).put("dv", "d1"))
        waitText("Połączenie przychodzące")
        rule.onNodeWithContentDescription("Odbierz").performClick()
        until("połączono") { core.calls.state.value?.phase == pl.szept.call.CallManager.Phase.CONNECTED }
        waitText("0:0")
        rule.onNodeWithContentDescription("Wycisz").performClick()
        until("wyciszono") { core.calls.state.value?.muted == true }
        rule.onNodeWithContentDescription("Rozłącz").performClick()
        waitText("Zakończono")
        until("ekran rozmowy zamknięty", 8_000) { core.calls.state.value == null }
        waitText("Zosia")

        // dzwonienie z rozmowy
        rule.onNodeWithText("Zosia").performClick()
        waitDesc("Zadzwoń")
        rule.onNodeWithContentDescription("Zadzwoń").performClick()
        waitText("Dzwonię…")
        until("dzwonię", 8_000) { core.groupCalls.state.value?.invited == setOf(peer) }
        // dodawanie osób w trakcie rozmowy
        rule.onNodeWithContentDescription("Dodaj osobę").performClick()
        waitText("Dodaj do rozmowy")
        rule.onNodeWithText("Anuluj").performClick()
        rule.onNodeWithContentDescription("Rozłącz").performClick()
        until("rozłączono bez okna", 8_000) { core.groupCalls.state.value == null }
        waitGone("Dzwonię…")
        println("UI 1.9 OK")
    }
    @Test
    fun v20GroupCall() {
        waitText("Zaczynamy")
        rule.onNode(hasText("Twoje imię", substring = true)).performTextInput("Tester")
        rule.onNodeWithText("Zaczynamy").performClick()
        waitText("Nie masz jeszcze rozmów")
        val core = App.core
        core.setRelays(emptyList())
        org.robolectric.Shadows.shadowOf(rule.activity.application).grantPermissions(android.Manifest.permission.RECORD_AUDIO, android.Manifest.permission.CAMERA)
        core.groupCalls.factory = pl.szept.call.RtcSessionFactory { _, _ -> GroupCallTest.FakeSession(java.util.Collections.synchronizedList(mutableListOf())) }
        val peer = Crypto.publicKey(Crypto.newPrivateKey()).hex()
        core.addContact(peer, "Ola")
        val chat = kotlinx.coroutines.runBlocking { core.createGroup("Chór", listOf(peer)) }
        val gid = chat.removePrefix("g:")
        waitText("Chór")
        fun sig(op: String, cid: String, extra: org.json.JSONObject.() -> Unit = {}) = core.groupCalls.onSignal(peer,
            org.json.JSONObject().put("t", "gcall").put("op", op).put("cid", cid).put("g", gid).put("ts", pl.szept.nostr.Clock.now()).put("dv", "d1").apply(extra))

        // zaproszenie do rozmowy grupowej → dołącz → Ola się łączy → wyjdź
        sig("ring", "g1") { put("vid", false) }
        waitText("Ola zaprasza do rozmowy")
        rule.onNodeWithContentDescription("Odbierz").performClick()
        waitText("Czekam, aż ktoś dołączy")
        sig("offer", "g1") { put("sdp", "v=0").put("to_dv", core.deviceId) }
        waitText("2 osoby")
        waitText("Ola")
        rule.onNodeWithContentDescription("Rozłącz").performClick()
        until("ekran zamknięty", 8_000) { core.groupCalls.state.value == null }

        // trwająca rozmowa w grupie → pasek „dołącz” w czacie
        sig("join", "g2") { put("vid", false) }
        waitText("Chór")
        rule.onNode(hasText("Chór") and androidx.compose.ui.test.hasClickAction()).performClick()
        waitText("Trwa rozmowa grupowa")
        rule.onNodeWithText("Trwa rozmowa grupowa", substring = true).performClick()
        until("dołączono", 8_000) { core.groupCalls.state.value?.id == "g2" && core.groupCalls.state.value?.phase == pl.szept.call.GroupCallManager.Phase.ACTIVE }
        rule.onNodeWithContentDescription("Rozłącz").performClick()
        until("koniec", 8_000) { core.groupCalls.state.value == null }
        println("UI 2.0 OK")
    }
    @Test
    fun v22() {
        waitText("Zaczynamy")
        rule.onNode(hasText("Twoje imię", substring = true)).performTextInput("Tester")
        rule.onNodeWithText("Zaczynamy").performClick()
        waitText("Nie masz jeszcze rozmów")
        val core = App.core
        core.setRelays(emptyList())

        // ---- ustawienia: proste kategorie ----
        go(Screen.Settings)
        waitText("Prywatność i bezpieczeństwo")
        waitText("Kopia zapasowa")
        rule.onNode(hasScrollAction()).performScrollToNode(hasText("Co nowego"))
        rule.onNodeWithText("Co nowego").performClick()
        waitText("Wersja " + pl.szept.BuildConfig.VERSION_NAME)

        // ---- PIN: ustaw, kod awaryjny, usuń koszem (z PIN-em) ----
        go(Screen.Security)
        rule.onNodeWithText("Ustaw PIN").performClick()
        val f = rule.onAllNodes(hasSetTextAction())
        f[0].performTextInput("1234"); f[1].performTextInput("1234")
        rule.onNodeWithText("Zapisz").performClick()
        waitText("PIN jest włączony")
        rule.onNodeWithText("Ustaw kod awaryjny").performClick()
        val w = rule.onAllNodes(hasSetTextAction())
        w[0].performTextInput("9999"); w[1].performTextInput("9999")
        rule.onNodeWithText("Zapisz").performClick()
        waitText("Kod awaryjny jest ustawiony")
        assertTrue(core.hasWipeCode)
        rule.onNodeWithContentDescription("Usuń kod awaryjny").performClick()
        rule.onNode(hasSetTextAction()).performTextInput("1234")
        rule.onNodeWithText("Usuń").performClick()
        waitText("Ustaw kod awaryjny")
        assertTrue(!core.hasWipeCode && core.lockEnabled)
        rule.onNodeWithContentDescription("Usuń PIN").performClick()
        rule.onNode(hasSetTextAction()).performTextInput("0000")
        rule.onNodeWithText("Usuń").performClick()
        waitText("Błędny PIN")
        rule.onNode(hasSetTextAction()).performTextClearance()
        rule.onNode(hasSetTextAction()).performTextInput("1234")
        rule.onNodeWithText("Usuń").performClick()
        waitText("Ustaw PIN")
        assertTrue(!core.lockEnabled)

        // ---- konto oficjalne: tylko do odczytu, ze znaczkiem ----
        val official = pl.szept.data.dmChatId(pl.szept.core.Admin.PUB)
        core.db.upsertContact(pl.szept.core.Admin.PUB, "Szept", false)
        core.db.ensureChat(official, false, pl.szept.core.Admin.PUB, "Szept")
        core.db.insertMessage(Message("o1", official, pl.szept.core.Admin.PUB, System.currentTimeMillis(), Kind.TEXT, "Witaj w Szepcie!", null, null, St.RECEIVED, 0, 0, 0, 0, 0, false))
        // moja wiadomość usunięta u rozmówcy
        val peer = Crypto.publicKey(Crypto.newPrivateKey()).hex()
        core.addContact(peer, "Iga")
        core.db.insertMessage(Message("d1", dmChatId(peer), core.myPub, System.currentTimeMillis(), Kind.TEXT, "ups", null, null, St.SENT, 0, 0, 0, 0, 0, true))
        core.db.setDeletedForOthers("d1")
        go(Screen.Chat(official))
        waitText("Witaj w Szepcie!")
        waitText("To oficjalne konto Szept")
        waitDesc("Zweryfikowane")
        go(Screen.Chat(dmChatId(peer)))
        waitText("ups")
        rule.onNode(hasContentDescription("Usunięte u rozmówcy"), useUnmergedTree = true).assertExists()
        waitDesc("Zadzwoń")

        // ---- panel administratora: tylko po haśle ----
        go(Screen.Admin)
        waitText("Dostęp tylko dla administratora")
        println("UI 2.2 OK")
    }
    @Test
    fun v23() {
        waitText("Zaczynamy")
        rule.onNode(hasText("Twoje imię", substring = true)).performTextInput("Tester")
        rule.onNodeWithText("Zaczynamy").performClick()
        waitText("Nie masz jeszcze rozmów")
        val core = App.core
        core.setRelays(emptyList())
        val peer = Crypto.publicKey(Crypto.newPrivateKey()).hex()
        core.addContact(peer, "Ola")
        val chat = dmChatId(peer)
        core.db.insertMessage(Message("s1", chat, peer, System.currentTimeMillis(), Kind.TEXT, "Przesuń mnie w prawo", null, null, St.RECEIVED, 0, 0, 0, 0, 0, false))

        // ---- status znajomego: kropka na liście, opis w rozmowie ----
        core.presence.value = mapOf(peer to pl.szept.core.Core.Presence("a", System.currentTimeMillis()))
        waitText("Ola")
        waitDesc("Status: aktywny")
        core.presence.value = mapOf(peer to pl.szept.core.Core.Presence("b", System.currentTimeMillis()))
        waitDesc("Status: zaraz wraca")
        go(Screen.Chat(chat))
        waitText("zaraz wraca")
        core.presence.value = mapOf(peer to pl.szept.core.Core.Presence("i", System.currentTimeMillis() - 20 * 60_000))
        waitText("nieaktywny(-a) · 20 min temu")

        // ---- odpowiedź gestem: przesunięcie w prawo ----
        waitText("Przesuń mnie w prawo")
        rule.onNodeWithText("Przesuń mnie w prawo").performTouchInput { swipeRight(startX = 10f, endX = 600f) }
        waitText("Odpowiadasz: Ola")
        // przytrzymanie = reakcje
        rule.onAllNodesWithText("Przesuń mnie w prawo")[0].performTouchInput { longClick() }
        waitText("👍")
        rule.onNodeWithText("❤️").performClick()
        until("reakcja") { core.db.reaction("s1", core.myPub)?.emoji == "❤️" }

        // ---- mój status: zaraz wracam ----
        go(Screen.MyProfile)
        waitText("Mój status")
        rule.onNodeWithText("🕒 Zaraz wracam").performClick()
        until("zaraz wracam zapisane") { core.awayManual && core.myPresence() == pl.szept.core.Core.Seen.AWAY }
        rule.onNodeWithText("Automatycznie").performClick()
        until("automatycznie") { !core.awayManual }

        // ---- usuwanie ze znajomych ----
        go(Screen.ContactInfo(peer))
        waitText("Stały ID")
        println("KONTAKT: " + core.db.contact(peer))
        rule.onNode(hasScrollAction()).performScrollToNode(hasText("Usuń czat u mnie"))
        waitText("Usuń ze znajomych")
        rule.onNodeWithText("Usuń ze znajomych").performClick()
        waitText("Usunąć Ola ze znajomych?")
        rule.onNodeWithText("Usuń").performClick()
        until("usunięto") { core.db.contact(peer)?.isContact == false && core.db.chat(chat) == null }

        // ---- mój kod: skanowanie kodu znajomego ----
        go(Screen.MyCode)
        rule.onNode(hasScrollAction()).performScrollToNode(hasText("Skanuj kod QR"))
        waitText("Skanuj kod QR")
        println("UI 2.3 OK")
    }
    @Test
    fun v25Gifs() {
        waitText("Zaczynamy")
        rule.onNode(hasText("Twoje imię", substring = true)).performTextInput("Tester")
        rule.onNodeWithText("Zaczynamy").performClick()
        waitText("Nie masz jeszcze rozmów")
        val core = App.core
        core.setRelays(emptyList())
        pl.szept.core.Gifs.saved(rule.activity).forEach { pl.szept.core.Gifs.remove(rule.activity, it) }
        pl.szept.core.Gifs.save(rule.activity, GifTest.gifFile(rule.activity.cacheDir))
        pl.szept.core.Gifs.setKlipyKey(rule.activity, "")
        val peer = Crypto.publicKey(Crypto.newPrivateKey()).hex()
        core.addContact(peer, "Gosia")
        val chat = dmChatId(peer)
        go(Screen.Chat(chat))
        // przycisk GIF w polu wiadomości → moja kolekcja z animowanym podglądem
        waitDesc("Wybierz GIF")
        rule.onNodeWithContentDescription("Wybierz GIF").performClick()
        waitText("Moje (1)")
        waitText("Dodaj z telefonu")
        rule.onAllNodes(hasContentDescription("GIF"))[0].performClick()
        waitText("Wyślij")
        waitText("✓ W Twoich GIF-ach")
        rule.onNodeWithText("Wyślij").performClick()
        until("GIF wysłany") { core.db.messages(chat, 10).any { it.mine && it.media?.mime == "image/gif" } }
        // zakładka KLIPY bez klucza — prośba o klucz
        rule.onNodeWithContentDescription("Wybierz GIF").performClick()
        rule.onNodeWithText("Szukaj (KLIPY)").performClick()
        waitText("bezpłatnego klucza")
        rule.onNodeWithContentDescription("Zamknij").performClick()
        // GIF w rozmowie: dotknięcie → podgląd z zapisem
        waitDesc("GIF")
        println("UI 2.5 OK")
    }
    @Test
    fun v27GroupModeration() {
        waitText("Zaczynamy")
        rule.onNode(hasText("Twoje imię", substring = true)).performTextInput("Tester")
        rule.onNodeWithText("Zaczynamy").performClick()
        waitText("Nie masz jeszcze rozmów")
        val core = App.core
        core.setRelays(emptyList())
        val peer = Crypto.publicKey(Crypto.newPrivateKey()).hex()
        core.addContact(peer, "Hania")
        val chat = kotlinx.coroutines.runBlocking { core.createGroup("Drużyna", listOf(peer)) }
        val gid = chat.removePrefix("g:")
        go(Screen.GroupInfo(gid))
        waitText("Drużyna")
        rule.onNode(hasScrollAction()).performScrollToNode(hasText("Hania"))
        rule.onNodeWithText("Hania").performClick()
        rule.onNodeWithText("🔇 Wycisz…").performClick()
        waitText("Do odwołania")
        rule.onNodeWithText("1 godzina").performClick()
        until("wyciszona") { core.db.group(gid)!!.isMuted(peer, System.currentTimeMillis()) }
        rule.onNode(hasScrollAction()).performScrollToNode(hasText("🔇 wyciszony(-a)", substring = true))
        rule.onNodeWithText("Hania").performClick()
        rule.onNodeWithText("⛔ Zbanuj (bez powrotu z linku)").performClick()
        until("zbanowana") { peer in core.db.group(gid)!!.banned && !core.db.group(gid)!!.isMember(peer) }
        rule.onNode(hasScrollAction()).performScrollToNode(hasText("Odbanuj"))
        rule.onNodeWithText("Odbanuj").performClick()
        until("odbanowana") { core.db.group(gid)!!.banned.isEmpty() }
        // panel administratora: logowanie kodem jednorazowym
        go(Screen.Admin)
        waitText("Masz kod jednorazowy")
        println("UI 2.7 OK")
    }
}
