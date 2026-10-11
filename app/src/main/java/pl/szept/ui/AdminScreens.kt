package pl.szept.ui

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
    Text("Do kogo", fontWeight = FontWeight.Bold)
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = { selectedChats.clear(); selectedChats.addAll(chats.map { it.id }) }) { Text("Zaznacz wszystkich") }
        TextButton(onClick = { selectedChats.clear(); extra.clear() }) { Text("Wyczyść") }
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
                Checkbox(true, { extra.remove(pk) }); Text("Spoza znajomych: " + core.db.contact(pk)?.display.orEmpty().ifBlank { pk.take(12) + "…" })
            }
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(code, { code = it.trim() }, Modifier.weight(1f), singleLine = true, placeholder = { Text("Kod lub link osoby spoza znajomych") })
        IconButton(onClick = scan) { Icon(Icons.Default.QrCodeScanner, "Skanuj kod") }
        TextButton(onClick = {
            val pk = (Links.parse(code) as? Links.Parsed.ContactCode)?.pk ?: code.lowercase().takeIf { it.isHex64() }
            if (pk == null) toast(ctx, "Nie rozpoznano kodu — wklej link szept://u/… albo zeskanuj kod QR tej osoby")
            else { if (pk !in extra) extra.add(pk); code = "" }
        }) { Text("Dodaj") }
    }
    Text("Odbiorcy: $count " + plural(count, "osoba", "osoby", "osób"), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium)
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
    val jobs by core.adminJobs.collectAsState()
    var progress by remember { mutableStateOf(mapOf<String, Int>()) }
    LaunchedEffect(jobs) { while (jobs.isNotEmpty()) { progress = withContext(Dispatchers.IO) { core.adminProgress() }; delay(1500) } }
    val apk = remember { File(ctx.applicationInfo.sourceDir) }

    Scaffold(topBar = { BackBar("Panel administratora", nav) }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(16.dp)) {
            if (!unlocked) {
                Icon(Icons.Default.AdminPanelSettings, null, Modifier.size(56.dp).align(Alignment.CenterHorizontally), tint = MaterialTheme.colorScheme.primary)
                Text("Dostęp tylko dla administratora", Modifier.fillMaxWidth(), textAlign = TextAlign.Center, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(pw, { pw = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Hasło administratora") },
                    visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { rememberMe = !rememberMe }) {
                    Checkbox(rememberMe, { rememberMe = it }); Text("Nie pytaj ponownie na tym telefonie")
                }
                Button(onClick = {
                    busy = true
                    scope.launch {
                        val ok = withContext(Dispatchers.Default) { core.adminLogin(pw.toCharArray(), rememberMe) }
                        busy = false
                        if (!ok) toast(ctx, "Błędne hasło") else pw = ""
                    }
                }, enabled = !busy && pw.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
                    if (busy) CircularProgressIndicator(Modifier.size(18.dp)) else Text("Zaloguj")
                }
                HorizontalDivider(Modifier.padding(vertical = 16.dp))
                var otp by remember { mutableStateOf("") }
                Text("Masz kod jednorazowy od administratora?", style = MaterialTheme.typography.titleSmall)
                OutlinedTextField(otp, { otp = it.uppercase().take(24) }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Kod jednorazowy (np. ABCD-EFGH-…)") })
                OutlinedButton(onClick = {
                    busy = true
                    scope.launch {
                        val ok = try { core.adminLoginOtp(otp) } catch (e: Exception) { false }
                        busy = false
                        if (!ok) toast(ctx, "Kod jest nieprawidłowy, wygasł albo został już użyty") else otp = ""
                    }
                }, enabled = !busy && otp.length >= 12, modifier = Modifier.fillMaxWidth()) { Text("Wejdź kodem jednorazowym") }
                Text("Kod działa raz — dostęp trwa do wylogowania lub zamknięcia aplikacji.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                return@Column
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                listOf("Aktualizacja", "Wiadomość", "Znaczki", "Wymuszanie", "Konta", "Dostęp").forEachIndexed { i, t ->
                    FilterChip(tab == i, { tab = i }, { Text(t, style = MaterialTheme.typography.labelSmall) })
                }
            }
            Spacer(Modifier.height(8.dp))
            when (tab) {
                0 -> {
                    Text("Wyślij tę wersję (${BuildConfig.VERSION_NAME}) wybranym osobom. Dostaną ją od konta „Szept” ✔ z przyciskiem „Zainstaluj”. " +
                        "Plik idzie raz, bez względu na liczbę osób. U Ciebie nic nie pojawi się w rozmowach.", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    RecipientPicker(chats, extra)
                    OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth(), label = { Text("Krótki opis zmian (opcjonalnie)") }, maxLines = 4)
                    Button(onClick = {
                        scope.launch {
                            try {
                                val n = core.adminSend(core.recipientsOf(chats, extra), text, apk, BuildConfig.VERSION_NAME)
                                toast(ctx, "Wysyłam aktualizację do $n " + plural(n, "osoby", "osób", "osób")); text = ""
                            } catch (e: Exception) { toast(ctx, e.message ?: "Nie udało się") }
                        }
                    }, Modifier.fillMaxWidth()) { Icon(Icons.Default.SystemUpdate, null); Spacer(Modifier.width(8.dp)); Text("Wyślij aktualizację") }
                }
                1 -> {
                    Text("Wiadomość od oficjalnego konta „Szept” ✔. Odbiorcy nie mogą odpowiadać, a u Ciebie nie zostaje w historii.", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    RecipientPicker(chats, extra)
                    OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth(), label = { Text("Treść") }, minLines = 3)
                    Button(onClick = {
                        scope.launch {
                            try { val n = core.adminSend(core.recipientsOf(chats, extra), text); toast(ctx, "Wysłano do $n " + plural(n, "osoby", "osób", "osób")); text = "" }
                            catch (e: Exception) { toast(ctx, e.message ?: "Nie udało się") }
                        }
                    }, enabled = text.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("Wyślij jako Szept") }
                }
                2 -> BadgeTab()
                3 -> PolicyTab(apk)
                4 -> ModerationTab()
                5 -> AccessTab()
            }
            if (jobs.isNotEmpty()) {
                HorizontalDivider(Modifier.padding(vertical = 12.dp))
                Text("W toku", fontWeight = FontWeight.Bold)
                jobs.forEach { (id, label) ->
                    Text(label, style = MaterialTheme.typography.bodySmall)
                    LinearProgressIndicator(progress = { (progress[id] ?: 0) / 100f }, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp))
                }
                Text("Wysyłka trwa także po wyjściu z tego ekranu.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            HorizontalDivider(Modifier.padding(vertical = 12.dp))
            TextButton(onClick = { core.adminLogout() }) { Text("Wyloguj z panelu administratora") }
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
    Text("Znaczek ✔ obok nazwy potwierdza, że to prawdziwa osoba/aplikacja. Podpisuje go klucz administratora, więc nikt go nie podrobi.",
        style = MaterialTheme.typography.bodySmall)
    Spacer(Modifier.height(8.dp))
    people.forEach { p ->
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(p.display, Modifier.weight(1f), maxLines = 1)
            if (p.verified) VerifiedBadge()
            TextButton(onClick = { core.adminBadge(p.pk, !p.verified); ver++; toast(ctx, if (p.verified) "Odebrano znaczek" else "Nadano znaczek") }) {
                Text(if (p.verified) "Odbierz" else "Nadaj ✔")
            }
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(code, { code = it.trim() }, Modifier.weight(1f), singleLine = true, placeholder = { Text("Kod osoby spoza listy") })
        TextButton(onClick = {
            val pk = (Links.parse(code) as? Links.Parsed.ContactCode)?.pk ?: code.lowercase().takeIf { it.isHex64() }
            if (pk == null) toast(ctx, "Nie rozpoznano kodu") else { core.adminBadge(pk, true); code = ""; toast(ctx, "Nadano znaczek") }
        }) { Text("Nadaj ✔") }
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
    var msg by remember { mutableStateOf("Ta wersja Szepta jest już nieaktualna. Zainstaluj nową, żeby dalej pisać.") }
    val cur = policy?.optInt("min") ?: 0
    // klucz KLIPY (GIF-y) dla wszystkich
    var gk by remember { mutableStateOf(policy?.optString("gk") ?: "") }
    Card(Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
        Column(Modifier.padding(12.dp)) {
            Text("GIF-y (KLIPY) dla wszystkich", style = MaterialTheme.typography.titleSmall)
            Text(
                if ((policy?.optString("gk") ?: "").isNotEmpty()) "Klucz jest rozesłany — wszyscy mogą szukać GIF-ów." else "Klucz nie jest rozesłany — każdy musi wpisać własny.",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedTextField(gk, { gk = it.trim() }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Klucz aplikacji z klipy.com") })
            TextButton(onClick = {
                scope.launch { try { core.adminSetGifKey(gk); toast(ctx, if (gk.isBlank()) "Usunięto klucz" else "Rozesłano klucz KLIPY") } catch (e: Exception) { toast(ctx, e.message ?: "Błąd") } }
            }) { Text(if (gk.isBlank()) "Usuń klucz u wszystkich" else "Roześlij klucz") }
        }
    }
    TurnCard()
    Card(Modifier.fillMaxWidth()) {
        Text(
            if (cur > 0) "Teraz: wersje starsze niż ${Admin.versionName(cur)} muszą się zaktualizować." else "Teraz: nic nie jest wymuszane.",
            Modifier.padding(12.dp),
        )
    }
    Spacer(Modifier.height(8.dp))
    Text("Osoby ze starszą wersją zobaczą ekran „Musisz zaktualizować” z przyciskiem pobrania tej wersji (${BuildConfig.VERSION_NAME}). Działa u wszystkich, także u nieznajomych.",
        style = MaterialTheme.typography.bodySmall)
    Spacer(Modifier.height(8.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Najstarsza dozwolona wersja: ")
        OutlinedButton(onClick = { menu = true }) { Text(Admin.versionName(min)) }
        DropdownMenu(menu, { menu = false }) {
            versions.forEach { (n, c) -> DropdownMenuItem({ Text(n) }, { min = c; menu = false }) }
        }
    }
    OutlinedTextField(msg, { msg = it }, Modifier.fillMaxWidth(), label = { Text("Komunikat dla użytkowników") }, maxLines = 4)
    Button(onClick = {
        scope.launch {
            try { core.adminPolicy(min, msg, apk, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE); toast(ctx, "Wysyłam — wersje starsze niż ${Admin.versionName(min)} będą musiały się zaktualizować") }
            catch (e: Exception) { toast(ctx, e.message ?: "Nie udało się") }
        }
    }, Modifier.fillMaxWidth()) { Text("Wymuś aktualizację") }
    if (cur > 0) TextButton(onClick = {
        scope.launch { try { core.adminPolicy(0, "", null, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE); toast(ctx, "Wyłączono wymuszanie") } catch (e: Exception) { toast(ctx, e.message ?: "Błąd") } }
    }) { Text("Wyłącz wymuszanie") }
    Text("Uwaga: plik w przekaźnikach jest przechowywany 21 dni — po tym czasie wymuś ponownie, żeby nowi mogli go pobrać.",
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
        Text("Musisz zaktualizować Szept", style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(policy?.optString("msg")?.ifBlank { null } ?: "Ta wersja jest nieaktualna.", textAlign = TextAlign.Center)
        Text("Twoja wersja: ${BuildConfig.VERSION_NAME}" + (policy?.optString("v")?.let { " · nowa: $it" } ?: ""),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
        Spacer(Modifier.height(24.dp))
        val path = update?.path
        when {
            path != null -> Button(onClick = { installApk(ctx, path) }, Modifier.fillMaxWidth()) { Text("Zainstaluj nową wersję") }
            pending != null -> {
                Text("Pobieranie nowej wersji… ${pending.progress}%")
                LinearProgressIndicator(progress = { pending.progress / 100f }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            }
            else -> Text("Czekam na plik nowej wersji. Jeśli to trwa, poproś znajomego o przesłanie nowej wersji Szepta.", textAlign = TextAlign.Center)
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
    val fmt = remember { java.text.SimpleDateFormat("d.MM.yyyy HH:mm", java.util.Locale("pl")) }
    fun apply(mode: String, until: Long) {
        val pk = target ?: return
        scope.launch {
            try { core.adminSanction(pk, mode, until, reason); toast(ctx, "Zapisano — dotrze do wszystkich przy najbliższym połączeniu"); target = null; reason = ""; code = "" }
            catch (e: Exception) { toast(ctx, e.message ?: "Nie udało się") }
        }
    }
    Text(
        "Bez serwera każda kopia Szepta sama pilnuje tej listy (podpisanej Twoim kluczem): wyciszony nie może pisać, zablokowany nie może pisać ani dzwonić, " +
            "a usunięte konto zostaje skasowane z telefonu tej osoby. Ktoś może jednak założyć nowe konto.",
        style = MaterialTheme.typography.bodySmall,
    )
    Spacer(Modifier.height(8.dp))
    val people = remember { core.knownPeople().filter { !Admin.isAdmin(it.pk) } }
    Text("Wybierz osobę", style = MaterialTheme.typography.titleSmall)
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(code, { code = it.trim() }, Modifier.weight(1f), singleLine = true, label = { Text("Kod / link osoby") })
        TextButton(onClick = {
            val pk = (Links.parse(code) as? Links.Parsed.ContactCode)?.pk
            if (pk == null) toast(ctx, "Nie rozpoznano kodu") else target = pk
        }) { Text("Wybierz") }
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
                Text("Wybrano: ${core.db.nameOf(pk)} (${pl.szept.data.shortId(pk)})", fontWeight = FontWeight.Medium)
                OutlinedTextField(reason, { reason = it.take(200) }, Modifier.fillMaxWidth(), label = { Text("Powód (zobaczy go ta osoba)") })
                Text("Wycisz na:", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("1 h" to 3600_000L, "1 dzień" to 86400_000L, "7 dni" to 7 * 86400_000L, "30 dni" to 30 * 86400_000L, "na zawsze" to 0L).forEach { (t, ms) ->
                        OutlinedButton(onClick = { apply("mute", if (ms == 0L) 0L else System.currentTimeMillis() + ms) }) { Text(t) }
                    }
                }
                Button(onClick = { apply("ban", 0L) }, Modifier.fillMaxWidth().padding(top = 6.dp)) { Text("⛔ Zablokuj pisanie (ban)") }
                OutlinedButton(onClick = { confirmDel = true }, Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("🗑 Usuń konto tej osoby") }
                if (core.sanctionOf(pk) != null) TextButton(onClick = { apply("none", 0L) }) { Text("Zdejmij ograniczenia") }
            }
        }
    }
    if (confirmDel) AlertDialog(
        onDismissRequest = { confirmDel = false },
        title = { Text("Usunąć konto?") },
        text = { Text("Konto ${target?.let { core.db.nameOf(it) }} zostanie skasowane z jej telefonu (wiadomości, kontakty, klucze), a inni przestaną dostawać od niej wiadomości. Tego nie da się cofnąć.") },
        confirmButton = { TextButton(onClick = { confirmDel = false; apply("del", 0L) }) { Text("Usuń konto", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { confirmDel = false }) { Text("Anuluj") } },
    )
    HorizontalDivider(Modifier.padding(vertical = 12.dp))
    Text("Aktywne ograniczenia (${list.size})", style = MaterialTheme.typography.titleSmall)
    if (list.isEmpty()) Text("Brak.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    list.forEach { s ->
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(core.db.nameOf(s.pk) + " · " + modeLabel(s.mode) + if (s.until > 0) " do " + fmt.format(java.util.Date(s.until)) else "")
                if (s.reason.isNotBlank()) Text(s.reason, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (s.mode != "del") TextButton(onClick = {
                scope.launch { try { core.adminSanction(s.pk, "none", 0, ""); toast(ctx, "Zdjęto ograniczenia") } catch (e: Exception) { toast(ctx, e.message ?: "Błąd") } }
            }) { Text("Cofnij") }
        }
    }
}

private fun modeLabel(m: String) = when (m) { "mute" -> "wyciszony"; "ban" -> "zablokowany"; else -> "konto usunięte" }

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
    Text("Hasło panelu", style = MaterialTheme.typography.titleSmall)
    if (core.adminViaOtp) Text("Wszedłeś kodem jednorazowym — podgląd hasła i tworzenie kodów są niedostępne.", style = MaterialTheme.typography.bodySmall)
    else {
        val saved = remember { core.adminSavedPassword() }
        if (saved == null) Text("Hasło nie jest zapamiętane na tym telefonie (zaloguj się ponownie hasłem, żeby móc je tu podejrzeć).", style = MaterialTheme.typography.bodySmall)
        else Row(verticalAlignment = Alignment.CenterVertically) {
            Text(shown ?: "•".repeat(12), Modifier.weight(1f), fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
            TextButton(onClick = { shown = if (shown == null) saved else null }) { Text(if (shown == null) "Pokaż" else "Ukryj") }
            if (shown != null) TextButton(onClick = { copyText(ctx, saved) }) { Text("Kopiuj") }
        }
        Text("Hasło jest zapisane zaszyfrowane kluczem tego telefonu. Nie pokazuj go nikomu.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        HorizontalDivider(Modifier.padding(vertical = 12.dp))
        Text("Kody jednorazowe", style = MaterialTheme.typography.titleSmall)
        Text(
            "Kod pozwala wejść do panelu raz (np. na innym telefonie) bez podawania hasła. Daje pełny dostęp na jedną sesję — przekaż go tylko zaufanej osobie.",
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedTextField(label, { label = it.take(40) }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Dla kogo / opis (opcjonalnie)") })
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("ważny 1 h" to 3600_000L, "1 dzień" to 86400_000L, "7 dni" to 7 * 86400_000L).forEach { (t, ms) ->
                OutlinedButton(onClick = {
                    scope.launch { try { newCode = core.adminCreateOtp(ms, label); label = "" } catch (e: Exception) { toast(ctx, e.message ?: "Błąd") } }
                }) { Text(t) }
            }
        }
        newCode?.let { c ->
            Card(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                Column(Modifier.padding(12.dp)) {
                    Text("Nowy kod (zapisz go teraz — później nie da się go wyświetlić):", style = MaterialTheme.typography.labelMedium)
                    Text(c, style = MaterialTheme.typography.titleLarge, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                    TextButton(onClick = { copyText(ctx, c) }) { Text("Kopiuj") }
                }
            }
        }
        val fmt = remember { java.text.SimpleDateFormat("d.MM HH:mm", java.util.Locale("pl")) }
        Text("Aktywne kody (${otps.size})", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
        otps.forEach { o ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text((o.label.ifBlank { "kod" }) + " · ważny do " + fmt.format(java.util.Date(o.exp)), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { scope.launch { core.adminRevokeOtp(o.id) } }) { Text("Unieważnij") }
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
            Text("Rozmowy: serwery TURN dla wszystkich", style = MaterialTheme.typography.titleSmall)
            Text(
                (if (current.isNotEmpty()) "Rozesłane serwery: ${current.size}. " else "Brak serwera — rozmowy przez sieć komórkową często zostają na „Łączenie…”. ") +
                    "TURN przekazuje zaszyfrowany dźwięk i obraz, gdy telefony nie mogą połączyć się bezpośrednio. Możesz dodać kilku dostawców — telefon użyje tego, który działa.",
                style = MaterialTheme.typography.bodySmall,
            )
            rows.forEachIndexed { i, r ->
                Text("Serwer ${i + 1}", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
                OutlinedTextField(r.urls, { v -> rows[i] = r.copy(urls = v) }, Modifier.fillMaxWidth(),
                    label = { Text("Adresy (każdy w nowej linii), np. free.expressturn.com:3478") }, minLines = 2)
                OutlinedTextField(r.user, { v -> rows[i] = r.copy(user = v.trim()) }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Użytkownik (username)") })
                OutlinedTextField(r.pass, { v -> rows[i] = r.copy(pass = v.trim()) }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Hasło (password / credential)") })
                if (rows.size > 1) TextButton(onClick = { rows.removeAt(i) }) { Text("Usuń ten serwer") }
            }
            Row {
                if (rows.size < 4) TextButton(onClick = { rows.add(Row("", "", "")) }) { Text("+ Dodaj serwer") }
                TextButton(onClick = {
                    val list = rows.filter { it.urls.isNotBlank() }.map { pl.szept.core.Core.TurnServer(it.urls.lines(), it.user, it.pass) }
                    scope.launch {
                        try { core.adminSetTurns(list); toast(ctx, if (list.isEmpty()) "Usunięto serwery TURN" else "Rozesłano serwery TURN: ${list.size}") }
                        catch (e: Exception) { toast(ctx, e.message ?: "Błąd") }
                    }
                }) { Text("Roześlij wszystkim") }
            }
        }
    }
}
