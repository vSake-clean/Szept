package pl.szept

import android.app.Application
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * Języki (3.4): każdy tekst z polskimi znakami, który przechodzi przez tr(), musi mieć tłumaczenie angielskie,
 * a miejsca na wartości ({0}, {1}…) muszą się zgadzać. Nowy tekst bez tłumaczenia = czerwony test.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class I18nTest {
    private val ctx get() = RuntimeEnvironment.getApplication()

    @After fun back() = I18n.set(ctx, "pl")

    private fun srcDir(): File = listOf(File("src/main/java"), File("app/src/main/java")).first { it.isDirectory }

    /** Klucze tr("…") z kodu (z odkodowanymi znakami ucieczki). */
    private fun sourceKeys(): Map<String, String> {
        val rx = Regex("""\btr\("((?:[^"\\]|\\.)*)"""")
        val out = LinkedHashMap<String, String>()
        srcDir().walkTopDown().filter { it.extension == "kt" }.forEach { f ->
            rx.findAll(f.readText()).forEach { m ->
                val k = m.groupValues[1].replace(Regex("""\\(u[0-9a-fA-F]{4}|.)""")) { e ->
                    val c = e.groupValues[1]
                    when {
                        c == "n" -> "\n"; c == "t" -> "\t"; c.startsWith("u") -> c.drop(1).toInt(16).toChar().toString(); else -> c
                    }
                }
                out.putIfAbsent(k, f.name)
            }
        }
        return out
    }

    private fun english(): JSONObject = JSONObject(ctx.assets.open("i18n/en.json").bufferedReader().use { it.readText() })

    @Test
    fun everyPolishTextHasEnglish() {
        val en = english()
        val polish = Regex("[ąćęłńóśźżĄĆĘŁŃÓŚŹŻ]|\\b(nie|się|jest|aby|albo|oraz|już|Twój|Twoje|Dotknij|Wyślij|Usuń|Zapisz|Pokaż)\\b")
        val keys = sourceKeys()
        assertTrue("za mało tekstów — zła ścieżka?", keys.size > 2000)
        val missing = keys.filter { (k, _) -> polish.containsMatchIn(k) && !en.has(k) }
        assertTrue("Brak tłumaczeń (${missing.size}):\n" + missing.entries.take(40).joinToString("\n") { "${it.value}: ${it.key}" }, missing.isEmpty())
        println("✔ ${keys.size} tekstów w kodzie, ${en.length()} tłumaczeń")
    }

    @Test
    fun placeholdersAndEdgesMatch() {
        val en = english()
        val ph = Regex("""\{\d}""")
        for (k in en.keys()) {
            val v = en.getString(k)
            assertEquals("miejsca na wartości: $k", ph.findAll(k).map { it.value }.sorted().toList(), ph.findAll(v).map { it.value }.sorted().toList())
            // fragmenty sklejane z innymi tekstami: spacje i nowe linie na brzegach zostają
            assertEquals("początek: $k", k.takeWhile { it.isWhitespace() }, v.takeWhile { it.isWhitespace() })
            assertEquals("koniec: $k", k.takeLastWhile { it.isWhitespace() }, v.takeLastWhile { it.isWhitespace() })
        }
    }

    @Test
    fun switchingLanguage() {
        I18n.set(ctx, "pl")
        assertEquals("Ustawienia", tr("Ustawienia"))
        assertEquals("Wysyłanie 5/7…", tr("Wysyłanie {0}/{1}…", 5, 7))
        I18n.set(ctx, "en")
        assertEquals("en", I18n.lang)
        assertEquals("Settings", tr("Ustawienia"))
        assertEquals("Sending 5/7…", tr("Wysyłanie {0}/{1}…", 5, 7))
        // brak tłumaczenia = tekst polski (nic się nie psuje)
        assertEquals("Zupełnie nowy tekst", tr("Zupełnie nowy tekst"))
        assertEquals("en", I18n.chosen(ctx))
        I18n.set(ctx, "pl")
        assertEquals("Ustawienia", tr("Ustawienia"))
    }
}
