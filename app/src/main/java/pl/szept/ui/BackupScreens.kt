package pl.szept.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import pl.szept.App
import pl.szept.core.Backup
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
private fun PasswordField(value: String, label: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value, { onChange(it.take(128)) }, Modifier.fillMaxWidth(), label = { Text(label) }, singleLine = true,
        visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
    )
}

/** Zapis zaszyfrowanej kopii całego konta do pliku (np. w Pobranych albo na Dysku). */
@Composable
fun BackupScreen(nav: Nav) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val core = App.core
    var pass by remember { mutableStateOf("") }
    var pass2 by remember { mutableStateOf("") }
    var withMedia by remember { mutableStateOf(true) }
    var progress by remember { mutableStateOf<Int?>(null) }
    var last by remember { mutableStateOf(core.prefs.getLong("last_backup", 0)) }
    // liczenie rozmiaru plików poza wątkiem interfejsu
    val sizes by androidx.compose.runtime.produceState(0L to 0L) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { Backup.estimateSize(core, true) to Backup.estimateSize(core, false) }
    }
    val create = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        progress = 0
        val pw = pass.toCharArray()
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val os = ctx.contentResolver.openOutputStream(uri) ?: throw IOException("Nie można zapisać pliku")
                    os.buffered().use { Backup.export(core, it, pw, withMedia, pl.szept.BuildConfig.VERSION_NAME) { p -> progress = p } }
                }
                last = System.currentTimeMillis()
                core.prefs.edit().putLong("last_backup", last).apply()
                pass = ""; pass2 = ""
                toast(ctx, "Kopia zapisana. Zapamiętaj hasło — bez niego nie da się jej odczytać.")
            } catch (e: Exception) {
                toast(ctx, "Nie udało się zapisać kopii: ${e.message}")
            } finally { progress = null; pw.fill(' ') }
        }
    }
    Scaffold(topBar = { BackBar("Kopia zapasowa", nav) }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(16.dp)) {
            Text(
                "Kopia zawiera Twoje konto (klucz), kontakty, grupy, całą historię wiadomości i — jeśli chcesz — zdjęcia, filmy i pliki. " +
                    "Jest zaszyfrowana hasłem, które teraz ustawisz. Zapisz ją np. w Pobranych albo na dysku w chmurze, " +
                    "a na nowym telefonie wybierz „Przywróć z kopii zapasowej” na ekranie powitalnym.",
                style = MaterialTheme.typography.bodyMedium,
            )
            if (last > 0) Text("Ostatnia kopia: " + formatWhen(last), Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(12.dp))
            PasswordField(pass, "Hasło do kopii (min. 8 znaków)") { pass = it }
            Spacer(Modifier.height(6.dp))
            PasswordField(pass2, "Powtórz hasło") { pass2 = it }
            if (pass2.isNotEmpty() && pass != pass2) Text("Hasła się różnią", color = MaterialTheme.colorScheme.error)
            Row(Modifier.fillMaxWidth().clickable { withMedia = !withMedia }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(withMedia, { withMedia = it })
                Column {
                    Text("Dołącz zdjęcia, filmy i pliki")
                    Text("Rozmiar kopii: ok. " + formatSize(if (withMedia) sizes.first else sizes.second), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            progress?.let {
                LinearProgressIndicator(progress = { it / 100f }, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp))
                Text("Zapisywanie… $it%")
            }
            Button(
                onClick = { create.launch("Szept-kopia-" + SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date()) + ".szept") },
                enabled = progress == null && pass.length >= 8 && pass == pass2, modifier = Modifier.fillMaxWidth(),
            ) { Text("Utwórz kopię i zapisz…") }
            Spacer(Modifier.height(16.dp))
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text("Ważne", fontWeight = FontWeight.Bold)
                    Text(
                        "• Bez hasła kopii nie da się odczytać — nikt (także twórcy aplikacji) go nie odzyska.\n" +
                            "• Kopia zawiera klucz konta: chroń plik tak jak hasło.\n" +
                            "• Wiadomości z ostatnich 21 dni dotrą na nowy telefon także bez kopii — kopia przenosi całą starszą historię.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

/** Przywracanie kopii na ekranie powitalnym. [onDone] — konto przywrócone. */
@Composable
fun RestoreBackupDialog(uri: Uri, onDismiss: () -> Unit, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var pass by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0) }
    var err by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Przywróć kopię zapasową") },
        text = {
            Column {
                Text("Wpisz hasło, które ustawiłeś(-aś) przy tworzeniu kopii.", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
                PasswordField(pass, "Hasło kopii") { pass = it; err = null }
                err?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 6.dp)) }
                if (busy) {
                    LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 10.dp))
                    Text("Przywracanie… (to może chwilę potrwać)", style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy && pass.isNotEmpty(), onClick = {
                busy = true
                val pw = pass.toCharArray()
                scope.launch {
                    try {
                        val info = withContext(Dispatchers.IO) {
                            val ins = ctx.contentResolver.openInputStream(uri) ?: throw IOException("Nie można otworzyć pliku")
                            ins.buffered().use { Backup.restore(App.core, it, pw) { p -> progress = p } }
                        }
                        (ctx.applicationContext as App).resetCore()
                        toast(ctx, "Przywrócono konto ${info.name}")
                        onDone()
                    } catch (e: Backup.BadPassword) {
                        err = "Błędne hasło albo to nie jest kopia Szepta"
                    } catch (e: Exception) {
                        err = "Nie udało się: ${e.message}"
                        (ctx.applicationContext as App).resetCore()
                    } finally { busy = false; pw.fill(' ') }
                }
            }) { Text("Przywróć") }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("Anuluj") } },
    )
}
