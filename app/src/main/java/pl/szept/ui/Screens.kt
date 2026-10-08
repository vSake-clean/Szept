package pl.szept.ui

import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.GroupAdd
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material3.AlertDialog
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.launch
import pl.szept.App
import pl.szept.core.Links
import pl.szept.data.Chat
import pl.szept.data.Contact
import pl.szept.nostr.Relay

// ======================= wspólne elementy =======================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackBar(title: String, nav: Nav, actions: @Composable () -> Unit = {}) {
    TopAppBar(
        title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        navigationIcon = { IconButton(onClick = { nav.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Wstecz") } },
        actions = { actions() },
    )
}

@Composable
fun Banner(text: String, action: String, onClick: () -> Unit) {
    Card(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = onClick) { Text(action) }
        }
    }
}

@Composable
fun ActionRow(icon: ImageVector, text: String, sub: String? = null, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(42.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.onPrimary)
        }
        Spacer(Modifier.width(14.dp))
        Column {
            Text(text, style = MaterialTheme.typography.titleMedium)
            if (sub != null) Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

fun qrBitmap(text: String, px: Int = 640): ImageBitmap {
    val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, px, px, mapOf(EncodeHintType.MARGIN to 1, EncodeHintType.CHARACTER_SET to "UTF-8"))
    val pixels = IntArray(px * px) { if (m[it % px, it / px]) android.graphics.Color.BLACK else android.graphics.Color.WHITE }
    return Bitmap.createBitmap(pixels, px, px, Bitmap.Config.ARGB_8888).asImageBitmap()
}

@Composable
fun QrImage(text: String, size: Dp) {
    val bmp = remember(text) { qrBitmap(text) }
    Image(bmp, "Kod QR", Modifier.size(size).clip(RoundedCornerShape(12.dp)).background(Color.White).padding(10.dp))
}

@Composable
fun rememberQrScanner(onResult: (String) -> Unit): () -> Unit {
    val launcher = rememberLauncherForActivityResult(ScanContract()) { r -> r.contents?.let(onResult) }
    return {
        launcher.launch(
            ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE).setPrompt("Zeskanuj kod Szept")
                .setBeepEnabled(false).setOrientationLocked(false),
        )
    }
}

// ======================= powitanie =======================

@Composable
fun OnboardingScreen(onDone: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var restore by remember { mutableStateOf(false) }
    var key by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var backupUri by remember { mutableStateOf<android.net.Uri?>(null) }
    val ctx = LocalContext.current
    val pickBackup = rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri -> backupUri = uri }
    backupUri?.let { u ->
        RestoreBackupDialog(u, onDismiss = { backupUri = null }) {
            backupUri = null
            UiPrefs.reload(ctx)
            onDone()
        }
    }
    Scaffold { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(32.dp))
            Box(Modifier.size(96.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary), contentAlignment = Alignment.Center) {
                Text("💬", fontSize = 44.sp)
            }
            Spacer(Modifier.height(16.dp))
            Text("Szept", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
            // konto usunięte przez administratora — informacja przy zakładaniu nowego
            val notice = remember { ctx.getSharedPreferences("szept_notice", android.content.Context.MODE_PRIVATE).getString("deleted", null) }
            if (notice != null) Card(Modifier.fillMaxWidth().padding(top = 12.dp), colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Text(notice, Modifier.padding(12.dp), color = MaterialTheme.colorScheme.onErrorContainer)
            }
            Text("Prywatny komunikator bez serwera", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(24.dp))
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("• Bez numeru telefonu, bez e-maila, bez rejestracji")
                    Text("• Szyfrowanie end-to-end — nikt poza odbiorcą nie przeczyta wiadomości")
                    Text("• Wiadomości czekają do 21 dni, nawet gdy telefon odbiorcy jest wyłączony")
                    Text("• Grupy, zdjęcia, głosówki, wideo, wiadomości znikające")
                }
            }
            Spacer(Modifier.height(24.dp))
            OutlinedTextField(name, { name = it.take(40) }, Modifier.fillMaxWidth(), label = { Text("Twoje imię / pseudonim") }, singleLine = true)
            if (restore) {
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(key, { key = it.trim() }, Modifier.fillMaxWidth(), label = { Text("Klucz prywatny (64 znaki)") }, singleLine = true)
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp)) }
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = {
                    if (name.isBlank()) { error = "Wpisz imię"; return@Button }
                    if (restore) {
                        if (!App.core.restoreIdentity(key, name)) { error = "Nieprawidłowy klucz"; return@Button }
                    } else App.core.createIdentity(name)
                    onDone()
                },
                Modifier.fillMaxWidth().height(52.dp),
            ) { Text(if (restore) "Przywróć konto" else "Zaczynamy") }
            TextButton(onClick = { restore = !restore; error = null }) {
                Text(if (restore) "Utwórz nowe konto" else "Mam już konto (przywróć z klucza)")
            }
            TextButton(onClick = { pickBackup.launch(arrayOf("*/*")) }) { Text("Przywróć z kopii zapasowej (plik .szept)") }
            Spacer(Modifier.height(12.dp))
            Text("Wersja ${pl.szept.BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ======================= lista czatów =======================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(nav: Nav) {
    val ctx = LocalContext.current
    val act = ctx as? MainActivity
    val chats by rememberDb { App.core.db.chats() }
    val update by rememberDb { App.core.db.latestUpdate() }
    val revealed by App.core.hiddenRevealed.collectAsState()
    var chatMenu by remember { mutableStateOf<Chat?>(null) }
    val states by App.core.relayStates.collectAsState()
    val connected = states.values.count { it == Relay.State.CONNECTED }
    var menu by remember { mutableStateOf(false) }
    var battery by remember { mutableStateOf(true) }
    var notif by remember { mutableStateOf(true) }
    LifecycleResumeEffect(Unit) {
        battery = MainActivity.ignoresBattery(ctx)
        notif = MainActivity.hasNotificationPermission(ctx)
        onPauseOrDispose { }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Szept", fontWeight = FontWeight.Bold)
                        val (txt, col) = when {
                            connected == 0 -> "Łączenie…" to MaterialTheme.colorScheme.error
                            connected < 3 -> "● Połączono z $connected/${states.size} przekaźnikami" to Fire
                            else -> "● Online · $connected/${states.size} przekaźników" to OkGreen
                        }
                        Text(txt, style = MaterialTheme.typography.labelSmall, color = col)
                    }
                },
                actions = {
                    IconButton(onClick = { nav.go(Screen.Search) }) { Icon(Icons.Default.Search, "Szukaj") }
                    IconButton(onClick = { nav.go(Screen.MyCode) }) { Icon(Icons.Default.QrCode, "Mój kod") }
                    IconButton(onClick = { nav.go(Screen.AddContact()) }) { Icon(Icons.Default.PersonAdd, "Dodaj kontakt") }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "Menu") }
                        DropdownMenu(menu, { menu = false }) {
                            DropdownMenuItem({ Text("Mój profil") }, { menu = false; nav.go(Screen.MyProfile) }, leadingIcon = { Icon(Icons.Default.AccountCircle, null) })
                            DropdownMenuItem({ Text("Osoby") }, { menu = false; nav.go(Screen.People) }, leadingIcon = { Icon(Icons.Default.People, null) })
                            DropdownMenuItem({ Text("Nowa grupa") }, { menu = false; nav.go(Screen.NewGroup) }, leadingIcon = { Icon(Icons.Default.GroupAdd, null) })
                            DropdownMenuItem({ Text("Wiadomość do wielu") }, { menu = false; nav.go(Screen.Broadcast) }, leadingIcon = { Icon(Icons.Default.Campaign, null) })
                            DropdownMenuItem({ Text("Archiwum") }, { menu = false; nav.go(Screen.Archive) }, leadingIcon = { Icon(Icons.Default.Archive, null) })
                            DropdownMenuItem({ Text("Dołącz do grupy (link)") }, { menu = false; nav.go(Screen.AddContact()) }, leadingIcon = { Icon(Icons.Default.Link, null) })
                            DropdownMenuItem({ Text("Wyślij aplikację znajomym") }, { menu = false; shareApp(ctx) }, leadingIcon = { Icon(Icons.Default.Share, null) })
                            DropdownMenuItem({ Text("Ustawienia") }, { menu = false; nav.go(Screen.Settings) }, leadingIcon = { Icon(Icons.Default.Settings, null) })
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { nav.go(Screen.NewChat) }) { Icon(Icons.Default.Edit, "Nowa rozmowa") }
        },
    ) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize()) {
            if (!battery) item {
                Banner("Zezwól na działanie w tle bez ograniczeń baterii — inaczej wiadomości mogą docierać z opóźnieniem.", "Zezwól") {
                    MainActivity.askBattery(ctx)
                }
            }
            if (!notif) item { Banner("Włącz powiadomienia, aby wiedzieć o nowych wiadomościach.", "Włącz") { act?.askNotifications() } }
            update?.let { u ->
                if (u.path != null && versionNewer(u.body, pl.szept.BuildConfig.VERSION_NAME)) item {
                    Banner("Dostępna nowa wersja Szept ${u.body}.", "Zainstaluj") { installApk(ctx, u.path) }
                }
            }
            item { StoriesRow(nav) }
            val all = chats
            if (all != null) {
                val visible = all.filter { !it.archived && (!it.hidden || revealed) }
                val archived = all.filter { it.archived && (!it.hidden || revealed) }
                if (revealed) item {
                    Banner("Widzisz ukryte czaty 🔒. Znikną z listy po wyjściu z aplikacji.", "Schowaj") { App.core.hiddenRevealed.value = false }
                }
                if (archived.isNotEmpty()) item { ArchiveEntry(archived) { nav.go(Screen.Archive) } }
                if (visible.isEmpty() && archived.isEmpty()) item { EmptyHome(nav) }
                items(visible, key = { it.id }) { c -> ChatRow(c, onLong = { chatMenu = c }) { nav.go(Screen.Chat(c.id)) } }
            }
        }
    }
    chatMenu?.let { c -> ChatMenuDialog(c, nav) { chatMenu = null } }
}

@Composable
private fun ArchiveEntry(archived: List<Chat>, onClick: () -> Unit) {
    val unread = archived.sumOf { it.unread }
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(50.dp).clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.Archive, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Text("Zarchiwizowane (${archived.size})", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
        if (unread > 0) Badge(containerColor = MaterialTheme.colorScheme.outline) { Text(unread.toString()) }
    }
}

/** Menu po przytrzymaniu rozmowy na liście. */
@Composable
fun ChatMenuDialog(c: Chat, nav: Nav, onDismiss: () -> Unit) {
    val core = App.core
    var confirmDelete by remember { mutableStateOf(false) }
    var needPin by remember { mutableStateOf(false) }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Usunąć rozmowę „${c.name}” z telefonu?") },
            text = { Text("Wiadomości znikną tylko u Ciebie.") },
            confirmButton = { TextButton(onClick = { core.deleteChat(c.id); onDismiss() }) { Text("Usuń") } },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Anuluj") } },
        )
        return
    }
    if (needPin) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Najpierw ustaw PIN ukrytych czatów") },
            text = { Text("Ukryte czaty znikają z listy. Pokażesz je, wpisując ten PIN w wyszukiwarce (lupa na górze) albo w „Prywatność i blokada”.") },
            confirmButton = { TextButton(onClick = { onDismiss(); nav.go(Screen.Security) }) { Text("Ustaw PIN") } },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Anuluj") } },
        )
        return
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(c.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        text = {
            Column {
                @Composable
                fun item(t: String, color: Color = MaterialTheme.colorScheme.onSurface, f: () -> Unit) {
                    Text(t, Modifier.fillMaxWidth().clickable { f() }.padding(vertical = 11.dp), color = color, style = MaterialTheme.typography.bodyLarge)
                }
                item(if (c.pinned > 0) "📌 Odepnij" else "📌 Przypnij na górze") { core.setChatPinned(c.id, c.pinned == 0L); onDismiss() }
                item(if (c.archived) "Przywróć z archiwum" else "Archiwizuj") { core.setArchived(c.id, !c.archived); onDismiss() }
                item(if (c.muted) "Włącz powiadomienia" else "Wycisz") { core.setMuted(c.id, !c.muted); onDismiss() }
                if (c.unread > 0) item("Oznacz jako przeczytane") { core.markChatRead(c.id); onDismiss() }
                item(if (c.hidden) "Pokazuj normalnie (odkryj)" else "🔒 Ukryj czat") {
                    if (!c.hidden && !core.hasHiddenPin) needPin = true else { core.setHidden(c.id, !c.hidden); onDismiss() }
                }
                HorizontalDivider()
                item("Usuń rozmowę", MaterialTheme.colorScheme.error) { confirmDelete = true }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Zamknij") } },
    )
}

@Composable
private fun EmptyHome(nav: Nav) {
    Column(Modifier.fillMaxWidth().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("👋", fontSize = 48.sp)
        Spacer(Modifier.height(12.dp))
        Text("Nie masz jeszcze rozmów", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text(
            "Pokaż znajomemu swój kod QR albo wyślij mu link. Gdy go zeskanuje, możecie pisać. " +
                "Możesz też utworzyć grupę i rozesłać link zaproszenia — każdy w grupie może potem pisać do każdego.",
            textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        Button(onClick = { nav.go(Screen.MyCode) }, Modifier.fillMaxWidth()) { Text("Pokaż mój kod") }
        OutlinedButton(onClick = { nav.go(Screen.AddContact()) }, Modifier.fillMaxWidth()) { Text("Dodaj kontakt / dołącz do grupy") }
        OutlinedButton(onClick = { nav.go(Screen.NewGroup) }, Modifier.fillMaxWidth()) { Text("Utwórz grupę") }
        Spacer(Modifier.height(16.dp))
        Text(
            "Znajomi nie mają jeszcze Szepta? Wyślij im aplikację (Quick Share, Bluetooth, komunikator, e-mail).",
            textAlign = TextAlign.Center, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val ctx = LocalContext.current
        TextButton(onClick = { shareApp(ctx) }) { Text("Wyślij aplikację znajomym") }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChatRow(c: Chat, onLong: () -> Unit = {}, onClick: () -> Unit) {
    // awatar: zdjęcie profilowe kontaktu albo ikona grupy
    val image = if (c.isGroup) App.core.db.group(c.id.removePrefix("g:"))?.icon?.ifBlank { null }
    else c.peer?.let { App.core.db.contact(it)?.avatar?.ifBlank { null } }
    Row(
        Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLong).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            Avatar(c.name, c.peer ?: c.id, 50.dp, c.isGroup, image)
            if (!c.isGroup) rememberSeen(c.peer)?.let { (s, _) -> PresenceDot(s, Modifier.align(Alignment.BottomEnd)) }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(c.name, Modifier.weight(1f, fill = false), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    fontWeight = if (c.unread > 0) FontWeight.Bold else FontWeight.Normal)
                if (!c.isGroup && App.core.isVerified(c.peer)) VerifiedBadge()
                if (c.muted) Icon(Icons.Default.NotificationsOff, "Wyciszony", Modifier.padding(start = 4.dp).size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                if (c.hidden) Icon(Icons.Default.Lock, "Ukryty", Modifier.padding(start = 4.dp).size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                if (c.pinned > 0) Icon(Icons.Default.PushPin, "Przypięty", Modifier.padding(start = 4.dp).size(16.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.weight(1f))
                Text(formatListTime(c.lastTs), style = MaterialTheme.typography.labelSmall,
                    color = if (c.unread > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (c.draft.isNotBlank()) Text(
                    "Szkic: " + c.draft.replace('\n', ' '), Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error,
                ) else Text(
                    (if (c.xmode != 0) "🔥 " else "") + c.lastText.ifEmpty { if (c.isGroup) "Grupa" else "Nowa rozmowa" },
                    Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (c.unread > 0) Badge(containerColor = if (c.muted) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.primary) { Text(c.unread.toString()) }
            }
        }
    }
}

// ======================= nowa rozmowa =======================

@Composable
fun NewChatScreen(nav: Nav) {
    val people by rememberDb { App.core.knownPeople().filter { !it.blocked } }
    var query by remember { mutableStateOf("") }
    Scaffold(topBar = { BackBar("Nowa rozmowa", nav) }) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize()) {
            item { ActionRow(Icons.Default.Group, "Nowa grupa") { nav.replace(Screen.NewGroup) } }
            item { ActionRow(Icons.Default.PersonAdd, "Dodaj kontakt", "Skanuj kod QR lub wklej link") { nav.replace(Screen.AddContact()) } }
            item { ActionRow(Icons.Default.Link, "Dołącz do grupy", "Wklej link zaproszenia") { nav.replace(Screen.AddContact()) } }
            item {
                OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    placeholder = { Text("Szukaj osoby") }, singleLine = true)
            }
            val list = people?.filter { query.isBlank() || it.name.contains(query, true) } ?: emptyList()
            if (people?.isEmpty() == true) item {
                Text("Nie znasz jeszcze nikogo. Dodaj kontakt albo dołącz do grupy — członkowie grup pojawią się tutaj.",
                    Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items(list, key = { it.pk }) { p -> PersonRow(p) { nav.replace(Screen.Chat(App.core.openDm(p.pk))) } }
        }
    }
}

@Composable
fun PersonRow(p: Contact, trailing: @Composable () -> Unit = {}, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(p.display, p.pk, 42.dp, image = p.avatar.ifBlank { null })
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(p.name, style = MaterialTheme.typography.titleMedium)
                if (p.verified || pl.szept.core.Admin.isAdmin(p.pk)) VerifiedBadge()
            }
            Text(if (p.isContact) "Kontakt" else "Z grupy", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        trailing()
    }
}

/** Tworzenie grupy (gid == null) albo dodawanie członków do istniejącej. */
@Composable
fun PickPeopleScreen(nav: Nav, gid: String?) {
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    val group by rememberDb(gid) { gid?.let { App.core.db.group(it) } }
    val people by rememberDb { App.core.knownPeople().filter { !it.blocked } }
    val selected = remember { mutableStateListOf<String>() }
    var name by remember { mutableStateOf("") }
    Scaffold(
        topBar = { BackBar(if (gid == null) "Nowa grupa" else "Dodaj członków", nav) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = {
                    scope.launch {
                        if (gid == null) {
                            val chat = App.core.createGroup(name.ifBlank { "Grupa" }, selected.toList())
                            nav.replace(Screen.Chat(chat))
                        } else {
                            if (App.core.addMembers(gid, selected.toList())) nav.back() else toast(ctx, "Tylko administrator może dodawać")
                        }
                    }
                },
                icon = { Icon(Icons.Default.Check, null) },
                text = { Text(if (gid == null) "Utwórz" else "Dodaj (${selected.size})") },
            )
        },
    ) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize().imePadding()) {
            if (gid == null) item {
                OutlinedTextField(name, { name = it.take(60) }, Modifier.fillMaxWidth().padding(16.dp), label = { Text("Nazwa grupy") }, singleLine = true)
                Text(
                    "Wybierz osoby teraz albo później udostępnij link zaproszenia (Informacje o grupie → Link). Grupa nie ma limitu osób.",
                    Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            val list = people?.filter { p -> group?.isMember(p.pk) != true } ?: emptyList()
            if (people != null && list.isEmpty()) item {
                Text("Brak osób do wybrania. Dodaj kontakty kodem QR albo rozešlij link zaproszenia.", Modifier.padding(16.dp))
            }
            items(list, key = { it.pk }) { p ->
                PersonRow(p, trailing = { Checkbox(p.pk in selected, { on -> if (on) selected.add(p.pk) else selected.remove(p.pk) }) }) {
                    if (p.pk in selected) selected.remove(p.pk) else selected.add(p.pk)
                }
            }
            item { Spacer(Modifier.height(80.dp)) }
        }
    }
}

// ======================= dodawanie kontaktu / dołączanie =======================

@Composable
fun AddContactScreen(nav: Nav, prefill: String?) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var code by remember { mutableStateOf(prefill ?: "") }
    var error by remember { mutableStateOf<String?>(null) }
    fun submit(text: String) {
        when (val p = Links.parse(text)) {
            is Links.Parsed.ContactCode -> {
                if (p.pk == App.core.myPub) { error = "To Twój własny kod 🙂"; return }
                App.core.addContact(p.pk, p.name)?.let { nav.replace(Screen.Chat(it)) }
                toast(ctx, "Dodano kontakt ${p.name}")
            }
            is Links.Parsed.GroupInvite -> scope.launch { nav.replace(Screen.Chat(App.core.joinGroup(p))) }
            is Links.Parsed.Relays -> {
                val cur = App.core.configuredRelays()
                val added = p.urls.filter { it !in cur }
                App.core.setRelays((cur + added).distinct())
                toast(ctx, if (added.isEmpty()) "Masz już te przekaźniki" else "Dodano przekaźniki: ${added.size}")
                nav.back()
            }
            is Links.Parsed.Lan -> {
                App.core.setLanEnabled(true)
                if (!App.core.lan.joinNetwork(p.ssid, p.pass, p.host, p.port))
                    toast(ctx, "Połącz się ręcznie z siecią Wi-Fi „${p.ssid}” (hasło: ${p.pass}) — Szept sam znajdzie przekaźnik")
                nav.replace(Screen.Lan)
            }
            null -> error = "Nie rozpoznano kodu. Wklej link zaczynający się od szept://"
        }
    }
    val scan = rememberQrScanner { code = it; submit(it) }
    val parsed = remember(code) { Links.parse(code) }
    Scaffold(topBar = { BackBar("Dodaj kontakt / grupę", nav) }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(16.dp)) {
            Button(onClick = scan, Modifier.fillMaxWidth().height(56.dp)) {
                Icon(Icons.Default.QrCodeScanner, null); Spacer(Modifier.width(8.dp)); Text("Skanuj kod QR")
            }
            Spacer(Modifier.height(20.dp))
            Text("…albo wklej kod / link otrzymany od znajomego:", style = MaterialTheme.typography.bodyMedium)
            OutlinedTextField(
                code, { code = it; error = null }, Modifier.fillMaxWidth(), placeholder = { Text("szept://…") }, minLines = 2,
                trailingIcon = {
                    IconButton(onClick = {
                        val clip = ctx.getSystemService(android.content.ClipboardManager::class.java).primaryClip
                        val t = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(ctx)?.toString()
                        if (t.isNullOrBlank()) toast(ctx, "Schowek jest pusty") else { code = t.trim(); error = null }
                    }) { Icon(Icons.Default.ContentPaste, "Wklej") }
                },
            )
            when (parsed) {
                is Links.Parsed.ContactCode -> Text("Kontakt: ${parsed.name.ifBlank { parsed.pk.take(12) }}", Modifier.padding(top = 6.dp), color = MaterialTheme.colorScheme.primary)
                is Links.Parsed.GroupInvite -> Text("Zaproszenie do grupy „${parsed.name}”", Modifier.padding(top = 6.dp), color = MaterialTheme.colorScheme.primary)
                is Links.Parsed.Relays -> Text("Przekaźniki: " + parsed.urls.joinToString { it.removePrefix("wss://") }, Modifier.padding(top = 6.dp), color = MaterialTheme.colorScheme.primary)
                is Links.Parsed.Lan -> Text("Sieć bez internetu: „${parsed.ssid}”", Modifier.padding(top = 6.dp), color = MaterialTheme.colorScheme.primary)
                null -> {}
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 6.dp)) }
            Spacer(Modifier.height(8.dp))
            Button(onClick = { submit(code) }, Modifier.fillMaxWidth(), enabled = code.isNotBlank()) {
                Text(
                    when (parsed) {
                        is Links.Parsed.GroupInvite -> "Dołącz do grupy"
                        is Links.Parsed.Relays -> "Dodaj przekaźniki"
                        is Links.Parsed.Lan -> "Dołącz do sieci"
                        else -> "Dodaj kontakt"
                    },
                )
            }
            Spacer(Modifier.height(24.dp))
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))
            Text("Twój kod", style = MaterialTheme.typography.titleMedium)
            Text("Znajomy może zeskanować Twój kod lub otworzyć link — wtedy oboje będziecie mieli się w kontaktach.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { nav.replace(Screen.MyCode) }, Modifier.fillMaxWidth()) {
                Icon(Icons.Default.QrCode, null); Spacer(Modifier.width(8.dp)); Text("Pokaż mój kod QR")
            }
        }
    }
}

@Composable
fun MyCodeScreen(nav: Nav) {
    val ctx = LocalContext.current
    val link = App.core.myCode()
    val scan = rememberQrScanner { code -> nav.replace(Screen.AddContact(code)) }
    Scaffold(topBar = { BackBar("Mój kod", nav) }) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Avatar(App.core.myName, App.core.myPub, 64.dp)
            Spacer(Modifier.height(8.dp))
            Text(App.core.myName, style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(16.dp))
            QrImage(link, 280.dp)
            Spacer(Modifier.height(16.dp))
            Text("Pokaż ten kod znajomemu do zeskanowania (Szept → Dodaj kontakt → Skanuj) albo wyślij mu link dowolnym komunikatorem/SMS-em.",
                textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(16.dp))
            Button(onClick = { shareText(ctx, "Dodaj mnie w Szept: $link") }, Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Share, null); Spacer(Modifier.width(8.dp)); Text("Wyślij link")
            }
            OutlinedButton(onClick = { copyText(ctx, link) }, Modifier.fillMaxWidth()) {
                Icon(Icons.Default.ContentCopy, null); Spacer(Modifier.width(8.dp)); Text("Kopiuj link")
            }
            Spacer(Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))
            Text("Masz kod znajomego?", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Button(onClick = scan, Modifier.fillMaxWidth().height(56.dp)) {
                Icon(Icons.Default.QrCodeScanner, null); Spacer(Modifier.width(8.dp)); Text("Skanuj kod QR")
            }
        }
    }
}
