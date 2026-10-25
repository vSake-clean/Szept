@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package pl.szept.ui.games

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import org.json.JSONObject
import pl.szept.App
import pl.szept.core.GameCatalog
import pl.szept.core.Games
import pl.szept.core.PyGames
import pl.szept.ui.Nav
import pl.szept.ui.rememberDb

/** Krótki opis trybu gry (na liście). */
fun modeLabel(info: GameCatalog.Info): String = when {
    info.python -> "Python"
    info.chat == GameCatalog.Chat.VERSUS -> if (info.players == "2") "we dwoje" else "we dwoje lub więcej"
    info.chat == GameCatalog.Chat.GROUP -> if (info.groupOnly) "tylko w grupie" else "w rozmowie"
    info.chat == GameCatalog.Chat.CHALLENGE -> "sam lub wyzwanie"
    else -> "sam"
}

private enum class Filter(val label: String) { ALL("Wszystkie"), SOLO("Sam"), CHALLENGE("Wyzwania"), VERSUS("We dwoje"), GROUP("W grupie") }

/** Lista gier z wyszukiwarką i filtrami — pozycje jak w menu spinacza (ikona Material + nazwa). */
@Composable
private fun GameList(games: List<GameCatalog.Info>, filters: List<Filter>, header: (@Composable () -> Unit)? = null, onPick: (GameCatalog.Info) -> Unit) {
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(Filter.ALL) }
    var cat by remember { mutableStateOf<GameCatalog.Cat?>(null) }
    val shown = remember(games, query, filter, cat) {
        val q = Games.norm(query)
        games.filter { g ->
            (q.isEmpty() || Games.norm(g.name + " " + g.desc).contains(q)) &&
                (cat == null || g.cat == cat) &&
                when (filter) {
                    Filter.ALL -> true
                    Filter.SOLO -> g.solo
                    Filter.CHALLENGE -> g.chat == GameCatalog.Chat.CHALLENGE
                    Filter.VERSUS -> g.chat == GameCatalog.Chat.VERSUS
                    Filter.GROUP -> g.chat == GameCatalog.Chat.GROUP
                }
        }
    }
    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), singleLine = true,
            placeholder = { Text("Szukaj gry") }, leadingIcon = { Icon(Icons.Default.Search, null) },
            trailingIcon = if (query.isNotEmpty()) ({ IconButton(onClick = { query = "" }) { Icon(Icons.Default.Clear, "Wyczyść") } }) else null,
            shape = RoundedCornerShape(24.dp))
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            filters.forEach { f -> FilterChip(filter == f && cat == null, { filter = f; cat = null }, { Text(f.label) }) }
            GameCatalog.Cat.entries.filter { c -> games.any { it.cat == c } }.forEach { c ->
                FilterChip(cat == c, { cat = if (cat == c) null else c }, { Text(c.label) }, leadingIcon = { Icon(catIcon(c), null, Modifier.size(16.dp), tint = catColor(c)) })
            }
        }
        LazyColumn(Modifier.weight(1f)) {
            header?.let { h -> item { h() } }
            item { Text("${shown.size} " + GameCatalog.plural(shown.size.toLong(), "gra", "gry", "gier"), Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            val grouped = query.isEmpty() && cat == null
            if (grouped) {
                GameCatalog.Cat.entries.forEach { c ->
                    val inCat = shown.filter { it.cat == c }
                    if (inCat.isEmpty()) return@forEach
                    item(key = "h-$c") {
                        Row(Modifier.padding(start = 16.dp, top = 12.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(catIcon(c), null, tint = catColor(c), modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
                            Text(c.label, style = MaterialTheme.typography.titleSmall, color = catColor(c))
                        }
                    }
                    items(inCat, key = { it.id }) { g -> GameRow(g) { onPick(g) } }
                }
            } else items(shown, key = { it.id }) { g -> GameRow(g) { onPick(g) } }
        }
    }
}

@Composable
private fun GameRow(info: GameCatalog.Info, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(info.name, fontWeight = FontWeight.Medium) },
        supportingContent = { Text(info.desc, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall) },
        leadingContent = { GameBadge(info, 44.dp) },
        trailingContent = { Text(modeLabel(info), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary) },
        modifier = Modifier.clickable { onClick() },
    )
}

/** Wybór gry w rozmowie (spinacz → Gra). */
@Composable
fun GamePickerDialog(chatId: String, isGroup: Boolean, onDismiss: () -> Unit) {
    val pyChanges by PyGames.changes.collectAsState()
    val games = remember(isGroup, pyChanges) { GameCatalog.all().filter { it.inChat && (isGroup || !it.groupOnly) || it.solo } }
    var picked by remember { mutableStateOf<GameCatalog.Info?>(null) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
                TopAppBar(
                    title = { Text("Zagraj") },
                    navigationIcon = { IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "Zamknij") } },
                )
                GameList(games, listOf(Filter.ALL, Filter.GROUP, Filter.VERSUS, Filter.CHALLENGE, Filter.SOLO)) { picked = it }
            }
        }
    }
    picked?.let { g -> GameDetailDialog(g, chatId, isGroup, onDismiss = { picked = null }, onDone = { picked = null; onDismiss() }) }
}

/** Szczegóły gry i wybór: wyślij do rozmowy albo zagraj sam. */
@Composable
fun GameDetailDialog(info: GameCatalog.Info, chatId: String?, isGroup: Boolean, onDismiss: () -> Unit, onDone: () -> Unit) {
    val ctx = LocalContext.current
    var word by remember { mutableStateOf("") }
    var hint by remember { mutableStateOf("") }
    var a by remember { mutableStateOf("") }
    var b by remember { mutableStateOf("") }
    var q by remember { mutableStateOf("") }
    var max by remember { mutableStateOf("") }
    var truth by remember { mutableIntStateOf(0) }
    var totals by remember { mutableStateOf(false) }
    val canChat = chatId != null && info.inChat && (isGroup || !info.groupOnly)
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { GameBadge(info, 52.dp) },
        title = { Text(info.name) },
        text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                Text(info.desc)
                Spacer(Modifier.height(6.dp))
                Text("Tryb: ${modeLabel(info)} · gracze: ${info.players}" + if (info.score.has) " · wynik: " + scoreLabel(info.score) else "",
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (canChat) when (info.id) {
                    "hangman" -> {
                        OutlinedTextField(word, { word = Games.cleanWord(it).take(20) }, Modifier.fillMaxWidth().padding(top = 8.dp), singleLine = true,
                            label = { Text("Twoje hasło (puste = losowe)") })
                        OutlinedTextField(hint, { hint = it.take(60) }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Podpowiedź (opcjonalnie)") })
                    }
                    "wyr" -> {
                        Text("Własne pytanie (puste = losowe):", Modifier.padding(top = 8.dp), style = MaterialTheme.typography.labelMedium)
                        OutlinedTextField(a, { a = it.take(80) }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Opcja A") })
                        OutlinedTextField(b, { b = it.take(80) }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Opcja B") })
                    }
                    "whoof" -> OutlinedTextField(q, { q = it.take(120) }, Modifier.fillMaxWidth().padding(top = 8.dp), singleLine = true, label = { Text("Kto z nas… (puste = losowe)") })
                    "ball8" -> OutlinedTextField(q, { q = it.take(200) }, Modifier.fillMaxWidth().padding(top = 8.dp), label = { Text("Twoje pytanie") })
                    "pick" -> OutlinedTextField(max, { max = it.filter { c -> c.isDigit() }.take(7) }, Modifier.fillMaxWidth().padding(top = 8.dp), singleLine = true,
                        label = { Text("Liczba od 1 do… (puste = losowa osoba)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    "truth" -> Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("Losowo", "Prawda", "Wyzwanie").forEachIndexed { i, t -> FilterChip(truth == i, { truth = i }, { Text(t) }) }
                    }
                }
            }
        },
        confirmButton = {
            if (canChat) Button(onClick = {
                val extra = when (info.id) {
                    "hangman" -> JSONObject().put("w", word).put("h", hint)
                    "wyr" -> JSONObject().put("a", a).put("b", b)
                    "whoof", "ball8" -> JSONObject().put("q", q)
                    "pick" -> JSONObject().put("max", max.toIntOrNull() ?: 0)
                    "truth" -> if (truth == 0) null else JSONObject().put("t", if (truth == 1) "p" else "w")
                    else -> null
                }
                startInChat(ctx, chatId!!, info.id, extra)
                onDone()
            }) { Icon(Icons.AutoMirrored.Filled.Send, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(if (info.chat == GameCatalog.Chat.CHALLENGE) "Wyzwij rozmowę" else "Wyślij do rozmowy") }
            else if (info.solo) Button(onClick = { Play.start(ctx, info.id); onDone() }) { Text(if (info.ai) "Zagraj z komputerem" else "Zagraj") }
        },
        dismissButton = {
            Row {
                if (info.score.has && info.solo) TextButton(onClick = { totals = true }) { Text("Łącznie") }
                if (canChat && info.solo) TextButton(onClick = { Play.start(ctx, info.id); onDone() }) { Text(if (info.ai) "Z komputerem" else "Sam") }
                else TextButton(onClick = onDismiss) { Text("Anuluj") }
            }
        },
    )
    if (totals) TotalsDialog(info, null, null) { totals = false }
}

fun scoreLabel(s: GameCatalog.Score): String = when (s.kind) {
    GameCatalog.Score.Kind.WDL -> "wygrane i przegrane"
    GameCatalog.Score.Kind.TIME -> if (s.higher) "czas (dłużej = lepiej)" else "czas (szybciej = lepiej)"
    GameCatalog.Score.Kind.NUMBER -> (s.forms.getOrNull(2) ?: "punkty") + if (s.higher) " (więcej = lepiej)" else " (mniej = lepiej)"
    GameCatalog.Score.Kind.NONE -> "bez wyniku"
}

// ============================ ekran „Gry” ============================

/** Wszystkie gry do zagrania samemu i moje wyniki. */
@Composable
fun GamesScreen(nav: Nav) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val pyChanges by PyGames.changes.collectAsState()
    val solo = remember(pyChanges) { GameCatalog.forSolo() }
    var picked by remember { mutableStateOf<GameCatalog.Info?>(null) }
    Scaffold(topBar = { pl.szept.ui.BackBar("Gry", nav) }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            TabRow(tab) {
                Tab(tab == 0, { tab = 0 }, text = { Text("Graj") }, icon = { Icon(Icons.Default.SportsEsports, null) })
                Tab(tab == 1, { tab = 1 }, text = { Text("Moje wyniki") }, icon = { Icon(Icons.Default.Leaderboard, null) })
            }
            if (tab == 0) GameList(solo, listOf(Filter.ALL, Filter.CHALLENGE, Filter.VERSUS), header = {
                val pending = remember(pyChanges) { PyGames.pending() }
                if (pending.isNotEmpty()) Text("Pobieram gry od administratora: " + pending.joinToString { it.name }, Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                Text("Gry z wynikiem możesz też wysłać do rozmowy (spinacz → Gra) — wtedy każdy gra ten sam układ, a tabela pokazuje, kto wygrał.",
                    Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }) { picked = it }
            else MyStatsList()
        }
    }
    picked?.let { g -> GameDetailDialog(g, null, false, onDismiss = { picked = null }, onDone = { picked = null }) }
}

/** Podsumowanie wszystkich moich wyników: gra, liczba gier, rekord. */
@Composable
private fun MyStatsList() {
    val data by rememberDb {
        val db = App.core.db
        db.gameScoreCounts().mapNotNull { (id, c) ->
            val info = GameCatalog.byId(id) ?: return@mapNotNull null
            val best = info.score.best(db.gameScores(id).map { it.score })
            Triple(info, c, best)
        }.sortedByDescending { it.second.second }
    }
    var open by remember { mutableStateOf<GameCatalog.Info?>(null) }
    val list = data ?: return Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
    if (list.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) { Text("Zagraj w coś — tu zobaczysz swoje wyniki i rekordy.") }
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Text("Rozegrane gry: ${list.sumOf { it.second.first }}", Modifier.padding(16.dp), style = MaterialTheme.typography.titleSmall)
        }
        items(list, key = { it.first.id }) { (info, c, best) ->
            ListItem(
                headlineContent = { Text(info.name) },
                supportingContent = {
                    Text("rozegrane: ${c.first} · ostatnio: ${fmtDate(c.second)}" + (best?.let { " · " + (if (info.score.kind == GameCatalog.Score.Kind.WDL) "" else "rekord: " + info.score.fmt(it)) } ?: ""))
                },
                leadingContent = { GameBadge(info) },
                trailingContent = { Icon(Icons.Default.Leaderboard, "Łącznie") },
                modifier = Modifier.clickable { open = info },
            )
        }
    }
    open?.let { TotalsDialog(it, null, null) { open = null } }
}
