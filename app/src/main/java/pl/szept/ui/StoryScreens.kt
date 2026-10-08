package pl.szept.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import pl.szept.App
import pl.szept.core.Core
import pl.szept.core.MediaUtil
import pl.szept.data.Kind
import pl.szept.data.Story

/** Kolory tła relacji tekstowych. */
val STORY_BG = listOf(
    Color(0xFF5B4BDB), Color(0xFF0B8A6F), Color(0xFFD14D72), Color(0xFF2F6FDE),
    Color(0xFFE65100), Color(0xFF263238), Color(0xFF7B3FB5), Color(0xFF00838F),
)

/** Pasek relacji (24 h) na górze listy rozmów. */
@Composable
fun StoriesRow(nav: Nav) {
    val core = App.core
    val stories by rememberDb { core.db.stories() }
    var newStory by remember { mutableStateOf(false) }
    val list = stories ?: emptyList()
    val mine = list.filter { it.pk == core.myPub }
    val others = list.filter { it.pk != core.myPub }.groupBy { it.pk }.entries
        .sortedWith(compareBy({ e -> e.value.all { it.seen } }, { e -> -e.value.maxOf { it.ts } }))
    val myAvatar = remember(stories) { if (core.avatarFile.exists()) pl.szept.crypto.B64.enc(core.avatarFile.readBytes()) else null }
    LazyRow(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "me") {
            StoryBubble(
                "Twoja relacja", core.myName, core.myPub, myAvatar, ring = if (mine.isEmpty()) null else true, plus = true,
                onClick = { if (mine.isNotEmpty()) nav.go(Screen.Stories(core.myPub)) else newStory = true },
            )
        }
        items(others, key = { it.key }) { (pk, l) ->
            val c = core.db.contact(pk)
            StoryBubble(c?.display ?: "?", c?.display ?: "?", pk, c?.avatar?.ifBlank { null }, ring = l.any { !it.seen }) {
                nav.go(Screen.Stories(pk))
            }
        }
    }
    if (newStory) NewStoryDialog { newStory = false }
}

@Composable
private fun StoryBubble(label: String, name: String, key: String, avatar: String?, ring: Boolean?, plus: Boolean = false, onClick: () -> Unit) {
    Column(Modifier.width(64.dp).clickable(onClick = onClick), horizontalAlignment = Alignment.CenterHorizontally) {
        Box {
            Box(
                Modifier.size(58.dp).clip(CircleShape)
                    .border(
                        BorderStroke(2.5.dp, when (ring) { true -> MaterialTheme.colorScheme.primary; false -> MaterialTheme.colorScheme.outline; null -> Color.Transparent }),
                        CircleShape,
                    ).padding(4.dp),
                contentAlignment = Alignment.Center,
            ) { Avatar(name, key, 50.dp, image = avatar) }
            if (plus) Box(
                Modifier.align(Alignment.BottomEnd).size(20.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Default.Add, "Dodaj relację", Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onPrimary) }
        }
        Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Nowa relacja: zdjęcie (z podpisem) albo tekst na kolorowym tle. Widzą ją Twoje kontakty przez 24 h. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NewStoryDialog(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var mode by remember { mutableIntStateOf(0) }   // 0 = wybór, 1 = tekst, 2 = zdjęcie
    var text by remember { mutableStateOf("") }
    var bg by remember { mutableIntStateOf(0) }
    var att by remember { mutableStateOf<Core.Attachment?>(null) }
    var busy by remember { mutableStateOf(false) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        if (uri == null) { if (att == null) mode = 0; return@rememberLauncherForActivityResult }
        scope.launch {
            busy = true
            try { att = MediaUtil.prepareImage(ctx, uri); mode = 2 } catch (e: Exception) { toast(ctx, "Nie udało się: ${e.message}") } finally { busy = false }
        }
    }
    fun publish() {
        busy = true
        App.work.launch {
            try {
                if (mode == 2) App.core.postStory(Kind.IMAGE, text, att, 0) else App.core.postStory(Kind.TEXT, text, null, bg)
                toast(ctx, "Relacja opublikowana — zniknie po 24 h")
                onDismiss()
            } catch (e: Exception) { toast(ctx, sendError(e)) } finally { busy = false }
        }
    }
    Dialog(onDismissRequest = { att?.file?.delete(); onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Card(Modifier.fillMaxWidth(0.94f).imePadding()) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
                Text("Nowa relacja", style = MaterialTheme.typography.titleLarge)
                Text("Widzą ją Twoje kontakty przez 24 godziny, potem znika.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(12.dp))
                when (mode) {
                    0 -> {
                        Button(onClick = { pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, Modifier.fillMaxWidth()) { Text("📷 Zdjęcie z galerii") }
                        Button(onClick = { mode = 1 }, Modifier.fillMaxWidth()) { Text("✏️ Tekst") }
                    }
                    1 -> {
                        Box(
                            Modifier.fillMaxWidth().heightIn(min = 160.dp).clip(RoundedCornerShape(12.dp)).background(STORY_BG[bg]).padding(16.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(text.ifEmpty { "Twój tekst…" }, color = Color.White.copy(alpha = if (text.isEmpty()) 0.6f else 1f), fontSize = 22.sp, textAlign = TextAlign.Center, fontWeight = FontWeight.Bold)
                        }
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(text, { text = it.take(700) }, Modifier.fillMaxWidth(), placeholder = { Text("Co słychać?") }, maxLines = 5)
                        Spacer(Modifier.height(8.dp))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            STORY_BG.forEachIndexed { i, c ->
                                Box(
                                    Modifier.size(32.dp).clip(CircleShape).background(c)
                                        .border(BorderStroke(if (bg == i) 3.dp else 0.dp, MaterialTheme.colorScheme.onSurface), CircleShape)
                                        .clickable { bg = i },
                                )
                            }
                        }
                    }
                    2 -> {
                        att?.let { a ->
                            Box(Modifier.fillMaxWidth().aspectRatio(if (a.w > 0 && a.h > 0) (a.w.toFloat() / a.h).coerceIn(0.5f, 2f) else 1f).clip(RoundedCornerShape(12.dp)).background(Color.Black)) {
                                rememberImage(a.file.absolutePath, 1024)?.let { BitmapImage(it, Modifier.fillMaxSize(), crop = false) }
                            }
                        }
                        OutlinedTextField(text, { text = it.take(300) }, Modifier.fillMaxWidth(), placeholder = { Text("Podpis (opcjonalnie)") }, maxLines = 3)
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                    if (busy) CircularProgressIndicator(Modifier.size(24.dp))
                    TextButton(onClick = { att?.file?.delete(); onDismiss() }) { Text("Anuluj") }
                    if (mode != 0) Button(onClick = { publish() }, enabled = !busy && (mode == 2 || text.isNotBlank())) { Text("Opublikuj") }
                }
            }
        }
    }
}

/** Oglądanie relacji jednej osoby: dotknij z prawej = dalej, z lewej = wstecz, przytrzymaj = pauza. */
@Composable
fun StoryViewer(nav: Nav, pk: String) {
    val core = App.core
    val all by rememberDb(pk) { core.db.stories().filter { it.pk == pk } }
    val stories = all ?: return
    if (stories.isEmpty()) { LaunchedEffect(Unit) { nav.back() }; return }
    var idx by remember { mutableIntStateOf(stories.indexOfFirst { !it.seen }.takeIf { it >= 0 } ?: 0) }
    var paused by remember { mutableStateOf(false) }
    var viewers by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val i = idx.coerceIn(0, stories.size - 1)
    val s = stories[i]
    var progress by remember(s.id) { mutableFloatStateOf(0f) }
    val mine = pk == core.myPub
    val ready = s.kind != Kind.IMAGE || s.path != null
    LaunchedEffect(s.id) { core.markStorySeen(s.id) }
    LaunchedEffect(s.id, paused, ready, viewers, confirmDelete) {
        if (!ready || paused || viewers || confirmDelete) return@LaunchedEffect
        val total = 6000f
        while (progress < 1f) { delay(50); progress = (progress + 50 / total).coerceAtMost(1f) }
        if (i < stories.size - 1) idx = i + 1 else nav.back()
    }
    Surface(color = Color.Black, modifier = Modifier.fillMaxSize()) {
        Box(
            Modifier.fillMaxSize().pointerInput(s.id, stories.size) {
                detectTapGestures(
                    onPress = { paused = true; tryAwaitRelease(); paused = false },
                    onTap = { o ->
                        if (o.x < size.width / 3f) { if (i > 0) idx = i - 1 else progress = 0f }
                        else { if (i < stories.size - 1) idx = i + 1 else nav.back() }
                    },
                )
            },
        ) {
            if (s.kind == Kind.IMAGE) {
                if (s.path != null) {
                    rememberImage(s.path, 2048)?.let { BitmapImage(it, Modifier.fillMaxSize(), crop = false) }
                } else CircularProgressIndicator(Modifier.align(Alignment.Center))
                if (s.body.isNotEmpty()) Text(
                    s.body, Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color.Black.copy(alpha = 0.5f)).padding(16.dp).padding(bottom = 40.dp),
                    color = Color.White, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyLarge,
                )
            } else {
                Box(Modifier.fillMaxSize().background(STORY_BG.getOrElse(s.bg) { STORY_BG[0] }).padding(28.dp), contentAlignment = Alignment.Center) {
                    Text(s.body, color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                }
            }
            Column(Modifier.fillMaxWidth().padding(top = 36.dp, start = 8.dp, end = 8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    stories.forEachIndexed { j, _ ->
                        val f = when { j < i -> 1f; j > i -> 0f; else -> progress }
                        Box(Modifier.weight(1f).height(3.dp).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = 0.35f))) {
                            Box(Modifier.fillMaxWidth(f).height(3.dp).background(Color.White))
                        }
                    }
                }
                Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    val c = core.db.contact(pk)
                    Avatar(if (mine) core.myName else c?.display ?: "", pk, 36.dp, image = if (mine) null else c?.avatar?.ifBlank { null })
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(if (mine) "Twoja relacja" else c?.display ?: "", color = Color.White, fontWeight = FontWeight.Bold)
                        Text(agoText(s.ts, pl.szept.nostr.Clock.now()), color = Color.White.copy(alpha = 0.75f), style = MaterialTheme.typography.labelSmall)
                    }
                    if (mine) IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Default.Delete, "Usuń relację", tint = Color.White) }
                    IconButton(onClick = { nav.back() }) { Icon(Icons.Default.Close, "Zamknij", tint = Color.White) }
                }
            }
            if (mine) {
                val views by rememberDb(s.id) { core.db.storyViews(s.id) }
                Row(
                    Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp).clip(RoundedCornerShape(20.dp))
                        .background(Color.Black.copy(alpha = 0.5f)).clickable { viewers = true }.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.Visibility, null, tint = Color.White, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(plural(views?.size ?: 0, "wyświetlenie", "wyświetlenia", "wyświetleń"), color = Color.White)
                }
                if (viewers) AlertDialog(
                    onDismissRequest = { viewers = false },
                    title = { Text("Kto obejrzał") },
                    text = {
                        Column(Modifier.verticalScroll(rememberScrollState())) {
                            val v = views ?: emptyList()
                            if (v.isEmpty()) Text("Jeszcze nikt (albo oglądający wyłączyli potwierdzenia odczytu).")
                            v.forEach { (who, ts) ->
                                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                    ContactAvatar(core.db.contact(who), 30.dp)
                                    Spacer(Modifier.width(10.dp))
                                    Text(core.db.nameOf(who), Modifier.weight(1f))
                                    Text(formatTime(ts), style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    },
                    confirmButton = { TextButton(onClick = { viewers = false }) { Text("Zamknij") } },
                )
            }
            if (confirmDelete) AlertDialog(
                onDismissRequest = { confirmDelete = false },
                title = { Text("Usunąć relację?") },
                text = { Text("Zniknie też u znajomych.") },
                confirmButton = { TextButton(onClick = { confirmDelete = false; core.deleteStory(s.id) }) { Text("Usuń") } },
                dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Anuluj") } },
            )
        }
    }
}
