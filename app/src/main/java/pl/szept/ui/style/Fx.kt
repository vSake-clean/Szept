package pl.szept.ui.style

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * Efekty na cały ekran: konfetti, serca, balony, śnieg, fajerwerki, gwiazdy — po wygranej w grze albo
 * po wiadomości z 🎉, ❤️, 🎈… Rysowane nad wszystkim, nie przechwytują dotyku.
 */
object ScreenFx {
    enum class Kind(val label: String, val emoji: String) {
        CONFETTI("Konfetti", "🎉"), HEARTS("Serca", "❤️"), BALLOONS("Balony", "🎈"),
        SNOW("Śnieg", "❄️"), FIREWORKS("Fajerwerki", "🎆"), STARS("Gwiazdy", "⭐"),
    }

    internal class Burst(val kind: Kind, val seed: Long) {
        var start = -1L
        val seconds = when (kind) { Kind.BALLOONS -> 4.6f; Kind.SNOW -> 5f; Kind.STARS -> 2f; else -> 3.4f }
        val rnd = java.util.Random(seed)
        val n = when (kind) { Kind.CONFETTI -> 110; Kind.HEARTS -> 24; Kind.BALLOONS -> 13; Kind.SNOW -> 80; Kind.FIREWORKS -> 6; Kind.STARS -> 34 }
        /** Dane cząstek: [x 0..1, y/offset, prędkość, faza, wielkość, kolor-indeks, opóźnienie]. */
        val p = Array(n) { FloatArray(7) { rnd.nextFloat() } }
    }

    internal val bursts = mutableStateListOf<Burst>()
    private var last = 0L

    /** Odtwórz efekt (najwyżej jeden na ~1 s, by się nie piętrzyły). */
    fun play(kind: Kind) {
        val now = System.currentTimeMillis()
        if (now - last < 900 || bursts.size >= 3) return
        last = now
        bursts.add(Burst(kind, now))
    }

    fun celebrate() = play(Kind.CONFETTI)

    /** Usuń wszystkie trwające efekty (np. przy zmianie konta, w testach). */
    fun clear() { bursts.clear(); last = 0L }

    /** Efekt pasujący do treści wiadomości albo null. */
    fun forText(text: String): Kind? {
        if (text.isBlank() || text.length > 500) return null
        val t = text.lowercase()
        return when {
            "🎆" in text || "🎇" in text || "nowego roku" in t || "sylwester" in t -> Kind.FIREWORKS
            "🎉" in text || "🎊" in text || "🥳" in text || "gratul" in t || "sto lat" in t || "wszystkiego najlepszego" in t -> Kind.CONFETTI
            "🎈" in text || "urodzin" in t -> Kind.BALLOONS
            "❄" in text || "☃" in text || "⛄" in text || "śnieg" in t || "wesołych świąt" in t -> Kind.SNOW
            "❤" in text || "💕" in text || "💖" in text || "💗" in text || "😍" in text || "🥰" in text || "😘" in text || "kocham" in t -> Kind.HEARTS
            "⭐" in text || "🌟" in text || "✨" in text -> Kind.STARS
            else -> null
        }
    }
}

/** Iskry pod palcem (gdy włączone). */
internal object TouchSparks {
    class Tap(val pos: Offset, val seed: Long) { var start = -1L }
    val taps = mutableStateListOf<Tap>()
    fun add(o: Offset) {
        if (taps.size >= 6) taps.removeAt(0)
        taps.add(Tap(o, System.nanoTime()))
    }
}

/** Modyfikator korzenia: zbiera dotknięcia do iskier (niczego nie przechwytuje). */
fun Modifier.sparkTaps(enabled: Boolean): Modifier = if (!enabled) this else pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            val ev = awaitPointerEvent(PointerEventPass.Initial)
            ev.changes.forEach { c -> if (c.pressed && !c.previousPressed) TouchSparks.add(c.position) }
        }
    }
}

private val FUN = listOf(
    Color(0xFFFF4D6D), Color(0xFFFFC300), Color(0xFF3DDC97), Color(0xFF4CC9F0), Color(0xFF9B5DE5), Color(0xFFFF8FAB), Color(0xFFF15BB5),
)

private fun sinf(x: Float) = sin(x.toDouble()).toFloat()
private fun cosf(x: Float) = cos(x.toDouble()).toFloat()

/** Warstwa efektów (nad całą aplikacją). */
@Composable
fun FxLayer() {
    val bursts = ScreenFx.bursts
    val taps = TouchSparks.taps
    if (bursts.isEmpty() && taps.isEmpty()) return
    val st = LocalStyle.current
    val clock = remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        while (bursts.isNotEmpty() || taps.isNotEmpty()) {
            androidx.compose.runtime.withFrameNanos { now ->
                // nowy zegar (np. po odtworzeniu ekranu) liczy od innego początku — wtedy efekt zaczyna się od nowa
                bursts.forEach { if (it.start < 0 || now < it.start) it.start = now }
                taps.forEach { if (it.start < 0 || now < it.start) it.start = now }
                bursts.removeAll { now - it.start > (it.seconds * 1e9).toLong() }
                taps.removeAll { now - it.start > 650_000_000L }
                clock.longValue = now
            }
        }
    }
    val heart = remember { heartPath() }
    val star5 = remember { starPath(5, 1f, 0.45f) }
    val star4 = remember { starPath(4, 1f, 0.25f) }
    val leaf = remember { Path().apply { moveTo(-1f, 0f); quadraticTo(0f, -0.62f, 1f, 0f); quadraticTo(0f, 0.62f, -1f, 0f); close() } }
    Canvas(Modifier.fillMaxSize()) {
        val now = clock.longValue
        for (b in bursts) {
            if (b.start < 0) continue
            val t = (now - b.start) / 1e9f
            val fade = (1f - ((t - (b.seconds - 0.7f)) / 0.7f)).coerceIn(0f, 1f)
            drawBurst(b, t, fade, heart, star5)
        }
        for (tp in taps) {
            if (tp.start < 0) continue
            val t = (now - tp.start) / 0.65e9f
            drawTap(tp, t.coerceIn(0f, 1f), st, heart, star4, leaf)
        }
    }
}

internal fun DrawScope.drawBurst(b: ScreenFx.Burst, t: Float, fade: Float, heart: Path, star: Path) {
    val w = size.width; val h = size.height; val px = density
    when (b.kind) {
        ScreenFx.Kind.CONFETTI -> for (q in b.p) {
            val delay = q[6] * 0.6f
            val tt = (t - delay).coerceAtLeast(0f)
            if (tt <= 0f) continue
            val y = -30f * px - q[1] * 200f * px + tt * (140f + 160f * q[2]) * px
            val x = q[0] * w + sinf(tt * (2f + 3f * q[3]) + q[3] * 9f) * 26f * px
            val c = FUN[(q[5] * FUN.size).toInt().coerceIn(0, FUN.size - 1)]
            val s = (5f + 5f * q[4]) * px
            withTransform({ translate(x, y); rotate(tt * (200f + 300f * q[3]) + q[3] * 360f, Offset.Zero); scale(1f, cosf(tt * (4f + 6f * q[2])).coerceIn(-1f, 1f), Offset.Zero) }) {
                drawRect(c.copy(alpha = fade), Offset(-s / 2f, -s), Size(s, s * 1.8f))
            }
        }
        ScreenFx.Kind.HEARTS -> for (q in b.p) {
            val tt = (t - q[6] * 1.2f).coerceAtLeast(0f)
            if (tt <= 0f) continue
            val y = h + 30f * px - tt * (150f + 120f * q[2]) * px
            val x = q[0] * w + sinf(tt * 2.2f + q[3] * 6f) * 22f * px
            val s = (10f + 12f * q[4]) * px
            val c = listOf(Color(0xFFFF2D55), Color(0xFFFF6B9A), Color(0xFFE11D48), Color(0xFFFF8FAB))[(q[5] * 4).toInt().coerceIn(0, 3)]
            withTransform({ translate(x, y); rotate(sinf(tt * 3f + q[3]) * 15f, Offset.Zero); scale(s, s, Offset.Zero) }) { drawPath(heart, c.copy(alpha = 0.9f * fade)) }
        }
        ScreenFx.Kind.BALLOONS -> for (q in b.p) {
            val tt = (t - q[6] * 1.4f).coerceAtLeast(0f)
            if (tt <= 0f) continue
            val y = h + 60f * px - tt * (110f + 70f * q[2]) * px
            val x = q[0] * w + sinf(tt * 1.4f + q[3] * 6f) * 18f * px
            val r = (17f + 8f * q[4]) * px
            val c = FUN[(q[5] * FUN.size).toInt().coerceIn(0, FUN.size - 1)]
            drawLine(Color.Gray.copy(alpha = 0.7f * fade), Offset(x, y + r * 1.15f), Offset(x + sinf(tt * 3f) * 6f * px, y + r * 3f), 1.2f * px)
            drawOval(c.copy(alpha = 0.92f * fade), Offset(x - r, y - r * 1.2f), Size(r * 2, r * 2.4f))
            drawOval(Color.White.copy(alpha = 0.45f * fade), Offset(x - r * 0.55f, y - r * 0.9f), Size(r * 0.45f, r * 0.7f))
        }
        ScreenFx.Kind.SNOW -> for (q in b.p) {
            val tt = (t - q[6] * 1.5f).coerceAtLeast(0f)
            if (tt <= 0f) continue
            val y = -10f * px - q[1] * 120f * px + tt * (55f + 60f * q[2]) * px
            val x = q[0] * w + sinf(tt * (1f + q[3]) + q[3] * 7f) * 20f * px
            val r = (2f + 3.5f * q[4]) * px
            drawCircle(Color(0xFF90CAF9).copy(alpha = 0.6f * fade), r + 1f * px, Offset(x, y))
            drawCircle(Color.White.copy(alpha = 0.95f * fade), r, Offset(x, y))
        }
        ScreenFx.Kind.FIREWORKS -> b.p.forEachIndexed { i, q ->
            val t0 = q[6] * 1.6f
            val tt = t - t0
            if (tt < 0f) return@forEachIndexed
            val cx = w * (0.15f + 0.7f * q[0]); val cy = h * (0.15f + 0.3f * q[1])
            val col = FUN[(q[5] * FUN.size).toInt().coerceIn(0, FUN.size - 1)]
            if (tt < 0.45f) {
                // rakieta leci w górę
                val k = tt / 0.45f
                val y = h - (h - cy) * k
                drawCircle(Color.White.copy(alpha = fade), 2.5f * px, Offset(cx, y))
                drawLine(col.copy(alpha = 0.5f * fade), Offset(cx, y), Offset(cx, y + 30f * px), 2f * px)
            } else {
                val e = tt - 0.45f
                val a = ((1.5f - e) / 1.5f).coerceIn(0f, 1f) * fade
                val rr = java.util.Random(b.seed + i)
                repeat(36) { k ->
                    val ang = k * 10f * (Math.PI / 180f).toFloat() + rr.nextFloat() * 0.1f
                    val sp = (110f + 90f * rr.nextFloat()) * px
                    val x = cx + cosf(ang) * sp * e
                    val y = cy + sinf(ang) * sp * e + 60f * px * e * e
                    drawCircle(col.copy(alpha = a), 2.2f * px, Offset(x, y))
                }
            }
        }
        ScreenFx.Kind.STARS -> for (q in b.p) {
            val tt = (t - q[6] * 0.3f).coerceAtLeast(0f)
            val ang = q[0] * 6.283f
            val sp = (120f + 220f * q[2]) * px
            val x = w / 2f + cosf(ang) * sp * tt
            val y = h * 0.45f + sinf(ang) * sp * tt
            val s = (6f + 9f * q[4]) * px * (1f - tt / 2.2f).coerceAtLeast(0.2f)
            withTransform({ translate(x, y); rotate(tt * 220f, Offset.Zero); scale(s, s, Offset.Zero) }) {
                drawPath(star, Color(0xFFFFC83D).copy(alpha = fade))
            }
        }
    }
}

private fun DrawScope.drawTap(tp: TouchSparks.Tap, t: Float, st: StyleState, heart: Path, star: Path, leaf: Path) {
    val px = density
    val a = (1f - t)
    val prim = st.pal.primary
    drawCircle(prim.copy(alpha = 0.35f * a), (6f + 30f * t) * px, tp.pos, style = Stroke(2f * px * a + 0.5f))
    val rnd = java.util.Random(tp.seed)
    repeat(8) { i ->
        val ang = i * 0.785f + rnd.nextFloat() * 0.4f
        val d = (10f + 34f * t) * px * (0.7f + 0.6f * rnd.nextFloat())
        val o = Offset(tp.pos.x + cosf(ang) * d, tp.pos.y + sinf(ang) * d)
        val s = (3f + 3f * rnd.nextFloat()) * px * (1f - t * 0.6f)
        val col = when (st.spec.spark) {
            Spark.NEON -> if (i % 2 == 0) Color(0xFF00E5FF) else Color(0xFFFF2BD6)
            Spark.GOLD -> Color(0xFFE9C46A)
            Spark.HEARTS -> Color(0xFFFF6B9A)
            Spark.LEAVES -> st.pal.bg.getOrElse(i % max(1, st.pal.bg.size)) { prim }
            Spark.PIXELS, Spark.DOTS -> prim
            Spark.BUBBLES -> if (st.dark) Color.White else prim
            Spark.STARS -> Color(0xFFFFC83D)
        }.copy(alpha = a)
        when (st.spec.spark) {
            Spark.DOTS, Spark.GOLD -> drawCircle(col, s * 0.6f, o)
            Spark.BUBBLES -> drawCircle(col, s, o, style = Stroke(1.2f * px))
            Spark.NEON -> drawLine(col, o, Offset(o.x + cosf(ang) * s * 2f, o.y + sinf(ang) * s * 2f), 2f * px)
            Spark.PIXELS -> drawRect(col, Offset(o.x - s / 2f, o.y - s / 2f), Size(s, s))
            Spark.HEARTS -> withTransform({ translate(o.x, o.y); scale(s, s, Offset.Zero) }) { drawPath(heart, col) }
            Spark.LEAVES -> withTransform({ translate(o.x, o.y); rotate(ang * 57f, Offset.Zero); scale(s * 1.3f, s * 1.3f, Offset.Zero) }) { drawPath(leaf, col) }
            Spark.STARS -> withTransform({ translate(o.x, o.y); scale(s * 1.2f, s * 1.2f, Offset.Zero) }) { drawPath(star, col) }
        }
    }
}

/** Dla testów: jedna klatka efektu w chwili [t] (sekundy). */
internal fun drawBurstForTest(s: DrawScope, b: ScreenFx.Burst, t: Float) {
    s.drawBurst(b, t, 1f, heartPath(), starPath(5, 1f, 0.45f))
}
