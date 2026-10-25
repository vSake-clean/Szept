package pl.szept.ui.games

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import pl.szept.App
import pl.szept.core.Battleship
import pl.szept.core.Checkers
import pl.szept.core.Chess
import pl.szept.core.Connect4
import pl.szept.core.DotsBoxes
import pl.szept.core.Duel
import pl.szept.core.Duels
import pl.szept.core.GameCatalog
import pl.szept.core.Gomoku
import pl.szept.core.Mancala
import pl.szept.core.Morris
import pl.szept.core.Nim
import pl.szept.core.Reversi
import pl.szept.core.TttDuel
import pl.szept.data.Message
import kotlin.math.hypot

/** Kolory graczy: A i B. */
val SIDE_COLORS = listOf(Color(0xFFE53935), Color(0xFF1E88E5))
private val SIDE_NAMES = mapOf(
    "ttt" to listOf("✕", "◯"), "c4" to listOf("czerwone", "żółte"), "gomoku" to listOf("czarne", "białe"), "reversi" to listOf("czarne", "białe"),
    "checkers" to listOf("jasne", "ciemne"), "chess" to listOf("białe", "czarne"), "dots" to listOf("czerwone", "niebieskie"),
    "nim" to listOf("", ""), "mancala" to listOf("dół", "góra"), "morris" to listOf("czerwone", "niebieskie"), "battleship" to listOf("", ""),
)

fun sideName(id: String, side: Int) = SIDE_NAMES[id]?.getOrNull(side).orEmpty()

// ============================ plansze ============================

/** Plansza gry we dwoje. [mySide] — 0/1 albo -1 (widz), [enabled] — czy mogę teraz wykonać ruch. */
@Composable
fun DuelBoard(id: String, state: Any, mySide: Int, enabled: Boolean, onMove: (String) -> Unit) {
    when (id) {
        "ttt" -> TttBoard(state as TttDuel.S, enabled, onMove)
        "c4" -> C4Board(state as Connect4.S, enabled, onMove)
        "gomoku" -> GomokuBoard(state as Gomoku.S, enabled, onMove)
        "reversi" -> ReversiBoard(state as Reversi.S, mySide, enabled, onMove)
        "checkers" -> CheckersBoard(state as Checkers.S, mySide, enabled, onMove)
        "chess" -> ChessBoard(state as Chess.S, mySide, enabled, onMove)
        "dots" -> DotsBoard(state as DotsBoxes.S, enabled, onMove)
        "nim" -> NimBoard(state as Nim.S, enabled, onMove)
        "mancala" -> MancalaBoard(state as Mancala.S, mySide, enabled, onMove)
        "morris" -> MorrisBoard(state as Morris.S, mySide, enabled, onMove)
        "battleship" -> BattleshipBoard(state as Battleship.S, mySide, enabled, onMove)
    }
}

/** Dodatkowa informacja o stanie gry (wynik, szach, faza). */
fun duelInfo(id: String, state: Any): String = when (state) {
    is Reversi.S -> "● ${state.count(0)} : ${state.count(1)} ○" + if (state.passed) " · pas" else ""
    is DotsBoxes.S -> "${state.score[0]} : ${state.score[1]}"
    is Mancala.S -> "${state.p[6]} : ${state.p[13]}"
    is Nim.S -> "zostało ${state.left} " + GameCatalog.plural(state.left.toLong(), "zapałka", "zapałki", "zapałek")
    is Chess.S -> when { state.reason.isNotEmpty() -> state.reason; state.check -> "Szach!"; else -> "" }
    is Morris.S -> "w ręce: ${state.hand[0]} / ${state.hand[1]}"
    is Battleship.S -> if (!state.ready) "ustawianie floty" else "trafienia ${state.hits(0).size} : ${state.hits(1).size}"
    else -> ""
}

@Composable
private fun SquareCanvasBox(content: @Composable BoxScope.() -> Unit) {
    Box(Modifier.fillMaxWidth().aspectRatio(1f), content = content)
}

@Composable
fun TttBoard(s: TttDuel.S, enabled: Boolean, onMove: (String) -> Unit) {
    val line = MaterialTheme.colorScheme.primary
    Column(Modifier.fillMaxWidth().aspectRatio(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (r in 0..2) Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for (c in 0..2) {
                val i = r * 3 + c
                val can = enabled && s.b[i] == 0
                Box(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(10.dp))
                    .background(if (i in s.line) line.copy(alpha = 0.3f) else MaterialTheme.colorScheme.surfaceVariant)
                    .border(1.dp, if (can) line.copy(alpha = 0.6f) else Color.Transparent, RoundedCornerShape(10.dp))
                    .clickable(enabled = can) { onMove(i.toString()) }
                    .semantics { contentDescription = "Pole ${i + 1}" + when (s.b[i]) { 1 -> ": krzyżyk"; 2 -> ": kółko"; else -> "" } }) {
                    Canvas(Modifier.fillMaxSize().padding(14.dp)) {
                        when (s.b[i]) {
                            1 -> { drawLine(SIDE_COLORS[0], Offset.Zero, Offset(size.width, size.height), 10f); drawLine(SIDE_COLORS[0], Offset(size.width, 0f), Offset(0f, size.height), 10f) }
                            2 -> drawCircle(SIDE_COLORS[1], size.minDimension / 2 - 5, style = Stroke(10f))
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun C4Board(s: Connect4.S, enabled: Boolean, onMove: (String) -> Unit) {
    val W = Connect4.W; val H = Connect4.H
    Box(Modifier.fillMaxWidth().aspectRatio(W.toFloat() / H)) {
        Canvas(Modifier.fillMaxSize().clip(RoundedCornerShape(10.dp)).pointerInput(enabled) {
            detectTapGestures { p -> if (enabled) { val c = (p.x / (size.width / W)).toInt().coerceIn(0, W - 1); if (Connect4.dropRow(s.b, c) >= 0) onMove(c.toString()) } }
        }) {
            drawRect(Color(0xFF1565C0))
            val cs = size.width / W
            for (i in 0 until W * H) {
                val center = Offset((i % W + 0.5f) * cs, (i / W + 0.5f) * cs)
                val col = when (s.b[i]) { 1 -> Color(0xFFE53935); 2 -> Color(0xFFFDD835); else -> Color(0xFFE3F2FD) }
                drawCircle(col, cs * 0.4f, center)
                if (i == s.last) drawCircle(Color.White, cs * 0.12f, center)
                if (i in s.line) drawCircle(Color.White, cs * 0.42f, center, style = Stroke(5f))
            }
        }
    }
}

@Composable
fun GomokuBoard(s: Gomoku.S, enabled: Boolean, onMove: (String) -> Unit) {
    val N = Gomoku.N
    SquareCanvasBox {
        Canvas(Modifier.fillMaxSize().clip(RoundedCornerShape(8.dp)).pointerInput(enabled, s) {
            detectTapGestures { p ->
                if (!enabled) return@detectTapGestures
                val cs = size.width / N.toFloat()
                val c = (p.x / cs).toInt().coerceIn(0, N - 1); val r = (p.y / cs).toInt().coerceIn(0, N - 1)
                if (s.b[r * N + c] == 0) onMove((r * N + c).toString())
            }
        }) {
            drawRect(Color(0xFFE8C07D))
            val cs = size.width / N
            for (k in 0 until N) {
                drawLine(Color(0xFF6D4C41), Offset((k + 0.5f) * cs, cs / 2), Offset((k + 0.5f) * cs, size.height - cs / 2), 2f)
                drawLine(Color(0xFF6D4C41), Offset(cs / 2, (k + 0.5f) * cs), Offset(size.width - cs / 2, (k + 0.5f) * cs), 2f)
            }
            for (i in 0 until N * N) if (s.b[i] != 0) {
                val center = Offset((i % N + 0.5f) * cs, (i / N + 0.5f) * cs)
                drawCircle(if (s.b[i] == 1) Color(0xFF212121) else Color.White, cs * 0.42f, center)
                if (i == s.last || i in s.line) drawCircle(Color(0xFFE53935), cs * 0.12f, center)
            }
        }
    }
}

@Composable
fun ReversiBoard(s: Reversi.S, mySide: Int, enabled: Boolean, onMove: (String) -> Unit) {
    val N = Reversi.N
    val hints = remember(s, enabled, mySide) { if (enabled && mySide >= 0) Reversi.legal(s.b, mySide).toSet() else emptySet() }
    SquareCanvasBox {
        Canvas(Modifier.fillMaxSize().clip(RoundedCornerShape(8.dp)).pointerInput(enabled, s) {
            detectTapGestures { p ->
                val cs = size.width / N.toFloat()
                val i = (p.y / cs).toInt().coerceIn(0, N - 1) * N + (p.x / cs).toInt().coerceIn(0, N - 1)
                if (i in hints) onMove(i.toString())
            }
        }) {
            drawRect(Color(0xFF2E7D32))
            val cs = size.width / N
            for (k in 1 until N) { drawLine(Color(0xFF1B5E20), Offset(k * cs, 0f), Offset(k * cs, size.height), 2f); drawLine(Color(0xFF1B5E20), Offset(0f, k * cs), Offset(size.width, k * cs), 2f) }
            for (i in 0 until N * N) {
                val center = Offset((i % N + 0.5f) * cs, (i / N + 0.5f) * cs)
                when (s.b[i]) {
                    1 -> drawCircle(Color(0xFF212121), cs * 0.4f, center)
                    2 -> drawCircle(Color.White, cs * 0.4f, center)
                    else -> if (i in hints) drawCircle(Color.White.copy(alpha = 0.3f), cs * 0.12f, center)
                }
                if (i == s.last) drawCircle(Color(0xFFE53935), cs * 0.08f, center)
            }
        }
    }
}

@Composable
fun CheckersBoard(s: Checkers.S, mySide: Int, enabled: Boolean, onMove: (String) -> Unit) {
    val flip = mySide == 1
    var path by remember(s) { mutableStateOf(listOf<Int>()) }
    val legal = remember(s, enabled) { if (enabled) Checkers.legal(s) else emptyList() }
    val cand = legal.filter { it.path.size >= path.size && it.path.take(path.size) == path }
    val nextSquares = if (path.isEmpty()) legal.map { it.path[0] }.toSet() else cand.mapNotNull { it.path.getOrNull(path.size) }.toSet()
    fun tap(i: Int) {
        if (!enabled) return
        if (i in nextSquares) {
            val np = path + i
            val done = legal.firstOrNull { it.path == np }
            if (done != null && legal.none { it.path.size > np.size && it.path.take(np.size) == np }) { onMove(done.toString()); path = emptyList() }
            else path = np
        } else if (path.size <= 1) path = if (i in legal.map { it.path[0] }) listOf(i) else emptyList()
    }
    Column(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(6.dp))) {
        for (vr in 0 until 8) Row(Modifier.weight(1f)) {
            for (vc in 0 until 8) {
                val r = if (flip) 7 - vr else vr; val c = if (flip) 7 - vc else vc
                val i = r * 8 + c
                val dark = (r + c) % 2 == 1
                val p = s.b[i]
                Box(Modifier.weight(1f).fillMaxHeight()
                    .background(when { i in path -> Color(0xFFFFB74D); i in s.last -> Color(0xFF9CCC65); dark -> Color(0xFF8D6E63); else -> Color(0xFFEFEBE9) })
                    .clickable(enabled = enabled && dark) { tap(i) }, contentAlignment = Alignment.Center) {
                    if (p != 0) {
                        val col = if (Checkers.owner(p) == 0) Color(0xFFFFF8E1) else Color(0xFF3E2723)
                        Box(Modifier.fillMaxSize(0.78f).clip(CircleShape).background(col).border(2.dp, Color.Black.copy(alpha = 0.4f), CircleShape), contentAlignment = Alignment.Center) {
                            if (Checkers.isKing(p)) Icon(Icons.Default.Star, null, tint = Color(0xFFFFC107), modifier = Modifier.fillMaxSize(0.6f))
                        }
                    } else if (enabled && i in nextSquares && path.isNotEmpty()) Box(Modifier.fillMaxSize(0.3f).clip(CircleShape).background(Color(0xFFFFEB3B).copy(alpha = 0.8f)))
                }
            }
        }
    }
}

@Composable
fun ChessBoard(s: Chess.S, mySide: Int, enabled: Boolean, onMove: (String) -> Unit) {
    val flip = mySide == 1
    var sel by remember(s) { mutableIntStateOf(-1) }
    var promo by remember(s) { mutableStateOf<Pair<Int, Int>?>(null) }
    val legal = remember(s, enabled) { if (enabled) Chess.legalMoves(s) else emptyList() }
    val targets = legal.filter { it.from == sel }.map { it.to }.toSet()
    val kingInCheck = if (s.check) s.b.indexOf(if (s.turn == 0) Chess.K else -Chess.K) else -1
    Column(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(6.dp))) {
        for (vr in 0 until 8) Row(Modifier.weight(1f)) {
            for (vc in 0 until 8) {
                val rank = if (flip) vr else 7 - vr; val file = if (flip) 7 - vc else vc
                val i = rank * 8 + file
                val light = (rank + file) % 2 == 1
                val p = s.b[i]
                Box(Modifier.weight(1f).fillMaxHeight()
                    .background(when {
                        i == sel -> Color(0xFFFFD54F)
                        i == kingInCheck -> Color(0xFFE57373)
                        i == s.lastFrom || i == s.lastTo -> if (light) Color(0xFFF6F669) else Color(0xFFBACA2B)
                        light -> Color(0xFFF0D9B5) else -> Color(0xFFB58863)
                    })
                    .clickable(enabled = enabled) {
                        when {
                            i in targets -> {
                                val from = sel
                                if (legal.any { it.from == from && it.to == i && it.promo != 0 }) promo = from to i
                                else { onMove(Chess.Mv(from, i).toString()); sel = -1 }
                            }
                            legal.any { it.from == i } -> sel = i
                            else -> sel = -1
                        }
                    }, contentAlignment = Alignment.Center) {
                    if (p != 0) Text(Chess.glyph(p), fontSize = 26.sp, color = if (p > 0) Color.White else Color.Black,
                        style = MaterialTheme.typography.headlineSmall.copy(shadow = androidx.compose.ui.graphics.Shadow(if (p > 0) Color.Black else Color.White, Offset(1f, 1f), 2f)))
                    if (i in targets) Box(Modifier.fillMaxSize(if (p != 0) 0.9f else 0.3f).clip(CircleShape)
                        .then(if (p != 0) Modifier.border(3.dp, Color(0xFF2E7D32).copy(alpha = 0.7f), CircleShape) else Modifier.background(Color(0xFF2E7D32).copy(alpha = 0.5f))))
                }
            }
        }
    }
    promo?.let { (from, to) ->
        AlertDialog(
            onDismissRequest = { promo = null },
            title = { Text("Promocja pionka") },
            text = {
                Row(horizontalArrangement = Arrangement.SpaceEvenly, modifier = Modifier.fillMaxWidth()) {
                    listOf(Chess.Q, Chess.R, Chess.B, Chess.N).forEach { k ->
                        Box(Modifier.size(56.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFFF0D9B5)).clickable { onMove(Chess.Mv(from, to, k).toString()); promo = null; sel = -1 },
                            contentAlignment = Alignment.Center) { Text(Chess.glyph(if (s.turn == 0) k else -k), fontSize = 32.sp, color = Color.Black) }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { promo = null }) { Text("Anuluj") } },
        )
    }
}

@Composable
fun DotsBoard(s: DotsBoxes.S, enabled: Boolean, onMove: (String) -> Unit) {
    val N = DotsBoxes.N
    val lineFree = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)
    val dot = MaterialTheme.colorScheme.onSurface
    SquareCanvasBox {
        Canvas(Modifier.fillMaxSize().padding(10.dp).pointerInput(enabled, s) {
            detectTapGestures { p ->
                if (!enabled) return@detectTapGestures
                val cs = size.width / N.toFloat()
                var best = -1; var bestD = Float.MAX_VALUE
                for (e in 0 until DotsBoxes.EDGES) {
                    if (s.edges[e] != 0) continue
                    val mid = if (e < DotsBoxes.H_EDGES) Offset((e % N + 0.5f) * cs, (e / N) * cs) else { val k = e - DotsBoxes.H_EDGES; Offset((k % (N + 1)) * cs, (k / (N + 1) + 0.5f) * cs) }
                    val d = hypot(p.x - mid.x, p.y - mid.y)
                    if (d < bestD) { bestD = d; best = e }
                }
                if (best >= 0 && bestD < cs * 0.45f) onMove(best.toString())
            }
        }) {
            val cs = size.width / N
            for (b in 0 until N * N) if (s.boxes[b] != 0) drawRect(SIDE_COLORS[s.boxes[b] - 1].copy(alpha = 0.3f), Offset((b % N) * cs, (b / N) * cs), Size(cs, cs))
            for (e in 0 until DotsBoxes.EDGES) {
                val (a, b) = if (e < DotsBoxes.H_EDGES) { val r = e / N; val c = e % N; Offset(c * cs, r * cs) to Offset((c + 1) * cs, r * cs) }
                else { val k = e - DotsBoxes.H_EDGES; val r = k / (N + 1); val c = k % (N + 1); Offset(c * cs, r * cs) to Offset(c * cs, (r + 1) * cs) }
                val owner = s.edges[e]
                drawLine(if (owner == 0) lineFree else SIDE_COLORS[owner - 1], a, b, if (owner == 0) 6f else 9f)
            }
            for (r in 0..N) for (c in 0..N) drawCircle(dot, 9f, Offset(c * cs, r * cs))
        }
    }
}

@Composable
fun NimBoard(s: Nim.S, enabled: Boolean, onMove: (String) -> Unit) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Canvas(Modifier.fillMaxWidth().height(90.dp)) {
            val n = s.left; val gap = size.width / 26f
            for (i in 0 until n) {
                val x = gap * (i + 1.5f)
                drawLine(Color(0xFFFFE0B2), Offset(x, size.height * 0.25f), Offset(x, size.height * 0.95f), 8f)
                drawCircle(Color(0xFFD32F2F), 9f, Offset(x, size.height * 0.22f))
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (k in 1..3) Button(onClick = { onMove(k.toString()) }, enabled = enabled && k <= s.left) { Text("Weź $k") }
        }
    }
}

@Composable
fun MancalaBoard(s: Mancala.S, mySide: Int, enabled: Boolean, onMove: (String) -> Unit) {
    val me = if (mySide == 1) 1 else 0
    val them = 1 - me
    @Composable
    fun Pit(idx: Int, mine: Boolean, k: Int, modifier: Modifier) {
        val can = enabled && mine && s.p[idx] > 0
        Box(modifier.aspectRatio(1f).padding(2.dp).clip(CircleShape)
            .background(if (idx == s.last) Color(0xFFFFCC80) else Color(0xFFD7CCC8))
            .border(2.dp, if (can) MaterialTheme.colorScheme.primary else Color.Transparent, CircleShape)
            .clickable(enabled = can) { onMove(k.toString()) }, contentAlignment = Alignment.Center) {
            Text("${s.p[idx]}", fontWeight = FontWeight.Bold, color = Color(0xFF3E2723))
        }
    }
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color(0xFF8D6E63)).padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(36.dp).height(90.dp).clip(RoundedCornerShape(18.dp)).background(Color(0xFFBCAAA4)), contentAlignment = Alignment.Center) {
            Text("${s.p[Mancala.store(them)]}", fontWeight = FontWeight.Bold, color = SIDE_COLORS[them])
        }
        Column(Modifier.weight(1f)) {
            Row { for (k in 5 downTo 0) Pit(Mancala.pit(them, k), false, k, Modifier.weight(1f)) }
            Row { for (k in 0..5) Pit(Mancala.pit(me, k), true, k, Modifier.weight(1f)) }
        }
        Box(Modifier.width(36.dp).height(90.dp).clip(RoundedCornerShape(18.dp)).background(Color(0xFFBCAAA4)), contentAlignment = Alignment.Center) {
            Text("${s.p[Mancala.store(me)]}", fontWeight = FontWeight.Bold, color = SIDE_COLORS[me])
        }
    }
}

private fun morrisPos(i: Int): Offset {
    val sq = i / 8; val k = i % 8
    val s = (3 - sq).toFloat()
    val (dx, dy) = when (k) { 0 -> -1 to -1; 1 -> 0 to -1; 2 -> 1 to -1; 3 -> 1 to 0; 4 -> 1 to 1; 5 -> 0 to 1; 6 -> -1 to 1; else -> -1 to 0 }
    return Offset(3 + dx * s, 3 + dy * s)
}

@Composable
fun MorrisBoard(s: Morris.S, mySide: Int, enabled: Boolean, onMove: (String) -> Unit) {
    var sel by remember(s) { mutableIntStateOf(-1) }
    var pending by remember(s) { mutableStateOf<String?>(null) }
    val moves = remember(s, enabled) { if (enabled && mySide >= 0) Morris.moves(s, mySide) else emptyList() }
    val removable = pending?.let { p -> moves.filter { it.startsWith(p + "x") }.map { it.substringAfter("x").toInt() }.toSet() } ?: emptySet()
    val line = MaterialTheme.colorScheme.onSurface
    fun tap(i: Int) {
        if (!enabled) return
        pending?.let { p -> if (i in removable) { onMove("${p}x$i"); pending = null; sel = -1 }; return }
        val base = when {
            s.hand[mySide] > 0 -> if (s.b[i] == 0) "p$i" else null
            s.b[i] == mySide + 1 -> { sel = i; null }
            sel >= 0 && s.b[i] == 0 -> "$sel-$i"
            else -> null
        } ?: return
        when {
            base in moves -> { onMove(base); sel = -1 }
            moves.any { it.startsWith(base + "x") } -> pending = base
        }
    }
    Column(Modifier.fillMaxWidth()) {
        SquareCanvasBox {
            Canvas(Modifier.fillMaxSize().padding(12.dp).pointerInput(enabled, s, sel, pending) {
                detectTapGestures { p ->
                    val u = size.width / 6f
                    val hit = (0 until 24).minBy { val q = morrisPos(it); hypot(q.x * u - p.x, q.y * u - p.y) }
                    val q = morrisPos(hit)
                    if (hypot(q.x * u - p.x, q.y * u - p.y) < u * 0.5f) tap(hit)
                }
            }) {
                val u = size.width / 6f
                for (sq in 0..2) {
                    val a = morrisPos(sq * 8); val b = morrisPos(sq * 8 + 4)
                    drawRect(line, Offset(a.x * u, a.y * u), Size((b.x - a.x) * u, (b.y - a.y) * u), style = Stroke(4f))
                }
                for (k in listOf(1, 3, 5, 7)) drawLine(line, morrisPos(k) * u, morrisPos(k + 16) * u, 4f)
                for (i in 0 until 24) {
                    val c = morrisPos(i) * u
                    when (s.b[i]) {
                        0 -> drawCircle(line, u * 0.09f, c)
                        else -> drawCircle(SIDE_COLORS[s.b[i] - 1], u * 0.3f, c)
                    }
                    if (i == sel) drawCircle(Color(0xFFFFC107), u * 0.36f, c, style = Stroke(6f))
                    if (i in removable) drawCircle(Color(0xFFFFEB3B), u * 0.38f, c, style = Stroke(7f))
                    if (i in s.last) drawCircle(Color.White, u * 0.08f, c)
                }
            }
        }
        if (pending != null) Text("Młynek! Wybierz pionek przeciwnika do zbicia", Modifier.fillMaxWidth(), textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun BattleshipBoard(s: Battleship.S, mySide: Int, enabled: Boolean, onMove: (String) -> Unit) {
    val N = Battleship.N
    val rnd = remember { kotlin.random.Random(System.nanoTime()) }
    var fleet by remember { mutableStateOf(Battleship.randomFleet(rnd)) }
    var view by remember { mutableIntStateOf(0) }  // 0 = strzały, 1 = moja flota
    @Composable
    fun Grid(cell: (Int) -> Pair<Color, Boolean>, onTap: ((Int) -> Unit)?) {
        Column(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(6.dp)).background(Color(0xFF0D47A1)).padding(2.dp)) {
            for (r in 0 until N) Row(Modifier.weight(1f)) {
                for (c in 0 until N) {
                    val i = r * N + c
                    val (col, mark) = cell(i)
                    Box(Modifier.weight(1f).fillMaxHeight().padding(1.dp).background(col).then(if (onTap != null) Modifier.clickable { onTap(i) } else Modifier),
                        contentAlignment = Alignment.Center) {
                        if (mark) Box(Modifier.fillMaxSize(0.35f).clip(CircleShape).background(Color.White))
                    }
                }
            }
        }
    }
    val sea = Color(0xFF42A5F5)
    if (!s.ready && mySide >= 0 && s.fleets[mySide] == null) {
        val cells = Battleship.parseFleet(fleet)?.flatten()?.toSet() ?: emptySet()
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Twoja flota (4, 3, 3, 2, 2)", style = MaterialTheme.typography.labelLarge)
            Grid({ i -> (if (i in cells) Color(0xFF546E7A) else sea) to false }, null)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { fleet = Battleship.randomFleet(rnd) }) { Icon(Icons.Default.Shuffle, null); Text(" Losuj") }
                Button(onClick = { onMove(fleet) }, enabled = enabled) { Text("Gotowe") }
            }
        }
        return
    }
    val me = mySide.coerceAtLeast(0)
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        if (mySide >= 0) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(view == 0, { view = 0 }, { Text("Strzały") })
            FilterChip(view == 1, { view = 1 }, { Text("Moja flota") })
        }
        if (!s.ready) Text("Czekamy, aż przeciwnik ustawi flotę…", style = MaterialTheme.typography.labelMedium)
        if (view == 0 || mySide < 0) {
            val shots = s.shots[me].toSet()
            val enemy = s.fleets[1 - me] ?: emptySet()
            val sunk = s.sunk(me).flatten().toSet()
            Grid({ i ->
                when {
                    i in sunk -> Color(0xFF212121) to true
                    i in shots && i in enemy -> Color(0xFFE53935) to true
                    i in shots -> sea.copy(alpha = 0.5f) to false
                    else -> sea to false
                }
            }, if (enabled && s.ready) ({ i -> if (i !in shots) onMove("S:$i") }) else null)
        } else {
            val mine = s.fleets[me] ?: emptySet()
            val theirShots = s.shots[1 - me].toSet()
            Grid({ i ->
                when {
                    i in mine && i in theirShots -> Color(0xFFE53935) to true
                    i in mine -> Color(0xFF546E7A) to false
                    i in theirShots -> sea.copy(alpha = 0.5f) to true
                    else -> sea to false
                }
            }, null)
        }
    }
}

// ============================ dymek gry we dwoje ============================

@Composable
fun DuelBubble(m: Message, info: GameCatalog.Info, big: Boolean = false) {
    @Suppress("UNCHECKED_CAST") val duel = Duels.ALL[info.id] as? Duel<Any> ?: return
    val ctx = LocalContext.current
    val core = App.core
    val me = core.myPub
    val game = remember(m.ex.game) { gameJson(m) }
    val moves = rememberMoves(m.id)
    val played = remember(moves) { Duels.replay(duel, m.sender, game.optLong("s"), moves) }
    val mySide = played.sideOf(me)
    val canWrite = remember(m.chat) { core.canWrite(m.chat) == null }
    val enabled = mySide >= 0 && canWrite && duel.canMove(played.state, mySide)
    var totals by remember { mutableStateOf(false) }
    var zoom by remember { mutableStateOf(false) }
    val w = played.winner
    Column(if (big) Modifier.fillMaxWidth() else Modifier.width(272.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        val bName = played.b?.let(::whoName) ?: "czeka na gracza"
        val sa = sideName(info.id, 0); val sb = sideName(info.id, 1)
        GameTitle(info, "${whoName(played.a)}${if (sa.isNotEmpty()) " ($sa)" else ""} vs $bName${if (sb.isNotEmpty() && played.b != null) " ($sb)" else ""}")
        Spacer(Modifier.height(6.dp))
        DuelBoard(info.id, played.state, mySide, enabled) { mv -> core.gameMove(m.id, mv) }
        Spacer(Modifier.height(6.dp))
        val extra = duelInfo(info.id, played.state)
        val status = when {
            w == 2 -> "Remis"
            w != null -> "Wygrywa: ${whoName(played.pkOf(w))}"
            enabled -> "Twój ruch"
            mySide == 1 && played.b == null && !duel.canMove(played.state, 1) -> "Czekaj na ruch: ${whoName(played.a)}"
            else -> {
                val next = (0..1).firstOrNull { duel.canMove(played.state, it) }
                if (next == null) "" else "Ruch: " + (played.pkOf(next)?.let(::whoName) ?: "drugi gracz")
            }
        }
        Text(listOf(status, extra).filter { it.isNotEmpty() }.joinToString(" · "), fontWeight = FontWeight.Medium, textAlign = TextAlign.Center,
            color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
        BubbleActions {
            if (w != null) TextButton(onClick = { startInChat(ctx, m.chat, info.id) }) { Text("Rewanż") }
            TotalsButton { totals = true }
            if (!big) IconButton(onClick = { zoom = true }) { Icon(Icons.Default.Fullscreen, "Powiększ planszę") }
        }
    }
    if (totals) TotalsDialog(info, m.id, m.chat) { totals = false }
    if (zoom) Dialog(onDismissRequest = { zoom = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth(0.98f).wrapContentHeight(), shape = RoundedCornerShape(20.dp)) {
            Column(Modifier.padding(12.dp)) {
                Row { Spacer(Modifier.weight(1f)); IconButton(onClick = { zoom = false }) { Icon(Icons.Default.Close, "Zamknij") } }
                DuelBubble(m, info, big = true)
            }
        }
    }
}

// ============================ gra z komputerem ============================

@Composable
fun DuelVsAi(id: String, env: GameEnv) {
    @Suppress("UNCHECKED_CAST") val duel = Duels.ALL[id] as Duel<Any>
    val info = remember(id) { GameCatalog.byId(id)!! }
    val rnd = remember(id) { seeded(env.seed, "ai") }
    var state by remember(id) { mutableStateOf(duel.start(env.seed)) }
    var thinking by remember { mutableStateOf(false) }
    var done by remember { mutableStateOf(false) }
    val human = 0; val ai = 1
    LaunchedEffect(state) {
        val w = duel.winner(state)
        if (w != null) {
            if (!done) { done = true; delay(1600); env.finish(when (w) { 2 -> 0.5; human -> 1.0; else -> 0.0 }) }
            return@LaunchedEffect
        }
        // komputer rusza, gdy może (w statkach ustawia flotę od razu)
        if (duel.canMove(state, ai)) {
            thinking = true
            delay(if (state is Battleship.S && !(state as Battleship.S).ready) 50 else 450)
            val mv = withContext(Dispatchers.Default) { duel.ai(state, ai, rnd) }
            thinking = false
            if (mv != null) duel.apply(state, ai, mv)?.let { state = it }
        }
    }
    val w = duel.winner(state)
    Column(Modifier.fillMaxSize().padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Ty${sideName(id, 0).let { if (it.isNotEmpty()) " ($it)" else "" }} vs komputer${sideName(id, 1).let { if (it.isNotEmpty()) " ($it)" else "" }}",
            style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Box(Modifier.widthIn(max = 520.dp).fillMaxWidth()) {
            DuelBoard(id, state, human, w == null && !thinking && duel.canMove(state, human)) { mv -> duel.apply(state, human, mv)?.let { state = it } }
        }
        Spacer(Modifier.height(8.dp))
        val status = when {
            w == 2 -> "Remis"
            w == human -> "Wygrałeś!"
            w == ai -> "Wygrywa komputer"
            thinking -> "Komputer myśli…"
            duel.canMove(state, human) -> "Twój ruch"
            else -> ""
        }
        Text(listOf(status, duelInfo(id, state)).filter { it.isNotEmpty() }.joinToString(" · "), fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        if (info.id == "chess") Text("Dotknij figury, a potem pola docelowego", style = MaterialTheme.typography.labelSmall)
    }
}

// ============================ kamień, papier, nożyce ============================

private val RPS_LABELS = mapOf("r" to "Kamień", "p" to "Papier", "s" to "Nożyce")
private val RPS_ICONS = mapOf("r" to Icons.Default.BackHand, "p" to Icons.Default.Description, "s" to Icons.Default.ContentCut)

@Composable
fun RpsBubble(m: Message, info: GameCatalog.Info) {
    val ctx = LocalContext.current
    val core = App.core
    val me = core.myPub
    val moves = rememberMoves(m.id)
    val picks = remember(moves) { pl.szept.core.Games.rps(moves) }
    val mine = picks.firstOrNull { it.first == me }?.second
    val points = remember(picks) { pl.szept.core.Games.rpsPoints(picks) }
    var totals by remember { mutableStateOf(false) }
    val reveal = mine != null && picks.size >= 2
    Column(Modifier.width(260.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        GameTitle(info, "zagrało: ${picks.size}")
        Spacer(Modifier.height(6.dp))
        if (mine == null) {
            Text("Wybierz w tajemnicy:", style = MaterialTheme.typography.labelMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("r", "p", "s").forEach { k ->
                    OutlinedButton(onClick = { core.gameMove(m.id, k) }, contentPadding = PaddingValues(8.dp)) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) { Icon(RPS_ICONS[k]!!, null); Text(RPS_LABELS[k]!!, style = MaterialTheme.typography.labelSmall) }
                    }
                }
            }
        } else if (!reveal) Text("Wybrałeś: ${RPS_LABELS[mine]}. Czekamy na innych…", textAlign = TextAlign.Center)
        if (reveal) {
            val best = points.values.maxOrNull() ?: 0
            picks.sortedByDescending { points[it.first] ?: 0 }.forEach { (pk, k) ->
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(RPS_ICONS[k]!!, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
                    Text(whoName(pk), Modifier.weight(1f), fontWeight = if ((points[pk] ?: 0) == best && best > 0) FontWeight.Bold else FontWeight.Normal)
                    Text("${points[pk] ?: 0} pkt")
                }
            }
        }
        BubbleActions {
            if (reveal) TextButton(onClick = { startInChat(ctx, m.chat, "rps") }) { Text("Jeszcze raz") }
            TotalsButton { totals = true }
        }
    }
    if (totals) TotalsDialog(info, m.id, m.chat) { totals = false }
}

/** Kamień, papier, nożyce z komputerem: do 3 wygranych. */
@Composable
fun RpsVsAi(env: GameEnv) {
    val rnd = remember { seeded(env.seed, "rps") }
    var me by remember { mutableIntStateOf(0) }
    var ai by remember { mutableIntStateOf(0) }
    var last by remember { mutableStateOf<Pair<String, String>?>(null) }
    var over by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        GameHud("Ty $me : $ai komputer", "do 3 wygranych")
        Spacer(Modifier.weight(1f))
        last?.let { (a, b) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(RPS_ICONS[a]!!, null, Modifier.size(72.dp), tint = SIDE_COLORS[0])
                Text("  vs  ", style = MaterialTheme.typography.titleLarge)
                Icon(RPS_ICONS[b]!!, null, Modifier.size(72.dp), tint = SIDE_COLORS[1])
            }
            Text(when { a == b -> "Remis"; pl.szept.core.Games.rpsBeats(a, b) -> "Wygrywasz rundę!"; else -> "Komputer wygrywa rundę" }, style = MaterialTheme.typography.titleMedium)
        }
        Spacer(Modifier.weight(1f))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("r", "p", "s").forEach { k ->
                Button(onClick = {
                    if (over) return@Button
                    val b = listOf("r", "p", "s")[rnd.nextInt(3)]
                    last = k to b
                    if (pl.szept.core.Games.rpsBeats(k, b)) me++ else if (pl.szept.core.Games.rpsBeats(b, k)) ai++
                    if (me >= 3 || ai >= 3) { over = true; env.finish(if (me >= 3) 1.0 else 0.0) }
                }, Modifier.weight(1f).height(80.dp)) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) { Icon(RPS_ICONS[k]!!, null); Text(RPS_LABELS[k]!!) }
                }
            }
        }
    }
}
