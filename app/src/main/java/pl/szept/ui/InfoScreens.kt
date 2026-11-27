package pl.szept.ui

import pl.szept.tr
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.MediaController
import android.widget.VideoView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.ui.unit.sp
import pl.szept.core.MediaUtil
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.GroupAdd
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.WifiTethering
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LifecycleResumeEffect
import kotlinx.coroutines.launch
import pl.szept.App
import pl.szept.crypto.hex
import pl.szept.data.GroupState
import pl.szept.data.Member
import pl.szept.nostr.Relay

// ======================= podgląd mediów =======================

@Composable
fun VideoPlayer(path: String, modifier: Modifier) {
    AndroidView(
        factory = { c ->
            VideoView(c).apply {
                val mc = MediaController(c)
                mc.setAnchorView(this)
                setMediaController(mc)
                setVideoPath(path)
                setOnPreparedListener { it.start() }
            }
        },
        modifier = modifier,
        onRelease = { it.stopPlayback() },
    )
}

// ======================= informacje o grupie =======================

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GroupInfoScreen(nav: Nav, gid: String) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val core = App.core
    val g by rememberDb(gid) { core.db.group(gid) }
    val chat by rememberDb(gid) { core.db.chat("g:$gid") }
    val names by rememberDb(gid) { core.db.contacts(false).associate { it.pk to it.display } }
    val requests by rememberDb(gid) { core.db.joinReqs(gid) }
    var descrDialog by remember { mutableStateOf(false) }
    var limitedDialog by remember { mutableStateOf(false) }
    val group = g ?: return
    val me = core.myPub
    val iAmAdmin = group.isAdmin(me) && group.state == GroupState.ACTIVE
    var rename by remember { mutableStateOf(false) }
    var invite by remember { mutableStateOf(false) }
    var lookDialog by remember { mutableStateOf(false) }
    var memberMenu by remember { mutableStateOf<Member?>(null) }
    var muteFor by remember { mutableStateOf<Member?>(null) }
    var confirm by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }
    val pickIcon = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch { runCatching { core.setGroupLook(gid, MediaUtil.prepareAvatar(ctx, uri).second, null) }.onFailure { toast(ctx, tr("Nie udało się")) } }
    }

    Scaffold(topBar = { BackBar(tr("Informacje o grupie"), nav) }) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize()) {
            item {
                Column(Modifier.fillMaxWidth().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box {
                        Avatar(group.name, "g:$gid", 72.dp, true, group.icon.ifBlank { null })
                        if (iAmAdmin) IconButton(
                            onClick = { lookDialog = true },
                            modifier = Modifier.align(Alignment.BottomEnd).size(26.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary),
                        ) { Icon(Icons.Default.CameraAlt, tr("Zmień ikonę"), Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onPrimary) }
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(group.name, style = MaterialTheme.typography.headlineSmall)
                        if (iAmAdmin) IconButton(onClick = { rename = true }) { Icon(Icons.Default.Edit, tr("Zmień nazwę")) }
                    }
                    Text(tr("{0} członków", group.members.size), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (group.descr.isNotBlank()) Card(Modifier.fillMaxWidth().padding(top = 10.dp)) {
                        Text(group.descr, Modifier.padding(12.dp))
                    }
                    if (iAmAdmin) TextButton(onClick = { descrDialog = true }) { Text(if (group.descr.isBlank()) tr("Dodaj opis grupy") else tr("Zmień opis")) }
                    if (group.state != GroupState.ACTIVE) Text(
                        when (group.state) {
                            GroupState.PENDING -> tr("Oczekiwanie na akceptację"); GroupState.REMOVED -> tr("Nie jesteś członkiem")
                            GroupState.LEFT -> tr("Opuściłeś(-aś) grupę"); else -> tr("Grupa rozwiązana")
                        }, color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            if (group.state == GroupState.ACTIVE) {
                item {
                    ActionRow(
                        Icons.Default.Link, tr("Link zaproszenia"),
                        if (group.approve) tr("Nowe osoby czekają na Twoje zatwierdzenie") else tr("Każdy z linkiem może dołączyć (akceptuje admin online)"),
                    ) { invite = true }
                }
                if (iAmAdmin) {
                    val reqs = requests ?: emptyList()
                    if (reqs.isNotEmpty()) item {
                        Text(tr("Prośby o dołączenie ({0})", reqs.size), Modifier.padding(start = 16.dp, top = 8.dp), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                    }
                    items(reqs, key = { "req" + it.pk }) { r ->
                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            ContactAvatar(core.db.contact(r.pk), 40.dp)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(r.name.ifBlank { core.db.nameOf(r.pk) }, style = MaterialTheme.typography.titleMedium)
                                Text(pl.szept.data.shortId(r.pk), style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
                            }
                            TextButton(onClick = { core.rejectJoin(gid, r.pk) }) { Text(tr("Odrzuć"), color = MaterialTheme.colorScheme.error) }
                            Button(onClick = { scope.launch { if (!core.acceptJoin(gid, r.pk)) toast(ctx, tr("Nie udało się")) } }) { Text(tr("Przyjmij")) }
                        }
                    }
                    item { ActionRow(Icons.Default.GroupAdd, tr("Dodaj członków")) { nav.go(Screen.AddMembers(gid)) } }
                    item {
                        ListItem(
                            headlineContent = { Text(tr("Zatwierdzaj nowe osoby")) },
                            supportingContent = { Text(tr("Osoby z linku czekają, aż administrator je przyjmie")) },
                            trailingContent = { Switch(group.approve, { on -> scope.launch { core.setApprove(gid, on) } }) },
                        )
                    }
                    item {
                        ListItem(
                            headlineContent = { Text(tr("Tylko administratorzy piszą")) },
                            supportingContent = { Text(tr("Pozostali mogą tylko czytać (i reagować)")) },
                            trailingContent = { Switch(group.adminsOnly, { on -> scope.launch { core.setAdminsOnly(gid, on) } }) },
                        )
                    }
                }
            }
            item { ActionRow(Icons.Default.Image, tr("Multimedia, pliki i linki")) { nav.go(Screen.Media("g:$gid")) } }
            item {
                ActionRow(Icons.Default.MusicNote, tr("Dźwięk powiadomień tej grupy"), tr("Własny dźwięk i wibracje")) {
                    pl.szept.service.AndroidNotifier.openChatSound(ctx, "g:$gid", group.name)
                }
            }
            item { HorizontalDivider(); Text(tr("Członkowie"), Modifier.padding(16.dp), style = MaterialTheme.typography.titleSmall) }
            item {
                ListItem(
                    headlineContent = { Text(tr("Wycisz powiadomienia")) },
                    supportingContent = { Text(tr("Wiadomości nadal przychodzą, ale bez dźwięku i powiadomień")) },
                    trailingContent = { Switch(chat?.muted == true, { on -> core.setMuted("g:$gid", on) }) },
                )
                AutoCleanRow("g:$gid")
            }
            items(group.members.sortedWith(compareBy({ !group.isAdmin(it.pk) }, { it.name.lowercase() })), key = { it.pk }) { mem ->
                val name = if (mem.pk == me) "Ty" else names?.get(mem.pk) ?: mem.name.ifBlank { mem.pk.take(8) }
                Row(
                    Modifier.fillMaxWidth().clickable(enabled = mem.pk != me) { memberMenu = mem }.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Avatar(name, mem.pk, 40.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(name, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    if (group.isMuted(mem.pk, System.currentTimeMillis())) Text(tr("🔇 wyciszony(-a)  "), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelMedium)
                    if (group.isAdmin(mem.pk)) Text("admin", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
                }
            }
            if (iAmAdmin && group.banned.isNotEmpty()) {
                item { Text(tr("Zbanowani (nie wrócą z linku)"), Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp), style = MaterialTheme.typography.titleSmall) }
                items(group.banned, key = { "bn_$it" }) { pk ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Avatar(names?.get(pk) ?: core.db.nameOf(pk), pk, 32.dp)
                        Spacer(Modifier.width(12.dp))
                        Text(names?.get(pk) ?: core.db.nameOf(pk), Modifier.weight(1f))
                        TextButton(onClick = { scope.launch { core.unbanMember(gid, pk) } }) { Text(tr("Odbanuj")) }
                    }
                }
            }
            item {
                HorizontalDivider()
                if (group.state == GroupState.ACTIVE || group.state == GroupState.PENDING) {
                    ActionRow(Icons.AutoMirrored.Filled.Logout, tr("Opuść grupę")) {
                        confirm = tr("Opuścić grupę „{0}”?", group.name) to { scope.launch { core.leaveGroup(gid) } }
                    }
                }
                if (iAmAdmin) ActionRow(Icons.Default.Block, tr("Rozwiąż grupę"), tr("Grupa zostanie zamknięta dla wszystkich")) {
                    confirm = tr("Rozwiązać grupę dla wszystkich członków?") to { scope.launch { core.closeGroup(gid) } }
                }
                ActionRow(Icons.Default.Delete, tr("Usuń czat u mnie")) {
                    confirm = tr("Usunąć całą historię tej grupy z telefonu?") to { core.deleteChat("g:$gid"); nav.home() }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    if (lookDialog) {
        val emojis = listOf("👥", "🎉", "⚽", "🎮", "🎵", "🍕", "💼", "📚", "🏠", "✈️", "🔥", "💬", "🐱", "🌍", "❤️", "⭐")
        StyleAlertDialog(
            onDismissRequest = { lookDialog = false },
            title = { Text(tr("Ikona grupy")) },
            text = {
                Column {
                    OutlinedButton(onClick = { lookDialog = false; pickIcon.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.CameraAlt, null); Spacer(Modifier.width(6.dp)); Text(tr("Wybierz zdjęcie"))
                    }
                    Text(tr("albo wybierz emoji:"), Modifier.padding(vertical = 8.dp), style = MaterialTheme.typography.bodySmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        emojis.forEach { e ->
                            Text(e, Modifier.clip(CircleShape).clickable { scope.launch { core.setGroupLook(gid, e, null) }; lookDialog = false }.padding(8.dp), fontSize = 26.sp)
                        }
                    }
                    if (group.icon.isNotEmpty()) TextButton(onClick = { scope.launch { core.setGroupLook(gid, "", null) }; lookDialog = false }) { Text(tr("Usuń ikonę")) }
                }
            },
            confirmButton = { TextButton(onClick = { lookDialog = false }) { Text(tr("Zamknij")) } },
        )
    }
    if (rename) {
        var name by remember { mutableStateOf(group.name) }
        StyleAlertDialog(
            onDismissRequest = { rename = false },
            title = { Text(tr("Nazwa grupy")) },
            text = { OutlinedTextField(name, { name = it.take(60) }, singleLine = true) },
            confirmButton = { TextButton(onClick = { scope.launch { core.renameGroup(gid, name) }; rename = false }) { Text(tr("Zapisz")) } },
            dismissButton = { TextButton(onClick = { rename = false }) { Text(tr("Anuluj")) } },
        )
    }
    if (invite) {
        val link = core.groupInvite(gid) ?: ""
        StyleAlertDialog(
            onDismissRequest = { invite = false },
            title = { Text(tr("Zaproszenie do „{0}”", group.name)) },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    QrImage(link, 220.dp)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        if (group.approve) tr("Zeskanuj w Szepcie albo wyślij link. Każdą prośbę musi zatwierdzić administrator.")
                        else tr("Zeskanuj w Szepcie albo wyślij link. Prośbę zaakceptuje automatycznie pierwszy administrator, który będzie online."),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (iAmAdmin) TextButton(onClick = { scope.launch { core.resetInvite(gid); toast(ctx, tr("Stary link przestał działać")) } }) { Text(tr("Unieważnij i utwórz nowy link")) }
                    if (iAmAdmin) TextButton(onClick = { invite = false; limitedDialog = true }) { Text(tr("Link z limitem czasu lub osób…")) }
                }
            },
            confirmButton = { TextButton(onClick = { shareText(ctx, tr("Dołącz do grupy „{0}” w Szept: {1}", group.name, link)) }) { Text(tr("Wyślij link")) } },
            dismissButton = { TextButton(onClick = { copyText(ctx, link) }) { Text(tr("Kopiuj")) } },
        )
    }
    if (descrDialog) {
        var d by remember { mutableStateOf(group.descr) }
        StyleAlertDialog(
            onDismissRequest = { descrDialog = false },
            title = { Text(tr("Opis grupy")) },
            text = { OutlinedTextField(d, { d = it.take(1000) }, minLines = 3, maxLines = 8, placeholder = { Text(tr("O czym jest ta grupa? Zasady?")) }) },
            confirmButton = { TextButton(onClick = { scope.launch { core.setGroupDescr(gid, d) }; descrDialog = false }) { Text(tr("Zapisz")) } },
            dismissButton = { TextButton(onClick = { descrDialog = false }) { Text(tr("Anuluj")) } },
        )
    }
    if (limitedDialog) LimitedInviteDialog(gid, group) { limitedDialog = false }
    muteFor?.let { mem ->
        StyleAlertDialog(
            onDismissRequest = { muteFor = null },
            title = { Text(tr("Wycisz: {0}", names?.get(mem.pk) ?: mem.name)) },
            text = {
                Column {
                    Text(tr("Ta osoba dalej czyta grupę, ale nie może w niej pisać ani reagować."), style = MaterialTheme.typography.bodySmall)
                    listOf(tr("1 godzina") to 3600_000L, tr("8 godzin") to 8 * 3600_000L, tr("1 dzień") to 86400_000L, tr("7 dni") to 7 * 86400_000L, tr("Do odwołania") to Long.MAX_VALUE).forEach { (t, ms) ->
                        Text(t, Modifier.fillMaxWidth().clickable {
                            val until = if (ms == Long.MAX_VALUE) Long.MAX_VALUE else System.currentTimeMillis() + ms
                            scope.launch { core.muteMember(gid, mem.pk, until) }; muteFor = null
                        }.padding(vertical = 12.dp), style = MaterialTheme.typography.bodyLarge)
                    }
                }
            },
            confirmButton = { TextButton(onClick = { muteFor = null }) { Text(tr("Anuluj")) } },
        )
    }
    memberMenu?.let { mem ->
        val name = names?.get(mem.pk) ?: mem.name
        StyleAlertDialog(
            onDismissRequest = { memberMenu = null },
            title = { Text(name) },
            text = {
                Column {
                    @Composable
                    fun item(t: String, f: () -> Unit) {
                        Text(t, Modifier.fillMaxWidth().clickable { f(); memberMenu = null }.padding(vertical = 12.dp), style = MaterialTheme.typography.bodyLarge)
                    }
                    item(tr("Napisz prywatnie")) { nav.go(Screen.Chat(core.openDm(mem.pk))) }
                    if (iAmAdmin) {
                        if (group.isAdmin(mem.pk)) item(tr("Odbierz uprawnienia admina")) { scope.launch { core.setAdmin(gid, mem.pk, false) } }
                        else item(tr("Mianuj administratorem")) { scope.launch { core.setAdmin(gid, mem.pk, true) } }
                        if (!group.isAdmin(mem.pk)) {
                            if (group.isMuted(mem.pk, System.currentTimeMillis())) item(tr("🔊 Cofnij wyciszenie")) { scope.launch { core.muteMember(gid, mem.pk, 0) } }
                            else item(tr("🔇 Wycisz…")) { muteFor = mem }
                        }
                        item(tr("Usuń z grupy")) { scope.launch { core.removeMember(gid, mem.pk) } }
                        item(tr("⛔ Zbanuj (bez powrotu z linku)")) { scope.launch { core.banMember(gid, mem.pk) } }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { memberMenu = null }) { Text(tr("Zamknij")) } },
        )
    }
    confirm?.let { (q, action) ->
        StyleAlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(q) },
            confirmButton = { TextButton(onClick = { action(); confirm = null }) { Text("Tak") } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text(tr("Anuluj")) } },
        )
    }
}

/** Linki zaproszenia z limitem czasu i/lub liczby osób. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LimitedInviteDialog(gid: String, group: pl.szept.data.Group, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val core = App.core
    var validMs by remember { mutableStateOf(86400_000L) }
    var maxUses by remember { mutableStateOf(1) }
    var created by remember { mutableStateOf<String?>(null) }
    val now = System.currentTimeMillis()
    StyleAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Link z limitem")) },
        text = {
            Column(Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState())) {
                val c = created
                if (c != null) {
                    QrImage(c, 200.dp)
                    Text(tr("Link gotowy. Wyślij go wybranym osobom."), style = MaterialTheme.typography.bodySmall)
                    Row {
                        TextButton(onClick = { shareText(ctx, tr("Dołącz do grupy „{0}” w Szept: {1}", group.name, c)) }) { Text(tr("Wyślij")) }
                        TextButton(onClick = { copyText(ctx, c) }) { Text(tr("Kopiuj")) }
                    }
                    HorizontalDivider()
                } else {
                    Text(tr("Ważny przez:"), fontWeight = FontWeight.Medium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(3600_000L to tr("1 h"), 86400_000L to tr("1 dzień"), 7 * 86400_000L to tr("7 dni"), 0L to tr("bez limitu")).forEach { (v, t) ->
                            androidx.compose.material3.FilterChip(validMs == v, { validMs = v }, { Text(t) })
                        }
                    }
                    Text(tr("Dla ilu osób:"), fontWeight = FontWeight.Medium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(1 to tr("1 osoba"), 5 to "5", 10 to "10", 25 to "25", 0 to tr("bez limitu")).forEach { (v, t) ->
                            androidx.compose.material3.FilterChip(maxUses == v, { maxUses = v }, { Text(t) })
                        }
                    }
                    Button(onClick = {
                        scope.launch { created = core.createInvite(gid, validMs, maxUses) ?: run { toast(ctx, tr("Nie udało się")); null } }
                    }, Modifier.fillMaxWidth()) { Text(tr("Utwórz link")) }
                }
                val active = group.invites.filter { it.valid(now) }
                if (active.isNotEmpty()) {
                    Text(tr("Aktywne linki z limitem"), Modifier.padding(top = 10.dp), fontWeight = FontWeight.Medium)
                    active.forEach { inv ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text((if (inv.max > 0) tr("użyto {0}/{1}", inv.used, inv.max) else tr("użyto {0}", inv.used)) + if (inv.exp > 0) tr(" · do {0}", formatWhen(inv.exp)) else tr(" · bez limitu czasu"),
                                    style = MaterialTheme.typography.bodySmall)
                            }
                            TextButton(onClick = { core.inviteLink(gid, inv.token)?.let { copyText(ctx, it) } }) { Text(tr("Kopiuj")) }
                            TextButton(onClick = { scope.launch { core.revokeInvite(gid, inv.token) } }) { Text(tr("Unieważnij"), color = MaterialTheme.colorScheme.error) }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(tr("Zamknij")) } },
    )
}

// ======================= informacje o kontakcie =======================

fun fingerprint(pk: String) = pk.take(32).chunked(4).joinToString(" ")

@Composable
fun ContactInfoScreen(nav: Nav, pk: String) {
    val core = App.core
    val c by rememberDb(pk) { core.db.contact(pk) }
    val dm by rememberDb(pk) { core.db.chat("d:$pk") }
    var confirmDelete by remember { mutableStateOf(false) }
    val contact = c
    Scaffold(topBar = { BackBar(tr("Kontakt"), nav) }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Avatar(contact?.name ?: "", pk, 80.dp)
            Spacer(Modifier.height(10.dp))
            Text(contact?.name ?: "", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(16.dp))
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Lock, null, tint = OkGreen); Spacer(Modifier.width(6.dp))
                        Text(tr("Odcisk klucza"), fontWeight = FontWeight.Medium)
                    }
                    Text(fingerprint(pk), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
                    Text(tr("Porównajcie te znaki na żywo, aby mieć pewność, że rozmawiasz z właściwą osobą."),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(16.dp))
            if (contact?.isContact == false) {
                Button(onClick = { core.addContact(pk, contact.name) }, Modifier.fillMaxWidth()) { Text(tr("Dodaj do kontaktów")) }
            }
            OutlinedButton(onClick = { nav.go(Screen.Chat(core.openDm(pk))) }, Modifier.fillMaxWidth()) {
                Icon(Icons.AutoMirrored.Filled.Chat, null); Spacer(Modifier.width(6.dp)); Text(tr("Napisz"))
            }
            if (dm != null) ListItem(
                headlineContent = { Text(tr("Wycisz powiadomienia")) },
                trailingContent = { Switch(dm?.muted == true, { on -> core.setMuted("d:$pk", on) }) },
            )
            OutlinedButton(onClick = { core.setBlocked(pk, !(contact?.blocked ?: false)) }, Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Block, null); Spacer(Modifier.width(6.dp))
                Text(if (contact?.blocked == true) tr("Odblokuj") else tr("Zablokuj (nie odbieraj wiadomości prywatnych)"))
            }
            OutlinedButton(onClick = { confirmDelete = true }, Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Delete, null); Spacer(Modifier.width(6.dp)); Text(tr("Usuń czat u mnie"))
            }
        }
    }
    if (confirmDelete) StyleAlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text(tr("Usunąć całą rozmowę z telefonu?")) },
        confirmButton = { TextButton(onClick = { core.deleteChat("d:$pk"); confirmDelete = false; nav.home() }) { Text(tr("Usuń")) } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(tr("Anuluj")) } },
    )
}

// ======================= ustawienia =======================

@Composable
private fun Section(title: String) {
    Text(title, Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
}
