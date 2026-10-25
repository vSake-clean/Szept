@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package pl.szept.ui.games

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CompareArrows
import androidx.compose.material.icons.automirrored.filled.DirectionsRun
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.automirrored.filled.LiveHelp
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import pl.szept.App
import pl.szept.core.GameCatalog
import pl.szept.core.Games
import pl.szept.data.Db
import pl.szept.data.Message
import pl.szept.ui.PyGameActivity
import pl.szept.ui.rememberDb
import pl.szept.ui.toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ============================ ikony i kolory ============================

/** Ikona gry — w tym samym stylu (Material), co pozostałe pozycje w menu spinacza. */
fun gameIcon(id: String): ImageVector = when (id) {
    "snake" -> Icons.Default.Gesture
    "flappy" -> Icons.Default.Flight
    "tetris" -> Icons.Default.Widgets
    "breakout" -> Icons.Default.ViewComfy
    "pong" -> Icons.Default.SportsTennis
    "dodge" -> Icons.Default.Shield
    "shooter" -> Icons.Default.RocketLaunch
    "runner" -> Icons.AutoMirrored.Filled.DirectionsRun
    "jumper" -> Icons.Default.Height
    "stack" -> Icons.Default.Layers
    "balloons" -> Icons.Default.BubbleChart
    "mole" -> Icons.Default.Pets
    "aim" -> Icons.Default.GpsFixed
    "tapper" -> Icons.Default.TouchApp
    "eggs" -> Icons.Default.Egg
    "2048" -> Icons.Default.Apps
    "mines" -> Icons.Default.Flag
    "sudoku" -> Icons.Default.GridOn
    "fifteen" -> Icons.Default.Dashboard
    "lights" -> Icons.Default.Lightbulb
    "flood" -> Icons.Default.FormatColorFill
    "maze" -> Icons.Default.Route
    "hanoi" -> Icons.AutoMirrored.Filled.Sort
    "peg" -> Icons.Default.Adjust
    "sokoban" -> Icons.Default.Inventory2
    "mastermind" -> Icons.Default.Password
    "match3" -> Icons.Default.Diamond
    "lines" -> Icons.Default.BlurOn
    "memory" -> Icons.Default.Style
    "simon" -> Icons.Default.Palette
    "digits" -> Icons.Default.Pin
    "chimp" -> Icons.Default.Psychology
    "reflex" -> Icons.Default.Speed
    "schulte" -> Icons.Default.TableChart
    "shade" -> Icons.Default.Opacity
    "stopwatch" -> Icons.Default.Timer
    "stroop" -> Icons.Default.ColorLens
    "math" -> Icons.Default.Calculate
    "wordle" -> Icons.Default.Abc
    "hangsolo" -> Icons.Default.TextFields
    "anagram" -> Icons.Default.SortByAlpha
    "typing" -> Icons.Default.Keyboard
    "quiz" -> Icons.Default.Quiz
    "truefalse" -> Icons.Default.ThumbsUpDown
    "blackjack" -> Icons.Default.Paid
    "higherlower" -> Icons.Default.SwapVert
    "slots" -> Icons.Default.Redeem
    "yahtzee" -> Icons.Default.Filter5
    "guessnum" -> Icons.Default.QuestionMark
    "ttt" -> Icons.Default.Grid3x3
    "c4" -> Icons.Default.Circle
    "gomoku" -> Icons.Default.Grid4x4
    "reversi" -> Icons.Default.Contrast
    "checkers" -> Icons.Default.Token
    "chess" -> Icons.Default.Castle
    "dots" -> Icons.Default.ScatterPlot
    "nim" -> Icons.Default.LocalFireDepartment
    "mancala" -> Icons.Default.Hive
    "morris" -> Icons.Default.Hub
    "battleship" -> Icons.Default.Sailing
    "rps" -> Icons.Default.BackHand
    "dice" -> Icons.Default.Casino
    "fast" -> Icons.Default.FlashOn
    "hangman" -> Icons.Default.Spellcheck
    "guessgroup" -> Icons.Default.QuestionAnswer
    "emoji" -> Icons.Default.EmojiEmotions
    "quick" -> Icons.AutoMirrored.Filled.LiveHelp
    "typerace" -> Icons.Default.KeyboardAlt
    "wyr" -> Icons.AutoMirrored.Filled.CompareArrows
    "never" -> Icons.Default.Mood
    "truth" -> Icons.Default.Whatshot
    "whoof" -> Icons.Default.HowToVote
    "ball8" -> Icons.Default.AutoAwesome
    "coin" -> Icons.Default.Toll
    "pick" -> Icons.Default.Shuffle
    "card" -> Icons.Default.LocalActivity
    "roulette" -> Icons.Default.Album
    "dicewar" -> Icons.Default.Looks6
    "bjgroup" -> Icons.Default.Groups
    "py:wheelie" -> Icons.Default.TwoWheeler
    else -> if (id.startsWith("py:")) Icons.Default.Code else Icons.Default.SportsEsports
}

fun catColor(cat: GameCatalog.Cat): Color = when (cat) {
    GameCatalog.Cat.ARCADE -> Color(0xFFE53935)
    GameCatalog.Cat.PUZZLE -> Color(0xFF1E88E5)
    GameCatalog.Cat.BRAIN -> Color(0xFF8E24AA)
    GameCatalog.Cat.WORDS -> Color(0xFF00897B)
    GameCatalog.Cat.CARDS -> Color(0xFFF4511E)
    GameCatalog.Cat.BOARD -> Color(0xFF6D4C41)
    GameCatalog.Cat.PARTY -> Color(0xFFD81B60)
    GameCatalog.Cat.PYTHON -> Color(0xFF3949AB)
}

fun catIcon(cat: GameCatalog.Cat): ImageVector = when (cat) {
    GameCatalog.Cat.ARCADE -> Icons.Default.SportsEsports
    GameCatalog.Cat.PUZZLE -> Icons.Default.Extension
    GameCatalog.Cat.BRAIN -> Icons.Default.Psychology
    GameCatalog.Cat.WORDS -> Icons.Default.Abc
    GameCatalog.Cat.CARDS -> Icons.Default.Casino
    GameCatalog.Cat.BOARD -> Icons.Default.Castle
    GameCatalog.Cat.PARTY -> Icons.Default.Celebration
    GameCatalog.Cat.PYTHON -> Icons.Default.Code
}

/** Ikona gry w kolorowym, zaokrąglonym kwadracie. */
@Composable
fun GameBadge(info: GameCatalog.Info, size: Dp = 40.dp) {
    val c = catColor(info.cat)
    Box(Modifier.size(size).clip(RoundedCornerShape(size / 4)).background(c.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
        Icon(gameIcon(info.id), null, tint = c, modifier = Modifier.size(size * 0.6f))
    }
}

/** Nagłówek gry w dymku: ikona + nazwa. */
@Composable
fun GameTitle(info: GameCatalog.Info, sub: String? = null) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        GameBadge(info, 28.dp)
        Spacer(Modifier.width(8.dp))
        Column {
            Text(info.name, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (sub != null) Text(sub, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

fun whoName(pk: String?): String = when (pk) { null -> "?"; App.core.myPub -> "Ty"; else -> App.core.db.nameOf(pk) }

fun fmtMove(v: Double): String = if (v == Math.floor(v) && Math.abs(v) < 1e15) v.toLong().toString() else String.format(Locale.US, "%.3f", v).trimEnd('0').trimEnd('.')

private val dateFmt = SimpleDateFormat("d.MM.yy HH:mm", Locale("pl"))
fun fmtDate(ts: Long): String = dateFmt.format(Date(ts))

// ============================ gra na pełnym ekranie ============================

/**
 * Gra jednoosobowa: [seed] — układ (w wyzwaniu ten sam dla wszystkich), [finish] — wynik końcowy,
 * [fail] — przegrana bez wyniku (np. mina w saperze).
 */
class GameEnv(val seed: Long, val finish: (Double) -> Unit, val fail: (String) -> Unit = {})

data class PlayReq(val id: String, val seed: Long, val chatMsg: String? = null, val chatId: String? = null)

/** Gra otwarta na pełnym ekranie (nad aplikacją). */
object Play {
    var req by mutableStateOf<PlayReq?>(null)

    fun start(ctx: android.content.Context, id: String, seed: Long? = null, chatMsg: String? = null, chatId: String? = null) {
        val info = GameCatalog.byId(id)
        if (info == null) { toast(ctx, "Tej gry nie ma w tej wersji Szepta — zaktualizuj aplikację"); return }
        if (info.python) { PyGameActivity.launch(ctx, id, chatMsg, chatId); return }
        if (SOLO_GAMES[id] == null) { toast(ctx, "Tej gry nie da się uruchomić samemu"); return }
        req = PlayReq(id, seed ?: Games.secureLong(), chatMsg, chatId)
    }
}

/** Zapisuje wynik w telefonie (i w wyzwaniu — w rozmowie). Zwraca poprzedni rekord. */
suspend fun saveScore(info: GameCatalog.Info, score: Double, chatId: String?, chatMsg: String?, label: String = ""): Double? = withContext(Dispatchers.IO) {
    val db = App.core.db
    val prev = info.score.best(db.gameScores(info.id).map { it.score })
    db.addGameScore(info.id, score, chatId?.let { "chat:$it" } ?: if (info.ai) "ai" else "solo", label)
    if (chatMsg != null) App.core.gameMove(chatMsg, fmtMove(score))
    prev
}

@Composable
fun PlayHost() {
    val r = Play.req ?: return
    val info = remember(r.id) { GameCatalog.byId(r.id) }
    val game = SOLO_GAMES[r.id]
    if (info == null || game == null) { Play.req = null; return }
    var round by remember(r) { mutableIntStateOf(0) }
    var seed by remember(r) { mutableLongStateOf(r.seed) }
    var result by remember(r) { mutableStateOf<Double?>(null) }
    var failed by remember(r) { mutableStateOf<String?>(null) }
    var prevBest by remember(r) { mutableStateOf<Double?>(null) }
    var totals by remember(r) { mutableStateOf(false) }
    var help by remember(r) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    BackHandler { Play.req = null }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().systemBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { Play.req = null }) { Icon(Icons.Default.Close, "Zamknij grę") }
                GameBadge(info, 30.dp)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(info.name, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (r.chatMsg != null) Text("Wyzwanie w rozmowie", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                }
                if (info.score.has) TextButton(onClick = { totals = true }, contentPadding = PaddingValues(horizontal = 8.dp)) {
                    Icon(Icons.Default.Leaderboard, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Łącznie")
                }
                IconButton(onClick = { help = true }) { Icon(Icons.AutoMirrored.Filled.HelpOutline, "Jak grać") }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                key(r, round) {
                    game(GameEnv(seed, finish = { score ->
                        if (result == null && failed == null) {
                            result = score
                            scope.launch { prevBest = saveScore(info, score, r.chatId, r.chatMsg) }
                        }
                    }, fail = { why -> if (result == null) failed = why }))
                }
                failed?.let { why ->
                    FailPanel(info, why, onAgain = { failed = null; round++ }, onTotals = { totals = true }, onClose = { Play.req = null })
                }
                result?.let { sc ->
                    ResultPanel(info, sc, prevBest, r.chatMsg != null,
                        onAgain = { result = null; prevBest = null; if (r.chatMsg == null) seed = Games.secureLong(); round++ },
                        onTotals = { totals = true },
                        onClose = { Play.req = null })
                }
            }
        }
    }
    if (totals) TotalsDialog(info, r.chatMsg, r.chatId) { totals = false }
    if (help) AlertDialog(
        onDismissRequest = { help = false },
        icon = { GameBadge(info) },
        title = { Text(info.name) },
        text = { Text(info.desc) },
        confirmButton = { TextButton(onClick = { help = false }) { Text("OK") } },
    )
}

/** Wynik po zakończonej grze: najpierw ten wynik, a „Łącznie” pokazuje tabelę wszystkich. */
@Composable
fun ResultPanel(info: GameCatalog.Info, score: Double, prevBest: Double?, challenge: Boolean, onAgain: () -> Unit, onTotals: () -> Unit, onClose: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)).clickable(enabled = false) {}, contentAlignment = Alignment.Center) {
        Card(Modifier.padding(24.dp).widthIn(max = 380.dp)) {
            Column(Modifier.padding(20.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                GameBadge(info, 48.dp)
                Spacer(Modifier.height(8.dp))
                val wdl = info.score.kind == GameCatalog.Score.Kind.WDL
                Text(if (wdl) info.score.fmt(score) + (if (score >= 0.99) "!" else "") else "Koniec gry", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                if (info.score.has && !wdl) {
                    Text("Twój wynik", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(info.score.fmt(score), fontSize = 36.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    when {
                        prevBest == null || info.score.better(score, prevBest) -> Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.EmojiEvents, null, tint = Color(0xFFFFB300)); Spacer(Modifier.width(4.dp))
                            Text(if (prevBest == null) "Pierwszy wynik — to Twój rekord" else "Nowy rekord!", fontWeight = FontWeight.Bold, color = Color(0xFFFFA000))
                        }
                        else -> Text("Twój rekord: " + info.score.fmt(prevBest), style = MaterialTheme.typography.bodyMedium)
                    }
                }
                if (wdl) {
                    val all by rememberDb(info.id, score) { App.core.db.gameScores(info.id) }
                    all?.let { l ->
                        Text("Bilans: ${l.count { it.score >= 0.99 }} wygr. · ${l.count { it.score in 0.49..0.51 }} rem. · ${l.count { it.score < 0.49 }} przegr.",
                            style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
                    }
                }
                if (challenge) Text("Wynik wysłany do rozmowy", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 6.dp))
                Spacer(Modifier.height(14.dp))
                Button(onClick = onAgain, Modifier.fillMaxWidth()) { Icon(Icons.Default.Replay, null); Spacer(Modifier.width(6.dp)); Text("Zagraj jeszcze raz") }
                if (info.score.has) OutlinedButton(onClick = onTotals, Modifier.fillMaxWidth()) { Icon(Icons.Default.Leaderboard, null); Spacer(Modifier.width(6.dp)); Text("Łącznie") }
                TextButton(onClick = onClose, Modifier.fillMaxWidth()) { Text("Zamknij") }
            }
        }
    }
}

/** Przegrana bez wyniku — nic nie trafia do tabeli ani do rozmowy. */
@Composable
fun FailPanel(info: GameCatalog.Info, why: String, onAgain: () -> Unit, onTotals: () -> Unit, onClose: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)).clickable(enabled = false) {}, contentAlignment = Alignment.Center) {
        Card(Modifier.padding(24.dp).widthIn(max = 380.dp)) {
            Column(Modifier.padding(20.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                GameBadge(info, 48.dp)
                Spacer(Modifier.height(8.dp))
                Text("Nie udało się", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(why, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp))
                Spacer(Modifier.height(14.dp))
                Button(onClick = onAgain, Modifier.fillMaxWidth()) { Icon(Icons.Default.Replay, null); Spacer(Modifier.width(6.dp)); Text("Spróbuj jeszcze raz") }
                if (info.score.has) OutlinedButton(onClick = onTotals, Modifier.fillMaxWidth()) { Icon(Icons.Default.Leaderboard, null); Spacer(Modifier.width(6.dp)); Text("Łącznie") }
                TextButton(onClick = onClose, Modifier.fillMaxWidth()) { Text("Zamknij") }
            }
        }
    }
}

// ============================ tabela „Łącznie” ============================

/** Wyniki zbiorcze gry: to wyzwanie, cała rozmowa i moje wyniki w tym telefonie. */
@Composable
fun TotalsDialog(info: GameCatalog.Info, chatMsg: String?, chatId: String?, onDismiss: () -> Unit) {
    val tabs = buildList {
        if (chatMsg != null && info.chat == GameCatalog.Chat.CHALLENGE) add("Ta gra")
        if (chatId != null && info.inChat) add("W tej rozmowie")
        if (info.solo) add("Moje wyniki")
    }.ifEmpty { listOf("Moje wyniki") }
    var tab by remember { mutableIntStateOf(0) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth(0.94f).fillMaxHeight(0.86f), shape = RoundedCornerShape(20.dp)) {
            Column {
                Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    GameBadge(info, 32.dp); Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Łącznie", style = MaterialTheme.typography.titleLarge)
                        Text(info.name, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "Zamknij") }
                }
                if (tabs.size > 1) TabRow(tab) { tabs.forEachIndexed { i, t -> Tab(tab == i, { tab = i }, text = { Text(t) }) } }
                Box(Modifier.weight(1f)) {
                    when (tabs[tab.coerceIn(0, tabs.lastIndex)]) {
                        "Ta gra" -> ThisChallengeTable(info, chatMsg!!)
                        "W tej rozmowie" -> ChatTotalsTable(info, chatId!!)
                        else -> MyScoresTable(info)
                    }
                }
            }
        }
    }
}

@Composable
private fun StatChip(label: String, value: String) {
    Column(Modifier.clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, fontWeight = FontWeight.Bold, maxLines = 1)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun TableRow(cells: List<String>, weights: List<Float>, header: Boolean = false, highlight: Boolean = false, odd: Boolean = false) {
    val bg = when {
        header -> MaterialTheme.colorScheme.secondaryContainer
        highlight -> MaterialTheme.colorScheme.primaryContainer
        odd -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
        else -> Color.Transparent
    }
    Row(Modifier.fillMaxWidth().background(bg).padding(horizontal = 12.dp, vertical = 8.dp)) {
        cells.forEachIndexed { i, c ->
            Text(c, Modifier.weight(weights.getOrElse(i) { 1f }), fontWeight = if (header || (highlight && i == cells.lastIndex)) FontWeight.Bold else FontWeight.Normal,
                style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis,
                textAlign = if (i == 0) TextAlign.Start else TextAlign.End)
        }
    }
}

private fun ctxLabel(ctx: String): String = when {
    ctx == "solo" -> "sam"
    ctx == "ai" -> "z komputerem"
    ctx.startsWith("chat:") -> App.core.db.chat(ctx.removePrefix("chat:"))?.name?.let { "wyzwanie: $it" } ?: "wyzwanie"
    else -> ctx
}

/** Moje wyniki w tej grze (zapisane w telefonie). */
@Composable
fun MyScoresTable(info: GameCatalog.Info) {
    val rows by rememberDb(info.id) { App.core.db.gameScores(info.id) }
    var sortBest by remember { mutableStateOf(true) }
    var confirmClear by remember { mutableStateOf(false) }
    val list = rows ?: return Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
    val sc = info.score
    if (list.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) { Text("Nie masz jeszcze wyników w tej grze.", textAlign = TextAlign.Center) }
        return
    }
    val best = sc.best(list.map { it.score })
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            FlowRow(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                StatChip("rozegrane", list.size.toString())
                if (sc.kind == GameCatalog.Score.Kind.WDL) {
                    StatChip("wygrane", list.count { it.score >= 0.99 }.toString())
                    StatChip("remisy", list.count { it.score in 0.49..0.51 }.toString())
                    StatChip("przegrane", list.count { it.score < 0.49 }.toString())
                } else {
                    best?.let { StatChip(if (sc.kind == GameCatalog.Score.Kind.TIME && !sc.higher) "najlepszy czas" else "najlepszy wynik", sc.fmt(it)) }
                    StatChip("średnio", sc.fmt(list.map { it.score }.average()))
                    StatChip("ostatni", sc.fmt(list.first().score))
                }
            }
            if (sc.kind != GameCatalog.Score.Kind.WDL) Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                FilterChip(sortBest, { sortBest = true }, { Text("Najlepsze") })
                Spacer(Modifier.width(8.dp))
                FilterChip(!sortBest, { sortBest = false }, { Text("Najnowsze") })
            }
            Spacer(Modifier.height(6.dp))
            TableRow(listOf("#", "Kiedy", "Gdzie", "Wynik"), listOf(0.4f, 1.6f, 1.1f, 1.3f), header = true)
        }
        val sorted = if (sortBest && sc.kind != GameCatalog.Score.Kind.WDL) sc.sort(list) { it.score } else list
        items(sorted.size) { i ->
            val s = sorted[i]
            TableRow(listOf("${i + 1}", fmtDate(s.ts), ctxLabel(s.ctx) + if (s.label.isNotBlank()) " · ${s.label}" else "", sc.fmt(s.score)),
                listOf(0.4f, 1.6f, 1.1f, 1.3f), highlight = s.score == best && sc.kind != GameCatalog.Score.Kind.WDL, odd = i % 2 == 1)
        }
        item {
            TextButton(onClick = { confirmClear = true }, Modifier.padding(8.dp)) { Icon(Icons.Default.DeleteOutline, null); Spacer(Modifier.width(4.dp)); Text("Wyczyść moje wyniki") }
        }
    }
    if (confirmClear) AlertDialog(
        onDismissRequest = { confirmClear = false },
        title = { Text("Wyczyścić wyniki?") },
        text = { Text("Wszystkie Twoje wyniki w grze „${info.name}” znikną z tego telefonu.") },
        confirmButton = { TextButton(onClick = { confirmClear = false; App.work.launch(Dispatchers.IO) { App.core.db.clearGameScores(info.id) } }) { Text("Wyczyść") } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Anuluj") } },
    )
}

/** Wyzwanie w rozmowie: ranking i wszystkie próby wszystkich osób. */
@Composable
fun ThisChallengeTable(info: GameCatalog.Info, msgId: String) {
    val moves by rememberDb(msgId) { App.core.db.gameMoves(msgId) }
    val all = moves ?: return
    val sc = info.score
    val results = remember(all) { Games.challengeResults(all) }
    val ranking = remember(all) { Games.challengeRanking(sc, all) }
    val attempts = remember(all) { all.mapNotNull { m -> m.mv.toDoubleOrNull()?.let { Triple(m.sender, it, m.ts) } } }
    LazyColumn(Modifier.fillMaxSize()) {
        item { Text("Ranking", Modifier.padding(12.dp), style = MaterialTheme.typography.titleSmall) }
        item { TableRow(listOf("Miejsce", "Osoba", "Próby", "Najlepszy"), listOf(0.8f, 1.6f, 0.7f, 1.2f), header = true) }
        items(ranking.size) { i ->
            val (pk, v) = ranking[i]
            TableRow(listOf(listOf("🥇", "🥈", "🥉").getOrElse(i) { "${i + 1}." }, whoName(pk), (results[pk]?.size ?: 0).toString(), sc.fmt(v)),
                listOf(0.8f, 1.6f, 0.7f, 1.2f), highlight = pk == App.core.myPub, odd = i % 2 == 1)
        }
        item { Text("Wszystkie próby", Modifier.padding(12.dp), style = MaterialTheme.typography.titleSmall) }
        item { TableRow(listOf("Osoba", "Kiedy", "Wynik"), listOf(1.5f, 1.3f, 1.2f), header = true) }
        val byTime = attempts.sortedByDescending { it.third }
        items(byTime.size) { i ->
            val (pk, v, ts) = byTime[i]
            TableRow(listOf(whoName(pk), fmtDate(ts), sc.fmt(v)), listOf(1.5f, 1.3f, 1.2f), highlight = ranking.firstOrNull()?.let { it.first == pk && it.second == v } == true, odd = i % 2 == 1)
        }
        if (ranking.isEmpty()) item { Text("Nikt jeszcze nie zagrał.", Modifier.padding(16.dp)) }
    }
}

/** Wszystkie gry tego rodzaju w rozmowie: kto ile grał i wygrał, najlepsze wyniki. */
@Composable
fun ChatTotalsTable(info: GameCatalog.Info, chatId: String) {
    class PRow(val pk: String, var games: Int = 0, var wins: Int = 0, var draws: Int = 0, var losses: Int = 0, var best: Double? = null)
    val data by rememberDb(chatId, info.id) {
        val msgs = App.core.db.gameMessages(chatId, info.id)
        val moves = App.core.db.gameMovesOf(msgs.map { it.id })
        val rows = LinkedHashMap<String, PRow>()
        var counted = 0
        for (m in msgs) {
            val g = try { JSONObject(m.ex.game ?: continue) } catch (e: Exception) { continue }
            val o = Games.outcome(m.sender, g, moves[m.id] ?: emptyList()) ?: continue
            if (!o.finished && o.scores.isEmpty()) continue
            counted++
            for (p in o.players) {
                val r = rows.getOrPut(p) { PRow(p) }
                r.games++
                when { p in o.winners -> r.wins++; o.draw -> r.draws++; o.finished && o.winners.isNotEmpty() -> r.losses++ }
                o.scores[p]?.let { v -> r.best = r.best?.let { b -> if (info.score.better(v, b)) v else b } ?: v }
            }
        }
        counted to rows.values.sortedWith(compareByDescending<PRow> { it.wins }.thenBy { it.losses }.thenByDescending { it.games })
    }
    val (count, rows) = data ?: return Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
    val versus = info.chat == GameCatalog.Chat.VERSUS
    val hasScore = info.score.has && info.score.kind != GameCatalog.Score.Kind.WDL && rows.any { it.best != null }
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Text("Rozegrane w tej rozmowie: $count", Modifier.padding(12.dp), style = MaterialTheme.typography.titleSmall)
            val head = buildList { add("Osoba"); add("Gry"); add("Wygr."); if (versus) { add("Rem."); add("Przegr.") }; if (hasScore) add("Najlepszy") }
            TableRow(head, head.mapIndexed { i, _ -> if (i == 0) 1.6f else if (i == head.lastIndex && hasScore) 1.3f else 0.7f }, header = true)
        }
        items(rows.size) { i ->
            val r = rows[i]
            val cells = buildList {
                add((if (i == 0 && r.wins > 0) "🏆 " else "") + whoName(r.pk)); add(r.games.toString()); add(r.wins.toString())
                if (versus) { add(r.draws.toString()); add(r.losses.toString()) }
                if (hasScore) add(r.best?.let { info.score.fmt(it) } ?: "—")
            }
            TableRow(cells, cells.mapIndexed { j, _ -> if (j == 0) 1.6f else if (j == cells.lastIndex && hasScore) 1.3f else 0.7f }, highlight = r.pk == App.core.myPub, odd = i % 2 == 1)
        }
        if (rows.isEmpty()) item { Text("Brak zakończonych gier.", Modifier.padding(16.dp)) }
    }
}

// ============================ pomocnicze do gier zręcznościowych ============================

/** Pętla gry: [step] co klatkę ekranu (dt w sekundach). Zwracany licznik klatek odświeża rysowanie. */
@Composable
fun rememberFrames(running: Boolean, step: (Float) -> Unit): State<Long> {
    val frame = remember { mutableLongStateOf(0L) }
    val cb by rememberUpdatedState(step)
    LaunchedEffect(running) {
        if (!running) return@LaunchedEffect
        var last = withFrameNanos { it }
        while (true) {
            withFrameNanos { now ->
                val dt = ((now - last) / 1e9f).coerceIn(0f, 0.05f)
                last = now
                cb(dt)
                frame.longValue++
            }
        }
    }
    return frame
}

/** Pasek u góry gry: wynik (najważniejsze — widać go cały czas) i dodatkowa informacja. */
@Composable
fun GameHud(score: String, extra: String = "", modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(score, fontWeight = FontWeight.Bold, fontSize = 22.sp, modifier = Modifier.weight(1f))
        if (extra.isNotEmpty()) Text(extra, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Odliczanie [seconds] s; po końcu wywołuje [onEnd]. Zwraca pozostały czas w sekundach. */
@Composable
fun rememberCountdown(seconds: Int, running: Boolean = true, onEnd: () -> Unit): State<Int> {
    val left = remember { mutableIntStateOf(seconds) }
    val end by rememberUpdatedState(onEnd)
    LaunchedEffect(running) {
        if (!running) return@LaunchedEffect
        while (left.intValue > 0) { delay(1000); left.intValue-- }
        end()
    }
    return left
}

/** Stoper gry (ms od startu), odświeżany co 100 ms. */
@Composable
fun rememberStopwatch(running: Boolean): State<Long> {
    val ms = remember { mutableLongStateOf(0L) }
    LaunchedEffect(running) {
        if (!running) return@LaunchedEffect
        val start = System.currentTimeMillis() - ms.longValue
        while (true) { ms.longValue = System.currentTimeMillis() - start; delay(100) }
    }
    return ms
}

/** Duży przycisk startu przed grą zręcznościową. */
@Composable
fun StartOverlay(text: String, onStart: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)).clickable { onStart() }, contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
            Icon(Icons.Default.PlayCircle, null, tint = Color.White, modifier = Modifier.size(72.dp))
            Spacer(Modifier.height(8.dp))
            Text(text, color = Color.White, textAlign = TextAlign.Center, style = MaterialTheme.typography.titleMedium)
        }
    }
}

/** Ziarno → generator (ten sam układ dla wszystkich w wyzwaniu). */
fun seeded(seed: Long, salt: String = "") = Games.rng(seed, salt)

/** Wiadomość z grą: dane JSON. */
fun gameJson(m: Message): JSONObject = try { JSONObject(m.ex.game ?: "{}") } catch (e: Exception) { JSONObject() }

/** Ruchy gry z bazy (odświeżane). */
@Composable
fun rememberMoves(msgId: String): List<Db.GameMove> {
    val moves by rememberDb(msgId) { App.core.db.gameMoves(msgId) }
    return moves ?: emptyList()
}

/** Wyślij nową grę tego rodzaju do rozmowy. */
fun startInChat(ctx: android.content.Context, chatId: String, type: String, extra: JSONObject? = null) {
    App.work.launch { try { App.core.startGame(chatId, type, extra) } catch (e: Exception) { toast(ctx, pl.szept.ui.sendError(e)) } }
}

/** Przyciski pod grą w dymku. */
@Composable
fun BubbleActions(content: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically, content = content)
}

@Composable
fun TotalsButton(onClick: () -> Unit) {
    TextButton(onClick = onClick) { Icon(Icons.Default.Leaderboard, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Łącznie") }
}

@Suppress("unused")
private fun noop() = LocalContext
