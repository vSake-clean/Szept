package pl.szept.ui.games

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import pl.szept.core.GameCatalog
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// ============================ pary ============================

private val MEMORY_ICONS: List<ImageVector> = listOf(
    Icons.Default.Pets, Icons.Default.Favorite, Icons.Default.Star, Icons.Default.LocalFlorist, Icons.Default.WbSunny, Icons.Default.Cake,
    Icons.Default.DirectionsCar, Icons.Default.MusicNote, Icons.Default.Anchor, Icons.Default.Bolt, Icons.Default.Icecream, Icons.Default.Rocket,
)
private val MEMORY_COLORS = listOf(0xFFE53935, 0xFFD81B60, 0xFFFDD835, 0xFF43A047, 0xFFFB8C00, 0xFF8E24AA, 0xFF1E88E5, 0xFF00897B, 0xFF3949AB, 0xFFFFB300, 0xFFF06292, 0xFF6D4C41).map { Color(it) }

@Composable
fun MemoryGame(env: GameEnv) {
    val cards = remember { seeded(env.seed, "memory").let { r -> (0 until 10).flatMap { listOf(it, it) }.shuffled(r) } }
    val open = remember { mutableStateListOf<Int>() }
    val done = remember { mutableStateListOf<Int>() }
    var moves by remember { mutableIntStateOf(0) }
    var over by remember { mutableStateOf(false) }
    LaunchedEffect(open.size) {
        if (open.size == 2) {
            moves++
            if (cards[open[0]] == cards[open[1]]) { done.addAll(open); open.clear(); if (done.size == cards.size) { over = true; env.finish(moves.toDouble()) } }
            else { delay(750); open.clear() }
        }
    }
    Column(Modifier.fillMaxSize()) {
        GameHud("$moves ruchów", "pary: ${done.size / 2}/10")
        Box(Modifier.weight(1f).fillMaxWidth().padding(10.dp), contentAlignment = Alignment.Center) {
            Column(Modifier.widthIn(max = 520.dp).aspectRatio(4f / 5f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (r in 0 until 5) Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (c in 0 until 4) {
                        val i = r * 4 + c
                        val shown = i in open || i in done
                        Box(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(12.dp))
                            .background(if (shown) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.primary)
                            .clickable(enabled = !shown && open.size < 2 && !over) { open.add(i) }, contentAlignment = Alignment.Center) {
                            if (shown) Icon(MEMORY_ICONS[cards[i]], null, tint = MEMORY_COLORS[cards[i]].copy(alpha = if (i in done) 0.5f else 1f), modifier = Modifier.fillMaxSize(0.6f))
                            else Icon(Icons.Default.QuestionMark, null, tint = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.5f))
                        }
                    }
                }
            }
        }
    }
}

// ============================ simon ============================

private val SIMON = listOf(Color(0xFF43A047), Color(0xFFE53935), Color(0xFFFDD835), Color(0xFF1E88E5))

@Composable
fun SimonGame(env: GameEnv) {
    val rnd = remember { seeded(env.seed, "simon") }
    val seq = remember { mutableStateListOf(rnd.nextInt(4)) }
    var showing by remember { mutableStateOf(true) }
    var lit by remember { mutableIntStateOf(-1) }
    var pos by remember { mutableIntStateOf(0) }
    var over by remember { mutableStateOf(false) }
    var started by remember { mutableStateOf(false) }
    LaunchedEffect(seq.size, started) {
        if (!started) return@LaunchedEffect
        showing = true; delay(600)
        val speed = max(250L, 550L - seq.size * 20L)
        for (k in seq) { lit = k; delay(speed); lit = -1; delay(150) }
        showing = false; pos = 0
    }
    fun tap(k: Int) {
        if (showing || over) return
        if (seq[pos] != k) { over = true; env.finish((seq.size - 1).toDouble()); return }
        pos++
        if (pos == seq.size) seq.add(rnd.nextInt(4))
    }
    Column(Modifier.fillMaxSize()) {
        GameHud("poziom ${seq.size}", if (showing) "patrz…" else "powtórz")
        SquareBoard(Modifier.weight(1f)) {
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                for (r in 0 until 2) Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    for (c in 0 until 2) {
                        val k = r * 2 + c
                        var pressed by remember { mutableStateOf(false) }
                        Box(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(24.dp))
                            .background(SIMON[k].copy(alpha = if (lit == k || pressed) 1f else 0.35f))
                            .pointerInput(showing, over) {
                                awaitEachGesture {
                                    awaitFirstDown()
                                    if (!showing && !over) { pressed = true; tap(k) }
                                    while (true) { val e = awaitPointerEvent(); if (e.changes.all { !it.pressed }) break }
                                    pressed = false
                                }
                            })
                    }
                }
            }
        }
    }
    if (!started) StartOverlay("Zapamiętaj kolejność kolorów i powtórz ją") { started = true }
}

// ============================ pamięć cyfr ============================

@Composable
fun DigitsGame(env: GameEnv) {
    val rnd = remember { seeded(env.seed, "digits") }
    var level by remember { mutableIntStateOf(3) }
    var number by remember { mutableStateOf("") }
    var showing by remember { mutableStateOf(true) }
    var input by remember { mutableStateOf("") }
    var over by remember { mutableStateOf(false) }
    var started by remember { mutableStateOf(false) }
    LaunchedEffect(level, started) {
        if (!started) return@LaunchedEffect
        number = (1..level).joinToString("") { if (it == 1) (1 + rnd.nextInt(9)).toString() else rnd.nextInt(10).toString() }
        showing = true; input = ""
        delay(1000L + level * 450L)
        showing = false
    }
    Column(Modifier.fillMaxSize().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        GameHud("poziom ${level - 2}", "$level cyfr")
        Spacer(Modifier.weight(1f))
        if (showing) Text(number, fontSize = if (level < 10) 48.sp else 32.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, letterSpacing = 4.sp)
        else {
            Text("Jaka to była liczba?", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(input, { input = it.filter { c -> c.isDigit() }.take(40) }, singleLine = true, textStyle = MaterialTheme.typography.headlineMedium,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            Button(onClick = {
                if (input == number) level++ else { over = true; env.finish((level - 3).toDouble()) }
            }, enabled = input.isNotEmpty() && !over, modifier = Modifier.padding(top = 12.dp)) { Text("Sprawdź") }
        }
        Spacer(Modifier.weight(1f))
    }
    if (!started) StartOverlay("Zapamiętaj liczbę, a potem ją wpisz") { started = true }
}

// ============================ test szympansa ============================

@Composable
fun ChimpGame(env: GameEnv) {
    val rnd = remember { seeded(env.seed, "chimp") }
    val cols = 5; val rows = 7
    var count by remember { mutableIntStateOf(4) }
    var cells by remember { mutableStateOf(emptyMap<Int, Int>()) }   // pole → liczba
    var next by remember { mutableIntStateOf(1) }
    var hidden by remember { mutableStateOf(false) }
    var lives by remember { mutableIntStateOf(2) }
    var over by remember { mutableStateOf(false) }
    fun newRound() { cells = (0 until cols * rows).shuffled(rnd).take(count).mapIndexed { k, cell -> cell to k + 1 }.toMap(); next = 1; hidden = false }
    LaunchedEffect(Unit) { newRound() }
    fun tap(cell: Int) {
        if (over) return
        val v = cells[cell] ?: return
        if (v != next) {
            lives--
            if (lives <= 0) { over = true; env.finish((count - 4).toDouble()) } else newRound()
            return
        }
        hidden = true
        cells = cells - cell
        next++
        if (cells.isEmpty()) { count++; newRound() }
    }
    Column(Modifier.fillMaxSize()) {
        GameHud("poziom ${count - 3}", "szanse: $lives")
        Box(Modifier.weight(1f).fillMaxWidth().padding(10.dp), contentAlignment = Alignment.Center) {
            Column(Modifier.widthIn(max = 480.dp).aspectRatio(cols.toFloat() / rows), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for (r in 0 until rows) Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (c in 0 until cols) {
                        val i = r * cols + c
                        val v = cells[i]
                        Box(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(10.dp))
                            .background(if (v != null) (if (hidden) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface) else Color.Transparent)
                            .then(if (v != null && !hidden) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(10.dp)) else Modifier)
                            .clickable(enabled = v != null) { tap(i) }, contentAlignment = Alignment.Center) {
                            if (v != null && !hidden) Text("$v", fontSize = 24.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
        Text("Dotykaj od 1 w górę. Po pierwszym dotknięciu liczby znikają!", Modifier.fillMaxWidth().padding(8.dp), textAlign = TextAlign.Center, style = MaterialTheme.typography.labelMedium)
    }
}

// ============================ refleks ============================

@Composable
fun ReflexGame(env: GameEnv) {
    val rnd = remember { seeded(env.seed, "reflex") }
    val results = remember { mutableStateListOf<Int>() }
    // 0 = gotowy, 1 = czekaj, 2 = teraz, 3 = falstart
    var phase by remember { mutableIntStateOf(0) }
    var goAt by remember { mutableLongStateOf(0L) }
    var over by remember { mutableStateOf(false) }
    LaunchedEffect(phase) {
        if (phase == 1) {
            delay(1200L + rnd.nextInt(2600))
            if (phase == 1) { goAt = System.nanoTime(); phase = 2 }
        }
        if (phase == 3) { delay(1200); phase = 0 }
    }
    val (bg, label) = when (phase) {
        1 -> Color(0xFFC62828) to "Czekaj na zielone…"
        2 -> Color(0xFF2E7D32) to "TERAZ!"
        3 -> Color(0xFF424242) to "Za wcześnie! Jeszcze raz"
        else -> MaterialTheme.colorScheme.primary to "Dotknij, aby zacząć próbę ${results.size + 1}/5"
    }
    Column(Modifier.fillMaxSize()) {
        GameHud(if (results.isEmpty()) "— ms" else "${results.average().roundToInt()} ms", "próby: ${results.size}/5")
        Box(Modifier.weight(1f).fillMaxWidth().background(bg).pointerInput(Unit) {
            awaitEachGesture {
                awaitFirstDown()
                if (!over) when (phase) {
                    0 -> phase = 1
                    1 -> phase = 3
                    2 -> {
                        results.add(((System.nanoTime() - goAt) / 1_000_000).toInt().coerceIn(0, 9999))
                        phase = 0
                        if (results.size >= 5) { over = true; env.finish(results.average().roundToInt().toDouble()) }
                    }
                }
            }
        }, contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(label, color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                results.lastOrNull()?.let { if (phase == 0) Text("$it ms", color = Color.White, fontSize = 40.sp) }
            }
        }
    }
}

// ============================ tabela Schultego ============================

@Composable
fun SchulteGame(env: GameEnv) {
    val nums = remember { (1..25).shuffled(seeded(env.seed, "schulte")) }
    var next by remember { mutableIntStateOf(1) }
    var started by remember { mutableStateOf(false) }
    var over by remember { mutableStateOf(false) }
    var wrong by remember { mutableIntStateOf(-1) }
    val ms = rememberStopwatch(started && !over)
    Column(Modifier.fillMaxSize()) {
        GameHud(GameCatalog.fmtTime(ms.value.toDouble()), "szukaj: $next")
        SquareBoard(Modifier.weight(1f)) {
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                for (r in 0 until 5) Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (c in 0 until 5) {
                        val v = nums[r * 5 + c]
                        Box(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(8.dp))
                            .background(when { v < next -> MaterialTheme.colorScheme.primary.copy(alpha = 0.25f); v == wrong -> Color(0xFFE57373); else -> MaterialTheme.colorScheme.surfaceVariant })
                            .clickable(enabled = !over) {
                                started = true
                                if (v == next) { next++; wrong = -1; if (next > 25) { over = true; env.finish(ms.value.toDouble()) } } else wrong = v
                            }, contentAlignment = Alignment.Center) {
                            Text("$v", fontSize = 24.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

// ============================ inny odcień ============================

@Composable
fun ShadeGame(env: GameEnv) {
    val rnd = remember { seeded(env.seed, "shade") }
    var level by remember { mutableIntStateOf(1) }
    var odd by remember { mutableIntStateOf(0) }
    var hue by remember { mutableFloatStateOf(0f) }
    var over by remember { mutableStateOf(false) }
    val n = min(8, 2 + level / 3)
    fun round() { odd = rnd.nextInt(n * n); hue = rnd.nextFloat() * 360f }
    LaunchedEffect(level) { round() }
    val left = rememberCountdown(60, !over) { if (!over) { over = true; env.finish((level - 1).toDouble()) } }
    val diff = max(0.04f, 0.32f - level * 0.012f)
    Column(Modifier.fillMaxSize()) {
        GameHud("poziom $level", "${left.value} s")
        SquareBoard(Modifier.weight(1f)) {
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                for (r in 0 until n) Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (c in 0 until n) {
                        val i = r * n + c
                        Box(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(6.dp))
                            .background(Color.hsv(hue, 0.6f, 0.75f))
                            .background(if (i == odd) Color.White.copy(alpha = diff * 0.9f) else Color.Transparent)
                            .clickable(enabled = !over) { if (i == odd) level++ else { over = true; env.finish((level - 1).toDouble()) } })
                    }
                }
            }
        }
        Text("Dotknij pola, które ma inny odcień", Modifier.fillMaxWidth().padding(8.dp), textAlign = TextAlign.Center, style = MaterialTheme.typography.labelMedium)
    }
}

// ============================ stoper ============================

@Composable
fun StopwatchGame(env: GameEnv) {
    var running by remember { mutableStateOf(false) }
    var start by remember { mutableLongStateOf(0L) }
    var shown by remember { mutableLongStateOf(0L) }
    var over by remember { mutableStateOf(false) }
    LaunchedEffect(running) {
        while (running) { shown = (System.nanoTime() - start) / 1_000_000; delay(16) }
    }
    Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text("Zatrzymaj dokładnie na", style = MaterialTheme.typography.titleMedium)
        Text("10,00 s", fontSize = 40.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(24.dp))
        Text(if (running && shown > 3000) "?,?? s" else GameCatalog.fmtTime(shown.toDouble()), fontSize = 56.sp, fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(24.dp))
        Button(onClick = {
            if (over) return@Button
            if (!running) { start = System.nanoTime(); running = true }
            else {
                running = false
                val t = (System.nanoTime() - start) / 1_000_000
                shown = t; over = true
                env.finish(abs(t - 10_000).toDouble())
            }
        }, Modifier.size(160.dp), shape = RoundedCornerShape(80.dp)) { Text(if (running) "STOP" else "START", fontSize = 24.sp) }
        Spacer(Modifier.height(16.dp))
        Text("Wynik to różnica od 10 sekund (mniej = lepiej)", style = MaterialTheme.typography.labelMedium)
    }
}

// ============================ kolory (Stroop) ============================

private val STROOP = listOf("CZERWONY" to Color(0xFFE53935), "NIEBIESKI" to Color(0xFF1E88E5), "ZIELONY" to Color(0xFF43A047), "ŻÓŁTY" to Color(0xFFFDD835), "FIOLETOWY" to Color(0xFF8E24AA))

@Composable
fun StroopGame(env: GameEnv) {
    val rnd = remember { seeded(env.seed, "stroop") }
    var word by remember { mutableIntStateOf(0) }
    var ink by remember { mutableIntStateOf(1) }
    var score by remember { mutableIntStateOf(0) }
    var over by remember { mutableStateOf(false) }
    var started by remember { mutableStateOf(false) }
    fun next() { word = rnd.nextInt(STROOP.size); ink = rnd.nextInt(STROOP.size) }
    LaunchedEffect(Unit) { next() }
    val left = rememberCountdown(30, started && !over) { if (!over) { over = true; env.finish(score.toDouble()) } }
    Column(Modifier.fillMaxSize().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        GameHud("$score pkt", "${left.value} s")
        Spacer(Modifier.weight(1f))
        Text("Jakiego KOLORU jest napis?", style = MaterialTheme.typography.titleMedium)
        Text(STROOP[word].first, fontSize = 44.sp, fontWeight = FontWeight.Black, color = STROOP[ink].second, modifier = Modifier.padding(16.dp))
        Spacer(Modifier.weight(1f))
        STROOP.forEachIndexed { k, (name, _) ->
            OutlinedButton(onClick = {
                if (!started || over) return@OutlinedButton
                score = max(0, score + if (k == ink) 1 else -1); next()
            }, Modifier.fillMaxWidth().padding(vertical = 3.dp)) { Text(name.lowercase().replaceFirstChar { it.uppercase() }) }
        }
    }
    if (!started) StartOverlay("Wybieraj kolor napisu, a nie jego treść.\nBłąd = −1 pkt") { started = true }
}

// ============================ szybkie liczenie ============================

@Composable
fun MathGame(env: GameEnv) {
    val rnd = remember { seeded(env.seed, "math") }
    data class Q(val text: String, val answer: Int, val options: List<Int>)
    fun make(level: Int): Q {
        val op = rnd.nextInt(if (level < 5) 2 else 4)
        val (t, a) = when (op) {
            0 -> { val x = rnd.nextInt(5, 20 + level * 4); val y = rnd.nextInt(2, 20 + level * 4); "$x + $y" to x + y }
            1 -> { val x = rnd.nextInt(10, 30 + level * 4); val y = rnd.nextInt(1, x); "$x − $y" to x - y }
            2 -> { val x = rnd.nextInt(2, 10 + level / 2); val y = rnd.nextInt(2, 10); "$x × $y" to x * y }
            else -> { val y = rnd.nextInt(2, 10); val a = rnd.nextInt(2, 10 + level / 2); "${a * y} : $y" to a }
        }
        val opts = (setOf(a) + generateSequence { a + rnd.nextInt(-10, 11) }.filter { it != a && it >= 0 }.take(3)).toList().shuffled(rnd)
        return Q(t, a, opts)
    }
    var score by remember { mutableIntStateOf(0) }
    var q by remember { mutableStateOf(make(0)) }
    var over by remember { mutableStateOf(false) }
    var started by remember { mutableStateOf(false) }
    val left = rememberCountdown(60, started && !over) { if (!over) { over = true; env.finish(score.toDouble()) } }
    Column(Modifier.fillMaxSize().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        GameHud("$score pkt", "${left.value} s")
        Spacer(Modifier.weight(1f))
        Text(q.text + " = ?", fontSize = 44.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.weight(1f))
        for (row in q.options.chunked(2)) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            row.forEach { v ->
                Button(onClick = {
                    if (!started || over) return@Button
                    if (v == q.answer) score++ else score = max(0, score - 1)
                    q = make(score)
                }, Modifier.weight(1f).height(64.dp)) { Text("$v", fontSize = 24.sp) }
            }
        }
        Spacer(Modifier.height(16.dp))
    }
    if (!started) StartOverlay("Rozwiązuj działania. Błąd = −1 pkt") { started = true }
}
