package pl.szept.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import pl.szept.App
import pl.szept.core.GameCatalog
import pl.szept.data.Message
import pl.szept.ui.games.ChallengeBubble
import pl.szept.ui.games.DuelBubble
import pl.szept.ui.games.PartyBubble
import pl.szept.ui.games.RpsBubble
import pl.szept.ui.games.gameJson

/** Wiadomość z grą: wyzwanie, gra we dwoje albo gra w grupie. */
@Composable
fun GameContent(m: Message) {
    val game = remember(m.ex.game) { gameJson(m) }
    val info = remember(m.ex.game) { GameCatalog.byId(game.optString("g")) }
    when {
        info == null -> Column {
            RichText(m.body)
            Text("Ta gra jest w nowszej wersji Szepta.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        info.id == "rps" -> RpsBubble(m, info)
        info.chat == GameCatalog.Chat.VERSUS -> DuelBubble(m, info)
        info.chat == GameCatalog.Chat.CHALLENGE -> ChallengeBubble(m, info)
        else -> PartyBubble(m, info, game)
    }
}

/** Automatyczne usuwanie starych wiadomości w tej rozmowie (u mnie). */
@Composable
fun AutoCleanRow(chatId: String) {
    val core = App.core
    var days by remember(chatId) { mutableIntStateOf(core.autoCleanDays(chatId)) }
    var open by remember { mutableStateOf(false) }
    androidx.compose.material3.ListItem(
        headlineContent = { Text("Automatyczne usuwanie starych wiadomości") },
        supportingContent = { Text(if (days <= 0) "Wyłączone" else "Wiadomości starsze niż $days dni znikają z tego telefonu") },
        modifier = Modifier.clickable { open = true },
    )
    if (open) AlertDialog(
        onDismissRequest = { open = false },
        title = { Text("Usuwaj stare wiadomości") },
        text = {
            Column {
                Text("Dotyczy tylko tego telefonu — u rozmówcy nic się nie zmienia.", style = MaterialTheme.typography.bodySmall)
                listOf(0 to "Wyłączone", 7 to "Starsze niż 7 dni", 30 to "Starsze niż 30 dni", 90 to "Starsze niż 90 dni").forEach { (d, t) ->
                    Row(Modifier.fillMaxWidth().clickable { days = d; core.setAutoClean(chatId, d); open = false }.padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.RadioButton(days == d, { days = d; core.setAutoClean(chatId, d); open = false })
                        Text(t)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { open = false }) { Text("Zamknij") } },
    )
}
