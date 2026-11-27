package pl.szept.ui

import pl.szept.tr
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import pl.szept.App
import pl.szept.core.SafetyCode

/**
 * Kod bezpieczeństwa z osobą [pk]: 60 cyfr, takich samych u was obojga. Porównajcie je na żywo
 * albo zeskanujcie wzajemnie kody QR — wtedy macie pewność, że nikt nie podmienił klucza.
 */
@Composable
fun SafetyCard(pk: String) {
    val core = App.core
    val ctx = LocalContext.current
    var verified by remember(pk) { mutableStateOf(core.isKeyVerified(pk)) }
    var showQr by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<SafetyCode.Result?>(null) }
    val groups = remember(pk) { SafetyCode.groups(core.myPub, pk) }
    val scan = rememberQrScanner { text ->
        val r = SafetyCode.check(text, core.myPub, pk)
        result = r
        if (r == SafetyCode.Result.MATCH) { core.setKeyVerified(pk, true); verified = true }
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Text(tr("Stały ID: {0}", pl.szept.data.shortId(pk)), fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(if (verified) Icons.Default.VerifiedUser else Icons.Default.Shield, null,
                    tint = if (verified) OkGreen else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(6.dp))
                Text(if (verified) tr("Kod bezpieczeństwa — zweryfikowany ✓") else tr("Kod bezpieczeństwa"), fontWeight = FontWeight.Medium,
                    color = if (verified) OkGreen else MaterialTheme.colorScheme.onSurface)
            }
            Spacer(Modifier.height(6.dp))
            groups.chunked(4).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    row.forEach { Text(it, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.titleMedium) }
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                tr("U tej osoby w Szepcie widać te same cyfry. Porównajcie je na żywo albo zeskanujcie nawzajem kody QR — ") +
                    tr("zgodny kod oznacza, że nikt nie podszywa się pod żadne z was."),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { showQr = !showQr }, Modifier.weight(1f)) {
                    Icon(Icons.Default.QrCode, null); Spacer(Modifier.width(6.dp)); Text(if (showQr) tr("Ukryj") else tr("Mój kod"))
                }
                OutlinedButton(onClick = { try { scan() } catch (e: Exception) { toast(ctx, tr("Nie można otworzyć aparatu")) } }, Modifier.weight(1f)) {
                    Icon(Icons.Default.QrCodeScanner, null); Spacer(Modifier.width(6.dp)); Text(tr("Skanuj"))
                }
            }
            if (showQr) Column(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                QrImage(SafetyCode.qr(core.myPub, pk), 220.dp)
                Text(tr("Niech ta osoba zeskanuje ten kod w Twoim profilu u siebie."), style = MaterialTheme.typography.labelSmall,
                    textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp))
            }
            result?.let { r ->
                Text(
                    when (r) {
                        SafetyCode.Result.MATCH -> tr("✓ Kody się zgadzają — rozmowa jest bezpieczna.")
                        SafetyCode.Result.MISMATCH -> tr("⚠️ Kody się NIE zgadzają. Ktoś mógł podmienić klucz — nie przesyłajcie poufnych rzeczy i dodajcie się ponownie na żywo (kod QR).")
                        SafetyCode.Result.OTHER_PERSON -> tr("To kod bezpieczeństwa innej osoby. Zeskanuj kod z profilu tej osoby, z którą rozmawiasz.")
                        SafetyCode.Result.INVALID -> tr("To nie jest kod bezpieczeństwa Szepta.")
                    },
                    color = if (r == SafetyCode.Result.MATCH) OkGreen else MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp),
                )
            }
            TextButton(onClick = { core.setKeyVerified(pk, !verified); verified = !verified; result = null }) {
                Text(if (verified) tr("Cofnij oznaczenie „zweryfikowany”") else tr("Porównaliśmy na żywo — oznacz jako zweryfikowany"))
            }
        }
    }
}
