package pl.szept.ui.style

import android.graphics.Bitmap
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Density
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Chwila „zatrzymanego” tła, gdy animacje są wyłączone. */
private const val STILL_T = 11.3f

/**
 * Ruch tła rozmowy jak w Telegramie: gradient przesuwa się przy każdej wysłanej wiadomości.
 * [phase] rośnie o 1 przy wysłaniu (animowane), tło odczytuje go przy rysowaniu.
 */
object ChatBgMotion {
    val phase = Animatable(0f)
}

/** Tło stylu — rysowane pod wszystkimi ekranami (albo w podglądzie stylu). */
@Composable
fun StyleBackground(modifier: Modifier = Modifier, st: StyleState = LocalStyle.current, kind: Bg = st.spec.bg, animate: Boolean = true) {
    val anim = LocalAnim.current
    val moving = animate && st.spec.moving && anim[Anim.BG] && kind == Bg.SKY
    val time = rememberTime(moving)
    Spacer(
        modifier.drawWithCache {
            val p = makeBg(kind, st.pal, size, this)
            onDrawBehind { p.draw(this, if (moving) time.floatValue + STILL_T else STILL_T, null) }
        },
    )
}

private fun sinf(x: Float) = sin(x.toDouble()).toFloat()
private fun cosf(x: Float) = cos(x.toDouble()).toFloat()
private fun frac(x: Float) = x - floor(x)

/** Kafelek wzoru (powtarzany na całym ekranie). */
private fun tile(wPx: Int, hPx: Int, block: (android.graphics.Canvas, android.graphics.Paint) -> Unit): ShaderBrush {
    val bmp = Bitmap.createBitmap(max(1, wPx), max(1, hPx), Bitmap.Config.ARGB_8888)
    val c = android.graphics.Canvas(bmp)
    val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    block(c, p)
    return ShaderBrush(ImageShader(bmp.asImageBitmap(), TileMode.Repeated, TileMode.Repeated))
}

internal abstract class BgPainter(val pal: Pal, val size: Size, d: Density) {
    val w = size.width
    val h = size.height
    val px = d.density
    val rnd = java.util.Random(1234)
    fun r() = rnd.nextFloat()
    val maxDim = max(w, h)
    val minDim = min(w, h)
    abstract fun DrawScope.paint(t: Float, tx: Float, ty: Float)
    fun draw(s: DrawScope, t: Float, tilt: TiltState?) = with(s) { paint(t, tilt?.x ?: 0f, tilt?.y ?: 0f) }
    fun drawAt(s: DrawScope, t: Float, tx: Float, ty: Float) = with(s) { paint(t, tx, ty) }
}

internal fun makeBg(kind: Bg, pal: Pal, size: Size, d: Density): BgPainter = when (kind) {
    Bg.PLAIN, Bg.RETRO -> Plain(pal, size, d)
    Bg.AURA -> Aura(pal, size, d)
    Bg.SKY -> Sky(pal, size, d)
    Bg.IOS6 -> Ios6(pal, size, d)
    Bg.TELEGRAM -> TelegramBg(pal, size, d)
    Bg.WHATSAPP -> WhatsAppBg(pal, size, d)
    Bg.MICA -> Mica(pal, size, d)
    Bg.HARMONY -> Harmony(pal, size, d)
    Bg.BLISS -> Bliss(pal, size, d)
    Bg.DESKTOP -> Solid(pal.bg.getOrElse(0) { Color(0xFF008080) }, pal, size, d)
    Bg.TERMINAL -> Solid(Color.Black, pal, size, d)
}

private class Plain(pal: Pal, size: Size, d: Density) : BgPainter(pal, size, d) {
    override fun DrawScope.paint(t: Float, tx: Float, ty: Float) { drawRect(pal.base) }
}

private class Solid(val c: Color, pal: Pal, size: Size, d: Density) : BgPainter(pal, size, d) {
    override fun DrawScope.paint(t: Float, tx: Float, ty: Float) { drawRect(c) }
}

/**
 * Miękka kolorowa poświata pod szkłem (jak tapety pod Liquid Glass): tło stylu i trzy rozlane plamy koloru.
 * Dzięki niej szkło ma co rozmywać i załamywać, a tekst zostaje czytelny.
 */
private class Aura(pal: Pal, size: Size, d: Density) : BgPainter(pal, size, d) {
    private val cols = pal.bg.ifEmpty { listOf(Color(0xFFCFE0FF), Color(0xFFF0D8FF), Color(0xFFCFF3E8)) }
    private val spots = listOf(Offset(0.12f, 0.1f) to 0.78f, Offset(0.95f, 0.46f) to 0.72f, Offset(0.25f, 0.98f) to 0.85f)
    private val brushes = spots.mapIndexed { k, (o, r) ->
        val c = cols[k % cols.size]
        Brush.radialGradient(listOf(c, c.copy(alpha = 0.55f), c.copy(alpha = 0f)), Offset(o.x * w, o.y * h), r * maxDim)
    }
    override fun DrawScope.paint(t: Float, tx: Float, ty: Float) {
        drawRect(pal.base)
        brushes.forEach { drawRect(it) }
    }
}

/** iOS 26 „Niebo”: błękit z miękkimi chmurami (w ciemnym — nocne niebo). */
private class Sky(pal: Pal, size: Size, d: Density) : BgPainter(pal, size, d) {
    private val cols = pal.bg.ifEmpty { listOf(Color(0xFF1F6FDB), Color(0xFF3D93F0), Color(0xFF79B9F6)) }
    private val grad = Brush.verticalGradient(cols)
    private class Cloud(val x: Float, val y: Float, val rx: Float, val ry: Float, val a: Float, val speed: Float)
    private val clouds = List(9) {
        // chmury delikatne — na niebie są białe napisy i szkło
        Cloud(r() * 1.2f - 0.1f, 0.25f + r() * 0.75f, (90f + 140f * r()) * px, (40f + 60f * r()) * px, (if (pal.dark) 0.04f else 0.06f) + r() * 0.07f, 2f + 4f * r())
    }
    private val puff = Brush.radialGradient(listOf(Color.White, Color.White.copy(alpha = 0.5f), Color.Transparent), Offset.Zero, 1f)

    override fun DrawScope.paint(t: Float, tx: Float, ty: Float) {
        drawRect(grad)
        for (c in clouds) {
            val x = ((c.x * w + t * c.speed * px) % (w + 2 * c.rx)) - c.rx * 0.5f
            val y = c.y * h
            withTransform({ translate(x, y); scale(c.rx, c.ry, Offset.Zero) }) { drawCircle(puff, 1f, Offset.Zero, alpha = c.a) }
        }
    }
}

/** iOS 6: jasne, niebiesko-szare tło Wiadomości. */
private class Ios6(pal: Pal, size: Size, d: Density) : BgPainter(pal, size, d) {
    private val grad = Brush.verticalGradient(listOf(lerp(pal.base, Color.White, 0.18f), pal.base))
    override fun DrawScope.paint(t: Float, tx: Float, ty: Float) { drawRect(grad) }
}

/** Deseń rysunków (WhatsApp, Telegram): proste ikonki kreską, w różnych obrotach. */
private fun doodleTile(px: Float, color: Color, seed: Long): ShaderBrush {
    val s = (230 * px).toInt().coerceIn(200, 900)
    val rr = java.util.Random(seed)
    return tile(s, s) { c, p ->
        p.style = android.graphics.Paint.Style.STROKE
        p.strokeWidth = max(1.2f, 1.25f * px)
        p.strokeCap = android.graphics.Paint.Cap.ROUND
        p.strokeJoin = android.graphics.Paint.Join.ROUND
        p.color = color.toArgb()
        val n = 5
        val cell = s / n.toFloat()
        var k = 0
        for (row in 0 until n) for (col in 0 until n) {
            val cx = (col + 0.5f) * cell + (rr.nextFloat() - 0.5f) * cell * 0.3f + (if (row % 2 == 1) cell * 0.25f else 0f)
            val cy = (row + 0.5f) * cell + (rr.nextFloat() - 0.5f) * cell * 0.3f
            val sz = cell * (0.26f + rr.nextFloat() * 0.08f)
            c.save(); c.translate(cx % s, cy); c.rotate(rr.nextFloat() * 70f - 35f)
            doodle(c, p, k++ % 12, sz)
            c.restore()
        }
    }
}

/** Jedna ikonka deseniu w kwadracie ±s. */
private fun doodle(c: android.graphics.Canvas, p: android.graphics.Paint, kind: Int, s: Float) {
    val path = android.graphics.Path()
    when (kind) {
        0 -> { // serce
            path.moveTo(0f, s * 0.8f); path.cubicTo(-s * 1.3f, -s * 0.1f, -s * 0.6f, -s * 1.1f, 0f, -s * 0.35f)
            path.cubicTo(s * 0.6f, -s * 1.1f, s * 1.3f, -s * 0.1f, 0f, s * 0.8f); c.drawPath(path, p)
        }
        1 -> { // gwiazda
            for (i in 0..10) {
                val rad = if (i % 2 == 0) s else s * 0.45f
                val a = (i * PI / 5 - PI / 2).toFloat()
                if (i == 0) path.moveTo(cosf(a) * rad, sinf(a) * rad) else path.lineTo(cosf(a) * rad, sinf(a) * rad)
            }
            c.drawPath(path, p)
        }
        2 -> { // uśmiech
            c.drawCircle(0f, 0f, s, p); c.drawCircle(-s * 0.35f, -s * 0.25f, s * 0.08f, p); c.drawCircle(s * 0.35f, -s * 0.25f, s * 0.08f, p)
            c.drawArc(-s * 0.55f, -s * 0.4f, s * 0.55f, s * 0.6f, 20f, 140f, false, p)
        }
        3 -> { // chmurka
            path.moveTo(-s, s * 0.4f); path.cubicTo(-s * 1.4f, s * 0.4f, -s * 1.3f, -s * 0.4f, -s * 0.6f, -s * 0.3f)
            path.cubicTo(-s * 0.5f, -s, s * 0.4f, -s * 1.1f, s * 0.55f, -s * 0.35f); path.cubicTo(s * 1.3f, -s * 0.5f, s * 1.4f, s * 0.4f, s * 0.9f, s * 0.4f)
            path.close(); c.drawPath(path, p)
        }
        4 -> { // nuta
            c.drawCircle(-s * 0.35f, s * 0.6f, s * 0.28f, p); c.drawLine(-s * 0.07f, s * 0.6f, -s * 0.07f, -s * 0.8f, p)
            c.drawLine(-s * 0.07f, -s * 0.8f, s * 0.6f, -s * 0.5f, p)
        }
        5 -> { // słuchawka telefonu
            path.moveTo(-s * 0.7f, -s * 0.8f); path.quadTo(-s * 1.1f, s * 0.1f, -s * 0.2f, s * 0.75f); path.quadTo(s * 0.3f, s * 1.0f, s * 0.75f, s * 0.6f)
            c.drawPath(path, p); c.drawLine(-s * 0.7f, -s * 0.8f, -s * 0.35f, -s * 0.45f, p); c.drawLine(s * 0.75f, s * 0.6f, s * 0.4f, s * 0.3f, p)
        }
        6 -> { // samolocik
            path.moveTo(-s, 0f); path.lineTo(s, -s * 0.7f); path.lineTo(s * 0.3f, s * 0.8f); path.lineTo(-s * 0.1f, s * 0.15f); path.close()
            c.drawPath(path, p); c.drawLine(-s * 0.1f, s * 0.15f, s, -s * 0.7f, p)
        }
        7 -> { // kubek
            c.drawRoundRect(-s * 0.6f, -s * 0.5f, s * 0.4f, s * 0.8f, s * 0.15f, s * 0.15f, p); c.drawArc(s * 0.2f, -s * 0.2f, s * 0.8f, s * 0.45f, -90f, 180f, false, p)
            c.drawLine(-s * 0.3f, -s * 0.9f, -s * 0.2f, -s * 0.65f, p); c.drawLine(0f, -s * 0.95f, s * 0.1f, -s * 0.65f, p)
        }
        8 -> { // słońce
            c.drawCircle(0f, 0f, s * 0.45f, p)
            for (i in 0 until 8) { val a = i * PI.toFloat() / 4f; c.drawLine(cosf(a) * s * 0.65f, sinf(a) * s * 0.65f, cosf(a) * s, sinf(a) * s, p) }
        }
        9 -> { // koperta
            c.drawRoundRect(-s, -s * 0.65f, s, s * 0.65f, s * 0.1f, s * 0.1f, p); path.moveTo(-s, -s * 0.6f); path.lineTo(0f, s * 0.1f); path.lineTo(s, -s * 0.6f); c.drawPath(path, p)
        }
        10 -> { // balon
            c.drawOval(-s * 0.6f, -s, s * 0.6f, s * 0.45f, p); path.moveTo(0f, s * 0.45f); path.quadTo(-s * 0.3f, s * 0.75f, s * 0.1f, s); c.drawPath(path, p)
        }
        else -> { // listek
            path.moveTo(-s, 0f); path.quadTo(0f, -s * 0.8f, s, 0f); path.quadTo(0f, s * 0.8f, -s, 0f); c.drawPath(path, p); c.drawLine(-s * 0.8f, 0f, s * 0.8f, 0f, p)
        }
    }
}

/** Telegram: animowany gradient z 4 kolorów (przesuwa się po wysłaniu wiadomości) i deseń rysunków. */
private class TelegramBg(pal: Pal, size: Size, d: Density) : BgPainter(pal, size, d) {
    private val cols = pal.bg.ifEmpty { listOf(Color(0xFFDBDDBB), Color(0xFF6BA587), Color(0xFFD5D88D), Color(0xFF88B884)) }
    private val base = avg(cols)
    /** 8 punktów, po których krążą kolory (jak w Telegramie). */
    private val pts = listOf(
        Offset(0.80f, 0.10f), Offset(0.60f, 0.20f), Offset(0.35f, 0.25f), Offset(0.25f, 0.60f),
        Offset(0.20f, 0.90f), Offset(0.40f, 0.80f), Offset(0.65f, 0.75f), Offset(0.75f, 0.40f),
    )
    private val brushes = cols.map { c -> Brush.radialGradient(listOf(c, c.copy(alpha = 0.55f), c.copy(alpha = 0f)), Offset.Zero, 1f) }
    private val pattern = doodleTile(px, if (pal.dark) Color.White.copy(alpha = 0.07f) else Color(0xFF1F3B1A).copy(alpha = 0.13f), 7)

    override fun DrawScope.paint(t: Float, tx: Float, ty: Float) {
        drawRect(base)
        val ph = ChatBgMotion.phase.value
        val i0 = floor(ph).toInt(); val f = frac(ph)
        val rad = maxDim * 0.85f
        for (k in cols.indices) {
            val a = pts[((k * 2 + i0) % 8 + 8) % 8]; val b = pts[((k * 2 + i0 + 1) % 8 + 8) % 8]
            val o = Offset((a.x + (b.x - a.x) * f) * w, (a.y + (b.y - a.y) * f) * h)
            withTransform({ translate(o.x, o.y); scale(rad, rad, Offset.Zero) }) { drawCircle(brushes[k], 1f, Offset.Zero) }
        }
        drawRect(pattern)
    }
}

/** WhatsApp: beżowe (nocą ciemnozielone) tło z deseniem rysunków. */
private class WhatsAppBg(pal: Pal, size: Size, d: Density) : BgPainter(pal, size, d) {
    private val bgc = if (pal.dark) Color(0xFF0B141A) else Color(0xFFEFEAE2)
    private val pattern = doodleTile(px, if (pal.dark) Color.White.copy(alpha = 0.055f) else Color(0xFF6B5B46).copy(alpha = 0.09f), 3)
    override fun DrawScope.paint(t: Float, tx: Float, ty: Float) { drawRect(bgc); drawRect(pattern) }
}

/** Windows 11 Mica: tło delikatnie zabarwione kolorami tapety. */
private class Mica(pal: Pal, size: Size, d: Density) : BgPainter(pal, size, d) {
    private val a = if (pal.dark) Color(0xFF1B2433) else Color(0xFFDCE6F5)
    private val b = if (pal.dark) Color(0xFF241F2E) else Color(0xFFEDE6F3)
    private val ga = Brush.radialGradient(listOf(a.copy(alpha = 0.9f), Color.Transparent), Offset(0f, 0f), maxDim * 0.8f)
    private val gb = Brush.radialGradient(listOf(b.copy(alpha = 0.8f), Color.Transparent), Offset(w, h), maxDim * 0.7f)
    override fun DrawScope.paint(t: Float, tx: Float, ty: Float) {
        drawRect(pal.base)
        drawCircle(ga, maxDim * 0.8f, Offset(0f, 0f))
        drawCircle(gb, maxDim * 0.7f, Offset(w, h))
    }
}

/** Windows 7: głęboki błękit z jasnymi smugami światła (w duchu tapety „Harmony”). */
private class Harmony(pal: Pal, size: Size, d: Density) : BgPainter(pal, size, d) {
    private val grad = Brush.verticalGradient(pal.bg.ifEmpty { listOf(Color(0xFF0B3D7A), Color(0xFF1468B8), Color(0xFF55A5E8)) })
    private val beams = List(5) { floatArrayOf(r(), 0.15f + r() * 0.7f, 0.5f + r()) }
    private val beam = Brush.radialGradient(listOf(Color(0xFFBFF2FF).copy(alpha = 0.55f), Color(0xFF6FD3FF).copy(alpha = 0.18f), Color.Transparent), Offset.Zero, 1f)
    private val glow = Brush.radialGradient(listOf(Color(0xFF9BE7FF).copy(alpha = 0.35f), Color.Transparent), Offset(w * 0.5f, h * 0.45f), minDim * 0.6f)
    override fun DrawScope.paint(t: Float, tx: Float, ty: Float) {
        drawRect(grad)
        for (b in beams) {
            withTransform({ translate(b[0] * w, b[1] * h); rotate(-28f, Offset.Zero); scale(w * 0.9f * b[2], 26f * px, Offset.Zero) }) {
                drawCircle(beam, 1f, Offset.Zero)
            }
        }
        drawCircle(glow, minDim * 0.6f, Offset(w * 0.5f, h * 0.45f))
    }
}

/** Windows XP: niebo z chmurami i zielone wzgórze (w duchu tapety „Bliss”). */
private class Bliss(pal: Pal, size: Size, d: Density) : BgPainter(pal, size, d) {
    private val sky = Brush.verticalGradient(listOf(Color(0xFF245EDB), Color(0xFF4F8EEB), Color(0xFF9CC7F5)), 0f, h * 0.62f)
    private val hill = Path().apply {
        moveTo(0f, h * 0.66f)
        cubicTo(w * 0.2f, h * 0.6f, w * 0.38f, h * 0.53f, w * 0.58f, h * 0.56f)
        cubicTo(w * 0.78f, h * 0.59f, w * 0.9f, h * 0.66f, w, h * 0.7f)
        lineTo(w, h); lineTo(0f, h); close()
    }
    private val grass = Brush.verticalGradient(listOf(Color(0xFF7DC242), Color(0xFF4E9A2A), Color(0xFF2F6E1A)), h * 0.53f, h)
    private val shine = Brush.radialGradient(listOf(Color(0xFFB7E37A).copy(alpha = 0.45f), Color.Transparent), Offset(w * 0.45f, h * 0.6f), w * 0.5f)
    private val clouds = List(6) { floatArrayOf(r(), 0.08f + r() * 0.4f, (60f + 90f * r()) * px, (22f + 26f * r()) * px) }
    private val puff = Brush.radialGradient(listOf(Color.White.copy(alpha = 0.75f), Color.White.copy(alpha = 0.25f), Color.Transparent), Offset.Zero, 1f)
    override fun DrawScope.paint(t: Float, tx: Float, ty: Float) {
        drawRect(sky)
        for (c in clouds) withTransform({ translate(c[0] * w, c[1] * h); scale(c[2], c[3], Offset.Zero) }) { drawCircle(puff, 1f, Offset.Zero) }
        drawPath(hill, grass)
        drawPath(hill, shine)
    }
}

// ======================= kształty pomocnicze =======================

/** Gwiazda o [n] ramionach (promień 1). */
internal fun starPath(n: Int, outer: Float, inner: Float): Path = Path().apply {
    for (i in 0 until n * 2) {
        val rad = if (i % 2 == 0) outer else inner
        val a = (i * PI / n - PI / 2).toFloat()
        val x = cosf(a) * rad; val y = sinf(a) * rad
        if (i == 0) moveTo(x, y) else lineTo(x, y)
    }
    close()
}

/** Serduszko (mniej więcej w kwadracie −1…1). */
internal fun heartPath(): Path = Path().apply {
    moveTo(0f, 0.85f)
    cubicTo(-1.25f, 0.05f, -0.75f, -1.05f, 0f, -0.35f)
    cubicTo(0.75f, -1.05f, 1.25f, 0.05f, 0f, 0.85f)
    close()
}
