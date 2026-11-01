package pl.szept.ui.style

import android.os.Build
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp

// ======================= cienie i poświata =======================

/**
 * Miękki cień kształtu. Android 9+: prawdziwe rozmycie (setShadowLayer), starsze: kilka coraz większych
 * półprzezroczystych kopii. [outsideOnly] — cień tylko na zewnątrz (pod przezroczystym szkłem nie ciemnieje).
 */
internal fun DrawScope.softShadow(path: Path, color: Color, blur: Float, dx: Float, dy: Float, fill: Color, outsideOnly: Boolean) {
    if (color.alpha <= 0f) return
    if (Build.VERSION.SDK_INT >= 28) {
        val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            this.color = (if (outsideOnly || fill.alpha < 0.99f) Color.Black else fill).toArgb()
            setShadowLayer(blur.coerceAtLeast(0.5f), dx, dy, color.copy(alpha = color.alpha.coerceAtMost(0.99f)).toArgb())
        }
        val np = path.asAndroidPath()
        drawIntoCanvas { cv ->
            val c = cv.nativeCanvas
            c.save()
            // wnętrze kształtu zostaje czyste — rysujemy tylko cień dookoła (wypełnienie dokłada wywołujący)
            c.clipOutPath(np)
            c.drawPath(np, p)
            c.restore()
        }
    } else {
        val b = path.getBounds()
        if (b.width <= 0f || b.height <= 0f) return
        val col = color.copy(alpha = color.alpha * 0.26f)
        clipPath(path, ClipOp.Difference) {
            for (i in 3 downTo 0) {
                val grow = blur * (i + 1) / 4f
                withTransform({
                    translate(dx, dy)
                    scale((b.width + grow * 2) / b.width, (b.height + grow * 2) / b.height, pivot = b.center)
                }) { drawPath(path, col) }
            }
        }
    }
}

/** Świecąca (neonowa) obwódka. */
internal fun DrawScope.glowStroke(path: Path, color: Color, width: Float, blur: Float) {
    if (color.alpha <= 0f) return
    if (Build.VERSION.SDK_INT >= 28) {
        val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = width
            this.color = color.toArgb()
            setShadowLayer(blur, 0f, 0f, color.toArgb())
        }
        drawIntoCanvas { it.nativeCanvas.drawPath(path.asAndroidPath(), p) }
    } else {
        for (i in 3 downTo 1) drawPath(path, color.copy(alpha = 0.13f), style = Stroke(width + blur * i / 1.6f))
    }
    drawPath(path, color, style = Stroke(width))
}

// ======================= powierzchnie =======================

/** Ogonek dymka w stylu komiksu (pod dolnym rogiem). */
private fun tailPath(size: Size, mine: Boolean, d: Density): Path = with(d) {
    val h = 10.dp.toPx(); val w0 = 14.dp.toPx()
    val x = if (mine) size.width - 18.dp.toPx() else 18.dp.toPx()
    val tip = if (mine) x + 10.dp.toPx() else x - 10.dp.toPx()
    Path().apply {
        moveTo(x - w0 / 2f, size.height - 2.dp.toPx())
        lineTo(tip, size.height + h)
        lineTo(x + w0 / 2f, size.height - 2.dp.toPx())
        close()
    }
}

/** Rysuje jedną powierzchnię w stylu — przygotowane raz dla rozmiaru, rysowane w każdej klatce. */
private class SurfacePainter(val st: StyleState, val role: Role, val f: Fill, val path: Path, val size: Size, val d: Density) {
    val px = d.density
    val small = role == Role.CHIP
    val base = st.pal.base
    val vertical = st.surf == Surf.GLOSSY
    val fillBrush: Brush? = f.grad?.let {
        if (vertical) Brush.verticalGradient(it, 0f, size.height)
        else Brush.linearGradient(it, Offset.Zero, Offset(size.width * 0.4f, size.height))
    }
    val bubble = role == Role.MINE || role == Role.THEIRS
    val tail: Path? = if (st.spec.comicTail && bubble) tailPath(size, role == Role.MINE, d) else null
    val sheen = Brush.verticalGradient(
        0f to Color.White.copy(alpha = if (st.dark) 0.12f else 0.3f), 0.55f to Color.Transparent, startY = 0f, endY = size.height,
    )
    val holo = Brush.sweepGradient(
        listOf(Color(0xFFFF9AD5), Color(0xFF9AD8FF), Color(0xFFB8FF9A), Color(0xFFFFE89A), Color(0xFFD7A6FF), Color(0xFFFF9AD5)),
        Offset(size.width / 2f, size.height / 2f),
    )
    val chalk = PathEffect.dashPathEffect(floatArrayOf(14f * px, 1.6f * px, 6f * px, 1.2f * px))

    fun DrawScope.fill() {
        when {
            fillBrush != null -> drawPath(path, fillBrush)
            f.color.alpha > 0f -> drawPath(path, f.color)
        }
    }

    fun DrawScope.outline(width: Float) { if (f.line.alpha > 0f) drawPath(path, f.line, style = Stroke(width)) }

    fun draw(s: DrawScope, tilt: TiltState?) = with(s) {
        when (st.surf) {
            Surf.FLAT -> { fill(); outline(1f * px) }
            Surf.GLASS -> {
                softShadow(path, Color.Black.copy(alpha = if (st.dark) 0.4f else 0.12f), (if (small) 6f else 14f) * px, 0f, (if (small) 2f else 5f) * px, f.color, true)
                fill()
                val tx = tilt?.x ?: 0f; val ty = tilt?.y ?: 0f
                clipPath(path) {
                    drawRect(sheen)
                    // odblask światła — przesuwa się, gdy przechylasz telefon
                    val c = Offset(size.width * (0.22f - tx * 0.25f), size.height * (0.05f - ty * 0.3f))
                    val r = maxOf(size.minDimension * 0.9f, 40f * px)
                    drawCircle(Brush.radialGradient(listOf(Color.White.copy(alpha = if (st.dark) 0.16f else 0.32f), Color.Transparent), c, r), r, c)
                }
                val rim = Brush.linearGradient(
                    listOf(
                        Color.White.copy(alpha = if (st.dark) 0.55f else 0.95f), Color.White.copy(alpha = 0.12f),
                        Color.White.copy(alpha = 0.04f), Color.White.copy(alpha = if (st.dark) 0.3f else 0.6f),
                    ),
                    Offset(size.width * (0f - tx * 0.3f), size.height * (0f - ty * 0.3f)), Offset(size.width * (1f - tx * 0.3f), size.height * (1f - ty * 0.3f)),
                )
                drawPath(path, rim, style = Stroke(1.2f * px))
                if (st.spec.iridescent) {
                    rotate(tx * 40f, Offset(size.width / 2f, size.height / 2f)) {
                        drawPath(path, holo, alpha = if (st.dark) 0.55f else 0.85f, style = Stroke(1.7f * px))
                    }
                }
                if (f.line.alpha > 0f) outline(1f * px)
            }
            Surf.FROST -> {
                softShadow(path, Color.Black.copy(alpha = if (st.dark) 0.45f else 0.1f), (if (small) 8f else 18f) * px, 0f, (if (small) 2f else 6f) * px, f.color, true)
                fill(); outline(1f * px)
            }
            Surf.SOFT -> {
                val o = (if (small) 3f else if (role == Role.INPUT) 4f else 5f) * px
                val b = (if (small) 6f else 11f) * px
                softShadow(path, st.pal.light, b, -o, -o, base, false)
                softShadow(path, st.pal.shade, b, o, o, base, false)
                drawPath(path, base)
                fill()
            }
            Surf.BRUTAL -> {
                val o = (if (small) 2f else 4f) * px
                translate(o, o) {
                    drawPath(path, st.pal.shade)
                    tail?.let { drawPath(it, st.pal.shade) }
                }
                tail?.let { drawPath(it, if (fillBrush == null) f.color else f.grad!!.last()) }
                fill()
                val sw = (if (small) 1.5f else 2.2f) * px
                tail?.let { drawPath(it, f.line, style = Stroke(sw)) }
                outline(sw)
                // spoina ogonka z dymkiem — bez kreski
                tail?.let { t ->
                    val b = t.getBounds()
                    drawRect(if (fillBrush == null) f.color else f.grad!!.last(), Offset(b.left + sw, size.height - sw * 1.4f), Size(b.width - sw * 2f, sw * 1.2f))
                }
            }
            Surf.CLAY -> {
                softShadow(path, st.pal.shade, (if (small) 6f else 16f) * px, 0f, (if (small) 3f else 8f) * px, f.solid(base), false)
                fill()
                clipPath(path) {
                    drawRect(Brush.linearGradient(listOf(Color.White.copy(alpha = if (st.dark) 0.14f else 0.6f), Color.Transparent), Offset.Zero, Offset(size.width * 0.5f, size.height * 0.7f)))
                    drawRect(Brush.linearGradient(listOf(Color.Transparent, Color.Black.copy(alpha = if (st.dark) 0.25f else 0.08f)), Offset(size.width * 0.45f, size.height * 0.35f), Offset(size.width, size.height)))
                }
                outline(1.5f * px)
            }
            Surf.NEON -> {
                fill()
                glowStroke(path, f.line, 1.4f * px, (if (small) 5f else 9f) * px)
            }
            Surf.GLOSSY -> {
                softShadow(path, st.pal.shade, 3f * px, 0f, 1.5f * px, f.solid(base), false)
                fill()
                clipPath(path) {
                    val inset = 2.5f * px
                    drawRoundRect(
                        Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.55f), Color.White.copy(alpha = 0.06f)), inset, size.height * 0.5f),
                        Offset(inset, inset), Size(size.width - inset * 2, size.height * 0.48f), CornerRadius(12f * px),
                    )
                }
                outline(0.8f * px)
            }
            Surf.NOTE -> {
                softShadow(path, st.pal.shade, (if (small) 2f else 5f) * px, 0f, (if (small) 1f else 2f) * px, f.solid(base), false)
                fill()
                outline(1f * px)
                if (st.spec.tape && role == Role.MINE && size.width > 60f * px) {
                    // kawałek taśmy klejącej na karteczce
                    rotate(-4f, Offset(size.width / 2f, 0f)) {
                        drawRect(Color(0xFFF3E9C9).copy(alpha = 0.75f), Offset(size.width / 2f - 22f * px, -6f * px), Size(44f * px, 13f * px))
                    }
                }
            }
            Surf.CHALK -> {
                fill()
                if (f.line.alpha > 0f) drawPath(path, f.line, style = Stroke(1.7f * px, pathEffect = chalk, cap = StrokeCap.Round))
            }
            Surf.BEVEL -> {
                fill()
                bevel(this, size, raised = role != Role.INPUT && role != Role.THEIRS && role != Role.MINE, px = px)
            }
        }
    }
}

/** Ramka 3D jak w okienkach z lat 90. (wypukła albo wklęsła). */
internal fun bevel(s: DrawScope, size: Size, raised: Boolean, px: Float) = with(s) {
    val w = size.width; val h = size.height
    val lt = if (raised) Color.White else Color(0xFF808080)
    val rb = if (raised) Color.Black else Color.White
    val lt2 = if (raised) Color(0xFFDFDFDF) else Color.Black
    val rb2 = if (raised) Color(0xFF808080) else Color(0xFFDFDFDF)
    val t = 1.4f * px
    drawRect(lt, Offset.Zero, Size(w, t)); drawRect(lt, Offset.Zero, Size(t, h))
    drawRect(rb, Offset(0f, h - t), Size(w, t)); drawRect(rb, Offset(w - t, 0f), Size(t, h))
    drawRect(lt2, Offset(t, t), Size(w - 2 * t, t)); drawRect(lt2, Offset(t, t), Size(t, h - 2 * t))
    drawRect(rb2, Offset(t, h - 2 * t), Size(w - 2 * t, t)); drawRect(rb2, Offset(w - 2 * t, t), Size(t, h - 2 * t))
}

/** Tło powierzchni w danym stylu (cienie, wypełnienie, obwódka, odblaski). Nie przycina treści. */
fun Modifier.styleSurface(st: StyleState, role: Role, shape: Shape, tilt: TiltState? = null, fill: Fill? = null): Modifier =
    drawWithCache {
        val path = Path().apply { addOutline(shape.createOutline(size, layoutDirection, this@drawWithCache)) }
        val p = SurfacePainter(st, role, fill ?: st.fill(role), path, size, this)
        onDrawBehind { p.draw(this, tilt) }
    }

/** Wersja korzystająca z bieżącego stylu. */
@Composable
fun Modifier.styled(role: Role, shape: Shape): Modifier {
    val st = LocalStyle.current
    val tilt = if (st.spec.tilt) LocalTilt.current else null
    return styleSurface(st, role, shape, tilt)
}

/** Tło górnego paska (pod paskiem stanu też) — jak w danym systemie. */
fun Modifier.styleTopBar(st: StyleState): Modifier = drawBehind {
    val f = st.pal.bar
    val px = density
    val y = size.height
    when (st.spec.chrome) {
        Chrome.GLASS -> return@drawBehind
        Chrome.WIN7 -> { aeroBar(st.pal); return@drawBehind }
        else -> {}
    }
    val g = f.grad
    if (g != null) drawRect(Brush.verticalGradient(g)) else if (f.color.alpha > 0f) drawRect(f.color)
    when (st.spec.chrome) {
        Chrome.IOS6 -> {
            drawRect(Color.White.copy(alpha = 0.55f), Offset.Zero, Size(size.width, px))
            drawRect(Color(0xFF2D3B52), Offset(0f, y - px), Size(size.width, px))
        }
        Chrome.WINXP -> {
            drawRect(Color(0xFF8CC0FF).copy(alpha = 0.8f), Offset.Zero, Size(size.width, px))
            drawRect(Color(0xFF0035A8), Offset(0f, y - px), Size(size.width, px))
        }
        Chrome.WIN95 -> drawRect(Color(0xFF808080), Offset(0f, y - px), Size(size.width, px))
        Chrome.TELEGRAM, Chrome.WHATSAPP -> drawRect(
            Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.12f), Color.Transparent), y - 0.5f * px, y + 3f * px),
            Offset(0f, y - 0.5f * px), Size(size.width, 3.5f * px),
        )
        Chrome.MESSENGER, Chrome.TERMINAL -> drawRect(st.pal.line.copy(alpha = 0.6f), Offset(0f, y - px * 0.6f), Size(size.width, px * 0.6f))
        else -> if (f.line.alpha > 0f) drawRect(f.line, Offset(0f, y - px), Size(size.width, px))
    }
}

/** Pasek wpisywania wiadomości w stylu systemu (iOS 6, Windows, Telegram…). */
@Composable
fun Modifier.styleInputBar(): Modifier = styleInputBar(LocalStyle.current)

/** Pasek pola wiadomości dla podanego stylu (np. w podglądzie stylu). */
fun Modifier.styleInputBar(st: StyleState): Modifier {
    return when (st.spec.chrome) {
        Chrome.MATERIAL, Chrome.GLASS, Chrome.WHATSAPP, Chrome.WIN11 -> this
        else -> this.drawBehind {
            val px = density
            when (st.spec.chrome) {
                Chrome.WIN95 -> { drawRect(st.pal.base); bevel(this, size, raised = true, px = px) }
                Chrome.IOS6 -> {
                    drawRect(Brush.verticalGradient(listOf(Color(0xFFE9ECF0), Color(0xFFCDD3DB))))
                    drawRect(Color(0xFF8E98A6), Offset.Zero, Size(size.width, px))
                }
                Chrome.WINXP -> { drawRect(st.pal.base); drawRect(Color.White, Offset.Zero, Size(size.width, px)) }
                Chrome.WIN7 -> {
                    drawRect(Brush.verticalGradient(listOf(Color(0xFFF7F9FC), Color(0xFFE3E9F1))))
                    drawRect(Color(0xFFB9C6D6), Offset.Zero, Size(size.width, px))
                }
                Chrome.TELEGRAM -> drawRect(if (st.dark) Color(0xFF17212B) else Color.White)
                Chrome.MESSENGER, Chrome.ONEUI -> drawRect(st.pal.base)
                Chrome.EXPRESSIVE -> {}
                Chrome.TERMINAL -> { drawRect(Color.Black); drawRect(st.pal.line, Offset.Zero, Size(size.width, px)) }
                else -> {}
            }
        }
    }
}

/** Karta (np. rozmowa na liście) — pusty modyfikator w stylach bez kart. */
@Composable
fun Modifier.styleCard(): Modifier {
    val st = LocalStyle.current
    if (st.pal.card == null) return this
    return this.padding(horizontal = 10.dp, vertical = 4.dp).styled(Role.CARD, st.spec.cardShape()).clipDraw(st.spec.cardShape())
}

/** Przycina tylko rysowanie (nie obszar dotyku — ten zostaje prostokątny). */
fun Modifier.clipDraw(shape: Shape): Modifier = drawWithCache {
    val path = Path().apply { addOutline(shape.createOutline(size, layoutDirection, this@drawWithCache)) }
    onDrawWithContent { clipPath(path) { this@onDrawWithContent.drawContent() } }
}

// ======================= dymki =======================

/** Kolory wewnątrz dymka/paska — tak, by wszystko w środku było czytelne na jego tle. */
fun ColorScheme.inside(fill: Color, text: Color): ColorScheme {
    val prim = if (contrast(primary, fill) >= 3f) primary else readable(text, fill, 4.5f)
    return copy(
        primary = prim, onPrimary = if (contrast(fill, prim) >= 3f) fill else onPrimary,
        surface = lerp(fill, if (fill.luminance() > 0.5f) Color.White else Color.Black, 0.35f),
        onSurface = text, onBackground = text, onSurfaceVariant = lerp(fill, text, 0.72f),
        surfaceVariant = lerp(fill, text, 0.1f), secondaryContainer = lerp(fill, text, 0.12f), onSecondaryContainer = text,
        primaryContainer = lerp(fill, prim, 0.22f), onPrimaryContainer = text,
        outline = lerp(fill, text, 0.45f), outlineVariant = lerp(fill, text, 0.2f),
    )
}

@Composable
fun BubbleTheme(fill: Color, text: Color, content: @Composable () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val adapted = remember(cs, fill, text) { cs.inside(fill, text) }
    MaterialTheme(colorScheme = adapted, typography = MaterialTheme.typography, shapes = MaterialTheme.shapes) {
        CompositionLocalProvider(LocalContentColor provides text, content = content)
    }
}

/**
 * Dymek wiadomości w bieżącym stylu (z animacją wciśnięcia).
 * [first]/[last] — położenie w serii dymków od tej samej osoby (ogonki, zaokrąglenia).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun StyledBubble(
    mine: Boolean,
    modifier: Modifier = Modifier,
    border: BorderStroke? = null,
    first: Boolean = true,
    last: Boolean = true,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val st = LocalStyle.current
    val role = if (mine) Role.MINE else Role.THEIRS
    val shape = remember(st.spec, mine, first, last) { st.spec.bubbleShape(mine, first, last) }
    val f = st.fill(role)
    val src = remember { MutableInteractionSource() }
    val anim = LocalAnim.current
    val tilt = if (st.spec.tilt) LocalTilt.current else null
    val tail = st.spec.tailSpace()
    val clickable = onClick != null || onLongClick != null
    // Messenger: kolor moich dymków zależy od ich miejsca na ekranie (przesuwa się przy przewijaniu)
    val gradient = if (mine && st.spec.gradientMine) f.grad else null
    val posY = remember { mutableFloatStateOf(0f) }
    val screenH = androidx.compose.ui.platform.LocalView.current.height.toFloat().coerceAtLeast(1f)
    Box(
        modifier
            .then(if (st.spec.comicTail) Modifier.padding(bottom = 9.dp) else Modifier)
            .pressScale(src, anim[Anim.PRESS] && clickable)
            // dotyk na całym prostokącie dymka (przycięcie niżej dotyczy tylko rysowania)
            .then(
                if (clickable) Modifier.combinedClickable(
                    interactionSource = src, indication = null, onClick = onClick ?: {}, onLongClick = onLongClick,
                ) else Modifier,
            )
            .then(
                if (gradient != null) Modifier
                    .onGloballyPositioned { posY.floatValue = it.positionInWindow().y }
                    .drawWithCache {
                        val path = Path().apply { addOutline(shape.createOutline(size, layoutDirection, this@drawWithCache)) }
                        onDrawBehind {
                            val y = posY.floatValue
                            drawPath(path, Brush.verticalGradient(gradient, -y, screenH - y))
                        }
                    }
                else Modifier.styleSurface(st, role, shape, tilt),
            )
            .then(if (border != null) Modifier.border(border, shape) else Modifier)
            .clipDraw(shape)
            .then(if (clickable) Modifier.indication(src, ripple()) else Modifier)
            .padding(start = if (mine) 0.dp else tail, end = if (mine) tail else 0.dp),
    ) {
        BubbleTheme(f.solid(st.pal.base), f.text, content)
    }
}

/** Mała „pigułka” w stylu (nagłówek dnia, komunikat systemowy). */
@Composable
fun StyledChip(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val st = LocalStyle.current
    val shape = remember(st.spec) { st.spec.chipShape() }
    Box(modifier.styled(Role.CHIP, shape).clip(shape)) {
        BubbleTheme(st.pal.chip.solid(st.pal.base), st.pal.chip.text, content)
    }
}

/** Kolor tekstu na pasku stylu (np. biały na granatowym pasku Retro 95). */
@Composable
fun barText(): Color = LocalStyle.current.pal.bar.text

/** Prostokątny kształt dla pasków. */
val BarShape: Shape = RectangleShape

/** Kolory górnego paska w stylu (tło rysuje [styleTopBar], tu tylko przezroczysty kontener i kolory treści). */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun styleBarColors(): androidx.compose.material3.TopAppBarColors {
    val st = LocalStyle.current
    val cs = MaterialTheme.colorScheme
    val plain = st.pal.bar.solid(st.pal.base) == st.pal.base
    val text = if (plain) cs.onSurface else st.pal.bar.text
    return androidx.compose.material3.TopAppBarDefaults.topAppBarColors(
        containerColor = Color.Transparent, scrolledContainerColor = Color.Transparent,
        titleContentColor = text, navigationIconContentColor = text,
        actionIconContentColor = if (plain) cs.onSurfaceVariant else st.pal.bar.text,
    )
}

/** Kolor czytelny na pasku stylu (np. zielony „Online” na granatowym pasku). */
@Composable
fun onBar(c: Color, min: Float = 3.5f): Color {
    val st = LocalStyle.current
    return readable(c, st.pal.bar.solid(st.pal.base), min)
}

/** Kolor drugorzędnego tekstu na pasku stylu. */
@Composable
fun barMuted(): Color {
    val st = LocalStyle.current
    val plain = st.pal.bar.solid(st.pal.base) == st.pal.base
    return if (plain) MaterialTheme.colorScheme.onSurfaceVariant else st.pal.bar.text.copy(alpha = 0.8f)
}
