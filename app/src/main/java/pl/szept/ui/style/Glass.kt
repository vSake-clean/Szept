package pl.szept.ui.style

import android.os.Build
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/*
 * Szkło w stylu Liquid Glass (iOS 26):
 *  - rozmycie tego, co jest pod spodem (Android 12+: prawdziwe rozmycie GPU),
 *  - soczewka przy krawędziach (treść spod brzegu „zawija się” do środka — jak w szkle),
 *  - nasycenie barw, delikatne rozjaśnienie, jasny odblask na brzegu i miękki cień.
 * Treść pod szkłem nagrywamy do warstwy graficznej ([Backdrop]) i rysujemy ją ponownie w kształcie szkła.
 */

/** Nagrana treść, nad którą leży szkło. */
@Stable
class Backdrop {
    var layer: GraphicsLayer? = null
    /** Położenie nagranej treści w oknie. */
    var origin: Offset = Offset.Zero
    var ready = false
}

/** Tło, które widać przez szkło (domyślnie: tło stylu; w rozmowie: wiadomości). */
val LocalBackdrop = compositionLocalOf<Backdrop?> { null }

@Composable
fun rememberBackdrop(): Backdrop {
    val layer = rememberGraphicsLayer()
    return remember { Backdrop() }.also { it.layer = layer }
}

/** Nagrywa treść elementu, żeby szkło nad nią mogło ją rozmyć i załamać. */
fun Modifier.backdropSource(b: Backdrop): Modifier =
    onGloballyPositioned { b.origin = it.positionInRoot() }
        .drawWithContent {
            val l = b.layer
            if (l == null) { drawContent(); return@drawWithContent }
            l.record { this@drawWithContent.drawContent() }
            b.ready = true
            drawLayer(l)
        }

/** Wygląd szkła. */
@Stable
class GlassLook(
    /** Rozmycie treści pod spodem. */
    val blur: Dp = 7.dp,
    /** Największe przesunięcie treści przy samej krawędzi (soczewka). */
    val lens: Dp = 12.dp,
    /** Szerokość pasa przy krawędzi, w którym szkło załamuje światło. */
    val band: Dp = 16.dp,
    /** Zabarwienie tafli (np. lekko białe w jasnym motywie). */
    val tint: Color = Color.White.copy(alpha = 0.16f),
    /** Kolor szkła bez rozmycia (starsze Androidy). */
    val fallback: Color = Color.White.copy(alpha = 0.72f),
    val dark: Boolean = false,
    /** Siła cienia pod szkłem (0 = bez). */
    val shadow: Float = 1f,
    /** Nasycenie barw pod szkłem (blisko 1.0 — jak w iOS, żeby szkło nie przejaskrawiało tła). */
    val saturation: Float = 1.12f,
)

private class GlassPos { var pos = Offset.Zero }

/**
 * Kształt szkła z „ciągłymi” rogami (squircle, jak w iOS) — róg to wycinek superelipsy,
 * dzięki czemu zaokrąglenie łagodnie przechodzi w prostą krawędź (bez widocznego „zlania” łuku).
 * [n] steruje pełnością rogu (2 = zwykłe koło, większe = bardziej kwadratowe z miękkim narożnikiem).
 */
private fun squirclePath(size: Size, inset: Float, radius: Float, n: Float = 4.5f): Path {
    val l = inset; val t = inset; val r = size.width - inset; val b = size.height - inset
    val path = Path()
    if (r - l <= 0f || b - t <= 0f) return path
    val rad = max(0f, min(radius - inset, min(r - l, b - t) / 2f))
    if (rad <= 0.5f) { path.addRoundRect(RoundRect(l, t, r, b, CornerRadius(0f, 0f))); return path }
    val exp = 2f / n
    val steps = 14
    fun p(v: Double) = Math.pow(v.coerceIn(0.0, 1.0), exp.toDouble()).toFloat()
    // obrys zgodnie z ruchem wskazówek zegara, zaczynając na górnej krawędzi
    path.moveTo(l + rad, t)
    path.lineTo(r - rad, t)
    run { val cx = r - rad; val cy = t + rad                          // róg prawy-górny
        for (i in 0..steps) { val u = Math.PI / 2 * i / steps; path.lineTo(cx + rad * p(Math.sin(u)), cy - rad * p(Math.cos(u))) } }
    path.lineTo(r, b - rad)
    run { val cx = r - rad; val cy = b - rad                          // róg prawy-dolny
        for (i in 0..steps) { val u = Math.PI / 2 * i / steps; path.lineTo(cx + rad * p(Math.cos(u)), cy + rad * p(Math.sin(u))) } }
    path.lineTo(l + rad, b)
    run { val cx = l + rad; val cy = b - rad                          // róg lewy-dolny
        for (i in 0..steps) { val u = Math.PI / 2 * i / steps; path.lineTo(cx - rad * p(Math.sin(u)), cy + rad * p(Math.cos(u))) } }
    path.lineTo(l, t + rad)
    run { val cx = l + rad; val cy = t + rad                          // róg lewy-górny
        for (i in 0..steps) { val u = Math.PI / 2 * i / steps; path.lineTo(cx - rad * p(Math.cos(u)), cy - rad * p(Math.sin(u))) } }
    path.close()
    return path
}

/**
 * Tafla szkła o zaokrąglonych rogach ([cornerRadius] = null → kapsułka/koło).
 * Rysowana pod treścią elementu (np. ikoną przycisku).
 */
@Composable
fun Modifier.liquidGlass(look: GlassLook, cornerRadius: Dp? = null, backdrop: Backdrop? = LocalBackdrop.current): Modifier {
    // „Rozmycie i przezroczystość” wyłączone → matowa tafla bez nagrywania tła (szybciej)
    val source = if (LocalAnim.current[Anim.BLUR]) backdrop else null
    // odblask na krawędzi przesuwa się, gdy przechylasz telefon (żyroskop)
    val tilt = if (LocalStyle.current.spec.tilt && LocalAnim.current[Anim.TILT]) LocalTilt.current else null
    val layer = rememberGraphicsLayer()
    val holder = remember { GlassPos() }
    return this
        .onGloballyPositioned { holder.pos = it.positionInRoot() }
        .drawWithCache {
            val w = size.width; val h = size.height
            val radius = cornerRadius?.toPx()?.coerceAtMost(min(w, h) / 2f) ?: (min(w, h) / 2f)
            val outline = squirclePath(size, 0f, radius)
            val blurPx = look.blur.toPx()
            val margin = ceil(blurPx * 2.5f + look.lens.toPx() + 2f)
            val band = min(look.band.toPx(), min(w, h) / 2f)
            val lens = look.lens.toPx()
            val rings = 9
            val ringPaths = List(rings) { i -> squirclePath(size, band * i / rings, radius) }
            val center = Offset(w / 2f, h / 2f)
            val canBlur = Build.VERSION.SDK_INT >= 31
            val sat = ColorMatrix().apply { setToSaturation(look.saturation) }
            if (canBlur) {
                layer.renderEffect = if (blurPx > 0.5f) BlurEffect(blurPx, blurPx, TileMode.Clamp) else null
            }
            layer.colorFilter = ColorFilter.colorMatrix(sat)
            val rimColors = listOf(
                Color.White.copy(alpha = if (look.dark) 0.62f else 0.95f), Color.White.copy(alpha = 0.12f),
                Color.White.copy(alpha = 0.05f), Color.White.copy(alpha = if (look.dark) 0.4f else 0.7f),
            )
            val inner = Brush.verticalGradient(
                0f to Color.White.copy(alpha = if (look.dark) 0.07f else 0.22f), 0.45f to Color.Transparent, startY = 0f, endY = h,
            )
            // błysk skupiony w lewym-górnym rogu + słabszy w prawym-dolnym (odbicie światła w szkle, nie równa obwódka)
            val specTop = Brush.linearGradient(
                0f to Color.White.copy(alpha = if (look.dark) 0.5f else 0.92f),
                0.16f to Color.White.copy(alpha = if (look.dark) 0.12f else 0.3f),
                0.46f to Color.Transparent, start = Offset.Zero, end = Offset(w, h),
            )
            val specBottom = Brush.linearGradient(
                0.55f to Color.Transparent,
                0.86f to Color.White.copy(alpha = if (look.dark) 0.05f else 0.16f),
                1f to Color.White.copy(alpha = if (look.dark) 0.16f else 0.42f), start = Offset.Zero, end = Offset(w, h),
            )
            // miękki połysk tuż pod lewym-górnym narożnikiem
            val glow = Brush.radialGradient(
                listOf(Color.White.copy(alpha = if (look.dark) 0.1f else 0.2f), Color.Transparent),
                center = Offset(w * 0.3f, h * 0.22f), radius = max(w, h) * 0.6f,
            )
            onDrawBehind {
                // cień pod taflą (miękki, tylko na zewnątrz)
                if (look.shadow > 0f) softShadow(
                    outline, Color.Black.copy(alpha = (if (look.dark) 0.5f else 0.11f) * look.shadow),
                    20f * density, 0f, 6f * density, Color.Black, true,
                )
                val b = source
                val src = b?.layer
                if (b != null && src != null && b.ready) {
                    // nagranie fragmentu tła (z zapasem na rozmycie) → warstwa z rozmyciem i nasyceniem
                    layer.record(IntSize((w + 2 * margin).toInt(), (h + 2 * margin).toInt())) {
                        translate(b.origin.x - holder.pos.x + margin, b.origin.y - holder.pos.y + margin) { drawLayer(src) }
                    }
                    clipPath(outline) {
                        // soczewka: pierścienie od krawędzi do środka — przy brzegu treść ściśnięta (widać to, co tuż za krawędzią)
                        for (i in 0 until rings) {
                            val k = 1f - i.toFloat() / rings
                            val d = lens * k * k
                            val inset = band * i / rings
                            val hw = w / 2f - inset; val hh = h / 2f - inset
                            val sx = if (hw > 1f) hw / (hw + d) else 1f
                            val sy = if (hh > 1f) hh / (hh + d) else 1f
                            clipPath(ringPaths[i]) {
                                withTransform({ scale(sx, sy, center) }) {
                                    translate(-margin, -margin) { drawLayer(layer) }
                                }
                            }
                        }
                    }
                    if (!canBlur) drawPath(outline, look.fallback.copy(alpha = look.fallback.alpha * 0.55f))
                } else {
                    drawPath(outline, look.fallback)
                }
                drawPath(outline, look.tint)
                drawPath(outline, inner)
                drawPath(outline, glow)
                if (tilt != null) {
                    // światło „ślizga się” po krawędzi zgodnie z przechyleniem
                    val tx = tilt.x; val ty = tilt.y
                    val sx = w * 0.55f * tx; val sy = h * 0.55f * ty
                    drawPath(outline, Brush.linearGradient(rimColors, Offset(sx, sy), Offset(w + sx, h + sy)), style = Stroke(1.2f * density))
                    drawPath(outline, Brush.radialGradient(
                        listOf(Color.White.copy(alpha = if (look.dark) 0.1f else 0.22f), Color.Transparent),
                        Offset(w * (0.5f - 0.45f * tx), h * (0.15f - 0.4f * ty)), max(w, h) * 0.7f,
                    ))
                } else {
                    drawPath(outline, specTop, style = Stroke(1.4f * density))
                    drawPath(outline, specBottom, style = Stroke(1f * density))
                }
            }
        }
}

/** Szklany okrągły przycisk / kapsułka z treścią na środku. */
@Composable
fun GlassBox(look: GlassLook, modifier: Modifier = Modifier, cornerRadius: Dp? = null, content: @Composable BoxScope.() -> Unit) {
    Box(modifier.liquidGlass(look, cornerRadius), contentAlignment = androidx.compose.ui.Alignment.Center, content = content)
}

/** Szkło dla bieżącego stylu (jasne lub ciemne). */
fun glassLookFor(dark: Boolean, tinted: Color? = null): GlassLook = if (tinted != null) GlassLook(
    tint = tinted.copy(alpha = 0.78f), fallback = tinted.copy(alpha = 0.92f), dark = dark, saturation = 1.08f,
) else if (dark) GlassLook(
    tint = Color(0xFF1C1C1E).copy(alpha = 0.32f), fallback = Color(0xFF2C2C2E).copy(alpha = 0.86f), dark = true,
) else GlassLook()

val GlassCircle = CircleShape
val GlassCapsule = RoundedCornerShape(50)
