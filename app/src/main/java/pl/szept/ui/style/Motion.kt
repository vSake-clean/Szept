package pl.szept.ui.style

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.withInfiniteAnimationFrameNanos
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.currentValueOf
import androidx.compose.material3.LocalContentColor
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sin

// ======================= ustawienia animacji =======================

/** Grupy w „Animacjach zaawansowanych”. */
enum class AnimGroup(val title: String) {
    MOTION("Ruch i przejścia"), TOUCH("Dotyk i przyciski"), APPEAR("Pojawianie się"), EFFECTS("Efekty"), CHAT("Rozmowy"),
}

/** Animacje, które można osobno włączać i wyłączać. [continuous] — trwają cały czas (wyłączane przy oszczędzaniu baterii). */
enum class Anim(val key: String, val title: String, val desc: String, val group: AnimGroup, val continuous: Boolean = false, val defaultOn: Boolean = true) {
    SCREENS("screens", "Przejścia między ekranami", "Ekrany wsuwają się, przenikają albo powiększają — tak jak w wybranym stylu", AnimGroup.MOTION),
    MENUS("menus", "Otwieranie i zamykanie menu", "Menu wyrasta z przycisku, wysuwa się albo pojawia — zależnie od stylu", AnimGroup.MOTION),
    PANELS("panels", "Okna i panele", "Okna dialogowe pojawiają się z powiększeniem, od dołu albo z odbiciem", AnimGroup.MOTION),
    COLORS("colors", "Zmiana stylu i kolorów", "Kolory i tło przechodzą płynnie przy zmianie stylu i motywu", AnimGroup.MOTION),
    PRESS("press", "Efekty kliknięcia", "Ugięcie, podświetlenie, fala albo „wciśnięcie” w ramkę — jak w oryginale stylu", AnimGroup.TOUCH),
    HOVER("hover", "Efekty najechania", "Podświetlenie pod kursorem myszy albo rysikiem", AnimGroup.TOUCH),
    BUTTONS("buttons", "Animowane przyciski", "Przycisk wysyłania, pływający przycisk, liczniki i ptaszki ✓✓", AnimGroup.TOUCH),
    APPEAR("appear", "Płynne pojawianie się elementów", "Elementy list wsuwają się po kolei i płynnie zmieniają miejsce", AnimGroup.APPEAR),
    MSG_IN("msgin", "Wjazd nowych wiadomości", "Sprężyna, wyskok albo pisanie znak po znaku — zależnie od stylu", AnimGroup.APPEAR),
    BLUR("blur", "Rozmycie i przezroczystość", "Szkło rozmywa i załamuje to, co pod nim; wyłączone — matowe i szybsze", AnimGroup.EFFECTS),
    BG("bg", "Animowane tło", "Gradient przesuwający się przy wysłaniu wiadomości, płynące chmury", AnimGroup.EFFECTS, continuous = true),
    TILT("tilt", "Odblaski i paralaksa", "Szkło łapie światło, gdy przechylasz telefon (żyroskop)", AnimGroup.EFFECTS, continuous = true),
    SHIMMER("shimmer", "Połysk wczytywania", "Migoczące zaślepki, zanim pojawią się rozmowy i zdjęcia", AnimGroup.EFFECTS, continuous = true),
    TITLE("title", "Animowany tytuł", "Błysk, kursor terminala albo kropki — zależnie od stylu", AnimGroup.EFFECTS, continuous = true),
    SPARKS("sparks", "Iskry przy dotknięciu", "Pod palcem rozbłyskują iskierki", AnimGroup.EFFECTS, defaultOn = false),
    CHAT_FX("chatfx", "Efekty w rozmowie", "🎉 konfetti, ❤️ serca, 🎈 balony, ❄️ śnieg, 🎆 fajerwerki, ⭐ gwiazdy po takiej wiadomości", AnimGroup.CHAT),
    GAME_FX("gamefx", "Konfetti w grach", "Przy wygranej i nowym rekordzie", AnimGroup.CHAT),
    TYPING("typing", "Animowane „pisze…”", "Skaczące kropki, gdy rozmówca pisze", AnimGroup.CHAT, continuous = true),
    PRESENCE("presence", "Pulsujący status online", "Kropka przy aktywnych osobach pulsuje", AnimGroup.CHAT, continuous = true),
    STORIES("stories", "Obracająca się obwódka relacji", "Kolorowy pierścień wokół nowych relacji", AnimGroup.CHAT, continuous = true),
    REACTIONS("reactions", "Animowane reakcje", "Reakcje wyskakują, a nowa emotka wylatuje w górę", AnimGroup.CHAT),
}

/**
 * Globalna skala czasu wszystkich animacji Compose (także wbudowanych: menu, przełączniki, fale).
 * 0 = animacje wyłączone (wszystko natychmiast). Ustawiana z ustawień i ustawień systemu.
 */
object MotionGate : androidx.compose.ui.MotionDurationScale {
    @Volatile var scale = 1f
    override val scaleFactor: Float get() = scale
}

/** Skuteczne ustawienia animacji (po uwzględnieniu ustawień systemu i oszczędzania baterii). */
@Stable
class AnimFlags(private val on: Set<Anim>, val factor: Float, val transition: Int) {
    operator fun get(a: Anim) = a in on
    val any get() = on.isNotEmpty()
    /** Czas trwania w ms po uwzględnieniu szybkości. */
    fun ms(base: Int) = (base * factor).toInt().coerceAtLeast(1)

    companion object {
        val NONE = AnimFlags(emptySet(), 1f, Trans.FADE)
        val ALL = AnimFlags(Anim.entries.toSet(), 1f, Trans.AUTO)

        /**
         * [followSystem] — gdy w telefonie animacje są wyłączone (opcje programisty, „Usuń animacje”, oszczędzanie energii),
         * Szept też ich nie pokazuje. Domyślnie wyłączone: o animacjach w Szepcie decyduje przełącznik w aplikacji.
         */
        fun build(
            enabled: Boolean, off: Set<String>, speed: Float, battery: Boolean, powerSave: Boolean, systemScale: Float, transition: Int,
            followSystem: Boolean = false,
        ): AnimFlags {
            if (!enabled || (followSystem && systemScale == 0f)) return NONE
            val set = Anim.entries.filter { it.key !in off && (!it.continuous || !(battery && powerSave)) }.toSet()
            return AnimFlags(set, speed.coerceIn(0.3f, 3f), transition)
        }

        /** Domyślnie wyłączone animacje (zapisywane jako „wyłączone”). */
        val DEFAULT_OFF: Set<String> = Anim.entries.filter { !it.defaultOn }.map { it.key }.toSet()
    }
}

val LocalAnim = staticCompositionLocalOf { AnimFlags.ALL }

/**
 * Systemowa skala animacji: 0 = telefon wyłączył animacje — w ustawieniach („Usuń animacje”, skala animatora
 * w opcjach programisty) albo w trybie oszczędzania energii.
 */
fun systemAnimScale(ctx: Context): Float = try {
    val set = Settings.Global.getFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
    if (set == 0f || !android.animation.ValueAnimator.areAnimatorsEnabled()) 0f else set
} catch (_: Throwable) { 1f }

/** Tryb oszczędzania energii (odświeża się na bieżąco). */
@Composable
fun rememberPowerSave(): Boolean {
    val ctx = LocalContext.current
    val pm = remember { ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager }
    var save by remember { mutableStateOf(pm?.isPowerSaveMode == true) }
    DisposableEffect(pm) {
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) { save = pm?.isPowerSaveMode == true }
        }
        try { ctx.registerReceiver(r, IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)) } catch (_: Throwable) { }
        onDispose { try { ctx.unregisterReceiver(r) } catch (_: Throwable) { } }
    }
    return save
}

// ======================= zegar animacji =======================

/**
 * Wspólny zegar ciągłych animacji (sekundy). Działa tylko wtedy, gdy ktoś go używa — tło, pulsowanie, tytuł…
 * Odczyt wartości w fazie rysowania nie przebudowuje ekranu, tylko go przerysowuje.
 */
@Stable
class Ticker {
    val time: MutableFloatState = mutableFloatStateOf(0f)
    var users by mutableIntStateOf(0)
    var speed = 1f
}

val LocalTicker = staticCompositionLocalOf { Ticker() }

/** Prowadzi zegar — wywoływane raz, w korzeniu motywu. */
@Composable
fun RunTicker(t: Ticker, factor: Float) {
    t.speed = 1f / factor
    val life by androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    val active = t.users > 0 && life.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)
    LaunchedEffect(active) {
        if (!active) return@LaunchedEffect
        var last = -1L
        // „nieskończona” animacja — w testach i przy wyłączonych animacjach systemu się nie kręci
        while (true) {
            withInfiniteAnimationFrameNanos { now ->
                if (last >= 0) {
                    val dt = ((now - last) / 1e9f).coerceIn(0f, 0.1f)
                    t.time.floatValue = (t.time.floatValue + dt * t.speed) % 36_000f
                }
                last = now
            }
        }
    }
}

/** Czas do ciągłej animacji (gdy [enabled]); rysuj, czytając `.floatValue` w fazie rysowania. */
@Composable
fun rememberTime(enabled: Boolean): MutableFloatState {
    val t = LocalTicker.current
    DisposableEffect(enabled, t) {
        if (enabled) t.users++
        onDispose { if (enabled) t.users-- }
    }
    return t.time
}

// ======================= żyroskop (odblaski i paralaksa) =======================

@Stable
class TiltState {
    var x by mutableFloatStateOf(0f)
    var y by mutableFloatStateOf(0f)
}

val LocalTilt = staticCompositionLocalOf { TiltState() }

/** Czujnik grawitacji → przechylenie telefonu (−1…1), wygładzone. Działa tylko, gdy aplikacja jest na ekranie. */
@Composable
fun TiltSensor(enabled: Boolean, state: TiltState) {
    val ctx = LocalContext.current
    androidx.lifecycle.compose.LifecycleResumeEffect(enabled) {
        val sm = if (enabled) ctx.getSystemService(Context.SENSOR_SERVICE) as? SensorManager else null
        val sensor = sm?.getDefaultSensor(Sensor.TYPE_GRAVITY) ?: sm?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        var fx = 0f; var fy = 0f
        val l = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                val tx = (-e.values[0] / 5f).coerceIn(-1f, 1f)
                val ty = ((e.values[1] - 6.5f) / 4f).coerceIn(-1f, 1f)
                fx += (tx - fx) * 0.18f; fy += (ty - fy) * 0.18f
                if (abs(fx - state.x) > 0.008f || abs(fy - state.y) > 0.008f) { state.x = fx; state.y = fy }
            }
            override fun onAccuracyChanged(s: Sensor?, a: Int) {}
        }
        if (sm != null && sensor != null) sm.registerListener(l, sensor, SensorManager.SENSOR_DELAY_UI)
        onPauseOrDispose { sm?.unregisterListener(l) }
    }
}

// ======================= sprężyste dotknięcia =======================

/** Ugięcie przy wciśnięciu: postęp 0…1 (z odbiciem przy puszczeniu). */
private suspend fun Animatable<Float, *>.press() = animateTo(1f, spring(stiffness = 900f))
private suspend fun Animatable<Float, *>.release() {
    if (value < 0.5f) animateTo(1f, tween(70))
    animateTo(0f, spring(dampingRatio = 0.38f, stiffness = 520f))
}

/**
 * Wskaźnik dotknięcia w stylu oryginału:
 *  SCALE — ugięcie i sprężyste odbicie (iOS, nakładki), HIGHLIGHT — podświetlenie (Windows, Fluent),
 *  SUNKEN — przycisk „wciska się” w ramkę 3D (Windows 95/XP), INVERT — odwrócenie kolorów (terminal).
 * [animate] = false — informacja zwrotna bez animacji, [hover] — podświetlenie pod kursorem/rysikiem.
 */
class StyleIndication(val press: Press, val animate: Boolean, val hover: Boolean, val accent: Color) : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode = PressNode(interactionSource, press, animate, hover, accent)
    override fun equals(other: Any?) = other is StyleIndication && other.press == press && other.animate == animate && other.hover == hover && other.accent == accent
    override fun hashCode() = press.hashCode() * 31 + animate.hashCode() * 7 + hover.hashCode() + accent.hashCode()
}

/** Zgodność: dawny wskaźnik z ugięciem. */
fun BounceIndication(): IndicationNodeFactory = StyleIndication(Press.SCALE, true, true, Color.Unspecified)

private class PressNode(
    private val src: InteractionSource, private val press: Press, private val animate: Boolean,
    private val hoverOn: Boolean, private val accent: Color,
) : Modifier.Node(), DrawModifierNode, CompositionLocalConsumerModifierNode {
    private val progress = Animatable(0f)
    private val light = Animatable(0f)
    private val hovered = Animatable(0f)

    override fun onAttach() {
        coroutineScope.launch {
            src.interactions.collect { i ->
                when (i) {
                    is PressInteraction.Press -> {
                        if (animate) { launch { progress.press() }; launch { light.animateTo(1f, tween(90)) } }
                        else { launch { progress.snapTo(1f) }; launch { light.snapTo(1f) } }
                    }
                    is PressInteraction.Release, is PressInteraction.Cancel -> {
                        if (animate) {
                            launch { progress.release() }
                            launch { if (light.value < 0.6f) light.animateTo(1f, tween(60)); light.animateTo(0f, tween(260)) }
                        } else { launch { progress.snapTo(0f) }; launch { light.snapTo(0f) } }
                    }
                    is androidx.compose.foundation.interaction.HoverInteraction.Enter -> if (hoverOn) launch { hovered.animateTo(1f, tween(if (animate) 90 else 0)) }
                    is androidx.compose.foundation.interaction.HoverInteraction.Exit -> launch { hovered.animateTo(0f, tween(if (animate) 160 else 0)) }
                }
            }
        }
        coroutineScope.launch {
            androidx.compose.runtime.snapshotFlow { progress.value + light.value * 7f + hovered.value * 13f }.collect { invalidateDraw() }
        }
    }

    override fun ContentDrawScope.draw() {
        val p = progress.value
        val l = light.value
        val h = hovered.value
        if ((p == 0f && l == 0f && h == 0f) || size.maxDimension <= 0f) { drawContent(); return }
        val c = currentValueOf(LocalContentColor)
        when (press) {
            Press.SCALE -> {
                val s = 1f - p * min(0.08f, 10.dp.toPx() / size.maxDimension)
                scale(s, s) {
                    this@draw.drawContent()
                    val a = 0.11f * l + 0.06f * h
                    if (a > 0f) drawRect(c.copy(alpha = a))
                }
            }
            Press.SUNKEN -> {
                // przycisk wciśnięty: treść przesunięta o piksel, ramka odwrócona (ciemna u góry i z lewej)
                val o = if (l > 0.5f) 1.dp.toPx() else 0f
                translate(o, o) { this@draw.drawContent() }
                if (l > 0.5f) {
                    val t = 1.2.dp.toPx(); val w = size.width; val hh = size.height
                    drawRect(Color(0xFF404040), androidx.compose.ui.geometry.Offset.Zero, androidx.compose.ui.geometry.Size(w, t))
                    drawRect(Color(0xFF404040), androidx.compose.ui.geometry.Offset.Zero, androidx.compose.ui.geometry.Size(t, hh))
                    drawRect(Color.White, androidx.compose.ui.geometry.Offset(0f, hh - t), androidx.compose.ui.geometry.Size(w, t))
                    drawRect(Color.White, androidx.compose.ui.geometry.Offset(w - t, 0f), androidx.compose.ui.geometry.Size(t, hh))
                }
                if (h > 0f) drawRect(Color(0xFF000080).copy(alpha = 0.08f * h))
            }
            Press.INVERT -> {
                drawContent()
                // zielony tekst na czerni → czarny tekst na zielonym (jak zaznaczenie w terminalu)
                if (l > 0.5f) drawRect(if (accent.isSpecified) accent else c, blendMode = BlendMode.Difference)
                if (h > 0f) drawRect((if (accent.isSpecified) accent else c).copy(alpha = 0.12f * h))
            }
            else -> {
                drawContent()
                val a = 0.12f * l + 0.07f * h
                if (a > 0f) drawRect(c.copy(alpha = a))
            }
        }
    }
}

/** Ugięcie całego elementu (razem z tłem i cieniem) dla podanego źródła dotknięć. */
@Composable
fun Modifier.pressScale(src: InteractionSource, enabled: Boolean = true): Modifier {
    if (!enabled) return this
    val p = remember { Animatable(0f) }
    LaunchedEffect(src) {
        src.interactions.collect { i ->
            when (i) {
                is PressInteraction.Press -> launch { p.press() }
                is PressInteraction.Release, is PressInteraction.Cancel -> launch { p.release() }
            }
        }
    }
    return graphicsLayer {
        val v = p.value
        if (v != 0f && size.maxDimension > 0f) {
            val s = 1f - v * min(0.06f, 10.dp.toPx() / size.maxDimension)
            scaleX = s; scaleY = s
        }
    }
}

// ======================= wejścia i podskoki =======================

/**
 * Pojawienie się nowej wiadomości — jak w oryginale stylu:
 * SPRING (sprężyna z boku), SLIDE_UP (wsunięcie od pola wiadomości), POP (wyskok z odbiciem),
 * FADE (przenikanie), TYPEWRITER (tekst „pisany” od lewej z kursorem), NONE.
 */
@Composable
fun Modifier.appear(mine: Boolean, play: Boolean): Modifier {
    if (!play) return this
    val st = LocalStyle.current
    val kind = st.motion.appear
    if (kind == Appear.NONE) return this
    val p = remember { Animatable(0f) }
    val f = LocalAnim.current.factor
    val m = st.motion
    LaunchedEffect(Unit) {
        when (kind) {
            Appear.SPRING -> p.animateTo(1f, spring(dampingRatio = 0.62f, stiffness = 380f / f))
            Appear.POP -> p.animateTo(1f, spring(dampingRatio = 0.48f, stiffness = 420f / f))
            Appear.TYPEWRITER -> p.animateTo(1f, tween((650 * f).toInt(), easing = androidx.compose.animation.core.LinearEasing))
            Appear.SLIDE_UP -> p.animateTo(1f, tween((m.duration.coerceAtLeast(200) * f).toInt(), easing = m.easing))
            else -> p.animateTo(1f, tween((220 * f).toInt()))
        }
    }
    val cursor = st.pal.primary
    return when (kind) {
        Appear.TYPEWRITER -> drawWithContent {
            val v = p.value
            if (v >= 1f) { drawContent(); return@drawWithContent }
            val x = size.width * v
            clipRect(right = x) { this@drawWithContent.drawContent() }
            drawRect(cursor, androidx.compose.ui.geometry.Offset(x, size.height * 0.25f), androidx.compose.ui.geometry.Size(8.dp.toPx(), size.height * 0.5f))
        }
        else -> graphicsLayer {
            val v = p.value
            when (kind) {
                Appear.SPRING -> {
                    val dx = 56.dp.toPx() * (1f - v)
                    translationX = if (mine) dx else -dx
                    translationY = 18.dp.toPx() * (1f - v)
                    val sc = 0.82f + 0.18f * v
                    scaleX = sc; scaleY = sc
                    transformOrigin = TransformOrigin(if (mine) 1f else 0f, 1f)
                }
                Appear.POP -> {
                    val sc = 0.55f + 0.45f * v
                    scaleX = sc; scaleY = sc
                    transformOrigin = TransformOrigin(if (mine) 1f else 0f, 1f)
                }
                Appear.SLIDE_UP -> {
                    translationY = 30.dp.toPx() * (1f - v)
                    if (mine) translationX = 12.dp.toPx() * (1f - v)
                }
                else -> {}
            }
            alpha = v.coerceIn(0f, 1f)
        }
    }
}

/** Podskok przy zmianie [key] (np. licznik nieprzeczytanych). */
@Composable
fun Modifier.pop(key: Any?, enabled: Boolean = LocalAnim.current[Anim.BUTTONS], appear: Boolean = true): Modifier {
    if (!enabled) return this
    val s = remember { Animatable(1f) }
    var first by remember { mutableStateOf(true) }
    LaunchedEffect(key) {
        if (first) { first = false; if (!appear) return@LaunchedEffect; s.snapTo(0.4f) }
        else s.snapTo(1.35f)
        s.animateTo(1f, spring(dampingRatio = 0.35f, stiffness = Spring.StiffnessMediumLow))
    }
    return graphicsLayer { scaleX = s.value; scaleY = s.value }
}

/** Kaskada: elementy wsuwają się po kolei przy otwieraniu ekranu (tylko raz). */
@Stable
class Cascade {
    private val start = System.currentTimeMillis()
    private var n = 0
    private val seen = HashSet<Any>()
    /** Opóźnienie dla elementu albo -1, gdy nie animować (ekran otwarty dawno albo element już był). */
    fun delayFor(key: Any): Int {
        if (!seen.add(key)) return -1
        val i = n++
        if (System.currentTimeMillis() - start > 700 || i > 14) return -1
        return i * 38
    }
}

@Composable
fun rememberCascade(): Cascade = remember { Cascade() }

/** Kaskada bieżącego ekranu (każdy ekran dostaje własną). */
val LocalCascade = staticCompositionLocalOf<Cascade?> { null }

/** Element wsuwa się po kolei z innymi elementami tego ekranu. */
@Composable
fun Modifier.cascadeIn(key: Any): Modifier = cascade(LocalCascade.current, key)

@Composable
fun Modifier.cascade(c: Cascade?, key: Any): Modifier {
    if (c == null || !LocalAnim.current[Anim.APPEAR]) return this
    val delay = remember(key) { c.delayFor(key) }
    if (delay < 0) return this
    val p = remember { Animatable(0f) }
    val f = LocalAnim.current.factor
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay((delay * f).toLong())
        p.animateTo(1f, spring(dampingRatio = 0.8f, stiffness = 300f / f))
    }
    return graphicsLayer {
        val v = p.value
        alpha = v.coerceIn(0f, 1f)
        translationY = 26.dp.toPx() * (1f - v)
    }
}

// ======================= ciągłe efekty =======================

/** Połysk wczytywania (przesuwający się blask). */
@Composable
fun Modifier.shimmer(base: Color): Modifier {
    val on = LocalAnim.current[Anim.SHIMMER]
    val t = rememberTime(on)
    return drawBehind {
        drawRect(base)
        if (on) {
            val w = size.width
            val x = ((t.floatValue * 0.7f) % 1.6f - 0.3f) * w
            drawRect(Brush.linearGradient(listOf(Color.Transparent, Color.White.copy(alpha = 0.35f), Color.Transparent), Offset(x - w * 0.3f, 0f), Offset(x + w * 0.3f, size.height)))
        }
    }
}

/** Pulsujący pierścień (status online). */
@Composable
fun Modifier.pulse(color: Color, active: Boolean): Modifier {
    val on = active && LocalAnim.current[Anim.PRESENCE]
    if (!on) return this
    val t = rememberTime(true)
    return drawBehind {
        val ph = (t.floatValue % 1.8f) / 1.8f
        val r = size.minDimension / 2f * (1f + ph * 1.1f)
        drawCircle(color.copy(alpha = 0.45f * (1f - ph)), r, center, style = Stroke(2.dp.toPx() * (1f - ph * 0.5f)))
    }
}

/** Tęczowy, obracający się pierścień wokół nieobejrzanej relacji. */
@Composable
fun Modifier.storyRing(colors: List<Color>, width: Dp, animate: Boolean): Modifier {
    val on = animate && LocalAnim.current[Anim.STORIES]
    val t = rememberTime(on)
    return drawWithContent {
        drawContent()
        val sw = width.toPx()
        rotate(if (on) (t.floatValue * 70f) % 360f else 0f) {
            drawCircle(Brush.sweepGradient(colors + colors.first(), center), size.minDimension / 2f - sw / 2f, center, style = Stroke(sw))
        }
    }
}

/** Trzy skaczące kropki („pisze…”). */
@Composable
fun TypingDots(color: Color, dot: Dp = 5.dp) {
    val on = LocalAnim.current[Anim.TYPING]
    val t = rememberTime(on)
    Row {
        for (i in 0..2) {
            Spacer(
                Modifier.size(dot).graphicsLayer {
                    val ph = if (on) sin((t.floatValue * 7f - i * 0.9f).toDouble()).toFloat() else 0f
                    translationY = -(ph.coerceAtLeast(0f)) * dot.toPx() * 0.9f
                    alpha = if (on) 0.55f + 0.45f * ph.coerceAtLeast(0f) else 0.8f
                }.drawBehind { drawCircle(color) },
            )
            if (i < 2) Spacer(Modifier.width(dot * 0.6f))
        }
    }
}

/** Animacja napisu „Szept” w stylu: błysk, neon, kursor terminala… */
@Composable
fun Modifier.titleFx(): Modifier {
    val st = LocalStyle.current
    val on = LocalAnim.current[Anim.TITLE]
    val t = rememberTime(on)
    if (!on) return this
    val glow = st.pal.primary
    return when (st.spec.id) {
        "terminal" -> drawWithContent {
            drawContent()
            if ((t.floatValue * 1.6f).toInt() % 2 == 0) {
                drawRect(glow, Offset(size.width + 3.dp.toPx(), size.height * 0.18f), androidx.compose.ui.geometry.Size(9.dp.toPx(), size.height * 0.64f))
            }
        }
        "neon", "vapor" -> graphicsLayer {
            // migotanie neonu
            compositingStrategy = CompositingStrategy.Offscreen
            val x = t.floatValue
            val flick = if ((x % 4.3f) in 3.9f..4.0f || (x % 7.1f) in 6.6f..6.68f) 0.45f else 1f
            alpha = flick
        }.drawWithContent {
            drawContent()
            drawRect(glow.copy(alpha = 0.18f), blendMode = BlendMode.SrcAtop)
        }
        "retro" -> this
        else -> graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }.drawWithContent {
            drawContent()
            val period = 4.2f
            val ph = (t.floatValue % period) / 1.1f
            if (ph <= 1f) {
                val w = size.width
                val x = -w * 0.5f + ph * w * 2f
                drawRect(
                    Brush.linearGradient(listOf(Color.Transparent, Color.White.copy(alpha = if (st.dark) 0.85f else 0.75f), Color.Transparent), Offset(x - w * 0.25f, 0f), Offset(x + w * 0.25f, size.height)),
                    blendMode = BlendMode.SrcAtop,
                )
            }
        }
    }
}

// ======================= przejścia między ekranami =======================

/**
 * Przejście między ekranami w charakterze stylu (głębokość stosu rośnie = do przodu).
 * [type] — rodzaj (z ustawień albo stylu), [m] — czasy i krzywa stylu, [factor] — tempo z ustawień.
 */
fun AnimatedContentTransitionScope<Pair<Any, Int>>.screenTransition(type: Int, factor: Float, m: MotionSpec = Motions.Material): ContentTransform {
    val fwd = targetState.second >= initialState.second
    val d = (m.duration.coerceAtLeast(120) * factor).toInt()
    val q = (d * 0.55f).toInt()
    val e = m.easing
    // sprężyste charaktery ruchu (iOS, Expressive, zabawny…): przesunięcie i powiększenie na sprężynie — widać odbicie
    val springy = m.damping < 0.9f
    val stiff = (m.stiffness / (factor * factor)).coerceIn(40f, 3000f)
    val slideSpec: androidx.compose.animation.core.FiniteAnimationSpec<androidx.compose.ui.unit.IntOffset> =
        if (springy) androidx.compose.animation.core.spring(m.damping, stiff, androidx.compose.ui.unit.IntOffset(1, 1)) else tween(d, easing = e)
    val scaleSpec: androidx.compose.animation.core.FiniteAnimationSpec<Float> =
        if (springy) androidx.compose.animation.core.spring(m.damping, stiff) else tween(d, easing = e)
    return when (type) {
        Trans.NONE -> androidx.compose.animation.EnterTransition.None togetherWith androidx.compose.animation.ExitTransition.None
        Trans.FADE -> fadeIn(tween(d, easing = e)) togetherWith fadeOut(tween(q))
        Trans.ZOOM -> if (fwd) (scaleIn(scaleSpec, initialScale = 0.86f) + fadeIn(tween(d))) togetherWith (scaleOut(tween(d, easing = e), targetScale = 1.06f) + fadeOut(tween(q)))
        else (scaleIn(scaleSpec, initialScale = 1.1f) + fadeIn(tween(d))) togetherWith (scaleOut(tween(d, easing = e), targetScale = 0.92f) + fadeOut(tween(q)))
        // Fluent: wsunięcie z dołu o ~40 dp z wygaszeniem, wyjście krótkie
        Trans.RISE -> if (fwd) (slideInVertically(tween(d, easing = e)) { (it * 0.06f).toInt() } + fadeIn(tween(d))) togetherWith fadeOut(tween(q / 2))
        else fadeIn(tween(d)) togetherWith (slideOutVertically(tween(q, easing = e)) { (it * 0.06f).toInt() } + fadeOut(tween(q)))
        Trans.DEPTH -> if (fwd) (scaleIn(tween(d), initialScale = 1.12f) + fadeIn(tween(d))) togetherWith (scaleOut(tween(d), targetScale = 0.86f) + fadeOut(tween(d)))
        else (scaleIn(tween(d), initialScale = 0.86f) + fadeIn(tween(d))) togetherWith (scaleOut(tween(d), targetScale = 1.12f) + fadeOut(tween(q)))
        // Material: wspólna oś — krótki przesuw (30 dp) z przenikaniem
        Trans.SHARED_X -> if (fwd) (slideInHorizontally(tween(d, easing = e)) { (it * 0.08f).toInt() } + fadeIn(tween(d - q, delayMillis = q / 2))) togetherWith
            (slideOutHorizontally(tween(d, easing = e)) { -(it * 0.08f).toInt() } + fadeOut(tween(q / 2)))
        else (slideInHorizontally(tween(d, easing = e)) { -(it * 0.08f).toInt() } + fadeIn(tween(d - q, delayMillis = q / 2))) togetherWith
            (slideOutHorizontally(tween(d, easing = e)) { (it * 0.08f).toInt() } + fadeOut(tween(q / 2)))
        // iOS i komunikatory: nowy ekran wjeżdża z prawej, poprzedni odsuwa się o ~30% (paralaksa)
        else -> if (fwd) (slideInHorizontally(slideSpec) { it } + fadeIn(tween(q))) togetherWith
            (slideOutHorizontally(slideSpec) { -it / 3 } + fadeOut(tween(d)))
        else (slideInHorizontally(slideSpec) { -it / 3 } + fadeIn(tween(d))) togetherWith
            (slideOutHorizontally(slideSpec) { it } + fadeOut(tween(q)))
    }
}

/** Przesunięcie dla paralaksy (piksele) — pomocnicze. */
internal fun parallax(tilt: TiltState?, depth: Float): IntOffset =
    if (tilt == null) IntOffset.Zero else IntOffset((tilt.x * depth).toInt(), (tilt.y * depth).toInt())

internal const val TAU = (2 * PI).toFloat()
