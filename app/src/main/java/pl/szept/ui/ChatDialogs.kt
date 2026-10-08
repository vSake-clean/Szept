package pl.szept.ui

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import pl.szept.App
import pl.szept.core.Core
import pl.szept.data.Db
import pl.szept.data.Group
import pl.szept.data.Kind
import pl.szept.data.Member
import pl.szept.data.Message
import pl.szept.data.Poll
import pl.szept.data.Reaction
import pl.szept.data.Scheduled
import pl.szept.data.St
import pl.szept.data.Vote
import pl.szept.data.XMode
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

val QUICK_REACTIONS = listOf("👍", "❤️", "😂", "😮", "😢", "🙏")
val MORE_REACTIONS = listOf(
    "🔥", "👏", "🎉", "😍", "😡", "🤔", "👎", "💯", "😅", "🥰", "😎", "🤝", "🙌", "💪", "😴", "🤯",
    "🥳", "😬", "👀", "✅", "❌", "🍕", "⚽", "🎮", "☕", "🍺", "💔", "🤣", "😇", "🙈",
)

private val whenFmt = SimpleDateFormat("d MMM, HH:mm", Locale("pl"))

/** „dziś 18:30”, „jutro 08:00”, „12 paź, 09:15”. */
fun formatWhen(ts: Long): String {
    val c = Calendar.getInstance().apply { timeInMillis = ts }
    val today = Calendar.getInstance()
    val tomorrow = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, 1) }
    fun same(a: Calendar, b: Calendar) = a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
    return when {
        same(c, today) -> "dziś " + formatTime(ts)
        same(c, tomorrow) -> "jutro " + formatTime(ts)
        else -> whenFmt.format(Date(ts))
    }
}

@Composable
private fun DialogItem(t: String, color: Color = MaterialTheme.colorScheme.onSurface, f: () -> Unit) {
    Text(t, Modifier.fillMaxWidth().clickable { f() }.padding(vertical = 11.dp), color = color, style = MaterialTheme.typography.bodyLarge)
}

// ======================= akcje wiadomości =======================

/**
 * Menu po przytrzymaniu wiadomości: szybkie reakcje na górze, potem odpowiedz / edytuj / przekaż / przypnij /
 * zapisz / informacje / usuń.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MessageActionsDialog(
    m: Message, group: Group?, interact: Boolean, pinned: Boolean,
    onDismiss: () -> Unit,
    onReply: (() -> Unit)?, onEdit: () -> Unit, onForward: () -> Unit, onInfo: () -> Unit, onVoters: () -> Unit,
    onSave: () -> Unit, onPickPeople: () -> Unit, onTranscribe: (() -> Unit)? = null, onSaveGif: (() -> Unit)? = null,
) {
    val ctx = LocalContext.current
    val core = App.core
    var confirmAll by remember { mutableStateOf(false) }
    var moreEmoji by remember { mutableStateOf(false) }
    if (confirmAll) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Usunąć u wszystkich?") },
            text = { Text("Wiadomość zniknie u wszystkich uczestników rozmowy (także tych, którzy są teraz offline — gdy się połączą).") },
            confirmButton = { TextButton(onClick = { core.deleteForEveryone(m.id); onDismiss() }) { Text("Usuń") } },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Anuluj") } },
        )
        return
    }
    val ephemeral = m.xmode != XMode.OFF
    val canReact = interact && m.kind != Kind.SYSTEM && !(m.xmode == XMode.AFTER_OPEN)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Wiadomość") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (canReact) {
                    val mineR = remember(m.id) { core.db.reaction(m.id, core.myPub)?.emoji }
                    Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        QUICK_REACTIONS.forEach { e ->
                            Text(
                                e, fontSize = 26.sp,
                                modifier = Modifier.clip(CircleShape)
                                    .background(if (mineR == e) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                                    .clickable { core.react(m.id, e); onDismiss() }.padding(4.dp),
                            )
                        }
                        IconButton(onClick = { moreEmoji = !moreEmoji }, modifier = Modifier.size(36.dp)) { Icon(Icons.Default.Add, "Więcej reakcji") }
                    }
                    if (moreEmoji) FlowRow(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        MORE_REACTIONS.forEach { e ->
                            Text(e, fontSize = 24.sp, modifier = Modifier.clip(CircleShape).clickable { core.react(m.id, e); onDismiss() }.padding(5.dp))
                        }
                    }
                    HorizontalDivider()
                }
                if (onReply != null && m.kind != Kind.SYSTEM) DialogItem("Odpowiedz") { onReply() }
                if (core.canEdit(m)) DialogItem("Edytuj") { onEdit() }
                if (m.kind == Kind.TEXT && !ephemeral && m.body.isNotEmpty()) DialogItem("Kopiuj tekst") { copyText(ctx, m.body); onDismiss() }
                if (core.canForward(m)) DialogItem("Przekaż dalej") { onForward() }
                if (m.kind != Kind.SYSTEM && !ephemeral && core.canPin(m.chat)) {
                    DialogItem(if (pinned) "Odepnij" else if (m.chat.startsWith("g:")) "Przypnij (ogłoszenie)" else "Przypnij") {
                        core.pinMessage(m.id, !pinned); onDismiss()
                    }
                }
                if (m.kind == Kind.POLL) DialogItem("Kto głosował") { onVoters() }
                if (onTranscribe != null && m.kind == Kind.VOICE && m.path != null && !ephemeral) {
                    DialogItem(if (m.body.isBlank()) "📝 Zamień na tekst (w telefonie)" else "📝 Zamień na tekst ponownie") { onTranscribe() }
                }
                if (onSaveGif != null) DialogItem("Zapisz w moich GIF-ach") { onSaveGif() }
                if ((m.kind == Kind.IMAGE || m.kind == Kind.VIDEO) && m.path != null && !ephemeral) DialogItem("Zapisz w galerii") { onSave() }
                if (m.path != null && !ephemeral) DialogItem("Udostępnij") { shareFile(ctx, m.path, m.media?.mime ?: ""); onDismiss() }
                if (m.mine && group != null && m.kind != Kind.SYSTEM) DialogItem("Informacje (kto przeczytał)") { onInfo() }
                if (m.mine && m.status == St.FAILED) DialogItem("Ponów wysyłanie") { core.retry(m.id); onDismiss() }
                HorizontalDivider()
                DialogItem("Usuń u mnie", MaterialTheme.colorScheme.error) { core.deleteForMe(m.id); onDismiss() }
                if (core.canDeleteForOthers(m) && m.kind != Kind.SYSTEM) {
                    if (group == null) {
                        DialogItem("Usuń u rozmówcy (u mnie zostaje)", MaterialTheme.colorScheme.error) {
                            core.deleteFor(m.id, core.participants(m.chat), false); toast(ctx, "Usunięto u rozmówcy"); onDismiss()
                        }
                    } else DialogItem("Usuń u wybranych osób…", MaterialTheme.colorScheme.error) { onPickPeople() }
                    DialogItem("Usuń u wszystkich", MaterialTheme.colorScheme.error) { confirmAll = true }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Zamknij") } },
    )
}

/** Kto i jak zareagował; własną reakcję można tu usunąć. */
@Composable
fun ReactionsDialog(m: Message, list: List<Reaction>, onDismiss: () -> Unit) {
    val core = App.core
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Reakcje (${list.size})") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                list.sortedWith(compareBy({ it.pk != core.myPub }, { it.emoji })).forEach { r ->
                    val me = r.pk == core.myPub
                    Row(
                        Modifier.fillMaxWidth().clickable(enabled = me) { core.react(m.id, r.emoji); onDismiss() }.padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(r.emoji, fontSize = 22.sp)
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(if (me) "Ty" else core.db.nameOf(r.pk), style = MaterialTheme.typography.bodyLarge)
                            if (me) Text("Dotknij, aby usunąć", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Zamknij") } },
    )
}

/** „Przeczytane przez…” dla mojej wiadomości w grupie. */
@Composable
fun ReadByDialog(m: Message, g: Group, onDismiss: () -> Unit) {
    val core = App.core
    val reads by rememberDb(m.id) { core.db.reads(m.id) }
    val others = g.members.filter { it.pk != core.myPub }
    val r = reads ?: emptyMap()
    val read = others.filter { (r[it.pk]?.first ?: 0) >= St.READ }.sortedByDescending { r[it.pk]?.second ?: 0 }
    val notYet = others.filter { it !in read }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Informacje o wiadomości") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("Wysłano: ${formatWhen(m.ts)}", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(10.dp))
                Text("✓✓ Przeczytali (${read.size})", fontWeight = FontWeight.Bold, color = Color(0xFF2F8CFF))
                read.forEach { mem -> PersonLine(mem, formatTime(r[mem.pk]?.second ?: 0)) }
                Spacer(Modifier.height(10.dp))
                Text("Jeszcze nie (${notYet.size})", fontWeight = FontWeight.Bold)
                notYet.forEach { mem -> PersonLine(mem, "") }
                Spacer(Modifier.height(8.dp))
                Text(
                    "Osoby, które wyłączyły potwierdzenia odczytu, zawsze są na liście „jeszcze nie”.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Zamknij") } },
    )
}

@Composable
private fun PersonLine(mem: Member, trailing: String) {
    val c = App.core.db.contact(mem.pk)
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        ContactAvatar(c ?: pl.szept.data.Contact(mem.pk, mem.name, false, false), 30.dp)
        Spacer(Modifier.width(10.dp))
        Text(c?.display ?: mem.name.ifBlank { mem.pk.take(8) }, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(trailing, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Kto na co głosował (głosy w ankietach są jawne). */
@Composable
fun VotersDialog(m: Message, votes: List<Vote>, onDismiss: () -> Unit) {
    val core = App.core
    val poll = remember(m.body) { Poll.parse(m.body) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(poll?.q ?: "Ankieta") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                poll?.opts?.forEachIndexed { i, o ->
                    val who = votes.filter { i in it.sel }
                    Text("$o — ${who.size}", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
                    if (who.isEmpty()) Text("nikt", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    who.forEach { v -> Text("• " + if (v.pk == core.myPub) "Ty" else core.db.nameOf(v.pk), style = MaterialTheme.typography.bodyMedium) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Zamknij") } },
    )
}

// ======================= ankieta =======================

@Composable
fun PollDialog(onDismiss: () -> Unit, onSend: (Poll) -> Unit) {
    var q by remember { mutableStateOf("") }
    val opts = remember { mutableStateListOf("", "") }
    var multi by remember { mutableStateOf(false) }
    val ready = q.isNotBlank() && opts.count { it.isNotBlank() } >= 2
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Nowa ankieta") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(q, { q = it.take(300) }, Modifier.fillMaxWidth(), label = { Text("Pytanie") })
                Spacer(Modifier.height(6.dp))
                opts.forEachIndexed { i, o ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(o, { v -> opts[i] = v.take(100) }, Modifier.weight(1f), label = { Text("Odpowiedź ${i + 1}") }, singleLine = true)
                        if (opts.size > 2) IconButton(onClick = { opts.removeAt(i) }) { Icon(Icons.Default.Close, "Usuń odpowiedź") }
                    }
                }
                if (opts.size < 10) TextButton(onClick = { opts.add("") }) { Text("+ Dodaj odpowiedź") }
                Row(Modifier.fillMaxWidth().clickable { multi = !multi }, verticalAlignment = Alignment.CenterVertically) {
                    Text("Można wybrać kilka odpowiedzi", Modifier.weight(1f))
                    Switch(multi, { multi = it })
                }
                Text("Wszyscy w rozmowie widzą, kto jak zagłosował.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            TextButton(enabled = ready, onClick = { onSend(Poll(q.trim(), opts.map { it.trim() }.filter { it.isNotEmpty() }, multi)) }) { Text("Wyślij") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Anuluj") } },
    )
}

// ======================= lokalizacja =======================

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun LocationDialog(onDismiss: () -> Unit, onPick: (liveMs: Long) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Lokalizacja") },
        text = {
            Column {
                DialogItem("📍 Tylko teraz — wyślij, gdzie jestem") { onPick(0) }
                Text("Na żywo — pozycja aktualizuje się przez:", Modifier.padding(top = 8.dp, bottom = 4.dp), style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(15 to "15 min", 30 to "30 min", 60 to "1 godz.", 120 to "2 godz.", 480 to "8 godz.").forEach { (min, t) ->
                        AssistChip(onClick = { onPick(min * 60_000L) }, label = { Text("🟢 $t") })
                    }
                }
                Text(
                    "Pozycję ustala Twój telefon (GPS lub sieć) i trafia ona — zaszyfrowana — tylko do uczestników tej rozmowy. " +
                        "Udostępnianie na żywo możesz zakończyć w każdej chwili.",
                    Modifier.padding(top = 6.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Anuluj") } },
    )
}

// ======================= zaplanowane =======================

/** Wybór terminu wysyłki: szybkie opcje albo własna data i godzina. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduleDialog(onDismiss: () -> Unit, onPick: (Long) -> Unit) {
    var custom by remember { mutableStateOf(false) }
    if (custom) { DateTimeDialog(onDismiss, onPick); return }
    fun at(daysAhead: Int, hour: Int) = Calendar.getInstance().apply {
        add(Calendar.DAY_OF_YEAR, daysAhead); set(Calendar.HOUR_OF_DAY, hour); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis
    val now = System.currentTimeMillis()
    val options = buildList {
        add("Za godzinę" to now + 3600_000L)
        at(0, 20).takeIf { it > now + 5 * 60_000 }?.let { add("Dziś wieczorem" to it) }
        add("Jutro rano" to at(1, 8))
        add("Jutro w południe" to at(1, 12))
        add("Jutro wieczorem" to at(1, 18))
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Zaplanuj wysłanie") },
        text = {
            Column {
                options.forEach { (label, t) ->
                    Row(Modifier.fillMaxWidth().clickable { onPick(t) }.padding(vertical = 10.dp)) {
                        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                        Text(formatWhen(t), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                DialogItem("📅 Wybierz datę i godzinę…") { custom = true }
                Text(
                    "Telefon wyśle wiadomość sam, także przy zamkniętej aplikacji. Gdy telefon głęboko śpi, może się spóźnić o kilka minut.",
                    Modifier.padding(top = 6.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Anuluj") } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateTimeDialog(onDismiss: () -> Unit, onPick: (Long) -> Unit) {
    val ctx = LocalContext.current
    var step by remember { mutableIntStateOf(0) }
    val todayUtc = remember {
        val c = Calendar.getInstance()
        Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            clear(); set(c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH))
        }.timeInMillis
    }
    val dateState = rememberDatePickerState(
        initialSelectedDateMillis = todayUtc,
        selectableDates = object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis >= todayUtc && utcTimeMillis < todayUtc + 366L * 86400_000
        },
    )
    val nowCal = remember { Calendar.getInstance() }
    val timeState = rememberTimePickerState(initialHour = (nowCal.get(Calendar.HOUR_OF_DAY) + 1) % 24, initialMinute = 0, is24Hour = true)
    if (step == 0) {
        DatePickerDialog(
            onDismissRequest = onDismiss,
            confirmButton = { TextButton(onClick = { step = 1 }) { Text("Dalej") } },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Anuluj") } },
        ) { DatePicker(dateState) }
    } else {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Godzina") },
            text = { TimePicker(timeState) },
            confirmButton = {
                TextButton(onClick = {
                    val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = dateState.selectedDateMillis ?: todayUtc }
                    val local = Calendar.getInstance().apply {
                        set(utc.get(Calendar.YEAR), utc.get(Calendar.MONTH), utc.get(Calendar.DAY_OF_MONTH), timeState.hour, timeState.minute, 0)
                        set(Calendar.MILLISECOND, 0)
                    }.timeInMillis
                    if (local <= System.currentTimeMillis() + 30_000) toast(ctx, "Wybierz godzinę w przyszłości") else onPick(local)
                }) { Text("Zaplanuj") }
            },
            dismissButton = { TextButton(onClick = { step = 0 }) { Text("Wstecz") } },
        )
    }
}

fun scheduledPreview(s: Scheduled): String = when (s.kind) {
    Kind.IMAGE -> "📷 Zdjęcie" + if (s.body.isNotEmpty()) ": " + s.body else ""
    Kind.VIDEO -> "🎬 Wideo" + if (s.body.isNotEmpty()) ": " + s.body else ""
    Kind.VOICE -> "🎤 Wiadomość głosowa"
    Kind.FILE -> "📎 " + (s.att?.optString("name") ?: "Plik")
    else -> s.body
}

@Composable
fun ScheduledListDialog(list: List<Scheduled>, onDismiss: () -> Unit) {
    val core = App.core
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Zaplanowane (${list.size})") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (list.isEmpty()) Text("Brak zaplanowanych wiadomości.")
                list.forEach { s ->
                    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Column(Modifier.padding(10.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Schedule, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.width(6.dp))
                                Text(formatWhen(s.at), fontWeight = FontWeight.Bold)
                                if (s.xmode == XMode.AFTER_OPEN) Text("  ⏱", color = Fire)
                            }
                            Text(scheduledPreview(s), maxLines = 3, overflow = TextOverflow.Ellipsis)
                            Row {
                                TextButton(onClick = { core.sendScheduledNow(s.id) }) { Text("Wyślij teraz") }
                                TextButton(onClick = { core.cancelScheduled(s.id) }) { Text("Usuń", color = MaterialTheme.colorScheme.error) }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Zamknij") } },
    )
}

// ======================= paski nad polem tekstowym =======================

/** Przypięta wiadomość nad rozmową; przy kilku dotknięcie przechodzi do kolejnej. */
@Composable
fun PinnedBar(m: Message, index: Int, count: Int, canUnpin: Boolean, onClick: () -> Unit, onUnpin: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.secondaryContainer).clickable(onClick = onClick)
            .padding(start = 12.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.PushPin, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(
                if (count > 1) "Przypięta wiadomość ${index + 1}/$count" else "Przypięta wiadomość",
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold,
            )
            Text(Db.preview(m).replace('\n', ' '), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
        }
        if (canUnpin) IconButton(onClick = onUnpin) { Icon(Icons.Default.Close, "Odepnij") } else Spacer(Modifier.width(12.dp))
    }
}

@Composable
fun EditBar(m: Message, onCancel: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.secondaryContainer).padding(start = 12.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.Edit, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text("Edytujesz wiadomość", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            Text(Db.preview(m).replace('\n', ' '), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
        }
        IconButton(onClick = onCancel) { Icon(Icons.Default.Close, "Anuluj edycję") }
    }
}

/** Podpowiedzi osób po wpisaniu „@” w grupie. */
@Composable
fun MentionSuggestions(list: List<Pair<String, String>>, onPick: (pk: String, name: String) -> Unit) {
    if (list.isEmpty()) return
    Card(Modifier.fillMaxWidth().padding(horizontal = 8.dp).heightIn(max = 220.dp)) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            list.take(8).forEach { (pk, name) ->
                Row(
                    Modifier.fillMaxWidth().clickable { onPick(pk, name) }.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (pk == "all") Text("📣", fontSize = 20.sp) else ContactAvatar(App.core.db.contact(pk), 28.dp)
                    Spacer(Modifier.width(10.dp))
                    Text(if (pk == "all") "@wszyscy — powiadom całą grupę" else name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

// ======================= album: podgląd kilku zdjęć =======================

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AlbumPreviewDialog(
    atts: List<Core.Attachment>, initialCaption: String, chatMode: Int, chatTtl: Int,
    onCancel: () -> Unit, onSend: (caption: String, xmode: Int?, ttl: Int?) -> Unit, onSchedule: (caption: String) -> Unit,
    onEdit: ((Int) -> Unit)? = null,
) {
    var caption by remember { mutableStateOf(initialCaption) }
    var timed by remember { mutableStateOf(chatMode == XMode.AFTER_OPEN) }
    var ttl by remember { mutableIntStateOf(if (chatMode == XMode.AFTER_OPEN) chatTtl else 10) }
    Dialog(onDismissRequest = onCancel, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Card(Modifier.fillMaxWidth(0.94f).imePadding()) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(14.dp)) {
                Text("Wyślij ${plural(atts.size, "zdjęcie", "zdjęcia", "zdjęć")}", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(10.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    atts.forEachIndexed { i, a ->
                        Box(Modifier.size(92.dp).clip(RoundedCornerShape(8.dp)).background(Color.Black).clickable(enabled = onEdit != null) { onEdit?.invoke(i) }) {
                            rememberImage(a.file.absolutePath, 256)?.let { BitmapImage(it, Modifier.fillMaxSize()) }
                            if (onEdit != null) Text("✏️", Modifier.align(Alignment.BottomEnd).padding(3.dp))
                        }
                    }
                }
                if (onEdit != null) Text("Dotknij zdjęcia, aby je edytować.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(caption, { caption = it.take(2000) }, Modifier.fillMaxWidth(), placeholder = { Text("Dodaj podpis…") }, maxLines = 4)
                Spacer(Modifier.height(8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(!timed, { timed = false }, { Text("Zwykłe") })
                    FilterChip(timed, { timed = true }, { Text("⏱ Czasowe") })
                }
                if (timed) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(5, 10, 30, 60, 300).forEach { o -> FilterChip(ttl == o, { ttl = o }, { Text(formatTtl(o)) }) }
                }
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onCancel) { Text("Anuluj") }
                    if (!timed) TextButton(onClick = { onSchedule(caption.trim()) }) { Text("🕓 Zaplanuj") }
                    Spacer(Modifier.width(4.dp))
                    Button(onClick = {
                        when {
                            timed -> onSend(caption.trim(), XMode.AFTER_OPEN, ttl)
                            chatMode == XMode.AFTER_SEND -> onSend(caption.trim(), null, null)
                            else -> onSend(caption.trim(), XMode.OFF, null)
                        }
                    }) { Icon(Icons.AutoMirrored.Filled.Send, null); Spacer(Modifier.width(6.dp)); Text("Wyślij") }
                }
            }
        }
    }
}

/** Mały znacznik „🕓 Zaplanowane: N” nad polem tekstowym. */
@Composable
fun ScheduledBar(count: Int, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.Schedule, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(6.dp))
        Text("Zaplanowane: $count — dotknij, aby zobaczyć", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
fun SquareThumb(path: String?, modifier: Modifier) {
    Box(modifier.aspectRatio(1f).clip(RoundedCornerShape(6.dp)).background(Color.Black.copy(alpha = 0.1f))) {
        rememberImage(path, 320)?.let { BitmapImage(it, Modifier.fillMaxSize()) }
    }
}
