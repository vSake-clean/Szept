package pl.szept.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.NewReleases
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiTethering
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import pl.szept.App
import pl.szept.BuildConfig
import pl.szept.nostr.Relay

/** Zmiany w kolejnych wersjach (najnowsza na górze). Każda nowa wersja dopisuje tu swój wpis. */
val CHANGELOG: List<Pair<String, List<String>>> = listOf(
    "3.0" to listOf(
        "Wyślij, gdy będzie online: przytrzymaj przycisk wysyłania → „🟢 Wyślij, gdy będzie online” — wiadomość czeka i wychodzi sama, gdy rozmówca otworzy Szept.",
        "Tłumaczenie w telefonie: przytrzymaj wiadomość → „🌐 Przetłumacz”. Treść nie opuszcza telefonu — internet potrzebny tylko raz, do pobrania pakietu języka.",
        "Formatowanie tekstu: *pogrubienie*, _kursywa_, ~przekreślenie~, `kod` i ||spoiler|| (ukryty tekst — odkrywa się po dotknięciu, w powiadomieniach jest zakryty).",
        "Fałszywy PIN (Prywatność i blokada): drugi PIN otwiera „pusty” Szept bez Twoich rozmów i kontaktów. Prawdziwe wiadomości przychodzą dalej, ale bez powiadomień, dopóki nie wpiszesz prawdziwego PIN-u.",
        "Zamaskowana ikona: Szept może udawać działający „Kalkulator” — otwiera się dopiero po wpisaniu Twojego kodu i „=”.",
        "Automatyczne czyszczenie: w profilu osoby lub informacjach o grupie ustawisz, żeby wiadomości starsze niż 7, 30 albo 90 dni same znikały z tego telefonu.",
        "Podgląd Szeptu na liście ostatnich aplikacji jest zasłonięty (można wyłączyć w Prywatność i blokada → Zrzuty ekranu).",
        "Gry w czacie (📎 → 🎮 Gra): kółko i krzyżyk, rzut kośćmi i „Kto szybciej?” — grane wiadomościami, bez żadnego serwera.",
    ),
    "2.9" to listOf(
        "Rozmowy działają od razu także przez internet komórkowy — serwery pośredniczące (Metered i ExpressTURN) są wbudowane w aplikację, nic nie trzeba ustawiać.",
        "Rozmowy: kilka serwerów TURN naraz (np. Metered i ExpressTURN), każdy z własnym użytkownikiem i hasłem — telefon używa tego, który działa.",
        "Adres serwera TURN można wkleić tak, jak pokazuje go dostawca (np. „free.expressturn.com:3478”) — Szept sam dopisze „turn:”.",
    ),
    "2.8" to listOf(
        "Rozmowy: naprawione wieczne „Łączenie…” — gdy połączenia nie da się zestawić, po 30 s pojawia się jasny komunikat (i wpis w czacie), zamiast wisieć bez końca.",
        "Rozmowy: panel administratora → „Wymuszanie” → „Serwer TURN dla wszystkich” — po rozesłaniu rozmowy działają także przez sieć komórkową, gdzie połączenie bezpośrednie jest blokowane.",
        "Rozmowy: pewniejsze wykrywanie nawiązania połączenia i więcej serwerów STUN.",
        "Dziennik błędów (Ustawienia → Pomoc) zapisuje przebieg łączenia rozmowy — łatwiej znaleźć przyczynę, gdy coś nie działa.",
    ),
    "2.7" to listOf(
        "Panel administratora → „Konta”: wyciszenie osoby (1 h – 30 dni albo na zawsze), blokada pisania (ban) i usunięcie konta — z powodem, który zobaczy ta osoba. Każdą sankcję można cofnąć.",
        "Panel administratora → „Dostęp”: podgląd hasła panelu (zapisanego zaszyfrowanie na tym telefonie) i kody jednorazowe — wejście do panelu bez hasła, działają tylko raz.",
        "Grupy: administrator grupy może wyciszyć osobę (1 h, 8 h, 1 dzień, 7 dni, do odwołania) albo ją zbanować — zbanowana nie wróci z linku zaproszenia. Lista zbanowanych z przyciskiem „Odbanuj”.",
        "Wyciszona osoba widzi w czacie, do kiedy nie może pisać.",
    ),
    "2.6" to listOf(
        "Naprawiona awaria aplikacji w tle (po zmianie zdjęcia profilowego lub banera aplikacja potrafiła się zamykać).",
        "Bezpieczeństwo: pliki od innych osób są dokładnie sprawdzane — złośliwa wiadomość nie może już usunąć danych ani wywrócić aplikacji.",
        "Błędny adres przekaźnika albo literówka w kodzie znajomego nie zamykają już aplikacji.",
        "Wysyłanie zdjęć, wideo, GIF-ów i albumów kończy się także po wyjściu z czatu (np. gdy ktoś zadzwoni).",
        "Nagrywanie głosówki i kamera notatki wideo wyłączają się po zamknięciu czatu/okna.",
        "Anulowanie „Zaplanuj” nie kasuje już wybranego zdjęcia; zdjęcie z aparatu nie ginie po obróceniu telefonu.",
        "Wiadomości w kolejce nie przepadają po 7 dniach bez internetu (czekają tak długo jak koperty — 21 dni).",
        "Dziennik błędów: Ustawienia → Pomoc → „Dziennik błędów” — można go skopiować i przesłać, gdy coś nie działa.",
    ),
    "2.5" to listOf(
        "GIF-y: przycisk „GIF” w polu wiadomości (albo spinacz → GIF). Każdy GIF widać animowany jeszcze przed wysłaniem.",
        "Moje GIF-y: własna kolekcja zapisana tylko w telefonie — dodasz GIF z telefonu albo zapiszesz GIF z rozmowy (dotknij go → „Zapisz w moich”). Przytrzymaj, aby usunąć.",
        "Wyszukiwarka GIF-ów KLIPY: popularne i wyszukiwanie po polsku. Wymaga bezpłatnego klucza z klipy.com (wpisz go w oknie GIF-ów albo administrator roześle go wszystkim).",
        "GIF wybrany z galerii zostaje animowany (wcześniej zamieniał się w zwykłe zdjęcie).",
        "Usunięto edytor zdjęć przy wysyłaniu i w relacjach — zostaje tylko kadrowanie zdjęcia profilowego i banera.",
    ),
    "2.4" to listOf(
        "Naprawione rozmowy prywatne — wcześniej dzwonek nie docierał do rozmówcy.",
        "Do trwającej rozmowy można dodawać osoby (ikona osoby z plusem) — dostają dzwonek; w grupie można też zadzwonić ponownie do tych, którzy nie odebrali.",
        "Nowy wygląd rozmów głosowych: duże zdjęcie rozmówcy, pulsowanie przy dzwonieniu, kolorowe tło. Rozmowa wideo we dwoje: obraz rozmówcy na cały ekran.",
        "Nie ma już okna „Nikt nie odebrał” — ekran rozmowy po prostu się zamyka, a w czacie zostaje wpis. Odrzucenie połączenia dzwoniący widzi od razu.",
        "Edytor zdjęć: przyciski nie chowają się pod paskami telefonu, wyraźny przycisk „Przytnij”, a ustawiony kadr zapisuje się też po „Gotowe” (wcześniej ginął — także przy zdjęciu profilowym i banerze).",
        "Na podglądzie zdjęcia przed wysłaniem jest przycisk „Edytuj / przytnij” (albo dotknij zdjęcia).",
        "Usuwanie ze znajomych: w profilu osoby „Usuń ze znajomych” (z opcją usunięcia rozmowy).",
    ),
    "2.3" to listOf(
        "Statusy znajomych: zielony — jest w aplikacji, żółty — aplikacja działa w tle, pomarańczowy — „zaraz wracam”, szary — nieaktywny (aplikacja wyłączona).",
        "Status widać jako kropkę przy awatarze na liście rozmów, pod nazwą w czacie i w profilu.",
        "Mój profil → „Mój status”: automatycznie albo „Zaraz wracam”; można też ukryć swój status przed innymi.",
        "Odpowiedź gestem: przesuń wiadomość w prawo, aby na nią odpowiedzieć; przytrzymaj, aby dodać reakcję.",
        "„Mój kod” ma teraz przycisk „Skanuj kod QR” — dodasz znajomego bez szukania innego ekranu.",
    ),
    "2.2" to listOf(
        "Prostsze ustawienia: kilka czytelnych kategorii zamiast długiej listy.",
        "PIN i kod awaryjny: ikonka kosza (usuwanie po wpisaniu PIN-u) — wcześniej nie dało się ich wyłączyć.",
        "Wiadomość „usunięta u rozmówcy” ma u Ciebie oznaczenie (czerwona kropka, napis albo nic — do wyboru).",
        "Zrzuty ekranu: informacja w czacie, gdy ktoś zrobi zrzut; można też całkowicie zablokować zrzuty w Szepcie.",
        "Wiadomości czasowych nie da się sfotografować zrzutem ekranu.",
        "Lokalizacja: tylko teraz albo na żywo przez wybrany czas (15 min – 8 h).",
        "W czacie u góry: przycisk dzwonienia; wiadomości czasowe przeniesione do menu ⋮.",
        "Oficjalne konto „Szept” ✔ z aktualizacjami — na jego wiadomości nie da się odpowiadać.",
        "Znaczek weryfikacji ✔ przy nazwie — nie do podrobienia.",
        "Panel administratora (tylko z hasłem): aktualizacje do wybranych osób, wymuszanie aktualizacji starych wersji.",
        "Ekran „Co nowego” po każdej aktualizacji.",
    ),
)

@Composable
private fun Category(icon: ImageVector, title: String, sub: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(42.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Heading(t: String) {
    Text(t, Modifier.padding(start = 16.dp, top = 18.dp, bottom = 2.dp), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
}

/** Ustawienia: kilka prostych kategorii. Szczegóły techniczne są głębiej. */
@Composable
fun SettingsScreen(nav: Nav) {
    val ctx = LocalContext.current
    val core = App.core
    val act = ctx as? MainActivity
    val states by core.relayStates.collectAsState()
    var battery by remember { mutableStateOf(true) }
    var notif by remember { mutableStateOf(true) }
    LifecycleResumeEffect(Unit) {
        battery = MainActivity.ignoresBattery(ctx); notif = MainActivity.hasNotificationPermission(ctx)
        onPauseOrDispose { }
    }
    val connected = states.values.count { it == Relay.State.CONNECTED }

    Scaffold(topBar = { BackBar("Ustawienia", nav) }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState())) {
            val avatar = remember { if (core.avatarFile.exists()) pl.szept.crypto.B64.enc(core.avatarFile.readBytes()) else null }
            Card(Modifier.fillMaxWidth().padding(12.dp).clickable { nav.go(Screen.MyProfile) }) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Avatar(core.myName, core.myPub, 56.dp, image = avatar)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(core.myName, style = MaterialTheme.typography.titleLarge)
                            if (core.myBadge) VerifiedBadge(20.dp)
                        }
                        Text("Dotknij, aby zmienić zdjęcie, nazwę i opis", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null)
                }
            }
            // najważniejsze problemy — od razu widoczne, z jednym przyciskiem
            if (!notif || !battery) Card(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
            ) {
                Column(Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Warning, null); Spacer(Modifier.width(8.dp))
                        Text("Wiadomości mogą przychodzić z opóźnieniem", fontWeight = FontWeight.Bold)
                    }
                    if (!notif) TextButton(onClick = { act?.askNotifications() }) { Text("Włącz powiadomienia") }
                    if (!battery) TextButton(onClick = { MainActivity.askBattery(ctx) }) { Text("Zezwól na działanie w tle") }
                }
            }
            Heading("Najczęściej używane")
            Category(Icons.Default.Lock, "Prywatność i bezpieczeństwo", "PIN, kod awaryjny, zrzuty ekranu, potwierdzenia odczytu") { nav.go(Screen.Security) }
            Category(Icons.Default.Palette, "Wygląd i dźwięki", "Motyw, kolory, tapeta, wielkość tekstu, dźwięk powiadomień") { nav.go(Screen.Appearance) }
            Category(Icons.Default.People, "Osoby", "Twoje kontakty, blokowanie") { nav.go(Screen.People) }
            Category(Icons.Default.PersonAdd, "Zaproś znajomych", "Wyślij im aplikację i swój kod") { shareApp(ctx) }
            Heading("Konto i telefon")
            Category(Icons.Default.Backup, "Kopia zapasowa", "Przenieś całą historię na nowy telefon") { nav.go(Screen.Backup) }
            Category(Icons.Default.Key, "Kod dostępu do konta", "Zaloguj się na innym telefonie") { nav.go(Screen.AccessCode) }
            Category(Icons.Default.Devices, "Moje urządzenia", "Jedno konto na kilku telefonach") { nav.go(Screen.Devices) }
            Heading("Połączenie")
            Category(Icons.Default.Wifi, "Połączenie i bateria", if (connected > 0) "Połączono ($connected) ✓" else "Brak połączenia — dotknij, aby sprawdzić") { nav.go(Screen.Connection) }
            Category(Icons.Default.WifiTethering, "Bez internetu", "Pisanie przez Wi-Fi lub hotspot, gdy nie ma internetu") { nav.go(Screen.Lan) }
            Category(Icons.Default.Call, "Rozmowy", "Połączenia głosowe i wideo") { nav.go(Screen.CallSettings) }
            Heading("Pomoc")
            Category(Icons.Default.NewReleases, "Co nowego", "Zmiany w wersji ${BuildConfig.VERSION_NAME}") { nav.go(Screen.WhatsNew) }
            Category(Icons.Default.HelpOutline, "Jak działa Szept", "Prosto o tym, jak to działa bez serwera") { nav.go(Screen.Help) }
            HorizontalDivider(Modifier.padding(top = 12.dp))
            Text("Szept ${BuildConfig.VERSION_NAME}", Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = { nav.go(Screen.Admin) }, Modifier.padding(horizontal = 8.dp)) {
                Icon(Icons.Default.AdminPanelSettings, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Panel administratora")
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

/** Połączenie: bateria, tryb pracy w tle, przekaźniki. */
@Composable
fun ConnectionScreen(nav: Nav) {
    val ctx = LocalContext.current
    val core = App.core
    val states by core.relayStates.collectAsState()
    var bgMode by remember { mutableStateOf(core.bgMode) }
    var relays by remember { mutableStateOf(core.configuredRelays()) }
    var newRelay by remember { mutableStateOf("") }
    var battery by remember { mutableStateOf(true) }
    var advanced by remember { mutableStateOf(false) }
    LifecycleResumeEffect(Unit) { battery = MainActivity.ignoresBattery(ctx); onPauseOrDispose { } }
    val info = remember(states, relays) { core.relayInfo() }
    val connected = states.values.count { it == Relay.State.CONNECTED }
    Scaffold(topBar = { BackBar("Połączenie i bateria", nav) }) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize()) {
            item {
                Card(Modifier.fillMaxWidth().padding(12.dp)) {
                    Text(
                        if (connected > 0) "✓ Szept działa — połączono z $connected " + plural(connected, "przekaźnikiem", "przekaźnikami", "przekaźnikami")
                        else "Brak połączenia. Sprawdź internet.",
                        Modifier.padding(14.dp), style = MaterialTheme.typography.titleSmall,
                    )
                }
                ListItem(
                    headlineContent = { Text("Działanie w tle") },
                    supportingContent = { Text(if (battery) "Dozwolone ✓ — wiadomości przychodzą na bieżąco" else "Android może usypiać Szepta i wiadomości się spóźnią") },
                    trailingContent = { if (!battery) TextButton(onClick = { MainActivity.askBattery(ctx) }) { Text("Zezwól") } },
                )
                Text(
                    "Xiaomi, Huawei, Oppo, Vivo, Samsung: włącz też „Autostart” dla Szepta (dotknij tutaj).",
                    Modifier.padding(horizontal = 16.dp).clickable {
                        try { ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + ctx.packageName))) } catch (_: Exception) {}
                    },
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary,
                )
                Heading("Bateria")
                listOf(
                    Triple(pl.szept.core.Core.BG_BALANCED, "Normalnie (zalecane)", "Wiadomości od razu, rozsądne zużycie baterii."),
                    Triple(pl.szept.core.Core.BG_FULL, "Zawsze na bieżąco", "Najszybciej i najpewniej, ale bateria schodzi szybciej."),
                    Triple(pl.szept.core.Core.BG_SAVER, "Oszczędzanie baterii", "Nowe wiadomości co ok. 15 minut. Najmniej baterii."),
                ).forEach { (mode, title, sub) ->
                    Row(
                        Modifier.fillMaxWidth().clickable { bgMode = mode; core.setBgMode(mode) }.padding(horizontal = 8.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(bgMode == mode, { bgMode = mode; core.setBgMode(mode) })
                        Column {
                            Text(title, style = MaterialTheme.typography.bodyLarge)
                            Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                HorizontalDivider(Modifier.padding(top = 8.dp))
                ListItem(
                    headlineContent = { Text("Dla zaawansowanych: przekaźniki") },
                    supportingContent = { Text("Lista serwerów pośredniczących. Zwykle nie trzeba nic zmieniać.") },
                    modifier = Modifier.clickable { advanced = !advanced },
                    trailingContent = { Text(if (advanced) "Ukryj" else "Pokaż", color = MaterialTheme.colorScheme.primary) },
                )
            }
            if (advanced) {
                items(info, key = { it.first }) { (url, st, err) ->
                    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(10.dp).clip(CircleShape).background(
                            when (st) { Relay.State.CONNECTED -> OkGreen; Relay.State.CONNECTING -> Fire; else -> Color.Gray },
                        ))
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(url.removePrefix("wss://"), style = MaterialTheme.typography.bodyMedium)
                            if (st != Relay.State.CONNECTED && err != null) Text(err, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                        }
                        IconButton(onClick = {
                            if (relays.size > 1) { relays = relays - url; core.setRelays(relays) } else toast(ctx, "Musi zostać co najmniej jeden przekaźnik")
                        }) { Icon(Icons.Default.Close, "Usuń") }
                    }
                }
                item {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(newRelay, { newRelay = it.trim() }, Modifier.weight(1f), placeholder = { Text("wss://twoj.przekaznik") }, singleLine = true)
                        TextButton(onClick = {
                            val u = if (newRelay.startsWith("ws")) newRelay else "wss://$newRelay"
                            if (!pl.szept.nostr.isValidRelayUrl(u)) toast(ctx, "To nie jest poprawny adres przekaźnika (np. wss://nazwa.pl)")
                            else if (u !in relays) { relays = relays + u; core.setRelays(relays); newRelay = "" }
                        }) { Text("Dodaj") }
                    }
                    TextButton(onClick = { relays = pl.szept.core.Core.DEFAULT_RELAYS; core.setRelays(relays) }, Modifier.padding(horizontal = 8.dp)) {
                        Text("Przywróć domyślne")
                    }
                    ListItem(
                        leadingContent = { Icon(Icons.Default.Router, null) },
                        headlineContent = { Text("Własny przekaźnik") },
                        supportingContent = { Text("Instrukcja dla Raspberry Pi / komputera") },
                        modifier = Modifier.clickable { nav.go(Screen.OwnRelay) },
                    )
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

/** Co nowego — zmiany w kolejnych wersjach. */
@Composable
fun WhatsNewScreen(nav: Nav) {
    Scaffold(topBar = { BackBar("Co nowego", nav) }) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize().padding(horizontal = 16.dp)) {
            items(CHANGELOG) { (v, items) ->
                Text("Wersja $v", Modifier.padding(top = 16.dp, bottom = 6.dp), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                items.forEach { Text("• $it", Modifier.padding(vertical = 3.dp), style = MaterialTheme.typography.bodyMedium) }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

/** Jak działa Szept — prostym językiem. */
@Composable
fun HelpScreen(nav: Nav) {
    Scaffold(topBar = { BackBar("Jak działa Szept", nav) }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
            listOf(
                "📮 Szept nie ma własnego serwera ani firmy, która czyta wiadomości. Twoje konto powstało w Twoim telefonie.",
                "🔒 Każda wiadomość jest zamykana w „kopercie”, którą otworzy tylko telefon odbiorcy.",
                "📬 Koperty czekają w kilku niezależnych „skrzynkach pocztowych” w internecie (do 21 dni), aż odbiorca włączy telefon.",
                "📵 Bez internetu? Telefony w tej samej sieci Wi-Fi mogą pisać do siebie bezpośrednio (Ustawienia → Bez internetu).",
                "📱 Zmieniasz telefon? Zrób kopię zapasową (Ustawienia → Kopia zapasowa) i przywróć ją na nowym.",
                "✔ Niebieski znaczek przy nazwie oznacza konto potwierdzone przez Szept. Nie da się go podrobić.",
            ).forEach { Text(it, Modifier.padding(vertical = 6.dp), style = MaterialTheme.typography.bodyLarge) }
            CrashLogSection()
        }
    }
}

/** Dziennik błędów: zapisany tylko w telefonie; można go skopiować i przesłać twórcy. */
@Composable
private fun CrashLogSection() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var log by remember { mutableStateOf(pl.szept.core.CrashLog.read()) }
    androidx.compose.material3.HorizontalDivider(Modifier.padding(vertical = 12.dp))
    Text("Dziennik błędów", style = MaterialTheme.typography.titleMedium)
    if (log.isBlank()) Text("Brak zapisanych błędów. 👍", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    else {
        Text(
            "Jeśli coś nie działa, skopiuj dziennik i wyślij go twórcy aplikacji. Nie zawiera treści wiadomości.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        androidx.compose.foundation.layout.Row {
            androidx.compose.material3.TextButton(onClick = { copyText(ctx, log) }) { Text("Kopiuj dziennik") }
            androidx.compose.material3.TextButton(onClick = { pl.szept.core.CrashLog.clear(); log = "" }) { Text("Wyczyść") }
        }
        Text(log.takeLast(4000), style = MaterialTheme.typography.labelSmall, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
    }
}
