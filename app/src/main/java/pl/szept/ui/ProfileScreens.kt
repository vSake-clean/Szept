package pl.szept.ui

import pl.szept.tr
import androidx.compose.material3.ButtonDefaults

import androidx.compose.material.icons.filled.PersonRemove

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Checkbox
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
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import pl.szept.App
import pl.szept.core.MediaUtil
import pl.szept.crypto.hex
import pl.szept.data.Member
import pl.szept.data.dmChatId

@Composable
private fun SectionTitle(t: String) =
    Text(t, Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)

/** Baner + nachodzący awatar — wspólny nagłówek profilu (mój i cudzy). */
@Composable
private fun ProfileHeader(name: String, key: String, avatar: String?, banner: String?, onBanner: (() -> Unit)? = null, onAvatar: (() -> Unit)? = null) {
    val bannerBmp = remember(banner) { if (isImageData(banner)) Images.thumb(banner!!) else null }
    Box(Modifier.fillMaxWidth()) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(3f).clickable(enabled = onBanner != null) { onBanner?.invoke() }
                .background(avatarColor(key).copy(alpha = 0.35f)),
            contentAlignment = Alignment.Center,
        ) {
            if (bannerBmp != null) BitmapImage(bannerBmp, Modifier.fillMaxSize())
            if (onBanner != null) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.15f)), Alignment.TopEnd) {
                Icon(Icons.Default.CameraAlt, tr("Zmień baner"), Modifier.padding(8.dp), tint = Color.White)
            }
        }
        Box(Modifier.padding(start = 16.dp).padding(top = 56.dp)) {
            Box(Modifier.size(96.dp).clip(CircleShape).background(baseColor()).padding(3.dp)) {
                Avatar(name, key, 90.dp, image = avatar)
            }
            if (onAvatar != null) IconButton(
                onClick = onAvatar,
                modifier = Modifier.align(Alignment.BottomEnd).size(30.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary),
            ) { Icon(Icons.Default.CameraAlt, tr("Zmień zdjęcie"), Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onPrimary) }
        }
    }
}

// ======================= mój profil =======================

@Composable
fun MyProfileScreen(nav: Nav) {
    val ctx = LocalContext.current
    val core = App.core
    val changes by core.db.changes.collectAsState()
    var name by remember { mutableStateOf(core.myName) }
    var status by remember(changes) { mutableStateOf(core.myStatus) }
    var bio by remember(changes) { mutableStateOf(core.myBio) }
    // wersja obrazów: rośnie po każdej zmianie, więc ekran odświeża się od razu
    var imgVer by remember { mutableIntStateOf(0) }
    val avatar = remember(changes, imgVer) { if (core.avatarFile.exists()) pl.szept.crypto.B64.enc(core.avatarFile.readBytes()) else null }
    val banner = remember(changes, imgVer) { if (core.bannerFile.exists()) pl.szept.crypto.B64.enc(core.bannerFile.readBytes()) else null }
    val scope = rememberCoroutineScope()
    // zdjęcie wybrane do kadrowania: (plik, czy_baner)
    var editing by remember { mutableStateOf<Pair<java.io.File, Boolean>?>(null) }

    fun picked(uri: Uri?, banner: Boolean) {
        if (uri == null) return
        scope.launch {
            try { editing = MediaUtil.prepareForEdit(ctx, uri) to banner } catch (e: Exception) { toast(ctx, tr("Nie udało się otworzyć zdjęcia")) }
        }
    }
    val pickAvatar = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { picked(it, false) }
    val pickBanner = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { picked(it, true) }

    editing?.let { (file, isBanner) ->
        ImageEditorDialog(file, onCancel = { file.delete(); editing = null }, startRatio = if (isBanner) 3f else 1f) { edited ->
            editing = null
            scope.launch {
                try {
                    val u = Uri.fromFile(edited.file)
                    val out = if (isBanner) MediaUtil.prepareBanner(ctx, u).first else MediaUtil.prepareAvatar(ctx, u).first
                    if (isBanner) core.setBanner(out) else core.setAvatar(out)
                    imgVer++
                } catch (e: Exception) { toast(ctx, tr("Nie udało się zapisać zdjęcia")) }
                finally { edited.file.delete(); file.delete() }
            }
        }
    }

    Scaffold(topBar = { BackBar(tr("Mój profil"), nav) }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().imePadding().verticalScroll(rememberScrollState())) {
            ProfileHeader(
                name, core.myPub, avatar, banner,
                onBanner = { pickBanner.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                onAvatar = { pickAvatar.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
            )
            Spacer(Modifier.height(10.dp))
            MyPresenceCard()
            Column(Modifier.padding(16.dp)) {
                OutlinedTextField(name, { name = it.take(60) }, Modifier.fillMaxWidth(), label = { Text(tr("Nazwa")) }, singleLine = true)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(status, { status = it.take(120) }, Modifier.fillMaxWidth(), label = { Text(tr("Status (np. „🎮 gram”)")) }, singleLine = true)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(bio, { bio = it.take(500) }, Modifier.fillMaxWidth(), label = { Text(tr("O mnie / opis")) }, minLines = 2, maxLines = 5)
                Spacer(Modifier.height(10.dp))
                Button(onClick = {
                    if (name.isNotBlank()) core.setName(name)
                    core.setStatusBio(status, bio)
                    toast(ctx, tr("Zapisano profil"))
                }, Modifier.fillMaxWidth()) { Text(tr("Zapisz i wyślij znajomym")) }
                Text(
                    tr("Zmiany trafiają do Twoich kontaktów i grup przy najbliższym połączeniu."),
                    Modifier.padding(top = 6.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (avatar != null || banner != null) TextButton(onClick = { core.setAvatar(null); core.setBanner(null); imgVer++ }) { Text(tr("Usuń zdjęcie i baner")) }
            }
            HorizontalDivider()
            ListItem(
                leadingContent = { Icon(Icons.Default.ContentCopy, null) },
                headlineContent = { Text(tr("Twój stały ID")) },
                supportingContent = { Text(core.myShortId, fontFamily = FontFamily.Monospace) },
                modifier = Modifier.clickable { copyText(ctx, core.myShortId) },
            )
            Text(
                tr("Ten identyfikator pochodzi z Twojego klucza i nigdy się nie zmienia — nawet gdy zmienisz nazwę. Inni rozpoznają Cię po nim."),
                Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(20.dp))
        }
    }
}

// ======================= profil kontaktu =======================

@Composable
fun ProfileView(nav: Nav, pk: String) {
    val ctx = LocalContext.current
    val core = App.core
    val startCall = rememberCallStarter()
    val c by rememberDb(pk) { core.db.contact(pk) }
    val dm by rememberDb(pk) { core.db.chat(dmChatId(pk)) }
    val streak by rememberDb(pk) { core.streakDays(dmChatId(pk)) }
    var aliasDialog by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmUnfriend by remember { mutableStateOf(false) }
    val contact = c

    Scaffold(topBar = { BackBar(tr("Profil"), nav) }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState())) {
            ProfileHeader(contact?.display ?: "", pk, contact?.avatar?.ifBlank { null }, contact?.banner?.ifBlank { null })
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(contact?.display ?: "", style = MaterialTheme.typography.headlineSmall)
                    if (core.isVerified(pk)) VerifiedBadge(22.dp)
                    IconButton(onClick = { aliasDialog = true }) { Icon(Icons.Default.Edit, tr("Zmień nazwę u siebie"), Modifier.size(20.dp)) }
                }
                rememberSeen(pk)?.let { (st, at) ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 2.dp)) {
                        PresenceDot(st, size = 12.dp)
                        Spacer(Modifier.width(6.dp))
                        Text(presenceText(st, at), style = MaterialTheme.typography.labelLarge, color = presenceColor(st))
                    }
                }
                if (!contact?.status.isNullOrBlank()) Text(contact!!.status, color = MaterialTheme.colorScheme.primary)
                if ((streak ?: 0) > 0) Text(tr("🔥 {0} dni z rzędu", streak), style = MaterialTheme.typography.labelLarge, color = Fire, modifier = Modifier.padding(top = 4.dp))
                if (!contact?.bio.isNullOrBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Card(Modifier.fillMaxWidth()) { Text(contact!!.bio, Modifier.padding(12.dp)) }
                }
                Spacer(Modifier.height(10.dp))
                if (pk != core.myPub) SafetyCard(pk) else Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp)) {
                        Text(tr("Stały ID: {0}", pl.szept.data.shortId(pk)), fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium)
                        Spacer(Modifier.height(6.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Lock, null, tint = OkGreen, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
                            Text(tr("Odcisk klucza"), fontWeight = FontWeight.Medium)
                        }
                        Text(fingerprint(pk), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                        Text(tr("Porównajcie te znaki na żywo, aby mieć pewność, że to właściwa osoba."),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Spacer(Modifier.height(12.dp))
                if (contact?.isContact == false) Button(onClick = { core.addContact(pk, contact.name) }, Modifier.fillMaxWidth()) { Text(tr("Dodaj do kontaktów")) }
                OutlinedButton(onClick = { nav.go(Screen.Chat(core.openDm(pk))) }, Modifier.fillMaxWidth()) {
                    Icon(Icons.AutoMirrored.Filled.Chat, null); Spacer(Modifier.width(6.dp)); Text(tr("Napisz"))
                }
                if (pk != core.myPub && contact?.blocked != true) Row(Modifier.fillMaxWidth()) {
                    OutlinedButton(onClick = { startCall(pk, false) }, Modifier.weight(1f)) {
                        Icon(Icons.Default.Call, null); Spacer(Modifier.width(6.dp)); Text(tr("Zadzwoń"))
                    }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(onClick = { startCall(pk, true) }, Modifier.weight(1f)) {
                        Icon(Icons.Default.Videocam, null); Spacer(Modifier.width(6.dp)); Text(tr("Wideo"))
                    }
                }
                if (dm != null) ListItem(
                    headlineContent = { Text(tr("Wycisz powiadomienia")) },
                    trailingContent = { Switch(dm?.muted == true, { on -> core.setMuted(dmChatId(pk), on) }) },
                )
                if (dm != null) {
                    OutlinedButton(onClick = { nav.go(Screen.Media(dmChatId(pk))) }, Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.Image, null); Spacer(Modifier.width(6.dp)); Text(tr("Multimedia, pliki i linki"))
                    }
                    OutlinedButton(onClick = { pl.szept.service.AndroidNotifier.openChatSound(ctx, dmChatId(pk), contact?.display ?: "") }, Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.MusicNote, null); Spacer(Modifier.width(6.dp)); Text(tr("Własny dźwięk powiadomień"))
                    }
                    AutoCleanRow(dmChatId(pk))
                }
                OutlinedButton(onClick = { core.setBlocked(pk, !(contact?.blocked ?: false)) }, Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Block, null); Spacer(Modifier.width(6.dp))
                    Text(if (contact?.blocked == true) tr("Odblokuj") else tr("Zablokuj"))
                }
                if (contact?.isContact == true && !pl.szept.core.Admin.isAdmin(pk)) OutlinedButton(
                    onClick = { confirmUnfriend = true }, Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) {
                    Icon(Icons.Default.PersonRemove, null); Spacer(Modifier.width(6.dp)); Text(tr("Usuń ze znajomych"))
                }
                OutlinedButton(onClick = { confirmDelete = true }, Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Delete, null); Spacer(Modifier.width(6.dp)); Text(tr("Usuń czat u mnie"))
                }
            }
        }
    }

    if (aliasDialog) {
        var alias by remember { mutableStateOf(contact?.alias ?: "") }
        StyleAlertDialog(
            onDismissRequest = { aliasDialog = false },
            title = { Text(tr("Nazwa u Ciebie")) },
            text = {
                Column {
                    OutlinedTextField(alias, { alias = it.take(60) }, singleLine = true, placeholder = { Text(contact?.name ?: "") })
                    Text(tr("Widoczna tylko dla Ciebie. Puste = nazwa, którą wybrała ta osoba."),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            confirmButton = { TextButton(onClick = { core.setAlias(pk, alias); aliasDialog = false }) { Text(tr("Zapisz")) } },
            dismissButton = { TextButton(onClick = { aliasDialog = false }) { Text(tr("Anuluj")) } },
        )
    }
    if (confirmUnfriend) {
        var alsoChat by remember { mutableStateOf(true) }
        StyleAlertDialog(
            onDismissRequest = { confirmUnfriend = false },
            title = { Text(tr("Usunąć {0} ze znajomych?", contact?.display ?: "")) },
            text = {
                Column {
                    Text(tr("Ta osoba zniknie z Twoich kontaktów i przestanie widzieć Twój status oraz relacje. Nie dostanie o tym powiadomienia."))
                    Row(Modifier.fillMaxWidth().clickable { alsoChat = !alsoChat }.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(alsoChat, { alsoChat = it })
                        Text(tr("Usuń też rozmowę"))
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    core.removeFriend(pk, alsoChat); confirmUnfriend = false
                    toast(ctx, tr("Usunięto ze znajomych"))
                    if (alsoChat) nav.home()
                }) { Text(tr("Usuń"), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmUnfriend = false }) { Text(tr("Anuluj")) } },
        )
    }
    if (confirmDelete) StyleAlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text(tr("Usunąć całą rozmowę z telefonu?")) },
        confirmButton = { TextButton(onClick = { core.deleteChat(dmChatId(pk)); confirmDelete = false; nav.home() }) { Text(tr("Usuń")) } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(tr("Anuluj")) } },
    )
}

// ======================= Osoby =======================

@Composable
fun PeopleScreen(nav: Nav) {
    val core = App.core
    val people by rememberDb {
        val byPk = LinkedHashMap<String, Member>()
        core.db.contacts(false).filter { it.isContact || it.blocked }.forEach { byPk[it.pk] = Member(it.pk, it.display) }
        core.db.groups().forEach { g -> g.members.forEach { m -> if (m.pk != core.myPub) byPk.getOrPut(m.pk) { m } } }
        byPk.values.sortedBy { core.db.nameOf(it.pk).lowercase() }
    }
    var query by remember { mutableStateOf("") }
    Scaffold(topBar = { BackBar(tr("Osoby"), nav) }) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize()) {
            item {
                OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().padding(12.dp),
                    placeholder = { Text(tr("Szukaj (nazwa lub ID)")) }, leadingIcon = { Icon(Icons.Default.Search, null) }, singleLine = true)
                Text(
                    tr("Wszyscy, których zna Twój telefon — kontakty oraz osoby z Twoich grup. Możesz każdą zablokować (po stałym ID)."),
                    Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            val list = (people ?: emptyList()).filter {
                query.isBlank() || core.db.nameOf(it.pk).contains(query, true) || pl.szept.data.shortId(it.pk).contains(query, true)
            }
            if (people?.isEmpty() == true) item { Text(tr("Nikogo tu jeszcze nie ma."), Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(list, key = { it.pk }) { m ->
                val c = core.db.contact(m.pk)
                Row(
                    Modifier.fillMaxWidth().clickable { nav.go(Screen.ContactInfo(m.pk)) }.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ContactAvatar(c, 44.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(core.db.nameOf(m.pk), style = MaterialTheme.typography.titleMedium)
                        Text(pl.szept.data.shortId(m.pk), style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (c?.blocked == true) Text("zablokowany", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
                    IconButton(onClick = { core.setBlocked(m.pk, !(c?.blocked ?: false)) }) {
                        Icon(Icons.Default.Block, tr("Blokuj"), tint = if (c?.blocked == true) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

// ======================= kod dostępu do konta =======================

@Composable
fun AccessCodeScreen(nav: Nav) {
    val ctx = LocalContext.current
    val core = App.core
    var shown by remember { mutableStateOf(false) }
    val code = core.myPriv.hex()
    Scaffold(topBar = { BackBar(tr("Kod dostępu do konta"), nav) }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Default.Lock, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(8.dp))
            Text(tr("To jest hasło-klucz do Twojego konta"), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
            Text(
                tr("Zapisz go w bezpiecznym miejscu. Po zmianie telefonu wybierz „Mam już konto” i wpisz ten kod, aby odzyskać konto i wiadomości z ostatnich 21 dni. ") +
                    tr("Każdy, kto go pozna, przejmie Twoje konto — nikomu go nie pokazuj."),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.padding(vertical = 8.dp),
            )
            Spacer(Modifier.height(8.dp))
            if (shown) {
                QrImage(code, 240.dp)
                Spacer(Modifier.height(8.dp))
                Card(Modifier.fillMaxWidth()) { Text(code, Modifier.padding(12.dp), fontFamily = FontFamily.Monospace) }
                Spacer(Modifier.height(8.dp))
                Row {
                    OutlinedButton(onClick = { copyText(ctx, code) }) { Icon(Icons.Default.ContentCopy, null); Spacer(Modifier.width(6.dp)); Text(tr("Kopiuj")) }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(onClick = { shown = false }) { Text(tr("Ukryj")) }
                }
            } else {
                Button(onClick = { shown = true }) { Text(tr("Pokaż kod dostępu")) }
            }
        }
    }
}

// ======================= bezpieczeństwo: blokada + wipe =======================

@Composable
fun SecurityScreen(nav: Nav, act: MainActivity) {
    val ctx = LocalContext.current
    val core = App.core
    val changes by core.db.changes.collectAsState()
    var pinDialog by remember { mutableStateOf(false) }
    var wipeDialog by remember { mutableStateOf(false) }
    var hiddenDialog by remember { mutableStateOf(false) }
    var revealDialog by remember { mutableStateOf(false) }
    var decoyDialog by remember { mutableStateOf(false) }
    var calcDialog by remember { mutableStateOf(false) }
    // w „pustym” Szepcie (fałszywy PIN) nie pokazujemy ustawień prawdziwego konta
    val decoy = App.decoyActive
    var hideRecents by remember { mutableStateOf(UiPrefs.hideRecents) }
    var notifLevel by remember { mutableStateOf(core.notifPrivacy) }
    var fsOn by remember { mutableStateOf(core.forwardSecrecy) }
    var noShots by remember { mutableStateOf(UiPrefs.noShots) }
    var receipts by remember { mutableStateOf(core.readReceipts) }
    var typing by remember { mutableStateOf(core.typingEnabled) }
    var shotWatch by remember { mutableStateOf(ctx.getSharedPreferences("ui", android.content.Context.MODE_PRIVATE).getBoolean("shotwatch", false)) }
    val shotPerm = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) { shotWatch = true; ctx.getSharedPreferences("ui", android.content.Context.MODE_PRIVATE).edit().putBoolean("shotwatch", true).apply(); act.startShotWatcher() }
        else toast(ctx, tr("Bez zgody Szept nie wykryje zrzutów ekranu na tym telefonie"))
    }
    val scope = rememberCoroutineScope()
    // ustawienia blokady są w preferencjach — po każdej zmianie zwiększamy secVer, żeby ekran od razu się odświeżył
    var secVer by remember { mutableIntStateOf(0) }
    var confirm by remember { mutableStateOf<String?>(null) }
    val hasHidden = remember(changes, hiddenDialog, secVer) { core.hasHiddenPin }
    val lockOn = remember(changes, pinDialog, secVer) { decoy || core.lockEnabled }
    val decoyOn = remember(changes, decoyDialog, secVer) { App.real.hasDecoyPin }
    var calcOn by remember { mutableStateOf(Disguise.isOn(ctx)) }
    val wipeOn = remember(changes, wipeDialog, secVer) { core.hasWipeCode }
    val bioOn = remember(secVer) { core.biometricEnabled }
    val canBiometric = remember {
        BiometricManager.from(ctx).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK) == BiometricManager.BIOMETRIC_SUCCESS
    }
    Scaffold(topBar = { BackBar(tr("Prywatność i blokada"), nav) }) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize()) {
            item {
                SectionTitle(tr("Blokada aplikacji"))
                if (!lockOn) {
                    Text(tr("Aplikacja otwiera się bez kodu. Ustaw PIN, żeby nikt nie przeczytał Twoich rozmów."),
                        Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(onClick = { pinDialog = true }, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Icon(Icons.Default.Lock, null); Spacer(Modifier.width(8.dp)); Text(tr("Ustaw PIN"))
                    }
                } else {
                    ListItem(
                        leadingContent = { Icon(Icons.Default.Lock, null, tint = MaterialTheme.colorScheme.primary) },
                        headlineContent = { Text(tr("PIN jest włączony")) },
                        supportingContent = { Text(tr("Aplikacja prosi o PIN przy każdym otwarciu")) },
                        trailingContent = {
                            if (!decoy) Row {
                                IconButton(onClick = { pinDialog = true }) { Icon(Icons.Default.Edit, tr("Zmień PIN")) }
                                IconButton(onClick = { confirm = "pin" }) { Icon(Icons.Default.Delete, tr("Usuń PIN"), tint = MaterialTheme.colorScheme.error) }
                            }
                        },
                    )
                    if (canBiometric && !decoy) ListItem(
                        leadingContent = { Icon(Icons.Default.Fingerprint, null) },
                        headlineContent = { Text(tr("Odblokowanie odciskiem palca / twarzą")) },
                        trailingContent = { Switch(bioOn, { on -> core.setBiometric(on); secVer++ }) },
                    )
                }
                HorizontalDivider()
            }
            if (!decoy) item {
                SectionTitle(tr("Kod awaryjny (wipe)"))
                Text(
                    tr("Drugi, osobny kod. Gdy ktoś Cię zmusi do odblokowania, wpisz go zamiast PIN-u — aplikacja po cichu usunie wybrane dane."),
                    Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (!lockOn) Text(tr("Najpierw ustaw PIN powyżej."), Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                else if (!wipeOn) OutlinedButton(onClick = { wipeDialog = true }, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text(tr("Ustaw kod awaryjny"))
                } else ListItem(
                    headlineContent = { Text(tr("Kod awaryjny jest ustawiony")) },
                    trailingContent = {
                        Row {
                            IconButton(onClick = { wipeDialog = true }) { Icon(Icons.Default.Edit, tr("Zmień kod awaryjny")) }
                            IconButton(onClick = { confirm = "wipe" }) { Icon(Icons.Default.Delete, tr("Usuń kod awaryjny"), tint = MaterialTheme.colorScheme.error) }
                        }
                    },
                )
                HorizontalDivider(Modifier.padding(top = 8.dp))
                SectionTitle(tr("Fałszywy PIN"))
                Text(
                    tr("Drugi PIN, który otwiera „pusty” Szept — bez Twoich rozmów i kontaktów. Prawdziwe wiadomości dalej przychodzą, ") +
                        tr("ale bez powiadomień, dopóki nie wpiszesz prawdziwego PIN-u (np. po zablokowaniu ekranu)."),
                    Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (!lockOn) Text(tr("Najpierw ustaw PIN powyżej."), Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                else if (!decoyOn) OutlinedButton(onClick = { decoyDialog = true }, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text(tr("Ustaw fałszywy PIN"))
                } else ListItem(
                    headlineContent = { Text(tr("Fałszywy PIN jest ustawiony")) },
                    trailingContent = {
                        Row {
                            IconButton(onClick = { decoyDialog = true }) { Icon(Icons.Default.Edit, tr("Zmień fałszywy PIN")) }
                            IconButton(onClick = { core.setDecoyPin(null); secVer++; toast(ctx, tr("Fałszywy PIN usunięty")) }) {
                                Icon(Icons.Default.Delete, tr("Usuń fałszywy PIN"), tint = MaterialTheme.colorScheme.error)
                            }
                        }
                    },
                )
                HorizontalDivider(Modifier.padding(top = 8.dp))
                SectionTitle(tr("Zamaskowana ikona"))
                Text(
                    tr("Na ekranie głównym zamiast Szeptu widać „Kalkulator”, który naprawdę liczy. Szept otwiera się dopiero, ") +
                        tr("gdy wpiszesz swój kod i naciśniesz „=”. Zmiana ikony może potrwać kilka sekund; czasem trzeba ją od nowa dodać na ekran główny."),
                    Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ListItem(
                    headlineContent = { Text(tr("Udawaj kalkulator")) },
                    supportingContent = { Text(if (core.hasCalcCode) tr("Kod ustawiony — dotknij, aby zmienić") else tr("Najpierw ustaw kod (dotknij)")) },
                    modifier = Modifier.clickable { calcDialog = true },
                    trailingContent = {
                        Switch(calcOn, { v ->
                            if (v && !core.hasCalcCode) calcDialog = true
                            else { Disguise.set(ctx, v); calcOn = v; toast(ctx, if (v) tr("Szept udaje teraz kalkulator") else tr("Przywrócono ikonę Szeptu")) }
                        })
                    },
                )
                HorizontalDivider(Modifier.padding(top = 8.dp))
                SectionTitle(tr("Ukryte czaty"))
                Text(
                    tr("Ukryty czat znika z listy rozmów, a jego powiadomienia nie pokazują nazwy ani treści. ") +
                        tr("Aby go zobaczyć, wpisz PIN ukrytych czatów w wyszukiwarce (lupa na ekranie głównym). Ukrywasz czat, przytrzymując go na liście."),
                    Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ListItem(
                    headlineContent = { Text(if (hasHidden) tr("PIN ukrytych czatów ustawiony") else tr("Ustaw PIN ukrytych czatów")) },
                    supportingContent = { if (hasHidden) Text(tr("Dotknij, aby zmienić")) },
                    modifier = Modifier.clickable { hiddenDialog = true },
                )
                if (hasHidden) Row(Modifier.padding(horizontal = 8.dp)) {
                    TextButton(onClick = { revealDialog = true }) { Text(tr("Pokaż ukryte czaty")) }
                    TextButton(onClick = { core.setHiddenPin(null); toast(ctx, tr("Wyłączono — wszystkie czaty są znowu widoczne")) }) { Text(tr("Wyłącz ukrywanie")) }
                }
                HorizontalDivider(Modifier.padding(top = 8.dp))
            }
            item {
                SectionTitle(tr("Treść powiadomień"))
                listOf(
                    pl.szept.core.Core.NOTIF_FULL to tr("Nadawca i treść"),
                    pl.szept.core.Core.NOTIF_SENDER to tr("Tylko nadawca („Nowa wiadomość”)"),
                    pl.szept.core.Core.NOTIF_NONE to tr("Nic — tylko „Szept: nowa wiadomość”"),
                ).forEach { (v, t) ->
                    Row(Modifier.fillMaxWidth().clickable { notifLevel = v; core.setNotifPrivacy(v) }.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(notifLevel == v, { notifLevel = v; core.setNotifPrivacy(v) }); Text(t)
                    }
                }
                Text(
                    tr("Wiadomości czasowe nigdy nie pokazują treści w powiadomieniach."),
                    Modifier.padding(horizontal = 16.dp, vertical = 6.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                HorizontalDivider(Modifier.padding(top = 8.dp))
                SectionTitle(tr("Zrzuty ekranu"))
                ListItem(
                    headlineContent = { Text(tr("Zakaz zrzutów ekranu w Szepcie")) },
                    supportingContent = { Text(tr("Nikt (także Ty) nie zrobi zrzutu ani nagrania ekranu żadnej rozmowy.")) },
                    trailingContent = { Switch(noShots, { v -> noShots = v; UiPrefs.noShots = v; UiPrefs.save(ctx); act.setSecure(false) }) },
                )
                ListItem(
                    headlineContent = { Text(tr("Ukryj podgląd w ostatnich aplikacjach")) },
                    supportingContent = { Text(tr("Na liście ostatnio używanych aplikacji Szept jest zasłonięty, więc nikt nie podejrzy rozmowy.")) },
                    trailingContent = { Switch(hideRecents, { v -> hideRecents = v; UiPrefs.hideRecents = v; UiPrefs.save(ctx); act.applyRecents() }) },
                )
                Text(
                    tr("Wiadomości czasowych i zdjęć czasowych nie da się zrobić zrzutem ekranu nigdy. Gdy ktoś zrobi zrzut rozmowy, w czacie pojawia się o tym informacja."),
                    Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (android.os.Build.VERSION.SDK_INT < 34) ListItem(
                    headlineContent = { Text(tr("Informuj o moich zrzutach ekranu")) },
                    supportingContent = { Text(tr("Na tym Androidzie wymaga zgody na dostęp do zdjęć (Szept sprawdza tylko, czy powstał nowy zrzut).")) },
                    trailingContent = {
                        Switch(shotWatch, { v ->
                            if (!v) { shotWatch = false; ctx.getSharedPreferences("ui", android.content.Context.MODE_PRIVATE).edit().putBoolean("shotwatch", false).apply() }
                            else shotPerm.launch(if (android.os.Build.VERSION.SDK_INT >= 33) android.Manifest.permission.READ_MEDIA_IMAGES else android.Manifest.permission.READ_EXTERNAL_STORAGE)
                        })
                    },
                )
                HorizontalDivider(Modifier.padding(top = 8.dp))
                SectionTitle(tr("Co widzą inni"))
                ListItem(
                    headlineContent = { Text(tr("Potwierdzenia odczytu ✓✓")) },
                    supportingContent = { Text(tr("Rozmówca widzi, że przeczytałeś(-aś) wiadomość")) },
                    trailingContent = { Switch(receipts, { receipts = it; core.setReadReceipts(it) }) },
                )
                ListItem(
                    headlineContent = { Text(tr("„pisze…”")) },
                    supportingContent = { Text(tr("Rozmówca widzi, że właśnie piszesz")) },
                    trailingContent = { Switch(typing, { typing = it; core.setTypingEnabled(it) }) },
                )
                var share by remember { mutableStateOf(core.shareStatus) }
                ListItem(
                    headlineContent = { Text(tr("Status aktywności")) },
                    supportingContent = { Text(tr("Znajomi widzą, czy jesteś w aplikacji, w tle albo nieaktywny(-a)")) },
                    trailingContent = { Switch(share, { share = it; core.setShareStatus(it) }) },
                )
                HorizontalDivider(Modifier.padding(top = 8.dp))
                SectionTitle(tr("Rotacja kluczy"))
                ListItem(
                    headlineContent = { Text(tr("Nowy klucz co tydzień (forward secrecy)")) },
                    supportingContent = {
                        Text(
                            tr("Wiadomości do Ciebie są szyfrowane kluczem, który telefon zmienia co 7 dni, a stare kasuje po 5 tygodniach. ") +
                                tr("Nawet ktoś, kto kiedyś pozna Twój kod konta, nie odczyta starszych kopert zapisanych po drodze. ") +
                                tr("Działa z nadawcami, którzy mają Szept 1.8 lub nowszy; wszystkie Twoje urządzenia muszą mieć 1.8.") +
                                (if (fsOn && core.prekeyCreated() > 0) tr("\nObecny klucz od: ") + formatWhen(core.prekeyCreated()) else ""),
                        )
                    },
                    trailingContent = { Switch(fsOn, { v -> fsOn = v; scope.launch(kotlinx.coroutines.Dispatchers.IO) { core.setForwardSecrecy(v) } }) },
                )
                HorizontalDivider(Modifier.padding(top = 8.dp))
                SectionTitle(tr("Dane w telefonie"))
                ListItem(
                    headlineContent = { Text(if (core.db.encrypted) tr("Baza wiadomości zaszyfrowana") else tr("Baza wiadomości bez dodatkowego szyfrowania")) },
                    supportingContent = {
                        Text(
                            if (core.db.encrypted) tr("AES-256 (SQLCipher), klucz w sprzętowym magazynie kluczy Androida — nie da się go wyjąć z telefonu.")
                            else tr("Ten telefon nie obsługuje szyfrowania bazy — wiadomości chroni szyfrowanie systemu Android.")
                        )
                    },
                    leadingContent = { Icon(Icons.Default.Lock, null) },
                )
                Spacer(Modifier.height(24.dp))
            }
        }
    }
    if (pinDialog) SetPinDialog(canBiometric, onDismiss = { pinDialog = false }) { pin, bio ->
        if (core.isWipeCode(pin)) toast(ctx, tr("PIN musi być inny niż kod awaryjny"))
        else if (core.isDecoyPin(pin)) toast(ctx, tr("PIN musi być inny niż fałszywy PIN"))
        else { core.setPin(pin, bio); pinDialog = false; secVer++; toast(ctx, tr("PIN zapisany")) }
    }
    confirm?.let { what ->
        var code by remember(what) { mutableStateOf("") }
        var err by remember(what) { mutableStateOf<String?>(null) }
        StyleAlertDialog(
            onDismissRequest = { confirm = null },
            icon = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) },
            title = { Text(if (what == "pin") tr("Usunąć PIN?") else tr("Usunąć kod awaryjny?")) },
            text = {
                Column {
                    Text(if (what == "pin") tr("Wpisz obecny PIN. Razem z PIN-em zniknie też kod awaryjny.") else tr("Wpisz obecny PIN, żeby potwierdzić."),
                        style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    PinField(code, tr("Obecny PIN")) { code = it; err = null }
                    err?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (!core.verifyPin(code)) { err = tr("Błędny PIN"); return@TextButton }
                    if (what == "pin") { core.setWipeCode(null, JSONObject()); core.setPin(null); toast(ctx, tr("PIN usunięty")) }
                    else { core.setWipeCode(null, JSONObject()); toast(ctx, tr("Kod awaryjny usunięty")) }
                    confirm = null; secVer++
                }) { Text(tr("Usuń"), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text(tr("Anuluj")) } },
        )
    }
    if (decoyDialog) HiddenPinDialog(
        onDismiss = { decoyDialog = false }, title = tr("Fałszywy PIN"),
        note = tr("Wpisz go na ekranie blokady zamiast prawdziwego PIN-u, a otworzy się pusty Szept."),
    ) { pin ->
        when {
            core.verifyPin(pin) -> toast(ctx, tr("Fałszywy PIN musi być inny niż prawdziwy"))
            core.isWipeCode(pin) -> toast(ctx, tr("Fałszywy PIN musi być inny niż kod awaryjny"))
            else -> { core.setDecoyPin(pin); decoyDialog = false; secVer++; toast(ctx, tr("Fałszywy PIN zapisany")) }
        }
    }
    if (calcDialog) HiddenPinDialog(
        onDismiss = { calcDialog = false }, title = tr("Kod do kalkulatora"),
        note = tr("W kalkulatorze wpisz ten kod i naciśnij „=”, żeby otworzyć Szept. Zapamiętaj go!"),
    ) { code ->
        core.setCalcCode(code); calcDialog = false
        Disguise.set(ctx, true); calcOn = true
        toast(ctx, tr("Szept udaje teraz kalkulator"))
    }
    if (hiddenDialog) HiddenPinDialog(onDismiss = { hiddenDialog = false }) { pin ->
        when {
            core.verifyPin(pin) -> toast(ctx, tr("Użyj innego kodu niż PIN blokady"))
            core.isWipeCode(pin) -> toast(ctx, tr("Użyj innego kodu niż kod awaryjny"))
            core.isDecoyPin(pin) -> toast(ctx, tr("Użyj innego kodu niż fałszywy PIN"))
            else -> { core.setHiddenPin(pin); hiddenDialog = false; toast(ctx, tr("Ustawiono PIN ukrytych czatów")) }
        }
    }
    if (revealDialog) {
        var code by remember { mutableStateOf("") }
        StyleAlertDialog(
            onDismissRequest = { revealDialog = false },
            title = { Text(tr("PIN ukrytych czatów")) },
            text = { PinField(code, "PIN") { code = it } },
            confirmButton = {
                TextButton(onClick = {
                    if (core.verifyHiddenPin(code)) { core.hiddenRevealed.value = true; revealDialog = false; nav.home() }
                    else toast(ctx, tr("Błędny PIN"))
                }) { Text(tr("Pokaż")) }
            },
            dismissButton = { TextButton(onClick = { revealDialog = false }) { Text(tr("Anuluj")) } },
        )
    }
    if (wipeDialog) WipeDialog(onDismiss = { wipeDialog = false; secVer++ })
}

@Composable
private fun SetPinDialog(canBiometric: Boolean, onDismiss: () -> Unit, onSave: (String, Boolean) -> Unit) {
    var pin by remember { mutableStateOf("") }
    var again by remember { mutableStateOf("") }
    var bio by remember { mutableStateOf(canBiometric) }
    var err by remember { mutableStateOf<String?>(null) }
    StyleAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Ustaw PIN")) },
        text = {
            Column {
                PinField(pin, tr("PIN (4–8 cyfr)")) { pin = it }
                Spacer(Modifier.height(8.dp))
                PinField(again, tr("Powtórz PIN")) { again = it }
                if (canBiometric) Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { bio = !bio }) {
                    Checkbox(bio, { bio = it }); Text(tr("Pozwól odblokować odciskiem / twarzą"))
                }
                err?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                when {
                    pin.length < 4 -> err = tr("PIN za krótki")
                    pin != again -> err = tr("PIN-y się różnią")
                    else -> onSave(pin, bio)
                }
            }) { Text(tr("Zapisz")) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Anuluj")) } },
    )
}

@Composable
private fun HiddenPinDialog(onDismiss: () -> Unit, title: String = tr("PIN ukrytych czatów"), note: String? = null, onSave: (String) -> Unit) {
    var pin by remember { mutableStateOf("") }
    var again by remember { mutableStateOf("") }
    var err by remember { mutableStateOf<String?>(null) }
    StyleAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                note?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 8.dp)) }
                PinField(pin, tr("PIN (4–8 cyfr)")) { pin = it }
                Spacer(Modifier.height(8.dp))
                PinField(again, tr("Powtórz PIN")) { again = it }
                err?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                when {
                    pin.length < 4 -> err = tr("PIN za krótki")
                    pin != again -> err = tr("PIN-y się różnią")
                    else -> onSave(pin)
                }
            }) { Text(tr("Zapisz")) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Anuluj")) } },
    )
}

@Composable
private fun PinField(value: String, label: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value, { onChange(it.filter { ch -> ch.isDigit() }.take(8)) }, label = { Text(label) }, singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
    )
}

@Composable
private fun WipeDialog(onDismiss: () -> Unit) {
    val core = App.core
    val cfg = remember { core.wipeConfig() }
    var code by remember { mutableStateOf("") }
    var again by remember { mutableStateOf("") }
    var scopeAll by remember { mutableStateOf(cfg.optString("scope", "all") != "sel") }
    var block by remember { mutableStateOf(cfg.optString("block", "none")) }
    var account by remember { mutableStateOf(cfg.optBoolean("account")) }
    val selChats = remember { mutableStateListOf<String>().apply { cfg.optJSONArray("chats")?.let { for (i in 0 until it.length()) add(it.optString(i)) } } }
    val selPks = remember { mutableStateListOf<String>().apply { cfg.optJSONArray("pks")?.let { for (i in 0 until it.length()) add(it.optString(i)) } } }
    var err by remember { mutableStateOf<String?>(null) }
    val chats = remember { core.db.chats() }
    val contacts = remember { core.db.contacts(false) }

    StyleAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Kod awaryjny")) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (core.hasWipeCode) Text(tr("Kod jest ustawiony. Wpisz nowy, aby go zmienić."),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                PinField(code, tr("Kod awaryjny (4–8 cyfr)")) { code = it }
                Spacer(Modifier.height(6.dp))
                PinField(again, tr("Powtórz kod")) { again = it }
                Spacer(Modifier.height(8.dp))
                Text(tr("Co ma skasować:"), fontWeight = FontWeight.Medium)
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { scopeAll = true }) { RadioButton(scopeAll, { scopeAll = true }); Text(tr("Wszystkie czaty i wiadomości")) }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { scopeAll = false }) { RadioButton(!scopeAll, { scopeAll = false }); Text(tr("Tylko wybrane czaty")) }
                if (!scopeAll) chats.forEach { ch ->
                    Row(Modifier.fillMaxWidth().clickable { if (ch.id in selChats) selChats.remove(ch.id) else selChats.add(ch.id) }.padding(start = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(ch.id in selChats, { on -> if (on) selChats.add(ch.id) else selChats.remove(ch.id) }); Text(ch.name, maxLines = 1)
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(tr("Blokowanie osób:"), fontWeight = FontWeight.Medium)
                listOf("none" to tr("Nie blokuj nikogo"), "all" to tr("Zablokuj wszystkich"), "sel" to tr("Zablokuj wybranych")).forEach { (v, t) ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { block = v }) { RadioButton(block == v, { block = v }); Text(t) }
                }
                if (block == "sel") contacts.forEach { c ->
                    Row(Modifier.fillMaxWidth().clickable { if (c.pk in selPks) selPks.remove(c.pk) else selPks.add(c.pk) }.padding(start = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(c.pk in selPks, { on -> if (on) selPks.add(c.pk) else selPks.remove(c.pk) }); Text(c.display, maxLines = 1)
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { account = !account }) {
                    Checkbox(account, { account = it }); Text(tr("Usuń też konto i klucze (wylogowanie)"))
                }
                err?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                when {
                    code.length < 4 -> err = tr("Kod za krótki")
                    code != again -> err = tr("Kody się różnią")
                    core.verifyPin(code) -> err = tr("Kod awaryjny musi być inny niż PIN")
                    core.isDecoyPin(code) -> err = tr("Kod awaryjny musi być inny niż fałszywy PIN")
                    else -> {
                        val c = JSONObject().put("scope", if (scopeAll) "all" else "sel").put("block", block).put("account", account)
                        if (!scopeAll) c.put("chats", JSONArray(selChats))
                        if (block == "sel") c.put("pks", JSONArray(selPks))
                        core.setWipeCode(code, c); onDismiss()
                    }
                }
            }) { Text(tr("Zapisz")) }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onDismiss) { Text(tr("Anuluj")) }
            }
        },
    )
}

// ======================= ekran blokady =======================

@Composable
fun LockScreen(act: MainActivity, onUnlock: (decoy: Boolean) -> Unit) {
    val ctx = LocalContext.current
    // blokada należy zawsze do prawdziwego konta (także gdy otwarty jest „pusty” Szept)
    val core = App.real
    var pin by remember { mutableStateOf("") }
    var err by remember { mutableStateOf(false) }
    var wiped by remember { mutableStateOf(false) }

    fun tryBiometric() {
        if (!core.biometricEnabled) return
        val prompt = BiometricPrompt(act as FragmentActivity, androidx.core.content.ContextCompat.getMainExecutor(ctx),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) { onUnlock(false) }
            })
        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder().setTitle(tr("Szept")).setSubtitle(tr("Odblokuj aplikację"))
                .setNegativeButtonText(tr("Użyj PIN-u")).build(),
        )
    }
    androidx.compose.runtime.LaunchedEffect(Unit) { tryBiometric() }

    Scaffold { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(Icons.Default.Lock, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(12.dp))
            Text(tr("Szept jest zablokowany"), style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                pin,
                {
                    pin = it.filter { ch -> ch.isDigit() }.take(8); err = false
                    if (pin.length >= 4) {
                        when {
                            core.verifyPin(pin) -> onUnlock(false)
                            core.isDecoyPin(pin) -> onUnlock(true)
                            core.isWipeCode(pin) -> { wiped = true; if (core.applyWipe()) act.onWiped() else onUnlock(false) }
                        }
                    }
                },
                label = { Text("PIN") }, singleLine = true, isError = err,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            )
            if (err) Text(tr("Błędny PIN"), color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp))
            Spacer(Modifier.height(12.dp))
            Button(onClick = {
                when {
                    core.verifyPin(pin) -> onUnlock(false)
                    core.isDecoyPin(pin) -> onUnlock(true)
                    core.isWipeCode(pin) -> { if (core.applyWipe()) act.onWiped() else onUnlock(false) }
                    else -> { err = true; pin = "" }
                }
            }) { Text(tr("Odblokuj")) }
            if (core.biometricEnabled) TextButton(onClick = { tryBiometric() }) {
                Icon(Icons.Default.Fingerprint, null); Spacer(Modifier.width(6.dp)); Text(tr("Odcisk / twarz"))
            }
        }
    }
}

// ======================= mój status =======================

/** Wybór statusu: automatyczny (aktywny / w tle / nieaktywny) albo „Zaraz wracam”. */
@Composable
fun MyPresenceCard() {
    val core = App.core
    var away by remember { mutableStateOf(core.awayManual) }
    var share by remember { mutableStateOf(core.shareStatus) }
    val me = if (away) pl.szept.core.Core.Seen.AWAY else pl.szept.core.Core.Seen.ACTIVE
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PresenceDot(me, size = 14.dp)
                Spacer(Modifier.width(8.dp))
                Text(tr("Mój status"), style = MaterialTheme.typography.titleSmall)
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(!away, { away = false; core.setAway(false) }, { Text(tr("Automatycznie")) })
                FilterChip(away, { away = true; core.setAway(true) }, { Text(tr("🕒 Zaraz wracam")) })
            }
            Text(
                if (!share) tr("Nie pokazujesz innym swojego statusu.")
                else if (away) tr("Znajomi widzą „zaraz wraca”, dopóki tego nie zmienisz.")
                else tr("Zielony — jesteś w aplikacji · żółty — aplikacja działa w tle · szary — aplikacja wyłączona."),
                Modifier.padding(top = 6.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(tr("Pokazuj innym mój status"), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Switch(share, { share = it; core.setShareStatus(it) })
            }
        }
    }
}
