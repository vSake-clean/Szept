package pl.szept.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import pl.szept.ui.style.Anim
import pl.szept.ui.style.Chrome
import pl.szept.ui.style.FabStyle
import pl.szept.ui.style.GlassActions
import pl.szept.ui.style.GlassIconButton
import pl.szept.ui.style.HomeHeader
import pl.szept.ui.style.LocalAnim
import pl.szept.ui.style.LocalStyle
import pl.szept.ui.style.bevel
import pl.szept.ui.style.styleBarColors
import pl.szept.ui.style.styleTopBar
import pl.szept.ui.style.titleFx

/*
 * Nagłówek listy rozmów, pole wyszukiwania i przycisk nowej rozmowy w kształcie danego stylu:
 * duży tytuł nad listą (zwija się przy przewijaniu), wielki nagłówek do obsługi jedną ręką,
 * napis-logo z okrągłymi przyciskami, tytuł z kropek…
 */

/** Akcja w nagłówku listy rozmów. */
class HomeAction(val icon: ImageVector, val label: String, val onClick: () -> Unit)

/** Czy styl ma tytuł nad listą (przewijany razem z nią). */
@Composable
fun hasListTitle(): Boolean = LocalStyle.current.spec.homeHeader.let { it == HomeHeader.LARGE_TITLE || it == HomeHeader.BIG }

/** Czy akcje mieszczą się w nagłówku (w stylach z kapsułką lub okrągłymi przyciskami część trafia do menu). */
@Composable
fun compactHomeActions(): Boolean = LocalStyle.current.let { it.spec.chrome == Chrome.GLASS || it.spec.homeHeader == HomeHeader.WORDMARK }

/**
 * Pasek nad listą rozmów.
 * [collapsed] — duży tytuł zniknął z ekranu (wtedy mały tytuł pojawia się na pasku).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeTopBar(
    status: @Composable () -> Unit,
    collapsed: Boolean,
    actions: List<HomeAction>,
    onMenu: () -> Unit,
    menu: @Composable () -> Unit,
    onCompose: (() -> Unit)?,
) {
    val st = LocalStyle.current
    val spec = st.spec
    val smallTitle by animateFloatAsState(if (collapsed) 1f else 0f, label = "tytul")
    when {
        spec.chrome == Chrome.GLASS -> Box(
            Modifier.fillMaxWidth()
                .drawBehind {
                    val fade = st.pal.base
                    drawRect(Brush.verticalGradient(listOf(fade.copy(alpha = 0.94f), fade.copy(alpha = 0.6f * smallTitle + 0.2f), fade.copy(alpha = 0f))))
                }
                .statusBarsPadding().height(60.dp).padding(horizontal = 14.dp),
        ) {
            Text(
                "Szept", Modifier.align(Alignment.Center).graphicsLayer { alpha = smallTitle },
                style = MaterialTheme.typography.titleMedium.copy(fontSize = 17.sp, fontWeight = FontWeight.SemiBold), color = st.pal.onBase,
            )
            Row(Modifier.align(Alignment.CenterEnd), verticalAlignment = Alignment.CenterVertically) {
                GlassActions {
                    actions.forEach { a -> IconButton(onClick = a.onClick) { Icon(a.icon, a.label) } }
                    Box {
                        IconButton(onClick = onMenu) { Icon(Icons.Default.MoreHoriz, "Menu") }
                        menu()
                    }
                }
                if (onCompose != null) {
                    Spacer(Modifier.width(8.dp))
                    GlassIconButton(Icons.Default.EditNote, "Nowa rozmowa", onCompose)
                }
            }
        }
        spec.homeHeader == HomeHeader.WORDMARK -> Row(
            Modifier.fillMaxWidth().styleTopBar(st).statusBarsPadding().height(60.dp).padding(start = 16.dp, end = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "szept", Modifier.titleFx().semantics { heading() }, color = st.pal.primary,
                fontSize = 30.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.04).em,
            )
            Spacer(Modifier.weight(1f))
            if (onCompose != null) RoundBarButton(Icons.Default.Edit, "Nowa rozmowa", onCompose)
            actions.forEach { a -> RoundBarButton(a.icon, a.label, a.onClick) }
            Box {
                RoundBarButton(Icons.Default.MoreHoriz, "Menu", onMenu)
                menu()
            }
        }
        spec.homeHeader == HomeHeader.LARGE_TITLE || spec.homeHeader == HomeHeader.BIG -> TopAppBar(
            colors = styleBarColors(), modifier = Modifier.styleTopBar(st),
            title = {
                Text(
                    "Szept", Modifier.graphicsLayer { alpha = smallTitle; translationY = (1f - smallTitle) * 12.dp.toPx() },
                    fontWeight = spec.headWeight ?: FontWeight.SemiBold, fontFamily = spec.titleFont, maxLines = 1,
                )
            },
            actions = {
                if (onCompose != null) IconButton(onClick = onCompose) { Icon(Icons.Default.EditNote, "Nowa rozmowa") }
                actions.forEach { a -> IconButton(onClick = a.onClick) { Icon(a.icon, a.label) } }
                Box {
                    IconButton(onClick = onMenu) { Icon(Icons.Default.MoreVert, "Menu") }
                    menu()
                }
            },
        )
        else -> TopAppBar(
            colors = styleBarColors(),
            modifier = Modifier.styleTopBar(st),
            title = {
                Column {
                    if (spec.chrome == Chrome.TERMINAL) TerminalTitle()
                    else Text(
                        "Szept", fontWeight = FontWeight.Bold, modifier = Modifier.titleFx(),
                        style = if (spec.homeHeader == HomeHeader.COMPACT) MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold) else androidx.compose.material3.LocalTextStyle.current,
                    )
                    status()
                }
            },
            actions = {
                if (onCompose != null) IconButton(onClick = onCompose) { Icon(Icons.Default.Edit, "Nowa rozmowa") }
                actions.forEach { a -> IconButton(onClick = a.onClick) { Icon(a.icon, a.label) } }
                Box {
                    IconButton(onClick = onMenu) { Icon(Icons.Default.MoreVert, "Menu") }
                    menu()
                }
            },
        )
    }
}

/** Okrągły szary przycisk na pasku (jak w aplikacji społecznościowej). */
@Composable
private fun RoundBarButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    val st = LocalStyle.current
    Box(
        Modifier.padding(horizontal = 4.dp).size(40.dp).clip(CircleShape).background(st.pal.chip.solid(st.pal.base))
            .clickable(onClick = onClick).semantics { contentDescription = label; role = Role.Button },
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, Modifier.size(22.dp), tint = st.pal.onBase) }
}

/** Tytuł terminala: znak zachęty z migającym kursorem. */
@Composable
private fun TerminalTitle() {
    val st = LocalStyle.current
    val on = LocalAnim.current[Anim.TITLE]
    val blink = remember { Animatable(1f) }
    LaunchedEffect(on) {
        if (!on) { blink.snapTo(1f); return@LaunchedEffect }
        while (true) { blink.snapTo(1f); kotlinx.coroutines.delay(500); blink.snapTo(0f); kotlinx.coroutines.delay(500) }
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = "Szept" }) {
        Text("szept@telefon:~$ ", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = st.pal.primary, fontSize = 18.sp)
        Box(Modifier.size(width = 10.dp, height = 18.dp).graphicsLayer { alpha = blink.value }.background(st.pal.primary))
    }
}

/** Duży tytuł nad listą rozmów (przewija się razem z listą). */
@Composable
fun ListTitleBlock(status: @Composable () -> Unit, onSearch: () -> Unit, topPadding: Dp = 0.dp) {
    val st = LocalStyle.current
    val spec = st.spec
    if (spec.homeHeader == HomeHeader.BIG) {
        // wielki nagłówek do obsługi jedną ręką: górna część ekranu jest pusta, tytuł na środku
        val h = (LocalConfiguration.current.screenHeightDp * 0.26f).dp.coerceIn(150.dp, 260.dp)
        Column(Modifier.fillMaxWidth().height(h).padding(top = topPadding), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Text(
                "Szept", Modifier.titleFx().semantics { heading() }, color = st.pal.onBase,
                fontSize = 38.sp, fontWeight = spec.headWeight ?: FontWeight.Normal, fontFamily = spec.titleFont,
            )
            Spacer(Modifier.height(4.dp))
            status()
        }
        if (spec.searchPill) SearchPill(onSearch, Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
        return
    }
    Column(Modifier.fillMaxWidth().padding(top = topPadding).padding(horizontal = 16.dp).padding(top = 2.dp, bottom = 6.dp)) {
        if (spec.dotTitle) DotTitle("SZEPT", st.pal.onBase, Modifier.padding(top = 6.dp, bottom = 6.dp))
        else Text(
            "Szept", Modifier.titleFx().semantics { heading() }, color = st.pal.onBase,
            fontSize = if (spec.chrome == Chrome.HYPER) 32.sp else 34.sp,
            fontWeight = spec.headWeight ?: FontWeight.Bold, fontFamily = spec.titleFont, letterSpacing = (-0.01).em,
        )
        status()
        if (spec.searchPill || spec.chrome == Chrome.GLASS) SearchPill(onSearch, Modifier.padding(top = 10.dp))
    }
}

/** Pole wyszukiwania pod tytułem (otwiera wyszukiwanie). */
@Composable
fun SearchPill(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val st = LocalStyle.current
    val d = st.dark
    val (fill, shape) = when (st.spec.chrome) {
        Chrome.GLASS -> Color(0xFF767680).copy(alpha = if (d) 0.24f else 0.12f) to RoundedCornerShape(10.dp)
        Chrome.DISCORD -> Color(0xFF1E1F22) to RoundedCornerShape(8.dp)
        Chrome.MESSENGER, Chrome.FACEBOOK -> (if (d) Color(0xFF3A3B3C) else Color(0xFFF0F2F5)) to RoundedCornerShape(20.dp)
        Chrome.HYPER -> (if (d) Color(0xFF1F1F1F) else Color(0xFFEDEDED)) to RoundedCornerShape(24.dp)
        else -> st.pal.chip.solid(st.pal.base) to RoundedCornerShape(24.dp)
    }
    val h = when (st.spec.chrome) {
        Chrome.GLASS, Chrome.DISCORD, Chrome.MESSENGER, Chrome.FACEBOOK -> 38.dp
        else -> 44.dp
    }
    Row(
        modifier.fillMaxWidth().height(h).clip(shape).background(fill).clickable(onClick = onClick)
            .semantics { role = Role.Button; contentDescription = "Szukaj" }.padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.Search, null, Modifier.size(20.dp), tint = st.pal.muted)
        Spacer(Modifier.width(8.dp))
        Text("Szukaj", color = st.pal.muted, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

// ======================= tytuł z kropek (wyświetlacz matrycowy) =======================

/** Litery 5×7 (kolumny od lewej, bity od góry). */
private val DOT_GLYPHS: Map<Char, List<String>> = mapOf(
    'S' to listOf(".###.", "#...#", "#....", ".###.", "....#", "#...#", ".###."),
    'Z' to listOf("#####", "....#", "...#.", "..#..", ".#...", "#....", "#####"),
    'E' to listOf("#####", "#....", "#....", "####.", "#....", "#....", "#####"),
    'P' to listOf("####.", "#...#", "#...#", "####.", "#....", "#....", "#...."),
    'T' to listOf("#####", "..#..", "..#..", "..#..", "..#..", "..#..", "..#.."),
    'A' to listOf(".###.", "#...#", "#...#", "#####", "#...#", "#...#", "#...#"),
    'Y' to listOf("#...#", "#...#", ".#.#.", "..#..", "..#..", "..#..", "..#.."),
    'L' to listOf("#....", "#....", "#....", "#....", "#....", "#....", "#####"),
    ' ' to listOf(".....", ".....", ".....", ".....", ".....", ".....", "....."),
)

/** Napis z kropek (jak na wyświetlaczu z diodami). */
@Composable
fun DotTitle(text: String, color: Color, modifier: Modifier = Modifier, dot: Dp = 5.dp) {
    val glyphs = text.uppercase().mapNotNull { DOT_GLYPHS[it] }
    val cols = glyphs.size * 6 - 1
    Canvas(modifier.size(width = dot * cols, height = dot * 7).semantics { contentDescription = text; heading() }) {
        val step = size.height / 7f
        val r = step * 0.38f
        glyphs.forEachIndexed { gi, g ->
            for (row in 0 until 7) for (col in 0 until 5) {
                val on = g[row][col] == '#'
                val c = Offset((gi * 6 + col) * step + step / 2f, row * step + step / 2f)
                if (on) drawCircle(color, r, c) else drawCircle(color.copy(alpha = 0.07f), r * 0.7f, c)
            }
        }
    }
}

// ======================= przycisk nowej rozmowy =======================

/** Przycisk „nowa rozmowa” w stylu (okrągły, rozszerzony z napisem, kwadratowy) — z animacją wejścia. */
@Composable
fun HomeFab(onClick: () -> Unit) {
    val st = LocalStyle.current
    val anim = LocalAnim.current
    val fabOn = anim[Anim.BUTTONS]
    val enter = remember { Animatable(if (fabOn) 0f else 1f) }
    LaunchedEffect(Unit) { enter.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = 260f)) }
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val turn by animateFloatAsState(if (pressed && fabOn) -25f else 0f, spring(dampingRatio = 0.35f), label = "fab")
    val grow = Modifier.graphicsLayer { scaleX = enter.value; scaleY = enter.value; rotationZ = (1f - enter.value) * -90f }
    when (st.spec.fab) {
        FabStyle.NONE -> {}
        FabStyle.EXTENDED -> ExtendedFloatingActionButton(
            onClick = onClick, interactionSource = src,
            modifier = Modifier.graphicsLayer { scaleX = enter.value; scaleY = enter.value },
            icon = { Icon(Icons.AutoMirrored.Filled.Chat, null) },
            text = { Text("Nowa rozmowa") },
            shape = if (st.spec.chrome == Chrome.EXPRESSIVE) RoundedCornerShape(20.dp) else RoundedCornerShape(16.dp),
        )
        FabStyle.SQUARE -> {
            val (shape, color, content) = when (st.spec.chrome) {
                Chrome.WHATSAPP -> Triple(RoundedCornerShape(16.dp), if (st.dark) Color(0xFF21C063) else Color(0xFF1DAA61), if (st.dark) Color(0xFF0B141A) else Color.White)
                Chrome.WIN11 -> Triple(RoundedCornerShape(6.dp), st.pal.primary, st.pal.onPrimary)
                Chrome.WIN95, Chrome.WINXP, Chrome.WIN7 -> Triple(RectangleShape, Color(0xFFC0C0C0), Color.Black)
                Chrome.TERMINAL -> Triple(RectangleShape, Color.Black, st.pal.primary)
                else -> Triple(RoundedCornerShape(12.dp), MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer)
            }
            val frame = when (st.spec.chrome) {
                Chrome.WIN95, Chrome.WINXP, Chrome.WIN7 -> Modifier.drawBehind { bevel(this, size, raised = !pressed, px = density) }
                Chrome.TERMINAL -> Modifier.border(BorderStroke(1.dp, st.pal.primary))
                else -> Modifier
            }
            FloatingActionButton(
                onClick = onClick, interactionSource = src, shape = shape, containerColor = color, contentColor = content,
                modifier = grow.then(frame),
            ) { Icon(Icons.Default.Edit, "Nowa rozmowa", Modifier.graphicsLayer { rotationZ = turn }) }
        }
        FabStyle.CIRCLE -> FloatingActionButton(onClick = onClick, interactionSource = src, modifier = grow) {
            Icon(Icons.Default.Edit, "Nowa rozmowa", Modifier.graphicsLayer { rotationZ = turn })
        }
    }
}

/** Linia oddzielająca wiersze wewnątrz wspólnej karty (rysowana na dole wiersza). */
fun Modifier.innerDivider(color: Color, start: Dp, end: Dp = 16.dp): Modifier = drawBehind {
    val h = 0.6f * density
    drawRect(color, Offset(start.toPx(), size.height - h), Size(size.width - start.toPx() - end.toPx(), h))
}
