package pl.szept.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.PointF
import android.graphics.Rect
import android.media.FaceDetector
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.BlurOn
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Button
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import pl.szept.core.Core
import pl.szept.core.MediaUtil
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Operacje na obrazie (niezależne od interfejsu — łatwe do przetestowania). */
object ImageOps {
    fun rotate90(src: Bitmap): Bitmap {
        val m = Matrix().apply { postRotate(90f) }
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true).let { if (it.isMutable) it else it.copy(Bitmap.Config.ARGB_8888, true) }
    }

    fun crop(src: Bitmap, r: Rect): Bitmap {
        val rr = Rect(r).apply { if (!intersect(0, 0, src.width, src.height)) set(0, 0, src.width, src.height) }
        if (rr.width() < 8 || rr.height() < 8) return src
        return Bitmap.createBitmap(src, rr.left, rr.top, rr.width(), rr.height()).copy(Bitmap.Config.ARGB_8888, true)
    }

    /** Zamazuje (pikselizuje) obszar — nieodwracalnie, więc bezpieczniej niż zwykłe rozmycie. */
    fun pixelate(bmp: Bitmap, r: Rect) {
        val rr = Rect(r)
        if (!rr.intersect(0, 0, bmp.width, bmp.height) || rr.width() < 4 || rr.height() < 4) return
        val block = max(6, min(rr.width(), rr.height()) / 8)
        val part = Bitmap.createBitmap(bmp, rr.left, rr.top, rr.width(), rr.height())
        val small = Bitmap.createScaledBitmap(part, max(1, rr.width() / block), max(1, rr.height() / block), true)
        val big = Bitmap.createScaledBitmap(small, rr.width(), rr.height(), false)
        android.graphics.Canvas(bmp).drawBitmap(big, rr.left.toFloat(), rr.top.toFloat(), null)
    }

    /** Wykrywa twarze (wbudowany w Androida detektor, bez internetu). Zwraca prostokąty w pikselach obrazu. */
    fun findFaces(bmp: Bitmap, max: Int = 12): List<Rect> {
        val scale = min(1f, 800f / bmp.width)
        var w = (bmp.width * scale).toInt(); if (w % 2 == 1) w--
        val h = (bmp.height * scale).toInt()
        if (w < 16 || h < 16) return emptyList()
        val small = Bitmap.createScaledBitmap(bmp, w, h, true).copy(Bitmap.Config.RGB_565, false)
        val faces = arrayOfNulls<FaceDetector.Face>(max)
        val n = try { FaceDetector(w, h, max).findFaces(small, faces) } catch (t: Throwable) { 0 }
        val p = PointF()
        return (0 until n).mapNotNull { i ->
            val f = faces[i] ?: return@mapNotNull null
            f.getMidPoint(p)
            val ed = f.eyesDistance()
            Rect(
                ((p.x - ed * 1.4f) / scale).toInt(), ((p.y - ed * 1.5f) / scale).toInt(),
                ((p.x + ed * 1.4f) / scale).toInt(), ((p.y + ed * 2.1f) / scale).toInt(),
            )
        }
    }

    fun drawStroke(bmp: Bitmap, pts: List<Offset>, color: Int, width: Float) {
        if (pts.isEmpty()) return
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color; strokeWidth = width; style = android.graphics.Paint.Style.STROKE
            strokeCap = android.graphics.Paint.Cap.ROUND; strokeJoin = android.graphics.Paint.Join.ROUND
        }
        val c = android.graphics.Canvas(bmp)
        if (pts.size == 1) { paint.style = android.graphics.Paint.Style.FILL; c.drawCircle(pts[0].x, pts[0].y, width / 2, paint); return }
        val path = android.graphics.Path().apply { moveTo(pts[0].x, pts[0].y); pts.drop(1).forEach { lineTo(it.x, it.y) } }
        c.drawPath(path, paint)
    }
}

private class Stroke2(val color: Color, val width: Float, val pts: MutableList<Offset>)

private enum class Tool { NONE, CROP, DRAW, BLUR }

private val PEN_COLORS = listOf(Color.White, Color.Black, Color(0xFFE53935), Color(0xFFFFD600), Color(0xFF43A047), Color(0xFF1E88E5), Color(0xFFD81B60))

/**
 * Edytor zdjęcia przed wysłaniem: kadrowanie, obrót, rysowanie palcem, zamazywanie fragmentów
 * i automatyczne zamazanie twarzy. Wszystko dzieje się w telefonie.
 */
@Composable
fun ImageEditorDialog(src: File, onCancel: () -> Unit, startRatio: Float? = null, onDone: (Core.Attachment) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var bmp by remember { mutableStateOf<Bitmap?>(null) }
    val history = remember { ArrayDeque<Bitmap>() }
    var version by remember { mutableIntStateOf(0) }
    var tool by remember { mutableStateOf(if (startRatio != null) Tool.CROP else Tool.NONE) }
    val strokes = remember { mutableStateListOf<Stroke2>() }
    var current by remember { mutableStateOf<Stroke2?>(null) }
    var penColor by remember { mutableStateOf(PEN_COLORS[2]) }
    var penWidth by remember { mutableFloatStateOf(12f) }
    var blurStart by remember { mutableStateOf<Offset?>(null) }
    var blurEnd by remember { mutableStateOf<Offset?>(null) }
    // kadr jako ułamki szerokości/wysokości obrazu
    var crop by remember { mutableStateOf(androidx.compose.ui.geometry.Rect(0f, 0f, 1f, 1f)) }
    var cropRatio by remember { mutableStateOf<Float?>(null) }
    var box by remember { mutableStateOf(IntSize.Zero) }
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(src) {
        bmp = withContext(Dispatchers.IO) {
            BitmapFactory.decodeFile(src.absolutePath)?.copy(Bitmap.Config.ARGB_8888, true)
        }
        if (bmp == null) { toast(ctx, "Nie można otworzyć zdjęcia"); onCancel() }
    }
    val b = bmp ?: run {
        Dialog(onDismissRequest = onCancel) { CircularProgressIndicator() }
        return
    }

    fun pushHistory() {
        history.addLast(b.copy(Bitmap.Config.ARGB_8888, true))
        while (history.size > 4) history.removeFirst()
    }

    fun commitStrokes() {
        if (strokes.isEmpty()) return
        pushHistory()
        strokes.forEach { s -> ImageOps.drawStroke(b, s.pts, s.color.toArgb(), s.width) }
        strokes.clear(); version++
    }

    // dopasowanie obrazu do okna
    val scale = if (box.width == 0) 1f else min(box.width.toFloat() / b.width, box.height.toFloat() / b.height)
    val ox = (box.width - b.width * scale) / 2
    val oy = (box.height - b.height * scale) / 2
    fun toImg(p: Offset) = Offset(((p.x - ox) / scale).coerceIn(0f, b.width.toFloat()), ((p.y - oy) / scale).coerceIn(0f, b.height.toFloat()))

    var ratioApplied by remember { mutableStateOf(false) }
    fun applyRatio(r: Float?) {
        cropRatio = r
        if (r == null) { crop = androidx.compose.ui.geometry.Rect(0f, 0f, 1f, 1f); return }
        // największy wyśrodkowany kadr o proporcji r (szer./wys.)
        val imgRatio = b.width.toFloat() / b.height
        crop = if (imgRatio > r) {
            val w = r / imgRatio; androidx.compose.ui.geometry.Rect((1 - w) / 2, 0f, (1 + w) / 2, 1f)
        } else {
            val h = imgRatio / r; androidx.compose.ui.geometry.Rect(0f, (1 - h) / 2, 1f, (1 + h) / 2)
        }
    }

    LaunchedEffect(Unit) { if (startRatio != null && !ratioApplied) { ratioApplied = true; applyRatio(startRatio) } }

    /** Zastosuj ustawiony kadr (także automatycznie przy „Gotowe” i przy zmianie narzędzia). */
    fun applyCrop(): Bitmap {
        if (tool != Tool.CROP) return b
        val c = crop
        tool = Tool.NONE
        if (c.left <= 0.001f && c.top <= 0.001f && c.right >= 0.999f && c.bottom >= 0.999f) return b
        pushHistory()
        val r = Rect((c.left * b.width).toInt(), (c.top * b.height).toInt(), (c.right * b.width).toInt(), (c.bottom * b.height).toInt())
        val out = ImageOps.crop(b, r)
        bmp = out; version++
        return out
    }

    fun setTool(t: Tool) {
        commitStrokes()
        if (tool == Tool.CROP) { applyCrop(); if (t == Tool.CROP) return }
        tool = if (tool == t) Tool.NONE else t
        if (tool == Tool.CROP) { crop = androidx.compose.ui.geometry.Rect(0f, 0f, 1f, 1f); cropRatio = null }
    }

    Dialog(onDismissRequest = onCancel, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(color = Color.Black, modifier = Modifier.fillMaxSize()) {
            // safeDrawingPadding: przyciski nie wchodzą pod pasek stanu / pasek nawigacji
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onCancel) { Icon(Icons.Default.Close, "Anuluj", tint = Color.White) }
                    Spacer(Modifier.weight(1f))
                    if (busy) CircularProgressIndicator(Modifier.size(22.dp).padding(end = 8.dp), color = Color.White)
                    Button(enabled = !busy, onClick = {
                        commitStrokes()
                        val b = applyCrop()
                        busy = true
                        scope.launch {
                            val att = try {
                                withContext(Dispatchers.IO) {
                                    val out = File(ctx.cacheDir, "edit_${System.nanoTime()}.jpg")
                                    out.outputStream().use { b.compress(Bitmap.CompressFormat.JPEG, 85, it) }
                                    Core.Attachment(out, "image/jpeg", "zdjecie.jpg", MediaUtil.thumbB64(b, 48, 40), b.width, b.height, 0)
                                }
                            } catch (e: Exception) {
                                if (e is kotlinx.coroutines.CancellationException) throw e
                                toast(ctx, "Nie udało się zapisać zdjęcia"); null
                            }
                            busy = false
                            att?.let(onDone)
                        }
                    }, modifier = Modifier.padding(end = 8.dp)) { Text("Gotowe") }
                }
                // margines wokół zdjęcia: narożniki kadru nie leżą przy krawędzi ekranu (gest „wstecz”)
                Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp).systemGestureExclusion().onSizeChanged { box = it }) {
                    val img = remember(b, version) { b.asImageBitmap() }
                    Canvas(
                        Modifier.fillMaxSize().pointerInput(tool, b, version, box) {
                            when (tool) {
                                Tool.DRAW -> detectDragGestures(
                                    onDragStart = { p -> current = Stroke2(penColor, penWidth / scale * 2.2f, mutableStateListOf(toImg(p))) },
                                    onDrag = { ch, _ -> current?.pts?.add(toImg(ch.position)); ch.consume() },
                                    onDragEnd = { current?.let { strokes.add(it) }; current = null },
                                    onDragCancel = { current = null },
                                )
                                Tool.BLUR -> detectDragGestures(
                                    onDragStart = { p -> blurStart = toImg(p); blurEnd = blurStart },
                                    onDrag = { ch, _ -> blurEnd = toImg(ch.position); ch.consume() },
                                    onDragEnd = {
                                        val s = blurStart; val e = blurEnd
                                        if (s != null && e != null && abs(s.x - e.x) > 8 && abs(s.y - e.y) > 8) {
                                            pushHistory()
                                            ImageOps.pixelate(b, Rect(min(s.x, e.x).toInt(), min(s.y, e.y).toInt(), max(s.x, e.x).toInt(), max(s.y, e.y).toInt()))
                                            version++
                                        }
                                        blurStart = null; blurEnd = null
                                    },
                                    onDragCancel = { blurStart = null; blurEnd = null },
                                )
                                Tool.CROP -> {
                                    var mode = 0 // 1..4 = narożnik, 5 = przesuwanie
                                    detectDragGestures(
                                        onDragStart = { p ->
                                            val ip = toImg(p); val fx = ip.x / b.width; val fy = ip.y / b.height
                                            val tol = 40.dp.toPx() / scale / b.width
                                            mode = when {
                                                abs(fx - crop.left) < tol && abs(fy - crop.top) < tol * b.width / b.height -> 1
                                                abs(fx - crop.right) < tol && abs(fy - crop.top) < tol * b.width / b.height -> 2
                                                abs(fx - crop.left) < tol && abs(fy - crop.bottom) < tol * b.width / b.height -> 3
                                                abs(fx - crop.right) < tol && abs(fy - crop.bottom) < tol * b.width / b.height -> 4
                                                crop.contains(Offset(fx, fy)) -> 5
                                                // poza kadrem: chwytamy najbliższy narożnik
                                                else -> listOf(
                                                    1 to Offset(crop.left, crop.top), 2 to Offset(crop.right, crop.top),
                                                    3 to Offset(crop.left, crop.bottom), 4 to Offset(crop.right, crop.bottom),
                                                ).minBy { (_, o) -> (o - Offset(fx, fy)).getDistanceSquared() }.first
                                            }
                                        },
                                        onDrag = { ch, d ->
                                            ch.consume()
                                            val dx = d.x / scale / b.width; val dy = d.y / scale / b.height
                                            val c = crop
                                            val minS = 0.08f
                                            crop = when (mode) {
                                                5 -> {
                                                    val nx = (c.left + dx).coerceIn(0f, 1f - c.width); val ny = (c.top + dy).coerceIn(0f, 1f - c.height)
                                                    androidx.compose.ui.geometry.Rect(nx, ny, nx + c.width, ny + c.height)
                                                }
                                                in 1..4 -> {
                                                    var l = c.left; var t = c.top; var r = c.right; var bt = c.bottom
                                                    when (mode) { 1 -> { l += dx; t += dy }; 2 -> { r += dx; t += dy }; 3 -> { l += dx; bt += dy }; 4 -> { r += dx; bt += dy } }
                                                    l = l.coerceIn(0f, r - minS); r = r.coerceIn(l + minS, 1f); t = t.coerceIn(0f, bt - minS); bt = bt.coerceIn(t + minS, 1f)
                                                    val ratio = cropRatio
                                                    if (ratio != null) {
                                                        // stała proporcja: wysokość wynika z szerokości (w pikselach obrazu)
                                                        val hFrac = ((r - l) * b.width / ratio) / b.height
                                                        if (mode <= 2) t = (bt - hFrac).coerceAtLeast(0f) else bt = (t + hFrac).coerceAtMost(1f)
                                                    }
                                                    androidx.compose.ui.geometry.Rect(l, t, r, bt)
                                                }
                                                else -> c
                                            }
                                        },
                                    )
                                }
                                Tool.NONE -> {}
                            }
                        },
                    ) {
                        drawImage(img, dstOffset = IntOffset(ox.toInt(), oy.toInt()), dstSize = IntSize((b.width * scale).toInt(), (b.height * scale).toInt()))
                        fun mp(p: Offset) = Offset(ox + p.x * scale, oy + p.y * scale)
                        (strokes + listOfNotNull(current)).forEach { s ->
                            if (s.pts.size == 1) drawCircle(s.color, s.width * scale / 2, mp(s.pts[0]))
                            else {
                                val path = Path().apply { moveTo(mp(s.pts[0]).x, mp(s.pts[0]).y); s.pts.drop(1).forEach { val q = mp(it); lineTo(q.x, q.y) } }
                                drawPath(path, s.color, style = Stroke(s.width * scale, cap = StrokeCap.Round, join = StrokeJoin.Round))
                            }
                        }
                        val bs = blurStart; val be = blurEnd
                        if (bs != null && be != null) {
                            val a = mp(bs); val z = mp(be)
                            drawRect(Color.White, Offset(min(a.x, z.x), min(a.y, z.y)), Size(abs(a.x - z.x), abs(a.y - z.y)), style = Stroke(2.dp.toPx()))
                        }
                        if (tool == Tool.CROP) {
                            val l = ox + crop.left * b.width * scale; val t = oy + crop.top * b.height * scale
                            val r = ox + crop.right * b.width * scale; val bt = oy + crop.bottom * b.height * scale
                            val dim = Color.Black.copy(alpha = 0.55f)
                            drawRect(dim, Offset(0f, 0f), Size(size.width, t))
                            drawRect(dim, Offset(0f, bt), Size(size.width, size.height - bt))
                            drawRect(dim, Offset(0f, t), Size(l, bt - t))
                            drawRect(dim, Offset(r, t), Size(size.width - r, bt - t))
                            drawRect(Color.White, Offset(l, t), Size(r - l, bt - t), style = Stroke(2.dp.toPx()))
                            val hs = 10.dp.toPx()
                            listOf(Offset(l, t), Offset(r, t), Offset(l, bt), Offset(r, bt)).forEach { drawCircle(Color.White, hs, it) }
                        }
                    }
                }
                // opcje narzędzia
                when (tool) {
                    Tool.DRAW -> Row(Modifier.fillMaxWidth().padding(8.dp).horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                        PEN_COLORS.forEach { c ->
                            Box(Modifier.padding(4.dp).size(30.dp).clip(CircleShape).background(c)
                                .border(BorderStroke(if (penColor == c) 3.dp else 1.dp, if (penColor == c) MaterialTheme.colorScheme.primary else Color.Gray), CircleShape)
                                .clickable { penColor = c })
                        }
                        Spacer(Modifier.width(8.dp))
                        listOf(6f to "S", 12f to "M", 24f to "L").forEach { (w, t) ->
                            FilterChip(penWidth == w, { penWidth = w }, { Text(t) }, modifier = Modifier.padding(horizontal = 2.dp))
                        }
                    }
                    Tool.CROP -> Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "Przeciągnij narożniki lub ramkę.", Modifier.weight(1f).padding(start = 4.dp),
                                color = Color.White, style = MaterialTheme.typography.bodySmall,
                            )
                            Button(onClick = { applyCrop() }) {
                                Icon(Icons.Default.Check, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Przytnij")
                            }
                        }
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                            listOf<Pair<Float?, String>>(null to "Dowolnie", 1f to "1:1", 3f to "3:1", 4f / 3 to "4:3", 3f / 4 to "3:4", 16f / 9 to "16:9").forEach { (r, t) ->
                                FilterChip(
                                    cropRatio == r, { applyRatio(r) }, { Text(t, color = Color.White) }, modifier = Modifier.padding(horizontal = 2.dp),
                                )
                            }
                        }
                    }
                    Tool.BLUR -> Text(
                        "Przeciągnij palcem po fragmencie, który chcesz zamazać.", Modifier.fillMaxWidth().padding(10.dp),
                        color = Color.White, style = MaterialTheme.typography.bodySmall,
                    )
                    Tool.NONE -> {}
                }
                Row(Modifier.fillMaxWidth().padding(bottom = 8.dp, top = 4.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                    EditorTool(Icons.Default.Crop, "Kadruj", tool == Tool.CROP) { setTool(Tool.CROP) }
                    EditorTool(Icons.Default.RotateRight, "Obróć", false) {
                        commitStrokes(); val cur = applyCrop(); pushHistory(); bmp = ImageOps.rotate90(cur); tool = Tool.NONE; version++
                    }
                    EditorTool(Icons.Default.Brush, "Rysuj", tool == Tool.DRAW) { setTool(Tool.DRAW) }
                    EditorTool(Icons.Default.BlurOn, "Zamaż", tool == Tool.BLUR) { setTool(Tool.BLUR) }
                    EditorTool(Icons.Default.Face, "Twarze", false) {
                        commitStrokes(); applyCrop()
                        busy = true
                        scope.launch {
                            val faces = withContext(Dispatchers.Default) { ImageOps.findFaces(b) }
                            if (faces.isNotEmpty()) { pushHistory(); faces.forEach { ImageOps.pixelate(b, it) }; version++ }
                            busy = false
                            toast(ctx, if (faces.isEmpty()) "Nie znaleziono twarzy — zamaż ręcznie narzędziem „Zamaż”" else "Zamazano twarze: ${faces.size}")
                        }
                    }
                    EditorTool(Icons.AutoMirrored.Filled.Undo, "Cofnij", false) {
                        when {
                            strokes.isNotEmpty() -> strokes.removeAt(strokes.lastIndex)
                            history.isNotEmpty() -> { bmp = history.removeLast(); version++ }
                            else -> toast(ctx, "Nie ma czego cofnąć")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EditorTool(icon: ImageVector, label: String, active: Boolean, onClick: () -> Unit) {
    Column(
        Modifier.clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp)).clickable(onClick = onClick)
            .background(if (active) Color.White.copy(alpha = 0.15f) else Color.Transparent).padding(horizontal = 6.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, label, tint = if (active) MaterialTheme.colorScheme.primary else Color.White)
        Text(label, color = if (active) MaterialTheme.colorScheme.primary else Color.White, style = MaterialTheme.typography.labelSmall)
    }
}
