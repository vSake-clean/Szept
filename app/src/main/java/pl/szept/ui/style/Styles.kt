package pl.szept.ui.style

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import pl.szept.ui.ACCENTS
import pl.szept.ui.Brand
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/*
 * Style aplikacji: kolory, kształty, czcionki, wygląd dymków i tło (często ruchome).
 * Każdy styl ma wersję jasną i ciemną (albo stały wygląd, np. neon zawsze ciemny).
 * Kontrast tekstu jest sprawdzany w testach — tekst zawsze ma być czytelny.
 */

/** Jak styl traktuje ustawienie „Motyw” (jasny/ciemny). */
enum class Mode { FOLLOW, LIGHT, DARK }

/** Wygląd powierzchni (dymki, karty, paski). */
enum class Surf { FLAT, GLASS, FROST, SOFT, BRUTAL, CLAY, NEON, GLOSSY, NOTE, CHALK, BEVEL }

/** Tło stylu. */
enum class Bg { PLAIN, SKY, IOS6, TELEGRAM, WHATSAPP, MICA, HARMONY, BLISS, DESKTOP, RETRO, TERMINAL }

/** „Rama” interfejsu: paski, pole wiadomości, nagłówek listy — tak jak w danym systemie/aplikacji. */
enum class Chrome { MATERIAL, GLASS, IOS6, ONEUI, EXPRESSIVE, WIN11, WIN7, WINXP, WIN95, TELEGRAM, WHATSAPP, MESSENGER, TERMINAL, HYPER, NOTHING, OXYGEN, COLOROS, EMUI, FACEBOOK, DISCORD, SIGNAL }

/** Ogonek dymka (tylko na ostatnim dymku w serii). */
enum class Tail { NONE, IMESSAGE, WHATSAPP, TELEGRAM }

/** Kształt awatarów. */
enum class AvatarShape { CIRCLE, SQUIRCLE, SQUARE, HEX, COOKIE }

/** Iskry przy dotknięciu — kształt cząstek. */
enum class Spark { DOTS, BUBBLES, NEON, PIXELS, HEARTS, LEAVES, STARS, GOLD }

/** Rodzaje przejść między ekranami. */
object Trans {
    const val AUTO = 0
    const val SLIDE = 1
    const val FADE = 2
    const val ZOOM = 3
    const val RISE = 4
    const val DEPTH = 5
    const val SHARED_X = 6
    const val NONE = 7
    val NAMES = listOf("Zależnie od stylu", "Przesunięcie z głębią", "Przenikanie", "Powiększenie", "Wsunięcie z dołu", "Głębia", "Wspólna oś", "Bez animacji")
}

/** Grupa stylów w galerii. */
enum class Category(val label: String) {
    CLASSIC("Klasyczny"), PHONE("Telefony i nakładki"), APPS("Komunikatory i aplikacje"), DESKTOP("Komputery"), OTHER("Inne"),
}

/** Nagłówek listy rozmów. */
enum class HomeHeader { STANDARD, LARGE_TITLE, BIG, WORDMARK, COMPACT }

/** Wiersze listy rozmów. */
enum class ListStyle { PLAIN, SEPARATORS, GROUPED, SEGMENTED, CARDS }

/** Pole wpisywania wiadomości. */
enum class InputStyle { OUTLINED, PILL, GLASS, IOS6, WINDOW, MESSENGER, DISCORD, BAR }

/** Przycisk nowej rozmowy. */
enum class FabStyle { CIRCLE, EXTENDED, SQUARE, NONE }

/** Reakcja na dotknięcie. */
enum class Press { RIPPLE, SCALE, HIGHLIGHT, SUNKEN, INVERT }

/** Animacja otwierania menu. */
enum class MenuAnim { GROW, DROP, SLIDE, FADE, SHEET, NONE }

/** Pojawianie się nowej wiadomości / elementu. */
enum class Appear { SPRING, SLIDE_UP, POP, FADE, TYPEWRITER, NONE }

/** Animacja okna dialogowego. */
enum class DialogAnim { ZOOM, BOUNCE, SCALE, SLIDE_UP, FADE, NONE }

/**
 * Charakter ruchu stylu (każdy styl porusza się jak oryginał).
 * Czasy i krzywe według tabel ruchu (Material 3, Apple HIG, Fluent, „Motion Design Skill”).
 */
@Immutable
class MotionSpec(
    val transition: Int = Trans.SLIDE,
    /** Czas przejścia ekranu (ms). */
    val duration: Int = 330,
    /** Krzywa (cubic-bezier) dla przejść. */
    val easing: androidx.compose.animation.core.Easing = androidx.compose.animation.core.CubicBezierEasing(0.2f, 0f, 0f, 1f),
    /** Sprężystość (1 = bez odbicia, mniej = więcej sprężyny). */
    val damping: Float = 0.85f,
    val stiffness: Float = 380f,
    val press: Press = Press.SCALE,
    val menu: MenuAnim = MenuAnim.DROP,
    val appear: Appear = Appear.SPRING,
    val dialog: DialogAnim = DialogAnim.SCALE,
    /** Opis charakteru ruchu (pokazywany przy stylu). */
    val label: String = "",
)

private fun bez(a: Float, b: Float, c: Float, d: Float) = androidx.compose.animation.core.CubicBezierEasing(a, b, c, d)

/** Gotowe charaktery ruchu (osobowości: Premium, Corporate, Playful, Energetic). */
object Motions {
    /** iOS: sprężyste wsunięcie z paralaksą, menu „wyrasta” z przycisku. */
    val Apple = MotionSpec(Trans.SLIDE, 420, bez(0.25f, 0.1f, 0.25f, 1f), 0.86f, 300f, Press.SCALE, MenuAnim.GROW, Appear.SPRING, DialogAnim.ZOOM, label = "sprężysty, z paralaksą")
    /** iOS 6: płynne przesunięcie i „podskakujący” alert. */
    val Glossy = MotionSpec(Trans.SLIDE, 350, bez(0.42f, 0f, 0.58f, 1f), 1f, 400f, Press.HIGHLIGHT, MenuAnim.FADE, Appear.FADE, DialogAnim.BOUNCE, label = "płynny, okna z podskokiem")
    /** Material 3: wspólna oś, krzywa „emphasized”, fala dotyku. */
    val Material = MotionSpec(Trans.SHARED_X, 300, bez(0.2f, 0f, 0f, 1f), 1f, 400f, Press.RIPPLE, MenuAnim.DROP, Appear.SLIDE_UP, DialogAnim.SCALE, label = "wspólna oś, fala dotyku")
    /** Material Expressive: fizyka sprężyn z odbiciem. */
    val Expressive = MotionSpec(Trans.ZOOM, 380, bez(0.05f, 0.7f, 0.1f, 1f), 0.62f, 420f, Press.SCALE, MenuAnim.GROW, Appear.POP, DialogAnim.SCALE, label = "sprężyny z odbiciem")
    /** Nakładki z płynną fizyką (szklane menu, powiększanie). */
    val Fluid = MotionSpec(Trans.ZOOM, 360, bez(0.4f, 0f, 0.2f, 1f), 0.8f, 360f, Press.SCALE, MenuAnim.GROW, Appear.SPRING, DialogAnim.SLIDE_UP, label = "płynna fizyka, powiększanie")
    /** Szybkie i zdecydowane (energetic). */
    val Snappy = MotionSpec(Trans.SHARED_X, 240, bez(0f, 0f, 0f, 1f), 0.9f, 600f, Press.SCALE, MenuAnim.GROW, Appear.SLIDE_UP, DialogAnim.SLIDE_UP, label = "szybki i zdecydowany")
    /** Spokojne, eleganckie (premium). */
    val Calm = MotionSpec(Trans.SHARED_X, 360, bez(0.4f, 0f, 0.2f, 1f), 1f, 300f, Press.HIGHLIGHT, MenuAnim.DROP, Appear.FADE, DialogAnim.SLIDE_UP, label = "spokojny i elegancki")
    /** Minimalistyczne, ostre przenikanie. */
    val Crisp = MotionSpec(Trans.FADE, 200, bez(0f, 0f, 0.2f, 1f), 1f, 700f, Press.HIGHLIGHT, MenuAnim.FADE, Appear.FADE, DialogAnim.FADE, label = "ostre przenikanie")
    /** Windows 11: wsunięcie z dołu (40 px) z wygaszeniem, krzywa decelerate. */
    val Fluent = MotionSpec(Trans.RISE, 333, bez(0.1f, 0.9f, 0.2f, 1f), 1f, 500f, Press.HIGHLIGHT, MenuAnim.SLIDE, Appear.SLIDE_UP, DialogAnim.SCALE, label = "wsunięcie od dołu z wygaszeniem")
    /** Windows 7: okna otwierają się z lekkim powiększeniem. */
    val Aero = MotionSpec(Trans.ZOOM, 250, bez(0f, 0f, 0.2f, 1f), 1f, 500f, Press.HIGHLIGHT, MenuAnim.FADE, Appear.FADE, DialogAnim.ZOOM, label = "powiększanie okien")
    /** Windows XP: menu wysuwane, wciskane przyciski. */
    val Luna = MotionSpec(Trans.FADE, 150, bez(0f, 0f, 1f, 1f), 1f, 800f, Press.SUNKEN, MenuAnim.SLIDE, Appear.NONE, DialogAnim.NONE, label = "wysuwane menu, wciskane przyciski")
    /** Windows 95: wszystko natychmiast, przyciski „wciskają się” w ramkę 3D. */
    val Retro = MotionSpec(Trans.NONE, 0, bez(0f, 0f, 1f, 1f), 1f, 1000f, Press.SUNKEN, MenuAnim.NONE, Appear.NONE, DialogAnim.NONE, label = "natychmiastowy, przyciski 3D")
    /** Terminal: cięcie, odwrócenie kolorów przy dotknięciu, tekst pisany znak po znaku. */
    val Terminal = MotionSpec(Trans.NONE, 0, bez(0f, 0f, 1f, 1f), 1f, 1000f, Press.INVERT, MenuAnim.NONE, Appear.TYPEWRITER, DialogAnim.NONE, label = "cięcie i pisanie znak po znaku")
    /** Komunikator z szybkim przesunięciem i dymkiem „lecącym” z pola wiadomości. */
    val Messaging = MotionSpec(Trans.SLIDE, 240, bez(0f, 0f, 0.2f, 1f), 0.9f, 500f, Press.RIPPLE, MenuAnim.GROW, Appear.SLIDE_UP, DialogAnim.SCALE, label = "szybkie przesunięcie")
    /** Zabawne „wyskakiwanie” (playful). */
    val Playful = MotionSpec(Trans.SLIDE, 300, bez(0.175f, 0.885f, 0.32f, 1.275f), 0.55f, 420f, Press.SCALE, MenuAnim.GROW, Appear.POP, DialogAnim.SCALE, label = "zabawne wyskakiwanie")
    /** Spokojne, korporacyjne. */
    val Corporate = MotionSpec(Trans.FADE, 250, bez(0.2f, 0f, 0f, 1f), 1f, 500f, Press.HIGHLIGHT, MenuAnim.DROP, Appear.SLIDE_UP, DialogAnim.SCALE, label = "spokojne przenikanie")
    /** Serwerowy czat: szybkie sprężyny, menu wysuwane od dołu. */
    val Gamer = MotionSpec(Trans.SLIDE, 200, bez(0f, 0f, 0f, 1f), 0.85f, 700f, Press.SCALE, MenuAnim.SHEET, Appear.FADE, DialogAnim.SLIDE_UP, label = "szybkie sprężyny, menu od dołu")
}

/** Charakter ruchu do wyboru w ustawieniach (0 = jak w stylu). */
val MOTION_PRESETS: List<Pair<String, MotionSpec?>> = listOf(
    "Jak w stylu" to null,
    "Sprężysty" to Motions.Apple,
    "Materiałowy" to Motions.Material,
    "Z odbiciem" to Motions.Expressive,
    "Płynna fizyka" to Motions.Fluid,
    "Szybki" to Motions.Snappy,
    "Spokojny" to Motions.Calm,
    "Ostry" to Motions.Crisp,
    "Zabawny" to Motions.Playful,
    "Okienkowy" to Motions.Fluent,
    "Retro" to Motions.Retro,
    "Terminal" to Motions.Terminal,
)

/** Wypełnienie powierzchni: kolor (może być przezroczysty), gradient, obwódka i kolor tekstu na niej. */
@Immutable
class Fill(val color: Color, val text: Color, val line: Color = Color.Transparent, val grad: List<Color>? = null) {
    /** Kolor „mniej więcej” (średnia gradientu, nałożona na tło) — do kontrastu i nieprzezroczystych zamienników. */
    fun solid(over: Color): Color = (grad?.let { avg(it) } ?: color).compositeOver(over)
}

/** Paleta stylu w jednej wersji (jasnej albo ciemnej). */
@Immutable
class Pal(
    val dark: Boolean,
    /** Nieprzezroczyste tło (pod ruchomymi elementami). */
    val base: Color,
    val onBase: Color,
    /** Tekst drugorzędny. */
    val muted: Color,
    val primary: Color,
    val onPrimary: Color,
    val mine: Fill,
    val theirs: Fill,
    /** Karty rozmów na liście (null = zwykłe wiersze). */
    val card: Fill?,
    val bar: Fill,
    val chip: Fill,
    val line: Color,
    /** Cień (miękki albo twardy — zależnie od stylu). */
    val shade: Color = if (dark) Color.Black.copy(alpha = 0.5f) else Color.Black.copy(alpha = 0.16f),
    /** Rozświetlenie (np. jasny cień neumorfizmu). */
    val light: Color = Color.White.copy(alpha = if (dark) 0.08f else 0.9f),
    /** Kolory tła stylu. */
    val bg: List<Color> = emptyList(),
    val error: Color = if (dark) Color(0xFFFFB4AB) else Color(0xFFBA1A1A),
)

class StyleSpec(
    val id: String,
    val name: String,
    val desc: String,
    val mode: Mode = Mode.FOLLOW,
    /** Korzysta z koloru wybranego w „Wyglądzie”. */
    val accent: Boolean = false,
    val surf: Surf = Surf.FLAT,
    val surfDark: Surf? = null,
    val bg: Bg = Bg.PLAIN,
    /** Tło się porusza (gdy animacje tła są włączone). */
    val moving: Boolean = false,
    val radius: Dp = 18.dp,
    val tail: Dp = 4.dp,
    val corners: IntArray = intArrayOf(4, 8, 12, 16, 28),
    val cut: Boolean = false,
    val organic: Boolean = false,
    val comicTail: Boolean = false,
    val cards: Boolean = false,
    val floatingBars: Boolean = false,
    val dividers: Boolean = false,
    val font: FontFamily? = null,
    val headWeight: FontWeight? = null,
    val tracking: Float = 0f,
    val transition: Int = Trans.SLIDE,
    val avatar: AvatarShape = AvatarShape.CIRCLE,
    val tilt: Boolean = false,
    val iridescent: Boolean = false,
    val tape: Boolean = false,
    val spark: Spark = Spark.DOTS,
    /** Własne tło obszaru wiadomości (np. pulpit w Windows 95, niebo w iOS 26). */
    val chatBg: Bg? = null,
    /** Paski, pole wiadomości i nagłówki jak w oryginale. */
    val chrome: Chrome = Chrome.MATERIAL,
    /** Ogonek dymka. */
    val tailKind: Tail = Tail.NONE,
    /** Dymki w serii mają mniejsze rogi od strony nadawcy (Google Messages, Messenger). */
    val grouped: Boolean = false,
    /** Kolor moich dymków zależy od miejsca na ekranie (gradient jak w Messengerze). */
    val gradientMine: Boolean = false,
    /** Kolory systemowe z tapety (Material You, Android 12+). */
    val dynamic: Boolean = false,
    /** Inspiracja (wewnętrznie — w aplikacji nie pokazujemy nazw firm). */
    val origin: String = "",
    val category: Category = Category.OTHER,
    val homeHeader: HomeHeader = HomeHeader.STANDARD,
    val listStyle: ListStyle = ListStyle.PLAIN,
    val input: InputStyle = InputStyle.OUTLINED,
    val fab: FabStyle = FabStyle.CIRCLE,
    /** Wiadomości bez dymków (awatar, nazwa i tekst — jak na czatach serwerowych). */
    val flatMessages: Boolean = false,
    /** Pole wyszukiwania pod tytułem listy rozmów. */
    val searchPill: Boolean = false,
    /** Tytuł „Szept” z kropek (wyświetlacz matrycowy). */
    val dotTitle: Boolean = false,
    /** Czcionka dużych nagłówków (np. szeryfowa). */
    val titleFont: FontFamily? = null,
    val motion: MotionSpec = Motions.Material,
    val palette: (dark: Boolean, accentIdx: Int, black: Boolean) -> Pal,
) {
    fun surfFor(dark: Boolean) = if (dark) surfDark ?: surf else surf
}

/** Aktualny styl z gotową paletą. */
@Immutable
class StyleState(val spec: StyleSpec, val pal: Pal, val motion: MotionSpec = spec.motion) {
    val dark get() = pal.dark
    val surf get() = spec.surfFor(pal.dark)
    fun fill(role: Role): Fill = when (role) {
        Role.MINE -> pal.mine
        Role.THEIRS -> pal.theirs
        Role.CARD -> pal.card ?: pal.theirs
        Role.BAR, Role.INPUT -> pal.bar
        Role.CHIP -> pal.chip
    }
}

/** Rola powierzchni — od niej zależy wypełnienie i wielkość cieni. */
enum class Role { MINE, THEIRS, CARD, BAR, INPUT, CHIP }

// ======================= pomocnicze =======================

private fun c(v: Long) = Color(v)
private fun w(a: Float) = Color.White.copy(alpha = a)
private fun Color.a(a: Float) = copy(alpha = a)

fun avg(l: List<Color>): Color {
    var r = 0f; var g = 0f; var b = 0f; var a = 0f
    l.forEach { r += it.red; g += it.green; b += it.blue; a += it.alpha }
    val n = l.size.coerceAtLeast(1)
    return Color(r / n, g / n, b / n, a / n)
}

/** Współczynnik kontrastu WCAG (1…21). */
fun contrast(a: Color, b: Color): Float {
    val la = a.luminance() + 0.05f
    val lb = b.luminance() + 0.05f
    return if (la > lb) la / lb else lb / la
}

/** Przyciemnia/rozjaśnia kolor, aż będzie czytelny na danym tle. */
fun readable(col: Color, bg: Color, min: Float = 4.5f): Color {
    var x = col
    val target = if (bg.luminance() > 0.35f) Color.Black else Color.White
    var i = 0
    while (contrast(x, bg) < min && i++ < 14) x = lerp(x, target, 0.1f)
    return x
}

fun accentOf(i: Int): Color = ACCENTS.getOrElse(i) { Brand }

// ======================= styl klasyczny (dotychczasowy wygląd) =======================

/** Dokładnie ten sam schemat kolorów, co przed wprowadzeniem stylów. */
fun classicScheme(dark: Boolean, black: Boolean, ai: Int): ColorScheme {
    val a = accentOf(ai)
    val bg = if (black) Color.Black else Color(0xFF121218)
    return if (dark) {
        if (ai == 0) darkColorScheme(
            primary = Color(0xFFB7AEFF), onPrimary = Color(0xFF21106F), primaryContainer = Color(0xFF3F31B3),
            onPrimaryContainer = Color(0xFFE4DFFF), secondaryContainer = if (black) Color(0xFF1E1C28) else Color(0xFF2E2B3D),
            background = bg, surface = bg,
        ) else darkColorScheme(
            primary = lerp(a, Color.White, 0.45f), onPrimary = lerp(a, Color.Black, 0.7f), primaryContainer = lerp(a, Color.Black, 0.35f),
            onPrimaryContainer = lerp(a, Color.White, 0.85f), secondaryContainer = lerp(if (black) Color(0xFF1C1C22) else Color(0xFF2B2B33), a, 0.12f),
            background = bg, surface = bg,
        )
    } else {
        if (ai == 0) lightColorScheme(
            primary = Brand, onPrimary = Color.White, primaryContainer = Color(0xFFE4DFFF),
            onPrimaryContainer = Color(0xFF170065), secondaryContainer = Color(0xFFECEAF4),
            background = Color(0xFFF7F7FB), surface = Color(0xFFF7F7FB),
        ) else lightColorScheme(
            primary = a, onPrimary = Color.White, primaryContainer = lerp(a, Color.White, 0.82f),
            onPrimaryContainer = lerp(a, Color.Black, 0.6f), secondaryContainer = lerp(Color(0xFFEDEDF2), a, 0.07f),
            background = Color(0xFFF7F7FB), surface = Color(0xFFF7F7FB),
        )
    }
}

private fun classicPal(dark: Boolean, ai: Int, black: Boolean): Pal {
    val cs = classicScheme(dark, black, ai)
    return Pal(
        dark, cs.surface, cs.onSurface, cs.onSurfaceVariant, cs.primary, cs.onPrimary,
        mine = Fill(cs.primaryContainer, cs.onPrimaryContainer), theirs = Fill(cs.secondaryContainer, cs.onSecondaryContainer),
        card = null, bar = Fill(cs.surface, cs.onSurface), chip = Fill(cs.surfaceVariant, cs.onSurfaceVariant), line = cs.outline,
    )
}

// ======================= katalog stylów =======================

/** Paleta z gotowego schematu Material (Material You — kolory z tapety). */
fun palFromScheme(cs: ColorScheme, dark: Boolean): Pal = Pal(
    dark, cs.surface, cs.onSurface, cs.onSurfaceVariant, cs.primary, cs.onPrimary,
    mine = Fill(cs.primaryContainer, cs.onPrimaryContainer), theirs = Fill(cs.surfaceContainerHigh, cs.onSurface),
    card = Fill(cs.surfaceContainerLow, cs.onSurface), bar = Fill(cs.surfaceContainer, cs.onSurface),
    chip = Fill(cs.surfaceContainerHighest, cs.onSurfaceVariant), line = cs.outline,
)

/** Schemat Material dla koloru akcentu (gdy kolory z tapety są niedostępne). */
private fun seedScheme(dark: Boolean, ai: Int): ColorScheme {
    val a = accentOf(ai)
    return if (!dark) {
        val b = lerp(Color.White, a, 0.04f)
        lightColorScheme(
            primary = readable(a, b), onPrimary = Color.White, primaryContainer = lerp(a, Color.White, 0.8f), onPrimaryContainer = lerp(a, Color.Black, 0.72f),
            secondaryContainer = lerp(b, a, 0.14f), onSecondaryContainer = c(0xFF1B1B21),
            surface = b, onSurface = c(0xFF1B1B21), onSurfaceVariant = c(0xFF46464F),
            surfaceContainerLowest = Color.White, surfaceContainerLow = lerp(b, a, 0.04f), surfaceContainer = lerp(b, a, 0.07f),
            surfaceContainerHigh = lerp(b, a, 0.1f), surfaceContainerHighest = lerp(b, a, 0.13f), outline = c(0xFF777680),
        )
    } else {
        val b = lerp(c(0xFF121318), a, 0.04f)
        darkColorScheme(
            primary = lerp(a, Color.White, 0.5f), onPrimary = lerp(a, Color.Black, 0.75f), primaryContainer = lerp(a, Color.Black, 0.45f), onPrimaryContainer = lerp(a, Color.White, 0.85f),
            secondaryContainer = lerp(b, a, 0.2f), onSecondaryContainer = c(0xFFE4E1EC),
            surface = b, onSurface = c(0xFFE4E1E9), onSurfaceVariant = c(0xFFC7C5D0),
            surfaceContainerLowest = lerp(b, Color.Black, 0.4f), surfaceContainerLow = lerp(b, a, 0.06f), surfaceContainer = lerp(b, a, 0.09f),
            surfaceContainerHigh = lerp(b, a, 0.13f), surfaceContainerHighest = lerp(b, a, 0.17f), outline = c(0xFF918F9A),
        )
    }
}

val STYLES: List<StyleSpec> = listOf(
    // ======================= klasyczny =======================
    StyleSpec(
        "classic", "Szept klasyczny", "Wygląd znany z poprzednich wersji Szepta — bez zmian, w wybranym kolorze.",
        accent = true, origin = "Szept", category = Category.CLASSIC,
        motion = MotionSpec(Trans.SLIDE, 300, bez(0.2f, 0f, 0f, 1f), 0.9f, 420f, Press.RIPPLE, MenuAnim.DROP, Appear.SPRING, DialogAnim.SCALE, "przesunięcie, jak dotąd"),
        palette = ::classicPal,
    ),
    // ======================= telefony i nakładki =======================
    StyleSpec(
        "liquid", "Liquid Glass", "Pływające szklane przyciski, które rozmywają i załamują treść pod spodem, duży tytuł i dymki z zawiniętymi ogonkami.",
        surf = Surf.FLAT, radius = 18.dp, tail = 18.dp, corners = intArrayOf(10, 14, 20, 26, 34), floatingBars = true,
        chrome = Chrome.GLASS, tailKind = Tail.IMESSAGE, origin = "iOS 26", category = Category.PHONE,
        homeHeader = HomeHeader.LARGE_TITLE, listStyle = ListStyle.SEPARATORS, input = InputStyle.GLASS, fab = FabStyle.NONE, searchPill = true, motion = Motions.Apple,
    ) { dark, _, _ ->
        if (!dark) Pal(
            false, Color.White, Color.Black, c(0xFF6C6C71), c(0xFF0088FF), Color.White,
            mine = Fill(c(0xFF0088FF), Color.White), theirs = Fill(c(0xFFE9E9EB), Color.Black),
            card = null, bar = Fill(Color.Transparent, Color.Black), chip = Fill(Color.Transparent, c(0xFF6C6C71)), line = c(0xFFC6C6C8),
        ) else Pal(
            true, Color.Black, Color.White, c(0xFF98989F), c(0xFF0091FF), Color.White,
            mine = Fill(c(0xFF0088FF), Color.White), theirs = Fill(c(0xFF262628), Color.White),
            card = null, bar = Fill(Color.Transparent, Color.White), chip = Fill(Color.Transparent, c(0xFF98989F)), line = c(0xFF38383A),
        )
    },
    StyleSpec(
        "liquid_sky", "Liquid Glass Niebo", "Szkło na tle nieba z chmurami — kolory przenikają przez przyciski, a dymki rozmówcy są przydymionym szkłem.",
        surf = Surf.FLAT, radius = 18.dp, tail = 18.dp, corners = intArrayOf(10, 14, 20, 26, 34), floatingBars = true, moving = true,
        chrome = Chrome.GLASS, tailKind = Tail.IMESSAGE, chatBg = Bg.SKY, origin = "iOS 26", category = Category.PHONE,
        homeHeader = HomeHeader.LARGE_TITLE, listStyle = ListStyle.SEPARATORS, input = InputStyle.GLASS, fab = FabStyle.NONE, searchPill = true, motion = Motions.Apple,
    ) { dark, _, _ ->
        if (!dark) Pal(
            false, Color.White, Color.Black, c(0xFF6C6C71), c(0xFF0088FF), Color.White,
            mine = Fill(c(0xFF0088FF), Color.White), theirs = Fill(c(0xFF0A2A55).a(0.5f), Color.White),
            card = null, bar = Fill(Color.Transparent, Color.Black), chip = Fill(c(0xFF0A2A55).a(0.5f), Color.White), line = c(0xFFC6C6C8),
            bg = listOf(c(0xFF1F6FDB), c(0xFF3D93F0), c(0xFF79B9F6)),
        ) else Pal(
            true, Color.Black, Color.White, c(0xFF98989F), c(0xFF0091FF), Color.White,
            mine = Fill(c(0xFF0088FF), Color.White), theirs = Fill(Color.Black.a(0.38f), Color.White),
            card = null, bar = Fill(Color.Transparent, Color.White), chip = Fill(Color.Black.a(0.4f), Color.White), line = c(0xFF38383A),
            bg = listOf(c(0xFF061533), c(0xFF0E2E63), c(0xFF1C4A8F)),
        )
    },
    StyleSpec(
        "materialyou", "Material You", "Kolory dobrane do tapety telefonu, zaokrąglone pole wiadomości z buźką, dymki w serii, rozszerzony przycisk „Nowa rozmowa” i fala przy dotknięciu.",
        radius = 20.dp, tail = 20.dp, corners = intArrayOf(8, 12, 16, 24, 28), grouped = true, dynamic = true, accent = true,
        origin = "Android 12–15", category = Category.PHONE, input = InputStyle.PILL, fab = FabStyle.EXTENDED, motion = Motions.Material,
    ) { dark, ai, _ -> palFromScheme(seedScheme(dark, ai), dark) },
    StyleSpec(
        "expressive", "Expressive Pop", "Rozmowa w zaokrąglonym kontenerze, lista z segmentów, pigułki i sprężysty ruch z odbiciem.",
        radius = 20.dp, tail = 20.dp, corners = intArrayOf(10, 16, 24, 28, 32), cards = true, grouped = true, dynamic = true, accent = true,
        headWeight = FontWeight.Medium, chrome = Chrome.EXPRESSIVE, origin = "Android 16", category = Category.PHONE,
        listStyle = ListStyle.SEGMENTED, input = InputStyle.PILL, fab = FabStyle.EXTENDED, motion = Motions.Expressive,
    ) { dark, ai, _ -> palFromScheme(seedScheme(dark, ai), dark) },
    StyleSpec(
        "oneui", "One Flow", "Wielki nagłówek do obsługi jedną ręką, lista w jednej zaokrąglonej karcie, niebieskie dymki i okna wysuwane od dołu.",
        radius = 18.dp, tail = 18.dp, corners = intArrayOf(10, 14, 20, 26, 30), cards = true, chrome = Chrome.ONEUI,
        origin = "One UI 7", category = Category.PHONE, homeHeader = HomeHeader.BIG, listStyle = ListStyle.GROUPED, input = InputStyle.PILL,
        motion = MotionSpec(Trans.ZOOM, 380, bez(0.33f, 0f, 0.3f, 1f), 0.95f, 340f, Press.SCALE, MenuAnim.GROW, Appear.SLIDE_UP, DialogAnim.SLIDE_UP, "powiększanie, okna od dołu"),
    ) { dark, _, _ ->
        if (!dark) Pal(
            false, c(0xFFF6F6F8), Color.Black, c(0xFF66666B), c(0xFF3570EC), Color.White,
            mine = Fill(c(0xFF3570EC), Color.White), theirs = Fill(Color.White, Color.Black),
            card = Fill(Color.White, Color.Black), bar = Fill(c(0xFFF6F6F8), Color.Black), chip = Fill(c(0xFFE7E7EA), c(0xFF505055)), line = c(0xFFD2D2D6),
        ) else Pal(
            true, Color.Black, c(0xFFFAFAFA), c(0xFFA1A1A6), c(0xFF6B9BF7), Color.Black,
            mine = Fill(c(0xFF3567D9), Color.White), theirs = Fill(c(0xFF2A2A2D), Color.White),
            card = Fill(c(0xFF171717), c(0xFFFAFAFA)), bar = Fill(Color.Black, c(0xFFFAFAFA)), chip = Fill(c(0xFF242426), c(0xFFB5B5BA)), line = c(0xFF3A3A3D),
        )
    },
    StyleSpec(
        "hyper", "Hyper Minimal", "Szare tło, białe karty z dużymi zaokrągleniami, ogromny cienki tytuł, pole szukania pod nim i płynna fizyka animacji.",
        radius = 18.dp, tail = 6.dp, corners = intArrayOf(10, 14, 18, 22, 28), cards = true, chrome = Chrome.HYPER,
        origin = "HyperOS 2", category = Category.PHONE, homeHeader = HomeHeader.LARGE_TITLE, listStyle = ListStyle.GROUPED,
        input = InputStyle.PILL, searchPill = true, headWeight = FontWeight.Light, motion = Motions.Fluid,
    ) { dark, _, _ ->
        if (!dark) Pal(
            false, c(0xFFF7F7F7), Color.Black, c(0xFF6E6E73), c(0xFF3482FF), Color.White,
            mine = Fill(c(0xFF3482FF), Color.White), theirs = Fill(Color.White, Color.Black),
            card = Fill(Color.White, Color.Black), bar = Fill(c(0xFFF7F7F7), Color.Black), chip = Fill(c(0xFFEDEDED), c(0xFF5E5E63)), line = c(0xFFD9D9D9),
        ) else Pal(
            true, Color.Black, c(0xFFE6E6E6), c(0xFF8C8C8C), c(0xFF4C8DFF), Color.White,
            mine = Fill(c(0xFF2D6AE0), Color.White), theirs = Fill(c(0xFF1F1F1F), c(0xFFE6E6E6)),
            card = Fill(c(0xFF1A1A1A), c(0xFFE6E6E6)), bar = Fill(Color.Black, c(0xFFE6E6E6)), chip = Fill(c(0xFF1F1F1F), c(0xFF9A9A9A)), line = c(0xFF333333),
        )
    },
    StyleSpec(
        "nothing", "Nothing Dot", "Czerń, biel i czerwony akcent, tytuł z kropek jak na wyświetlaczu matrycowym, szeryfowe nagłówki i ostre, szybkie przenikania.",
        radius = 20.dp, tail = 20.dp, corners = intArrayOf(8, 12, 18, 22, 28), cards = true, chrome = Chrome.NOTHING,
        origin = "Nothing OS 3", category = Category.PHONE, homeHeader = HomeHeader.LARGE_TITLE, listStyle = ListStyle.PLAIN,
        input = InputStyle.PILL, dotTitle = true, titleFont = FontFamily.Serif, motion = Motions.Crisp,
    ) { dark, _, _ ->
        if (dark) Pal(
            true, Color.Black, Color.White, c(0xFFA8A8A8), c(0xFFD71921), Color.White,
            mine = Fill(Color.White, Color.Black), theirs = Fill(c(0xFF1B1B1B), Color.White),
            card = Fill(c(0xFF1B1B1B), Color.White), bar = Fill(Color.Black, Color.White), chip = Fill(c(0xFF1B1B1B), c(0xFFA8A8A8)), line = c(0xFF3A3A3A),
            error = c(0xFFFF4D55),
        ) else Pal(
            false, Color.White, Color.Black, c(0xFF666666), c(0xFFD71921), Color.White,
            mine = Fill(Color.Black, Color.White), theirs = Fill(c(0xFFF0F0F0), Color.Black),
            card = Fill(c(0xFFF2F2F2), Color.Black), bar = Fill(Color.White, Color.Black), chip = Fill(c(0xFFF0F0F0), c(0xFF666666)), line = c(0xFFCFCFCF),
        )
    },
    StyleSpec(
        "oxygen", "Fluid Clean", "Jasnoszare tło, białe karty, pogrubione nagłówki, niebieskie pigułki i menu na rozmytym tle — szybki, zdecydowany ruch.",
        radius = 18.dp, tail = 18.dp, corners = intArrayOf(10, 14, 16, 22, 28), cards = true, chrome = Chrome.OXYGEN, headWeight = FontWeight.Bold,
        origin = "OxygenOS 15", category = Category.PHONE, homeHeader = HomeHeader.LARGE_TITLE, listStyle = ListStyle.GROUPED,
        input = InputStyle.PILL, motion = Motions.Snappy,
    ) { dark, _, _ ->
        if (!dark) Pal(
            false, c(0xFFF0F1F3), Color.Black, c(0xFF65686D), c(0xFF2B63EA), Color.White,
            mine = Fill(c(0xFF2B63EA), Color.White), theirs = Fill(Color.White, Color.Black),
            card = Fill(Color.White, Color.Black), bar = Fill(c(0xFFF0F1F3), Color.Black), chip = Fill(c(0xFFE4E6EA), c(0xFF55585D)), line = c(0xFFD3D5D9),
        ) else Pal(
            true, Color.Black, c(0xFFF2F2F2), c(0xFF9A9DA2), c(0xFF5585F2), Color.White,
            mine = Fill(c(0xFF2F62E0), Color.White), theirs = Fill(c(0xFF1C1C1E), c(0xFFF2F2F2)),
            card = Fill(c(0xFF151515), c(0xFFF2F2F2)), bar = Fill(Color.Black, c(0xFFF2F2F2)), chip = Fill(c(0xFF1C1C1E), c(0xFF9A9DA2)), line = c(0xFF333336),
        )
    },
    StyleSpec(
        "coloros", "Soft Breeze", "Miękkie, jasne powierzchnie, zielony akcent, zaokrąglone karty i spokojne, powiększające przejścia.",
        radius = 20.dp, tail = 20.dp, corners = intArrayOf(10, 14, 18, 24, 28), cards = true, chrome = Chrome.COLOROS,
        origin = "ColorOS 15", category = Category.PHONE, homeHeader = HomeHeader.LARGE_TITLE, listStyle = ListStyle.GROUPED,
        input = InputStyle.PILL, motion = MotionSpec(Trans.ZOOM, 380, bez(0.4f, 0f, 0.2f, 1f), 0.92f, 320f, Press.SCALE, MenuAnim.GROW, Appear.SLIDE_UP, DialogAnim.SLIDE_UP, "spokojne powiększanie"),
    ) { dark, _, _ ->
        if (!dark) Pal(
            false, c(0xFFF5F6F8), Color.Black, c(0xFF65686D), c(0xFF0B8C55), Color.White,
            mine = Fill(c(0xFF0E9A5F), Color.White), theirs = Fill(Color.White, Color.Black),
            card = Fill(Color.White, Color.Black), bar = Fill(c(0xFFF5F6F8), Color.Black), chip = Fill(c(0xFFE7E9EC), c(0xFF55585D)), line = c(0xFFD5D7DA),
        ) else Pal(
            true, Color.Black, c(0xFFF0F0F0), c(0xFF9B9EA3), c(0xFF2AD181), c(0xFF002814),
            mine = Fill(c(0xFF14965C), Color.White), theirs = Fill(c(0xFF1E1E1E), c(0xFFF0F0F0)),
            card = Fill(c(0xFF161616), c(0xFFF0F0F0)), bar = Fill(Color.Black, c(0xFFF0F0F0)), chip = Fill(c(0xFF1E1E1E), c(0xFF9B9EA3)), line = c(0xFF333333),
        )
    },
    StyleSpec(
        "emui", "Crystal Blue", "Duży tytuł z kapsułką wyszukiwania, lista z cienkimi liniami, intensywny błękit i okna wysuwane od dołu.",
        radius = 18.dp, tail = 18.dp, corners = intArrayOf(8, 12, 16, 24, 28), chrome = Chrome.EMUI,
        origin = "EMUI 14", category = Category.PHONE, homeHeader = HomeHeader.LARGE_TITLE, listStyle = ListStyle.SEPARATORS,
        input = InputStyle.PILL, searchPill = true, motion = Motions.Calm,
    ) { dark, _, _ ->
        if (!dark) Pal(
            false, c(0xFFF1F3F5), Color.Black, c(0xFF656A70), c(0xFF007DFF), Color.White,
            mine = Fill(c(0xFF007DFF), Color.White), theirs = Fill(Color.White, Color.Black),
            card = null, bar = Fill(c(0xFFF1F3F5), Color.Black), chip = Fill(c(0xFFE3E6E9), c(0xFF55595E)), line = c(0xFFD6D9DC),
        ) else Pal(
            true, Color.Black, c(0xFFE6E6E6), c(0xFF8F9399), c(0xFF3A8EF6), Color.White,
            mine = Fill(c(0xFF0A6BD9), Color.White), theirs = Fill(c(0xFF202224), c(0xFFE6E6E6)),
            card = null, bar = Fill(Color.Black, c(0xFFE6E6E6)), chip = Fill(c(0xFF1C1E20), c(0xFF8F9399)), line = c(0xFF2E3033),
        )
    },
    StyleSpec(
        "ios6", "Glossy 2012", "Błyszczące dymki z ogonkami, niebiesko-szary pasek z wytłoczonym napisem, przycisk „Wyślij” i podskakujące okna.",
        mode = Mode.LIGHT, surf = Surf.GLOSSY, bg = Bg.IOS6, radius = 16.dp, tail = 16.dp, corners = intArrayOf(4, 6, 8, 10, 12),
        chrome = Chrome.IOS6, tailKind = Tail.IMESSAGE, avatar = AvatarShape.SQUIRCLE, origin = "iOS 6", category = Category.PHONE,
        listStyle = ListStyle.SEPARATORS, input = InputStyle.IOS6, fab = FabStyle.NONE, motion = Motions.Glossy,
    ) { _, _, _ ->
        Pal(
            false, c(0xFFDBE2ED), Color.Black, c(0xFF475365), c(0xFF2F6BC9), Color.White,
            mine = Fill(c(0xFF2E7DE6), Color.White, line = c(0xFF1C5DB8), grad = listOf(c(0xFF6EAFFB), c(0xFF2D7DE8), c(0xFF1A66D2))),
            theirs = Fill(c(0xFFE6E6E6), Color.Black, line = c(0xFFAFB4BC), grad = listOf(c(0xFFFBFBFB), c(0xFFE9E9E9), c(0xFFDADADA))),
            card = Fill(Color.White, Color.Black, line = c(0xFFB9C2CF)),
            bar = Fill(c(0xFF7489A8), Color.White, line = c(0xFF2D3B52), grad = listOf(c(0xFFA3B2C7), c(0xFF7D90AE), c(0xFF5F7698))),
            chip = Fill(c(0xFFB9C3D2), c(0xFF2C3645)), line = c(0xFF8E9AAB), shade = Color.Black.a(0.35f),
        )
    },
    // ======================= komunikatory i aplikacje =======================
    StyleSpec(
        "telegram", "Doodle Gradient", "Niebieski pasek, jasnozielone dymki z ostrymi ogonkami i animowany gradient z rysunkami, który przesuwa się przy każdej wysłanej wiadomości.",
        chatBg = Bg.TELEGRAM, radius = 16.dp, tail = 16.dp, corners = intArrayOf(6, 8, 12, 14, 16), chrome = Chrome.TELEGRAM,
        tailKind = Tail.TELEGRAM, origin = "Telegram", category = Category.APPS, listStyle = ListStyle.SEPARATORS, input = InputStyle.BAR,
        motion = Motions.Messaging,
    ) { dark, _, _ ->
        if (!dark) Pal(
            false, Color.White, Color.Black, c(0xFF6D7883), c(0xFF3390EC), Color.White,
            mine = Fill(c(0xFFEFFDDE), Color.Black), theirs = Fill(Color.White, Color.Black),
            card = null, bar = Fill(c(0xFF527DA3), Color.White), chip = Fill(c(0xFF3F5D3D).a(0.66f), Color.White), line = c(0xFFC8C7CC),
            bg = listOf(c(0xFFDBDDBB), c(0xFF6BA587), c(0xFFD5D88D), c(0xFF88B884)),
        ) else Pal(
            true, c(0xFF17212B), c(0xFFF5F5F5), c(0xFF7D8B99), c(0xFF5EB5F7), c(0xFF0E1621),
            mine = Fill(c(0xFF2B5278), Color.White), theirs = Fill(c(0xFF182533), Color.White),
            card = null, bar = Fill(c(0xFF232E3C), Color.White), chip = Fill(Color.Black.a(0.38f), Color.White), line = c(0xFF2B3A4A),
            bg = listOf(c(0xFF1B3A4B), c(0xFF213547), c(0xFF2C3E50), c(0xFF17303A)),
        )
    },
    StyleSpec(
        "whatsapp", "Green Doodle", "Zielony pasek, beżowe tło z rysunkami, zielone dymki z „uszkiem” u góry, białe pole z okrągłym zielonym przyciskiem.",
        chatBg = Bg.WHATSAPP, radius = 8.dp, tail = 8.dp, corners = intArrayOf(4, 6, 8, 10, 12), chrome = Chrome.WHATSAPP,
        tailKind = Tail.WHATSAPP, origin = "WhatsApp", category = Category.APPS, input = InputStyle.PILL, fab = FabStyle.SQUARE,
        motion = MotionSpec(Trans.SHARED_X, 260, bez(0.2f, 0f, 0f, 1f), 1f, 500f, Press.RIPPLE, MenuAnim.DROP, Appear.FADE, DialogAnim.SCALE, "wspólna oś, przenikanie"),
    ) { dark, _, _ ->
        if (!dark) Pal(
            false, Color.White, c(0xFF111B21), c(0xFF667781), c(0xFF008069), Color.White,
            mine = Fill(c(0xFFD9FDD3), c(0xFF111B21)), theirs = Fill(Color.White, c(0xFF111B21)),
            card = null, bar = Fill(c(0xFF008069), Color.White), chip = Fill(Color.White, c(0xFF54656F)), line = c(0xFFD1D7DB),
        ) else Pal(
            true, c(0xFF111B21), c(0xFFE9EDEF), c(0xFF8696A0), c(0xFF00A884), c(0xFF111B21),
            mine = Fill(c(0xFF005C4B), c(0xFFE9EDEF)), theirs = Fill(c(0xFF202C33), c(0xFFE9EDEF)),
            card = null, bar = Fill(c(0xFF202C33), c(0xFFE9EDEF)), chip = Fill(c(0xFF182229), c(0xFF8696A0)), line = c(0xFF2A3942),
        )
    },
    StyleSpec(
        "messenger", "Gradient Bubbles", "Okrągłe dymki w serii z gradientem zależnym od miejsca na ekranie, rząd kolorowych ikon, pole „Aa” i kciuk w górę.",
        radius = 18.dp, tail = 18.dp, corners = intArrayOf(8, 12, 16, 20, 24), grouped = true, gradientMine = true, chrome = Chrome.MESSENGER,
        origin = "Messenger", category = Category.APPS, homeHeader = HomeHeader.LARGE_TITLE, input = InputStyle.MESSENGER, fab = FabStyle.NONE,
        searchPill = true, motion = Motions.Playful,
    ) { dark, _, _ ->
        if (!dark) Pal(
            false, Color.White, c(0xFF050505), c(0xFF65676B), c(0xFF0084FF), Color.White,
            mine = Fill(c(0xFF0084FF), Color.White, grad = listOf(c(0xFFA334FA), c(0xFF6B4BFF), c(0xFF0084FF))), theirs = Fill(c(0xFFF0F0F0), c(0xFF050505)),
            card = null, bar = Fill(Color.White, c(0xFF050505)), chip = Fill(Color.Transparent, c(0xFF65676B)), line = c(0xFFCED0D4),
        ) else Pal(
            true, Color.Black, c(0xFFE4E6EB), c(0xFFB0B3B8), c(0xFF0A93FF), Color.Black,
            mine = Fill(c(0xFF0084FF), Color.White, grad = listOf(c(0xFFA334FA), c(0xFF6B4BFF), c(0xFF0084FF))), theirs = Fill(c(0xFF303030), c(0xFFE4E6EB)),
            card = null, bar = Fill(Color.Black, c(0xFFE4E6EB)), chip = Fill(Color.Transparent, c(0xFFB0B3B8)), line = c(0xFF3E4042),
        )
    },
    StyleSpec(
        "facebook", "Social Blue", "Niebieski napis z nazwą w rogu, okrągłe szare przyciski, białe karty na jasnoszarym tle i niebieskie dymki.",
        radius = 18.dp, tail = 18.dp, corners = intArrayOf(6, 8, 8, 12, 12), grouped = true, cards = true, chrome = Chrome.FACEBOOK,
        origin = "Facebook", category = Category.APPS, homeHeader = HomeHeader.WORDMARK, listStyle = ListStyle.CARDS,
        input = InputStyle.MESSENGER, fab = FabStyle.NONE, motion = Motions.Corporate,
    ) { dark, _, _ ->
        if (!dark) Pal(
            false, c(0xFFF0F2F5), c(0xFF050505), c(0xFF5F6267), c(0xFF0866FF), Color.White,
            mine = Fill(c(0xFF0866FF), Color.White), theirs = Fill(Color.White, c(0xFF050505)),
            card = Fill(Color.White, c(0xFF050505)), bar = Fill(Color.White, c(0xFF050505)), chip = Fill(c(0xFFE4E6EB), c(0xFF5F6267)), line = c(0xFFCED0D4),
        ) else Pal(
            true, c(0xFF18191A), c(0xFFE4E6EB), c(0xFFB0B3B8), c(0xFF4E91FF), Color.White,
            mine = Fill(c(0xFF0866FF), Color.White), theirs = Fill(c(0xFF3A3B3C), c(0xFFE4E6EB)),
            card = Fill(c(0xFF242526), c(0xFFE4E6EB)), bar = Fill(c(0xFF242526), c(0xFFE4E6EB)), chip = Fill(c(0xFF3A3B3C), c(0xFFB0B3B8)), line = c(0xFF3E4042),
        )
    },
    StyleSpec(
        "discord", "Blurple Night", "Ciemny czat bez dymków: awatar, nazwa i godzina nad wiadomością, fioletowo-niebieski akcent i menu wysuwane od dołu.",
        mode = Mode.DARK, radius = 8.dp, tail = 8.dp, corners = intArrayOf(4, 6, 8, 12, 16), chrome = Chrome.DISCORD,
        origin = "Discord", category = Category.APPS, homeHeader = HomeHeader.COMPACT, input = InputStyle.DISCORD, flatMessages = true,
        searchPill = true, motion = Motions.Gamer,
    ) { _, _, _ ->
        Pal(
            true, c(0xFF313338), c(0xFFF2F3F5), c(0xFFB5BAC1), c(0xFF7983F5), Color.White,
            mine = Fill(Color.Transparent, c(0xFFDBDEE1)), theirs = Fill(Color.Transparent, c(0xFFDBDEE1)),
            card = null, bar = Fill(c(0xFF313338), c(0xFFF2F3F5), line = c(0xFF1E1F22)), chip = Fill(c(0xFF2B2D31), c(0xFF949BA4)), line = c(0xFF4E5058),
            error = c(0xFFF23F43),
        )
    },
    StyleSpec(
        "signal", "Clean Messaging", "Biel, jeden intensywny błękit, dymki w serii, czytelna typografia i spokojne przejścia — nic zbędnego.",
        radius = 18.dp, tail = 18.dp, corners = intArrayOf(8, 12, 16, 20, 24), grouped = true, chrome = Chrome.SIGNAL,
        origin = "Signal", category = Category.APPS, input = InputStyle.PILL, motion = Motions.Material,
    ) { dark, _, _ ->
        if (!dark) Pal(
            false, Color.White, c(0xFF1B1B1D), c(0xFF5E5E5E), c(0xFF2C6BED), Color.White,
            mine = Fill(c(0xFF2C6BED), Color.White), theirs = Fill(c(0xFFE9E9E9), c(0xFF1B1B1D)),
            card = null, bar = Fill(Color.White, c(0xFF1B1B1D)), chip = Fill(c(0xFFF0F0F0), c(0xFF5E5E5E)), line = c(0xFFD7D7D7),
        ) else Pal(
            true, c(0xFF121212), c(0xFFE9E9E9), c(0xFFA0A0A0), c(0xFF6191F3), Color.Black,
            mine = Fill(c(0xFF2C6BED), Color.White), theirs = Fill(c(0xFF3B3B3B), c(0xFFE9E9E9)),
            card = null, bar = Fill(c(0xFF121212), c(0xFFE9E9E9)), chip = Fill(c(0xFF262626), c(0xFFA0A0A0)), line = c(0xFF3A3A3A),
        )
    },
    // ======================= komputery =======================
    StyleSpec(
        "win11", "Soft Mica", "Materiał delikatnie zabarwiony tapetą, rogi 8 px, białe karty i okna wsuwające się z dołu z wygaszeniem.",
        bg = Bg.MICA, radius = 8.dp, tail = 8.dp, corners = intArrayOf(4, 4, 8, 8, 8), cards = true, chrome = Chrome.WIN11,
        origin = "Windows 11", category = Category.DESKTOP, listStyle = ListStyle.CARDS, input = InputStyle.WINDOW, fab = FabStyle.SQUARE,
        motion = Motions.Fluent,
    ) { dark, _, _ ->
        if (!dark) Pal(
            false, c(0xFFF3F3F3), c(0xFF1A1A1A), c(0xFF5C5C5C), c(0xFF005FB8), Color.White,
            mine = Fill(c(0xFFE8EBFA), c(0xFF1A1A1A)), theirs = Fill(Color.White, c(0xFF1A1A1A), line = c(0xFFE5E5E5)),
            card = Fill(Color.White.a(0.85f), c(0xFF1A1A1A), line = c(0xFFE5E5E5)), bar = Fill(Color.Transparent, c(0xFF1A1A1A)),
            chip = Fill(c(0xFFF9F9F9), c(0xFF5C5C5C), line = c(0xFFE5E5E5)), line = c(0xFF8A8A8A),
        ) else Pal(
            true, c(0xFF202020), Color.White, c(0xFFC5C5C5), c(0xFF4CC2FF), Color.Black,
            mine = Fill(c(0xFF2F2F4A), Color.White), theirs = Fill(c(0xFF2D2D2D), Color.White, line = c(0xFF3A3A3A)),
            card = Fill(c(0xFF2B2B2B).a(0.9f), Color.White, line = c(0xFF3A3A3A)), bar = Fill(Color.Transparent, Color.White),
            chip = Fill(c(0xFF2D2D2D), c(0xFFC5C5C5), line = c(0xFF3A3A3A)), line = c(0xFF9A9A9A),
        )
    },
    StyleSpec(
        "win7", "Aero Glass", "Szklany, rozmyty pasek tytułu z połyskiem nad tapetą, jasne okna i błyszczące dymki.",
        mode = Mode.LIGHT, surf = Surf.GLOSSY, chatBg = Bg.HARMONY, radius = 5.dp, tail = 5.dp, corners = intArrayOf(3, 3, 4, 5, 6),
        chrome = Chrome.WIN7, origin = "Windows 7", category = Category.DESKTOP, listStyle = ListStyle.SEPARATORS, input = InputStyle.WINDOW,
        fab = FabStyle.SQUARE, motion = Motions.Aero,
    ) { _, _, _ ->
        Pal(
            false, Color.White, Color.Black, c(0xFF545454), c(0xFF0066CC), Color.White,
            mine = Fill(c(0xFFDCEBFC), Color.Black, line = c(0xFF84ACDD), grad = listOf(c(0xFFF2F8FE), c(0xFFDCEBFC), c(0xFFC9E0FA))),
            theirs = Fill(Color.White, Color.Black, line = c(0xFFB9B9B9), grad = listOf(Color.White, c(0xFFF7F7F7), c(0xFFEDEDED))),
            card = Fill(Color.White, Color.Black, line = c(0xFFD9D9D9)),
            bar = Fill(c(0xFF6FA1D8), Color.Black), chip = Fill(c(0xFFF0F0F0), Color.Black, line = c(0xFFCFCFCF)), line = c(0xFF8E8E8E),
            shade = Color.Black.a(0.2f), bg = listOf(c(0xFF0B3D7A), c(0xFF1468B8), c(0xFF55A5E8)),
        )
    },
    StyleSpec(
        "winxp", "Luna Blue", "Niebieski pasek tytułu, beżowe okna, żółte dymki jak „baloniki”, wysuwane menu i zielone wzgórze w tle rozmowy.",
        mode = Mode.LIGHT, bg = Bg.PLAIN, chatBg = Bg.BLISS, radius = 6.dp, tail = 6.dp, corners = intArrayOf(3, 3, 4, 6, 8),
        chrome = Chrome.WINXP, tailKind = Tail.TELEGRAM, origin = "Windows XP", category = Category.DESKTOP, listStyle = ListStyle.SEPARATORS,
        input = InputStyle.WINDOW, fab = FabStyle.SQUARE, motion = Motions.Luna,
    ) { _, _, _ ->
        Pal(
            false, c(0xFFECE9D8), Color.Black, c(0xFF4B4B4B), c(0xFF0054E3), Color.White,
            mine = Fill(c(0xFFFFFFE1), Color.Black, line = Color.Black), theirs = Fill(Color.White, Color.Black, line = c(0xFF7F9DB9)),
            card = Fill(Color.White, Color.Black, line = c(0xFF7F9DB9)),
            bar = Fill(c(0xFF0058E6), Color.White, grad = listOf(c(0xFF3C8CF3), c(0xFF0058E6), c(0xFF0055E5), c(0xFF0C64F0), c(0xFF0047D1))),
            chip = Fill(c(0xFFFFFFE1), Color.Black, line = c(0xFF8F8F8F)), line = c(0xFF7F9DB9),
        )
    },
    StyleSpec(
        "retro", "Retro Grey", "Szare okna z ramkami 3D, granatowy pasek tytułu, turkusowy pulpit i przyciski, które naprawdę się wciskają.",
        mode = Mode.LIGHT, surf = Surf.BEVEL, bg = Bg.RETRO, chatBg = Bg.DESKTOP, radius = 0.dp, tail = 0.dp, corners = intArrayOf(0, 0, 0, 0, 0),
        cards = true, font = FontFamily.SansSerif, chrome = Chrome.WIN95, avatar = AvatarShape.SQUARE, spark = Spark.PIXELS,
        origin = "Windows 95", category = Category.DESKTOP, listStyle = ListStyle.CARDS, input = InputStyle.WINDOW, fab = FabStyle.SQUARE,
        motion = Motions.Retro,
    ) { _, _, _ ->
        Pal(
            false, c(0xFFC0C0C0), Color.Black, c(0xFF2E2E2E), c(0xFF000080), Color.White,
            mine = Fill(c(0xFFFFFFE1), Color.Black), theirs = Fill(Color.White, Color.Black),
            card = Fill(c(0xFFC0C0C0), Color.Black), bar = Fill(c(0xFF000080), Color.White),
            chip = Fill(c(0xFFC0C0C0), Color.Black), line = c(0xFF808080), error = c(0xFFB00000), bg = listOf(c(0xFF008080)),
        )
    },
    // ======================= inne =======================
    StyleSpec(
        "terminal", "Terminal Green", "Zielony fosfor na czerni, czcionka maszynowa, ostre ramki, odwrócenie kolorów przy dotknięciu i tekst pisany znak po znaku.",
        mode = Mode.DARK, bg = Bg.TERMINAL, radius = 0.dp, tail = 0.dp, corners = intArrayOf(0, 0, 0, 0, 0),
        dividers = true, font = FontFamily.Monospace, avatar = AvatarShape.SQUARE, chrome = Chrome.TERMINAL, spark = Spark.PIXELS,
        origin = "Terminal", category = Category.OTHER, homeHeader = HomeHeader.COMPACT, fab = FabStyle.SQUARE, motion = Motions.Terminal,
    ) { _, _, _ ->
        Pal(
            true, Color.Black, c(0xFF33FF66), c(0xFF29B34A), c(0xFF33FF66), Color.Black,
            mine = Fill(c(0xFF0B2611), c(0xFFB8FFC6), line = c(0xFF33FF66)), theirs = Fill(Color.Black, c(0xFF33FF66), line = c(0xFF1E7A33)),
            card = null, bar = Fill(c(0xFF050505), c(0xFF33FF66), line = c(0xFF1E7A33)), chip = Fill(Color.Black, c(0xFF29B34A), line = c(0xFF1E7A33)),
            line = c(0xFF1E7A33), error = c(0xFFFF5F56),
        )
    },
)

fun styleById(id: String): StyleSpec = STYLES.firstOrNull { it.id == id } ?: STYLES[0]

/** Schemat kolorów Material na podstawie palety (tło przezroczyste — pod spodem jest tło stylu). */
fun Pal.scheme(): ColorScheme {
    fun t(f: Float) = lerp(base, onBase, f)
    val start = if (dark) darkColorScheme() else lightColorScheme()
    return start.copy(
        primary = primary, onPrimary = onPrimary,
        primaryContainer = mine.solid(base), onPrimaryContainer = mine.text,
        inversePrimary = lerp(primary, if (dark) Color.Black else Color.White, 0.4f),
        secondary = lerp(primary, muted, 0.45f), onSecondary = onPrimary,
        secondaryContainer = theirs.solid(base), onSecondaryContainer = theirs.text,
        tertiary = lerp(primary, line, 0.3f), onTertiary = onPrimary,
        tertiaryContainer = t(0.12f), onTertiaryContainer = onBase,
        background = Color.Transparent, onBackground = onBase,
        surface = base, onSurface = onBase,
        surfaceVariant = t(0.08f), onSurfaceVariant = muted,
        surfaceTint = primary,
        inverseSurface = onBase, inverseOnSurface = base,
        error = error, onError = if (dark) Color(0xFF690005) else Color.White,
        errorContainer = if (dark) Color(0xFF93000A) else Color(0xFFFFDAD6), onErrorContainer = if (dark) Color(0xFFFFDAD6) else Color(0xFF410002),
        outline = line, outlineVariant = t(0.16f),
        scrim = Color.Black,
        surfaceBright = t(0.02f), surfaceDim = t(0.06f),
        surfaceContainerLowest = base, surfaceContainerLow = t(0.03f), surfaceContainer = t(0.05f),
        surfaceContainerHigh = t(0.08f), surfaceContainerHighest = t(0.11f),
    )
}

/** Kształty komponentów Material dla stylu. */
fun StyleSpec.shapes(): Shapes {
    val k = corners
    return if (cut) Shapes(CutCornerShape(k[0].dp), CutCornerShape(k[1].dp), CutCornerShape(k[2].dp), CutCornerShape(k[3].dp), CutCornerShape(k[4].dp))
    else Shapes(RoundedCornerShape(k[0].dp), RoundedCornerShape(k[1].dp), RoundedCornerShape(k[2].dp), RoundedCornerShape(k[3].dp), RoundedCornerShape(k[4].dp))
}

/** Czcionki stylu. */
fun StyleSpec.typography(): Typography {
    val t = Typography()
    if (font == null && headWeight == null && tracking == 0f) return t
    fun TextStyle.h() = copy(
        fontFamily = font ?: fontFamily, fontWeight = headWeight ?: fontWeight,
        letterSpacing = if (tracking != 0f) tracking.em else letterSpacing,
    )
    fun TextStyle.b() = copy(fontFamily = font ?: fontFamily)
    return t.copy(
        displayLarge = t.displayLarge.h(), displayMedium = t.displayMedium.h(), displaySmall = t.displaySmall.h(),
        headlineLarge = t.headlineLarge.h(), headlineMedium = t.headlineMedium.h(), headlineSmall = t.headlineSmall.h(),
        titleLarge = t.titleLarge.h(), titleMedium = t.titleMedium.b(), titleSmall = t.titleSmall.b(),
        bodyLarge = t.bodyLarge.b(), bodyMedium = t.bodyMedium.b(), bodySmall = t.bodySmall.b(),
        labelLarge = t.labelLarge.b(), labelMedium = t.labelMedium.b(), labelSmall = t.labelSmall.b(),
    )
}

/** Kształt „pigułki” (np. nagłówki dni) w stylu. */
fun StyleSpec.chipShape(): Shape = when {
    cut -> CutCornerShape(6.dp)
    corners[2] == 0 -> RoundedCornerShape(0.dp)
    else -> RoundedCornerShape(corners[2].coerceAtMost(14).dp)
}

/** Kształt pola wpisywania wiadomości. */
fun StyleSpec.fieldShape(): Shape = when {
    cut -> CutCornerShape(10.dp)
    corners[2] == 0 -> RoundedCornerShape(0.dp)
    corners[2] <= 6 -> RoundedCornerShape(corners[2].dp)
    else -> RoundedCornerShape(24.dp)
}

/** Kształt karty (np. rozmowy na liście). */
fun StyleSpec.cardShape(): Shape = when {
    cut -> CutCornerShape(topStart = 14.dp, bottomEnd = 14.dp)
    organic -> RoundedCornerShape(26.dp, 8.dp, 26.dp, 8.dp)
    else -> RoundedCornerShape(corners[3].dp)
}

private val CookieShape = GenericShape { size, _ ->
    val cx = size.width / 2f; val cy = size.height / 2f
    val r = minOf(cx, cy)
    val n = 96
    for (i in 0..n) {
        val th = (i.toFloat() / n) * 2f * PI.toFloat()
        val rr = r * (0.9f + 0.1f * cos(9f * th))
        val x = cx + rr * cos(th); val y = cy + rr * sin(th)
        if (i == 0) moveTo(x, y) else lineTo(x, y)
    }
    close()
}

private val HexShape = GenericShape { size, _ ->
    val w = size.width; val h = size.height
    moveTo(w * 0.25f, 0f); lineTo(w * 0.75f, 0f); lineTo(w, h / 2f); lineTo(w * 0.75f, h); lineTo(w * 0.25f, h); lineTo(0f, h / 2f); close()
}

fun AvatarShape.shape(): Shape = when (this) {
    AvatarShape.CIRCLE -> CircleShape
    AvatarShape.SQUIRCLE -> RoundedCornerShape(28)
    AvatarShape.SQUARE -> RoundedCornerShape(6)
    AvatarShape.HEX -> HexShape
    AvatarShape.COOKIE -> CookieShape
}

private val defaultState by lazy { StyleState(STYLES[0], STYLES[0].palette(false, 0, false)) }

val LocalStyle = staticCompositionLocalOf { defaultState }
