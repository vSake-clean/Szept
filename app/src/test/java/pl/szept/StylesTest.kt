package pl.szept

import android.graphics.Bitmap
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.printToString
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import pl.szept.crypto.Crypto
import pl.szept.crypto.hex
import pl.szept.data.Kind
import pl.szept.data.Message
import pl.szept.data.Reply
import pl.szept.data.St
import pl.szept.data.dmChatId
import pl.szept.ui.ACCENTS
import pl.szept.ui.FreshRows
import pl.szept.ui.MainActivity
import pl.szept.ui.Screen
import pl.szept.ui.UiPrefs
import pl.szept.ui.style.Anim
import pl.szept.ui.style.AnimFlags
import pl.szept.ui.style.Bg
import pl.szept.ui.style.Mode
import pl.szept.ui.style.Pal
import pl.szept.ui.style.STYLES
import pl.szept.ui.style.ScreenFx
import pl.szept.ui.style.Trans
import pl.szept.ui.style.contrast
import pl.szept.ui.style.makeBg
import java.io.File

/**
 * Style i animacje: katalog stylów, czytelność tekstu (kontrast liczony na prawdziwie narysowanych tłach),
 * ustawienia animacji, efekty i zrzuty ekranów każdego stylu (SZEPT_SHOTS=katalog).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h760dp-xhdpi")
class StylesTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    private val shots = System.getenv("SZEPT_SHOTS")?.let { File(it).apply { mkdirs() } }

    @org.junit.Before fun clean() { ScreenFx.clear() }

    @After fun reset() {
        UiPrefs.style = "classic"; UiPrefs.anim = true; UiPrefs.animOff = AnimFlags.DEFAULT_OFF
        UiPrefs.animSpeed = 1f; UiPrefs.transition = 0; UiPrefs.theme = 0; UiPrefs.motion = 0
        ScreenFx.clear()
    }

    private fun snap(name: String) {
        val dir = shots ?: return
        rule.waitForIdle()
        rule.runOnUiThread {
            val wmg = Class.forName("android.view.WindowManagerGlobal")
            val inst = wmg.getMethod("getInstance").invoke(null)
            val f = wmg.getDeclaredField("mViews").apply { isAccessible = true }
            @Suppress("UNCHECKED_CAST") val views = (f.get(inst) as List<android.view.View>).filter { it.isShown && it.width > 0 && it.height > 0 }
            views.forEachIndexed { i, v ->
                if (v === rule.activity.window.decorView) return@forEachIndexed
                val bmp = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
                v.draw(android.graphics.Canvas(bmp))
                File(dir, "$name-$i.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 90, it) }
            }
        }
        // główne okno przez PixelCopy — z prawdziwym rozmyciem szkła (gdy testy rysują sprzętowo: -Dszept.hwrender=hardware)
        val v = rule.activity.window.decorView
        val img = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
        val latch = java.util.concurrent.CountDownLatch(1)
        rule.runOnUiThread { android.view.PixelCopy.request(rule.activity.window, img, { latch.countDown() }, android.os.Handler(android.os.Looper.getMainLooper())) }
        repeat(80) { if (latch.count > 0) { rule.runOnUiThread { org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle() }; Thread.sleep(30) } }
        File(dir, "$name.png").outputStream().use { img.compress(Bitmap.CompressFormat.PNG, 90, it) }
    }

    private fun waitText(t: String, timeout: Long = 10_000) {
        val end = System.currentTimeMillis() + timeout
        while (System.currentTimeMillis() < end) {
            rule.waitForIdle()
            if (rule.onAllNodesWithText(t, substring = true).fetchSemanticsNodes().isNotEmpty()) return
            Thread.sleep(120)
        }
        println("BRAK TEKSTU: $t\n" + rule.onAllNodes(isRoot(), useUnmergedTree = true).printToString(Int.MAX_VALUE))
        throw AssertionError("Brak tekstu: $t")
    }

    /** Wszystkie warianty palet (jasny/ciemny, każdy kolor akcentu, czarny AMOLED). */
    private fun palettes(): List<Triple<String, Pal, pl.szept.ui.style.StyleSpec>> = buildList {
        for (spec in STYLES) for (dark in listOf(false, true)) for (ai in ACCENTS.indices) for (black in listOf(false, true)) {
            if ((spec.mode == Mode.LIGHT && dark) || (spec.mode == Mode.DARK && !dark)) continue
            if (!spec.accent && ai > 0) continue
            add(Triple("${spec.id}/${if (dark) "ciemny" else "jasny"}/akcent $ai${if (black) "/czarny" else ""}", spec.palette(dark, ai, black), spec))
        }
    }

    @Test
    fun catalogAndPaletteContrast() {
        assertTrue("za mało stylów: ${STYLES.size}", STYLES.size >= 20)
        assertEquals("powtórzone id", STYLES.size, STYLES.map { it.id }.toSet().size)
        for (id in listOf("classic", "liquid", "materialyou", "oneui", "hyper", "nothing", "telegram", "whatsapp", "messenger", "discord", "win7", "terminal"))
            assertTrue("brak stylu $id", STYLES.any { it.id == id })
        // nazwy i opisy stylów nie nazywają firm, aplikacji ani systemów
        val brands = listOf(
            "iOS", "iPhone", "Apple", "Android", "Google", "Samsung", "One UI", "Xiaomi", "HyperOS", "MIUI", "Huawei", "EMUI", "OxygenOS", "OnePlus",
            "ColorOS", "Oppo", "Telegram", "WhatsApp", "Messenger", "Facebook", "Discord", "Signal", "Windows", "Microsoft", "iMessage",
        )
        for (spec in STYLES) for (b in brands) {
            assertTrue("nazwa firmy „$b” w stylu ${spec.id}", !Regex("\\b" + Regex.escape(b) + "\\b").containsMatchIn(spec.name + " " + spec.desc))
        }
        // każdy styl (poza klasycznym) ma kategorię i własny opis ruchu
        assertTrue(STYLES.all { it.motion.label.isNotEmpty() })
        assertTrue(STYLES.map { it.category }.toSet().size >= 4)
        assertTrue("za mało animacji", Anim.entries.size >= 20)
        val bad = mutableListOf<String>()
        for ((name, p, spec) in palettes()) {
            fun chk(what: String, fg: Color, bg: Color, min: Float) {
                val c = contrast(fg, bg)
                if (c < min) bad += "$name: $what %.2f < %.1f".format(c, min)
            }
            // progi WCAG: tekst 4.5:1; elementy interfejsu i duże napisy 3:1 (oryginalne kolory marek, np. niebieskie dymki, bywają na granicy)
            chk("tekst na tle", p.onBase, p.base, 7f)
            chk("tekst drugorzędny", p.muted, p.base, 4.5f)
            chk("kolor główny", p.primary, p.base, if (spec.id == "classic") 2.9f else 3f)
            chk("tekst na przycisku", p.onPrimary, p.primary, if (spec.id == "classic") 2.9f else 3f)
            // półprzezroczyste dymki i pigułki na własnym tle rozmowy sprawdza test teł (na prawdziwie narysowanym tle)
            fun onChatBg(f: pl.szept.ui.style.Fill) = spec.chatBg != null && f.grad == null && f.color.alpha < 0.9f
            if (!onChatBg(p.mine)) chk("moje dymki", p.mine.text, p.mine.solid(p.base), 3.4f)
            if (!onChatBg(p.theirs)) chk("cudze dymki", p.theirs.text, p.theirs.solid(p.base), 4.5f)
            p.card?.let { chk("karty", it.text, it.solid(p.base), 4.5f) }
            // napisy na paskach są duże (tytuły) — próg dla dużego tekstu 3:1
            chk("pasek", p.bar.text, p.bar.solid(p.base), 3f)
            if (!onChatBg(p.chip)) chk("pigułki", p.chip.text, p.chip.solid(p.base), 4.5f)
        }
        assertTrue("Za słaby kontrast:\n" + bad.joinToString("\n"), bad.isEmpty())
    }

    /** Tło narysowane naprawdę (kilka chwil animacji) — tekst wprost na tle i w półprzezroczystych dymkach ma być czytelny. */
    @Test
    fun backgroundsKeepTextReadable() {
        val d = Density(2f)
        val w = 720; val h = 1520
        val bad = mutableListOf<String>()
        val seen = HashSet<String>()
        for ((name, p, spec) in palettes()) {
            // tła nie zależą od czarnego wariantu i (prawie) od akcentu — wystarczy po jednym
            val key = spec.id + p.dark + p.base + p.bg
            if (!seen.add(key)) continue
            for (kind in listOfNotNull(spec.bg, spec.chatBg).distinct()) {
                // tło rozmowy niesie tylko dymki i pigułki; napisy wprost na tle są na tle stron (listy, ustawienia)
                val chatOnly = kind == spec.chatBg && kind != spec.bg
                val bubbles = spec.chatBg == null || kind == spec.chatBg
                val samples = mutableListOf<Color>()
                for (t in listOf(0f, 4.7f, 13.1f, 29.9f)) {
                    val img = ImageBitmap(w, h)
                    val canvas = androidx.compose.ui.graphics.Canvas(img)
                    val painter = makeBg(kind, p, Size(w.toFloat(), h.toFloat()), d)
                    CanvasDrawScope().draw(density = d, layoutDirection = LayoutDirection.Ltr, canvas = canvas, size = Size(w.toFloat(), h.toFloat())) {
                        painter.draw(this, 11.3f + t, null)
                    }
                    val pm = img.toPixelMap()
                    for (y in 8 until h step 37) for (x in 6 until w step 29) samples += pm[x, y]
                }
                fun check(what: String, fg: Color, fill: Color?, minP10: Float, minAbs: Float) {
                    val cs = samples.map { s -> contrast(fg, if (fill == null) s else fill.compositeOver(s)) }.sorted()
                    val p10 = cs[cs.size / 10]
                    val p1 = cs[cs.size / 100]
                    if (p10 < minP10 || p1 < minAbs) bad += "$name/$kind: $what p10=%.2f p1=%.2f".format(p10, p1)
                }
                if (kind != Bg.DESKTOP && !chatOnly) {
                    check("tekst na tle", p.onBase, null, 4.5f, 3f)
                    check("tekst drugorzędny na tle", p.muted, null, 3f, 2.2f)
                }
                fun fill(f: pl.szept.ui.style.Fill) = f.grad?.let { pl.szept.ui.style.avg(it) } ?: f.color
                if (bubbles) {
                    check("moje dymki", p.mine.text, fill(p.mine), 3.4f, 3.2f)
                    check("cudze dymki", p.theirs.text, fill(p.theirs), 4.5f, 3.5f)
                    check("pigułki", p.chip.text, fill(p.chip), 4.5f, 3.5f)
                }
                if (!chatOnly) p.card?.let { check("karty", it.text, fill(it), 4.5f, 3.5f) }
            }
        }
        assertTrue("Nieczytelny tekst na tle:\n" + bad.joinToString("\n"), bad.isEmpty())
    }

    @Test
    fun animationSettings() {
        val all = AnimFlags.build(true, emptySet(), 1f, true, false, 1f, Trans.AUTO)
        assertTrue(Anim.entries.all { all[it] })
        // główny przełącznik i systemowe „Usuń animacje”
        assertTrue(Anim.entries.none { AnimFlags.build(false, emptySet(), 1f, true, false, 1f, 0)[it] })
        // telefon wyłączył animacje: domyślnie decyduje przełącznik w Szepcie, a po włączeniu „jak w telefonie” — brak ruchu
        assertTrue(Anim.entries.all { AnimFlags.build(true, emptySet(), 1f, true, false, 0f, 0)[it] })
        assertTrue(Anim.entries.none { AnimFlags.build(true, emptySet(), 1f, true, false, 0f, 0, followSystem = true)[it] })
        // oszczędzanie baterii: bez ciągłych, ale przejścia zostają
        val saver = AnimFlags.build(true, emptySet(), 1f, true, true, 1f, 0)
        assertTrue(!saver[Anim.BG] && !saver[Anim.TILT] && saver[Anim.SCREENS] && saver[Anim.MSG_IN])
        val noSaver = AnimFlags.build(true, emptySet(), 1f, false, true, 1f, 0)
        assertTrue(noSaver[Anim.BG])
        // pojedyncze wyłączenia i tempo
        val some = AnimFlags.build(true, setOf("bg", "press"), 1.6f, true, false, 1f, Trans.FADE)
        assertTrue(!some[Anim.BG] && !some[Anim.PRESS] && some[Anim.CHAT_FX])
        assertEquals(480, some.ms(300))
        assertEquals(Trans.FADE, some.transition)
        // iskry domyślnie wyłączone
        // iskry przy dotknięciu usunięte dla wszystkich; domyślnie włączone są wszystkie animacje
        assertTrue(Anim.entries.none { it.key == "sparks" })
        assertTrue(AnimFlags.DEFAULT_OFF.isEmpty())
        // zapis i odczyt ustawień
        val ctx = rule.activity
        UiPrefs.style = "neon"; UiPrefs.animOff = setOf("bg"); UiPrefs.animSpeed = 0.65f; UiPrefs.transition = Trans.ZOOM; UiPrefs.anim = false
        UiPrefs.save(ctx)
        UiPrefs.style = "x"; UiPrefs.animOff = emptySet(); UiPrefs.anim = true
        UiPrefs.reload(ctx)
        assertEquals("neon", UiPrefs.style)
        assertEquals(setOf("bg"), UiPrefs.animOff)
        assertEquals(0.65f, UiPrefs.animSpeed)
        assertEquals(Trans.ZOOM, UiPrefs.transition)
        assertTrue(!UiPrefs.anim)
    }

    @Test
    fun chatEffectsAndFreshMessages() {
        assertEquals(ScreenFx.Kind.CONFETTI, ScreenFx.forText("Wszystkiego najlepszego! 🎉"))
        assertEquals(ScreenFx.Kind.HEARTS, ScreenFx.forText("Kocham Cię ❤️"))
        assertEquals(ScreenFx.Kind.BALLOONS, ScreenFx.forText("🎈🎈"))
        assertEquals(ScreenFx.Kind.SNOW, ScreenFx.forText("Pada śnieg ❄️"))
        assertEquals(ScreenFx.Kind.FIREWORKS, ScreenFx.forText("Szczęśliwego Nowego Roku 🎆"))
        assertEquals(ScreenFx.Kind.STARS, ScreenFx.forText("Super ⭐"))
        assertEquals(null, ScreenFx.forText("Zwykła wiadomość"))

        val now = System.currentTimeMillis()
        fun msg(id: String, body: String, ts: Long = now) = Message(id, "d:x", "x", ts, Kind.TEXT, body, null, null, St.RECEIVED, 0, 0, 0, 0, 0, false)
        val f = FreshRows()
        val first = listOf(msg("a", "Cześć 🎉", now - 1000), msg("b", "hej"))
        f.update(first, true)
        // wiadomości z otwarcia rozmowy nie „wjeżdżają” i nie uruchamiają efektów
        assertTrue(!f.take("a") && !f.take("b"))
        assertEquals(0, f.fxSeq)
        f.update(listOf(msg("c", "Gratulacje!!!")) + first, true)
        assertTrue(f.take("c"))
        assertTrue("tylko raz", !f.take("c"))
        assertEquals(1, f.fxSeq)
        assertEquals(ScreenFx.Kind.CONFETTI, f.fx)
        // stara wiadomość (np. dociągnięta po powrocie sieci) bez efektu
        f.update(listOf(msg("d", "❤️", now - 10 * 60_000), msg("c", "Gratulacje!!!")) + first, true)
        assertEquals(1, f.fxSeq)
        // efekty wyłączone
        f.update(listOf(msg("e", "🎈")) + first, false)
        assertEquals(1, f.fxSeq)
        assertTrue(f.take("e"))
    }

    @Test
    fun everyStyleRendersScreens() {
        waitText("Zaczynamy")
        rule.onNode(hasText("Twoje imię", substring = true)).performTextInput("Tester")
        rule.onNodeWithText("Zaczynamy").performClick()
        waitText("Nie masz jeszcze rozmów")
        val core = App.core
        val peer = Crypto.publicKey(Crypto.newPrivateKey()).hex()
        val peer2 = Crypto.publicKey(Crypto.newPrivateKey()).hex()
        core.addContact(peer, "Kasia")
        core.addContact(peer2, "Bartek")
        val chat = dmChatId(peer)
        val now = System.currentTimeMillis()
        listOf(
            Message("m1", chat, peer, now - 60_000, Kind.TEXT, "Cześć! Jak Ci się podoba nowy wygląd? 👋", null, null, St.RECEIVED, 0, 0, 0, 0, 0, false),
            Message("m2", chat, core.myPub, now - 50_000, Kind.TEXT, "Super! Wypróbowuję wszystkie style 😄", null, null, St.READ, 0, 0, now, 0, 0, true),
            Message("m3", chat, peer, now - 40_000, Kind.TEXT, "Mnie najbardziej podoba się Liquid Glass", null, null, St.RECEIVED, 0, 0, 0, 0, 0, false,
                reply = Reply("m2", core.myPub, "Super! Wypróbowuję wszystkie style 😄", Kind.TEXT)),
            Message("m4", chat, core.myPub, now - 30_000, Kind.TEXT, "A ja neon i retro!", null, null, St.DELIVERED, 0, 0, now, 0, 0, true),
            Message("s1", chat, core.myPub, now - 20_000, Kind.SYSTEM, "Zmieniono styl rozmowy", null, null, St.RECEIVED, 0, 0, 1, 0, 0, false),
        ).forEach { core.db.insertMessage(it) }
        core.db.touchChat(chat, now - 30_000, "A ja neon i retro!", true)
        core.db.setReaction("m2", peer, "❤️", now)
        waitText("Kasia")

        for (spec in STYLES) {
            rule.runOnUiThread { UiPrefs.style = spec.id }
            waitText("Kasia")
            snap("50-lista-${spec.id}")
        }
        rule.runOnUiThread { UiPrefs.style = "classic" }
        rule.onNodeWithText("Kasia").performClick()
        waitText("neon i retro")
        for (spec in STYLES) {
            rule.runOnUiThread { UiPrefs.style = spec.id }
            waitText("Liquid Glass")
            snap("51-rozmowa-${spec.id}")
        }
        // ciemne warianty kilku stylów
        rule.runOnUiThread { UiPrefs.theme = 2 }
        for (id in listOf("liquid", "liquid_sky", "materialyou", "oneui", "hyper", "nothing", "telegram", "whatsapp", "messenger", "signal", "win11")) {
            rule.runOnUiThread { UiPrefs.style = id }
            waitText("Liquid Glass")
            snap("52-rozmowa-ciemny-$id")
        }
        // ciemny Liquid Glass na liście rozmów — nazwy osób muszą być widoczne (czytelny domyślny kolor tekstu)
        rule.runOnUiThread { UiPrefs.style = "liquid"; rule.activity.nav.home() }
        waitText("Kasia")
        snap("52-lista-ciemny-liquid")
        rule.runOnUiThread { UiPrefs.theme = 0; UiPrefs.style = "liquid" }
        // ekrany ustawień stylu i animacji
        rule.runOnUiThread { rule.activity.nav.home(); rule.activity.nav.go(Screen.Styles) }
        waitText("Liquid Glass")
        snap("53-style")
        rule.runOnUiThread { rule.activity.nav.go(Screen.Animations) }
        waitText("Charakter ruchu")
        snap("54-animacje")
        rule.runOnUiThread { rule.activity.nav.back(); rule.activity.nav.back(); rule.activity.nav.go(Screen.Appearance) }
        waitText("Zmień styl")
        snap("55-wyglad")
        // efekt na ekranie (konfetti) — pojawia się i sam znika
        rule.runOnUiThread { rule.activity.nav.home() }
        rule.runOnUiThread { ScreenFx.play(ScreenFx.Kind.CONFETTI) }
        assertTrue(ScreenFx.bursts.isNotEmpty())
        val end = System.currentTimeMillis() + 15_000
        while (ScreenFx.bursts.isNotEmpty() && System.currentTimeMillis() < end) { rule.mainClock.advanceTimeBy(500); rule.waitForIdle(); Thread.sleep(20) }
        assertTrue("efekt nie zniknął", ScreenFx.bursts.isEmpty())
    }

    /** Kreator wyglądu: po założeniu konta pokazuje się sam, każdy krok zmienia wygląd od razu. */
    @Test
    fun appearanceWizard() {
        pl.szept.ui.style.WizardGate.enabledInTests = true
        try {
            UiPrefs.wizard = 0
            waitText("Zaczynamy")
            rule.onNode(hasText("Twoje imię", substring = true)).performTextInput("Tester")
            rule.onNodeWithText("Zaczynamy").performClick()
            waitText("Nowy wygląd Szepta")
            snap("56-kreator-1")
            rule.onNodeWithText("Zaczynamy").performClick()
            waitText("Jasny czy ciemny?")
            rule.onNodeWithText("Ciemny").performClick()
            rule.waitForIdle()
            assertEquals(2, UiPrefs.theme)
            snap("56-kreator-2")
            rule.onNodeWithText("Dalej").performClick()
            waitText("Wybierz styl")
            rule.onNode(androidx.compose.ui.test.hasScrollAction()).performScrollToNode(hasText("Doodle Gradient"))
            rule.onNodeWithText("Doodle Gradient").performClick()
            rule.waitForIdle()
            assertEquals("telegram", UiPrefs.style)
            snap("56-kreator-3")
            rule.onNodeWithText("Dalej").performClick()
            waitText("Pojedyncze animacje")
            rule.onNodeWithText("Delikatne").performScrollTo().performClick()
            rule.waitForIdle()
            assertTrue(UiPrefs.anim && "bg" in UiPrefs.animOff && "typing" !in UiPrefs.animOff)
            rule.onNodeWithText("Wyłączone").performScrollTo().performClick()
            rule.waitForIdle()
            assertTrue(!UiPrefs.anim)
            rule.onNodeWithText("Pełne").performScrollTo().performClick()
            rule.waitForIdle()
            assertTrue(UiPrefs.anim && UiPrefs.animOff == AnimFlags.DEFAULT_OFF)
            rule.onNodeWithText("Z odbiciem").performScrollTo().performClick()
            rule.waitForIdle()
            assertEquals(3, UiPrefs.motion)
            snap("56-kreator-4")
            rule.onNodeWithText("Dalej").performClick()
            waitText("Kolor i tekst")
            rule.onNodeWithText("Duży").performScrollTo().performClick()
            rule.waitForIdle()
            assertEquals(1.15f, UiPrefs.fontScale)
            snap("56-kreator-5")
            rule.onNodeWithText("Dalej").performClick()
            waitText("Gotowe!")
            snap("56-kreator-6")
            rule.onNodeWithText("Gotowe").performClick()
            waitText("Nie masz jeszcze rozmów")
            assertEquals(pl.szept.ui.style.WIZARD_VERSION, UiPrefs.wizard)
            // zapisane na stałe
            UiPrefs.reload(rule.activity)
            assertEquals("telegram", UiPrefs.style)
            assertEquals(pl.szept.ui.style.WIZARD_VERSION, UiPrefs.wizard)
        } finally {
            pl.szept.ui.style.WizardGate.enabledInTests = false
            UiPrefs.fontScale = 1f; UiPrefs.motion = 0
        }
    }

    /** Każdy efekt coś rysuje (zrzut klatki w połowie animacji). */
    @Test
    fun effectsDraw() {
        val d = Density(2f)
        val w = 720; val h = 1520
        for (k in ScreenFx.Kind.entries) {
            val b = ScreenFx.Burst(k, 42L)
            val img = ImageBitmap(w, h)
            CanvasDrawScope().draw(density = d, layoutDirection = LayoutDirection.Ltr, canvas = androidx.compose.ui.graphics.Canvas(img), size = Size(w.toFloat(), h.toFloat())) {
                drawRect(Color(0xFF20232A))
                pl.szept.ui.style.drawBurstForTest(this, b, if (k == ScreenFx.Kind.FIREWORKS) 1.3f else 1.2f)
            }
            val pm = img.toPixelMap()
            var lit = 0
            for (y in 0 until h step 6) for (x in 0 until w step 6) if (pm[x, y] != Color(0xFF20232A)) lit++
            assertTrue("efekt ${k.label} nic nie narysował", lit > 20)
            shots?.let { dir ->
                val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                for (y in 0 until h) for (x in 0 until w) bmp.setPixel(x, y, android.graphics.Color.argb((pm[x, y].alpha * 255).toInt(), (pm[x, y].red * 255).toInt(), (pm[x, y].green * 255).toInt(), (pm[x, y].blue * 255).toInt()))
                File(dir, "57-efekt-${k.name.lowercase()}.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 90, it) }
            }
        }
    }
}
