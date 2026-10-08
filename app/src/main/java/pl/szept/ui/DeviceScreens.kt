package pl.szept.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import pl.szept.App
import pl.szept.core.Core

/** Moje urządzenia: jedno konto na kilku telefonach/tabletach. */
@Composable
fun DevicesScreen(nav: Nav) {
    val core = App.core
    var multi by remember { mutableStateOf(core.multiDevice) }
    var tick by remember { mutableIntStateOf(0) }
    var forget by remember { mutableStateOf<Core.Device?>(null) }
    LaunchedEffect(Unit) { while (true) { delay(3000); tick++ } }
    val devices = remember(tick, multi) { core.devices() }
    Scaffold(topBar = { BackBar("Moje urządzenia", nav) }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
            Text(
                "Możesz używać jednego konta na kilku telefonach lub tablecie. Wiadomości, które wyślesz z jednego, " +
                    "pojawią się na pozostałych; tak samo przeczytanie, reakcje, edycje, usuwanie, wyciszenie i nowe kontakty.",
                style = MaterialTheme.typography.bodyMedium,
            )
            ListItem(
                headlineContent = { Text("Synchronizuj z innymi urządzeniami", fontWeight = FontWeight.Bold) },
                supportingContent = { Text(if (multi) "Włączone" else "Włączy się samo, gdy zalogujesz się na drugim urządzeniu") },
                trailingContent = { Switch(multi, { v -> multi = v; core.setMultiDevice(v) }) },
            )
            HorizontalDivider()
            devices.forEach { d ->
                ListItem(
                    leadingContent = { Icon(if (d.me) Icons.Default.PhoneAndroid else Icons.Default.Devices, null) },
                    headlineContent = { Text(d.name + if (d.me) " (to urządzenie)" else "") },
                    supportingContent = { if (!d.me) Text("Ostatnio: " + formatWhen(d.seen)) },
                    trailingContent = { if (!d.me) TextButton(onClick = { forget = d }) { Text("Zapomnij") } },
                )
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Text("Jak dodać urządzenie", fontWeight = FontWeight.Bold)
            Text(
                "1. Zainstaluj Szept na drugim urządzeniu.\n" +
                    "2. Na ekranie powitalnym wybierz „Mam już konto” i wpisz kod dostępu do konta z tego telefonu.\n" +
                    "3. Urządzenia same się odnajdą — drugie dostanie kontakty i grupy, a od tej chwili wszystkie nowe wiadomości.\n" +
                    "Starsza historia: przenieś ją kopią zapasową (Ustawienia → Kopia zapasowa).",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { nav.go(Screen.AccessCode) }, Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Key, null); Text("  Pokaż kod dostępu do konta")
            }
            Spacer(Modifier.height(24.dp))
        }
    }
    forget?.let { d ->
        AlertDialog(
            onDismissRequest = { forget = null },
            title = { Text("Zapomnieć „${d.name}”?") },
            text = { Text("Przestanie dostawać kopie Twoich wiadomości. Jeśli to urządzenie zostało skradzione, zmień też konto — kod dostępu jest na nim zapisany.") },
            confirmButton = { TextButton(onClick = { core.forgetDevice(d.id); forget = null; tick++ }) { Text("Zapomnij") } },
            dismissButton = { TextButton(onClick = { forget = null }) { Text("Anuluj") } },
        )
    }
}
