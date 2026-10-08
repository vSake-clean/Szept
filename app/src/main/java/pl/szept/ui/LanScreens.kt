package pl.szept.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.WifiTethering
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import pl.szept.App
import pl.szept.core.Links
import pl.szept.lan.LanManager
import pl.szept.nostr.Relay

/** Tryb bez internetu: przekaźnik w telefonie, telefony w pobliżu, hotspot. */
@Composable
fun LanScreen(nav: Nav) {
    val ctx = LocalContext.current
    val core = App.core
    var on by remember { mutableStateOf(core.lanEnabled) }
    var fwd by remember { mutableStateOf(core.lanForward) }
    var courier by remember { mutableStateOf(core.courier) }
    var manual by remember { mutableStateOf("") }
    val states by core.relayStates.collectAsState()
    val running by core.lan.running.collectAsState()
    val hotspot by core.lan.hotspot.collectAsState()
    val status by core.lan.status.collectAsState()
    val stored by core.lan.stored.collectAsState()
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(running) { while (running) { delay(3000); tick++ } }
    val peers = remember(states, tick) { core.localRelayInfo().filter { !it.first.contains("?self=") } }
    val addresses = remember(running, tick / 5) { if (running) core.lan.myAddresses() else emptyList() }
    val clients = remember(states, tick) { core.lan.relay.clientCount }

    val hotspotPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        if (res.values.all { it }) core.lan.startHotspot()
        else toast(ctx, "Bez tego uprawnienia Android nie pozwala utworzyć sieci Wi-Fi")
    }
    val scan = rememberQrScanner { text ->
        when (val p = Links.parse(text)) {
            is Links.Parsed.Lan -> {
                if (!on) { on = true; core.setLanEnabled(true) }
                if (!core.lan.joinNetwork(p.ssid, p.pass, p.host, p.port))
                    toast(ctx, "Połącz się ręcznie w ustawieniach Wi-Fi z siecią „${p.ssid}”, hasło: ${p.pass}")
            }
            else -> toast(ctx, "To nie jest kod sieci Szept")
        }
    }

    Scaffold(topBar = { BackBar("Bez internetu", nav) }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(16.dp)) {
            Text(
                "Gdy nie ma internetu (wyjazd, góry, awaria), telefony z Szeptem w tej samej sieci Wi-Fi mogą pisać do siebie bezpośrednio. " +
                    "Każdy telefon z włączoną opcją przechowuje zaszyfrowane koperty dla innych i oddaje je, gdy się pojawią. " +
                    "Nie ma routera? Jeden telefon tworzy sieć Wi-Fi, a inni skanują jego kod.",
                style = MaterialTheme.typography.bodyMedium,
            )
            ListItem(
                headlineContent = { Text("Sieć lokalna", fontWeight = FontWeight.Bold) },
                supportingContent = {
                    Text(
                        if (!running) "Wyłączona"
                        else "Działa · telefony w pobliżu: ${peers.count { it.second == Relay.State.CONNECTED }}" +
                            (if (clients > 0) " · połączonych z Tobą: $clients" else "") + " · koperty w pamięci: $stored",
                    )
                },
                trailingContent = { Switch(on, { v -> on = v; core.setLanEnabled(v) }) },
            )
            if (running) {
                if (addresses.isNotEmpty()) Text(
                    "Adres tego telefonu: " + addresses.joinToString { "$it:${core.lan.relay.port}" },
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                peers.forEach { (url, st) ->
                    Text(
                        (if (st == Relay.State.CONNECTED) "● " else "○ ") + url.removePrefix("ws://"),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (st == Relay.State.CONNECTED) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            status?.let { Text(it, Modifier.padding(top = 6.dp), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall) }

            HorizontalDivider(Modifier.padding(vertical = 12.dp))
            Text("Bez routera: sieć z tego telefonu", fontWeight = FontWeight.Bold)
            val h = hotspot
            if (h == null) {
                Text(
                    "Telefon utworzy własną sieć Wi-Fi (bez internetu). Pozostali zeskanują kod, który się tu pojawi.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(6.dp))
                Button(onClick = {
                    if (!on) { on = true; core.setLanEnabled(true) }
                    hotspotPerm.launch(
                        if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES)
                        else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                    )
                }, Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.WifiTethering, null); Spacer(Modifier.width(8.dp)); Text("Utwórz sieć Wi-Fi")
                }
            } else {
                val link = remember(h) { LanManager.joinLink(h) }
                Card(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                    Column(Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        QrImage(link, 220.dp)
                        Text("Sieć: ${h.ssid}", fontWeight = FontWeight.Bold)
                        Text("Hasło: ${h.pass}")
                        Text(
                            "Znajomi: Szept → Bez internetu → „Dołącz do sieci” i zeskanuj kod. Na starszych telefonach połącz się z tą siecią w ustawieniach Wi-Fi.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Row {
                            TextButton(onClick = { shareText(ctx, "Sieć Szept bez internetu: $link") }) { Icon(Icons.Default.Share, null); Spacer(Modifier.width(4.dp)); Text("Wyślij") }
                            TextButton(onClick = { core.lan.stopHotspot() }) { Text("Wyłącz sieć") }
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = scan, Modifier.fillMaxWidth()) {
                Icon(Icons.Default.QrCodeScanner, null); Spacer(Modifier.width(8.dp)); Text("Dołącz do sieci (skanuj kod)")
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                OutlinedTextField(manual, { manual = it.trim() }, Modifier.weight(1f), singleLine = true, placeholder = { Text("lub adres, np. 192.168.1.20") })
                TextButton(onClick = {
                    if (!on) { on = true; core.setLanEnabled(true) }
                    val url = core.lan.addManual(manual)
                    toast(ctx, if (url == null) "Nieprawidłowy adres" else "Dodano $url")
                    if (url != null) manual = ""
                }) { Text("Dodaj") }
            }

            HorizontalDivider(Modifier.padding(vertical = 12.dp))
            Text("Kurier", fontWeight = FontWeight.Bold)
            ListItem(
                headlineContent = { Text("Wysyłaj dalej, gdy wróci internet") },
                supportingContent = { Text("Koperty, które inni zostawili w Twoim telefonie, trafią na przekaźniki w internecie, gdy tylko złapiesz zasięg.") },
                trailingContent = { Switch(fwd, { v -> fwd = v; core.setLanForward(v) }) },
            )
            ListItem(
                headlineContent = { Text("Zabieraj wiadomości dla znajomych") },
                supportingContent = {
                    Text(
                        "Gdy masz internet, telefon pobiera zaszyfrowane koperty dla Twoich znajomych (z ostatnich 3 dni) i oddaje je, " +
                            "gdy spotkacie się bez internetu. Nie widzisz ich treści. Zużywa trochę więcej danych.",
                    )
                },
                trailingContent = { Switch(courier, { v -> courier = v; core.setCourier(v) }) },
            )
            if (stored > 0) TextButton(onClick = { core.lan.relay.clear(); core.lan.stored.value = 0; toast(ctx, "Wyczyszczono") }) {
                Text("Wyczyść przechowywane koperty ($stored)")
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
