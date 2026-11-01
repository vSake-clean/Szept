package pl.szept.ui

import kotlinx.coroutines.launch

import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.material.icons.filled.Verified
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import pl.szept.App
import pl.szept.ui.style.pulse
import pl.szept.ui.style.scheme
import pl.szept.ui.style.shape
import pl.szept.ui.style.shapes
import pl.szept.ui.style.sparkTaps
import pl.szept.ui.style.typography
import pl.szept.crypto.B64
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

val Brand = Color(0xFF5B4BDB)
val Fire = Color(0xFFFF6D00)
val OkGreen = Color(0xFF2E9E5B)

/** Kolory akcentu do wyboru w „Wyglądzie”. */
val ACCENTS = listOf(
    Brand, Color(0xFF0B8A6F), Color(0xFF2F6FDE), Color(0xFFD14D72),
    Color(0xFFE65100), Color(0xFF7B3FB5), Color(0xFF00838F), Color(0xFFC0392B),
)
val ACCENT_NAMES = listOf("Fiolet", "Zieleń", "Błękit", "Róż", "Pomarańcz", "Śliwka", "Morski", "Czerwień")

/** Tapety czatów: gradienty (bez pobierania czegokolwiek z sieci). */
val WALLPAPERS = listOf(
    listOf(Color(0xFFE3F2FD), Color(0xFFF3E5F5)), listOf(Color(0xFFE8F5E9), Color(0xFFFFFDE7)),
    listOf(Color(0xFFFFEBEE), Color(0xFFFFF3E0)), listOf(Color(0xFF263238), Color(0xFF37474F)),
    listOf(Color(0xFF1A237E), Color(0xFF4A148C)), listOf(Color(0xFF004D40), Color(0xFF1B5E20)),
)

/** Ustawienia wyglądu (zapisywane w SharedPreferences „ui”). */
object UiPrefs {
    /** 0 = jak w systemie, 1 = jasny, 2 = ciemny, 3 = czarny (AMOLED). */
    var theme by mutableIntStateOf(0)
    var accent by mutableIntStateOf(0)
    var fontScale by mutableFloatStateOf(1f)
    /** "" = brak, "g0".."g5" = gradient, "img" = własne zdjęcie. */
    var wallpaper by mutableStateOf("")
    var wallpaperVer by mutableIntStateOf(0)
    /** Oznaczenie mojej wiadomości usuniętej u rozmówcy: 0 = czerwona kropka, 1 = napis, 2 = nic. */
    var delMark by mutableIntStateOf(0)
    /** Całkowity zakaz zrzutów ekranu w Szepcie. */
    var noShots by mutableStateOf(false)
    /** Na liście ostatnich aplikacji widać tylko logo Szepta, bez treści rozmów. */
    var hideRecents by mutableStateOf(true)
    /** Styl aplikacji — id z [pl.szept.ui.style.STYLES]. */
    var style by mutableStateOf("classic")
    /** Animacje w ogóle (główny przełącznik). */
    var anim by mutableStateOf(true)
    /** Wyłączone rodzaje animacji (klucze [pl.szept.ui.style.Anim]). */
    var animOff by mutableStateOf(pl.szept.ui.style.AnimFlags.DEFAULT_OFF)
    /** Mnożnik czasu animacji (mniej = szybciej). */
    var animSpeed by mutableFloatStateOf(1f)
    /** W trybie oszczędzania baterii bez ciągłych animacji. */
    var animBattery by mutableStateOf(true)
    /** Rodzaj przejść między ekranami ([pl.szept.ui.style.Trans]). */
    var transition by mutableIntStateOf(0)
    /** Charakter ruchu: 0 = jak w stylu, inaczej indeks w [pl.szept.ui.style.MOTION_PRESETS]. */
    var motion by mutableIntStateOf(0)
    /** Wersja, dla której pokazano kreator wyglądu (0 = jeszcze nie). */
    var wizard by mutableIntStateOf(0)
    private var loaded = false

    fun load(ctx: Context) {
        if (loaded) return
        loaded = true
        val p = ctx.getSharedPreferences("ui", Context.MODE_PRIVATE)
        theme = p.getInt("theme", 0); accent = p.getInt("accent", 0)
        fontScale = p.getFloat("font", 1f); wallpaper = p.getString("wall", "") ?: ""
        delMark = p.getInt("delmark", 0); noShots = p.getBoolean("noshots", false)
        hideRecents = p.getBoolean("hiderecents", true)
        style = p.getString("style", "classic") ?: "classic"
        anim = p.getBoolean("anim", true)
        animOff = p.getStringSet("anim_off", null)?.toSet() ?: pl.szept.ui.style.AnimFlags.DEFAULT_OFF
        animSpeed = p.getFloat("anim_speed", 1f)
        animBattery = p.getBoolean("anim_battery", true)
        transition = p.getInt("transition", 0)
        motion = p.getInt("motion", 0)
        wizard = p.getInt("wizard", 0)
    }

    fun save(ctx: Context) {
        ctx.getSharedPreferences("ui", Context.MODE_PRIVATE).edit()
            .putInt("theme", theme).putInt("accent", accent).putFloat("font", fontScale).putString("wall", wallpaper)
            .putInt("delmark", delMark).putBoolean("noshots", noShots).putBoolean("hiderecents", hideRecents)
            .putString("style", style).putBoolean("anim", anim).putStringSet("anim_off", HashSet(animOff))
            .putFloat("anim_speed", animSpeed).putBoolean("anim_battery", animBattery).putInt("transition", transition)
            .putInt("motion", motion).putInt("wizard", wizard).apply()
    }

    fun wallpaperFile(ctx: Context) = File(ctx.filesDir, "wallpaper.jpg")

    /** Ponowny odczyt (np. po przywróceniu kopii zapasowej). */
    fun reload(ctx: Context) { loaded = false; load(ctx) }
}

/**
 * Motyw aplikacji: styl (kolory, kształty, czcionki, tło), animacje i efekty.
 * [decorate] = false — bez ruchomego tła i efektów (np. ekran gry w Pythonie).
 */
@Composable
fun SzeptTheme(decorate: Boolean = true, content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    remember { UiPrefs.load(ctx) }
    val spec = pl.szept.ui.style.styleById(UiPrefs.style)
    val sysDark = isSystemInDarkTheme()
    val dark = when (spec.mode) {
        pl.szept.ui.style.Mode.LIGHT -> false
        pl.szept.ui.style.Mode.DARK -> true
        pl.szept.ui.style.Mode.FOLLOW -> when (UiPrefs.theme) { 1 -> false; 2, 3 -> true; else -> sysDark }
    }
    val black = UiPrefs.theme == 3
    val ai = UiPrefs.accent
    // Material You: kolory z tapety (Android 12+), gdy nie wybrano własnego koloru w „Wyglądzie”
    val pal = remember(spec, dark, ai, black) {
        if (spec.dynamic && ai == 0 && android.os.Build.VERSION.SDK_INT >= 31 && android.os.Build.FINGERPRINT != "robolectric") {
            val cs = if (dark) androidx.compose.material3.dynamicDarkColorScheme(ctx) else androidx.compose.material3.dynamicLightColorScheme(ctx)
            pl.szept.ui.style.palFromScheme(if (black && dark) cs.copy(surface = Color.Black, background = Color.Black) else cs, dark)
        } else spec.palette(dark, ai, black)
    }
    val motion = pl.szept.ui.style.MOTION_PRESETS.getOrNull(UiPrefs.motion)?.second ?: spec.motion
    val st = remember(spec, pal, motion) { pl.szept.ui.style.StyleState(spec, pal, motion) }
    val power = pl.szept.ui.style.rememberPowerSave()
    val sysScale = remember { pl.szept.ui.style.systemAnimScale(ctx) }
    val anim = remember(UiPrefs.anim, UiPrefs.animOff, UiPrefs.animSpeed, UiPrefs.animBattery, power, sysScale, UiPrefs.transition) {
        pl.szept.ui.style.AnimFlags.build(UiPrefs.anim, UiPrefs.animOff, UiPrefs.animSpeed, UiPrefs.animBattery, power, sysScale, UiPrefs.transition)
    }
    val target = remember(st) {
        if (spec.id == "classic") pl.szept.ui.style.classicScheme(dark, black, ai).copy(background = Color.Transparent) else pal.scheme()
    }
    val scheme = if (anim[pl.szept.ui.style.Anim.COLORS]) animateScheme(target, anim.ms(450)) else target
    val typography = remember(spec) { spec.typography() }
    val shapes = remember(spec) { spec.shapes() }
    val ticker = remember { pl.szept.ui.style.Ticker() }
    val tilt = remember { pl.szept.ui.style.TiltState() }
    // wszystkie animacje Compose (także menu i przełączniki) — wyłączone razem z animacjami w Szepcie
    androidx.compose.runtime.SideEffect { pl.szept.ui.style.MotionGate.scale = if (anim.any) 1f else 0f }
    val density = LocalDensity.current
    CompositionLocalProvider(LocalDensity provides Density(density.density, density.fontScale * UiPrefs.fontScale)) {
        MaterialTheme(colorScheme = scheme, typography = typography, shapes = shapes) {
            val press = motion.press
            val pressOn = anim[pl.szept.ui.style.Anim.PRESS]
            val indication = if (press == pl.szept.ui.style.Press.RIPPLE && pressOn) androidx.compose.foundation.LocalIndication.current
            else remember(press, pressOn, anim[pl.szept.ui.style.Anim.HOVER], pal.primary) {
                pl.szept.ui.style.StyleIndication(if (press == pl.szept.ui.style.Press.RIPPLE) pl.szept.ui.style.Press.HIGHLIGHT else press, pressOn, anim[pl.szept.ui.style.Anim.HOVER], pal.primary)
            }
            CompositionLocalProvider(
                pl.szept.ui.style.LocalStyle provides st, pl.szept.ui.style.LocalAnim provides anim,
                pl.szept.ui.style.LocalTicker provides ticker, pl.szept.ui.style.LocalTilt provides tilt,
                androidx.compose.foundation.LocalIndication provides indication,
            ) {
                pl.szept.ui.style.RunTicker(ticker, anim.factor)
                if (decorate) {
                    pl.szept.ui.style.TiltSensor(spec.tilt && anim[pl.szept.ui.style.Anim.TILT], tilt)
                    SystemBarsLook(st)
                    Box(Modifier.fillMaxSize().sparkTaps(anim[pl.szept.ui.style.Anim.SPARKS])) {
                        if (anim[pl.szept.ui.style.Anim.COLORS]) androidx.compose.animation.Crossfade(st, animationSpec = androidx.compose.animation.core.tween(anim.ms(450)), label = "styl") { s ->
                            pl.szept.ui.style.StyleBackground(Modifier.fillMaxSize(), s)
                        } else pl.szept.ui.style.StyleBackground(Modifier.fillMaxSize(), st)
                        content()
                        pl.szept.ui.style.FxLayer()
                    }
                } else content()
            }
        }
    }
}

/** Jasne albo ciemne ikony paska stanu i nawigacji — zależnie od stylu. */
@Composable
private fun SystemBarsLook(st: pl.szept.ui.style.StyleState) {
    val view = androidx.compose.ui.platform.LocalView.current
    val barLight = st.pal.bar.solid(st.pal.base).luminance() > 0.45f
    val navLight = st.pal.base.luminance() > 0.45f
    androidx.compose.runtime.SideEffect {
        val w = (view.context as? android.app.Activity)?.window ?: return@SideEffect
        val c = androidx.core.view.WindowCompat.getInsetsController(w, view)
        if (c.isAppearanceLightStatusBars != barLight) c.isAppearanceLightStatusBars = barLight
        if (c.isAppearanceLightNavigationBars != navLight) c.isAppearanceLightNavigationBars = navLight
    }
}

/** Płynna zmiana kolorów przy zmianie stylu/motywu. */
@Composable
private fun animateScheme(t: androidx.compose.material3.ColorScheme, ms: Int): androidx.compose.material3.ColorScheme {
    val spec = androidx.compose.animation.core.tween<Color>(ms)
    @Composable
    fun a(c: Color) = androidx.compose.animation.animateColorAsState(c, spec, label = "kolor").value
    return t.copy(
        primary = a(t.primary), onPrimary = a(t.onPrimary), primaryContainer = a(t.primaryContainer), onPrimaryContainer = a(t.onPrimaryContainer),
        secondary = a(t.secondary), secondaryContainer = a(t.secondaryContainer), onSecondaryContainer = a(t.onSecondaryContainer),
        onBackground = a(t.onBackground), surface = a(t.surface), onSurface = a(t.onSurface),
        surfaceVariant = a(t.surfaceVariant), onSurfaceVariant = a(t.onSurfaceVariant), outline = a(t.outline), outlineVariant = a(t.outlineVariant),
        surfaceContainer = a(t.surfaceContainer), surfaceContainerHigh = a(t.surfaceContainerHigh), surfaceContainerHighest = a(t.surfaceContainerHighest),
        surfaceContainerLow = a(t.surfaceContainerLow), error = a(t.error),
    )
}

/** Nieprzezroczyste tło bieżącego stylu (tam, gdzie coś musi zasłonić to, co pod spodem). */
@Composable
fun baseColor(): Color = pl.szept.ui.style.LocalStyle.current.pal.base

/** Tło rozmowy (tapeta z „Wyglądu”). */
@Composable
fun ChatWallpaper(modifier: Modifier) {
    val ctx = LocalContext.current
    val w = UiPrefs.wallpaper
    when {
        w.isEmpty() -> pl.szept.ui.style.LocalStyle.current.let { st ->
            st.spec.chatBg?.let { pl.szept.ui.style.StyleBackground(modifier, st, it) }
        }
        w.startsWith("g") -> {
            val colors = WALLPAPERS.getOrElse(w.drop(1).toIntOrNull() ?: 0) { WALLPAPERS[0] }
            Box(modifier.background(androidx.compose.ui.graphics.Brush.verticalGradient(colors)))
        }
        w == "img" -> {
            val f = UiPrefs.wallpaperFile(ctx)
            val bmp = rememberImage(if (f.exists()) f.absolutePath + "?v=" + UiPrefs.wallpaperVer else null, 1600)
            if (bmp != null) BitmapImage(bmp, modifier)
        }
    }
}

/** Polska odmiana: 1 głos, 2 głosy, 5 głosów. */
fun plural(n: Int, one: String, few: String, many: String): String {
    val m10 = n % 10; val m100 = n % 100
    val w = when {
        n == 1 -> one
        m10 in 2..4 && m100 !in 12..14 -> few
        else -> many
    }
    return "$n $w"
}

/** Otwiera lokalizację w aplikacji map (albo w OpenStreetMap w przeglądarce). */
fun openMap(ctx: Context, lat: Double, lon: Double, label: String) {
    val l = "%.6f".format(java.util.Locale.ROOT, lat); val o = "%.6f".format(java.util.Locale.ROOT, lon)
    try {
        ctx.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("geo:$l,$o?q=$l,$o(" + android.net.Uri.encode(label) + ")")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: Exception) {
        try {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://www.openstreetmap.org/?mlat=$l&mlon=$o#map=16/$l/$o")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) { toast(ctx, "Brak aplikacji map") }
    }
}

/** Ekrany aplikacji. */
sealed class Screen {
    data object Home : Screen()
    /** [focus] = wiadomość, do której przewinąć (np. z wyszukiwarki). */
    data class Chat(val id: String, val focus: String? = null) : Screen()
    data class GroupInfo(val gid: String) : Screen()
    data class ContactInfo(val pk: String) : Screen()
    data object NewChat : Screen()
    data object NewGroup : Screen()
    data class AddMembers(val gid: String) : Screen()
    data class AddContact(val prefill: String? = null) : Screen()
    data object MyCode : Screen()
    data object Settings : Screen()
    data class Viewer(val msgId: String) : Screen()
    data object MyProfile : Screen()
    data object People : Screen()
    data object AccessCode : Screen()
    data object Security : Screen()
    data object Archive : Screen()
    data object Search : Screen()
    data class Stories(val pk: String) : Screen()
    data object Broadcast : Screen()
    data class Forward(val msgId: String) : Screen()
    data class Media(val chatId: String) : Screen()
    data object Appearance : Screen()
    data object OwnRelay : Screen()
    data object Backup : Screen()
    data object Lan : Screen()
    data object Devices : Screen()
    data object CallSettings : Screen()
    data object Admin : Screen()
    data object WhatsNew : Screen()
    data object Connection : Screen()
    data object Help : Screen()
    data object Games : Screen()
    data object Styles : Screen()
    data object Animations : Screen()
    data object Wizard : Screen()
}

class Nav {
    val stack = mutableStateListOf<Screen>(Screen.Home)
    val current get() = stack.last()
    fun go(s: Screen) { stack.add(s) }
    fun back(): Boolean = if (stack.size > 1) { stack.removeAt(stack.lastIndex); true } else false
    fun replace(s: Screen) { stack.removeAt(stack.lastIndex); stack.add(s) }
    fun home() { while (stack.size > 1) stack.removeAt(stack.lastIndex) }
}

/** Zapytanie do bazy odświeżane przy każdej zmianie danych. */
@Composable
fun <T> rememberDb(vararg keys: Any?, query: () -> T): State<T?> {
    val changes by App.core.db.changes.collectAsState()
    return produceState<T?>(null, changes, *keys) { value = withContext(Dispatchers.IO) { query() } }
}

fun avatarColor(pk: String): Color {
    val palette = listOf(0xFF5B4BDB, 0xFF0B8A6F, 0xFFD14D72, 0xFF2F6FDE, 0xFFB86E00, 0xFF7B3FB5, 0xFF00838F, 0xFFC0392B)
    return Color(palette[(pk.hashCode() and 0x7fffffff) % palette.size])
}

fun initials(name: String): String {
    val parts = name.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    return when {
        parts.isEmpty() -> "?"
        parts.size == 1 -> parts[0].take(2).uppercase()
        else -> (parts[0].take(1) + parts[1].take(1)).uppercase()
    }
}

/** Rozpoznaje, czy wartość „icon” to obrazek base64 (długi), czy emoji/tekst (krótki). */
fun isImageData(s: String?) = s != null && s.length > 24

@Composable
fun Avatar(name: String, key: String, size: Dp = 46.dp, group: Boolean = false, image: String? = null) {
    val bmp = remember(image) { if (isImageData(image)) Images.thumb(image!!) else null }
    Box(
        Modifier.size(size).clip(pl.szept.ui.style.LocalStyle.current.spec.avatar.shape()).background(avatarColor(key)),
        contentAlignment = Alignment.Center,
    ) {
        when {
            bmp != null -> BitmapImage(bmp, Modifier.fillMaxSize())
            !image.isNullOrBlank() -> Text(image, fontSize = (size.value * 0.5f).sp)   // emoji
            else -> Text(if (group) "👥" else initials(name), color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = (size.value * 0.38f).sp)
        }
    }
}

/** Awatar kontaktu (ze zdjęciem profilowym, jeśli jest). */
@Composable
fun ContactAvatar(c: pl.szept.data.Contact?, size: Dp = 46.dp) =
    Avatar(c?.display ?: "", c?.pk ?: "", size, false, c?.avatar?.ifBlank { null })

private val timeFmt = SimpleDateFormat("HH:mm", Locale("pl"))
private val dayFmt = SimpleDateFormat("d MMM", Locale("pl"))

fun formatTime(ts: Long): String = timeFmt.format(Date(ts))

fun formatListTime(ts: Long): String {
    val now = Calendar.getInstance()
    val then = Calendar.getInstance().apply { timeInMillis = ts }
    return if (now.get(Calendar.YEAR) == then.get(Calendar.YEAR) && now.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR))
        timeFmt.format(Date(ts)) else dayFmt.format(Date(ts))
}

fun formatDuration(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(s / 60, s % 60)
}

fun formatTtl(sec: Int): String = when {
    sec < 60 -> "$sec s"
    sec < 3600 -> "${sec / 60} min"
    sec < 86400 -> "${sec / 3600} h"
    else -> "${sec / 86400} d"
}

fun formatSize(b: Long): String = when {
    b < 1024 -> "$b B"
    b < 1024 * 1024 -> "${b / 1024} KB"
    else -> "%.1f MB".format(b / 1024.0 / 1024.0)
}

fun toast(ctx: Context, text: String) {
    if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) Toast.makeText(ctx, text, Toast.LENGTH_LONG).show()
    else android.os.Handler(android.os.Looper.getMainLooper()).post { Toast.makeText(ctx, text, Toast.LENGTH_LONG).show() }
}

fun copyText(ctx: Context, text: String) {
    ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Szept", text))
    toast(ctx, "Skopiowano")
}

fun shareText(ctx: Context, text: String) {
    ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"; putExtra(Intent.EXTRA_TEXT, text)
    }, "Udostępnij"))
}

fun fileUri(ctx: Context, f: File) = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)

/** Otwiera instalator Androida dla pliku APK (użytkownik sam potwierdza instalację). */
fun installApk(ctx: Context, path: String) {
    try {
        ctx.startActivity(Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(fileUri(ctx, File(path)), "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    } catch (e: Exception) { toast(ctx, "Nie można otworzyć instalatora") }
}

/** Porównuje wersje typu „1.5” / „1.10”. Zwraca true, gdy a > b. */
fun versionNewer(a: String, b: String): Boolean {
    fun parts(v: String) = v.split('.').map { it.toIntOrNull() ?: 0 }
    val pa = parts(a); val pb = parts(b)
    for (i in 0 until maxOf(pa.size, pb.size)) {
        val x = pa.getOrElse(i) { 0 }; val y = pb.getOrElse(i) { 0 }
        if (x != y) return x > y
    }
    return false
}

fun openFile(ctx: Context, path: String, mime: String) {
    try {
        ctx.startActivity(Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(fileUri(ctx, File(path)), mime.ifEmpty { "*/*" })
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    } catch (e: Exception) { toast(ctx, "Brak aplikacji do otwarcia tego pliku") }
}

fun shareFile(ctx: Context, path: String, mime: String) {
    try {
        ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = mime.ifEmpty { "*/*" }
            putExtra(Intent.EXTRA_STREAM, fileUri(ctx, File(path)))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }, "Udostępnij / zapisz"))
    } catch (e: Exception) { toast(ctx, "Nie można udostępnić") }
}

/** Udostępnia zainstalowaną aplikację (plik APK) — np. przez Quick Share, Bluetooth, komunikator. */
fun shareApp(ctx: Context) = pl.szept.App.work.launch {
    try {
        // kopiowanie ~20 MB poza wątkiem interfejsu
        val out = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            ctx.cacheDir.listFiles()?.filter { it.name.startsWith("Szept") && it.name.endsWith(".apk") }?.forEach { it.delete() }
            File(ctx.cacheDir, "Szept-${pl.szept.BuildConfig.VERSION_NAME}.apk").also { File(ctx.applicationInfo.sourceDir).copyTo(it, overwrite = true) }
        }
        ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "application/vnd.android.package-archive"
            putExtra(Intent.EXTRA_STREAM, fileUri(ctx, out))
            putExtra(Intent.EXTRA_TEXT, "Zainstaluj Szept ${pl.szept.BuildConfig.VERSION_NAME} — prywatny komunikator bez serwera. Potem dodaj mnie: " + pl.szept.App.core.myCode())
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }, "Wyślij aplikację Szept"))
    } catch (e: Exception) { toast(ctx, "Nie udało się udostępnić aplikacji: ${e.message}") }
}

/** Pamięć podręczna miniatur i obrazów. */
object Images {
    private val cache = object : LruCache<String, ImageBitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
    }

    fun load(path: String, maxSide: Int): ImageBitmap? {
        val key = "$path@$maxSide"
        cache.get(key)?.let { return it }
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, o)
        var sample = 1
        while (maxOf(o.outWidth, o.outHeight) / (sample * 2) >= maxSide) sample *= 2
        val bmp: Bitmap = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        return bmp.asImageBitmap().also { cache.put(key, it) }
    }

    fun thumb(b64: String): ImageBitmap? {
        cache.get(b64)?.let { return it }
        return try {
            val bytes = B64.dec(b64)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()?.also { cache.put(b64, it) }
        } catch (e: Exception) { null }
    }
}

@Composable
fun rememberImage(path: String?, maxSide: Int): ImageBitmap? {
    val v by produceState<ImageBitmap?>(null, path, maxSide) {
        value = if (path == null) null else withContext(Dispatchers.IO) { try { Images.load(path, maxSide) } catch (e: Exception) { null } }
    }
    return v
}

@Composable
fun BitmapImage(bmp: ImageBitmap, modifier: Modifier, crop: Boolean = true) {
    Image(bmp, null, modifier, contentScale = if (crop) ContentScale.Crop else ContentScale.Fit)
}


/** Niebieski znaczek: konto zweryfikowane przez Szept (podpis administratora — nie da się go podrobić). */
@androidx.compose.runtime.Composable
fun VerifiedBadge(size: androidx.compose.ui.unit.Dp = 16.dp) {
    androidx.compose.material3.Icon(
        androidx.compose.material.icons.Icons.Filled.Verified, "Zweryfikowane",
        androidx.compose.ui.Modifier.padding(start = 4.dp).size(size), tint = Color(0xFF1E88E5),
    )
}

/** Kolor i opis statusu rozmówcy (aktywny / w tle / zaraz wracam / nieaktywny). */
fun presenceColor(s: pl.szept.core.Core.Seen): Color = when (s) {
    pl.szept.core.Core.Seen.ACTIVE -> Color(0xFF2E7D32)
    pl.szept.core.Core.Seen.IDLE -> Color(0xFFF9A825)
    pl.szept.core.Core.Seen.AWAY -> Color(0xFFEF6C00)
    pl.szept.core.Core.Seen.INACTIVE -> Color(0xFF9E9E9E)
}

fun presenceText(s: pl.szept.core.Core.Seen, at: Long, now: Long = System.currentTimeMillis()): String = when (s) {
    pl.szept.core.Core.Seen.ACTIVE -> "aktywny(-a) teraz"
    pl.szept.core.Core.Seen.IDLE -> "w tle — aplikacja działa"
    pl.szept.core.Core.Seen.AWAY -> "zaraz wraca"
    pl.szept.core.Core.Seen.INACTIVE -> {
        val min = ((now - at) / 60_000).coerceAtLeast(1)
        "nieaktywny(-a) · " + when {
            min < 60 -> "$min min temu"
            min < 24 * 60 -> "${min / 60} godz. temu"
            else -> "${min / 1440} dni temu"
        }
    }
}

/** Aktualny status rozmówcy (odświeża się co pół minuty). */
@androidx.compose.runtime.Composable
fun rememberSeen(pk: String?): Pair<pl.szept.core.Core.Seen, Long>? {
    val map by pl.szept.App.core.presence.collectAsState()
    var tick by remember { mutableIntStateOf(0) }
    androidx.compose.runtime.LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(30_000); tick++ } }
    return remember(map, tick, pk) { pl.szept.App.core.seenOf(pk) }
}

/** Kropka statusu (np. na awatarze). */
@androidx.compose.runtime.Composable
fun PresenceDot(s: pl.szept.core.Core.Seen, modifier: Modifier = Modifier, size: Dp = 14.dp) {
    androidx.compose.foundation.layout.Box(
        modifier.pulse(presenceColor(s), s == pl.szept.core.Core.Seen.ACTIVE)
            .size(size).clip(androidx.compose.foundation.shape.CircleShape)
            .background(MaterialTheme.colorScheme.surface).padding(2.dp).clip(androidx.compose.foundation.shape.CircleShape)
            .background(presenceColor(s))
            .semantics { contentDescription = "Status: " + presenceText(s, 0L).substringBefore(" ·") },
    )
}
