package pl.szept.ui.games

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import pl.szept.core.GameCatalog
import pl.szept.core.Puzzles
import kotlin.math.abs

/** Kwadratowa plansza wyśrodkowana na ekranie. */
@Composable
fun SquareBoard(modifier: Modifier = Modifier, maxSize: androidx.compose.ui.unit.Dp = 520.dp, content: @Composable BoxScope.() -> Unit) {
    Box(modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.widthIn(max = maxSize).fillMaxWidth().aspectRatio(1f), content = content)
    }
}

@Composable
private fun EndButton(onClick: () -> Unit) {
    TextButton(onClick = onClick) { Icon(Icons.Default.Flag, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Zakończ") }
}

// ============================ 2048 ============================

@Composable
fun Game2048(env: GameEnv) {
    val rnd = remember { seeded(env.seed, "2048") }
    val board = remember { mutableStateListOf(*Array(16) { 0 }) }
    var score by remember { mutableIntStateOf(0) }
    var over by remember { mutableStateOf(false) }
    fun spawn() {
        val free = (0 until 16).filter { board[it] == 0 }
        if (free.isNotEmpty()) board[free[rnd.nextInt(free.size)]] = if (rnd.nextInt(10) == 0) 4 else 2
    }
    LaunchedEffect(Unit) { if (board.all { it == 0 }) { spawn(); spawn() } }
    fun line(idx: List<Int>): Boolean {
        val vals = idx.map { board[it] }.filter { it != 0 }
        val out = mutableListOf<Int>()
        var i = 0
        while (i < vals.size) {
            if (i + 1 < vals.size && vals[i] == vals[i + 1]) { out.add(vals[i] * 2); score += vals[i] * 2; i += 2 } else { out.add(vals[i]); i++ }
        }
        while (out.size < 4) out.add(0)
        var changed = false
        idx.forEachIndexed { k, cell -> if (board[cell] != out[k]) { board[cell] = out[k]; changed = true } }
        return changed
    }
    fun move(dir: Int) {
        if (over) return
        var changed = false
        for (k in 0 until 4) {
            val idx = when (dir) {
                0 -> (0 until 4).map { k * 4 + it }                // lewo
                1 -> (3 downTo 0).map { k * 4 + it }               // prawo
                2 -> (0 until 4).map { it * 4 + k }                // góra
                else -> (3 downTo 0).map { it * 4 + k }            // dół
            }
            if (line(idx)) changed = true
        }
        if (changed) spawn()
        val canMove = (0 until 16).any { i -> board[i] == 0 || (i % 4 < 3 && board[i] == board[i + 1]) || (i < 12 && board[i] == board[i + 4]) }
        if (!canMove) { over = true; env.finish(score.toDouble()) }
    }
    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            GameHud("$score pkt", "max ${board.max()}", Modifier.weight(1f))
            EndButton { if (!over) { over = true; env.finish(score.toDouble()) } }
        }
        SquareBoard(Modifier.weight(1f).pointerInput(Unit) {
            var total = Offset.Zero
            detectDragGestures(onDragStart = { total = Offset.Zero }, onDragEnd = {
                if (total.getDistance() > 60f) move(if (abs(total.x) > abs(total.y)) (if (total.x > 0) 1 else 0) else (if (total.y > 0) 3 else 2))
            }) { ch, d -> ch.consume(); total += d }
        }) {
            Column(Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp)).background(Color(0xFFBBADA0)).padding(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for (r in 0 until 4) Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (c in 0 until 4) {
                        val v = board[r * 4 + c]
                        val bg = when (v) {
                            0 -> Color(0xFFCDC1B4); 2 -> Color(0xFFEEE4DA); 4 -> Color(0xFFEDE0C8); 8 -> Color(0xFFF2B179); 16 -> Color(0xFFF59563)
                            32 -> Color(0xFFF67C5F); 64 -> Color(0xFFF65E3B); 128 -> Color(0xFFEDCF72); 256 -> Color(0xFFEDCC61); 512 -> Color(0xFFEDC850)
                            1024 -> Color(0xFFEDC53F); 2048 -> Color(0xFFEDC22E); else -> Color(0xFF3C3A32)
                        }
                        Box(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(8.dp)).background(bg), contentAlignment = Alignment.Center) {
                            if (v > 0) Text(v.toString(), fontWeight = FontWeight.Bold, fontSize = if (v < 100) 30.sp else if (v < 1000) 24.sp else 18.sp,
                                color = if (v <= 4) Color(0xFF776E65) else Color.White)
                        }
                    }
                }
            }
        }
        Text("Przesuwaj palcem w dowolną stronę", Modifier.fillMaxWidth().padding(8.dp), textAlign = TextAlign.Center, style = MaterialTheme.typography.labelMedium)
    }
}

// ============================ saper ============================

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun MinesGame(env: GameEnv) {
    val m = remember { Puzzles.mines(env.seed) }
    val open = remember { mutableStateListOf(*Array(m.w * m.h) { false }) }
    val flags = remember { mutableStateListOf(*Array(m.w * m.h) { false }) }
    var started by remember { mutableStateOf(false) }
    var over by remember { mutableStateOf(false) }
    var flagMode by remember { mutableStateOf(false) }
    val ms = rememberStopwatch(started && !over)
    fun reveal(i: Int) {
        if (open[i] || flags[i] || over) return
        started = true
        if (i in m.mines) { open[i] = true; over = true; env.fail("Trafiłeś na minę."); return }
        val stack = ArrayDeque<Int>(); stack.add(i)
        while (stack.isNotEmpty()) {
            val k = stack.removeLast()
            if (open[k] || flags[k]) continue
            open[k] = true
            if (m.count(k) == 0) m.neighbors(k).forEach { if (!open[it] && it !in m.mines) stack.add(it) }
        }
        if ((0 until m.w * m.h).all { it in m.mines || open[it] }) { over = true; env.finish(ms.value.coerceAtLeast(1).toDouble()) }
    }
    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            GameHud(GameCatalog.fmtTime(ms.value.toDouble()), "miny: ${m.mines.size - flags.count { it }}", Modifier.weight(1f))
            FilterChip(flagMode, { flagMode = !flagMode }, { Text("Flagi") }, leadingIcon = { Icon(Icons.Default.Flag, null, Modifier.size(16.dp)) })
            Spacer(Modifier.width(8.dp))
        }
        Box(Modifier.weight(1f).fillMaxWidth().padding(8.dp), contentAlignment = Alignment.Center) {
            Column(Modifier.widthIn(max = 520.dp).aspectRatio(m.w.toFloat() / m.h), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                for (r in 0 until m.h) Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    for (c in 0 until m.w) {
                        val i = r * m.w + c
                        val isOpen = open[i]
                        val bg = when {
                            isOpen && i in m.mines -> Color(0xFFE53935)
                            isOpen -> MaterialTheme.colorScheme.surfaceVariant
                            !started && i == m.start -> Color(0xFF81C784)
                            else -> Color(0xFF90A4AE)
                        }
                        Box(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(4.dp)).background(bg)
                            .combinedClickable(onClick = { if (flagMode && !isOpen) flags[i] = !flags[i] else reveal(i) }, onLongClick = { if (!isOpen) flags[i] = !flags[i] }),
                            contentAlignment = Alignment.Center) {
                            when {
                                isOpen && i in m.mines -> Icon(Icons.Default.Brightness7, null, tint = Color.Black, modifier = Modifier.fillMaxSize(0.7f))
                                isOpen -> m.count(i).takeIf { it > 0 }?.let { n ->
                                    Text("$n", fontWeight = FontWeight.Bold, color = listOf(Color(0xFF1E88E5), Color(0xFF43A047), Color(0xFFE53935), Color(0xFF3949AB), Color(0xFF8E24AA), Color(0xFF00897B), Color.Black, Color.Gray)[n - 1])
                                }
                                flags[i] -> Icon(Icons.Default.Flag, null, tint = Color(0xFFD32F2F), modifier = Modifier.fillMaxSize(0.7f))
                                !started && i == m.start -> Icon(Icons.Default.TouchApp, null, tint = Color.White, modifier = Modifier.fillMaxSize(0.7f))
                            }
                        }
                    }
                }
            }
        }
        Text("Zacznij od zielonego pola. Przytrzymaj, aby postawić flagę.", Modifier.fillMaxWidth().padding(8.dp), textAlign = TextAlign.Center, style = MaterialTheme.typography.labelMedium)
    }
}

// ============================ sudoku ============================

@Composable
fun SudokuGame(env: GameEnv) {
    var data by remember { mutableStateOf<Pair<IntArray, IntArray>?>(null) }
    LaunchedEffect(Unit) { data = withContext(Dispatchers.Default) { Puzzles.sudoku(env.seed) } }
    val d = data ?: return Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Column(horizontalAlignment = Alignment.CenterHorizontally) { CircularProgressIndicator(); Text("Przygotowuję planszę…") } }
    val (puzzle, solution) = d
    val grid = remember(d) { mutableStateListOf(*puzzle.toTypedArray()) }
    var sel by remember { mutableIntStateOf(-1) }
    var over by remember { mutableStateOf(false) }
    val ms = rememberStopwatch(!over)
    fun conflict(i: Int): Boolean {
        val v = grid[i]; if (v == 0) return false
        val r = i / 9; val c = i % 9
        for (k in 0 until 81) {
            if (k == i || grid[k] != v) continue
            if (k / 9 == r || k % 9 == c || (k / 27 == i / 27 && (k % 9) / 3 == c / 3)) return true
        }
        return false
    }
    fun put(v: Int) {
        if (sel < 0 || puzzle[sel] != 0 || over) return
        grid[sel] = v
        if ((0 until 81).all { grid[it] == solution[it] }) { over = true; env.finish(ms.value.toDouble()) }
    }
    Column(Modifier.fillMaxSize()) {
        GameHud(GameCatalog.fmtTime(ms.value.toDouble()), "puste: ${grid.count { it == 0 }}")
        SquareBoard(Modifier.weight(1f)) {
            val line = MaterialTheme.colorScheme.onSurface
            Column(Modifier.fillMaxSize().border(2.dp, line)) {
                for (r in 0 until 9) Row(Modifier.weight(1f)) {
                    for (c in 0 until 9) {
                        val i = r * 9 + c
                        val same = sel >= 0 && grid[sel] != 0 && grid[i] == grid[sel]
                        val related = sel >= 0 && (sel / 9 == r || sel % 9 == c || (sel / 27 == i / 27 && (sel % 9) / 3 == c / 3))
                        val bg = when {
                            i == sel -> MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
                            same -> MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
                            related -> MaterialTheme.colorScheme.surfaceVariant
                            else -> MaterialTheme.colorScheme.surface
                        }
                        Box(Modifier.weight(1f).fillMaxHeight().background(bg)
                            .border(width = 0.5.dp, color = line.copy(alpha = 0.3f))
                            .then(if (c % 3 == 2 && c < 8) Modifier.padding(end = 1.5.dp) else Modifier)
                            .clickable { sel = i }, contentAlignment = Alignment.Center) {
                            if (grid[i] != 0) Text(grid[i].toString(), fontSize = 20.sp,
                                fontWeight = if (puzzle[i] != 0) FontWeight.Bold else FontWeight.Normal,
                                color = when { conflict(i) -> Color(0xFFE53935); puzzle[i] != 0 -> MaterialTheme.colorScheme.onSurface; else -> MaterialTheme.colorScheme.primary })
                        }
                    }
                }
            }
            Canvas(Modifier.fillMaxSize()) {
                for (k in 1..2) {
                    drawLine(line, Offset(size.width * k / 3, 0f), Offset(size.width * k / 3, size.height), 5f)
                    drawLine(line, Offset(0f, size.height * k / 3), Offset(size.width, size.height * k / 3), 5f)
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for (v in 1..9) Button(onClick = { put(v) }, Modifier.weight(1f), contentPadding = PaddingValues(0.dp)) { Text("$v", fontSize = 18.sp) }
        }
        TextButton(onClick = { put(0) }, Modifier.align(Alignment.CenterHorizontally)) { Icon(Icons.AutoMirrored.Filled.Backspace, null); Spacer(Modifier.width(4.dp)); Text("Wyczyść pole") }
    }
}

// ============================ piętnastka ============================

@Composable
fun FifteenGame(env: GameEnv) {
    val b = remember { mutableStateListOf(*Puzzles.fifteen(env.seed).toTypedArray()) }
    var moves by remember { mutableIntStateOf(0) }
    var over by remember { mutableStateOf(false) }
    fun tap(i: Int) {
        if (over) return
        val blank = b.indexOf(0)
        val r = i / 4; val c = i % 4; val br = blank / 4; val bc = blank % 4
        if (r != br && c != bc) return
        // przesuwa cały rząd/kolumnę w stronę pustego pola
        var cur = blank
        val step = when { r == br && c < bc -> -1; r == br && c > bc -> 1; c == bc && r < br -> -4; else -> 4 }
        while (cur != i) { b[cur] = b[cur + step]; cur += step; moves++ }
        b[i] = 0
        if ((0 until 15).all { b[it] == it + 1 }) { over = true; env.finish(moves.toDouble()) }
    }
    Column(Modifier.fillMaxSize()) {
        GameHud("$moves ruchów")
        SquareBoard(Modifier.weight(1f)) {
            Column(Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp)).background(Color(0xFF37474F)).padding(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                for (r in 0 until 4) Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (c in 0 until 4) {
                        val v = b[r * 4 + c]
                        Box(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(8.dp))
                            .background(if (v == 0) Color.Transparent else if (v == r * 4 + c + 1) Color(0xFF66BB6A) else Color(0xFFFFCC80))
                            .clickable(enabled = v != 0) { tap(r * 4 + c) }, contentAlignment = Alignment.Center) {
                            if (v != 0) Text("$v", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Color(0xFF3E2723))
                        }
                    }
                }
            }
        }
        Text("Dotknij kafelka w tym samym rzędzie lub kolumnie co puste pole", Modifier.fillMaxWidth().padding(8.dp), textAlign = TextAlign.Center, style = MaterialTheme.typography.labelMedium)
    }
}

// ============================ zgaś światła ============================

@Composable
fun LightsGame(env: GameEnv) {
    val n = 5
    val b = remember { mutableStateListOf(*Puzzles.lights(env.seed, n).toTypedArray()) }
    var moves by remember { mutableIntStateOf(0) }
    var over by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        GameHud("$moves ruchów", "świeci: ${b.count { it }}")
        SquareBoard(Modifier.weight(1f)) {
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for (r in 0 until n) Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (c in 0 until n) {
                        val i = r * n + c
                        Box(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(10.dp))
                            .background(if (b[i]) Color(0xFFFFEB3B) else Color(0xFF37474F))
                            .clickable(enabled = !over) {
                                val arr = b.toBooleanArray(); Puzzles.lightsToggle(arr, n, i); arr.forEachIndexed { k, v -> b[k] = v }
                                moves++
                                if (b.none { it }) { over = true; env.finish(moves.toDouble()) }
                            }, contentAlignment = Alignment.Center) {
                            if (b[i]) Icon(Icons.Default.Lightbulb, null, tint = Color(0xFFFF8F00))
                        }
                    }
                }
            }
        }
        Text("Dotknięcie przełącza lampkę i jej sąsiadów", Modifier.fillMaxWidth().padding(8.dp), textAlign = TextAlign.Center, style = MaterialTheme.typography.labelMedium)
    }
}

// ============================ zalewanie ============================

private val FLOOD_COLORS = listOf(0xFFE53935, 0xFF1E88E5, 0xFF43A047, 0xFFFDD835, 0xFF8E24AA, 0xFFFB8C00).map { Color(it) }

@Composable
fun FloodGame(env: GameEnv) {
    val n = 12
    val b = remember { mutableStateListOf(*Puzzles.flood(env.seed, n).toTypedArray()) }
    var moves by remember { mutableIntStateOf(0) }
    var over by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        GameHud("$moves ruchów", "cel: jeden kolor")
        SquareBoard(Modifier.weight(1f)) {
            Canvas(Modifier.fillMaxSize().clip(RoundedCornerShape(8.dp))) {
                val cs = size.width / n
                for (i in 0 until n * n) drawRect(FLOOD_COLORS[b[i]], Offset((i % n) * cs, (i / n) * cs), Size(cs + 1, cs + 1))
            }
        }
        Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            FLOOD_COLORS.forEachIndexed { k, c ->
                Box(Modifier.size(48.dp).clip(CircleShape).background(c).clickable(enabled = !over && b[0] != k) {
                    val arr = b.toIntArray(); Puzzles.floodFill(arr, n, k); arr.forEachIndexed { i, v -> b[i] = v }
                    moves++
                    if (b.all { it == b[0] }) { over = true; env.finish(moves.toDouble()) }
                })
            }
        }
    }
}

// ============================ labirynt ============================

@Composable
fun MazeGame(env: GameEnv) {
    val w = 10; val h = 15
    val cells = remember { Puzzles.maze(env.seed, w, h) }
    var pos by remember { mutableIntStateOf(0) }
    var started by remember { mutableStateOf(false) }
    var over by remember { mutableStateOf(false) }
    val trail = remember { mutableStateListOf(0) }
    val ms = rememberStopwatch(started && !over)
    fun slide(bit: Int, dx: Int, dy: Int) {
        if (over) return
        started = true
        var p = pos
        while (cells[p] and bit != 0) {
            p += dy * w + dx
            trail.add(p)
            // zatrzymanie na skrzyżowaniu
            val exits = listOf(1, 2, 4, 8).count { cells[p] and it != 0 }
            if (exits != 2 || p == w * h - 1) break
            val straight = cells[p] and bit != 0
            if (!straight) break
        }
        pos = p
        if (pos == w * h - 1) { over = true; env.finish(ms.value.coerceAtLeast(1).toDouble()) }
    }
    Column(Modifier.fillMaxSize()) {
        GameHud(GameCatalog.fmtTime(ms.value.toDouble()), "do wyjścia ↘")
        Box(Modifier.weight(1f).fillMaxWidth().padding(10.dp).pointerInput(Unit) {
            var total = Offset.Zero
            detectDragGestures(onDragStart = { total = Offset.Zero }) { ch, d ->
                ch.consume(); total += d
                if (total.getDistance() > 36f) {
                    if (abs(total.x) > abs(total.y)) { if (total.x > 0) slide(2, 1, 0) else slide(8, -1, 0) } else { if (total.y > 0) slide(4, 0, 1) else slide(1, 0, -1) }
                    total = Offset.Zero
                }
            }
        }, contentAlignment = Alignment.Center) {
            val wall = MaterialTheme.colorScheme.onSurface
            val path = MaterialTheme.colorScheme.primary
            Canvas(Modifier.widthIn(max = 520.dp).aspectRatio(w.toFloat() / h).fillMaxSize()) {
                val cs = size.width / w
                drawRect(Color(0xFF66BB6A), Offset((w - 1) * cs, (h - 1) * cs), Size(cs, cs))
                for (i in 1 until trail.size) {
                    val a = trail[i - 1]; val b = trail[i]
                    drawLine(path.copy(alpha = 0.35f), Offset((a % w + 0.5f) * cs, (a / w + 0.5f) * cs), Offset((b % w + 0.5f) * cs, (b / w + 0.5f) * cs), cs * 0.3f)
                }
                for (i in 0 until w * h) {
                    val x = (i % w) * cs; val y = (i / w) * cs; val c = cells[i]
                    if (c and 1 == 0) drawLine(wall, Offset(x, y), Offset(x + cs, y), 4f)
                    if (c and 2 == 0) drawLine(wall, Offset(x + cs, y), Offset(x + cs, y + cs), 4f)
                    if (c and 4 == 0) drawLine(wall, Offset(x, y + cs), Offset(x + cs, y + cs), 4f)
                    if (c and 8 == 0) drawLine(wall, Offset(x, y), Offset(x, y + cs), 4f)
                }
                drawCircle(Color(0xFFFF7043), cs * 0.32f, Offset((pos % w + 0.5f) * cs, (pos / w + 0.5f) * cs))
            }
        }
        Text("Przesuń palcem — kulka jedzie do najbliższego rozwidlenia", Modifier.fillMaxWidth().padding(8.dp), textAlign = TextAlign.Center, style = MaterialTheme.typography.labelMedium)
    }
}

// ============================ wieże Hanoi ============================

@Composable
fun HanoiGame(env: GameEnv) {
    val n = 5
    val pegs = remember { mutableStateListOf((n downTo 1).toList(), emptyList(), emptyList()) }
    var sel by remember { mutableIntStateOf(-1) }
    var moves by remember { mutableIntStateOf(0) }
    var over by remember { mutableStateOf(false) }
    fun tap(p: Int) {
        if (over) return
        if (sel < 0) { if (pegs[p].isNotEmpty()) sel = p; return }
        if (sel == p) { sel = -1; return }
        val disk = pegs[sel].last()
        if (pegs[p].isEmpty() || pegs[p].last() > disk) {
            pegs[p] = pegs[p] + disk; pegs[sel] = pegs[sel].dropLast(1); moves++
            if (pegs[2].size == n) { over = true; env.finish(moves.toDouble()) }
        }
        sel = -1
    }
    Column(Modifier.fillMaxSize()) {
        GameHud("$moves ruchów", "najlepiej: ${(1 shl n) - 1}")
        Row(Modifier.weight(1f).fillMaxWidth().padding(12.dp)) {
            for (p in 0 until 3) {
                Box(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(12.dp))
                    .background(if (sel == p) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f) else Color.Transparent)
                    .clickable { tap(p) }, contentAlignment = Alignment.BottomCenter) {
                    Box(Modifier.width(10.dp).fillMaxHeight(0.7f).background(Color(0xFF8D6E63)))
                    Column(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Bottom) {
                        for (disk in pegs[p].reversed()) {
                            Box(Modifier.fillMaxWidth(0.25f + disk * 0.14f).height(26.dp).padding(vertical = 2.dp).clip(RoundedCornerShape(8.dp))
                                .background(Color.hsv(disk * 55f % 360f, 0.6f, 0.9f)))
                        }
                    }
                }
            }
        }
        Text("Dotknij słupka, z którego bierzesz krążek, a potem docelowego", Modifier.fillMaxWidth().padding(8.dp), textAlign = TextAlign.Center, style = MaterialTheme.typography.labelMedium)
    }
}

// ============================ samotnik ============================

@Composable
fun PegGame(env: GameEnv) {
    val n = 7
    fun valid(i: Int): Boolean { val r = i / n; val c = i % n; return !((r < 2 || r > 4) && (c < 2 || c > 4)) }
    val b = remember { mutableStateListOf(*Array(n * n) { valid(it) && it != 24 }) }
    var sel by remember { mutableIntStateOf(-1) }
    var over by remember { mutableStateOf(false) }
    fun jumps(i: Int): List<Pair<Int, Int>> {
        if (!b[i]) return emptyList()
        val r = i / n; val c = i % n
        return listOf(0 to 1, 0 to -1, 1 to 0, -1 to 0).mapNotNull { (dr, dc) ->
            val mr = r + dr; val mc = c + dc; val tr = r + 2 * dr; val tc = c + 2 * dc
            if (tr !in 0 until n || tc !in 0 until n) return@mapNotNull null
            val mid = mr * n + mc; val to = tr * n + tc
            if (valid(to) && b[mid] && !b[to]) to to mid else null
        }
    }
    fun finishIfStuck() {
        if ((0 until n * n).none { valid(it) && jumps(it).isNotEmpty() }) { over = true; env.finish(b.count { it }.toDouble()) }
    }
    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            GameHud("${b.count { it }} pionków", "", Modifier.weight(1f))
            EndButton { if (!over) { over = true; env.finish(b.count { it }.toDouble()) } }
        }
        SquareBoard(Modifier.weight(1f)) {
            Column(Modifier.fillMaxSize()) {
                for (r in 0 until n) Row(Modifier.weight(1f)) {
                    for (c in 0 until n) {
                        val i = r * n + c
                        Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                            if (valid(i)) {
                                val targets = if (sel >= 0) jumps(sel).map { it.first } else emptyList()
                                Box(Modifier.fillMaxSize(0.78f).clip(CircleShape)
                                    .background(when { i == sel -> Color(0xFFFF7043); b[i] -> Color(0xFF5D4037); i in targets -> Color(0xFF81C784); else -> MaterialTheme.colorScheme.surfaceVariant })
                                    .clickable(enabled = !over) {
                                        if (b[i]) sel = if (sel == i) -1 else i
                                        else if (sel >= 0) jumps(sel).firstOrNull { it.first == i }?.let { (to, mid) -> b[sel] = false; b[mid] = false; b[to] = true; sel = -1; finishIfStuck() }
                                    })
                            }
                        }
                    }
                }
            }
        }
        Text("Wybierz pionek i przeskocz nim nad sąsiadem na wolne pole", Modifier.fillMaxWidth().padding(8.dp), textAlign = TextAlign.Center, style = MaterialTheme.typography.labelMedium)
    }
}

// ============================ magazynier (Sokoban) ============================

@Composable
fun SokobanGame(env: GameEnv) {
    val level = remember { (abs(env.seed) % Puzzles.SOKOBAN.size).toInt() }
    val start = remember { Puzzles.sokoban(level) }
    var s by remember { mutableStateOf(start) }
    val history = remember { mutableStateListOf<Puzzles.Soko>() }
    var moves by remember { mutableIntStateOf(0) }
    var over by remember { mutableStateOf(false) }
    fun go(dx: Int, dy: Int) {
        if (over) return
        val n = Puzzles.sokoMove(s, dx, dy) ?: return
        history.add(s); s = n; moves++
        if (n.solved) { over = true; env.finish(moves.toDouble()) }
    }
    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            GameHud("$moves ruchów", "poziom ${level + 1}", Modifier.weight(1f))
            IconButton(onClick = { if (history.isNotEmpty() && !over) { s = history.removeAt(history.lastIndex); moves++ } }) { Icon(Icons.AutoMirrored.Filled.Undo, "Cofnij") }
            IconButton(onClick = { if (!over) { history.clear(); s = start; moves = 0 } }) { Icon(Icons.Default.RestartAlt, "Od nowa") }
        }
        Box(Modifier.weight(1f).fillMaxWidth().padding(10.dp).pointerInput(Unit) {
            var total = Offset.Zero
            detectDragGestures(onDragStart = { total = Offset.Zero }) { ch, d ->
                ch.consume(); total += d
                if (total.getDistance() > 50f) {
                    if (abs(total.x) > abs(total.y)) go(if (total.x > 0) 1 else -1, 0) else go(0, if (total.y > 0) 1 else -1)
                    total = Offset.Zero
                }
            }
        }, contentAlignment = Alignment.Center) {
            Canvas(Modifier.widthIn(max = 520.dp).aspectRatio(s.w.toFloat() / s.h).fillMaxSize()) {
                val cs = size.width / s.w
                for (i in 0 until s.w * s.h) {
                    val o = Offset((i % s.w) * cs, (i / s.w) * cs)
                    when {
                        i in s.walls -> drawRoundRect(Color(0xFF6D4C41), o, Size(cs, cs), CornerRadius(4f))
                        else -> drawRect(Color(0xFFEFEBE9), o, Size(cs, cs))
                    }
                    if (i in s.goals) drawCircle(Color(0xFFE57373), cs * 0.18f, o + Offset(cs / 2, cs / 2))
                    if (i in s.boxes) drawRoundRect(if (i in s.goals) Color(0xFF66BB6A) else Color(0xFFFFB74D), o + Offset(cs * 0.1f, cs * 0.1f), Size(cs * 0.8f, cs * 0.8f), CornerRadius(cs * 0.12f))
                }
                drawCircle(Color(0xFF1E88E5), cs * 0.34f, Offset((s.player % s.w + 0.5f) * cs, (s.player / s.w + 0.5f) * cs))
            }
        }
        // przyciski kierunków
        Column(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            FilledTonalIconButton(onClick = { go(0, -1) }) { Icon(Icons.Default.KeyboardArrowUp, "Góra") }
            Row {
                FilledTonalIconButton(onClick = { go(-1, 0) }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Lewo") }
                Spacer(Modifier.width(48.dp))
                FilledTonalIconButton(onClick = { go(1, 0) }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Prawo") }
            }
            FilledTonalIconButton(onClick = { go(0, 1) }) { Icon(Icons.Default.KeyboardArrowDown, "Dół") }
        }
    }
}

// ============================ łamacz kodów ============================

private val CODE_COLORS = listOf(0xFFE53935, 0xFF1E88E5, 0xFF43A047, 0xFFFDD835, 0xFF8E24AA, 0xFFFB8C00).map { Color(it) }

@Composable
fun MastermindGame(env: GameEnv) {
    val secret = remember { seeded(env.seed, "mm").let { r -> List(4) { r.nextInt(6) } } }
    val tries = remember { mutableStateListOf<Pair<List<Int>, Pair<Int, Int>>>() }
    val cur = remember { mutableStateListOf<Int>() }
    var over by remember { mutableStateOf(false) }
    fun check() {
        if (cur.size < 4 || over) return
        val g = cur.toList()
        val black = g.indices.count { g[it] == secret[it] }
        val common = (0 until 6).sumOf { c -> minOf(g.count { it == c }, secret.count { it == c }) }
        tries.add(g to (black to common - black)); cur.clear()
        if (black == 4) { over = true; env.finish(tries.size.toDouble()) }
        else if (tries.size >= 10) { over = true; env.fail("Kod to był: " + secret.joinToString(" ") { listOf("czerwony", "niebieski", "zielony", "żółty", "fioletowy", "pomarańczowy")[it] }) }
    }
    @Composable
    fun Peg(c: Int?, size: androidx.compose.ui.unit.Dp = 28.dp, onClick: (() -> Unit)? = null) {
        Box(Modifier.size(size).clip(CircleShape).background(c?.let { CODE_COLORS[it] } ?: MaterialTheme.colorScheme.surfaceVariant)
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier))
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        GameHud("próba ${tries.size + 1}/10", "")
        Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            tries.forEach { (g, fb) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    g.forEach { Peg(it); Spacer(Modifier.width(6.dp)) }
                    Spacer(Modifier.width(12.dp))
                    repeat(fb.first) { Box(Modifier.padding(2.dp).size(12.dp).clip(CircleShape).background(MaterialTheme.colorScheme.onSurface)) }
                    repeat(fb.second) { Box(Modifier.padding(2.dp).size(12.dp).clip(CircleShape).border(2.dp, MaterialTheme.colorScheme.onSurface, CircleShape)) }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                for (k in 0 until 4) { Peg(cur.getOrNull(k), 32.dp) { if (k < cur.size) cur.removeAt(k) }; Spacer(Modifier.width(6.dp)) }
            }
        }
        Text("● = dobry kolor na dobrym miejscu, ○ = dobry kolor w złym miejscu", style = MaterialTheme.typography.labelSmall)
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            for (c in 0 until 6) Peg(c, 40.dp) { if (cur.size < 4 && !over) cur.add(c) }
        }
        Button(onClick = { check() }, enabled = cur.size == 4 && !over, modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) { Text("Sprawdź") }
    }
}

// ============================ klejnoty ============================

private val GEMS = listOf(0xFFE53935, 0xFF1E88E5, 0xFF43A047, 0xFFFDD835, 0xFF8E24AA, 0xFFFF7043).map { Color(it) }

@Composable
fun Match3Game(env: GameEnv) {
    val w = 7; val h = 8
    val rnd = remember { seeded(env.seed, "match3") }
    val b = remember {
        val arr = IntArray(w * h)
        for (i in arr.indices) {
            var g: Int
            do { g = rnd.nextInt(6) } while ((i % w >= 2 && arr[i - 1] == g && arr[i - 2] == g) || (i / w >= 2 && arr[i - w] == g && arr[i - 2 * w] == g))
            arr[i] = g
        }
        mutableStateListOf(*arr.toTypedArray())
    }
    var sel by remember { mutableIntStateOf(-1) }
    var score by remember { mutableIntStateOf(0) }
    var movesLeft by remember { mutableIntStateOf(25) }
    var busy by remember { mutableStateOf(false) }
    var over by remember { mutableStateOf(false) }
    fun matches(): Set<Int> {
        val out = HashSet<Int>()
        for (r in 0 until h) for (c in 0 until w - 2) { val i = r * w + c; if (b[i] >= 0 && b[i] == b[i + 1] && b[i] == b[i + 2]) { out += i; out += i + 1; out += i + 2 } }
        for (r in 0 until h - 2) for (c in 0 until w) { val i = r * w + c; if (b[i] >= 0 && b[i] == b[i + w] && b[i] == b[i + 2 * w]) { out += i; out += i + w; out += i + 2 * w } }
        return out
    }
    var pending by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    LaunchedEffect(pending) {
        val (a, c) = pending ?: return@LaunchedEffect
        busy = true
        val t = b[a]; b[a] = b[c]; b[c] = t
        delay(150)
        var m = matches()
        if (m.isEmpty()) { val t2 = b[a]; b[a] = b[c]; b[c] = t2; busy = false; pending = null; return@LaunchedEffect }
        movesLeft--
        var combo = 1
        while (m.isNotEmpty()) {
            score += m.size * 10 * combo
            m.forEach { b[it] = -1 }
            delay(180)
            for (c2 in 0 until w) {
                val col = (0 until h).map { b[it * w + c2] }.filter { it >= 0 }
                val fill = List(h - col.size) { rnd.nextInt(6) } + col
                for (r in 0 until h) b[r * w + c2] = fill[r]
            }
            delay(150)
            m = matches(); combo++
        }
        busy = false; pending = null
        if (movesLeft <= 0) { over = true; env.finish(score.toDouble()) }
    }
    fun tap(i: Int) {
        if (busy || over) return
        if (sel < 0) { sel = i; return }
        val adj = (abs(i - sel) == 1 && i / w == sel / w) || abs(i - sel) == w
        if (adj) pending = sel to i
        sel = if (adj) -1 else i
    }
    Column(Modifier.fillMaxSize()) {
        GameHud("$score pkt", "ruchy: $movesLeft")
        Box(Modifier.weight(1f).fillMaxWidth().padding(8.dp), contentAlignment = Alignment.Center) {
            Column(Modifier.widthIn(max = 520.dp).aspectRatio(w.toFloat() / h)) {
                for (r in 0 until h) Row(Modifier.weight(1f)) {
                    for (c in 0 until w) {
                        val i = r * w + c
                        Box(Modifier.weight(1f).fillMaxHeight().padding(2.dp).clip(RoundedCornerShape(10.dp))
                            .background(if (i == sel) MaterialTheme.colorScheme.primary.copy(alpha = 0.3f) else MaterialTheme.colorScheme.surfaceVariant)
                            .clickable { tap(i) }, contentAlignment = Alignment.Center) {
                            if (b[i] >= 0) Icon(Icons.Default.Diamond, null, tint = GEMS[b[i]], modifier = Modifier.fillMaxSize(0.8f))
                        }
                    }
                }
            }
        }
        Text("Dotknij dwóch sąsiednich klejnotów, by je zamienić", Modifier.fillMaxWidth().padding(8.dp), textAlign = TextAlign.Center, style = MaterialTheme.typography.labelMedium)
    }
}

// ============================ kulki ============================

private val BALL_COLORS = listOf(0xFFE53935, 0xFF1E88E5, 0xFF43A047, 0xFFFDD835, 0xFF8E24AA, 0xFF00ACC1, 0xFF6D4C41).map { Color(it) }

@Composable
fun LinesGame(env: GameEnv) {
    val n = 9
    val rnd = remember { seeded(env.seed, "lines") }
    val b = remember { mutableStateListOf(*Array(n * n) { -1 }) }
    val next = remember { mutableStateListOf(rnd.nextInt(7), rnd.nextInt(7), rnd.nextInt(7)) }
    var sel by remember { mutableIntStateOf(-1) }
    var score by remember { mutableIntStateOf(0) }
    var over by remember { mutableStateOf(false) }
    fun clearLines(from: Int): Int {
        val c = b[from]; if (c < 0) return 0
        val r0 = from / n; val c0 = from % n
        val remove = HashSet<Int>()
        for ((dr, dc) in listOf(0 to 1, 1 to 0, 1 to 1, 1 to -1)) {
            val line = mutableListOf(from)
            for (s in listOf(1, -1)) {
                var r = r0 + dr * s; var cc = c0 + dc * s
                while (r in 0 until n && cc in 0 until n && b[r * n + cc] == c) { line.add(r * n + cc); r += dr * s; cc += dc * s }
            }
            if (line.size >= 5) remove.addAll(line)
        }
        remove.forEach { b[it] = -1 }
        return remove.size
    }
    fun spawn() {
        for (k in 0 until 3) {
            val free = (0 until n * n).filter { b[it] < 0 }
            if (free.isEmpty()) break
            val i = free[rnd.nextInt(free.size)]
            b[i] = next[k]
            score += clearLines(i) * 2
        }
        for (k in 0 until 3) next[k] = rnd.nextInt(7)
        if (b.none { it < 0 }) { over = true; env.finish(score.toDouble()) }
    }
    LaunchedEffect(Unit) { if (b.all { it < 0 }) spawn() }
    fun reachable(from: Int, to: Int): Boolean {
        val seen = BooleanArray(n * n); val q = ArrayDeque<Int>(); q.add(from); seen[from] = true
        while (q.isNotEmpty()) {
            val p = q.removeFirst(); if (p == to) return true
            val r = p / n; val c = p % n
            for (x in listOfNotNull(if (r > 0) p - n else null, if (r < n - 1) p + n else null, if (c > 0) p - 1 else null, if (c < n - 1) p + 1 else null))
                if (!seen[x] && b[x] < 0) { seen[x] = true; q.add(x) }
        }
        return false
    }
    fun tap(i: Int) {
        if (over) return
        if (b[i] >= 0) { sel = i; return }
        if (sel < 0 || !reachable(sel, i)) return
        b[i] = b[sel]; b[sel] = -1; sel = -1
        val got = clearLines(i)
        if (got > 0) score += got * 2 else spawn()
    }
    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            GameHud("$score pkt", "", Modifier.weight(1f))
            Text("następne: ", style = MaterialTheme.typography.labelMedium)
            next.forEach { Box(Modifier.padding(2.dp).size(16.dp).clip(CircleShape).background(BALL_COLORS[it])) }
            Spacer(Modifier.width(8.dp))
            EndButton { if (!over) { over = true; env.finish(score.toDouble()) } }
        }
        SquareBoard(Modifier.weight(1f)) {
            Column(Modifier.fillMaxSize().clip(RoundedCornerShape(8.dp)).background(Color(0xFF9E9E9E)).padding(2.dp)) {
                for (r in 0 until n) Row(Modifier.weight(1f)) {
                    for (c in 0 until n) {
                        val i = r * n + c
                        Box(Modifier.weight(1f).fillMaxHeight().padding(1.dp).background(if (i == sel) Color(0xFFFFF59D) else Color(0xFFE0E0E0)).clickable { tap(i) },
                            contentAlignment = Alignment.Center) {
                            if (b[i] >= 0) Box(Modifier.fillMaxSize(0.75f).clip(CircleShape).background(BALL_COLORS[b[i]]))
                        }
                    }
                }
            }
        }
        Text("Wybierz kulkę i miejsce, do którego da się dojść. 5 w linii znika.", Modifier.fillMaxWidth().padding(8.dp), textAlign = TextAlign.Center, style = MaterialTheme.typography.labelMedium)
    }
}
