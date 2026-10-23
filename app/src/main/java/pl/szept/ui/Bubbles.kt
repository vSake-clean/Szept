package pl.szept.ui

import android.os.Build
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import androidx.compose.foundation.Canvas
import androidx.compose.material3.Checkbox
import androidx.compose.material3.RadioButton
import androidx.compose.material3.TextButton
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import pl.szept.App
import pl.szept.core.VoicePlayer
import pl.szept.core.VoiceRecorder
import pl.szept.data.Db
import pl.szept.data.Kind
import pl.szept.data.Loc
import pl.szept.data.Message
import pl.szept.data.Poll
import pl.szept.data.Reaction
import pl.szept.data.St
import pl.szept.data.Vote
import pl.szept.data.XMode

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MessageItem(
    m: Message,
    senderName: String?,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
    replyName: String? = null,
    onReplyClick: (String) -> Unit = {},
    reactions: List<Reaction> = emptyList(),
    votes: List<Vote> = emptyList(),
    highlight: Boolean = false,
    onReactionsClick: () -> Unit = {},
    onVote: (List<Int>) -> Unit = {},
) {
    if (m.kind == Kind.SYSTEM) {
        Box(Modifier.fillMaxWidth().padding(vertical = 6.dp), contentAlignment = Alignment.Center) {
            Text(
                m.body, Modifier.clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.secondaryContainer)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    val mine = m.mine
    val mentionsMe = !mine && (App.core.myPub in m.ex.mentions || "all" in m.ex.mentions)
    Column(
        Modifier.fillMaxWidth()
            .background(if (highlight) MaterialTheme.colorScheme.primary.copy(alpha = 0.16f) else Color.Transparent)
            .padding(horizontal = 8.dp, vertical = 2.dp),
        horizontalAlignment = if (mine) Alignment.End else Alignment.Start,
    ) {
        Surface(
            shape = RoundedCornerShape(18.dp, 18.dp, if (mine) 4.dp else 18.dp, if (mine) 18.dp else 4.dp),
            color = if (mine) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.secondaryContainer,
            border = if (mentionsMe) androidx.compose.foundation.BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary) else null,
            modifier = Modifier.widthIn(max = 300.dp).combinedClickable(onClick = onClick, onLongClick = onLongPress),
        ) {
            Column(Modifier.padding(horizontal = 10.dp, vertical = 7.dp)) {
                if (senderName != null && !mine) {
                    Text(senderName, color = avatarColor(m.sender), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                }
                if (m.ex.fwd) Text("↪ Przekazano", style = MaterialTheme.typography.labelSmall, fontStyle = FontStyle.Italic, color = MaterialTheme.colorScheme.onSurfaceVariant)
                m.reply?.let { r -> QuoteBox(r, replyName ?: "") { onReplyClick(r.id) } }
                when {
                    // moja wiadomość czasowa: treść nie jest przechowywana, widać tylko jej stan
                    mine && m.xmode == XMode.AFTER_OPEN -> { ViewOnceSent(m); TransferInfo(m) }
                    // cudza wiadomość czasowa: ukryta, dopóki jej nie dotkniesz
                    !mine && m.hiddenUntilOpened -> ViewOnceCover(m)
                    else -> Content(m, votes, onVote)
                }
                MetaRow(m, Modifier.align(Alignment.End))
            }
        }
        ReactionsRow(reactions, onReactionsClick)
    }
}

/** Czas, „edytowano”, odliczanie i status (✓, ✓✓). */
@Composable
private fun MetaRow(m: Message, modifier: Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        // „czasowa od otwarcia”: u nadawcy 1 h to tylko zapas na wypadek nieotwarcia — licznik dopiero od otwarcia
        val waiting = m.mine && m.xmode == XMode.AFTER_OPEN && m.status != St.OPENED
        if (m.expires > 0 && !waiting) { Countdown(m.expires); Spacer(Modifier.width(6.dp)) }
        if (m.edited > 0) Text("edytowano · ", style = MaterialTheme.typography.labelSmall, fontStyle = FontStyle.Italic, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(formatTime(m.ts), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (m.mine) { Spacer(Modifier.width(4.dp)); StatusIcon(m.status) }
        if (m.delOthers) when (UiPrefs.delMark) {
            0 -> Box(Modifier.padding(start = 5.dp).size(8.dp).clip(CircleShape).background(Color(0xFFE53935)).semantics { contentDescription = "Usunięte u rozmówcy" })
            1 -> Text(" · usunięte u rozmówcy", style = MaterialTheme.typography.labelSmall, color = Color(0xFFE53935))
        }
    }
}

/** Reakcje pod dymkiem: „👍 2 ❤️”. Dotknięcie pokazuje, kto zareagował. */
@Composable
fun ReactionsRow(list: List<Reaction>, onClick: () -> Unit) {
    if (list.isEmpty()) return
    val me = App.core.myPub
    val grouped = list.groupBy { it.emoji }.entries.sortedByDescending { it.value.size }
    Row(
        Modifier.padding(top = 2.dp, start = 6.dp, end = 6.dp).clip(RoundedCornerShape(12.dp))
            .background(if (list.any { it.pk == me }) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick).padding(horizontal = 7.dp, vertical = 2.dp)
            .semantics { contentDescription = "Reakcje: " + grouped.joinToString { it.key + " " + it.value.size } },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        grouped.take(5).forEach { (e, l) ->
            Text(e, fontSize = 14.sp)
            if (l.size > 1) Text(l.size.toString(), style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(start = 1.dp))
            Spacer(Modifier.width(3.dp))
        }
    }
}

/** Album: kilka zdjęć wysłanych naraz, pokazanych jako siatka. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AlbumItem(
    list: List<Message>, senderName: String?, highlight: Boolean, reactions: List<Reaction>,
    onOpen: (Message) -> Unit, onLong: (Message) -> Unit, onReactionsClick: () -> Unit,
) {
    val first = list.first()
    val mine = first.mine
    Column(
        Modifier.fillMaxWidth()
            .background(if (highlight) MaterialTheme.colorScheme.primary.copy(alpha = 0.16f) else Color.Transparent)
            .padding(horizontal = 8.dp, vertical = 2.dp),
        horizontalAlignment = if (mine) Alignment.End else Alignment.Start,
    ) {
        Surface(
            shape = RoundedCornerShape(18.dp, 18.dp, if (mine) 4.dp else 18.dp, if (mine) 18.dp else 4.dp),
            color = if (mine) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.secondaryContainer,
            modifier = Modifier.width(260.dp),
        ) {
            Column(Modifier.padding(5.dp)) {
                if (senderName != null && !mine) {
                    Text(senderName, Modifier.padding(start = 5.dp, bottom = 3.dp), color = avatarColor(first.sender), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                }
                if (first.ex.fwd) Text("↪ Przekazano", Modifier.padding(start = 5.dp), style = MaterialTheme.typography.labelSmall, fontStyle = FontStyle.Italic)
                list.chunked(2).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(3.dp), modifier = Modifier.padding(bottom = 3.dp)) {
                        row.forEach { m ->
                            AlbumTile(m, Modifier.weight(1f).aspectRatio(if (row.size == 1) 1.6f else 1f).combinedClickable(onClick = { onOpen(m) }, onLongClick = { onLong(m) }))
                        }
                    }
                }
                list.firstOrNull { it.body.isNotEmpty() }?.let { c -> Text(c.body, Modifier.padding(horizontal = 5.dp), style = MaterialTheme.typography.bodyLarge) }
                val last = list.last()
                val uploading = list.filter { it.mine && it.status == St.UPLOADING }
                if (uploading.isNotEmpty()) Text("Wysyłanie ${list.size - uploading.size}/${list.size}…", Modifier.padding(horizontal = 5.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                MetaRow(last.copy(status = list.minOf { it.status }.let { if (it < 0) St.FAILED else it }), Modifier.align(Alignment.End).padding(end = 5.dp))
            }
        }
        ReactionsRow(reactions, onReactionsClick)
    }
}

@Composable
private fun AlbumTile(m: Message, modifier: Modifier) {
    val bmp = rememberImage(m.path, 480)
    val thumb = remember(m.media?.thumb) { m.media?.thumb?.let { Images.thumb(it) } }
    Box(modifier.clip(RoundedCornerShape(10.dp)).background(Color.Black.copy(alpha = 0.1f)), contentAlignment = Alignment.Center) {
        when {
            bmp != null -> BitmapImage(bmp, Modifier.fillMaxSize())
            thumb != null -> BitmapImage(thumb, Modifier.fillMaxSize().let { if (Build.VERSION.SDK_INT >= 31) it.blur(10.dp) else it })
        }
        if (m.path == null) CircularProgressIndicator(Modifier.size(28.dp))
        if (m.kind == Kind.VIDEO && m.path != null) Icon(Icons.Default.PlayArrow, null, tint = Color.White, modifier = Modifier.size(30.dp))
    }
}

@Composable
private fun Content(m: Message, votes: List<Vote>, onVote: (List<Int>) -> Unit) {
    when (m.kind) {
        Kind.TEXT -> if (m.ex.game != null) GameContent(m) else RichText(m.body)
        Kind.IMAGE -> { ImageContent(m); Caption(m) }
        Kind.VIDEO -> if (m.ex.round) RoundVideoContent(m) else { VideoContent(m); Caption(m) }
        Kind.VOICE -> VoiceContent(m)
        Kind.FILE -> { FileContent(m); Caption(m) }
        Kind.UPDATE -> UpdateContent(m)
        Kind.POLL -> PollContent(m, votes, onVote)
        Kind.LOCATION -> LocationContent(m)
        else -> Text(m.body)
    }
    TransferInfo(m)
}

private val linkRx = Regex("""(https?://[^\s]+)|(@[\p{L}\p{N}_.\-]+)""")

/** Tekst z linkami, wzmiankami @ i formatowaniem (*pogrubienie*, _kursywa_, ~przekreślenie~, `kod`, ||spoiler||). */
@Composable
fun RichText(text: String, style: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.bodyLarge) {
    val primary = MaterialTheme.colorScheme.primary
    val spoilerBg = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
    val codeBg = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
    val parts = remember(text) { TextFormat.parse(text) }
    // odsłonięte spoilery (dotknięcie)
    var revealed by remember(text) { mutableStateOf(emptySet<Int>()) }
    val annotated = remember(parts, primary, revealed) {
        TextFormat.annotate(parts, primary, spoilerBg, codeBg, revealed) { id -> revealed = revealed + id }
    }
    Text(annotated, style = style)
}

/** Ankieta z wynikami na żywo. Dotknięcie odpowiedzi = głos (ponowne = wycofanie). */
@Composable
private fun PollContent(m: Message, votes: List<Vote>, onVote: (List<Int>) -> Unit) {
    val poll = remember(m.body) { Poll.parse(m.body) }
    if (poll == null) { Text("📊 Ankieta"); return }
    val me = App.core.myPub
    val mine = votes.firstOrNull { it.pk == me }?.sel ?: emptyList()
    val counts = IntArray(poll.opts.size)
    votes.forEach { v -> v.sel.forEach { if (it in counts.indices) counts[it]++ } }
    val voters = votes.count { it.sel.isNotEmpty() }
    Column(Modifier.widthIn(min = 230.dp, max = 280.dp)) {
        Text("📊 " + poll.q, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyLarge)
        Text(
            (if (poll.multi) "Możesz wybrać kilka" else "Wybierz jedną odpowiedź") + " · głosy jawne",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        poll.opts.forEachIndexed { i, o ->
            val selected = i in mine
            val frac = if (voters == 0) 0f else counts[i].toFloat() / voters
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable {
                    onVote(if (poll.multi) (if (selected) mine - i else mine + i) else (if (selected) emptyList() else listOf(i)))
                }.padding(vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (poll.multi) Checkbox(selected, null) else RadioButton(selected, null)
                Column(Modifier.weight(1f).padding(end = 4.dp)) {
                    Row {
                        Text(o, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        Text(counts[i].toString(), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                    }
                    LinearProgressIndicator(progress = { frac }, modifier = Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)))
                }
            }
        }
        Text(plural(voters, "głos", "głosy", "głosów"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Lokalizacja (bez pobierania map z sieci — otwiera się w aplikacji map). */
@Composable
private fun LocationContent(m: Message) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val loc = remember(m.body) { Loc.parse(m.body) }
    if (loc == null) { Text("📍 Lokalizacja"); return }
    var now by remember { mutableLongStateOf(pl.szept.nostr.Clock.now()) }
    LaunchedEffect(loc.live, loc.ended) { while (loc.live > 0 && !loc.ended) { now = pl.szept.nostr.Clock.now(); delay(10_000) } }
    val live = loc.isLive(now)
    val accent = if (live) OkGreen else MaterialTheme.colorScheme.primary
    Column(Modifier.widthIn(min = 230.dp, max = 280.dp)) {
        Box(Modifier.fillMaxWidth().height(110.dp).clip(RoundedCornerShape(12.dp)).clickable { openMap(ctx, loc.lat, loc.lon, "Szept") }) {
            MapSketch(accent)
            Text(
                "%.5f, %.5f".format(java.util.Locale.ROOT, loc.lat, loc.lon), Modifier.align(Alignment.BottomStart).padding(6.dp)
                    .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(6.dp)).padding(horizontal = 5.dp),
                color = Color.White, style = MaterialTheme.typography.labelSmall,
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            when {
                loc.live == 0L -> "📍 Lokalizacja"
                live -> "🟢 Lokalizacja na żywo"
                else -> "📍 Udostępnianie zakończone"
            },
            fontWeight = FontWeight.Bold,
        )
        val details = buildString {
            if (loc.acc > 0) append("dokładność ±${loc.acc.toInt()} m")
            if (live) append(" · do ${formatTime(loc.live)} · aktualizacja ${agoText(loc.upd, now)}")
            else if (loc.live > 0) append(" · ostatnia pozycja ${formatTime(loc.upd)}")
        }
        if (details.isNotEmpty()) Text(details.trimStart(' ', '·'), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row {
            TextButton(onClick = { openMap(ctx, loc.lat, loc.lon, "Szept") }) { Text("Otwórz w mapach") }
            if (m.mine && live) TextButton(onClick = { App.core.stopLive(m.id) }) { Text("Zakończ", color = MaterialTheme.colorScheme.error) }
        }
    }
}

fun agoText(ts: Long, now: Long): String {
    val s = ((now - ts) / 1000).coerceAtLeast(0)
    return when {
        s < 60 -> "przed chwilą"
        s < 3600 -> "${s / 60} min temu"
        else -> "${s / 3600} h temu"
    }
}

/** Szkic „mapy” z pinezką (dekoracja — prawdziwa mapa otwiera się w aplikacji map). */
@Composable
private fun MapSketch(pin: Color) {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val land = if (dark) Color(0xFF26332B) else Color(0xFFE5F0E0)
    val road = if (dark) Color(0xFF3B4A40) else Color.White
    Canvas(Modifier.fillMaxSize()) {
        drawRect(land)
        val w = size.width; val h = size.height
        drawLine(road, Offset(0f, h * 0.35f), Offset(w, h * 0.55f), strokeWidth = 10f)
        drawLine(road, Offset(w * 0.3f, 0f), Offset(w * 0.45f, h), strokeWidth = 8f)
        drawLine(road, Offset(w * 0.75f, 0f), Offset(w * 0.62f, h), strokeWidth = 6f)
        drawCircle(Color(0xFF9FC5E8).copy(alpha = 0.7f), radius = h * 0.18f, center = Offset(w * 0.86f, h * 0.2f))
        val c = Offset(w / 2, h / 2)
        drawCircle(pin.copy(alpha = 0.25f), radius = 26f, center = c)
        drawCircle(pin, radius = 12f, center = c)
        drawCircle(Color.White, radius = 5f, center = c)
    }
}

@Composable
private fun UpdateContent(m: Message) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val current = pl.szept.BuildConfig.VERSION_NAME
    val newer = versionNewer(m.body, current)
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.widthIn(min = 200.dp)) {
        Text("⬆️", fontSize = 26.sp)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text("Nowa wersja Szept ${m.body}", fontWeight = FontWeight.Bold)
            Text(
                when {
                    m.path == null -> "Pobieranie ${m.progress}%…"
                    !newer -> "Masz już wersję $current lub nowszą"
                    else -> "Dotknij, aby zainstalować"
                },
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Caption(m: Message) {
    if (m.body.isNotEmpty()) Box(Modifier.padding(top = 4.dp)) { RichText(m.body) }
}

@Composable
private fun TransferInfo(m: Message) {
    val text = when {
        m.mine && m.status == St.UPLOADING -> "Wysyłanie ${m.progress}%"
        !m.mine && m.media != null && m.path == null && m.status == St.DOWNLOADING -> "Pobieranie ${m.progress}%"
        !m.mine && m.status == St.DOWNLOAD_FAILED -> "Nie udało się pobrać — dotknij, aby ponowić"
        m.mine && m.status == St.FAILED -> "Nie wysłano — dotknij, aby ponowić"
        else -> null
    } ?: return
    Text(text, style = MaterialTheme.typography.labelSmall,
        color = if (m.status == St.FAILED || m.status == St.DOWNLOAD_FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
}

@Composable
private fun ImageContent(m: Message) {
    val meta = m.media
    val bmp = rememberImage(m.path, 720)
    val thumb = remember(meta?.thumb) { meta?.thumb?.let { Images.thumb(it) } }
    val ratio = if (meta != null && meta.w > 0 && meta.h > 0) (meta.w.toFloat() / meta.h).coerceIn(0.5f, 2f) else 1f
    Box(Modifier.width(240.dp).aspectRatio(ratio).clip(RoundedCornerShape(12.dp)).background(Color.Black.copy(alpha = 0.1f)), contentAlignment = Alignment.Center) {
        val gif = meta?.mime == "image/gif"
        when {
            gif && m.path != null -> GifImage(m.path, Modifier.fillMaxSize())
            bmp != null -> BitmapImage(bmp, Modifier.fillMaxSize())
            thumb != null -> BitmapImage(thumb, Modifier.fillMaxSize().let { if (Build.VERSION.SDK_INT >= 31) it.blur(10.dp) else it })
        }
        if (gif) GifBadge(Modifier.align(Alignment.TopStart).padding(6.dp))
        if (m.path == null) CircularProgressIndicator(Modifier.size(36.dp))
    }
}

@Composable
private fun VideoContent(m: Message) {
    val meta = m.media
    val thumb = remember(meta?.thumb) { meta?.thumb?.let { Images.thumb(it) } }
    val ratio = if (meta != null && meta.w > 0 && meta.h > 0) (meta.w.toFloat() / meta.h).coerceIn(0.5f, 2f) else 16f / 9
    Box(Modifier.width(240.dp).aspectRatio(ratio).clip(RoundedCornerShape(12.dp)).background(Color.Black), contentAlignment = Alignment.Center) {
        if (thumb != null) BitmapImage(thumb, Modifier.fillMaxSize())
        if (m.path == null) CircularProgressIndicator(Modifier.size(36.dp))
        else Box(Modifier.size(52.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.55f)), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.PlayArrow, "Odtwórz", tint = Color.White, modifier = Modifier.size(34.dp))
        }
        if (meta != null && meta.dur > 0) Text(
            formatDuration(meta.dur), Modifier.align(Alignment.BottomStart).padding(6.dp)
                .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(6.dp)).padding(horizontal = 5.dp),
            color = Color.White, style = MaterialTheme.typography.labelSmall,
        )
    }
}

@Composable
private fun VoiceContent(m: Message) {
    val playing by VoicePlayer.playing.collectAsState()
    val pos by VoicePlayer.position.collectAsState()
    val speed by VoicePlayer.speed.collectAsState()
    val isMe = playing == m.id
    // wiadomość zniknęła (np. czasowa) — przestań ją odtwarzać
    DisposableEffect(m.id) { onDispose { if (VoicePlayer.playing.value == m.id) VoicePlayer.stop() } }
    val bars = remember(m.media?.wave) { VoiceRecorder.decodeWave(m.media?.wave) }
    val dur = m.media?.dur ?: 0
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.width(250.dp)) {
        FilledIconButton(onClick = { m.path?.let { RoundPlayer.stop(); VoicePlayer.toggle(m.id, it) } }, enabled = m.path != null) {
            Icon(if (isMe) Icons.Default.Pause else Icons.Default.PlayArrow, if (isMe) "Pauza" else "Odtwórz")
        }
        Spacer(Modifier.width(8.dp))
        Waveform(
            bars, if (isMe) pos else 0f, Modifier.weight(1f).height(30.dp),
            played = MaterialTheme.colorScheme.primary, rest = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
            onSeek = m.path?.let { path -> { f: Float -> VoicePlayer.seek(m.id, path, f) } },
        )
        Spacer(Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(formatDuration(if (isMe) (dur * pos).toLong() else dur), style = MaterialTheme.typography.labelMedium)
            if (isMe) Text(
                (if (speed == 1.5f) "1,5" else speed.toInt().toString()) + "×",
                Modifier.clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f))
                    .clickable { VoicePlayer.cycleSpeed() }.padding(horizontal = 6.dp, vertical = 1.dp),
                style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold,
            )
        }
    }
    // transkrypcja (tylko w tym telefonie)
    if (m.body.isNotBlank()) Text(
        "📝 " + m.body, Modifier.widthIn(max = 250.dp).padding(top = 4.dp),
        style = MaterialTheme.typography.bodyMedium, fontStyle = FontStyle.Italic,
    )
}

@Composable
private fun FileContent(m: Message) {
    val meta = m.media ?: return
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.widthIn(min = 180.dp)) {
        Icon(Icons.AutoMirrored.Filled.InsertDriveFile, null, Modifier.size(36.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(8.dp))
        Column {
            Text(meta.name, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(formatSize(meta.size), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Zasłona wiadomości czasowej — treść pojawi się dopiero po dotknięciu, wtedy ruszy odliczanie. */
@Composable
private fun ViewOnceCover(m: Message) {
    val ready = m.media == null || m.path != null
    val action = when (m.kind) {
        Kind.VOICE -> "Dotknij, aby odsłuchać"
        Kind.IMAGE, Kind.VIDEO -> "Dotknij, aby zobaczyć"
        Kind.FILE -> "Dotknij, aby otworzyć"
        else -> "Dotknij, aby przeczytać"
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
        Box(Modifier.size(40.dp).clip(CircleShape).background(Fire.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.Timer, null, tint = Fire, modifier = Modifier.size(24.dp))
        }
        Spacer(Modifier.width(10.dp))
        Column {
            Text(Db.timedLabel(m.kind), fontWeight = FontWeight.Bold)
            Text(
                if (ready) "$action · zniknie ${formatTtl(m.ttl)} po otwarciu" else "Pobieranie ${m.progress}%…",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ViewOnceSent(m: Message) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
        Icon(Icons.Default.Timer, null, tint = Fire, modifier = Modifier.size(26.dp))
        Spacer(Modifier.width(8.dp))
        Column {
            Text(Db.timedLabel(m.kind), fontWeight = FontWeight.Bold)
            Text(
                if (m.status == St.OPENED) "Otwarto — zniknie po ${formatTtl(m.ttl)}" else "Czeka na otwarcie · zniknie ${formatTtl(m.ttl)} po otwarciu",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun Countdown(expires: Long) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(expires) { while (true) { now = System.currentTimeMillis(); delay(500) } }
    val left = ((expires - now + 999) / 1000).coerceAtLeast(0).toInt()
    Text("⏱ ${formatTtl(left)}", color = Fire, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
}

@Composable
private fun StatusIcon(status: Int) {
    val (icon, tint) = when (status) {
        St.QUEUED, St.UPLOADING -> Icons.Default.Schedule to MaterialTheme.colorScheme.onSurfaceVariant
        St.SENT -> Icons.Default.Done to MaterialTheme.colorScheme.onSurfaceVariant
        St.DELIVERED -> Icons.Default.DoneAll to MaterialTheme.colorScheme.onSurfaceVariant
        St.READ -> Icons.Default.DoneAll to Color(0xFF2F8CFF)
        St.OPENED -> Icons.Default.Visibility to Color(0xFF2F8CFF)
        St.FAILED -> Icons.Default.ErrorOutline to MaterialTheme.colorScheme.error
        else -> return
    }
    Icon(icon, null, Modifier.size(16.dp), tint = tint)
}
