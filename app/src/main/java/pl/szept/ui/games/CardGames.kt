package pl.szept.ui.games

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import pl.szept.core.Games

/** Karta do gry (0..51). */
@Composable
fun PlayingCard(c: Int?, width: Dp = 64.dp, hidden: Boolean = false) {
    Box(Modifier.width(width).aspectRatio(0.7f).clip(RoundedCornerShape(8.dp))
        .background(if (hidden || c == null) Color(0xFF3949AB) else Color.White)
        .border(1.dp, Color(0xFF9E9E9E), RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
        if (!hidden && c != null) {
            Text(Games.cardName(c), color = if (Games.cardRed(c)) Color(0xFFD32F2F) else Color.Black, fontWeight = FontWeight.Bold, fontSize = (width.value / 3).sp)
        }
    }
}

/** Kostka do gry z oczkami. */
@Composable
fun Die(v: Int, size: Dp = 52.dp, held: Boolean = false, onClick: (() -> Unit)? = null) {
    val dots = when (v) {
        1 -> listOf(4); 2 -> listOf(0, 8); 3 -> listOf(0, 4, 8); 4 -> listOf(0, 2, 6, 8); 5 -> listOf(0, 2, 4, 6, 8); else -> listOf(0, 2, 3, 5, 6, 8)
    }
    Box(Modifier.size(size).clip(RoundedCornerShape(size / 5))
        .background(if (held) Color(0xFFFFE082) else Color.White)
        .border(2.dp, if (held) Color(0xFFFF8F00) else Color(0xFF9E9E9E), RoundedCornerShape(size / 5))
        .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier).padding(size / 8)) {
        Column(Modifier.fillMaxSize()) {
            for (r in 0 until 3) Row(Modifier.weight(1f)) {
                for (c in 0 until 3) Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                    if ((r * 3 + c) in dots) Box(Modifier.fillMaxSize(0.7f).clip(RoundedCornerShape(50)).background(Color.Black))
                }
            }
        }
    }
}

// ============================ oczko ============================

@Composable
fun BlackjackGame(env: GameEnv) {
    val rnd = remember { seeded(env.seed, "blackjack") }
    var deck by remember { mutableStateOf(Games.deck(rnd)) }
    var pos by remember { mutableIntStateOf(0) }
    fun draw(): Int { if (pos >= 40) { deck = Games.deck(rnd); pos = 0 }; return deck[pos++] }
    var chips by remember { mutableIntStateOf(100) }
    var hand by remember { mutableIntStateOf(1) }
    val player = remember { mutableStateListOf<Int>() }
    val dealer = remember { mutableStateListOf<Int>() }
    var phase by remember { mutableIntStateOf(0) }   // 0 = rozdanie, 1 = gracz, 2 = krupier, 3 = wynik
    var msg by remember { mutableStateOf("") }
    var over by remember { mutableStateOf(false) }
    val bet = 10
    fun settle() {
        val p = Games.bjValue(player); val d = Games.bjValue(dealer)
        val bj = p == 21 && player.size == 2
        val delta = when {
            p > 21 -> -bet
            bj && !(d == 21 && dealer.size == 2) -> bet * 3 / 2
            d > 21 || p > d -> bet
            p == d -> 0
            else -> -bet
        }
        chips += delta
        msg = when { delta > 0 -> if (bj) "Oczko! +$delta" else "Wygrana +$delta"; delta == 0 -> "Remis"; else -> "Przegrana $delta" }
        phase = 3
    }
    fun deal() {
        player.clear(); dealer.clear()
        player.add(draw()); dealer.add(draw()); player.add(draw()); dealer.add(draw())
        phase = 1; msg = ""
        if (Games.bjValue(player) == 21) { phase = 2 }
    }
    LaunchedEffect(phase) {
        if (phase == 2) {
            delay(500)
            if (Games.bjValue(player) <= 21) while (Games.bjValue(dealer) < 17) { dealer.add(draw()); delay(450) }
            settle()
        }
    }
    LaunchedEffect(Unit) { deal() }
    Column(Modifier.fillMaxSize().background(Color(0xFF1B5E20)).padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth()) {
            Text("$chips żetonów", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 22.sp, modifier = Modifier.weight(1f))
            Text("rozdanie $hand/10", color = Color.White.copy(alpha = 0.8f))
        }
        Spacer(Modifier.height(16.dp))
        Text("Krupier" + if (phase >= 2) ": ${Games.bjValue(dealer)}" else "", color = Color.White)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { dealer.forEachIndexed { i, c -> PlayingCard(c, hidden = i == 1 && phase < 2) } }
        Spacer(Modifier.weight(1f))
        Text(msg, color = Color(0xFFFFEB3B), fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.weight(1f))
        Text("Ty: ${Games.bjValue(player)}", color = Color.White)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { player.forEach { PlayingCard(it) } }
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            when (phase) {
                1 -> {
                    Button(onClick = { player.add(draw()); if (Games.bjValue(player) >= 21) phase = 2 }) { Text("Dobierz") }
                    OutlinedButton(onClick = { phase = 2 }, colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)) { Text("Pas") }
                }
                3 -> Button(onClick = {
                    if (over) return@Button
                    if (hand >= 10 || chips <= 0) { over = true; env.finish(chips.coerceAtLeast(0).toDouble()) } else { hand++; deal() }
                }) { Text(if (hand >= 10 || chips <= 0) "Koniec gry" else "Następne rozdanie") }
                else -> {}
            }
        }
    }
}

// ============================ wyższa czy niższa ============================

@Composable
fun HigherLowerGame(env: GameEnv) {
    val rnd = remember { seeded(env.seed, "hilo") }
    var deck by remember { mutableStateOf(Games.deck(rnd)) }
    var pos by remember { mutableIntStateOf(0) }
    var cur by remember { mutableIntStateOf(deck[0]) }
    var prev by remember { mutableStateOf<Int?>(null) }
    var streak by remember { mutableIntStateOf(0) }
    var over by remember { mutableStateOf(false) }
    fun guess(higher: Boolean) {
        if (over) return
        pos++
        if (pos >= 52) { deck = Games.deck(rnd); pos = 0 }
        val n = deck[pos]
        val a = Games.cardRank(cur); val b = Games.cardRank(n)
        prev = cur; cur = n
        when {
            a == b -> {}
            (b > a) == higher -> streak++
            else -> { over = true; env.finish(streak.toDouble()) }
        }
    }
    Column(Modifier.fillMaxSize().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        GameHud("$streak pkt", "z rzędu")
        Spacer(Modifier.weight(1f))
        Row(verticalAlignment = Alignment.CenterVertically) {
            prev?.let { PlayingCard(it, 70.dp); Spacer(Modifier.width(16.dp)) }
            PlayingCard(cur, 120.dp)
        }
        Spacer(Modifier.weight(1f))
        Text("Następna karta będzie…", style = MaterialTheme.typography.titleMedium)
        Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { guess(true) }, Modifier.weight(1f).height(60.dp)) { Icon(Icons.Default.ArrowUpward, null); Text(" Wyższa") }
            Button(onClick = { guess(false) }, Modifier.weight(1f).height(60.dp)) { Icon(Icons.Default.ArrowDownward, null); Text(" Niższa") }
        }
        Text("As jest najwyższy. Ta sama wartość — grasz dalej.", style = MaterialTheme.typography.labelMedium)
    }
}

// ============================ jednoręki bandyta ============================

private val SLOT_ICONS: List<Pair<ImageVector, Color>> = listOf(
    Icons.Default.Diamond to Color(0xFF29B6F6), Icons.Default.Star to Color(0xFFFFCA28), Icons.Default.Favorite to Color(0xFFE53935),
    Icons.Default.Bolt to Color(0xFFFFA000), Icons.Default.LocalFlorist to Color(0xFFEC407A), Icons.Default.Icecream to Color(0xFF8D6E63),
)
private val SLOT_PAY = listOf(100, 50, 30, 20, 15, 10)

@Composable
fun SlotsGame(env: GameEnv) {
    val rnd = remember { seeded(env.seed, "slots") }
    var coins by remember { mutableIntStateOf(50) }
    var spins by remember { mutableIntStateOf(0) }
    var reels by remember { mutableStateOf(listOf(0, 1, 2)) }
    var spinning by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf("") }
    var over by remember { mutableStateOf(false) }
    var go by remember { mutableIntStateOf(0) }
    // rzadsze symbole wygrywają więcej
    val weights = listOf(1, 2, 3, 4, 5, 6)
    fun pick(): Int { var x = rnd.nextInt(weights.sum()); for (i in weights.indices) { if (x < weights[i]) return i; x -= weights[i] }; return 5 }
    LaunchedEffect(go) {
        if (go == 0) return@LaunchedEffect
        spinning = true
        val result = List(3) { pick() }
        repeat(10) { reels = List(3) { (it + reels[it] + 1) % 6 }; delay(70) }
        reels = result; spinning = false
        val win = when {
            result.toSet().size == 1 -> SLOT_PAY[result[0]]
            result.toSet().size == 2 -> 5
            else -> 0
        }
        coins += win
        msg = if (win > 0) "Wygrana: $win" else ""
        if (spins >= 20 || coins < 5) { over = true; env.finish(coins.toDouble()) }
    }
    Column(Modifier.fillMaxSize().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        GameHud("$coins żetonów", "zakręcenia: $spins/20")
        Spacer(Modifier.weight(1f))
        Row(Modifier.clip(RoundedCornerShape(16.dp)).background(Color(0xFF263238)).padding(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            reels.forEach { s ->
                Box(Modifier.size(84.dp).clip(RoundedCornerShape(10.dp)).background(Color.White), contentAlignment = Alignment.Center) {
                    Icon(SLOT_ICONS[s].first, null, tint = SLOT_ICONS[s].second, modifier = Modifier.size(56.dp))
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(msg, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color(0xFFFFA000))
        Spacer(Modifier.weight(1f))
        Button(onClick = { if (!spinning && !over && coins >= 5) { coins -= 5; spins++; msg = ""; go++ } }, enabled = !spinning && !over,
            modifier = Modifier.fillMaxWidth().height(60.dp)) { Text("Zakręć (5 żetonów)", fontSize = 18.sp) }
        Spacer(Modifier.height(8.dp))
        Text("3 takie same: " + SLOT_PAY.joinToString(" / ") + " · 2 takie same: 5", style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center)
    }
}

// ============================ generał (Yahtzee) ============================

private val YAHTZEE_CATS = listOf("Jedynki", "Dwójki", "Trójki", "Czwórki", "Piątki", "Szóstki", "Trzy jednakowe", "Kareta", "Full", "Mały strit", "Duży strit", "Generał", "Szansa")

fun yahtzeeScore(cat: Int, d: List<Int>): Int {
    val counts = (1..6).map { v -> d.count { it == v } }
    val sum = d.sum()
    fun straight(n: Int) = (1..(7 - n)).any { s -> (s until s + n).all { it in d } }
    return when (cat) {
        in 0..5 -> counts[cat] * (cat + 1)
        6 -> if (counts.any { it >= 3 }) sum else 0
        7 -> if (counts.any { it >= 4 }) sum else 0
        8 -> if (counts.contains(3) && counts.contains(2)) 25 else 0
        9 -> if (straight(4)) 30 else 0
        10 -> if (straight(5)) 40 else 0
        11 -> if (counts.contains(5)) 50 else 0
        else -> sum
    }
}

@Composable
fun YahtzeeGame(env: GameEnv) {
    val rnd = remember { seeded(env.seed, "yahtzee") }
    var dice by remember { mutableStateOf(List(5) { 1 + rnd.nextInt(6) }) }
    val held = remember { mutableStateListOf(false, false, false, false, false) }
    var rolls by remember { mutableIntStateOf(1) }
    val scores = remember { mutableStateListOf<Int?>(*arrayOfNulls<Int>(13)) }
    var over by remember { mutableStateOf(false) }
    val upper = (0..5).sumOf { scores[it] ?: 0 }
    val total = scores.sumOf { it ?: 0 } + if (upper >= 63) 35 else 0
    fun roll() { dice = dice.mapIndexed { i, v -> if (held[i]) v else 1 + rnd.nextInt(6) }; rolls++ }
    fun choose(cat: Int) {
        if (scores[cat] != null || over) return
        scores[cat] = yahtzeeScore(cat, dice)
        if (scores.all { it != null }) {
            over = true
            val up = (0..5).sumOf { scores[it] ?: 0 }
            env.finish((scores.sumOf { it ?: 0 } + if (up >= 63) 35 else 0).toDouble())
            return
        }
        for (i in 0 until 5) held[i] = false
        dice = List(5) { 1 + rnd.nextInt(6) }; rolls = 1
    }
    Column(Modifier.fillMaxSize().padding(12.dp)) {
        GameHud("$total pkt", "rzut $rolls/3")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            dice.forEachIndexed { i, v -> Die(v, 56.dp, held[i]) { if (rolls < 3) held[i] = !held[i] } }
        }
        Text("Dotknij kości, aby ją zatrzymać", Modifier.fillMaxWidth().padding(4.dp), textAlign = TextAlign.Center, style = MaterialTheme.typography.labelSmall)
        Button(onClick = { roll() }, enabled = rolls < 3 && !over, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.Casino, null); Text("  Rzuć jeszcze raz") }
        Spacer(Modifier.height(6.dp))
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            YAHTZEE_CATS.forEachIndexed { i, name ->
                val done = scores[i]
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable(enabled = done == null) { choose(i) }
                    .background(if (done == null) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f) else Color.Transparent).padding(horizontal = 12.dp, vertical = 9.dp)) {
                    Text(name, Modifier.weight(1f), fontWeight = if (done == null) FontWeight.Medium else FontWeight.Normal)
                    Text(done?.toString() ?: "+" + yahtzeeScore(i, dice), color = if (done == null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold)
                }
                if (i == 5) Text("Premia za górę (63+): ${if (upper >= 63) "+35" else "$upper/63"}", Modifier.padding(horizontal = 12.dp, vertical = 4.dp), style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

// ============================ zgadnij liczbę ============================

@Composable
fun GuessNumGame(env: GameEnv) {
    val target = remember { 1 + seeded(env.seed, "guess").nextInt(1000) }
    var input by remember { mutableStateOf("") }
    val tries = remember { mutableStateListOf<Pair<Int, Int>>() }
    var over by remember { mutableStateOf(false) }
    var lo by remember { mutableIntStateOf(1) }
    var hi by remember { mutableIntStateOf(1000) }
    fun submit() {
        val n = input.toIntOrNull() ?: return
        if (n !in 1..1000 || over) return
        val h = target.compareTo(n)
        tries.add(n to h); input = ""
        if (h > 0) lo = maxOf(lo, n + 1) else if (h < 0) hi = minOf(hi, n - 1)
        if (h == 0) { over = true; env.finish(tries.size.toDouble()) }
    }
    Column(Modifier.fillMaxSize().padding(16.dp).imePadding(), horizontalAlignment = Alignment.CenterHorizontally) {
        GameHud("${tries.size} prób", "zakres $lo–$hi")
        Text("Liczba od 1 do 1000", style = MaterialTheme.typography.titleMedium)
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 12.dp)) {
            OutlinedTextField(input, { input = it.filter { c -> c.isDigit() }.take(4) }, Modifier.weight(1f), singleLine = true, label = { Text("Twoja liczba") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            Spacer(Modifier.width(8.dp))
            Button(onClick = { submit() }, enabled = input.isNotEmpty() && !over) { Text("Sprawdź") }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            tries.reversed().forEach { (n, h) ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("$n", fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(80.dp))
                    Icon(when { h > 0 -> Icons.Default.ArrowUpward; h < 0 -> Icons.Default.ArrowDownward; else -> Icons.Default.Check }, null)
                    Text(when { h > 0 -> " szukana jest większa"; h < 0 -> " szukana jest mniejsza"; else -> " trafione!" })
                }
            }
        }
    }
}
