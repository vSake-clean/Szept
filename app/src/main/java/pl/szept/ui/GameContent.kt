package pl.szept.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import pl.szept.App
import pl.szept.core.Games
import pl.szept.data.Message

/** Wiadomość z grą: kości, kółko i krzyżyk albo „kto szybciej”. */
@Composable
fun GameContent(m: Message) {
    val game = remember(m.ex.game) { try { JSONObject(m.ex.game ?: "{}") } catch (e: Exception) { JSONObject() } }
    when (game.optString("g")) {
        Games.DICE -> DiceGame(m, game)
        Games.TTT -> TttGame(m)
        Games.FAST -> FastGame(m)
        else -> RichText(m.body)
    }
}

private fun startNew(ctx: android.content.Context, chat: String, type: String) {
    App.work.launch { try { App.core.startGame(chat, type) } catch (e: Exception) { toast(ctx, sendError(e)) } }
}

@Composable
private fun DiceGame(m: Message, game: JSONObject) {
    val ctx = LocalContext.current
    val v = remember(game) { game.optJSONArray("v")?.let { a -> List(a.length()) { a.optInt(it) } } ?: emptyList() }
    Column(Modifier.widthIn(min = 180.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("🎲 Rzut kośćmi", fontWeight = FontWeight.Bold)
        Text(v.joinToString(" ") { Games.DICE_FACES.getOrElse(it - 1) { "?" } }, fontSize = 52.sp,
            modifier = Modifier.semantics { contentDescription = "Kości: " + v.joinToString(" i ") })
        Text("Razem: ${v.sum()}", style = MaterialTheme.typography.titleMedium)
        TextButton(onClick = { startNew(ctx, m.chat, Games.DICE) }) { Text("Rzuć ponownie") }
    }
}

@Composable
private fun TttGame(m: Message) {
    val ctx = LocalContext.current
    val core = App.core
    val me = core.myPub
    val moves by rememberDb(m.id) { core.db.gameMoves(m.id) }
    val s = remember(moves) { Games.ttt(m.sender, moves ?: emptyList()) }
    fun who(pk: String?) = when (pk) { null -> "?"; me -> "Ty"; else -> core.db.nameOf(pk) }
    Column(Modifier.widthIn(min = 220.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("❌⭕ Kółko i krzyżyk", fontWeight = FontWeight.Bold)
        Text("❌ ${who(s.x)}  vs  ⭕ ${s.o?.let(::who) ?: "czeka na gracza"}", style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.height(6.dp))
        val lineColor = MaterialTheme.colorScheme.primary
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            for (r in 0..2) Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (c in 0..2) {
                    val i = r * 3 + c
                    val mark = s.board[i]
                    val can = Games.tttCanMove(s, me, i)
                    Box(
                        Modifier.size(56.dp).clip(RoundedCornerShape(10.dp))
                            .background(if (i in s.line) lineColor.copy(alpha = 0.3f) else MaterialTheme.colorScheme.surfaceVariant)
                            .border(1.dp, if (can) lineColor.copy(alpha = 0.6f) else Color.Transparent, RoundedCornerShape(10.dp))
                            .clickable(enabled = can) { core.gameMove(m.id, i.toString()) }
                            .semantics { contentDescription = "Pole ${i + 1}" + (mark?.let { ": $it" } ?: "") },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(when (mark) { 'X' -> "❌"; 'O' -> "⭕"; else -> "" }, fontSize = 26.sp)
                    }
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            when {
                s.winner != null -> "🏆 Wygrywa: ${who(s.winner)}"
                s.draw -> "🤝 Remis"
                s.next == me -> "Twój ruch"
                s.next == null && me != s.x -> "Twój ruch (grasz ⭕)"
                else -> "Ruch: ${who(s.next)}"
            },
            fontWeight = FontWeight.Medium, textAlign = TextAlign.Center,
        )
        if (s.over) TextButton(onClick = { startNew(ctx, m.chat, Games.TTT) }) { Text("Rewanż") }
    }
}

/** „Kto szybciej?” — każdy gra raz: czeka na zielone i jak najszybciej dotyka. */
@Composable
private fun FastGame(m: Message) {
    val ctx = LocalContext.current
    val core = App.core
    val me = core.myPub
    val moves by rememberDb(m.id) { core.db.gameMoves(m.id) }
    val results = remember(moves) { Games.fast(moves ?: emptyList()) }
    val mine = results.firstOrNull { it.first == me }
    // 0 = przed startem, 1 = czekaj, 2 = TERAZ, 3 = zapisane
    var phase by remember { mutableIntStateOf(0) }
    var goAt by remember { mutableLongStateOf(0L) }
    LaunchedEffect(phase) {
        if (phase == 1) {
            delay(1500L + (pl.szept.crypto.Crypto.randomBytes(1)[0].toInt() and 0xff) * 14L)
            if (phase == 1) { goAt = System.currentTimeMillis(); phase = 2 }
        }
    }
    Column(Modifier.widthIn(min = 220.dp, max = 280.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("⚡ Kto szybciej?", fontWeight = FontWeight.Bold)
        if (mine == null && phase != 3) {
            val (bg, label) = when (phase) {
                1 -> Color(0xFFC62828) to "Czekaj na zielone…"
                2 -> Color(0xFF2E7D32) to "TERAZ!"
                else -> MaterialTheme.colorScheme.primary to "Start"
            }
            Box(
                Modifier.fillMaxWidth().padding(vertical = 6.dp).height(64.dp).clip(RoundedCornerShape(14.dp)).background(bg)
                    .clickable {
                        when (phase) {
                            0 -> phase = 1
                            1 -> { phase = 3; core.gameMove(m.id, "-1") } // falstart
                            2 -> { phase = 3; core.gameMove(m.id, (System.currentTimeMillis() - goAt).coerceIn(0, 99_999).toString()) }
                        }
                    },
                contentAlignment = Alignment.Center,
            ) { Text(label, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 20.sp) }
            Text(if (phase == 0) "Dotknij Start, poczekaj na zielone i dotknij jak najszybciej. Jedna próba." else "",
                style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center)
        }
        results.forEachIndexed { i, (pk, ms) ->
            val medal = if (ms < 0) "❌" else listOf("🥇", "🥈", "🥉").getOrElse(i) { "•" }
            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                Text("$medal ${if (pk == me) "Ty" else core.db.nameOf(pk)}", Modifier.weight(1f))
                Text(if (ms < 0) "falstart" else "$ms ms", fontWeight = FontWeight.Medium)
            }
        }
        if (results.isNotEmpty()) TextButton(onClick = { startNew(ctx, m.chat, Games.FAST) }) { Text("Nowa runda") }
    }
}

/** Wybór gry do rozpoczęcia w rozmowie. */
@Composable
fun GamePickerDialog(onDismiss: () -> Unit, onPick: (String) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Zagraj") },
        text = {
            Column {
                listOf(
                    Triple(Games.TTT, "❌⭕ Kółko i krzyżyk", "Dla dwóch osób — kto pierwszy ułoży trzy w rzędzie."),
                    Triple(Games.DICE, "🎲 Rzut kośćmi", "Dwie kości, wynik widzą wszyscy."),
                    Triple(Games.FAST, "⚡ Kto szybciej?", "Test refleksu — każdy próbuje raz, wygrywa najszybszy."),
                ).forEach { (type, title, sub) ->
                    Column(Modifier.fillMaxWidth().clickable { onPick(type) }.padding(vertical = 10.dp)) {
                        Text(title, style = MaterialTheme.typography.titleMedium)
                        Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Anuluj") } },
    )
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
