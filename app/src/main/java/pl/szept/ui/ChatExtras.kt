package pl.szept.ui

import androidx.compose.material3.Surface

import androidx.compose.material.icons.filled.Edit

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import pl.szept.core.Core
import pl.szept.data.Kind
import pl.szept.data.Message
import pl.szept.data.Reply
import pl.szept.data.XMode
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

// ======================= separatory dni =======================

data class DayHeader(val key: String, val label: String)

private val dayKeyFmt = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT)
private val dayLabelFmt = SimpleDateFormat("EEEE, d MMMM", Locale("pl"))
private val dayLabelYearFmt = SimpleDateFormat("d MMMM yyyy", Locale("pl"))

fun dayLabel(ts: Long, now: Long = System.currentTimeMillis()): String {
    val c = Calendar.getInstance().apply { timeInMillis = ts }
    val today = Calendar.getInstance().apply { timeInMillis = now }
    val yesterday = Calendar.getInstance().apply { timeInMillis = now; add(Calendar.DAY_OF_YEAR, -1) }
    fun same(a: Calendar, b: Calendar) = a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
    return when {
        same(c, today) -> "Dzisiaj"
        same(c, yesterday) -> "Wczoraj"
        c.get(Calendar.YEAR) == today.get(Calendar.YEAR) -> dayLabelFmt.format(Date(ts)).replaceFirstChar { it.uppercase() }
        else -> dayLabelYearFmt.format(Date(ts))
    }
}

private fun rowTs(r: Any): Long = when (r) {
    is Message -> r.ts
    is AlbumRow -> r.msgs.first().ts
    else -> 0L
}

/** Lista od najnowszej wiadomości (lub albumu), z nagłówkiem dnia nad pierwszą wiadomością każdego dnia. */
fun withDayHeaders(newestFirst: List<Any>): List<Any> {
    val out = ArrayList<Any>(newestFirst.size + 8)
    for (i in newestFirst.indices) {
        val r = newestFirst[i]
        out.add(r)
        val ts = rowTs(r)
        val older = newestFirst.getOrNull(i + 1)
        val key = dayKeyFmt.format(Date(ts))
        if (older == null || dayKeyFmt.format(Date(rowTs(older))) != key) out.add(DayHeader("day:$key", dayLabel(ts)))
    }
    return out
}

@Composable
fun DayHeaderItem(label: String) {
    Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
        Text(
            label,
            Modifier.clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(horizontal = 12.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ======================= odpowiedzi =======================

/** Cytat w dymku wiadomości. */
@Composable
fun QuoteBox(reply: Reply, name: String, onClick: () -> Unit) {
    Row(
        Modifier.padding(bottom = 4.dp).clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.55f)).clickable(onClick = onClick),
    ) {
        Box(Modifier.width(4.dp).height(42.dp).background(avatarColor(reply.sender)))
        Column(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
            Text(name, style = MaterialTheme.typography.labelMedium, color = avatarColor(reply.sender), fontWeight = FontWeight.Bold, maxLines = 1)
            Text(reply.text.ifEmpty { "Wiadomość" }, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Pasek „Odpowiadasz na…” nad polem tekstowym. */
@Composable
fun ReplyBar(reply: Reply, name: String, onClose: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.secondaryContainer).padding(start = 12.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(4.dp).height(36.dp).background(avatarColor(reply.sender)))
        Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
            Text("Odpowiadasz: $name", style = MaterialTheme.typography.labelMedium, color = avatarColor(reply.sender), fontWeight = FontWeight.Bold)
            Text(reply.text, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        IconButton(onClick = onClose) { Icon(Icons.Default.Close, "Anuluj odpowiedź") }
    }
}

// ======================= podgląd mediów przed wysłaniem =======================

private val QUICK_TTLS = listOf(5, 10, 30, 60, 300, 3600)

/**
 * Podgląd zdjęcia/wideo przed wysłaniem: podpis + opcja „⏱ Czasowe” tylko dla tej jednej wiadomości.
 * Wiadomość czasowa: odbiorca widzi ją dopiero po dotknięciu, czas liczy się od otwarcia.
 * [onSend] dostaje tryb (null = ustawienie czatu) i czas.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MediaPreviewDialog(
    kind: String,
    att: Core.Attachment,
    initialCaption: String,
    chatMode: Int,
    chatTtl: Int,
    onCancel: () -> Unit,
    onSchedule: ((caption: String) -> Unit)? = null,
    onEdit: (() -> Unit)? = null,
    onSend: (caption: String, xmode: Int?, ttl: Int?) -> Unit,
) {
    var caption by remember { mutableStateOf(initialCaption) }
    var timed by remember { mutableStateOf(chatMode == XMode.AFTER_OPEN) }
    var ttl by remember { mutableIntStateOf(if (chatMode == XMode.AFTER_OPEN) chatTtl else 10) }
    Dialog(onDismissRequest = onCancel, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Card(Modifier.fillMaxWidth(0.94f).imePadding()) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(14.dp)) {
                Text(if (kind == Kind.VIDEO) "Wyślij wideo" else "Wyślij zdjęcie", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(10.dp))
                val ratio = if (att.w > 0 && att.h > 0) (att.w.toFloat() / att.h).coerceIn(0.5f, 2f) else 1f
                Box(
                    Modifier.fillMaxWidth().heightIn(max = 360.dp).aspectRatio(ratio).clip(RoundedCornerShape(12.dp)).background(Color.Black)
                        .clickable(enabled = onEdit != null && kind == Kind.IMAGE) { onEdit?.invoke() },
                    contentAlignment = Alignment.Center,
                ) {
                    if (kind == Kind.IMAGE) {
                        rememberImage(att.file.absolutePath, 1024)?.let { BitmapImage(it, Modifier.fillMaxSize(), crop = false) }
                        if (onEdit != null) Surface(
                            onClick = onEdit, shape = RoundedCornerShape(50), color = Color.Black.copy(alpha = 0.6f),
                            modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
                        ) {
                            Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Edit, null, tint = Color.White, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Edytuj / przytnij", color = Color.White, style = MaterialTheme.typography.labelLarge)
                            }
                        }
                    } else {
                        remember(att.thumb) { att.thumb?.let { Images.thumb(it) } }?.let { BitmapImage(it, Modifier.fillMaxSize(), crop = false) }
                        Box(Modifier.size(52.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.55f)), contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.PlayArrow, null, tint = Color.White, modifier = Modifier.size(34.dp))
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        formatSize(att.file.length()) + if (att.dur > 0) " · " + formatDuration(att.dur) else "",
                        Modifier.padding(top = 4.dp).weight(1f), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(caption, { caption = it.take(2000) }, Modifier.fillMaxWidth(), placeholder = { Text("Dodaj podpis…") }, maxLines = 4)
                Spacer(Modifier.height(10.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(!timed, { timed = false }, { Text("Zwykłe") })
                    FilterChip(timed, { timed = true }, { Text("⏱ Czasowe") })
                }
                if (timed) {
                    Text(
                        "Odbiorca zobaczy " + (if (kind == Kind.VIDEO) "wideo" else "zdjęcie") +
                            " dopiero po dotknięciu. Czas liczy się od otwarcia, potem zniknie:",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        QUICK_TTLS.forEach { o -> FilterChip(ttl == o, { ttl = o }, { Text(formatTtl(o)) }) }
                    }
                    if (kind == Kind.VIDEO && att.dur / 1000 + 5 > ttl) Text(
                        "Wideo nie zniknie w trakcie oglądania — czas wydłuży się do ${formatTtl((att.dur / 1000 + 5).toInt())}.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else if (chatMode == XMode.AFTER_SEND) {
                    Text("W tym czacie wiadomości znikają automatycznie ${formatTtl(chatTtl)} po wysłaniu.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onCancel) { Text("Anuluj") }
                    if (onSchedule != null && !timed) TextButton(onClick = { onSchedule(caption.trim()) }) { Text("🕓 Zaplanuj") }
                    Spacer(Modifier.width(4.dp))
                    Button(onClick = {
                        when {
                            timed -> onSend(caption.trim(), XMode.AFTER_OPEN, ttl)
                            chatMode == XMode.AFTER_SEND -> onSend(caption.trim(), null, null)   // automatyczne usuwanie czatu
                            else -> onSend(caption.trim(), XMode.OFF, null)
                        }
                    }) {
                        Icon(Icons.AutoMirrored.Filled.Send, null); Spacer(Modifier.width(6.dp)); Text("Wyślij")
                    }
                }
            }
        }
    }
}

// ======================= głosówki =======================

/** Słupki przebiegu dźwięku; część już odtworzona w kolorze [played]. Dotknięcie = przewiń. */
@Composable
fun Waveform(bars: FloatArray?, progress: Float, modifier: Modifier, played: Color, rest: Color, onSeek: ((Float) -> Unit)? = null) {
    val values = bars ?: remember { FloatArray(40) { 0.18f } }
    val seekState by rememberUpdatedState(onSeek)
    Canvas(
        modifier.then(
            if (onSeek != null) Modifier.pointerInput(Unit) {
                detectTapGestures { o -> seekState?.invoke((o.x / size.width).coerceIn(0f, 1f)) }
            } else Modifier,
        ),
    ) {
        val n = values.size
        if (n == 0) return@Canvas
        val gap = 2.dp.toPx()
        val bw = ((size.width - gap * (n - 1)) / n).coerceAtLeast(1f)
        values.forEachIndexed { i, v ->
            val h = (size.height * (0.12f + 0.88f * v.coerceIn(0f, 1f))).coerceAtLeast(2f)
            val color = if ((i + 0.5f) / n <= progress) played else rest
            drawRoundRect(color, topLeft = Offset(i * (bw + gap), (size.height - h) / 2), size = Size(bw, h), cornerRadius = CornerRadius(bw / 2, bw / 2))
        }
    }
}

@Composable
fun BlinkingDot() {
    val t = rememberInfiniteTransition(label = "rec")
    val a by t.animateFloat(1f, 0.2f, infiniteRepeatable(tween(600), RepeatMode.Reverse), label = "dot")
    Box(Modifier.size(12.dp).graphicsLayer { alpha = a }.clip(CircleShape).background(Color(0xFFE53935)))
}

private fun liveBars(amps: List<Float>, n: Int = 32): FloatArray {
    val tail = amps.takeLast(n)
    return FloatArray(n) { i -> val j = i - (n - tail.size); if (j < 0) 0f else kotlin.math.sqrt(tail[j].coerceIn(0f, 1f)) }
}

/** Wskaźnik podczas nagrywania z przytrzymanym przyciskiem (zastępuje pole tekstowe). */
@Composable
fun HoldRecordingIndicator(elapsed: Long, amps: List<Float>, dragX: Float, cancelDistancePx: Float, modifier: Modifier) {
    Row(modifier.height(52.dp).padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        BlinkingDot()
        Spacer(Modifier.width(8.dp))
        Text(formatDuration(elapsed), fontWeight = FontWeight.Medium)
        Spacer(Modifier.width(8.dp))
        Waveform(liveBars(amps, 20), 1f, Modifier.width(60.dp).height(22.dp), Color(0xFFE53935), Color.Transparent)
        Spacer(Modifier.weight(1f))
        val fade = 1f - (-dragX / cancelDistancePx).coerceIn(0f, 1f) * 0.85f
        Text(
            "‹ przesuń, aby anulować", Modifier.graphicsLayer { alpha = fade }.padding(end = 6.dp),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
        )
    }
}

/** Pasek nagrywania „bez trzymania” (po przesunięciu w górę albo krótkim dotknięciu mikrofonu). */
@Composable
fun LockedRecordingBar(elapsed: Long, amps: List<Float>, timed: Boolean, onTimed: () -> Unit, onCancel: () -> Unit, onSend: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onCancel) { Icon(Icons.Default.Delete, "Anuluj nagranie", tint = MaterialTheme.colorScheme.error) }
        BlinkingDot()
        Spacer(Modifier.width(6.dp))
        Text(formatDuration(elapsed), fontWeight = FontWeight.Medium)
        Spacer(Modifier.width(8.dp))
        Waveform(liveBars(amps), 1f, Modifier.weight(1f).height(26.dp), Color(0xFFE53935), Color.Transparent)
        Spacer(Modifier.width(6.dp))
        FilterChip(timed, onTimed, { Text("⏱ Czasowa") })
        Spacer(Modifier.width(6.dp))
        FilledIconButton(onClick = onSend) { Icon(Icons.AutoMirrored.Filled.Send, "Wyślij nagranie") }
    }
}

/**
 * Mikrofon jak w popularnych komunikatorach:
 *  - przytrzymaj = nagrywaj, puść = wyślij,
 *  - przesuń w lewo = anuluj, przesuń w górę = nagrywaj bez trzymania,
 *  - krótkie dotknięcie = nagrywanie bez trzymania (z przyciskiem Wyślij).
 */
@Composable
fun MicButton(
    holding: Boolean,
    dragX: Float,
    cancelDistancePx: Float,
    lockDistancePx: Float,
    onStart: () -> Boolean,
    onDrag: (Float) -> Unit,
    onCancel: () -> Unit,
    onLock: () -> Unit,
    onRelease: (quickTap: Boolean) -> Unit,
) {
    val start by rememberUpdatedState(onStart)
    val drag by rememberUpdatedState(onDrag)
    val cancel by rememberUpdatedState(onCancel)
    val lock by rememberUpdatedState(onLock)
    val release by rememberUpdatedState(onRelease)
    val scale by animateFloatAsState(if (holding) 1.35f else 1f, label = "mic")
    Box(contentAlignment = Alignment.BottomCenter) {
        if (holding) {
            // podpowiedź: w górę = blokada
            Column(
                Modifier.offset(y = (-70).dp).clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.secondaryContainer)
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(Icons.Default.Lock, null, Modifier.size(18.dp))
                Icon(Icons.Default.KeyboardArrowUp, null, Modifier.size(18.dp))
            }
        }
        Box(
            Modifier.padding(bottom = 4.dp).size(48.dp)
                .graphicsLayer {
                    scaleX = scale; scaleY = scale
                    translationX = if (holding) dragX.coerceIn(-cancelDistancePx, 0f) else 0f
                }
                .clip(CircleShape)
                .background(if (holding) Color(0xFFE53935) else MaterialTheme.colorScheme.primary)
                .semantics { contentDescription = "Nagraj głosówkę"; role = Role.Button }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        down.consume()
                        val t0 = System.currentTimeMillis()
                        if (!start()) return@awaitEachGesture
                        var finished = false
                        try {
                            var total = Offset.Zero
                            var outcome = 0 // 0 = puszczono, 1 = anuluj, 2 = blokada
                            while (true) {
                                val ev = awaitPointerEvent()
                                val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                                if (!ch.pressed) { ch.consume(); break }
                                total += ch.positionChange()
                                ch.consume()
                                drag(total.x)
                                if (total.x < -cancelDistancePx) { outcome = 1; break }
                                if (total.y < -lockDistancePx) { outcome = 2; break }
                            }
                            finished = true
                            when (outcome) {
                                1 -> cancel()
                                2 -> lock()
                                else -> release(System.currentTimeMillis() - t0 < 350)
                            }
                        } finally {
                            if (!finished) cancel()   // gest przerwany przez system
                        }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Default.Mic, null, tint = Color.White)
        }
    }
}
