package pl.szept

import android.content.Context
import android.content.res.Resources
import android.os.Build
import androidx.compose.runtime.mutableStateOf
import org.json.JSONObject
import java.util.Locale

/**
 * Języki aplikacji. Teksty w kodzie są po polsku i przechodzą przez [tr]; dla innego języka
 * tłumaczenie bierzemy ze słownika `assets/i18n/<język>.json` (klucz = tekst polski, `{0}` = wstawiana wartość).
 * Brak tłumaczenia = tekst polski, więc nic się nie psuje, gdy jakiegoś wpisu zabraknie.
 */
object I18n {
    data class Lang(val code: String, val name: String)

    /** Dostępne języki (nazwa zawsze w swoim języku). */
    val LANGS = listOf(Lang("pl", "Polski"), Lang("en", "English"))

    private val state = mutableStateOf("pl")
    @Volatile private var dict: Map<String, String> = emptyMap()

    /** Bieżący język (odczyt w interfejsie odświeża ekran po zmianie). */
    val lang: String get() = state.value

    /** Język, w którym ruszył proces — teksty liczone raz przy starcie zmieniają się dopiero po ponownym uruchomieniu. */
    @Volatile var startLang: String = "pl"
        private set

    val locale: Locale get() = if (lang == "pl") Locale("pl") else Locale.forLanguageTag(lang)

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("ui", Context.MODE_PRIVATE)

    /** Język telefonu, jeśli go mamy; inaczej angielski. */
    fun phoneLanguage(): String {
        val l = try { Resources.getSystem().configuration.locales[0].language } catch (e: Exception) { "pl" }
        return LANGS.firstOrNull { it.code == l }?.code ?: "en"
    }

    /** Czy język był już wybrany ręcznie (w kreatorze albo w ustawieniach). */
    fun chosen(ctx: Context): String? = prefs(ctx).getString("lang", null)

    fun init(ctx: Context) {
        // w testach (Robolectric ma telefon po angielsku) domyślnie polski, jak dotąd
        val test = Build.FINGERPRINT == "robolectric"
        val l = chosen(ctx) ?: if (test) "pl" else phoneLanguage()
        apply(ctx, l)
        startLang = l
    }

    /** Ustawia i zapamiętuje język. */
    fun set(ctx: Context, code: String) {
        prefs(ctx).edit().putString("lang", code).commit()   // od razu na dysk — zaraz może być restart
        apply(ctx, code)
    }

    private fun apply(ctx: Context, code: String) {
        val c = LANGS.firstOrNull { it.code == code }?.code ?: "pl"
        dict = if (c == "pl") emptyMap() else load(ctx, c)
        state.value = c
    }

    private fun load(ctx: Context, code: String): Map<String, String> = try {
        val o = JSONObject(ctx.assets.open("i18n/$code.json").bufferedReader(Charsets.UTF_8).use { it.readText() })
        HashMap<String, String>(o.length() * 2).apply { for (k in o.keys()) put(k, o.getString(k)) }
    } catch (e: Exception) { emptyMap() }

    /** Dla testów: słownik bieżącego języka. */
    internal fun dictionary(): Map<String, String> = dict

    fun tr(key: String, args: Array<out Any?>): String {
        state.value   // odczyt stanu: zmiana języka odświeża ekran
        val t = dict[key] ?: key
        if (args.isEmpty()) return t
        var out = t
        for (i in args.indices) out = out.replace("{$i}", args[i].toString())
        return out
    }
}

/** Tekst w bieżącym języku. [key] — tekst polski; `{0}`, `{1}`… — miejsca na [args]. */
fun tr(key: String, vararg args: Any?): String = I18n.tr(key, args)
