@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package pl.szept.ui.games

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import org.json.JSONObject
import pl.szept.App
import pl.szept.core.GameCatalog
import pl.szept.core.Games
import pl.szept.data.Message

private val BUBBLE_W = 264.dp

@Composable
private fun Medal(i: Int) = Text(listOf("🥇", "🥈", "🥉").getOrElse(i) { "${i + 1}." }, Modifier.width(30.dp))

@Composable
private fun RankRow(i: Int, pk: String, value: String, bold: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Medal(i)
        Text(whoName(pk), Modifier.weight(1f), maxLines = 1, fontWeight = if (bold || pk == App.core.myPub) FontWeight.Bold else FontWeight.Normal)
        Text(value, fontWeight = FontWeight.Medium)
    }
}

// ============================ wyzwanie (wynik z gry na pełnym ekranie) ============================

@Composable
fun ChallengeBubble(m: Message, info: GameCatalog.Info) {
    val ctx = LocalContext.current
    val me = App.core.myPub
    val game = remember(m.ex.game) { gameJson(m) }
    val moves = rememberMoves(m.id)
    val ranking = remember(moves) { Games.challengeRanking(info.score, moves) }
    val tries = remember(moves) { Games.challengeResults(moves) }
    var totals by remember { mutableStateOf(false) }
    val goal = when {
        info.score.kind == GameCatalog.Score.Kind.TIME && !info.score.higher -> "kto szybciej?"
        info.score.higher -> "kto więcej?"
        else -> "kto mniej?"
    }
    Column(Modifier.width(BUBBLE_W)) {
        GameTitle(info, "Wyzwanie: $goal")
        Spacer(Modifier.height(6.dp))
        if (ranking.isEmpty()) Text("Nikt jeszcze nie zagrał — bądź pierwszy!", style = MaterialTheme.typography.bodyMedium)
        ranking.take(5).forEachIndexed { i, (pk, v) -> RankRow(i, pk, info.score.fmt(v)) }
        if (ranking.size > 5) Text("…i jeszcze ${ranking.size - 5}", style = MaterialTheme.typography.labelSmall)
        ranking.indexOfFirst { it.first == me }.takeIf { it >= 5 }?.let { i -> RankRow(i, me, info.score.fmt(ranking[i].second)) }
        tries[me]?.let { Text("Twoje próby: ${it.size}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        BubbleActions {
            Button(onClick = { Play.start(ctx, info.id, game.optLong("s"), m.id, m.chat) }) {
                Icon(Icons.Default.PlayArrow, null); Spacer(Modifier.width(4.dp)); Text(if (tries[me] == null) "Zagraj" else "Jeszcze raz")
            }
            TotalsButton { totals = true }
        }
    }
    if (totals) TotalsDialog(info, m.id, m.chat) { totals = false }
}

// ============================ gry w grupie ============================

@Composable
fun PartyBubble(m: Message, info: GameCatalog.Info, game: JSONObject) {
    var totals by remember { mutableStateOf(false) }
    val showTotals: () -> Unit = { totals = true }
    when (info.id) {
        Games.DICE -> DiceBubble(m, info, game, showTotals)
        Games.FAST -> FastBubble(m, info, showTotals)
        "hangman" -> HangmanBubble(m, info, game, showTotals)
        "guessgroup" -> GuessGroupBubble(m, info, game, showTotals)
        "emoji" -> EmojiBubble(m, info, game, showTotals)
        "quick" -> QuickBubble(m, info, game, showTotals)
        "typerace" -> TypeRaceBubble(m, info, game, showTotals)
        "wyr" -> VoteBubble(m, info, game.optString("a"), game.optString("b"), listOf("a", "b"), null)
        "never" -> VoteBubble(m, info, "Ja tak", "Ja nigdy", listOf("y", "n"), "Nigdy przenigdy " + game.optString("q"))
        "truth" -> TruthBubble(m, info, game)
        "whoof" -> WhoBubble(m, info, game)
        "ball8" -> Ball8Bubble(m, info, game)
        "coin" -> CoinBubble(m, info, game)
        "pick" -> PickBubble(m, info, game)
        "card" -> CardBubble(m, info, game, showTotals)
        "roulette" -> RouletteBubble(m, info, game, showTotals)
        "dicewar" -> DiceWarBubble(m, info, showTotals)
        "bjgroup" -> BjGroupBubble(m, info, game, showTotals)
        else -> Text(m.body)
    }
    if (totals) TotalsDialog(info, m.id, m.chat) { totals = false }
}

@Composable
private fun DiceBubble(m: Message, info: GameCatalog.Info, game: JSONObject, totals: () -> Unit) {
    val ctx = LocalContext.current
    val v = remember(game) { game.optJSONArray("v")?.let { a -> List(a.length()) { a.optInt(it).coerceIn(1, 6) } } ?: emptyList() }
    Column(Modifier.widthIn(min = 200.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        GameTitle(info, "rzuca: ${whoName(m.sender)}")
        Row(Modifier.padding(8.dp).semantics { contentDescription = "Kości: " + v.joinToString(" i ") }, horizontalArrangement = Arrangement.spacedBy(10.dp)) { v.forEach { Die(it, 56.dp) } }
        Text("Razem: ${v.sum()}", style = MaterialTheme.typography.titleMedium)
        BubbleActions {
            TextButton(onClick = { startInChat(ctx, m.chat, Games.DICE) }) { Text("Rzuć ponownie") }
            TotalsButton(totals)
        }
    }
}

/** „Kto szybciej?” — każdy gra raz: czeka na zielone i jak najszybciej dotyka. */
@Composable
private fun FastBubble(m: Message, info: GameCatalog.Info, totals: () -> Unit) {
    val ctx = LocalContext.current
    val core = App.core
    val me = core.myPub
    val moves = rememberMoves(m.id)
    val results = remember(moves) { Games.fast(moves) }
    val mine = results.firstOrNull { it.first == me }
    // 0 = przed startem, 1 = czekaj, 2 = TERAZ, 3 = zapisane
    var phase by remember { mutableIntStateOf(0) }
    var goAt by remember { mutableLongStateOf(0L) }
    LaunchedEffect(phase) {
        if (phase == 1) {
            delay(1500L + Games.secureInt(3600))
            if (phase == 1) { goAt = System.currentTimeMillis(); phase = 2 }
        }
    }
    Column(Modifier.width(BUBBLE_W), horizontalAlignment = Alignment.CenterHorizontally) {
        GameTitle(info)
        if (mine == null && phase != 3) {
            val (bg, label) = when (phase) {
                1 -> Color(0xFFC62828) to "Czekaj na zielone…"
                2 -> Color(0xFF2E7D32) to "TERAZ!"
                else -> MaterialTheme.colorScheme.primary to "Start"
            }
            Box(
                Modifier.fillMaxWidth().padding(vertical = 6.dp).height(64.dp).clip(RoundedCornerShape(14.dp)).background(bg)
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            awaitFirstDown()
                            when (phase) {
                                0 -> phase = 1
                                1 -> { phase = 3; core.gameMove(m.id, "-1") }
                                2 -> { phase = 3; core.gameMove(m.id, (System.currentTimeMillis() - goAt).coerceIn(0, 99_999).toString()) }
                            }
                        }
                    },
                contentAlignment = Alignment.Center,
            ) { Text(label, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 20.sp) }
            if (phase == 0) Text("Dotknij Start, poczekaj na zielone i dotknij jak najszybciej. Jedna próba.", style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center)
        }
        results.forEachIndexed { i, (pk, ms) ->
            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                if (ms < 0) Text("❌", Modifier.width(30.dp)) else Medal(i)
                Text(whoName(pk), Modifier.weight(1f))
                Text(if (ms < 0) "falstart" else "$ms ms", fontWeight = FontWeight.Medium)
            }
        }
        BubbleActions {
            if (results.isNotEmpty()) TextButton(onClick = { startInChat(ctx, m.chat, Games.FAST) }) { Text("Nowa runda") }
            TotalsButton(totals)
        }
    }
}

/** Litery do zgadywania w rozmowie (mniejsze niż klawiatura na pełnym ekranie). */
@Composable
private fun LetterGrid(used: Set<Char>, good: Set<Char>, enabled: Boolean, onPick: (Char) -> Unit) {
    val letters = "AĄBCĆDEĘFGHIJKLŁMNŃOÓPRSŚTUWYZŹŻ"
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        letters.forEach { ch ->
            val u = ch in used
            Box(Modifier.size(28.dp).clip(RoundedCornerShape(5.dp))
                .background(when { u && ch in good -> Color(0xFF6AAA64); u -> Color(0xFF9E9E9E); else -> MaterialTheme.colorScheme.surfaceVariant })
                .clickable(enabled = enabled && !u) { onPick(ch) }, contentAlignment = Alignment.Center) {
                Text(ch.toString(), fontSize = 13.sp, fontWeight = FontWeight.Bold, color = if (u) Color.White else MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}

@Composable
private fun HangmanBubble(m: Message, info: GameCatalog.Info, game: JSONObject, totals: () -> Unit) {
    val ctx = LocalContext.current
    val core = App.core
    val me = core.myPub
    val moves = rememberMoves(m.id)
    val h = remember(moves) { Games.hangman(m.sender, game.optString("w"), moves) }
    val author = m.sender == me
    var guess by remember { mutableStateOf("") }
    val canPlay = !author && !h.over && core.canWrite(m.chat) == null
    Column(Modifier.width(BUBBLE_W), horizontalAlignment = Alignment.CenterHorizontally) {
        GameTitle(info, "hasło od: ${whoName(m.sender)}")
        game.optString("h").takeIf { it.isNotEmpty() }?.let { Text("Podpowiedź: $it", style = MaterialTheme.typography.labelMedium) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            HangmanDrawing(h.wrong, Games.HANGMAN_LIVES, Modifier.size(70.dp))
            Spacer(Modifier.width(8.dp))
            if (author || h.over) {
                // autor widzi całe hasło: odgadnięte litery wyraźnie, reszta wyszarzona
                val dim = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
                Text(androidx.compose.ui.text.buildAnnotatedString {
                    h.word.forEachIndexed { i, ch ->
                        if (i > 0) append(' ')
                        withStyle(androidx.compose.ui.text.SpanStyle(color = if (ch in h.guessed || h.over) Color.Unspecified else dim)) { append(ch) }
                    }
                }, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            } else Text(h.masked, fontSize = 20.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        }
        Text("Błędy: ${h.wrong}/${Games.HANGMAN_LIVES}", style = MaterialTheme.typography.labelMedium)
        when {
            h.solved -> Text("🏆 Odgadł(a): ${whoName(h.winner)}", fontWeight = FontWeight.Bold)
            h.lost -> Text("Nikt nie odgadł — hasło: ${h.word}", fontWeight = FontWeight.Bold)
            author -> Text("To Twoje hasło — inni zgadują.", style = MaterialTheme.typography.labelMedium)
        }
        if (canPlay) {
            LetterGrid(h.guessed + h.tried.mapNotNull { it.second.singleOrNull() }, h.guessed, true) { ch -> core.gameMove(m.id, "L:$ch") }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(guess, { guess = it.take(20) }, Modifier.weight(1f), singleLine = true, placeholder = { Text("Całe hasło") }, textStyle = MaterialTheme.typography.bodySmall)
                IconButton(onClick = { if (guess.isNotBlank()) { core.gameMove(m.id, "W:" + Games.cleanWord(guess)); guess = "" } }) { Icon(Icons.AutoMirrored.Filled.Send, "Zgadnij hasło") }
            }
        }
        BubbleActions {
            if (h.over) TextButton(onClick = { startInChat(ctx, m.chat, "hangman") }) { Text("Nowe hasło") }
            TotalsButton(totals)
        }
    }
}

@Composable
private fun GuessGroupBubble(m: Message, info: GameCatalog.Info, game: JSONObject, totals: () -> Unit) {
    val ctx = LocalContext.current
    val core = App.core
    val moves = rememberMoves(m.id)
    val g = remember(moves) { Games.guesses(game.optInt("n"), moves) }
    val won = g.lastOrNull()?.takeIf { it.hint == 0 }
    var input by remember { mutableStateOf("") }
    var lo = 1; var hi = 100
    g.forEach { if (it.hint > 0) lo = maxOf(lo, it.n + 1) else if (it.hint < 0) hi = minOf(hi, it.n - 1) }
    Column(Modifier.width(BUBBLE_W)) {
        GameTitle(info, if (won == null) "liczba od 1 do 100 · zakres $lo–$hi" else "zgadnięte w ${g.size} próbach")
        g.takeLast(6).forEach { x ->
            Row(Modifier.fillMaxWidth().padding(vertical = 1.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(whoName(x.who), Modifier.weight(1f), maxLines = 1, style = MaterialTheme.typography.bodySmall)
                Text("${x.n} ", fontWeight = FontWeight.Bold)
                Text(when { x.hint > 0 -> "↑ więcej"; x.hint < 0 -> "↓ mniej"; else -> "✔ trafione!" }, style = MaterialTheme.typography.bodySmall)
            }
        }
        if (won != null) Text("🏆 ${whoName(won.who)} zgadł(a): ${won.n}", fontWeight = FontWeight.Bold)
        else if (core.canWrite(m.chat) == null) Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(input, { input = it.filter { c -> c.isDigit() }.take(3) }, Modifier.weight(1f), singleLine = true, placeholder = { Text("Twój strzał") },
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number))
            IconButton(onClick = { input.toIntOrNull()?.takeIf { it in 1..100 }?.let { core.gameMove(m.id, it.toString()); input = "" } }) { Icon(Icons.AutoMirrored.Filled.Send, "Zgadnij") }
        }
        BubbleActions {
            if (won != null) TextButton(onClick = { startInChat(ctx, m.chat, "guessgroup") }) { Text("Nowa liczba") }
            TotalsButton(totals)
        }
    }
}

@Composable
private fun AnswerField(placeholder: String, onSend: (String) -> Unit) {
    var t by remember { mutableStateOf("") }
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(t, { t = it.take(60) }, Modifier.weight(1f), singleLine = true, placeholder = { Text(placeholder) })
        IconButton(onClick = { if (t.isNotBlank()) { onSend(t.trim()); t = "" } }) { Icon(Icons.AutoMirrored.Filled.Send, "Wyślij odpowiedź") }
    }
}

@Composable
private fun EmojiBubble(m: Message, info: GameCatalog.Info, game: JSONObject, totals: () -> Unit) {
    val ctx = LocalContext.current
    val core = App.core
    val moves = rememberMoves(m.id)
    val answer = game.optString("a")
    val first = remember(moves) { Games.firstCorrect(answer, moves) }
    val mine = moves.filter { it.sender == core.myPub }
    var reveal by remember { mutableStateOf(false) }
    Column(Modifier.width(BUBBLE_W), horizontalAlignment = Alignment.CenterHorizontally) {
        GameTitle(info, "kategoria: ${game.optString("k")}")
        Text(game.optString("e"), fontSize = 44.sp, modifier = Modifier.padding(8.dp))
        when {
            first != null -> { Text("🏆 ${whoName(first.sender)}", fontWeight = FontWeight.Bold); Text("Odpowiedź: $answer") }
            reveal -> Text("Odpowiedź: $answer", fontWeight = FontWeight.Bold)
            else -> {
                Text("Prób: ${moves.size}" + if (mine.isNotEmpty()) " · Twoje: ${mine.joinToString(", ") { it.mv }}" else "", style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center)
                if (core.canWrite(m.chat) == null) AnswerField("Twoja odpowiedź") { core.gameMove(m.id, it) }
            }
        }
        BubbleActions {
            if (first == null && !reveal && moves.isNotEmpty()) TextButton(onClick = { reveal = true }) { Text("Poddaję się") }
            if (first != null || reveal) TextButton(onClick = { startInChat(ctx, m.chat, "emoji") }) { Text("Następna") }
            TotalsButton(totals)
        }
    }
}

@Composable
private fun QuickBubble(m: Message, info: GameCatalog.Info, game: JSONObject, totals: () -> Unit) {
    val ctx = LocalContext.current
    val core = App.core
    val me = core.myPub
    val moves = rememberMoves(m.id)
    val answers = remember(moves) { Games.quickAnswers(moves) }
    val mine = answers.firstOrNull { it.first == me }
    val correct = game.optInt("c")
    val opts = remember(game) { game.optJSONArray("o")?.let { a -> List(a.length()) { a.optString(it).take(80) } } ?: emptyList() }
    var shownAt by remember { mutableLongStateOf(0L) }
    var open by remember { mutableStateOf(false) }
    Column(Modifier.width(BUBBLE_W)) {
        GameTitle(info, "odpowiedzi: ${answers.size}")
        if (mine == null && !open) {
            Text("Pytanie pojawi się po dotknięciu — liczy się czas!", style = MaterialTheme.typography.bodySmall)
            Button(onClick = { open = true; shownAt = System.currentTimeMillis() }, Modifier.fillMaxWidth()) { Text("Pokaż pytanie") }
        } else {
            Text(game.optString("q").take(300), fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 4.dp))
            opts.forEachIndexed { k, o ->
                val bg = when {
                    mine == null -> MaterialTheme.colorScheme.surfaceVariant
                    k == correct -> Color(0xFF6AAA64)
                    k == mine.second -> Color(0xFFE53935)
                    else -> MaterialTheme.colorScheme.surfaceVariant
                }
                Box(Modifier.fillMaxWidth().padding(vertical = 2.dp).clip(RoundedCornerShape(10.dp)).background(bg)
                    .clickable(enabled = mine == null) { core.gameMove(m.id, "$k:${(System.currentTimeMillis() - shownAt).coerceIn(0, 999_999)}") }.padding(10.dp)) {
                    Text(o, color = if (mine != null && (k == correct || k == mine.second)) Color.White else MaterialTheme.colorScheme.onSurface)
                }
            }
        }
        if (mine != null) {
            val good = answers.filter { it.second == correct }.sortedBy { it.third }
            good.take(5).forEachIndexed { i, (pk, _, ms) -> RankRow(i, pk, "${ms} ms") }
            val bad = answers.count { it.second != correct }
            if (bad > 0) Text("Błędne odpowiedzi: $bad", style = MaterialTheme.typography.labelSmall)
        }
        BubbleActions {
            if (mine != null) TextButton(onClick = { startInChat(ctx, m.chat, "quick") }) { Text("Następne pytanie") }
            TotalsButton(totals)
        }
    }
}

@Composable
private fun TypeRaceBubble(m: Message, info: GameCatalog.Info, game: JSONObject, totals: () -> Unit) {
    val ctx = LocalContext.current
    val core = App.core
    val me = core.myPub
    val moves = rememberMoves(m.id)
    val results = remember(moves) { Games.firstMoves(moves).mapNotNull { mv -> mv.mv.toIntOrNull()?.let { mv.sender to it } }.sortedBy { it.second } }
    val mine = results.firstOrNull { it.first == me }
    val word = game.optString("w").take(40)
    var started by remember { mutableStateOf(false) }
    var startAt by remember { mutableLongStateOf(0L) }
    var text by remember { mutableStateOf("") }
    Column(Modifier.width(BUBBLE_W)) {
        GameTitle(info, "wyniki: ${results.size}")
        if (mine == null) {
            if (!started) Button(onClick = { started = true; startAt = System.currentTimeMillis() }, Modifier.fillMaxWidth()) { Text("Start — pokaż słowo") }
            else {
                Text(word, fontSize = 24.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 6.dp))
                OutlinedTextField(text, { v ->
                    text = v.take(60)
                    if (text.trim().equals(word, ignoreCase = true)) core.gameMove(m.id, (System.currentTimeMillis() - startAt).coerceIn(0, 999_999).toString())
                }, Modifier.fillMaxWidth(), singleLine = true, placeholder = { Text("Przepisz słowo") })
            }
        } else Text("Słowo: $word", fontWeight = FontWeight.Medium)
        results.take(6).forEachIndexed { i, (pk, ms) -> RankRow(i, pk, GameCatalog.fmtTime(ms.toDouble())) }
        BubbleActions {
            if (mine != null) TextButton(onClick = { startInChat(ctx, m.chat, "typerace") }) { Text("Następne słowo") }
            TotalsButton(totals)
        }
    }
}

@Composable
private fun VoteBubble(m: Message, info: GameCatalog.Info, a: String, b: String, codes: List<String>, question: String?) {
    val ctx = LocalContext.current
    val core = App.core
    val me = core.myPub
    val moves = rememberMoves(m.id)
    val votes = remember(moves) { Games.lastMoves(moves).mapValues { it.value.mv }.filterValues { it in codes } }
    val mine = votes[me]
    Column(Modifier.width(BUBBLE_W)) {
        GameTitle(info, "głosy: ${votes.size}")
        question?.let { Text(it, fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 4.dp)) }
        listOf(a to codes[0], b to codes[1]).forEach { (label, code) ->
            val who = votes.filterValues { it == code }.keys
            val frac = if (votes.isEmpty()) 0f else who.size.toFloat() / votes.size
            Box(Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(RoundedCornerShape(12.dp))
                .border(2.dp, if (mine == code) MaterialTheme.colorScheme.primary else Color.Transparent, RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .clickable { core.gameMove(m.id, code) }) {
                if (mine != null) Box(Modifier.matchParentSize()) {
                    Box(Modifier.fillMaxHeight().fillMaxWidth(frac).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)))
                }
                Column(Modifier.padding(10.dp)) {
                    Row { Text(label.take(80), Modifier.weight(1f), fontWeight = FontWeight.Medium); if (mine != null) Text("${who.size}") }
                    if (mine != null && who.isNotEmpty()) Text(who.joinToString(", ") { whoName(it) }, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        if (mine == null) Text("Zagłosuj, aby zobaczyć wyniki", style = MaterialTheme.typography.labelSmall)
        BubbleActions { TextButton(onClick = { startInChat(ctx, m.chat, info.id) }) { Text("Następne") } }
    }
}

@Composable
private fun TruthBubble(m: Message, info: GameCatalog.Info, game: JSONObject) {
    val ctx = LocalContext.current
    val core = App.core
    val who = game.optString("who")
    val moves = rememberMoves(m.id)
    val done = moves.any { it.sender == who && it.mv == "done" }
    val dare = game.optString("t") == "w"
    Column(Modifier.width(BUBBLE_W)) {
        GameTitle(info, "wylosowano: ${whoName(who)}")
        Box(Modifier.fillMaxWidth().padding(vertical = 6.dp).clip(RoundedCornerShape(12.dp))
            .background(if (dare) Color(0xFFFFE0B2) else Color(0xFFBBDEFB)).padding(12.dp)) {
            Column {
                Text(if (dare) "WYZWANIE" else "PRAWDA", fontWeight = FontWeight.Black, color = if (dare) Color(0xFFE65100) else Color(0xFF0D47A1))
                Text(game.optString("q").take(300), color = Color.Black)
            }
        }
        if (done) Text("✔ Zrobione!", fontWeight = FontWeight.Bold, color = Color(0xFF2E7D32))
        else if (who == core.myPub) Button(onClick = { core.gameMove(m.id, "done") }, Modifier.fillMaxWidth()) { Text("Zrobione") }
        BubbleActions {
            TextButton(onClick = { startInChat(ctx, m.chat, "truth", JSONObject().put("t", "p")) }) { Text("Prawda") }
            TextButton(onClick = { startInChat(ctx, m.chat, "truth", JSONObject().put("t", "w")) }) { Text("Wyzwanie") }
        }
    }
}

@Composable
private fun WhoBubble(m: Message, info: GameCatalog.Info, game: JSONObject) {
    val ctx = LocalContext.current
    val core = App.core
    val me = core.myPub
    val moves = rememberMoves(m.id)
    val members = remember(m.chat) { (core.participants(m.chat) + me).distinct() }
    val votes = remember(moves) { Games.lastMoves(moves).mapValues { it.value.mv }.filterValues { it in members } }
    val mine = votes[me]
    val counts = votes.values.groupingBy { it }.eachCount()
    Column(Modifier.width(BUBBLE_W)) {
        GameTitle(info, "głosy: ${votes.size}")
        Text("Kto z nas " + game.optString("q").take(120), fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 4.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            members.sortedByDescending { counts[it] ?: 0 }.forEach { pk ->
                FilterChip(mine == pk, { core.gameMove(m.id, pk) }, { Text(whoName(pk) + if (mine != null) " · ${counts[pk] ?: 0}" else "") })
            }
        }
        if (mine == null) Text("Zagłosuj, aby zobaczyć wyniki", style = MaterialTheme.typography.labelSmall)
        BubbleActions { TextButton(onClick = { startInChat(ctx, m.chat, "whoof") }) { Text("Następne pytanie") } }
    }
}

@Composable
private fun Ball8Bubble(m: Message, info: GameCatalog.Info, game: JSONObject) {
    val ctx = LocalContext.current
    Column(Modifier.width(BUBBLE_W), horizontalAlignment = Alignment.CenterHorizontally) {
        GameTitle(info, "pyta: ${whoName(m.sender)}")
        game.optString("q").takeIf { it.isNotEmpty() }?.let { Text("„$it”", fontWeight = FontWeight.Medium, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp)) }
        Box(Modifier.padding(10.dp).size(150.dp).clip(CircleShape).background(Color(0xFF111111)), contentAlignment = Alignment.Center) {
            Box(Modifier.size(96.dp).clip(CircleShape).background(Color(0xFF1A237E)), contentAlignment = Alignment.Center) {
                Text(game.optString("a").take(60), color = Color.White, textAlign = TextAlign.Center, fontSize = 12.sp, modifier = Modifier.padding(6.dp))
            }
        }
        BubbleActions { TextButton(onClick = { startInChat(ctx, m.chat, "ball8") }) { Text("Zapytaj jeszcze raz") } }
    }
}

@Composable
private fun CoinBubble(m: Message, info: GameCatalog.Info, game: JSONObject) {
    val ctx = LocalContext.current
    val heads = game.optInt("v") == 0
    Column(Modifier.widthIn(min = 200.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        GameTitle(info, "rzuca: ${whoName(m.sender)}")
        Box(Modifier.padding(10.dp).size(110.dp).clip(CircleShape).background(Color(0xFFFFC107)).border(6.dp, Color(0xFFFFA000), CircleShape), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(if (heads) Icons.Default.EmojiEvents else Icons.Default.Looks5, null, tint = Color(0xFF6D4C41), modifier = Modifier.size(40.dp))
                Text(if (heads) "ORZEŁ" else "RESZKA", fontWeight = FontWeight.Black, color = Color(0xFF6D4C41))
            }
        }
        BubbleActions { TextButton(onClick = { startInChat(ctx, m.chat, "coin") }) { Text("Rzuć ponownie") } }
    }
}

@Composable
private fun PickBubble(m: Message, info: GameCatalog.Info, game: JSONObject) {
    val ctx = LocalContext.current
    Column(Modifier.widthIn(min = 200.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        GameTitle(info, "losuje: ${whoName(m.sender)}")
        if (game.has("who")) {
            Text("Wylosowana osoba:", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 6.dp))
            Text(whoName(game.optString("who")), fontSize = 26.sp, fontWeight = FontWeight.Bold)
        } else {
            Text("Liczba od 1 do ${game.optInt("max")}:", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 6.dp))
            Text("${game.optInt("n")}", fontSize = 40.sp, fontWeight = FontWeight.Bold)
        }
        BubbleActions {
            TextButton(onClick = { startInChat(ctx, m.chat, "pick", if (game.has("max")) JSONObject().put("max", game.optInt("max")) else null) }) { Text("Losuj ponownie") }
        }
    }
}

@Composable
private fun CardBubble(m: Message, info: GameCatalog.Info, game: JSONObject, totals: () -> Unit) {
    val ctx = LocalContext.current
    val core = App.core
    val moves = rememberMoves(m.id)
    val draws = remember(moves) { Games.cardDraws(game.optLong("s"), moves) }
    val mine = draws.firstOrNull { it.first == core.myPub }
    val best = draws.maxOfOrNull { Games.cardRank(it.second) }
    Column(Modifier.width(BUBBLE_W)) {
        GameTitle(info, "ciągnęło: ${draws.size}")
        draws.sortedByDescending { Games.cardRank(it.second) }.forEach { (pk, c) ->
            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                PlayingCard(c, 30.dp); Spacer(Modifier.width(8.dp))
                Text(whoName(pk), Modifier.weight(1f), fontWeight = if (Games.cardRank(c) == best) FontWeight.Bold else FontWeight.Normal)
                if (Games.cardRank(c) == best) Icon(Icons.Default.EmojiEvents, null, tint = Color(0xFFFFB300))
            }
        }
        BubbleActions {
            if (mine == null && core.canWrite(m.chat) == null) Button(onClick = { core.gameMove(m.id, "d") }) { Text("Ciągnij kartę") }
            else TextButton(onClick = { startInChat(ctx, m.chat, "card") }) { Text("Nowa runda") }
            TotalsButton(totals)
        }
    }
}

@Composable
private fun RouletteBubble(m: Message, info: GameCatalog.Info, game: JSONObject, totals: () -> Unit) {
    val ctx = LocalContext.current
    val core = App.core
    val me = core.myPub
    val moves = rememberMoves(m.id)
    val (bets, spun) = remember(moves) { Games.roulette(m.sender, moves) }
    val n = Games.rouletteNumber(game.optLong("s"))
    val labels = mapOf("r" to "Czerwone", "b" to "Czarne", "e" to "Parzyste", "o" to "Nieparzyste")
    Column(Modifier.width(BUBBLE_W)) {
        GameTitle(info, "zakłady: ${bets.size}")
        if (spun) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 6.dp)) {
                Box(Modifier.size(56.dp).clip(CircleShape).background(when { n == 0 -> Color(0xFF2E7D32); n in Games.ROULETTE_RED -> Color(0xFFD32F2F); else -> Color(0xFF212121) }),
                    contentAlignment = Alignment.Center) { Text("$n", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold) }
                Spacer(Modifier.width(10.dp))
                Text("Wygrywają: " + bets.filterValues { Games.rouletteWins(it, n) }.keys.joinToString(", ") { whoName(it) }.ifEmpty { "nikt" })
            }
        } else {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                labels.forEach { (code, label) -> FilterChip(bets[me] == code, { core.gameMove(m.id, "B:$code") }, { Text(label) }) }
            }
        }
        bets.forEach { (pk, b) ->
            Text("${whoName(pk)}: ${labels[b] ?: b}" + if (spun) (if (Games.rouletteWins(b, n)) " ✔" else " ✘") else "", style = MaterialTheme.typography.bodySmall)
        }
        BubbleActions {
            if (!spun && m.sender == me) Button(onClick = { core.gameMove(m.id, "spin") }, enabled = bets.isNotEmpty()) { Text("Kręć!") }
            if (!spun && m.sender != me) Text("Kołem kręci ${whoName(m.sender)}", style = MaterialTheme.typography.labelSmall)
            if (spun) TextButton(onClick = { startInChat(ctx, m.chat, "roulette") }) { Text("Nowa runda") }
            TotalsButton(totals)
        }
    }
}

@Composable
private fun DiceWarBubble(m: Message, info: GameCatalog.Info, totals: () -> Unit) {
    val ctx = LocalContext.current
    val core = App.core
    val moves = rememberMoves(m.id)
    val rolls = remember(moves) { Games.diceWar(moves).sortedByDescending { it.second.sum() } }
    val mine = rolls.firstOrNull { it.first == core.myPub }
    val best = rolls.maxOfOrNull { it.second.sum() }
    Column(Modifier.width(BUBBLE_W)) {
        GameTitle(info, "rzuciło: ${rolls.size}")
        rolls.forEach { (pk, v) ->
            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                v.forEach { Die(it, 22.dp); Spacer(Modifier.width(2.dp)) }
                Spacer(Modifier.width(6.dp))
                Text(whoName(pk), Modifier.weight(1f), fontWeight = if (v.sum() == best) FontWeight.Bold else FontWeight.Normal, maxLines = 1)
                Text("${v.sum()}", fontWeight = FontWeight.Bold)
            }
        }
        BubbleActions {
            if (mine == null && core.canWrite(m.chat) == null) Button(onClick = { core.gameMove(m.id, Games.rollDice(3).joinToString(",")) }) { Icon(Icons.Default.Casino, null); Text(" Rzuć") }
            else TextButton(onClick = { startInChat(ctx, m.chat, "dicewar") }) { Text("Nowa runda") }
            TotalsButton(totals)
        }
    }
}

@Composable
private fun BjGroupBubble(m: Message, info: GameCatalog.Info, game: JSONObject, totals: () -> Unit) {
    val ctx = LocalContext.current
    val core = App.core
    val me = core.myPub
    val seed = game.optLong("s")
    val moves = rememberMoves(m.id)
    val hands = remember(moves) { Games.bjHands(seed, moves) }
    val mine = hands[me]
    Column(Modifier.width(BUBBLE_W)) {
        GameTitle(info, "graczy: ${hands.size}")
        if (mine == null) {
            Text("Dostaniesz dwie karty i dobierasz, ile chcesz. Kto bliżej 21, ten wygrywa.", style = MaterialTheme.typography.bodySmall)
            if (core.canWrite(m.chat) == null) Button(onClick = { core.gameMove(m.id, "start") }, Modifier.fillMaxWidth()) { Text("Dołącz i weź karty") }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                mine.cards.forEach { PlayingCard(it, 36.dp) }
                Spacer(Modifier.width(6.dp))
                Text("= ${mine.value}" + if (mine.bust) " (fura!)" else "", fontWeight = FontWeight.Bold)
            }
            if (!mine.done) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { core.gameMove(m.id, "h") }) { Text("Dobierz") }
                OutlinedButton(onClick = { core.gameMove(m.id, "s") }) { Text("Pas") }
            }
        }
        val finished = hands.filter { it.value.done }
        val best = finished.filterValues { !it.bust }.maxOfOrNull { it.value.value }
        finished.entries.sortedByDescending { if (it.value.bust) -1 else it.value.value }.forEach { (pk, h) ->
            Row(Modifier.fillMaxWidth()) {
                Text(whoName(pk), Modifier.weight(1f), fontWeight = if (!h.bust && h.value == best) FontWeight.Bold else FontWeight.Normal)
                Text(if (h.bust) "fura (${h.value})" else "${h.value}" + if (h.value == best) " 🏆" else "")
            }
        }
        val waiting = hands.count { !it.value.done }
        if (waiting > 0) Text("Jeszcze gra: $waiting", style = MaterialTheme.typography.labelSmall)
        BubbleActions {
            if (mine?.done == true) TextButton(onClick = { startInChat(ctx, m.chat, "bjgroup") }) { Text("Nowe rozdanie") }
            TotalsButton(totals)
        }
    }
}
