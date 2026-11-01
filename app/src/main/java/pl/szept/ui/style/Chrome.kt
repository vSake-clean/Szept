package pl.szept.ui.style

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowBackIos
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldColors
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * „Rama” interfejsu w danym stylu: paski tytułu, przyciski i pole wiadomości jak w oryginalnym systemie/aplikacji.
 */

/** Bieżąca rama. */
@Composable
fun chrome(): Chrome = LocalStyle.current.spec.chrome

/** Szkło dla bieżącego stylu. */
@Composable
fun currentGlass(tinted: Color? = null): GlassLook {
    val st = LocalStyle.current
    return remember(st.dark, tinted) { glassLookFor(st.dark, tinted) }
}

/** Szklany okrągły przycisk z ikoną (iOS 26). */
@Composable
fun GlassIconButton(icon: ImageVector, desc: String?, onClick: () -> Unit, modifier: Modifier = Modifier, size: Dp = 44.dp, tint: Color? = null, enabled: Boolean = true) {
    val st = LocalStyle.current
    val src = remember { MutableInteractionSource() }
    Box(
        modifier.size(size).pressScale(src, LocalAnim.current[Anim.PRESS])
            .liquidGlass(currentGlass(tint))
            .clip(CircleShape)
            .clickable(interactionSource = src, indication = ripple(), enabled = enabled, onClick = onClick)
            .semantics { role = Role.Button; if (desc != null) contentDescription = desc },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, Modifier.size(size * 0.48f), tint = if (tint != null) Color.White else if (st.dark) Color.White else Color.Black)
    }
}

/** Szklana kapsułka z kilkoma przyciskami (iOS 26 grupuje powiązane akcje). */
@Composable
fun GlassActions(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    val st = LocalStyle.current
    Row(
        modifier.height(44.dp).liquidGlass(currentGlass()).padding(horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CompositionLocalProvider(LocalContentColor provides if (st.dark) Color.White else Color.Black) { content() }
    }
}

/** Ikona „wstecz” zgodna z systemem (iOS i One UI — szewron). */
@Composable
fun backIcon(): ImageVector = when (chrome()) {
    Chrome.GLASS, Chrome.IOS6, Chrome.ONEUI -> Icons.AutoMirrored.Filled.ArrowBackIos
    else -> Icons.AutoMirrored.Filled.ArrowBack
}

/** Pasek ekranu z przyciskiem „wstecz” w stylu systemu. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StyleBackBar(title: String, onBack: () -> Unit, hasActions: Boolean, actions: @Composable () -> Unit) {
    val st = LocalStyle.current
    when (st.spec.chrome) {
        Chrome.GLASS -> Box(Modifier.fillMaxWidth().statusBarsPadding().height(60.dp).padding(horizontal = 14.dp)) {
            GlassIconButton(Icons.AutoMirrored.Filled.ArrowBackIos, "Wstecz", onBack, Modifier.align(Alignment.CenterStart))
            Text(
                title, Modifier.align(Alignment.Center).padding(horizontal = 66.dp), maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleMedium.copy(fontSize = 17.sp, fontWeight = FontWeight.SemiBold), color = st.pal.onBase,
            )
            if (hasActions) GlassActions(Modifier.align(Alignment.CenterEnd)) { actions() }
        }
        Chrome.IOS6 -> Ios6Bar(title, onBack, hasActions, actions)
        else -> TopAppBar(
            title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = barTitleWeight()) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(backIcon(), "Wstecz") } },
            actions = { actions() },
            colors = styleBarColors(),
            modifier = Modifier.styleTopBar(st),
        )
    }
}

@Composable
fun barTitleWeight(): FontWeight? = when (chrome()) {
    Chrome.WINXP, Chrome.WIN95, Chrome.ONEUI, Chrome.WHATSAPP, Chrome.TELEGRAM, Chrome.MESSENGER -> FontWeight.Bold
    Chrome.WIN7 -> FontWeight.Normal
    else -> null
}

/** Pasek iOS 6: niebiesko-szary gradient, wytłoczony napis i przycisk „wstecz” w kształcie strzałki. */
@Composable
fun Ios6Bar(title: String, onBack: (() -> Unit)?, hasActions: Boolean, actions: @Composable () -> Unit, titleContent: (@Composable () -> Unit)? = null) {
    val st = LocalStyle.current
    Box(Modifier.fillMaxWidth().styleTopBar(st).statusBarsPadding().height(48.dp)) {
        if (onBack != null) Ios6BackButton("Wstecz", onBack, Modifier.align(Alignment.CenterStart).padding(start = 8.dp))
        Box(Modifier.align(Alignment.Center).padding(horizontal = 92.dp)) {
            if (titleContent != null) titleContent()
            else Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = ios6Title())
        }
        if (hasActions) Row(Modifier.align(Alignment.CenterEnd).padding(end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            CompositionLocalProvider(LocalContentColor provides Color.White) { actions() }
        }
    }
}

/** Wytłoczony biały napis jak w iOS 6. */
fun ios6Title(size: Int = 20) = TextStyle(
    color = Color.White, fontSize = size.sp, fontWeight = FontWeight.Bold,
    shadow = Shadow(Color(0xFF2B3A52).copy(alpha = 0.8f), Offset(0f, -2f), 1f),
)

/** Przycisk „wstecz” iOS 6 (wypukły, ze strzałkowym lewym bokiem). */
@Composable
fun Ios6BackButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier.height(32.dp).widthIn(min = 64.dp)
            .drawBehind {
                val r = 5.dp.toPx(); val a = 11.dp.toPx(); val w = size.width; val h = size.height
                val p = Path().apply {
                    moveTo(a, 0f); lineTo(w - r, 0f); quadraticTo(w, 0f, w, r); lineTo(w, h - r); quadraticTo(w, h, w - r, h)
                    lineTo(a, h); lineTo(0f, h / 2f); close()
                }
                drawPath(p, Brush.verticalGradient(listOf(Color(0xFF8DA0BC), Color(0xFF5E7598), Color(0xFF4D6488))))
                drawPath(p, Color(0xFF2E3D57), style = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx()))
            }
            .clickable(onClick = onClick).semantics { contentDescription = label; role = Role.Button }
            .padding(start = 16.dp, end = 9.dp),
        contentAlignment = Alignment.Center,
    ) { Text(label, style = ios6Title(13)) }
}

/** Błyszczący przycisk iOS 6 (np. „Wyślij”). */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun Ios6Button(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, onLongClick: (() -> Unit)? = null) {
    Box(
        modifier.height(30.dp).clip(RoundedCornerShape(15.dp))
            .background(Brush.verticalGradient(listOf(Color(0xFF8CBDFB), Color(0xFF3E87EA), Color(0xFF2668D8))))
            .border(BorderStroke(1.dp, Color(0xFF1F4F9F)), RoundedCornerShape(15.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .semantics { role = Role.Button }
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) { Text(label, style = ios6Title(14)) }
}

/** Kolory pola wiadomości w stylu (np. białe pole WhatsAppa, szklane w iOS 26, ramka XP). */
@Composable
fun fieldColors(): TextFieldColors {
    val st = LocalStyle.current
    val cs = MaterialTheme.colorScheme
    val (container, border) = when (st.spec.chrome) {
        Chrome.GLASS -> Color.Transparent to Color.Transparent
        Chrome.IOS6 -> Color.White to Color(0xFF9AA4B2)
        Chrome.WHATSAPP -> (if (st.dark) Color(0xFF2A3942) else Color.White) to Color.Transparent
        Chrome.TELEGRAM -> Color.Transparent to Color.Transparent
        Chrome.MESSENGER -> (if (st.dark) Color(0xFF3A3B3C) else Color(0xFFF0F2F5)) to Color.Transparent
        Chrome.EXPRESSIVE -> cs.surfaceContainerHighest to Color.Transparent
        Chrome.ONEUI -> (if (st.dark) Color(0xFF1F1F21) else Color(0xFFEBEBEE)) to Color.Transparent
        Chrome.WIN11 -> (if (st.dark) Color(0xFF2D2D2D) else Color.White) to (if (st.dark) Color(0xFF3A3A3A) else Color(0xFFE0E0E0))
        Chrome.WINXP -> Color.White to Color(0xFF7F9DB9)
        Chrome.WIN7 -> Color.White to Color(0xFFABADB3)
        Chrome.WIN95 -> Color.White to Color(0xFF808080)
        Chrome.TERMINAL -> Color.Black to st.pal.line
        else -> return OutlinedTextFieldDefaults.colors()
    }
    return OutlinedTextFieldDefaults.colors(
        focusedContainerColor = container, unfocusedContainerColor = container, disabledContainerColor = container,
        focusedBorderColor = if (border == Color.Transparent) Color.Transparent else st.pal.primary, unfocusedBorderColor = border,
    )
}

/** Wygląd przycisku wysyłania. */
enum class SendLook { CIRCLE, IOS_ARROW, IOS6_TEXT, PLANE }

@Composable
fun sendLook(): SendLook = when (chrome()) {
    Chrome.GLASS -> SendLook.IOS_ARROW
    Chrome.IOS6 -> SendLook.IOS6_TEXT
    Chrome.TELEGRAM -> SendLook.PLANE
    else -> SendLook.CIRCLE
}

/** Kolor przycisku wysyłania (WhatsApp — zielony itd.). */
@Composable
fun sendColor(): Color {
    val st = LocalStyle.current
    return when (st.spec.chrome) {
        Chrome.WHATSAPP -> Color(0xFF00A884)
        Chrome.GLASS -> st.pal.mine.color
        else -> MaterialTheme.colorScheme.primary
    }
}

/**
 * Pozycja wiersza w grupie kart: 0 = sam, 1 = pierwszy, 2 = środkowy, 3 = ostatni.
 * [segmented] = osobne segmenty z małymi odstępami (Material 3 Expressive), inaczej jedna wspólna karta (One UI, HyperOS).
 */
@Composable
fun Modifier.groupedCard(pos: Int, segmented: Boolean = false): Modifier {
    val st = LocalStyle.current
    val card = st.pal.card ?: Fill(MaterialTheme.colorScheme.surfaceContainerLow, MaterialTheme.colorScheme.onSurface)
    val big = when (st.spec.chrome) {
        Chrome.ONEUI -> 26.dp
        Chrome.HYPER, Chrome.COLOROS -> 22.dp
        else -> 24.dp
    }
    val small = if (segmented) 6.dp else 0.dp
    val shape = when (pos) {
        1 -> RoundedCornerShape(big, big, small, small)
        2 -> RoundedCornerShape(small)
        3 -> RoundedCornerShape(small, small, big, big)
        else -> RoundedCornerShape(big)
    }
    val gap = if (segmented) 3.dp else 0.dp
    return this.padding(horizontal = 12.dp).padding(top = if (pos == 0 || pos == 1) 4.dp else gap / 2, bottom = if (pos == 0 || pos == 3) 4.dp else gap / 2)
        .clip(shape).background(card.solid(st.pal.base))
}

/** Czy lista rozmów ma wiersze w kartach połączonych w grupę (One UI, Expressive). */
@Composable
fun groupedRows(): Boolean = chrome() == Chrome.ONEUI || chrome() == Chrome.EXPRESSIVE

/** Rysuje błyszczący pasek Aero (Windows 7) — szkło nad tapetą, z połyskiem w górnej połowie. */
internal fun androidx.compose.ui.graphics.drawscope.DrawScope.aeroBar(pal: Pal) {
    drawRect(Brush.verticalGradient(pal.bg.ifEmpty { listOf(Color(0xFF0B3D7A), Color(0xFF1468B8)) }.take(2)))
    drawRect(Brush.horizontalGradient(listOf(Color(0xFFBFE4FF).copy(alpha = 0.35f), Color(0xFF6FB2EE).copy(alpha = 0.55f), Color(0xFFBFE4FF).copy(alpha = 0.3f))))
    drawRect(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.5f), Color.White.copy(alpha = 0.12f)), 0f, size.height * 0.5f), size = Size(size.width, size.height * 0.5f))
    drawRect(Color(0xFF1B3A63).copy(alpha = 0.7f), Offset(0f, size.height - 1.dp.toPx()), Size(size.width, 1.dp.toPx()))
}

/** Odstęp ikon w szklanej kapsułce. */
val GlassActionSpacing = Arrangement.spacedBy(0.dp)
