package pl.szept.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import pl.szept.App
import pl.szept.core.Core
import pl.szept.core.Links
import pl.szept.data.Chat
import pl.szept.data.Db
import pl.szept.data.Kind
import pl.szept.data.Message
import pl.szept.service.AndroidNotifier

// ======================= archiwum =======================

@Composable
fun ArchiveScreen(nav: Nav) {
    val core = App.core
    val chats by rememberDb { core.db.chats() }
    val revealed by core.hiddenRevealed.collectAsState()
    var menu by remember { mutableStateOf<Chat?>(null) }
    Scaffold(topBar = { BackBar("Zarchiwizowane", nav) }) { pad ->
        val list = chats?.filter { it.archived && (!it.hidden || revealed) } ?: emptyList()
        LazyColumn(Modifier.padding(pad).fillMaxSize()) {
            item {
                Text(
                    "Zarchiwizowane rozmowy nie wyskakują na górę listy. Nowe wiadomości nadal przychodzą (z powiadomieniami, chyba że wyciszysz rozmowę). " +
                        "Przytrzymaj rozmowę, aby ją przywrócić.",
                    Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (chats != null && list.isEmpty()) item { Text("Archiwum jest puste.", Modifier.padding(16.dp)) }
            items(list, key = { it.id }) { c -> ChatRow(c, onLong = { menu = c }) { nav.go(Screen.Chat(c.id)) } }
        }
    }
    menu?.let { c -> ChatMenuDialog(c, nav) { menu = null } }
}

// ======================= wyszukiwarka =======================

/** Szuka w nazwach rozmów i w treści wiadomości. Wpisanie PIN-u ukrytych czatów pokazuje ukryte czaty. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(nav: Nav) {
    val ctx = LocalContext.current
    val core = App.core
    var q by remember { mutableStateOf("") }
    val revealed by core.hiddenRevealed.collectAsState()
    val results by produceState(emptyList<Chat>() to emptyList<Message>(), q, revealed) {
        val needle = q.trim()
        value = if (needle.length < 2) emptyList<Chat>() to emptyList() else withContext(Dispatchers.IO) {
            val all = core.db.chats()
            val hiddenIds = all.filter { it.hidden && !revealed }.map { it.id }.toSet()
            all.filter { it.id !in hiddenIds && it.name.contains(needle, true) } to core.db.search(needle).filter { it.chat !in hiddenIds }
        }
    }
    LaunchedEffect(q) {
        if (core.hasHiddenPin && q.length in 4..8 && q.all { it.isDigit() } && withContext(Dispatchers.IO) { core.verifyHiddenPin(q) }) {
            core.hiddenRevealed.value = true
            toast(ctx, "Pokazano ukryte czaty")
            nav.back()
        }
    }
    val chatNames by rememberDb { core.db.chats().associate { it.id to it.name } }
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = { nav.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Wstecz") } },
                title = {
                    TextField(
                        q, { q = it }, Modifier.fillMaxWidth(), placeholder = { Text("Szukaj rozmów i wiadomości") }, singleLine = true,
                        colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent),
                    )
                },
            )
        },
    ) { pad ->
        val (chats, msgs) = results
        LazyColumn(Modifier.padding(pad).fillMaxSize().imePadding()) {
            if (q.trim().length < 2) item {
                Text(
                    "Wpisz co najmniej 2 znaki. Wiadomości czasowe i znikające nie są przeszukiwane.",
                    Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (chats.isNotEmpty()) {
                item { Text("Rozmowy", Modifier.padding(start = 16.dp, top = 8.dp), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary) }
                items(chats, key = { "c" + it.id }) { c -> ChatRow(c) { nav.go(Screen.Chat(c.id)) } }
            }
            if (msgs.isNotEmpty()) {
                item { Text("Wiadomości (${msgs.size})", Modifier.padding(start = 16.dp, top = 8.dp), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary) }
                items(msgs, key = { "m" + it.id }) { m -> SearchHit(m, chatNames?.get(m.chat) ?: "", q.trim()) { nav.go(Screen.Chat(m.chat, m.id)) } }
            }
            if (q.trim().length >= 2 && chats.isEmpty() && msgs.isEmpty()) item { Text("Nic nie znaleziono.", Modifier.padding(16.dp)) }
        }
    }
}

@Composable
private fun SearchHit(m: Message, chatName: String, needle: String, onClick: () -> Unit) {
    val core = App.core
    val text = Db.preview(m).replace('\n', ' ')
    val idx = text.indexOf(needle, ignoreCase = true)
    val start = (idx - 30).coerceAtLeast(0)
    val snippet = (if (start > 0) "…" else "") + text.substring(start)
    val primary = MaterialTheme.colorScheme.primary
    val annotated = remember(snippet, needle) {
        buildAnnotatedString {
            val i = snippet.indexOf(needle, ignoreCase = true)
            if (i < 0) append(snippet) else {
                append(snippet.substring(0, i))
                withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = primary)) { append(snippet.substring(i, i + needle.length)) }
                append(snippet.substring(i + needle.length))
            }
        }
    }
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        ContactAvatar(core.db.contact(m.sender), 40.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row {
                Text(
                    (if (m.mine) "Ty" else core.db.nameOf(m.sender)) + if (m.chat.startsWith("g:")) " · $chatName" else "",
                    Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(formatListTime(m.ts), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(annotated, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

// ======================= wiadomość do wielu =======================

/** Ta sama wiadomość do wielu osób — każda dostaje ją osobno, w swojej prywatnej rozmowie. */
@Composable
fun BroadcastScreen(nav: Nav) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val core = App.core
    val people by rememberDb { core.db.contacts(false).filter { !it.blocked && it.pk != core.myPub } }
    val selected = remember { mutableStateListOf<String>() }
    var text by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var filter by remember { mutableStateOf("") }
    Scaffold(
        topBar = { BackBar("Wiadomość do wielu", nav) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = {
                    if (sending) return@ExtendedFloatingActionButton
                    if (text.isBlank() || selected.isEmpty()) { toast(ctx, "Wpisz wiadomość i wybierz osoby"); return@ExtendedFloatingActionButton }
                    sending = true
                    scope.launch {
                        var ok = 0
                        for (pk in selected.toList()) {
                            try { core.sendText(core.openDm(pk), text); ok++ } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e }
                        }
                        sending = false
                        toast(ctx, "Wysłano do $ok os.")
                        nav.back()
                    }
                },
                icon = { Icon(Icons.AutoMirrored.Filled.Send, null) },
                text = { Text(if (sending) "Wysyłanie…" else "Wyślij (${selected.size})") },
            )
        },
    ) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize().imePadding()) {
            item {
                OutlinedTextField(text, { text = it.take(10_000) }, Modifier.fillMaxWidth().padding(16.dp), label = { Text("Wiadomość") }, minLines = 3, maxLines = 8)
                Text(
                    "Każda wybrana osoba dostanie tę wiadomość osobno — jak zwykłą prywatną wiadomość od Ciebie. Odbiorcy nie widzą siebie nawzajem.",
                    Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(filter, { filter = it }, Modifier.weight(1f).padding(8.dp), placeholder = { Text("Szukaj osoby") }, singleLine = true)
                    TextButton(onClick = {
                        val all = people?.map { it.pk } ?: emptyList()
                        if (selected.size == all.size) selected.clear() else { selected.clear(); selected.addAll(all) }
                    }) { Text(if (selected.size == (people?.size ?: -1)) "Odznacz" else "Wszyscy") }
                }
            }
            val list = people?.filter { filter.isBlank() || it.display.contains(filter, true) } ?: emptyList()
            items(list, key = { it.pk }) { p ->
                PersonRow(p, trailing = { Checkbox(p.pk in selected, { on -> if (on) selected.add(p.pk) else selected.remove(p.pk) }) }) {
                    if (p.pk in selected) selected.remove(p.pk) else selected.add(p.pk)
                }
            }
            item { Spacer(Modifier.height(90.dp)) }
        }
    }
}

// ======================= przekazywanie =======================

@Composable
fun ForwardScreen(nav: Nav, msgId: String) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val core = App.core
    val m by rememberDb(msgId) { core.db.message(msgId) }
    val chats by rememberDb { core.db.chats() }
    val revealed by core.hiddenRevealed.collectAsState()
    val selected = remember { mutableStateListOf<String>() }
    var filter by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    Scaffold(
        topBar = { BackBar("Przekaż do…", nav) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = {
                    val msg = m ?: return@ExtendedFloatingActionButton
                    if (selected.isEmpty() || busy) return@ExtendedFloatingActionButton
                    busy = true
                    scope.launch {
                        try {
                            val n = core.forward(msg, selected.toList())
                            toast(ctx, "Przekazano do $n " + if (n == 1) "rozmowy" else "rozmów")
                            nav.back()
                        } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; toast(ctx, sendError(e)) } finally { busy = false }
                    }
                },
                icon = { Icon(Icons.AutoMirrored.Filled.Send, null) },
                text = { Text("Przekaż (${selected.size})") },
            )
        },
    ) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize()) {
            item {
                m?.let { msg ->
                    Card(Modifier.fillMaxWidth().padding(12.dp)) {
                        Text("↪ " + Db.preview(msg), Modifier.padding(12.dp), maxLines = 3, overflow = TextOverflow.Ellipsis)
                    }
                }
                OutlinedTextField(filter, { filter = it }, Modifier.fillMaxWidth().padding(horizontal = 12.dp), placeholder = { Text("Szukaj rozmowy") }, singleLine = true)
            }
            val list = chats?.filter { (!it.hidden || revealed) && it.id != m?.chat && (filter.isBlank() || it.name.contains(filter, true)) } ?: emptyList()
            items(list, key = { it.id }) { c ->
                Row(
                    Modifier.fillMaxWidth().clickable { if (c.id in selected) selected.remove(c.id) else selected.add(c.id) }.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val image = if (c.isGroup) core.db.group(c.id.removePrefix("g:"))?.icon?.ifBlank { null } else c.peer?.let { core.db.contact(it)?.avatar?.ifBlank { null } }
                    Avatar(c.name, c.peer ?: c.id, 42.dp, c.isGroup, image)
                    Spacer(Modifier.width(12.dp))
                    Text(c.name, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Checkbox(c.id in selected, { on -> if (on) selected.add(c.id) else selected.remove(c.id) })
                }
            }
            item { Spacer(Modifier.height(90.dp)) }
        }
    }
}

// ======================= galeria rozmowy =======================

private val urlRx = Regex("""https?://[^\s]+""")

@Composable
fun MediaGalleryScreen(nav: Nav, chatId: String) {
    val ctx = LocalContext.current
    val core = App.core
    var tab by remember { mutableIntStateOf(0) }
    val media by rememberDb(chatId) { core.db.chatMedia(chatId, listOf(Kind.IMAGE, Kind.VIDEO)) }
    val files by rememberDb(chatId) { core.db.chatMedia(chatId, listOf(Kind.FILE, Kind.VOICE)) }
    val links by rememberDb(chatId) {
        core.db.chatMedia(chatId, listOf(Kind.TEXT, Kind.IMAGE, Kind.VIDEO, Kind.FILE)).flatMap { m -> urlRx.findAll(m.body).map { it.value.trimEnd('.', ',', ')', '!', '?') to m }.toList() }
    }
    Scaffold(topBar = { BackBar("Multimedia, pliki i linki", nav) }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            TabRow(selectedTabIndex = tab) {
                Tab(tab == 0, { tab = 0 }, text = { Text("Media (${media?.size ?: 0})") })
                Tab(tab == 1, { tab = 1 }, text = { Text("Pliki (${files?.size ?: 0})") })
                Tab(tab == 2, { tab = 2 }, text = { Text("Linki (${links?.size ?: 0})") })
            }
            when (tab) {
                0 -> {
                    val l = media ?: emptyList()
                    if (l.isEmpty()) Text("Brak zdjęć i filmów.", Modifier.padding(16.dp))
                    LazyVerticalGrid(GridCells.Adaptive(110.dp), Modifier.fillMaxSize().padding(2.dp)) {
                        items(l, key = { it.id }) { m ->
                            Box(Modifier.padding(2.dp).aspectRatio(1f).clip(RoundedCornerShape(6.dp)).background(Color.Black.copy(alpha = 0.1f))
                                .clickable(enabled = m.path != null) { nav.go(Screen.Viewer(m.id)) }, contentAlignment = Alignment.Center) {
                                val bmp = rememberImage(if (m.kind == Kind.IMAGE) m.path else null, 320)
                                val thumb = remember(m.media?.thumb) { m.media?.thumb?.let { Images.thumb(it) } }
                                when {
                                    bmp != null -> BitmapImage(bmp, Modifier.fillMaxSize())
                                    thumb != null -> BitmapImage(thumb, Modifier.fillMaxSize())
                                }
                                if (m.kind == Kind.VIDEO) Icon(Icons.Default.PlayArrow, null, tint = Color.White, modifier = Modifier.size(32.dp))
                            }
                        }
                    }
                }
                1 -> LazyColumn(Modifier.fillMaxSize()) {
                    val l = files ?: emptyList()
                    if (l.isEmpty()) item { Text("Brak plików i głosówek.", Modifier.padding(16.dp)) }
                    items(l, key = { it.id }) { m ->
                        ListItem(
                            leadingContent = { Icon(if (m.kind == Kind.VOICE) Icons.Default.PlayArrow else Icons.AutoMirrored.Filled.InsertDriveFile, null) },
                            headlineContent = { Text(if (m.kind == Kind.VOICE) "Wiadomość głosowa · " + formatDuration(m.media?.dur ?: 0) else m.media?.name ?: "Plik", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            supportingContent = { Text((if (m.mine) "Ty" else core.db.nameOf(m.sender)) + " · " + formatWhen(m.ts) + " · " + formatSize(m.media?.size ?: 0)) },
                            modifier = Modifier.clickable(enabled = m.path != null) { m.path?.let { openFile(ctx, it, m.media?.mime ?: "") } },
                        )
                    }
                }
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    val l = links ?: emptyList()
                    if (l.isEmpty()) item { Text("Brak linków.", Modifier.padding(16.dp)) }
                    items(l) { (url, m) ->
                        ListItem(
                            leadingContent = { Icon(Icons.Default.Link, null) },
                            headlineContent = { Text(url, maxLines = 2, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.primary) },
                            supportingContent = { Text((if (m.mine) "Ty" else core.db.nameOf(m.sender)) + " · " + formatWhen(m.ts)) },
                            modifier = Modifier.clickable {
                                try { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) { toast(ctx, "Nie można otworzyć linku") }
                            },
                        )
                    }
                }
            }
        }
    }
}

// ======================= wygląd =======================

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AppearanceScreen(nav: Nav) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val pickWall = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        if (uri != null) scope.launch {
            try {
                val a = pl.szept.core.MediaUtil.prepareImage(ctx, uri)
                a.file.copyTo(UiPrefs.wallpaperFile(ctx), overwrite = true); a.file.delete()
                UiPrefs.wallpaper = "img"; UiPrefs.wallpaperVer++; UiPrefs.save(ctx)
            } catch (e: Exception) { toast(ctx, "Nie udało się") }
        }
    }
    Scaffold(topBar = { BackBar("Wygląd", nav) }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
            Text("Motyw", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            listOf(0 to "Jak w systemie", 1 to "Jasny", 2 to "Ciemny", 3 to "Czarny (AMOLED, oszczędza baterię)").forEach { (v, t) ->
                Row(Modifier.fillMaxWidth().clickable { UiPrefs.theme = v; UiPrefs.save(ctx) }, verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(UiPrefs.theme == v, { UiPrefs.theme = v; UiPrefs.save(ctx) }); Text(t)
                }
            }
            Spacer(Modifier.height(12.dp))
            Text("Kolor", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(vertical = 8.dp)) {
                ACCENTS.forEachIndexed { i, c ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable { UiPrefs.accent = i; UiPrefs.save(ctx) }) {
                        Box(
                            Modifier.size(40.dp).clip(CircleShape).background(c)
                                .border(BorderStroke(if (UiPrefs.accent == i) 3.dp else 0.dp, MaterialTheme.colorScheme.onSurface), CircleShape),
                            contentAlignment = Alignment.Center,
                        ) { if (UiPrefs.accent == i) Icon(Icons.Default.Check, null, tint = Color.White) }
                        Text(ACCENT_NAMES[i], style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Text("Rozmiar tekstu", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(0.85f to "Mały", 1f to "Normalny", 1.15f to "Duży", 1.3f to "Bardzo duży").forEach { (v, t) ->
                    FilterChip(UiPrefs.fontScale == v, { UiPrefs.fontScale = v; UiPrefs.save(ctx) }, { Text(t) })
                }
            }
            Spacer(Modifier.height(12.dp))
            Text("Tapeta rozmów", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 8.dp)) {
                WallChoice(UiPrefs.wallpaper == "", Modifier.background(MaterialTheme.colorScheme.background)) { UiPrefs.wallpaper = ""; UiPrefs.save(ctx) }
                WALLPAPERS.forEachIndexed { i, colors ->
                    WallChoice(UiPrefs.wallpaper == "g$i", Modifier.background(Brush.verticalGradient(colors))) { UiPrefs.wallpaper = "g$i"; UiPrefs.save(ctx) }
                }
            }
            OutlinedButton(onClick = { pickWall.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, Modifier.fillMaxWidth()) {
                Text(if (UiPrefs.wallpaper == "img") "Zmień własne zdjęcie tapety" else "Własne zdjęcie jako tapeta")
            }
            Spacer(Modifier.height(12.dp))
            Text("Wiadomość usunięta u rozmówcy", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            Text("Gdy usuniesz swoją wiadomość tylko u rozmówcy, u Ciebie zostaje z oznaczeniem:", style = MaterialTheme.typography.bodySmall)
            listOf(0 to "🔴 Czerwona kropka", 1 to "Napis „usunięte u rozmówcy”", 2 to "Bez oznaczenia").forEach { (v, t) ->
                Row(Modifier.fillMaxWidth().clickable { UiPrefs.delMark = v; UiPrefs.save(ctx) }, verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(UiPrefs.delMark == v, { UiPrefs.delMark = v; UiPrefs.save(ctx) }); Text(t)
                }
            }
            Spacer(Modifier.height(12.dp))
            Text("Podgląd", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            Box(Modifier.fillMaxWidth().height(170.dp).padding(vertical = 6.dp).clip(RoundedCornerShape(12.dp)).border(BorderStroke(1.dp, MaterialTheme.colorScheme.outline), RoundedCornerShape(12.dp))) {
                ChatWallpaper(Modifier.matchParentSize())
                Column(Modifier.padding(8.dp)) {
                    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.secondaryContainer) { Text("Cześć! Jak leci? 👋", Modifier.padding(10.dp)) }
                    Spacer(Modifier.height(6.dp))
                    Row(Modifier.fillMaxWidth()) {
                        Spacer(Modifier.weight(1f))
                        Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.primaryContainer) { Text("Super, a u Ciebie? 😄", Modifier.padding(10.dp)) }
                    }
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 12.dp))
            Text("Dźwięki powiadomień", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            Text(
                "Dźwięk wszystkich wiadomości zmienisz w ustawieniach systemu. Osobny dźwięk dla jednej rozmowy ustawisz w jej profilu (dotknij nazwy na górze rozmowy).",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = { AndroidNotifier.openChannelSettings(ctx, AndroidNotifier.CH_MSG) }, Modifier.fillMaxWidth()) { Text("Dźwięk wiadomości…") }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun WallChoice(selected: Boolean, bg: Modifier, onClick: () -> Unit) {
    Box(
        Modifier.size(width = 54.dp, height = 80.dp).clip(RoundedCornerShape(10.dp)).then(bg)
            .border(BorderStroke(if (selected) 3.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline), RoundedCornerShape(10.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { if (selected) Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.primary) }
}

// ======================= własny przekaźnik =======================

@Composable
fun OwnRelayScreen(nav: Nav) {
    val ctx = LocalContext.current
    val core = App.core
    var url by remember { mutableStateOf("") }
    val link = remember { Links.relays(core.configuredRelays()) }
    Scaffold(topBar = { BackBar("Własny przekaźnik", nav) }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(16.dp)) {
            Text(
                "Przekaźnik to „skrzynka pocztowa” na zaszyfrowane koperty. Szept domyślnie korzysta z publicznych przekaźników Nostr, " +
                    "ale możesz uruchomić własny — wtedy Wasze wiadomości nie zależą od nikogo obcego.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(12.dp))
            Text("Krok po kroku (Raspberry Pi, stary komputer lub serwer VPS)", fontWeight = FontWeight.Bold)
            listOf(
                "1. Zainstaluj Dockera (na Raspberry Pi OS / Ubuntu): curl -fsSL https://get.docker.com | sh",
                "2. Uruchom przekaźnik (nostr-rs-relay; na Raspberry Pi użyj 64-bitowego systemu):\n" +
                    "docker run -d --name przekaznik --restart unless-stopped -p 8080:8080 -v przekaznik:/usr/src/app/db scsibug/nostr-rs-relay",
                "3a. W sieci domowej: dodaj w Szepcie adres ws://ADRES-IP-KOMPUTERA:8080 (np. ws://192.168.1.20:8080). Działa tylko w tej samej sieci Wi-Fi.",
                "3b. Z całego internetu: potrzebujesz domeny i szyfrowania TLS. Najprościej: Caddy (caddy reverse-proxy --from twoja.domena.pl --to localhost:8080) " +
                    "albo bezpłatny tunel (np. Cloudflare Tunnel). Adres w Szepcie: wss://twoja.domena.pl",
                "4. Dodaj adres poniżej i wyślij link z przekaźnikami znajomym — po jego otwarciu Szept doda im te same przekaźniki.",
                "Wskazówka: zostaw też kilka publicznych przekaźników — jeśli Twój komputer się wyłączy, wiadomości dalej dojdą.",
            ).forEach {
                Text(it, Modifier.padding(vertical = 4.dp), style = MaterialTheme.typography.bodySmall, fontFamily = if (it.contains("docker") || it.contains("caddy")) FontFamily.Monospace else FontFamily.Default)
            }
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(url, { url = it.trim() }, Modifier.weight(1f), placeholder = { Text("wss://… lub ws://192.168…") }, singleLine = true)
                TextButton(onClick = {
                    val u = if (url.startsWith("ws")) url else "wss://$url"
                    if (url.isBlank()) return@TextButton
                    core.setRelays((core.configuredRelays() + u).distinct())
                    toast(ctx, if (u.startsWith("ws://")) "Dodano (bez szyfrowania połączenia — tylko w zaufanej sieci domowej)" else "Dodano przekaźnik")
                    url = ""
                }) { Text("Dodaj") }
            }
            Text(
                "Uwaga: ws:// (bez „s”) nie szyfruje połączenia z przekaźnikiem. Treść wiadomości i tak jest zaszyfrowana end-to-end, ale w sieci widać, do kogo są koperty.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            HorizontalDivider(Modifier.padding(vertical = 12.dp))
            Text("Udostępnij swoje przekaźniki", fontWeight = FontWeight.Bold)
            Text("Znajomy otworzy link w Szepcie i jednym dotknięciem doda te same przekaźniki.", style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(8.dp))
            QrImage(link, 200.dp)
            Row {
                OutlinedButton(onClick = { shareText(ctx, "Przekaźniki do Szepta: $link") }) { Icon(Icons.Default.Share, null); Spacer(Modifier.width(6.dp)); Text("Wyślij") }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = { copyText(ctx, link) }) { Icon(Icons.Default.ContentCopy, null); Spacer(Modifier.width(6.dp)); Text("Kopiuj") }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
