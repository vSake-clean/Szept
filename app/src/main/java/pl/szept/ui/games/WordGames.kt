@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package pl.szept.ui.games

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import pl.szept.core.GameCatalog
import pl.szept.core.GameData
import kotlin.math.abs
import kotlin.math.max

private val GREEN = Color(0xFF6AAA64)
private val YELLOW = Color(0xFFC9B458)
private val GRAY = Color(0xFF787C7E)

/** Klawiatura z polskimi literami. [state] litera → kolor (podpowiedzi). */
@Composable
fun PolishKeyboard(state: Map<Char, Color> = emptyMap(), disabled: Set<Char> = emptySet(), onKey: (Char) -> Unit, onBack: (() -> Unit)? = null, onEnter: (() -> Unit)? = null) {
    val rows = listOf("QWERTYUIOP", "ASDFGHJKL", "ZXCVBNM", "ĄĆĘŁŃÓŚŹŻ")
    Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        rows.forEachIndexed { ri, row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally)) {
                if (ri == 2 && onEnter != null) Key("OK", Modifier.weight(1.5f), MaterialTheme.colorScheme.primary, Color.White) { onEnter() }
                row.forEach { ch ->
                    val bg = state[ch] ?: MaterialTheme.colorScheme.surfaceVariant
                    val off = ch in disabled
                    Key(ch.toString(), Modifier.weight(1f), if (off) bg.copy(alpha = 0.3f) else bg, if (state[ch] != null) Color.White else MaterialTheme.colorScheme.onSurface) { if (!off) onKey(ch) }
                }
                if (ri == 2 && onBack != null) Box(Modifier.weight(1.5f).height(46.dp).clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.surfaceVariant).clickable { onBack() },
                    contentAlignment = Alignment.Center) { Icon(Icons.AutoMirrored.Filled.Backspace, "Usuń") }
            }
        }
    }
}

@Composable
private fun Key(label: String, modifier: Modifier, bg: Color, fg: Color, onClick: () -> Unit) {
    Box(modifier.height(46.dp).clip(RoundedCornerShape(6.dp)).background(bg).clickable { onClick() }, contentAlignment = Alignment.Center) {
        Text(label, color = fg, fontWeight = FontWeight.Bold, fontSize = 15.sp)
    }
}

// ============================ literaki (Wordle) ============================

@Composable
fun WordleGame(env: GameEnv) {
    val target = remember { GameData.WORDS5[(abs(env.seed) % GameData.WORDS5.size).toInt()] }
    val rows = remember { mutableStateListOf<String>() }
    var cur by remember { mutableStateOf("") }
    var over by remember { mutableStateOf(false) }
    fun colors(guess: String): List<Color> {
        val res = MutableList(5) { GRAY }
        val left = target.toMutableList()
        for (i in 0 until 5) if (guess[i] == target[i]) { res[i] = GREEN; left.remove(guess[i]) }
        for (i in 0 until 5) if (res[i] != GREEN && guess[i] in left) { res[i] = YELLOW; left.remove(guess[i]) }
        return res
    }
    val keyState = remember(rows.size) {
        val m = HashMap<Char, Color>()
        for (g in rows) colors(g).forEachIndexed { i, c ->
            val prev = m[g[i]]
            if (prev != GREEN && !(prev == YELLOW && c == GRAY)) m[g[i]] = c
        }
        m
    }
    fun submit() {
        if (cur.length != 5 || over) return
        rows.add(cur)
        if (cur == target) { over = true; env.finish(rows.size.toDouble()) }
        else if (rows.size >= 6) { over = true; env.fail("Szukane słowo: $target") }
        cur = ""
    }
    Column(Modifier.fillMaxSize()) {
        GameHud("próba ${minOf(rows.size + 1, 6)}/6", "5 liter")
        Column(Modifier.weight(1f).fillMaxWidth().padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically), horizontalAlignment = Alignment.CenterHorizontally) {
            for (r in 0 until 6) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                val word = rows.getOrNull(r) ?: if (r == rows.size) cur else ""
                val cols = rows.getOrNull(r)?.let { colors(it) }
                for (i in 0 until 5) {
                    Box(Modifier.size(52.dp).clip(RoundedCornerShape(6.dp)).background(cols?.get(i) ?: Color.Transparent)
                        .border(2.dp, if (cols == null) MaterialTheme.colorScheme.outline else Color.Transparent, RoundedCornerShape(6.dp)), contentAlignment = Alignment.Center) {
                        Text(word.getOrNull(i)?.toString() ?: "", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = if (cols != null) Color.White else MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
        }
        PolishKeyboard(keyState, onKey = { if (cur.length < 5 && !over) cur += it }, onBack = { cur = cur.dropLast(1) }, onEnter = { submit() })
    }
}

// ============================ wisielec (sam) ============================

@Composable
fun HangSoloGame(env: GameEnv) {
    val word = remember { GameData.WORDS[(abs(env.seed) % GameData.WORDS.size).toInt()] }
    val guessed = remember { mutableStateListOf<Char>() }
    var over by remember { mutableStateOf(false) }
    val wrong = guessed.count { it !in word }
    val lives = 8
    Column(Modifier.fillMaxSize()) {
        GameHud("$wrong ${GameCatalog.plural(wrong.toLong(), "błąd", "błędy", "błędów")}", "życia: ${lives - wrong}")
        Column(Modifier.weight(1f).fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            HangmanDrawing(wrong, lives, Modifier.size(160.dp))
            Spacer(Modifier.height(16.dp))
            Text(word.map { if (it in guessed || over) it else '_' }.joinToString(" "), fontSize = 28.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, letterSpacing = 2.sp)
        }
        PolishKeyboard(guessed.associateWith { if (it in word) GREEN else GRAY }, disabled = guessed.toSet(), onKey = { ch ->
            if (over || ch in guessed) return@PolishKeyboard
            guessed.add(ch)
            val w = guessed.count { it !in word }
            if (word.all { it in guessed }) { over = true; env.finish(w.toDouble()) }
            else if (w >= lives) { over = true; env.fail("Słowo: $word") }
        })
    }
}

/** Rysunek wisielca: [wrong] z [max] błędów. */
@Composable
fun HangmanDrawing(wrong: Int, max: Int, modifier: Modifier) {
    val c = MaterialTheme.colorScheme.onSurface
    androidx.compose.foundation.Canvas(modifier) {
        val w = size.width; val h = size.height; val sw = 6f
        val steps = listOf<() -> Unit>(
            { drawLine(c, androidx.compose.ui.geometry.Offset(0.1f * w, 0.95f * h), androidx.compose.ui.geometry.Offset(0.6f * w, 0.95f * h), sw) },
            { drawLine(c, androidx.compose.ui.geometry.Offset(0.25f * w, 0.95f * h), androidx.compose.ui.geometry.Offset(0.25f * w, 0.05f * h), sw) },
            { drawLine(c, androidx.compose.ui.geometry.Offset(0.25f * w, 0.05f * h), androidx.compose.ui.geometry.Offset(0.7f * w, 0.05f * h), sw) },
            { drawLine(c, androidx.compose.ui.geometry.Offset(0.7f * w, 0.05f * h), androidx.compose.ui.geometry.Offset(0.7f * w, 0.2f * h), sw) },
            { drawCircle(c, 0.08f * w, androidx.compose.ui.geometry.Offset(0.7f * w, 0.28f * h), style = androidx.compose.ui.graphics.drawscope.Stroke(sw)) },
            { drawLine(c, androidx.compose.ui.geometry.Offset(0.7f * w, 0.36f * h), androidx.compose.ui.geometry.Offset(0.7f * w, 0.62f * h), sw) },
            { drawLine(c, androidx.compose.ui.geometry.Offset(0.7f * w, 0.42f * h), androidx.compose.ui.geometry.Offset(0.58f * w, 0.52f * h), sw); drawLine(c, androidx.compose.ui.geometry.Offset(0.7f * w, 0.42f * h), androidx.compose.ui.geometry.Offset(0.82f * w, 0.52f * h), sw) },
            { drawLine(c, androidx.compose.ui.geometry.Offset(0.7f * w, 0.62f * h), androidx.compose.ui.geometry.Offset(0.6f * w, 0.8f * h), sw); drawLine(c, androidx.compose.ui.geometry.Offset(0.7f * w, 0.62f * h), androidx.compose.ui.geometry.Offset(0.8f * w, 0.8f * h), sw) },
        )
        val shown = if (max >= steps.size) wrong else wrong * steps.size / max
        steps.take(shown.coerceAtMost(steps.size)).forEach { it() }
    }
}

// ============================ anagramy ============================

@Composable
fun AnagramGame(env: GameEnv) {
    val rnd = remember { seeded(env.seed, "anagram") }
    val pool = remember { (GameData.WORDS5 + GameData.WORDS.filter { it.length in 6..8 }).shuffled(rnd) }
    var idx by remember { mutableIntStateOf(0) }
    val word = pool[idx % pool.size]
    val letters = remember(idx) { word.toList().shuffled(rnd).let { s -> if (s.joinToString("") == word && word.toSet().size > 1) s.reversed() else s } }
    val used = remember(idx) { mutableStateListOf<Int>() }
    var score by remember { mutableIntStateOf(0) }
    var over by remember { mutableStateOf(false) }
    var started by remember { mutableStateOf(false) }
    val left = rememberCountdown(60, started && !over) { if (!over) { over = true; env.finish(score.toDouble()) } }
    val built = used.map { letters[it] }.joinToString("")
    LaunchedEffect(built) {
        if (built.length == word.length) {
            if (built == word) { score += word.length; delay(250); idx++ } else { delay(400); used.clear() }
        }
    }
    Column(Modifier.fillMaxSize().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        GameHud("$score pkt", "${left.value} s")
        Spacer(Modifier.weight(1f))
        Text(built.ifEmpty { " " }, fontSize = 34.sp, fontWeight = FontWeight.Bold, letterSpacing = 4.sp,
            color = if (built.length == word.length && built != word) Color(0xFFE53935) else MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(24.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            letters.forEachIndexed { i, ch ->
                Box(Modifier.size(52.dp).clip(RoundedCornerShape(10.dp)).background(if (i in used) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.primaryContainer)
                    .clickable(enabled = i !in used && started && !over) { used.add(i) }, contentAlignment = Alignment.Center) {
                    if (i !in used) Text(ch.toString(), fontSize = 24.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        Spacer(Modifier.weight(1f))
        Row {
            OutlinedButton(onClick = { if (used.isNotEmpty()) used.removeAt(used.lastIndex) }) { Text("Cofnij literę") }
            Spacer(Modifier.width(12.dp))
            TextButton(onClick = { idx++ }) { Text("Pomiń") }
        }
    }
    if (!started) StartOverlay("Ułóż słowa z pomieszanych liter. Punkty = długość słowa") { started = true }
}

// ============================ szybkie pisanie ============================

@Composable
fun TypingGame(env: GameEnv) {
    val sentence = remember { GameData.SENTENCES[(abs(env.seed) % GameData.SENTENCES.size).toInt()] }
    var text by remember { mutableStateOf("") }
    var started by remember { mutableStateOf(false) }
    var over by remember { mutableStateOf(false) }
    val ms = rememberStopwatch(started && !over)
    Column(Modifier.fillMaxSize().padding(16.dp).imePadding()) {
        GameHud(GameCatalog.fmtTime(ms.value.toDouble()), "${text.length}/${sentence.length}")
        Spacer(Modifier.height(12.dp))
        val ok = sentence.startsWith(text)
        Text(buildAnnotatedString {
            val good = text.commonPrefixWith(sentence).length
            withStyle(SpanStyle(color = GREEN, fontWeight = FontWeight.Bold)) { append(sentence.substring(0, good)) }
            if (!ok && good < sentence.length) withStyle(SpanStyle(color = Color.White, background = Color(0xFFE53935))) { append(sentence[good]) }
            append(sentence.substring(if (!ok) minOf(good + 1, sentence.length) else good))
        }, fontSize = 22.sp, lineHeight = 32.sp)
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(text, { v ->
            if (over) return@OutlinedTextField
            if (!started && v.isNotEmpty()) started = true
            text = v.take(sentence.length + 5)
            if (text == sentence) { over = true; env.finish(ms.value.coerceAtLeast(1).toDouble()) }
        }, Modifier.fillMaxWidth(), isError = !ok, label = { Text("Przepisz zdanie (z polskimi znakami)") })
        Text("Czas rusza od pierwszej litery.", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 6.dp))
    }
}

// ============================ quiz wiedzy ============================

@Composable
fun QuizGame(env: GameEnv) {
    val questions = remember { GameData.QUIZ.shuffled(seeded(env.seed, "quiz")).take(10) }
    var idx by remember { mutableIntStateOf(0) }
    var score by remember { mutableIntStateOf(0) }
    var chosen by remember { mutableIntStateOf(-1) }
    var left by remember { mutableIntStateOf(15) }
    var over by remember { mutableStateOf(false) }
    var started by remember { mutableStateOf(false) }
    LaunchedEffect(idx, started) {
        if (!started) return@LaunchedEffect
        left = 15; chosen = -1
        while (left > 0 && chosen < 0) { delay(1000); if (chosen < 0) left-- }
        if (chosen < 0) chosen = 99
        delay(1300)
        if (idx < questions.lastIndex) idx++ else { over = true; env.finish(score.toDouble()) }
    }
    val q = questions[idx]
    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
        GameHud("$score pkt", "pytanie ${idx + 1}/10 · ${left} s")
        LinearProgressIndicator(progress = { left / 15f }, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(20.dp))
        Text(q.q, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(20.dp))
        q.o.forEachIndexed { k, opt ->
            val color = when {
                chosen < 0 -> MaterialTheme.colorScheme.surfaceVariant
                k == q.c -> GREEN
                k == chosen -> Color(0xFFE53935)
                else -> MaterialTheme.colorScheme.surfaceVariant
            }
            Box(Modifier.fillMaxWidth().padding(vertical = 5.dp).clip(RoundedCornerShape(14.dp)).background(color)
                .clickable(enabled = chosen < 0 && started) { chosen = k; if (k == q.c) score += 100 + left * 10 }.padding(16.dp)) {
                Text(opt, fontSize = 18.sp, color = if (chosen >= 0 && (k == q.c || k == chosen)) Color.White else MaterialTheme.colorScheme.onSurface)
            }
        }
    }
    if (!started) StartOverlay("10 pytań, 15 sekund na każde.\nSzybsza dobra odpowiedź = więcej punktów") { started = true }
}

// ============================ prawda czy fałsz ============================

@Composable
fun TrueFalseGame(env: GameEnv) {
    val order = remember { GameData.TRUE_FALSE.shuffled(seeded(env.seed, "tf")) }
    var idx by remember { mutableIntStateOf(0) }
    var score by remember { mutableIntStateOf(0) }
    var flash by remember { mutableStateOf<Boolean?>(null) }
    var over by remember { mutableStateOf(false) }
    var started by remember { mutableStateOf(false) }
    val left = rememberCountdown(60, started && !over) { if (!over) { over = true; env.finish(score.toDouble()) } }
    LaunchedEffect(flash) { if (flash != null) { delay(350); flash = null } }
    val (text, truth) = order[idx % order.size]
    fun answer(a: Boolean) {
        if (!started || over) return
        val good = a == truth
        score = max(0, score + if (good) 1 else -1)
        flash = good; idx++
        if (idx >= order.size) { over = true; env.finish(score.toDouble()) }
    }
    Column(Modifier.fillMaxSize().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        GameHud("$score pkt", "${left.value} s")
        Spacer(Modifier.weight(1f))
        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))
            .background(when (flash) { true -> GREEN.copy(alpha = 0.3f); false -> Color(0xFFE53935).copy(alpha = 0.3f); null -> MaterialTheme.colorScheme.surfaceVariant })
            .padding(28.dp), contentAlignment = Alignment.Center) {
            Text(text, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        }
        Spacer(Modifier.weight(1f))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { answer(true) }, Modifier.weight(1f).height(64.dp), colors = ButtonDefaults.buttonColors(containerColor = GREEN)) {
                Icon(Icons.Default.Check, null); Spacer(Modifier.width(6.dp)); Text("Prawda", fontSize = 18.sp)
            }
            Button(onClick = { answer(false) }, Modifier.weight(1f).height(64.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE53935))) {
                Icon(Icons.Default.Close, null); Spacer(Modifier.width(6.dp)); Text("Fałsz", fontSize = 18.sp)
            }
        }
        Spacer(Modifier.height(16.dp))
    }
    if (!started) StartOverlay("Oceniaj zdania jak najszybciej.\nBłąd = −1 pkt") { started = true }
}
