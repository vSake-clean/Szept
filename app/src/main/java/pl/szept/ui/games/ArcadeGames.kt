package pl.szept.ui.games

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.random.Random

private val SKY = Color(0xFF14213D)

/** Plansza gry zręcznościowej: pole rysowania + rozmiar w pikselach. */
@Composable
private fun ArcadeBox(modifier: Modifier = Modifier, bg: Color = SKY, content: @Composable BoxScope.(IntSize) -> Unit) {
    var size by remember { mutableStateOf(IntSize.Zero) }
    Box(modifier.fillMaxSize().background(bg).onSizeChanged { size = it }) { if (size.width > 0) content(size) }
}

// ============================ wąż ============================

@Composable
fun SnakeGame(env: GameEnv) {
    val rnd = remember { seeded(env.seed, "snake") }
    val cols = 16
    var rows by remember { mutableIntStateOf(24) }
    val body = remember { ArrayDeque<Pair<Int, Int>>() }
    var dir by remember { mutableStateOf(1 to 0) }
    var nextDir by remember { mutableStateOf(1 to 0) }
    var food by remember { mutableStateOf(5 to 5) }
    var score by remember { mutableIntStateOf(0) }
    var started by remember { mutableStateOf(false) }
    var over by remember { mutableStateOf(false) }
    var acc by remember { mutableFloatStateOf(0f) }
    fun placeFood() {
        while (true) { val f = rnd.nextInt(cols) to rnd.nextInt(rows); if (f !in body) { food = f; return } }
    }
    fun reset() { body.clear(); for (i in 0 until 3) body.addLast((cols / 2 - i) to rows / 2); placeFood() }
    val frame = rememberFrames(started && !over) { dt ->
        acc += dt
        val interval = max(0.065f, 0.15f - score * 0.003f)
        if (acc < interval) return@rememberFrames
        acc = 0f
        if (nextDir.first != -dir.first || nextDir.second != -dir.second) dir = nextDir
        val h = body.first()
        val n = (h.first + dir.first) to (h.second + dir.second)
        if (n.first !in 0 until cols || n.second !in 0 until rows || n in body.toList().dropLast(1)) { over = true; env.finish(score.toDouble()); return@rememberFrames }
        body.addFirst(n)
        if (n == food) { score++; placeFood() } else body.removeLast()
    }
    Column(Modifier.fillMaxSize()) {
        GameHud("$score pkt", "długość ${body.size}")
        ArcadeBox(Modifier.weight(1f), Color(0xFF1B3A1B)) { sz ->
            val cell = sz.width.toFloat() / cols
            LaunchedEffect(sz) { if (!started) { rows = max(10, (sz.height / cell).toInt()); reset() } }
            Canvas(Modifier.fillMaxSize().pointerInput(Unit) {
                var total = Offset.Zero
                detectDragGestures(onDragStart = { total = Offset.Zero }) { ch, d ->
                    ch.consume(); total += d
                    if (total.getDistance() > 40f) {
                        nextDir = if (abs(total.x) > abs(total.y)) (if (total.x > 0) 1 to 0 else -1 to 0) else (if (total.y > 0) 0 to 1 else 0 to -1)
                        total = Offset.Zero
                    }
                }
            }) {
                frame.value
                for (x in 0 until cols) for (y in 0 until rows) if ((x + y) % 2 == 0) drawRect(Color(0xFF214A21), Offset(x * cell, y * cell), Size(cell, cell))
                drawCircle(Color(0xFFE53935), cell * 0.42f, Offset((food.first + 0.5f) * cell, (food.second + 0.5f) * cell))
                body.forEachIndexed { i, p ->
                    drawRoundRect(if (i == 0) Color(0xFFB2FF59) else Color(0xFF76D275), Offset(p.first * cell + 1, p.second * cell + 1), Size(cell - 2, cell - 2), CornerRadius(cell / 4))
                }
            }
        }
    }
    if (!started) StartOverlay("Przesuwaj palcem, aby skręcać.\nDotknij, aby zacząć") { started = true }
}

// ============================ ptaszek ============================

@Composable
fun FlappyGame(env: GameEnv) {
    val rnd = remember { seeded(env.seed, "flappy") }
    class Pipe(var x: Float, val gapY: Float, var passed: Boolean = false)
    val pipes = remember { mutableListOf<Pipe>() }
    var y by remember { mutableFloatStateOf(0.5f) }
    var vy by remember { mutableFloatStateOf(0f) }
    var score by remember { mutableIntStateOf(0) }
    var started by remember { mutableStateOf(false) }
    var over by remember { mutableStateOf(false) }
    var spawn by remember { mutableFloatStateOf(0f) }
    var aspect by remember { mutableFloatStateOf(1.6f) } // wysokość / szerokość
    val gap = 0.26f
    val frame = rememberFrames(started && !over) { dt ->
        vy += 2.4f * dt; y += vy * dt
        spawn -= dt
        if (spawn <= 0f) { pipes.add(Pipe(1.15f, 0.25f + rnd.nextFloat() * 0.5f)); spawn = 1.55f }
        val speed = 0.32f + score * 0.004f
        pipes.forEach { it.x -= speed * dt }
        pipes.removeAll { it.x < -0.3f }
        val bx = 0.3f; val r = 0.035f
        for (p in pipes) {
            if (!p.passed && p.x + 0.09f < bx) { p.passed = true; score++ }
            val inX = bx + r > p.x && bx - r < p.x + 0.18f
            val yy = y; val top = p.gapY - gap / 2; val bot = p.gapY + gap / 2
            if (inX && (yy - r * aspect.let { 1f / it } < top || yy + r / aspect > bot)) { over = true }
        }
        if (y > 1f || y < 0f) over = true
        if (over) env.finish(score.toDouble())
    }
    Column(Modifier.fillMaxSize()) {
        GameHud("$score pkt")
        ArcadeBox(Modifier.weight(1f), Color(0xFF4FC3F7)) { sz ->
            LaunchedEffect(sz) { aspect = sz.height.toFloat() / sz.width }
            Canvas(Modifier.fillMaxSize().pointerInput(started, over) {
                detectTapGestures(onPress = { if (started && !over) vy = -0.85f })
            }) {
                frame.value
                val w = size.width; val h = size.height
                for (p in pipes) {
                    val px = p.x * w; val pw = 0.18f * w
                    drawRect(Color(0xFF43A047), Offset(px, 0f), Size(pw, (p.gapY - gap / 2) * h))
                    drawRect(Color(0xFF43A047), Offset(px, (p.gapY + gap / 2) * h), Size(pw, h))
                    drawRect(Color(0xFF2E7D32), Offset(px - 6, (p.gapY - gap / 2) * h - 24), Size(pw + 12, 24f))
                    drawRect(Color(0xFF2E7D32), Offset(px - 6, (p.gapY + gap / 2) * h), Size(pw + 12, 24f))
                }
                val c = Offset(0.3f * w, y * h)
                drawCircle(Color(0xFFFFEB3B), 0.035f * w, c)
                drawCircle(Color.White, 0.012f * w, c + Offset(0.012f * w, -0.01f * w))
                drawCircle(Color.Black, 0.005f * w, c + Offset(0.016f * w, -0.01f * w))
                drawRect(Color(0xFFFF9800), c + Offset(0.03f * w, -0.004f * w), Size(0.02f * w, 0.012f * w))
            }
        }
    }
    if (!started) StartOverlay("Dotykaj, aby podlecieć") { started = true; vy = -0.85f }
}

// ============================ klocki ============================

private val TETRO = listOf(
    listOf(0 to 1, 1 to 1, 2 to 1, 3 to 1),       // I
    listOf(0 to 0, 0 to 1, 1 to 1, 2 to 1),       // J
    listOf(2 to 0, 0 to 1, 1 to 1, 2 to 1),       // L
    listOf(1 to 0, 2 to 0, 1 to 1, 2 to 1),       // O
    listOf(1 to 0, 2 to 0, 0 to 1, 1 to 1),       // S
    listOf(1 to 0, 0 to 1, 1 to 1, 2 to 1),       // T
    listOf(0 to 0, 1 to 0, 1 to 1, 2 to 1),       // Z
)
private val TETRO_COLORS = listOf(0xFF00BCD4, 0xFF3F51B5, 0xFFFF9800, 0xFFFFEB3B, 0xFF4CAF50, 0xFF9C27B0, 0xFFF44336).map { Color(it) }

@Composable
fun TetrisGame(env: GameEnv) {
    val W = 10; val H = 20
    val rnd = remember { seeded(env.seed, "tetris") }
    val board = remember { IntArray(W * H) }
    val bag = remember { ArrayDeque<Int>() }
    fun nextPiece(): Int { if (bag.isEmpty()) bag.addAll((0 until 7).shuffled(rnd)); return bag.removeFirst() }
    var piece by remember { mutableIntStateOf(nextPiece()) }
    var next by remember { mutableIntStateOf(nextPiece()) }
    var cells by remember { mutableStateOf(TETRO[piece]) }
    var px by remember { mutableIntStateOf(3) }
    var py by remember { mutableIntStateOf(0) }
    var score by remember { mutableIntStateOf(0) }
    var lines by remember { mutableIntStateOf(0) }
    var started by remember { mutableStateOf(false) }
    var over by remember { mutableStateOf(false) }
    var acc by remember { mutableFloatStateOf(0f) }
    var redraw by remember { mutableIntStateOf(0) }
    fun fits(c: List<Pair<Int, Int>>, x: Int, y: Int) = c.all { (cx, cy) -> val bx = x + cx; val by = y + cy; bx in 0 until W && by < H && (by < 0 || board[by * W + bx] == 0) }
    fun rotate() {
        if (piece == 3) return
        val size = if (piece == 0) 4 else 3
        val r = cells.map { (x, y) -> (size - 1 - y) to x }
        for (kick in listOf(0, -1, 1, -2, 2)) if (fits(r, px + kick, py)) { cells = r; px += kick; redraw++; return }
    }
    fun lock() {
        for ((cx, cy) in cells) { val by = py + cy; if (by < 0) { over = true; return }; board[by * W + px + cx] = piece + 1 }
        var cleared = 0
        var y = H - 1
        while (y >= 0) {
            if ((0 until W).all { board[y * W + it] != 0 }) {
                for (yy in y downTo 1) for (x in 0 until W) board[yy * W + x] = board[(yy - 1) * W + x]
                for (x in 0 until W) board[x] = 0
                cleared++
            } else y--
        }
        lines += cleared
        score += listOf(0, 100, 300, 500, 800)[cleared] * (1 + lines / 10)
        piece = next; next = nextPiece(); cells = TETRO[piece]; px = 3; py = -1
        if (!fits(cells, px, py)) over = true
    }
    fun drop(): Boolean { if (fits(cells, px, py + 1)) { py++; return true }; lock(); return false }
    val frame = rememberFrames(started && !over) { dt ->
        acc += dt
        val interval = max(0.08f, 0.7f - (lines / 10) * 0.06f)
        if (acc >= interval) { acc = 0f; drop() }
        if (over) env.finish(score.toDouble())
    }
    Column(Modifier.fillMaxSize()) {
        GameHud("$score pkt", "linie: $lines")
        ArcadeBox(Modifier.weight(1f), Color(0xFF101418)) { sz ->
            val cell = min(sz.width.toFloat() / (W + 4), sz.height.toFloat() / H)
            val ox = (sz.width - cell * (W + 4)) / 2f
            Canvas(Modifier.fillMaxSize().pointerInput(started, over) {
                if (!started || over) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown()
                    var moved = 0f; var movedY = 0f; var dragged = false
                    val t0 = System.currentTimeMillis()
                    while (true) {
                        val ev = awaitPointerEvent()
                        val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                        if (!ch.pressed) break
                        val d = ch.positionChange(); moved += d.x; movedY += d.y
                        while (moved > cell) { if (fits(cells, px + 1, py)) px++; moved -= cell; dragged = true; redraw++ }
                        while (moved < -cell) { if (fits(cells, px - 1, py)) px--; moved += cell; dragged = true; redraw++ }
                        if (movedY > cell * 1.2f) { if (drop()) score += 1; movedY = 0f; dragged = true; redraw++ }
                        ch.consume()
                    }
                    if (!dragged && System.currentTimeMillis() - t0 < 300) rotate()
                }
            }) {
                frame.value; redraw
                drawRect(Color(0xFF1C232B), Offset(ox, 0f), Size(cell * W, cell * H))
                fun block(x: Float, y: Float, c: Color) = drawRoundRect(c, Offset(x + 1, y + 1), Size(cell - 2, cell - 2), CornerRadius(cell / 6))
                for (y in 0 until H) for (x in 0 until W) board[y * W + x].takeIf { it > 0 }?.let { block(ox + x * cell, y * cell, TETRO_COLORS[it - 1]) }
                // cień — gdzie spadnie klocek
                var gy = py; while (fits(cells, px, gy + 1)) gy++
                for ((cx, cy) in cells) if (gy + cy >= 0) drawRoundRect(TETRO_COLORS[piece].copy(alpha = 0.25f), Offset(ox + (px + cx) * cell + 1, (gy + cy) * cell + 1), Size(cell - 2, cell - 2), CornerRadius(cell / 6))
                for ((cx, cy) in cells) if (py + cy >= 0) block(ox + (px + cx) * cell, (py + cy) * cell, TETRO_COLORS[piece])
                for ((cx, cy) in TETRO[next]) block(ox + (W + 0.4f + cx * 0.8f) * cell, (1 + cy * 0.8f) * cell, TETRO_COLORS[next].copy(alpha = 0.8f))
            }
        }
    }
    if (!started) StartOverlay("Przesuwaj w bok, dotknij = obrót,\nprzesuń w dół = szybciej") { started = true }
}

// ============================ arkanoid ============================

@Composable
fun BreakoutGame(env: GameEnv) {
    val cols = 8; val rowsN = 6
    var bricks by remember { mutableStateOf(BooleanArray(cols * rowsN) { true }) }
    var paddle by remember { mutableFloatStateOf(0.5f) }
    var bx by remember { mutableFloatStateOf(0.5f) }
    var by by remember { mutableFloatStateOf(0.7f) }
    var vx by remember { mutableFloatStateOf(0.35f) }
    var vy by remember { mutableFloatStateOf(-0.55f) }
    var lives by remember { mutableIntStateOf(3) }
    var score by remember { mutableIntStateOf(0) }
    var level by remember { mutableIntStateOf(1) }
    var started by remember { mutableStateOf(false) }
    var over by remember { mutableStateOf(false) }
    var aspect by remember { mutableFloatStateOf(1.6f) }
    val rnd = remember { seeded(env.seed, "breakout") }
    fun serve() { bx = paddle; by = 0.85f; vx = (if (rnd.nextBoolean()) 1 else -1) * (0.25f + rnd.nextFloat() * 0.15f); vy = -(0.5f + level * 0.06f) }
    val frame = rememberFrames(started && !over) { dt ->
        bx += vx * dt; by += vy * dt / aspect
        val r = 0.018f
        if (bx < r) { bx = r; vx = abs(vx) }
        if (bx > 1 - r) { bx = 1 - r; vx = -abs(vx) }
        if (by < 0.02f) { by = 0.02f; vy = abs(vy) }
        val pw = 0.2f
        if (vy > 0 && by > 0.9f && by < 0.93f && bx in paddle - pw / 2 - r..paddle + pw / 2 + r) {
            vy = -abs(vy); vx = ((bx - paddle) / (pw / 2)) * 0.6f
        }
        val bw = 1f / cols; val bh = 0.035f
        val bxi = (bx / bw).toInt(); val byi = ((by - 0.08f) / bh).toInt()
        if (by > 0.08f && byi in 0 until rowsN && bxi in 0 until cols && bricks[byi * cols + bxi]) {
            bricks = bricks.copyOf().also { it[byi * cols + bxi] = false }
            vy = -vy; score += 10 * level
            if (bricks.none { it }) { level++; bricks = BooleanArray(cols * rowsN) { true }; serve() }
        }
        if (by > 1f) { lives--; if (lives <= 0) { over = true; env.finish(score.toDouble()) } else serve() }
    }
    Column(Modifier.fillMaxSize()) {
        GameHud("$score pkt", "życia: $lives · poziom $level")
        ArcadeBox(Modifier.weight(1f)) { sz ->
            LaunchedEffect(sz) { aspect = sz.height.toFloat() / sz.width }
            Canvas(Modifier.fillMaxSize().pointerInput(Unit) {
                awaitEachGesture {
                    val d = awaitFirstDown(); paddle = (d.position.x / size.width).coerceIn(0.1f, 0.9f)
                    while (true) { val e = awaitPointerEvent(); val c = e.changes.first(); if (!c.pressed) break; paddle = (c.position.x / size.width).coerceIn(0.1f, 0.9f); c.consume() }
                }
            }) {
                frame.value
                val w = size.width; val h = size.height
                val colors = listOf(0xFFEF5350, 0xFFFFA726, 0xFFFFEE58, 0xFF66BB6A, 0xFF42A5F5, 0xFFAB47BC).map { Color(it) }
                for (i in bricks.indices) if (bricks[i]) {
                    val x = (i % cols) * w / cols; val y = (0.08f + (i / cols) * 0.035f) * h
                    drawRoundRect(colors[i / cols], Offset(x + 2, y + 2), Size(w / cols - 4, 0.035f * h - 4), CornerRadius(6f))
                }
                drawRoundRect(Color.White, Offset((paddle - 0.1f) * w, 0.9f * h), Size(0.2f * w, 0.02f * h), CornerRadius(12f))
                drawCircle(Color(0xFFFFF59D), 0.018f * w, Offset(bx * w, by * h))
            }
        }
    }
    if (!started) StartOverlay("Przesuwaj paletkę palcem") { started = true; serve() }
}

// ============================ odbijanka ============================

@Composable
fun PongGame(env: GameEnv) {
    var paddle by remember { mutableFloatStateOf(0.5f) }
    var bx by remember { mutableFloatStateOf(0.5f) }
    var by by remember { mutableFloatStateOf(0.2f) }
    var vx by remember { mutableFloatStateOf(0.3f) }
    var vy by remember { mutableFloatStateOf(0.45f) }
    var score by remember { mutableIntStateOf(0) }
    var started by remember { mutableStateOf(false) }
    var over by remember { mutableStateOf(false) }
    var aspect by remember { mutableFloatStateOf(1.6f) }
    val frame = rememberFrames(started && !over) { dt ->
        bx += vx * dt; by += vy * dt / aspect
        if (bx < 0.02f) { bx = 0.02f; vx = abs(vx) }
        if (bx > 0.98f) { bx = 0.98f; vx = -abs(vx) }
        if (by < 0.02f) { by = 0.02f; vy = abs(vy) }
        val pw = max(0.12f, 0.26f - score * 0.004f)
        if (vy > 0 && by in 0.88f..0.91f && abs(bx - paddle) < pw / 2 + 0.02f) {
            vy = -abs(vy) * 1.05f; vx = ((bx - paddle) / (pw / 2)) * 0.7f * (1 + score * 0.02f); score++
        }
        if (by > 1f) { over = true; env.finish(score.toDouble()) }
    }
    Column(Modifier.fillMaxSize()) {
        GameHud("$score pkt", "odbicia")
        ArcadeBox(Modifier.weight(1f), Color(0xFF0D3B2E)) { sz ->
            LaunchedEffect(sz) { aspect = sz.height.toFloat() / sz.width }
            Canvas(Modifier.fillMaxSize().pointerInput(Unit) {
                awaitEachGesture {
                    val d = awaitFirstDown(); paddle = (d.position.x / size.width).coerceIn(0f, 1f)
                    while (true) { val e = awaitPointerEvent(); val c = e.changes.first(); if (!c.pressed) break; paddle = (c.position.x / size.width).coerceIn(0f, 1f); c.consume() }
                }
            }) {
                frame.value
                val w = size.width; val h = size.height
                drawLine(Color.White.copy(alpha = 0.2f), Offset(0f, h / 2), Offset(w, h / 2), 4f)
                val pw = max(0.12f, 0.26f - score * 0.004f)
                drawRoundRect(Color(0xFFFFCA28), Offset((paddle - pw / 2) * w, 0.9f * h), Size(pw * w, 0.022f * h), CornerRadius(14f))
                drawCircle(Color.White, 0.022f * w, Offset(bx * w, by * h))
            }
        }
    }
    if (!started) StartOverlay("Nie pozwól piłce spaść!") { started = true }
}

// ============================ unik ============================

@Composable
fun DodgeGame(env: GameEnv) {
    val rnd = remember { seeded(env.seed, "dodge") }
    class Block(var x: Float, var y: Float, val w: Float, val speed: Float)
    val blocks = remember { mutableListOf<Block>() }
    var px by remember { mutableFloatStateOf(0.5f) }
    var time by remember { mutableFloatStateOf(0f) }
    var spawn by remember { mutableFloatStateOf(0f) }
    var started by remember { mutableStateOf(false) }
    var over by remember { mutableStateOf(false) }
    var aspect by remember { mutableFloatStateOf(1.6f) }
    val frame = rememberFrames(started && !over) { dt ->
        time += dt; spawn -= dt
        if (spawn <= 0) { blocks.add(Block(rnd.nextFloat() * 0.85f, -0.1f, 0.08f + rnd.nextFloat() * 0.12f, 0.35f + time * 0.02f + rnd.nextFloat() * 0.2f)); spawn = max(0.18f, 0.7f - time * 0.015f) }
        blocks.forEach { it.y += it.speed * dt }
        blocks.removeAll { it.y > 1.1f }
        val r = 0.03f; val py = 0.9f
        for (b in blocks) {
            val cx = px.coerceIn(b.x, b.x + b.w); val cy = py.coerceIn(b.y, b.y + 0.04f)
            val dx = px - cx; val dy = (py - cy) * aspect
            if (dx * dx + dy * dy < r * r) { over = true; env.finish((time * 1000).toDouble()); break }
        }
    }
    Column(Modifier.fillMaxSize()) {
        GameHud(pl.szept.core.GameCatalog.fmtTime(time * 1000.0), "przetrwaj")
        ArcadeBox(Modifier.weight(1f), Color(0xFF26113D)) { sz ->
            LaunchedEffect(sz) { aspect = sz.height.toFloat() / sz.width }
            Canvas(Modifier.fillMaxSize().pointerInput(Unit) {
                awaitEachGesture {
                    val d = awaitFirstDown(); var last = d.position
                    while (true) { val e = awaitPointerEvent(); val c = e.changes.first(); if (!c.pressed) break; px = (px + (c.position.x - last.x) / size.width * 1.4f).coerceIn(0.03f, 0.97f); last = c.position; c.consume() }
                }
            }) {
                frame.value
                val w = size.width; val h = size.height
                for (b in blocks) drawRoundRect(Color(0xFFFF4081), Offset(b.x * w, b.y * h), Size(b.w * w, 0.04f * h), CornerRadius(8f))
                drawCircle(Color(0xFF64FFDA), 0.03f * w, Offset(px * w, 0.9f * h))
            }
        }
    }
    if (!started) StartOverlay("Przesuwaj palcem i unikaj bloków") { started = true }
}

// ============================ kosmiczna obrona ============================

@Composable
fun ShooterGame(env: GameEnv) {
    val rnd = remember { seeded(env.seed, "shooter") }
    class Rock(var x: Float, var y: Float, val r: Float, var hp: Int, val speed: Float)
    class Shot(var x: Float, var y: Float)
    val rocks = remember { mutableListOf<Rock>() }
    val shots = remember { mutableListOf<Shot>() }
    var px by remember { mutableFloatStateOf(0.5f) }
    var t by remember { mutableFloatStateOf(0f) }
    var fire by remember { mutableFloatStateOf(0f) }
    var spawn by remember { mutableFloatStateOf(0f) }
    var score by remember { mutableIntStateOf(0) }
    var lives by remember { mutableIntStateOf(3) }
    var started by remember { mutableStateOf(false) }
    var over by remember { mutableStateOf(false) }
    var aspect by remember { mutableFloatStateOf(1.6f) }
    val frame = rememberFrames(started && !over) { dt ->
        t += dt; fire -= dt; spawn -= dt
        if (fire <= 0) { shots.add(Shot(px, 0.86f)); fire = 0.22f }
        if (spawn <= 0) {
            val big = rnd.nextInt(4) == 0
            rocks.add(Rock(0.05f + rnd.nextFloat() * 0.9f, -0.05f, if (big) 0.07f else 0.04f, if (big) 3 else 1, 0.12f + t * 0.004f + rnd.nextFloat() * 0.1f))
            spawn = max(0.3f, 1.1f - t * 0.012f)
        }
        shots.forEach { it.y -= 1.2f * dt }
        rocks.forEach { it.y += it.speed * dt }
        val sIt = shots.iterator()
        while (sIt.hasNext()) {
            val s = sIt.next()
            if (s.y < -0.05f) { sIt.remove(); continue }
            val hit = rocks.firstOrNull { val dx = it.x - s.x; val dy = (it.y - s.y) * aspect; dx * dx + dy * dy < it.r * it.r }
            if (hit != null) { hit.hp--; sIt.remove(); if (hit.hp <= 0) { rocks.remove(hit); score += if (hit.r > 0.05f) 30 else 10 } }
        }
        val rIt = rocks.iterator()
        while (rIt.hasNext()) {
            val r = rIt.next()
            val dx = r.x - px; val dy = (r.y - 0.9f) * aspect
            if (dx * dx + dy * dy < (r.r + 0.035f) * (r.r + 0.035f)) { lives = 0 }
            else if (r.y > 1.05f) { rIt.remove(); lives-- }
        }
        if (lives <= 0) { over = true; env.finish(score.toDouble()) }
    }
    Column(Modifier.fillMaxSize()) {
        GameHud("$score pkt", "osłony: ${"♥".repeat(max(0, lives))}")
        ArcadeBox(Modifier.weight(1f), Color(0xFF05070F)) { sz ->
            LaunchedEffect(sz) { aspect = sz.height.toFloat() / sz.width }
            val stars = remember { List(60) { Offset(Random(it).nextFloat(), Random(it * 7 + 3).nextFloat()) } }
            Canvas(Modifier.fillMaxSize().pointerInput(Unit) {
                awaitEachGesture {
                    val d = awaitFirstDown(); var last = d.position
                    while (true) { val e = awaitPointerEvent(); val c = e.changes.first(); if (!c.pressed) break; px = (px + (c.position.x - last.x) / size.width * 1.3f).coerceIn(0.04f, 0.96f); last = c.position; c.consume() }
                }
            }) {
                frame.value
                val w = size.width; val h = size.height
                stars.forEach { s -> drawCircle(Color.White.copy(alpha = 0.6f), 2f, Offset(s.x * w, ((s.y + t * 0.05f) % 1f) * h)) }
                shots.forEach { drawRect(Color(0xFF80D8FF), Offset(it.x * w - 3, it.y * h), Size(6f, 0.03f * h)) }
                rocks.forEach { drawCircle(if (it.hp > 1) Color(0xFF8D6E63) else Color(0xFFA1887F), it.r * w, Offset(it.x * w, it.y * h)) }
                val path = Path().apply { moveTo(px * w, 0.86f * h); lineTo((px - 0.04f) * w, 0.94f * h); lineTo((px + 0.04f) * w, 0.94f * h); close() }
                drawPath(path, Color(0xFFB2EBF2))
            }
        }
    }
    if (!started) StartOverlay("Statek strzela sam — przesuwaj go palcem") { started = true }
}

// ============================ biegacz ============================

@Composable
fun RunnerGame(env: GameEnv) {
    val rnd = remember { seeded(env.seed, "runner") }
    class Ob(var x: Float, val w: Float, val h: Float)
    val obs = remember { mutableListOf<Ob>() }
    var y by remember { mutableFloatStateOf(0f) }   // wysokość nad ziemią
    var vy by remember { mutableFloatStateOf(0f) }
    var dist by remember { mutableFloatStateOf(0f) }
    var next by remember { mutableFloatStateOf(1.2f) }
    var started by remember { mutableStateOf(false) }
    var over by remember { mutableStateOf(false) }
    val frame = rememberFrames(started && !over) { dt ->
        val speed = 0.5f + dist * 0.004f
        dist += speed * dt * 10
        vy -= 3.2f * dt; y = max(0f, y + vy * dt); if (y == 0f) vy = 0f
        obs.forEach { it.x -= speed * dt }
        obs.removeAll { it.x < -0.2f }
        next -= speed * dt
        if (next <= 0) { obs.add(Ob(1.05f, 0.04f + rnd.nextFloat() * 0.05f, 0.06f + rnd.nextFloat() * 0.08f)); next = 0.45f + rnd.nextFloat() * 0.6f }
        val px = 0.18f; val pw = 0.06f
        if (obs.any { it.x < px + pw && it.x + it.w > px && y < it.h }) { over = true; env.finish(dist.toInt().toDouble()) }
    }
    Column(Modifier.fillMaxSize()) {
        GameHud("${dist.toInt()} pkt", "")
        ArcadeBox(Modifier.weight(1f), Color(0xFFFFF8E1)) { _ ->
            Canvas(Modifier.fillMaxSize().pointerInput(started, over) {
                detectTapGestures(onPress = { if (started && !over && y == 0f) vy = 1.35f })
            }) {
                frame.value
                val w = size.width; val h = size.height
                val ground = h * 0.7f
                val unit = w
                drawLine(Color(0xFF5D4037), Offset(0f, ground), Offset(w, ground), 6f)
                for (o in obs) drawRoundRect(Color(0xFF388E3C), Offset(o.x * w, ground - o.h * unit), Size(o.w * w, o.h * unit), CornerRadius(6f))
                val px = 0.18f * w
                drawRoundRect(Color(0xFF455A64), Offset(px, ground - (y + 0.1f) * unit), Size(0.06f * w, 0.1f * unit), CornerRadius(10f))
                drawCircle(Color.White, 0.008f * w, Offset(px + 0.042f * w, ground - (y + 0.08f) * unit))
            }
        }
    }
    if (!started) StartOverlay("Dotknij, aby skoczyć") { started = true }
}

// ============================ skoczek ============================

@Composable
fun JumperGame(env: GameEnv) {
    val rnd = remember { seeded(env.seed, "jumper") }
    class Plat(val x: Float, val y: Float, val moving: Boolean, var dx: Float)
    val plats = remember { mutableListOf<Plat>() }
    var px by remember { mutableFloatStateOf(0.5f) }
    var py by remember { mutableFloatStateOf(0.3f) }  // w jednostkach świata (1 = szerokość), w górę rośnie
    var vy by remember { mutableFloatStateOf(1.6f) }
    var target by remember { mutableFloatStateOf(0.5f) }
    var cam by remember { mutableFloatStateOf(0f) }
    var top by remember { mutableFloatStateOf(0f) }
    var started by remember { mutableStateOf(false) }
    var over by remember { mutableStateOf(false) }
    var viewH by remember { mutableFloatStateOf(1.6f) }
    LaunchedEffect(Unit) {
        plats.add(Plat(0.4f, 0.1f, false, 0f))
        var y = 0.1f
        while (y < 4f) { y += 0.18f + rnd.nextFloat() * 0.12f; plats.add(Plat(rnd.nextFloat() * 0.8f, y, rnd.nextInt(6) == 0, 0.2f)); top = y }
    }
    val frame = rememberFrames(started && !over) { dt ->
        px += (target - px) * min(1f, dt * 10f)
        val prevY = py
        vy -= 3.0f * dt; py += vy * dt
        for (p in plats) {
            if (p.moving) { val nx = p.x + p.dx * dt; if (nx < 0f || nx > 0.8f) p.dx = -p.dx }
        }
        val moved = plats.map { p -> if (p.moving) Plat((p.x + p.dx * dt).coerceIn(0f, 0.8f), p.y, true, p.dx) else p }
        plats.clear(); plats.addAll(moved)
        if (vy < 0) for (p in plats) if (prevY >= p.y && py < p.y && px > p.x - 0.03f && px < p.x + 0.23f) { py = p.y; vy = 1.6f; break }
        if (py - cam > viewH * 0.55f) cam = py - viewH * 0.55f
        while (top < cam + viewH + 1f) {
            top += 0.18f + rnd.nextFloat() * (0.12f + min(0.12f, cam * 0.01f))
            plats.add(Plat(rnd.nextFloat() * 0.8f, top, rnd.nextInt(max(2, 6 - (cam / 5).toInt())) == 0, 0.2f + rnd.nextFloat() * 0.2f))
        }
        plats.removeAll { it.y < cam - 0.3f }
        if (py < cam - 0.1f) { over = true; env.finish((cam * 100).toInt().toDouble()) }
    }
    Column(Modifier.fillMaxSize()) {
        GameHud("${(cam * 100).toInt()} pkt", "wysokość")
        ArcadeBox(Modifier.weight(1f), Color(0xFFE3F2FD)) { sz ->
            LaunchedEffect(sz) { viewH = sz.height.toFloat() / sz.width }
            Canvas(Modifier.fillMaxSize().pointerInput(Unit) {
                awaitEachGesture {
                    val d = awaitFirstDown(); target = (d.position.x / size.width).coerceIn(0.03f, 0.97f)
                    while (true) { val e = awaitPointerEvent(); val c = e.changes.first(); if (!c.pressed) break; target = (c.position.x / size.width).coerceIn(0.03f, 0.97f); c.consume() }
                }
            }) {
                frame.value
                val w = size.width; val h = size.height
                fun sy(y: Float) = h - (y - cam) * w
                for (p in plats) drawRoundRect(if (p.moving) Color(0xFF1E88E5) else Color(0xFF66BB6A), Offset(p.x * w, sy(p.y)), Size(0.2f * w, 0.025f * w), CornerRadius(10f))
                drawCircle(Color(0xFFFF7043), 0.035f * w, Offset(px * w, sy(py) - 0.035f * w))
            }
        }
    }
    if (!started) StartOverlay("Trzymaj palec tam, dokąd ma skakać") { started = true }
}

// ============================ wieża ============================

@Composable
fun StackGame(env: GameEnv) {
    data class Layer(val x: Float, val w: Float)
    val layers = remember { mutableStateListOf(Layer(0.25f, 0.5f)) }
    var cur by remember { mutableFloatStateOf(0f) }
    var dir by remember { mutableFloatStateOf(1f) }
    var started by remember { mutableStateOf(false) }
    var over by remember { mutableStateOf(false) }
    val frame = rememberFrames(started && !over) { dt ->
        val w = layers.last().w
        val speed = 0.45f + layers.size * 0.02f
        cur += dir * speed * dt
        if (cur + w > 1f) { cur = 1f - w; dir = -1f }
        if (cur < 0f) { cur = 0f; dir = 1f }
    }
    fun dropBlock() {
        val prev = layers.last()
        val l = max(cur, prev.x); val r = min(cur + prev.w, prev.x + prev.w)
        if (r - l < 0.01f) { over = true; env.finish((layers.size - 1).toDouble()); return }
        // prawie idealnie — bez przycinania
        if (abs(cur - prev.x) < 0.012f) layers.add(Layer(prev.x, prev.w)) else layers.add(Layer(l, r - l))
        cur = if (layers.size % 2 == 0) 0f else 1f - layers.last().w
        dir = if (layers.size % 2 == 0) 1f else -1f
    }
    Column(Modifier.fillMaxSize()) {
        GameHud("${layers.size - 1} pkt", "piętra")
        ArcadeBox(Modifier.weight(1f), Color(0xFF263238)) { _ ->
            Canvas(Modifier.fillMaxSize().pointerInput(started, over) { detectTapGestures { if (started && !over) dropBlock() } }) {
                frame.value
                val w = size.width; val h = size.height
                val lh = h * 0.045f
                val shift = max(0, layers.size - 12)
                layers.forEachIndexed { i, l ->
                    if (i < shift) return@forEachIndexed
                    val y = h - (i - shift + 1) * lh
                    val hue = (i * 23) % 360
                    drawRect(Color.hsv(hue.toFloat(), 0.55f, 0.95f), Offset(l.x * w, y), Size(l.w * w, lh - 2))
                }
                if (!over) {
                    val y = h - (layers.size - shift + 1) * lh
                    drawRect(Color.hsv(((layers.size * 23) % 360).toFloat(), 0.55f, 0.95f), Offset(cur * w, y), Size(layers.last().w * w, lh - 2))
                }
            }
        }
    }
    if (!started) StartOverlay("Dotknij, by upuścić blok na wieżę") { started = true }
}

// ============================ balony ============================

@Composable
fun BalloonsGame(env: GameEnv) {
    val rnd = remember { seeded(env.seed, "balloons") }
    class B(val x: Float, var y: Float, val speed: Float, val bad: Boolean, val color: Color)
    val list = remember { mutableListOf<B>() }
    var score by remember { mutableIntStateOf(0) }
    var started by remember { mutableStateOf(false) }
    var over by remember { mutableStateOf(false) }
    var spawn by remember { mutableFloatStateOf(0f) }
    var elapsed by remember { mutableFloatStateOf(0f) }
    var aspect by remember { mutableFloatStateOf(1.6f) }
    val palette = listOf(0xFFE53935, 0xFF1E88E5, 0xFF43A047, 0xFFFDD835, 0xFF8E24AA, 0xFFFB8C00).map { Color(it) }
    val left = rememberCountdown(30, started && !over) { if (!over) { over = true; env.finish(score.toDouble()) } }
    val frame = rememberFrames(started && !over) { dt ->
        elapsed += dt; spawn -= dt
        if (spawn <= 0) {
            val bad = rnd.nextInt(6) == 0
            list.add(B(0.08f + rnd.nextFloat() * 0.84f, 1.1f, 0.18f + elapsed * 0.012f + rnd.nextFloat() * 0.15f, bad, if (bad) Color(0xFF212121) else palette[rnd.nextInt(palette.size)]))
            spawn = max(0.25f, 0.7f - elapsed * 0.015f)
        }
        list.forEach { it.y -= it.speed * dt }
        list.removeAll { it.y < -0.15f }
    }
    Column(Modifier.fillMaxSize()) {
        GameHud("$score pkt", "${left.value} s")
        ArcadeBox(Modifier.weight(1f), Color(0xFFB3E5FC)) { sz ->
            LaunchedEffect(sz) { aspect = sz.height.toFloat() / sz.width }
            Canvas(Modifier.fillMaxSize().pointerInput(started, over) {
                detectTapGestures(onPress = { pos ->
                    if (!started || over) return@detectTapGestures
                    val x = pos.x / size.width; val y = pos.y / size.height
                    val hit = list.lastOrNull { b -> val dx = b.x - x; val dy = (b.y - y) * aspect; dx * dx + dy * dy < 0.07f * 0.07f }
                    if (hit != null) { list.remove(hit); score = max(0, score + if (hit.bad) -3 else 1) }
                })
            }) {
                frame.value
                val w = size.width; val h = size.height
                for (b in list) {
                    val c = Offset(b.x * w, b.y * h)
                    drawLine(Color(0xFF616161), c + Offset(0f, 0.055f * w), c + Offset(0f, 0.12f * w), 2f)
                    drawOval(b.color, c - Offset(0.045f * w, 0.055f * w), Size(0.09f * w, 0.11f * w))
                    drawCircle(Color.White.copy(alpha = 0.5f), 0.012f * w, c - Offset(0.018f * w, 0.025f * w))
                }
            }
        }
    }
    if (!started) StartOverlay("Przebijaj kolorowe balony. Czarne odejmują 3 punkty!") { started = true }
}

// ============================ kret ============================

@Composable
fun MoleGame(env: GameEnv) {
    val rnd = remember { seeded(env.seed, "mole") }
    val up = remember { mutableStateMapOf<Int, Pair<Float, Boolean>>() }  // dziura → (czas do schowania, złoty)
    var score by remember { mutableIntStateOf(0) }
    var started by remember { mutableStateOf(false) }
    var over by remember { mutableStateOf(false) }
    var spawn by remember { mutableFloatStateOf(0f) }
    var elapsed by remember { mutableFloatStateOf(0f) }
    val left = rememberCountdown(30, started && !over) { if (!over) { over = true; env.finish(score.toDouble()) } }
    rememberFrames(started && !over) { dt ->
        elapsed += dt; spawn -= dt
        up.keys.toList().forEach { k -> val (t, g) = up[k]!!; if (t - dt <= 0) up.remove(k) else up[k] = (t - dt) to g }
        if (spawn <= 0) {
            val free = (0 until 9).filter { it !in up }
            if (free.isNotEmpty()) up[free[rnd.nextInt(free.size)]] = (max(0.45f, 1.1f - elapsed * 0.02f)) to (rnd.nextInt(8) == 0)
            spawn = max(0.25f, 0.75f - elapsed * 0.015f)
        }
    }
    Column(Modifier.fillMaxSize()) {
        GameHud("$score pkt", "${left.value} s")
        ArcadeBox(Modifier.weight(1f), Color(0xFF7CB342)) { _ ->
            Canvas(Modifier.fillMaxSize().pointerInput(started, over) {
                detectTapGestures(onPress = { pos ->
                    if (!started || over) return@detectTapGestures
                    val cw = size.width / 3f; val ch = size.height / 3f
                    val i = (pos.y / ch).toInt().coerceIn(0, 2) * 3 + (pos.x / cw).toInt().coerceIn(0, 2)
                    up.remove(i)?.let { score += if (it.second) 3 else 1 }
                })
            }) {
                val w = size.width / 3f; val h = size.height / 3f
                for (i in 0 until 9) {
                    val c = Offset((i % 3 + 0.5f) * w, (i / 3 + 0.62f) * h)
                    drawOval(Color(0xFF3E2723), c - Offset(w * 0.32f, h * 0.09f), Size(w * 0.64f, h * 0.18f))
                    up[i]?.let { (_, gold) ->
                        val col = if (gold) Color(0xFFFFD600) else Color(0xFF795548)
                        drawCircle(col, w * 0.22f, c - Offset(0f, h * 0.13f))
                        drawCircle(Color.Black, w * 0.025f, c - Offset(w * 0.07f, h * 0.18f))
                        drawCircle(Color.Black, w * 0.025f, c - Offset(-w * 0.07f, h * 0.18f))
                        drawCircle(Color(0xFFF48FB1), w * 0.04f, c - Offset(0f, h * 0.11f))
                    }
                }
            }
        }
    }
    if (!started) StartOverlay("Uderzaj krety! Złoty = 3 punkty") { started = true }
}

// ============================ celownik ============================

@Composable
fun AimGame(env: GameEnv) {
    val rnd = remember { seeded(env.seed, "aim") }
    val total = 20
    var hits by remember { mutableIntStateOf(0) }
    var target by remember { mutableStateOf(Offset(0.5f, 0.5f)) }
    var started by remember { mutableStateOf(false) }
    var over by remember { mutableStateOf(false) }
    var penalty by remember { mutableLongStateOf(0L) }
    val ms = rememberStopwatch(started && !over)
    Column(Modifier.fillMaxSize()) {
        GameHud(pl.szept.core.GameCatalog.fmtTime((ms.value + penalty).toDouble()), "cel ${hits + 1}/$total")
        ArcadeBox(Modifier.weight(1f), Color(0xFF212121)) { sz ->
            val aspect = sz.height.toFloat() / sz.width
            Canvas(Modifier.fillMaxSize().pointerInput(started, over) {
                detectTapGestures(onPress = { pos ->
                    if (!started || over) return@detectTapGestures
                    val x = pos.x / size.width; val y = pos.y / size.height
                    val dx = x - target.x; val dy = (y - target.y) * aspect
                    if (sqrt(dx * dx + dy * dy) < 0.075f) {
                        hits++
                        if (hits >= total) { over = true; env.finish((ms.value + penalty).toDouble()) }
                        else target = Offset(0.1f + rnd.nextFloat() * 0.8f, 0.08f + rnd.nextFloat() * 0.84f)
                    } else penalty += 500
                })
            }) {
                val c = Offset(target.x * size.width, target.y * size.height)
                val r = 0.075f * size.width
                drawCircle(Color(0xFFE53935), r, c); drawCircle(Color.White, r * 0.66f, c); drawCircle(Color(0xFFE53935), r * 0.33f, c)
            }
        }
    }
    if (!started) StartOverlay("Trafiaj w cele jak najszybciej.\nPudło = +0,5 s") { started = true }
}

// ============================ klikacz ============================

@Composable
fun TapperGame(env: GameEnv) {
    var taps by remember { mutableIntStateOf(0) }
    var running by remember { mutableStateOf(false) }
    var over by remember { mutableStateOf(false) }
    val left = rememberCountdown(10, running && !over) { if (!over) { over = true; env.finish(taps.toDouble()) } }
    Column(Modifier.fillMaxSize()) {
        GameHud("$taps pkt", if (running) "${left.value} s" else "10 s")
        ArcadeBox(Modifier.weight(1f), Color(0xFF311B92)) { _ ->
            Canvas(Modifier.fillMaxSize().pointerInput(over) {
                awaitEachGesture {
                    awaitFirstDown()
                    if (!over) { running = true; taps++ }
                }
            }) {
                val r = min(size.width, size.height) * (0.3f + (taps % 2) * 0.02f)
                drawCircle(Color(0xFF7C4DFF), r, center)
                drawCircle(Color.White.copy(alpha = 0.2f), r * 0.7f, center)
            }
        }
    }
}

// ============================ łap jajka ============================

@Composable
fun EggsGame(env: GameEnv) {
    val rnd = remember { seeded(env.seed, "eggs") }
    // 4 rynny: 0 lewa górna, 1 lewa dolna, 2 prawa górna, 3 prawa dolna; jajko ma 5 pozycji (4 = na końcu)
    class Egg(val chute: Int, var pos: Int)
    val eggs = remember { mutableListOf<Egg>() }
    var basket by remember { mutableIntStateOf(0) }
    var score by remember { mutableIntStateOf(0) }
    var broken by remember { mutableIntStateOf(0) }
    var started by remember { mutableStateOf(false) }
    var over by remember { mutableStateOf(false) }
    var acc by remember { mutableFloatStateOf(0f) }
    var spawnIn by remember { mutableIntStateOf(0) }
    var flash by remember { mutableIntStateOf(-1) }
    val frame = rememberFrames(started && !over) { dt ->
        acc += dt
        val tick = max(0.16f, 0.55f - score * 0.006f)
        if (acc < tick) return@rememberFrames
        acc = 0f; flash = -1
        val done = mutableListOf<Egg>()
        for (e in eggs) { e.pos++; if (e.pos >= 5) done.add(e) }
        for (e in done) {
            eggs.remove(e)
            if (e.chute == basket) score++ else { broken++; flash = e.chute }
        }
        if (broken >= 3) { over = true; env.finish(score.toDouble()); return@rememberFrames }
        spawnIn--
        if (spawnIn <= 0) { eggs.add(Egg(rnd.nextInt(4), 0)); spawnIn = max(1, 3 - score / 20) + rnd.nextInt(2) }
    }
    Column(Modifier.fillMaxSize()) {
        GameHud("$score pkt", "stłuczone: $broken/3")
        ArcadeBox(Modifier.weight(1f), Color(0xFFCFD8C5)) { _ ->
            Canvas(Modifier.fillMaxSize().pointerInput(Unit) {
                detectTapGestures(onPress = { p ->
                    val left = p.x < size.width / 2; val topHalf = p.y < size.height / 2
                    basket = if (left) (if (topHalf) 0 else 1) else (if (topHalf) 2 else 3)
                })
            }) {
                frame.value
                val w = size.width; val h = size.height
                val lcd = Color(0xFF263238)
                // rynny
                fun chuteLine(c: Int): Pair<Offset, Offset> {
                    val left = c < 2; val y0 = if (c % 2 == 0) h * 0.18f else h * 0.5f
                    val x0 = if (left) w * 0.04f else w * 0.96f; val x1 = if (left) w * 0.34f else w * 0.66f
                    return Offset(x0, y0) to Offset(x1, y0 + h * 0.12f)
                }
                for (c in 0 until 4) { val (a, b) = chuteLine(c); drawLine(lcd, a + Offset(0f, 18f), b + Offset(0f, 18f), 8f) }
                for (e in eggs) {
                    val (a, b) = chuteLine(e.chute)
                    val t = e.pos / 4f
                    drawOval(lcd, Offset(a.x + (b.x - a.x) * t - 14f, a.y + (b.y - a.y) * t - 4f), Size(28f, 36f))
                }
                // wilk z koszykiem
                val cx = w / 2; val cy = h * 0.62f
                drawCircle(lcd, w * 0.07f, Offset(cx, cy - h * 0.16f))
                drawRoundRect(lcd, Offset(cx - w * 0.07f, cy - h * 0.1f), Size(w * 0.14f, h * 0.2f), CornerRadius(20f))
                val left = basket < 2; val up = basket % 2 == 0
                val hand = Offset(if (left) w * 0.33f else w * 0.67f, if (up) h * 0.36f else h * 0.68f)
                drawLine(lcd, Offset(cx, cy - h * 0.05f), hand, 10f)
                drawArc(lcd, 0f, 180f, false, hand - Offset(w * 0.06f, h * 0.02f), Size(w * 0.12f, h * 0.06f), style = Stroke(8f))
                if (flash >= 0) {
                    val (_, b) = chuteLine(flash)
                    drawCircle(Color(0xFFFFF59D), 26f, Offset(b.x, h * 0.9f)); drawCircle(Color(0xFFFFB300), 12f, Offset(b.x, h * 0.9f))
                }
            }
        }
    }
    if (!started) StartOverlay("Dotykaj rogów ekranu, by przesunąć koszyk") { started = true }
}
