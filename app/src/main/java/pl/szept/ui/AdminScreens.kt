package pl.szept.ui

import pl.szept.tr
import androidx.compose.material3.AlertDialog

import androidx.compose.material3.ButtonDefaults

import androidx.compose.material3.RadioButton

import androidx.compose.foundation.horizontalScroll

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import pl.szept.App
import pl.szept.BuildConfig
import pl.szept.core.Admin
import pl.szept.core.Links
import pl.szept.crypto.isHex64
import java.io.File

/** Wybór odbiorców: rozmowy i grupy z listy + osoby spoza znajomych (kod/link). Pokazuje liczbę osób. */
@Composable
private fun RecipientPicker(selectedChats: MutableList<String>, extra: MutableList<String>) {
    val core = App.core
    val chats = remember { core.db.chats().filter { !Admin.isAdmin(it.peer) } }
    var code by remember { mutableStateOf("") }
    val ctx = LocalContext.current
    val count = remember(selectedChats.toList(), extra.toList()) { core.recipientsOf(selectedChats, extra).size }
    val scan = rememberQrScanner { code = it }
    Text(tr("Do kogo"), fontWeight = FontWeight.Bold)
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = { selectedChats.clear(); selectedChats.addAll(chats.map { it.id }) }) { Text(tr("Zaznacz wszystkich")) }
        TextButton(onClick = { selectedChats.clear(); extra.clear() }) { Text(tr("Wyczyść")) }
    }
    Column(Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState())) {
        chats.forEach { c ->
            Row(Modifier.fillMaxWidth().clickable { if (c.id in selectedChats) selectedChats.remove(c.id) else selectedChats.add(c.id) },
                verticalAlignment = Alignment.CenterVertically) {
                Checkbox(c.id in selectedChats, { on -> if (on) selectedChats.add(c.id) else selectedChats.remove(c.id) })
                Text((if (c.isGroup) "👥 " else "") + c.name, maxLines = 1)
            }
        }
        extra.forEach { pk ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(true, { extra.remove(pk) }); Text(tr("Spoza znajomych: ") + core.db.contact(pk)?.display.orEmpty().ifBlank { pk.take(12) + "…" })
            }
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(code, { code = it.trim() }, Modifier.weight(1f), singleLine = true, placeholder = { Text(tr("Kod lub link osoby spoza znajomych")) })
        IconButton(onClick = scan) { Icon(Icons.Default.QrCodeScanner, tr("Skanuj kod")) }
        TextButton(onClick = {
            val pk = (Links.parse(code) as? Links.Parsed.ContactCode)?.pk ?: code.lowercase().takeIf { it.isHex64() }
            if (pk == null) toast(ctx, "Nie rozpoznano kodu — wklej link szept://u/… albo zeskanuj kod QR tej osoby")
            else { if (pk !in extra) extra.add(pk); code = "" }
        }) { Text(tr("Dodaj")) }
    }
    Text(tr("Odbiorcy: ") + plural(count, tr("osoba"), tr("osoby"), tr("osób")), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium)
}

/** Panel administratora: tylko po haśle. */
@Composable
fun AdminScreen(nav: Nav) {
    val ctx = LocalContext.current
    val core = App.core
    val scope = rememberCoroutineScope()
    val unlocked by core.adminUnlocked.collectAsState()
    var pw by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var rememberMe by remember { mutableStateOf(true) }
    var tab by remember { mutableIntStateOf(0) }
    val chats = remember { mutableStateListOf<String>() }
    val extra = remember { mutableStateListOf<String>() }
    var text by remember { mutableStateOf("") }
    val jobsFlow by core.adminJobs.collectAsState()
    // wysyłki z bazy (widać je także po ponownym uruchomieniu) + stan każdego przekaźnika
    var jobs by remember { mutableStateOf(listOf<pl.szept.core.Core.AdminJob>()) }
    var relayState by remember { mutableStateOf(mapOf<String, List<pl.szept.core.Core.RelayUpload>>()) }
    var cancelJob by remember { mutableStateOf<pl.szept.core.Core.AdminJob?>(null) }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(jobsFlow) {
        while (true) {
            withContext(Dispatchers.IO) {
                val j = core.adminJobList()
                jobs = j
                relayState = j.associate { it.id to core.uploadRelays(it.id) }
            }
            now = System.currentTimeMillis()   // godziny „odpoczywa do …” liczone na bieżąco
            delay(2000)
        }
    }
    val apk = remember { File(ctx.applicationInfo.sourceDir) }

    Scaffold(topBar = { BackBar(tr("Panel administratora"), nav) }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(16.dp)) {
            if (!unlocked) {
                Icon(Icons.Default.AdminPanelSettings, null, Modifier.size(56.dp).align(Alignment.CenterHorizontally), tint = MaterialTheme.colorScheme.primary)
                Text(tr("Dostęp tylko dla administratora"), Modifier.fillMaxWidth(), textAlign = TextAlign.Center, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(pw, { pw = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(tr("Hasło administratora")) },
                    visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { rememberMe = !rememberMe }) {
                    Checkbox(rememberMe, { rememberMe = it }); Text(tr("Nie pytaj ponownie na tym telefonie"))
                }
                Button(onClick = {
                    busy = true
                    scope.launch {
                        val ok = withContext(Dispatchers.Default) { core.adminLogin(pw.toCharArray(), rememberMe) }
                        busy = false
                        if (!ok) toast(ctx, tr("Błędne hasło")) else pw = ""
                    }
                }, enabled = !busy && pw.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
                    if (busy) CircularProgressIndicator(Modifier.size(18.dp)) else Text(tr("Zaloguj"))
                }
                HorizontalDivider(Modifier.padding(vertical = 16.dp))
                var otp by remember { mutableStateOf("") }
                Text(tr("Masz kod jednorazowy od administratora?"), style = MaterialTheme.typography.titleSmall)
                OutlinedTextField(otp, { otp = it.uppercase().take(24) }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(tr("Kod jednorazowy (np. ABCD-EFGH-…)")) })
                OutlinedButton(onClick = {
                    busy = true
                    scope.launch {
                        val ok = try { core.adminLoginOtp(otp) } catch (e: Exception) { false }
                        busy = false
                        if (!ok) toast(ctx, tr("Kod jest nieprawidłowy, wygasł albo został już użyty")) else otp = ""
                    }
                }, enabled = !busy && otp.length >= 12, modifier = Modifier.fillMaxWidth()) { Text(tr("Wejdź kodem jednorazowym")) }
                Text(tr("Kod działa raz — dostęp trwa do wylogowania lub zamknięcia aplikacji."), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                return@Column
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                listOf(tr("Aktualizacja"), tr("Wiadomość"), tr("Znaczki"), tr("Wymuszanie"), tr("Konta"), tr("Dostęp"), "Gry").forEachIndexed { i, t ->
                    FilterChip(tab == i, { tab = i }, { Text(t, style = MaterialTheme.typography.labelSmall) })
                }
            }
            Spacer(Modifier.height(8.dp))
            when (tab) {
                0 -> {
                    Text(tr("Wyślij tę wersję ({0}) wybranym osobom. Dostaną ją od konta „Szept” ✔ z przyciskiem „Zainstaluj”. ", BuildConfig.VERSION_NAME) +
                        tr("Plik idzie raz, bez względu na liczbę osób. U Ciebie nic nie pojawi się w rozmowach."), style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    RecipientPicker(chats, extra)
                    OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth(), label = { Text(tr("Krótki opis zmian (opcjonalnie)")) }, maxLines = 4)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        FastSendChip()
                        Spacer(Modifier.width(8.dp))
                        Text(tr("Szybka wysyłka: plik idzie naraz na wszystkie przekaźniki."), style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                    }
                    Button(onClick = {
                        scope.launch {
                            try {
                                val n = core.adminSend(core.recipientsOf(chats, extra), text, apk, BuildConfig.VERSION_NAME)
                                toast(ctx, tr("Wysyłam aktualizację do ") + plural(n, tr("osoby"), tr("osób"), tr("osób"))); text = ""
                            } catch (e: Exception) { toast(ctx, e.message ?: tr("Nie udało się")) }
                        }
                    }, Modifier.fillMaxWidth()) { Icon(Icons.Default.SystemUpdate, null); Spacer(Modifier.width(8.dp)); Text(tr("Wyślij aktualizację")) }
                }
                1 -> {
                    Text(tr("Wiadomość od oficjalnego konta „Szept” ✔. Odbiorcy nie mogą odpowiadać, a u Ciebie nie zostaje w historii."), style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    RecipientPicker(chats, extra)
                    OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth(), label = { Text(tr("Treść")) }, minLines = 3)
                    Button(onClick = {
                        scope.launch {
                            try { val n = core.adminSend(core.recipientsOf(chats, extra), text); toast(ctx, tr("Wysłano do ") + plural(n, tr("osoby"), tr("osób"), tr("osób"))); text = "" }
                            catch (e: Exception) { toast(ctx, e.message ?: tr("Nie udało się")) }
                        }
                    }, enabled = text.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text(tr("Wyślij jako Szept")) }
                }
                2 -> BadgeTab()
                3 -> PolicyTab(apk)
                4 -> ModerationTab()
                5 -> AccessTab()
                6 -> PyGamesTab()
            }
            val sentJobs = jobs.filter { it.sent }
            if (sentJobs.isNotEmpty()) {
                HorizontalDivider(Modifier.padding(vertical = 12.dp))
                Text(tr("Wysłane ✓"), fontWeight = FontWeight.Bold)
                sentJobs.forEach { job -> Text("✓ " + job.label, style = MaterialTheme.typography.bodySmall) }
                Text(tr("Odbiorcy już dostali plik. W tle dosyłam jeszcze zapasowe kopie na przekaźniki, które na to pozwalają."),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (jobs.any { !it.sent }) {
                HorizontalDivider(Modifier.padding(vertical = 12.dp))
                Text(tr("W toku"), fontWeight = FontWeight.Bold)
                jobs.filter { !it.sent }.forEach { job ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(job.label + " · " + job.progress + "%", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                        TextButton(onClick = { cancelJob = job }) { Text(tr("Anuluj")) }
                    }
                    LinearProgressIndicator(progress = { job.progress / 100f }, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp))
                    // każdy przekaźnik: ile paczek przyjął i co ostatnio odpowiedział
                    relayState[job.id].orEmpty().forEach { r ->
                        val hm = java.text.SimpleDateFormat("HH:mm", pl.szept.I18n.locale)
                        val state = when {
                            r.done >= r.need -> "✓"
                            r.skipUntil > now -> "⛔ " + tr("omijany do {0}", hm.format(java.util.Date(r.skipUntil))) + (r.error?.let { " ($it)" } ?: "")
                            r.rejected -> "⛔ " + tr("odmówił") + (r.error?.let { ": $it" } ?: "")
                            !r.connected -> "✗ " + tr("bez połączenia")
                            r.restUntil > now -> "⏸ " + tr("odpoczywa do {0} (prosił o wolniej)", hm.format(java.util.Date(r.restUntil)))
                            r.error != null -> "⚠ " + r.error
                            else -> "↑"
                        }
                        Text("${r.host}: ${r.done}/${r.need} $state", style = MaterialTheme.typography.labelSmall,
                            color = if (r.rejected || r.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2)
                    }
                    Spacer(Modifier.height(6.dp))
                }
                Text(tr("Wysyłka trwa także po wyjściu z tego ekranu. Odbiorcy dostają aktualizację, gdy pasek dojdzie do 100%."),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            HorizontalDivider(Modifier.padding(vertical = 12.dp))
            TextButton(onClick = { core.adminLogout() }) { Text(tr("Wyloguj z panelu administratora")) }
            cancelJob?.let { job ->
                StyleAlertDialog(
                    onDismissRequest = { cancelJob = null },
                    title = { Text(tr("Anulować wysyłkę?")) },
                    text = { Text(tr("„{0}” — plik przestanie się wgrywać i nikt go nie dostanie. Możesz wysłać jeszcze raz.", job.label)) },
                    confirmButton = { TextButton(onClick = { core.adminCancel(job.id); cancelJob = null; jobs = jobs - job }) { Text(tr("Anuluj wysyłkę")) } },
                    dismissButton = { TextButton(onClick = { cancelJob = null }) { Text(tr("Zostaw")) } },
                )
            }
        }
    }
}

@Composable
private fun BadgeTab() {
    val core = App.core
    val ctx = LocalContext.current
    var ver by remember { mutableIntStateOf(0) }
    val people = remember(ver) { core.db.contacts(false).filter { !Admin.isAdmin(it.pk) } }
    var code by remember { mutableStateOf("") }
    Text(tr("Znaczek ✔ obok nazwy potwierdza, że to prawdziwa osoba/aplikacja. Podpisuje go klucz administratora, więc nikt go nie podrobi."),
        style = MaterialTheme.typography.bodySmall)
    Spacer(Modifier.height(8.dp))
    people.forEach { p ->
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(p.display, Modifier.weight(1f), maxLines = 1)
            if (p.verified) VerifiedBadge()
            TextButton(onClick = { core.adminBadge(p.pk, !p.verified); ver++; toast(ctx, if (p.verified) tr("Odebrano znaczek") else tr("Nadano znaczek")) }) {
                Text(if (p.verified) tr("Odbierz") else tr("Nadaj ✔"))
            }
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(code, { code = it.trim() }, Modifier.weight(1f), singleLine = true, placeholder = { Text(tr("Kod osoby spoza listy")) })
        TextButton(onClick = {
            val pk = (Links.parse(code) as? Links.Parsed.ContactCode)?.pk ?: code.lowercase().takeIf { it.isHex64() }
            if (pk == null) toast(ctx, tr("Nie rozpoznano kodu")) else { core.adminBadge(pk, true); code = ""; toast(ctx, tr("Nadano znaczek")) }
        }) { Text(tr("Nadaj ✔")) }
    }
}

@Composable
private fun PolicyTab(apk: File) {
    val core = App.core
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val policy by core.policy.collectAsState()
    val versions = remember { Admin.VERSIONS.filter { it.second <= BuildConfig.VERSION_CODE } }
    var min by remember { mutableIntStateOf(BuildConfig.VERSION_CODE) }
    var menu by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf(tr("Ta wersja Szepta jest już nieaktualna. Zainstaluj nową, żeby dalej pisać.")) }
    val cur = policy?.optInt("min") ?: 0
    // klucz KLIPY (GIF-y) dla wszystkich
    var gk by remember { mutableStateOf(policy?.optString("gk") ?: "") }
    Card(Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
        Column(Modifier.padding(12.dp)) {
            Text(tr("GIF-y (KLIPY) dla wszystkich"), style = MaterialTheme.typography.titleSmall)
            Text(
                if ((policy?.optString("gk") ?: "").isNotEmpty()) tr("Klucz jest rozesłany — wszyscy mogą szukać GIF-ów.") else tr("Klucz nie jest rozesłany — każdy musi wpisać własny."),
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedTextField(gk, { gk = it.trim() }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(tr("Klucz aplikacji z klipy.com")) })
            TextButton(onClick = {
                scope.launch { try { core.adminSetGifKey(gk); toast(ctx, if (gk.isBlank()) tr("Usunięto klucz") else tr("Rozesłano klucz KLIPY")) } catch (e: Exception) { toast(ctx, e.message ?: tr("Błąd")) } }
            }) { Text(if (gk.isBlank()) tr("Usuń klucz u wszystkich") else tr("Roześlij klucz")) }
        }
    }
    TurnCard()
    Card(Modifier.fillMaxWidth()) {
        Text(
            if (cur > 0) tr("Teraz: wersje starsze niż {0} muszą się zaktualizować.", Admin.versionName(cur)) else tr("Teraz: nic nie jest wymuszane."),
            Modifier.padding(12.dp),
        )
    }
    Spacer(Modifier.height(8.dp))
    Text(tr("Osoby ze starszą wersją zobaczą ekran „Musisz zaktualizować” z przyciskiem pobrania tej wersji ({0}). Działa u wszystkich, także u nieznajomych.", BuildConfig.VERSION_NAME),
        style = MaterialTheme.typography.bodySmall)
    Spacer(Modifier.height(8.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(tr("Najstarsza dozwolona wersja: "))
        OutlinedButton(onClick = { menu = true }) { Text(Admin.versionName(min)) }
        StyleMenu(menu, { menu = false }) {
            versions.forEach { (n, c) -> DropdownMenuItem({ Text(n) }, { min = c; menu = false }) }
        }
    }
    OutlinedTextField(msg, { msg = it }, Modifier.fillMaxWidth(), label = { Text(tr("Komunikat dla użytkowników")) }, maxLines = 4)
    if (apk != null) Row(verticalAlignment = Alignment.CenterVertically) {
        FastSendChip()
        Spacer(Modifier.width(8.dp))
        Text(tr("Szybka wysyłka pliku nowej wersji."), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Button(onClick = {
        scope.launch {
            try { core.adminPolicy(min, msg, apk, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE); toast(ctx, tr("Wysyłam — wersje starsze niż {0} będą musiały się zaktualizować", Admin.versionName(min))) }
            catch (e: Exception) { toast(ctx, e.message ?: tr("Nie udało się")) }
        }
    }, Modifier.fillMaxWidth()) { Text(tr("Wymuś aktualizację")) }
    if (cur > 0) TextButton(onClick = {
        scope.launch { try { core.adminPolicy(0, "", null, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE); toast(ctx, tr("Wyłączono wymuszanie")) } catch (e: Exception) { toast(ctx, e.message ?: tr("Błąd")) } }
    }) { Text(tr("Wyłącz wymuszanie")) }
    Text(tr("Uwaga: plik w przekaźnikach jest przechowywany 21 dni — po tym czasie wymuś ponownie, żeby nowi mogli go pobrać."),
        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** Ekran blokujący: ta wersja jest za stara. */
@Composable
fun ForcedUpdateScreen() {
    val ctx = LocalContext.current
    val core = App.core
    val policy by core.policy.collectAsState()
    val changes by core.db.changes.collectAsState()
    val update = remember(changes, policy) { core.latestUpdateMessage() }
    val pending = remember(changes, policy) { policy?.optInt("code")?.let { core.db.message("upd:$it") } }
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Default.SystemUpdate, null, Modifier.size(72.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(16.dp))
        Text(tr("Musisz zaktualizować Szept"), style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(policy?.optString("msg")?.ifBlank { null } ?: tr("Ta wersja jest nieaktualna."), textAlign = TextAlign.Center)
        Text(tr("Twoja wersja: {0}", BuildConfig.VERSION_NAME) + (policy?.optString("v")?.let { tr(" · nowa: {0}", it) } ?: ""),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
        Spacer(Modifier.height(24.dp))
        val path = update?.path
        when {
            path != null -> Button(onClick = { installApk(ctx, path) }, Modifier.fillMaxWidth()) { Text(tr("Zainstaluj nową wersję")) }
            pending != null -> {
                Text(tr("Pobieranie nowej wersji… {0}%", pending.progress))
                LinearProgressIndicator(progress = { pending.progress / 100f }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            }
            else -> Text(tr("Czekam na plik nowej wersji. Jeśli to trwa, poproś znajomego o przesłanie nowej wersji Szepta."), textAlign = TextAlign.Center)
        }
        if (path == null) {
            Spacer(Modifier.height(16.dp))
            OutlinedButton(onClick = { openUrl(ctx, Admin.RELEASES_URL) }, Modifier.fillMaxWidth()) { Text(tr("Pobierz ze strony")) }
            Text(tr("Gdy pobieranie stoi w miejscu, nową wersję pobierzesz też ze strony Szepta (GitHub)."),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        }
    }
}

/** Moderacja kont: wyciszenie, blokada pisania, usunięcie konta (pilnuje tego każda kopia aplikacji). */
@Composable
private fun ModerationTab() {
    val ctx = LocalContext.current
    val core = App.core
    val scope = rememberCoroutineScope()
    val policy by core.policy.collectAsState()
    val list = remember(policy) { core.sanctions.filter { it.active(System.currentTimeMillis()) } }
    var code by remember { mutableStateOf("") }
    var target by remember { mutableStateOf<String?>(null) }
    var reason by remember { mutableStateOf("") }
    var confirmDel by remember { mutableStateOf(false) }
    val fmt = remember { java.text.SimpleDateFormat("d.MM.yyyy HH:mm", pl.szept.I18n.locale) }
    fun apply(mode: String, until: Long) {
        val pk = target ?: return
        scope.launch {
            try { core.adminSanction(pk, mode, until, reason); toast(ctx, tr("Zapisano — dotrze do wszystkich przy najbliższym połączeniu")); target = null; reason = ""; code = "" }
            catch (e: Exception) { toast(ctx, e.message ?: tr("Nie udało się")) }
        }
    }
    Text(
        tr("Bez serwera każda kopia Szepta sama pilnuje tej listy (podpisanej Twoim kluczem): wyciszony nie może pisać, zablokowany nie może pisać ani dzwonić, ") +
            tr("a usunięte konto zostaje skasowane z telefonu tej osoby. Ktoś może jednak założyć nowe konto."),
        style = MaterialTheme.typography.bodySmall,
    )
    Spacer(Modifier.height(8.dp))
    val people = remember { core.knownPeople().filter { !Admin.isAdmin(it.pk) } }
    Text(tr("Wybierz osobę"), style = MaterialTheme.typography.titleSmall)
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(code, { code = it.trim() }, Modifier.weight(1f), singleLine = true, label = { Text(tr("Kod / link osoby")) })
        TextButton(onClick = {
            val pk = (Links.parse(code) as? Links.Parsed.ContactCode)?.pk
            if (pk == null) toast(ctx, tr("Nie rozpoznano kodu")) else target = pk
        }) { Text(tr("Wybierz")) }
    }
    Column(Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState())) {
        people.forEach { p ->
            Row(Modifier.fillMaxWidth().clickable { target = p.pk }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                RadioButton(target == p.pk, { target = p.pk })
                Text(p.display, Modifier.weight(1f))
                core.sanctionOf(p.pk)?.let { Text(modeLabel(it.mode), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall) }
            }
        }
    }
    target?.let { pk ->
        Card(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            Column(Modifier.padding(12.dp)) {
                Text(tr("Wybrano: {0} ({1})", core.db.nameOf(pk), pl.szept.data.shortId(pk)), fontWeight = FontWeight.Medium)
                OutlinedTextField(reason, { reason = it.take(200) }, Modifier.fillMaxWidth(), label = { Text(tr("Powód (zobaczy go ta osoba)")) })
                Text(tr("Wycisz na:"), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(tr("1 h") to 3600_000L, tr("1 dzień") to 86400_000L, tr("7 dni") to 7 * 86400_000L, tr("30 dni") to 30 * 86400_000L, tr("na zawsze") to 0L).forEach { (t, ms) ->
                        OutlinedButton(onClick = { apply("mute", if (ms == 0L) 0L else System.currentTimeMillis() + ms) }) { Text(t) }
                    }
                }
                Button(onClick = { apply("ban", 0L) }, Modifier.fillMaxWidth().padding(top = 6.dp)) { Text(tr("⛔ Zablokuj pisanie (ban)")) }
                OutlinedButton(onClick = { confirmDel = true }, Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text(tr("🗑 Usuń konto tej osoby")) }
                if (core.sanctionOf(pk) != null) TextButton(onClick = { apply("none", 0L) }) { Text(tr("Zdejmij ograniczenia")) }
            }
        }
    }
    if (confirmDel) StyleAlertDialog(
        onDismissRequest = { confirmDel = false },
        title = { Text(tr("Usunąć konto?")) },
        text = { Text(tr("Konto {0} zostanie skasowane z jej telefonu (wiadomości, kontakty, klucze), a inni przestaną dostawać od niej wiadomości. Tego nie da się cofnąć.", target?.let { core.db.nameOf(it) })) },
        confirmButton = { TextButton(onClick = { confirmDel = false; apply("del", 0L) }) { Text(tr("Usuń konto"), color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { confirmDel = false }) { Text(tr("Anuluj")) } },
    )
    HorizontalDivider(Modifier.padding(vertical = 12.dp))
    Text(tr("Aktywne ograniczenia ({0})", list.size), style = MaterialTheme.typography.titleSmall)
    if (list.isEmpty()) Text(tr("Brak."), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    list.forEach { s ->
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(core.db.nameOf(s.pk) + " · " + modeLabel(s.mode) + if (s.until > 0) " do " + fmt.format(java.util.Date(s.until)) else "")
                if (s.reason.isNotBlank()) Text(s.reason, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (s.mode != "del") TextButton(onClick = {
                scope.launch { try { core.adminSanction(s.pk, "none", 0, ""); toast(ctx, tr("Zdjęto ograniczenia")) } catch (e: Exception) { toast(ctx, e.message ?: tr("Błąd")) } }
            }) { Text(tr("Cofnij")) }
        }
    }
}

private fun modeLabel(m: String) = when (m) { "mute" -> "wyciszony"; "ban" -> "zablokowany"; else -> tr("konto usunięte") }

/** Dostęp do panelu: podgląd hasła i kody jednorazowe. */
@Composable
private fun AccessTab() {
    val ctx = LocalContext.current
    val core = App.core
    val scope = rememberCoroutineScope()
    val policy by core.policy.collectAsState()
    val otps = remember(policy) { core.adminOtps }
    var shown by remember { mutableStateOf<String?>(null) }
    var newCode by remember { mutableStateOf<String?>(null) }
    var label by remember { mutableStateOf("") }
    Text(tr("Hasło panelu"), style = MaterialTheme.typography.titleSmall)
    if (core.adminViaOtp) Text(tr("Wszedłeś kodem jednorazowym — podgląd hasła i tworzenie kodów są niedostępne."), style = MaterialTheme.typography.bodySmall)
    else {
        val saved = remember { core.adminSavedPassword() }
        if (saved == null) Text(tr("Hasło nie jest zapamiętane na tym telefonie (zaloguj się ponownie hasłem, żeby móc je tu podejrzeć)."), style = MaterialTheme.typography.bodySmall)
        else Row(verticalAlignment = Alignment.CenterVertically) {
            Text(shown ?: "•".repeat(12), Modifier.weight(1f), fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
            TextButton(onClick = { shown = if (shown == null) saved else null }) { Text(if (shown == null) tr("Pokaż") else tr("Ukryj")) }
            if (shown != null) TextButton(onClick = { copyText(ctx, saved) }) { Text(tr("Kopiuj")) }
        }
        Text(tr("Hasło jest zapisane zaszyfrowane kluczem tego telefonu. Nie pokazuj go nikomu."), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        HorizontalDivider(Modifier.padding(vertical = 12.dp))
        Text(tr("Kody jednorazowe"), style = MaterialTheme.typography.titleSmall)
        Text(
            tr("Kod pozwala wejść do panelu raz (np. na innym telefonie) bez podawania hasła. Daje pełny dostęp na jedną sesję — przekaż go tylko zaufanej osobie."),
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedTextField(label, { label = it.take(40) }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(tr("Dla kogo / opis (opcjonalnie)")) })
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(tr("ważny 1 h") to 3600_000L, tr("1 dzień") to 86400_000L, tr("7 dni") to 7 * 86400_000L).forEach { (t, ms) ->
                OutlinedButton(onClick = {
                    scope.launch { try { newCode = core.adminCreateOtp(ms, label); label = "" } catch (e: Exception) { toast(ctx, e.message ?: "Błąd") } }
                }) { Text(t) }
            }
        }
        newCode?.let { c ->
            Card(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                Column(Modifier.padding(12.dp)) {
                    Text(tr("Nowy kod (zapisz go teraz — później nie da się go wyświetlić):"), style = MaterialTheme.typography.labelMedium)
                    Text(c, style = MaterialTheme.typography.titleLarge, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                    TextButton(onClick = { copyText(ctx, c) }) { Text(tr("Kopiuj")) }
                }
            }
        }
        val fmt = remember { java.text.SimpleDateFormat("d.MM HH:mm", pl.szept.I18n.locale) }
        Text(tr("Aktywne kody ({0})", otps.size), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
        otps.forEach { o ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text((o.label.ifBlank { tr("kod") }) + tr(" · ważny do ") + fmt.format(java.util.Date(o.exp)), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { scope.launch { core.adminRevokeOtp(o.id) } }) { Text(tr("Unieważnij")) }
            }
        }
    }
}

/** Serwery TURN dla wszystkich (kilku dostawców, każdy z własnym loginem) — potrzebne, gdy sieć blokuje połączenie bezpośrednie. */
@Composable
private fun TurnCard() {
    val ctx = LocalContext.current
    val core = App.core
    val scope = rememberCoroutineScope()
    val policy by core.policy.collectAsState()
    val current = remember(policy) { core.adminTurnServers() }
    data class Row(val urls: String, val user: String, val pass: String)
    val rows = remember(current) {
        androidx.compose.runtime.mutableStateListOf<Row>().apply {
            current.forEach { add(Row(it.urls.joinToString("\n"), it.user, it.pass)) }
            if (isEmpty()) add(Row("", "", ""))
        }
    }
    Card(Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
        Column(Modifier.padding(12.dp)) {
            Text(tr("Rozmowy: serwery TURN dla wszystkich"), style = MaterialTheme.typography.titleSmall)
            Text(
                (if (current.isNotEmpty()) tr("Rozesłane serwery: {0}. ", current.size) else tr("Brak serwera — rozmowy przez sieć komórkową często zostają na „Łączenie…”. ")) +
                    tr("TURN przekazuje zaszyfrowany dźwięk i obraz, gdy telefony nie mogą połączyć się bezpośrednio. Możesz dodać kilku dostawców — telefon użyje tego, który działa."),
                style = MaterialTheme.typography.bodySmall,
            )
            rows.forEachIndexed { i, r ->
                Text(tr("Serwer {0}", i + 1), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
                OutlinedTextField(r.urls, { v -> rows[i] = r.copy(urls = v) }, Modifier.fillMaxWidth(),
                    label = { Text(tr("Adresy (każdy w nowej linii), np. free.expressturn.com:3478")) }, minLines = 2)
                OutlinedTextField(r.user, { v -> rows[i] = r.copy(user = v.trim()) }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(tr("Użytkownik (username)")) })
                OutlinedTextField(r.pass, { v -> rows[i] = r.copy(pass = v.trim()) }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(tr("Hasło (password / credential)")) })
                if (rows.size > 1) TextButton(onClick = { rows.removeAt(i) }) { Text(tr("Usuń ten serwer")) }
            }
            Row {
                if (rows.size < 4) TextButton(onClick = { rows.add(Row("", "", "")) }) { Text(tr("+ Dodaj serwer")) }
                TextButton(onClick = {
                    val list = rows.filter { it.urls.isNotBlank() }.map { pl.szept.core.Core.TurnServer(it.urls.lines(), it.user, it.pass) }
                    scope.launch {
                        try { core.adminSetTurns(list); toast(ctx, if (list.isEmpty()) tr("Usunięto serwery TURN") else tr("Rozesłano serwery TURN: {0}", list.size)) }
                        catch (e: Exception) { toast(ctx, e.message ?: tr("Błąd")) }
                    }
                }) { Text(tr("Roześlij wszystkim")) }
            }
        }
    }
}

/** Gry w Pythonie dla wszystkich: plik .py (albo .zip z main.py) trafia do każdego telefonu. */
@Composable
private fun PyGamesTab() {
    val ctx = LocalContext.current
    val core = App.core
    val scope = rememberCoroutineScope()
    val policy by core.policy.collectAsState()
    val games = remember(policy) { core.adminPyGames() }
    var editing by remember { mutableStateOf<pl.szept.core.PyGames.Game?>(null) }
    var file by remember { mutableStateOf<File?>(null) }
    var fileName by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var desc by remember { mutableStateOf("") }
    var sk by remember { mutableStateOf("pts") }
    var unit by remember { mutableStateOf("") }
    var higher by remember { mutableStateOf(true) }
    var decimals by remember { mutableIntStateOf(0) }
    var args by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var confirmDel by remember { mutableStateOf<pl.szept.core.PyGames.Game?>(null) }
    fun reset() { editing = null; file = null; fileName = ""; name = ""; desc = ""; sk = "pts"; unit = ""; higher = true; decimals = 0; args = "" }
    val pick = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            try {
                val (f, n) = withContext(Dispatchers.IO) {
                    val n = ctx.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: "gra.py"
                    val dest = File(ctx.cacheDir, "pyg_pick_${System.nanoTime()}")
                    ctx.contentResolver.openInputStream(uri)?.use { ins -> dest.outputStream().use { ins.copyTo(it) } }
                    dest to n
                }
                if (f.length() > 8L * 1024 * 1024) { f.delete(); toast(ctx, tr("Plik gry może mieć najwyżej 8 MB")); return@launch }
                file = f; fileName = n
                if (name.isBlank()) name = n.substringBeforeLast('.').replace('_', ' ').replaceFirstChar { it.uppercase() }.take(40)
            } catch (e: Exception) { toast(ctx, tr("Nie udało się wczytać pliku")) }
        }
    }
    Text(
        tr("Gry w Pythonie (pygame) działają w telefonie każdej osoby — bez serwera. Plik .py albo .zip (z main.py i obrazkami/dźwiękami) trafia do wszystkich ") +
            tr("i pojawia się w Gry oraz w spinacz → Gra. Pierwsze uruchomienie pobiera raz środowisko Pythona (ok. 18 MB)."),
        style = MaterialTheme.typography.bodySmall,
    )
    Text(
        tr("Wskazówki: najlepiej pętla jak w pygbag — „async def main()” i „await asyncio.sleep(0)” w każdej klatce. Wynik do tabeli „Łącznie” zgłaszasz tak: ") +
            tr("„import szept” i „szept.score(wynik)” (poza Szeptem moduł nie istnieje — użyj try/except ImportError)."),
        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp),
    )
    HorizontalDivider(Modifier.padding(vertical = 10.dp))
    Text(tr("Rozesłane gry ({0})", games.size), style = MaterialTheme.typography.titleSmall)
    if (games.isEmpty()) Text(tr("Brak — dodaj pierwszą grę poniżej. Wbudowany jest „Wheelie Simulator 2D”."), style = MaterialTheme.typography.bodySmall)
    games.forEach { g ->
        Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            Column(Modifier.padding(10.dp)) {
                Text(g.name, fontWeight = FontWeight.Bold)
                if (g.desc.isNotBlank()) Text(g.desc, style = MaterialTheme.typography.bodySmall, maxLines = 3)
                Text(tr("Wynik: ") + pl.szept.ui.games.scoreLabel(g.score) + " · " + (if (g.zip) "ZIP" else tr("plik .py")) + " · " +
                    java.text.SimpleDateFormat("d.MM.yyyy", pl.szept.I18n.locale).format(java.util.Date(g.ver)),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row {
                    TextButton(onClick = { pl.szept.ui.games.Play.start(ctx, g.id) }, enabled = pl.szept.core.PyGames.isReady(g)) { Text(tr("Zagraj")) }
                    TextButton(onClick = {
                        editing = g; file = null; fileName = ""; name = g.name; desc = g.desc; sk = g.sk; unit = g.unit; higher = g.higher; decimals = g.decimals; args = g.args
                    }) { Text(tr("Zmień")) }
                    TextButton(onClick = {
                        scope.launch { try { core.adminRefreshPyGame(g.id); toast(ctx, tr("Wysyłam plik gry ponownie")) } catch (e: Exception) { toast(ctx, e.message ?: tr("Błąd")) } }
                    }) { Text(tr("Wyślij ponownie")) }
                    TextButton(onClick = { confirmDel = g }) { Text(tr("Usuń"), color = MaterialTheme.colorScheme.error) }
                }
            }
        }
    }
    Text(tr("Paczki z plikiem są na przekaźnikach 21 dni — później użyj „Wyślij ponownie”, żeby nowe telefony też mogły pobrać grę."),
        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    HorizontalDivider(Modifier.padding(vertical = 10.dp))
    Text(if (editing != null) tr("Zmień grę „{0}”", editing!!.name) else tr("Dodaj grę w Pythonie"), style = MaterialTheme.typography.titleSmall)
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(onClick = { pick.launch(arrayOf("*/*")) }) { Text(if (file == null) tr("Wybierz plik .py / .zip") else tr("Zmień plik")) }
        Spacer(Modifier.width(8.dp))
        Text(fileName.ifEmpty { if (editing != null) tr("(bez zmiany pliku)") else "" }, style = MaterialTheme.typography.bodySmall, maxLines = 1)
    }
    OutlinedTextField(name, { name = it.take(40) }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(tr("Nazwa gry")) })
    OutlinedTextField(desc, { desc = it.take(400) }, Modifier.fillMaxWidth(), label = { Text(tr("Opis (jak grać)")) }, minLines = 2)
    Text(tr("Wynik w grze"), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 6.dp))
    listOf("pts" to tr("Punkty (więcej = lepiej)"), "time" to tr("Czas w ms (szybciej = lepiej)"), "timehi" to tr("Czas w ms (dłużej = lepiej)"),
        "m" to tr("Metry (więcej = lepiej)"), "custom" to tr("Własna jednostka"), "none" to tr("Bez wyniku")).forEach { (k, t) ->
        Row(Modifier.fillMaxWidth().clickable { sk = k }, verticalAlignment = Alignment.CenterVertically) { RadioButton(sk == k, { sk = k }); Text(t) }
    }
    if (sk == "custom") {
        OutlinedTextField(unit, { unit = it.take(12) }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(tr("Jednostka, np. „monet”")) })
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { higher = !higher }) { Checkbox(higher, { higher = it }); Text(tr("Więcej = lepiej")) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(tr("Miejsca po przecinku: "))
            listOf(0, 1, 2).forEach { d -> FilterChip(decimals == d, { decimals = d }, { Text("$d") }); Spacer(Modifier.width(4.dp)) }
        }
    }
    OutlinedTextField(args, { args = it.take(80) }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(tr("Argumenty startowe (opcjonalnie, np. --telefon)")) })
    Row {
        Button(onClick = {
            val g = editing
            val src = file ?: g?.let { pl.szept.core.PyGames.fileOf(it) }
            if (src == null) { toast(ctx, tr("Wybierz plik gry")); return@Button }
            val zip = (file?.let { fileName.endsWith(".zip", true) || isZip(it) }) ?: g!!.zip
            busy = true
            scope.launch {
                try {
                    val copy = File(ctx.cacheDir, "pyg_send_${System.nanoTime()}").also { withContext(Dispatchers.IO) { src.copyTo(it, overwrite = true) } }
                    try { core.adminPublishPyGame(copy, zip, g?.id, name, desc, sk, unit, higher, decimals, args) } finally { copy.delete() }
                    toast(ctx, tr("Rozsyłam grę „{0}” do wszystkich", name)); reset()
                } catch (e: Exception) { toast(ctx, e.message ?: tr("Nie udało się")) } finally { busy = false }
            }
        }, enabled = !busy && name.isNotBlank() && (file != null || editing != null)) { Text(if (editing != null) tr("Zapisz i roześlij") else tr("Roześlij wszystkim")) }
        if (editing != null) TextButton(onClick = { reset() }) { Text(tr("Anuluj")) }
    }
    confirmDel?.let { g ->
        StyleAlertDialog(
            onDismissRequest = { confirmDel = null },
            title = { Text(tr("Usunąć grę?")) },
            text = { Text(tr("„{0}” zniknie z telefonów wszystkich osób.", g.name)) },
            confirmButton = { TextButton(onClick = { confirmDel = null; scope.launch { try { core.adminRemovePyGame(g.id) } catch (e: Exception) { toast(ctx, e.message ?: tr("Błąd")) } } }) { Text(tr("Usuń")) } },
            dismissButton = { TextButton(onClick = { confirmDel = null }) { Text(tr("Anuluj")) } },
        )
    }
}

/** Czy plik to archiwum ZIP (nagłówek „PK”). */
private fun isZip(f: File): Boolean = try { f.inputStream().use { val b = ByteArray(2); it.read(b) == 2 && b[0] == 'P'.code.toByte() && b[1] == 'K'.code.toByte() } } catch (e: Exception) { false }
