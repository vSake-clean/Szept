package pl.szept.ui.style

import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.min

/*
 * Kształty dymków jak w oryginalnych aplikacjach:
 *  - iMessage (iOS): zawinięty ogonek na dole ostatniego dymka w serii,
 *  - WhatsApp: „uszko” w górnym rogu pierwszego dymka w serii,
 *  - Telegram / dymki Windows XP: ostry ogonek na dole,
 *  - serie (Google Messages, Messenger): mniejsze rogi po stronie nadawcy między dymkami.
 * Miejsce na ogonek jest zarezerwowane w każdym dymku danego stylu — krawędzie dymków w serii się pokrywają.
 */

/** Szerokość miejsca na ogonek (0 = styl bez ogonków). */
fun StyleSpec.tailSpace(): Dp = when (tailKind) {
    Tail.NONE -> 0.dp
    Tail.IMESSAGE -> 6.dp
    Tail.WHATSAPP -> 8.dp
    Tail.TELEGRAM -> 7.dp
}

/** Kształt dymka: [first]/[last] — czy to pierwszy/ostatni dymek w serii od tej samej osoby. */
fun StyleSpec.bubbleShape(mine: Boolean, first: Boolean = true, last: Boolean = true): Shape {
    val r = radius
    if (tailKind != Tail.NONE) return TailShape(tailKind, mine, r, tailSpace(), first, last)
    val small = 4.dp
    return when {
        cut -> if (mine) CutCornerShape(topStart = r, topEnd = 2.dp, bottomEnd = r, bottomStart = 2.dp)
        else CutCornerShape(topStart = 2.dp, topEnd = r, bottomEnd = 2.dp, bottomStart = r)
        organic -> if (mine) RoundedCornerShape(r, tail, r, r) else RoundedCornerShape(tail, r, r, r)
        grouped -> {
            val top = if (first) r else small
            val bottom = if (last) r else small
            if (mine) RoundedCornerShape(r, top, bottom, r) else RoundedCornerShape(top, r, r, bottom)
        }
        else -> RoundedCornerShape(r, r, if (mine) tail else r, if (mine) r else tail)
    }
}

/** Dymek z ogonkiem (lub bez — wtedy samo „ciało” z miejscem na ogonek). */
private class TailShape(
    val kind: Tail, val mine: Boolean, val radius: Dp, val space: Dp, val first: Boolean, val last: Boolean,
) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): androidx.compose.ui.graphics.Outline {
        val p = with(density) { path(size, radius.toPx(), space.toPx(), density) }
        if (!mine) {
            // lustrzane odbicie: ogonek po lewej
            val m = androidx.compose.ui.graphics.Matrix().apply { scale(-1f, 1f); translate(-size.width, 0f) }
            p.transform(m)
        }
        return androidx.compose.ui.graphics.Outline.Generic(p)
    }

    /** Ścieżka dla dymka z ogonkiem po prawej (moje). */
    private fun path(size: Size, r0: Float, t: Float, d: Density): Path {
        val w = size.width; val h = size.height
        val bw = w - t
        val r = min(r0, min(bw, h) / 2f)
        val px = d.density
        val tailHere = when (kind) { Tail.WHATSAPP -> first; else -> last }
        if (!tailHere || h < 20f * px) {
            return Path().apply { addRoundRect(RoundRect(0f, 0f, bw, h, CornerRadius(r, r))) }
        }
        val k = 0.4477f   // przybliżenie łuku krzywą Béziera
        return Path().apply {
            when (kind) {
                Tail.IMESSAGE -> {
                    moveTo(r, 0f)
                    lineTo(bw - r, 0f)
                    cubicTo(bw - r * k, 0f, bw, r * k, bw, r)
                    lineTo(bw, h - 12f * px)
                    // zewnętrzny łuk ogonka do czubka w prawym dolnym rogu
                    cubicTo(bw, h - 4.5f * px, bw + t * 0.45f, h - 0.8f * px, w, h - 0.2f * px)
                    // od czubka z powrotem — wklęsłe wcięcie i płynne przejście w dolną krawędź
                    cubicTo(w - 3.2f * px, h + 0.6f * px, bw - 0.6f * px, h - 1.1f * px, bw - 3.6f * px, h - 2.6f * px)
                    cubicTo(bw - 5.6f * px, h - 0.7f * px, bw - 8.5f * px, h, bw - 12f * px, h)
                    lineTo(r, h)
                    cubicTo(r * k, h, 0f, h - r * k, 0f, h - r)
                    lineTo(0f, r)
                    cubicTo(0f, r * k, r * k, 0f, r, 0f)
                    close()
                }
                Tail.TELEGRAM -> {
                    moveTo(r, 0f)
                    lineTo(bw - r, 0f)
                    cubicTo(bw - r * k, 0f, bw, r * k, bw, r)
                    lineTo(bw, h - 10f * px)
                    // ostry ogonek: łuk na zewnątrz do czubka, potem płasko wzdłuż dołu
                    cubicTo(bw, h - 3.5f * px, bw + t * 0.6f, h - 0.6f * px, w, h)
                    lineTo(r, h)
                    cubicTo(r * k, h, 0f, h - r * k, 0f, h - r)
                    lineTo(0f, r)
                    cubicTo(0f, r * k, r * k, 0f, r, 0f)
                    close()
                }
                else -> { // WHATSAPP: „uszko” w prawym górnym rogu
                    moveTo(r, 0f)
                    lineTo(w - 1.2f * px, 0f)
                    // czubek uszka (lekko zaokrąglony) i skos w dół do krawędzi dymka
                    quadraticTo(w, 0f, w - 0.8f * px, 1.4f * px)
                    lineTo(bw, t * 1.35f)
                    lineTo(bw, h - r)
                    cubicTo(bw, h - r * k, bw - r * k, h, bw - r, h)
                    lineTo(r, h)
                    cubicTo(r * k, h, 0f, h - r * k, 0f, h - r)
                    lineTo(0f, r)
                    cubicTo(0f, r * k, r * k, 0f, r, 0f)
                    close()
                }
            }
        }
    }
}
