@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package pl.szept.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.outlined.EmojiEmotions
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import pl.szept.ui.style.Anim
import pl.szept.ui.style.Chrome
import pl.szept.ui.style.GlassActions
import pl.szept.ui.style.GlassIconButton
import pl.szept.ui.style.InputStyle
import pl.szept.ui.style.LocalAnim
import pl.szept.ui.style.LocalStyle
import pl.szept.ui.style.backIcon
import pl.szept.ui.style.bevel
import pl.szept.ui.style.currentGlass
import pl.szept.ui.style.fieldColors
import pl.szept.ui.style.fieldShape
import pl.szept.ui.style.ios6Title
import pl.szept.ui.style.liquidGlass
import pl.szept.ui.style.pressScale

/*
 * Pole wiadomości, przyciski „załącz” i „wyślij” oraz nagłówek rozmowy w kształtach danego stylu.
 * Każdy wygląd ma własny układ: szklane przyciski nad treścią, rząd kolorowych ikon z polem „Aa”,
 * płaski pasek z ikoną spinacza, okno z przyciskiem „Wyślij”…
 */

/** Kolor tła pola wiadomości w stylu (null = szkło albo brak tła). */
@Composable
private fun fieldFill(): Color {
    val st = LocalStyle.current
    val d = st.dark
    return when (st.spec.chrome) {
        Chrome.GLASS, Chrome.TELEGRAM -> Color.Transparent
        Chrome.IOS6, Chrome.WINXP, Chrome.WIN7, Chrome.WIN95 -> Color.White
        Chrome.WHATSAPP -> if (d) Color(0xFF2A3942) else Color.White
        Chrome.MESSENGER, Chrome.FACEBOOK -> if (d) Color(0xFF3A3B3C) else Color(0xFFF0F2F5)
        Chrome.DISCORD -> Color(0xFF383A40)
        Chrome.ONEUI -> if (d) Color(0xFF1F1F21) else Color(0xFFEBEBEE)
        Chrome.WIN11 -> if (d) Color(0xFF2D2D2D) else Color.White
        else -> st.pal.chip.solid(st.pal.base).let { if (it.alpha < 0.5f) MaterialTheme.colorScheme.surfaceContainerHighest else it }
    }
}

/** Kształt i wysokość pola dla stylu. */
private class FieldLook(val shape: Shape, val minHeight: Dp, val padH: Dp)

@Composable
private fun fieldLook(): FieldLook {
    val st = LocalStyle.current
    return when (st.spec.input) {
        InputStyle.GLASS -> FieldLook(RoundedCornerShape(22.dp), 44.dp, 14.dp)
        InputStyle.IOS6 -> FieldLook(RoundedCornerShape(16.dp), 32.dp, 12.dp)
        InputStyle.MESSENGER -> FieldLook(RoundedCornerShape(20.dp), 38.dp, 14.dp)
        InputStyle.DISCORD -> FieldLook(RoundedCornerShape(20.dp), 40.dp, 14.dp)
        InputStyle.BAR -> FieldLook(RectangleShape, 48.dp, 4.dp)
        InputStyle.WINDOW -> when (st.spec.chrome) {
            Chrome.WIN11 -> FieldLook(RoundedCornerShape(4.dp), 38.dp, 10.dp)
            Chrome.WIN7 -> FieldLook(RoundedCornerShape(2.dp), 36.dp, 8.dp)
            else -> FieldLook(RectangleShape, 36.dp, 8.dp)
        }
        else -> FieldLook(RoundedCornerShape(24.dp), 48.dp, 16.dp)
    }
}

/**
 * Pole wpisywania wiadomości w stylu.
 * [replace] — zamiast tekstu pokaż coś innego (np. nagrywanie), ale [trailing] zostaje na swoim miejscu
 * (dzięki temu przycisk mikrofonu w polu nie traci przytrzymania).
 */
@Composable
fun MessageField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    leading: (@Composable RowScope.() -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    replace: (@Composable () -> Unit)? = null,
    singleLine: Boolean = false,
) {
    val st = LocalStyle.current
    if (st.spec.input == InputStyle.OUTLINED) {
        // klasyczny wygląd (jak dotąd) i terminal z „>” na początku
        val term = st.spec.chrome == Chrome.TERMINAL
        if (replace != null) {
            Box(modifier) { replace() }
            return
        }
        OutlinedTextField(
            value, onValueChange, modifier.heightIn(max = 160.dp),
            placeholder = { Text(placeholder) }, shape = st.spec.fieldShape(), maxLines = if (singleLine) 1 else 6, singleLine = singleLine,
            colors = if (term) fieldColors() else OutlinedTextFieldDefaults.colors(),
            leadingIcon = when {
                term -> ({ Text(">", color = st.pal.primary, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace) })
                leading != null -> ({ Row(verticalAlignment = Alignment.CenterVertically) { leading() } })
                else -> null
            },
            trailingIcon = trailing?.let { t -> { Row(verticalAlignment = Alignment.CenterVertically) { t() } } },
        )
        return
    }
    val look = fieldLook()
    val fill = fieldFill()
    val src = remember { MutableInteractionSource() }
    val focused by src.collectIsFocusedAsState()
    val chromeKind = st.spec.chrome
    val text = when {
        fill.alpha < 0.1f -> st.pal.onBase
        fill.luminance() > 0.45f -> Color(0xFF111111)
        else -> Color(0xFFF2F3F5)
    }
    val hint = when (chromeKind) {
        Chrome.DISCORD -> Color(0xFF8D929B)
        Chrome.IOS6 -> Color(0xFF9A9A9F)
        else -> text.copy(alpha = 0.5f)
    }
    val surface: Modifier = when (st.spec.input) {
        InputStyle.GLASS -> Modifier.liquidGlass(currentGlass(), 22.dp)
        InputStyle.IOS6 -> Modifier.clip(look.shape).background(Color.White)
            .drawBehind {
                // wklęsłe pole: cień przy górnej krawędzi
                drawRect(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.18f), Color.Transparent), 0f, 5.dp.toPx()))
            }
            .border(1.dp, Color(0xFF8F99A8), look.shape)
        InputStyle.WINDOW -> when (chromeKind) {
            Chrome.WIN95 -> Modifier.background(Color.White).drawBehind { bevel(this, size, raised = false, px = density) }
            Chrome.WIN11 -> Modifier.clip(look.shape).background(fill)
                .border(1.dp, if (st.dark) Color(0xFF3A3A3A) else Color(0xFFE5E5E5), look.shape)
                .drawBehind {
                    // dolna linia (akcent, gdy pole jest aktywne)
                    val h = if (focused) 2.dp.toPx() else 1.dp.toPx()
                    drawRect(if (focused) st.pal.primary else Color(0xFF8A8A8A), Offset(0f, size.height - h), Size(size.width, h))
                }
            Chrome.WIN7 -> Modifier.clip(look.shape).background(Color.White)
                .border(1.dp, if (focused) Color(0xFF3D7BAD) else Color(0xFFABADB3), look.shape)
            else -> Modifier.background(Color.White).border(1.dp, Color(0xFF7F9DB9))
        }
        InputStyle.BAR -> Modifier
        else -> Modifier.clip(look.shape).background(fill)
    }
    val style = LocalTextStyle.current.merge(MaterialTheme.typography.bodyLarge).copy(
        color = text,
        fontSize = when (chromeKind) {
            Chrome.MESSENGER, Chrome.FACEBOOK, Chrome.IOS6, Chrome.WINXP, Chrome.WIN7, Chrome.WIN95 -> 15.sp
            Chrome.GLASS -> 17.sp
            else -> 16.sp
        },
    )
    Row(
        modifier.then(surface).defaultMinSize(minHeight = look.minHeight)
            .padding(start = if (leading != null) 2.dp else look.padH, end = if (trailing != null) 3.dp else look.padH),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CompositionLocalProvider(LocalContentColor provides hint) { leading?.invoke(this) }
        Box(Modifier.weight(1f).padding(vertical = 6.dp), contentAlignment = Alignment.CenterStart) {
            if (replace != null) replace()
            else BasicTextField(
                value, onValueChange, Modifier.fillMaxWidth().heightIn(max = 150.dp),
                textStyle = style, cursorBrush = SolidColor(if (chromeKind == Chrome.GLASS) st.pal.mine.color else st.pal.primary),
                maxLines = if (singleLine) 1 else 6, singleLine = singleLine, interactionSource = src,
                decorationBox = { inner ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (value.isEmpty()) Text(placeholder, style = style.copy(color = hint), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        inner()
                    }
                },
            )
        }
        CompositionLocalProvider(LocalContentColor provides hint) { trailing?.invoke(this) }
    }
}

/** Mała ikona w polu (np. buźka, spinacz, aparat). */
@Composable
fun FieldIcon(icon: ImageVector, desc: String, onClick: () -> Unit, modifier: Modifier = Modifier, rotation: Float = 0f, tint: Color? = null) {
    IconButton(onClick = onClick, modifier = modifier.size(40.dp)) {
        Icon(icon, desc, Modifier.size(24.dp).rotate(rotation), tint = tint ?: LocalContentColor.current)
    }
}

/** Przycisk „załącz” w stylu (szklany plus, aparat iOS 6, plus w kółku, przycisk okna…). */
@Composable
fun AttachButton(onClick: () -> Unit) {
    val st = LocalStyle.current
    when (st.spec.input) {
        InputStyle.GLASS -> GlassIconButton(Icons.Default.Add, "Załącz", onClick, Modifier.padding(end = 8.dp))
        InputStyle.IOS6 -> Box(
            Modifier.padding(end = 6.dp, bottom = 1.dp).size(width = 38.dp, height = 30.dp).clip(RoundedCornerShape(6.dp))
                .background(Brush.verticalGradient(listOf(Color(0xFFF4F5F7), Color(0xFFC9CED6))))
                .border(1.dp, Color(0xFF8F99A8), RoundedCornerShape(6.dp))
                .clickable(onClick = onClick).semantics { contentDescription = "Załącz"; role = Role.Button },
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Default.PhotoCamera, null, Modifier.size(19.dp), tint = Color(0xFF59626F)) }
        InputStyle.MESSENGER -> FieldIcon(Icons.Default.AddCircle, "Załącz", onClick, tint = st.pal.primary)
        InputStyle.DISCORD -> Box(
            Modifier.padding(end = 8.dp).size(40.dp).clip(CircleShape).background(Color(0xFF383A40))
                .clickable(onClick = onClick).semantics { contentDescription = "Załącz"; role = Role.Button },
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Default.Add, null, tint = Color(0xFFB5BAC1)) }
        InputStyle.WINDOW -> WinButton(onClick, Modifier.padding(end = 6.dp), desc = "Załącz") {
            Icon(Icons.Default.AttachFile, null, Modifier.size(18.dp))
        }
        InputStyle.PILL -> when (st.spec.chrome) {
            Chrome.ONEUI -> FieldIcon(Icons.Default.Add, "Załącz", onClick, tint = st.pal.onBase)
            else -> Box(
                Modifier.padding(end = 8.dp).size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer)
                    .clickable(onClick = onClick).semantics { contentDescription = "Załącz"; role = Role.Button },
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Default.Add, null, tint = MaterialTheme.colorScheme.onSecondaryContainer) }
        }
        // BAR i klasyczny: ikona spinacza
        else -> IconButton(onClick = onClick) {
            Icon(Icons.Default.AttachFile, "Załącz", Modifier.rotate(if (st.spec.chrome == Chrome.TELEGRAM) 45f else 0f))
        }
    }
}

/** Przycisk wysyłania w stylu (niebieska strzałka iOS, „Wyślij” iOS 6, samolot Telegrama, przycisk okna…). */
@Composable
fun SendButton(editing: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    val st = LocalStyle.current
    val desc = if (editing) "Zapisz zmianę" else "Wyślij (przytrzymaj: czasowa lub zaplanuj)"
    val icon = if (editing) Icons.Default.Check else Icons.AutoMirrored.Filled.Send
    val src = remember { MutableInteractionSource() }
    val press = LocalAnim.current[Anim.PRESS]
    when (st.spec.input) {
        InputStyle.GLASS -> Box(
            Modifier.padding(end = 4.dp).size(34.dp).pressScale(src, press).clip(CircleShape).background(st.pal.mine.color)
                .combinedClickable(src, null, onClick = onClick, onLongClick = onLongClick)
                .semantics { contentDescription = desc; role = Role.Button },
            contentAlignment = Alignment.Center,
        ) { Icon(if (editing) Icons.Default.Check else Icons.Default.ArrowUpward, null, Modifier.size(20.dp), tint = Color.White) }
        InputStyle.IOS6 -> pl.szept.ui.style.Ios6Button(if (editing) "Zapisz" else "Wyślij", onClick, Modifier.padding(start = 2.dp, bottom = 1.dp), onLongClick)
        InputStyle.BAR, InputStyle.MESSENGER -> Box(
            Modifier.size(44.dp).pressScale(src, press).clip(CircleShape)
                .combinedClickable(src, androidx.compose.material3.ripple(bounded = false), onClick = onClick, onLongClick = onLongClick)
                .semantics { contentDescription = desc; role = Role.Button },
            contentAlignment = Alignment.Center,
        ) { Icon(icon, null, Modifier.size(26.dp), tint = st.pal.primary) }
        InputStyle.WINDOW -> WinButton(onClick, Modifier.padding(start = 2.dp), primary = true, desc = desc, onLongClick = onLongClick) {
            Text(if (editing) "Zapisz" else "Wyślij", fontSize = 14.sp)
        }
        else -> {
            val color = when (st.spec.chrome) {
                Chrome.WHATSAPP -> Color(0xFF00A884)
                Chrome.DISCORD -> Color(0xFF5865F2)
                Chrome.TERMINAL -> Color.Black
                else -> MaterialTheme.colorScheme.primary
            }
            val onColor = when (st.spec.chrome) {
                Chrome.WHATSAPP, Chrome.DISCORD -> Color.White
                Chrome.TERMINAL -> st.pal.primary
                else -> MaterialTheme.colorScheme.onPrimary
            }
            val size = when (st.spec.chrome) {
                Chrome.WHATSAPP -> 48.dp
                Chrome.DISCORD -> 40.dp
                else -> 44.dp
            }
            // przycisk na środku pola: klasyczne pole ma 56 dp, pigułka 48 dp
            val bottom = when (st.spec.input) {
                InputStyle.OUTLINED -> 4.dp
                InputStyle.PILL -> (48.dp - size) / 2
                else -> 0.dp
            }
            Surface(
                shape = if (st.spec.chrome == Chrome.TERMINAL) RectangleShape else CircleShape, color = color,
                border = if (st.spec.chrome == Chrome.TERMINAL) BorderStroke(1.dp, st.pal.primary) else null,
                modifier = Modifier.padding(bottom = bottom).size(size)
                    .combinedClickable(onClick = onClick, onLongClick = onLongClick),
            ) {
                Box(contentAlignment = Alignment.Center) { Icon(icon, desc, tint = onColor) }
            }
        }
    }
}

/** Wygląd przycisku mikrofonu w stylu: (wypełniony, kolor tła, kolor ikony, rozmiar, odstęp od dołu). */
class MicLook(val filled: Boolean, val color: Color?, val tint: Color, val size: Dp, val bottom: Dp = 0.dp)

@Composable
fun micLook(): MicLook {
    val st = LocalStyle.current
    val muted = st.pal.muted
    return when (st.spec.input) {
        InputStyle.GLASS -> MicLook(false, null, if (st.dark) Color(0xFFAEAEB2) else Color(0xFF7C7C80), 36.dp)
        InputStyle.BAR -> MicLook(false, null, muted, 44.dp)
        InputStyle.IOS6 -> MicLook(false, null, Color(0xFF59626F), 40.dp)
        InputStyle.MESSENGER -> MicLook(false, null, st.pal.primary, 40.dp)
        InputStyle.WINDOW -> MicLook(false, null, st.pal.onBase, 40.dp)
        InputStyle.DISCORD -> MicLook(true, Color(0xFF383A40), Color(0xFFB5BAC1), 40.dp)
        InputStyle.PILL -> MicLook(true, if (st.spec.chrome == Chrome.WHATSAPP) Color(0xFF00A884) else null, Color.White, 48.dp)
        else -> when (st.spec.chrome) {
            Chrome.TERMINAL -> MicLook(true, Color.Black, st.pal.primary, 44.dp, 4.dp)
            else -> MicLook(true, null, Color.White, 48.dp, 4.dp)
        }
    }
}

/** Kciuk w górę (szybka reakcja zamiast wysyłania pustej wiadomości). */
@Composable
fun LikeButton(onClick: () -> Unit) {
    val st = LocalStyle.current
    val src = remember { MutableInteractionSource() }
    Box(
        Modifier.size(44.dp).pressScale(src, LocalAnim.current[Anim.PRESS]).clip(CircleShape)
            .clickable(src, androidx.compose.material3.ripple(bounded = false), onClick = onClick)
            .semantics { contentDescription = "Wyślij kciuk w górę"; role = Role.Button },
        contentAlignment = Alignment.Center,
    ) { Icon(Icons.Default.ThumbUp, null, Modifier.size(26.dp), tint = st.pal.primary) }
}

/** Przycisk w stylu okien (Windows 95 — wypukły, XP — zaokrąglony z niebieską ramką, 7 — błyszczący, 11 — płaski). */
@Composable
fun WinButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    desc: String? = null,
    onLongClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val st = LocalStyle.current
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val (shape, text) = when (st.spec.chrome) {
        Chrome.WIN11 -> RoundedCornerShape(4.dp) to if (primary) Color.White else st.pal.onBase
        Chrome.WINXP -> RoundedCornerShape(3.dp) to Color.Black
        Chrome.WIN7 -> RoundedCornerShape(3.dp) to Color.Black
        else -> RectangleShape to Color.Black
    }
    val body: Modifier = when (st.spec.chrome) {
        Chrome.WIN95 -> Modifier.background(Color(0xFFC0C0C0)).drawBehind { bevel(this, size, raised = !pressed, px = density) }
        Chrome.WINXP -> Modifier.clip(shape)
            .background(Brush.verticalGradient(if (pressed) listOf(Color(0xFFE5E4DD), Color(0xFFE3E2DA)) else listOf(Color.White, Color(0xFFF2F2EE), Color(0xFFE3E2D7))))
            .border(1.dp, Color(0xFF003C74), shape)
        Chrome.WIN7 -> Modifier.clip(shape)
            .drawBehind {
                val top = if (pressed) listOf(Color(0xFFE5F4FC), Color(0xFFC4E5F6)) else listOf(Color(0xFFF2F2F2), Color(0xFFEBEBEB))
                val bottom = if (pressed) listOf(Color(0xFF98D1EF), Color(0xFF68B3DB)) else listOf(Color(0xFFDDDDDD), Color(0xFFCFCFCF))
                drawRect(Brush.verticalGradient(top, 0f, size.height / 2f), size = Size(size.width, size.height / 2f))
                drawRect(Brush.verticalGradient(bottom, size.height / 2f, size.height), Offset(0f, size.height / 2f), Size(size.width, size.height / 2f))
            }
            .border(1.dp, if (pressed) Color(0xFF2C628B) else Color(0xFF707070), shape)
        else -> Modifier.clip(shape)
            .background(if (primary) st.pal.primary.copy(alpha = if (pressed) 0.85f else 1f) else if (st.dark) Color(0xFF2D2D2D) else Color(0xFFFBFBFB))
            .border(1.dp, if (primary) Color.Transparent else if (st.dark) Color(0xFF3A3A3A) else Color(0xFFE0E0E0), shape)
    }
    Box(
        modifier.heightIn(min = 34.dp).defaultMinSize(minWidth = 40.dp).then(body)
            .combinedClickable(src, null, onClick = onClick, onLongClick = onLongClick)
            .semantics { role = Role.Button; if (desc != null) contentDescription = desc }
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .offset(x = if (pressed && st.spec.chrome == Chrome.WIN95) 1.dp else 0.dp, y = if (pressed && st.spec.chrome == Chrome.WIN95) 1.dp else 0.dp),
        contentAlignment = Alignment.Center,
    ) {
        CompositionLocalProvider(LocalContentColor provides text) { content() }
    }
}

/**
 * Nagłówek rozmowy w stylu Liquid Glass: szklane koło „wstecz”, awatar z nazwą w szklanej kapsułce
 * i szklana kapsułka z akcjami. Treść rozmowy przewija się pod spodem (z łagodnym wygaszeniem u góry).
 */
@Composable
fun GlassChatHeader(
    name: String,
    onBack: () -> Unit,
    onTitle: () -> Unit,
    avatar: @Composable () -> Unit,
    status: (@Composable () -> Unit)?,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit,
) {
    val st = LocalStyle.current
    val fade = if (st.pal.bg.isNotEmpty()) st.pal.bg.first() else st.pal.base
    Box(
        modifier.fillMaxWidth()
            .drawBehind {
                drawRect(Brush.verticalGradient(listOf(fade.copy(alpha = 0.92f), fade.copy(alpha = 0.55f), fade.copy(alpha = 0f))))
            }
            .statusBarsPadding().padding(horizontal = 14.dp).padding(top = 4.dp, bottom = 8.dp),
    ) {
        // przyciski na wysokości środka awatara (52 dp), jak w oryginale
        GlassIconButton(backIcon(), "Wstecz", onBack, Modifier.align(Alignment.TopStart).padding(top = 4.dp))
        Column(
            Modifier.align(Alignment.TopCenter).padding(horizontal = 64.dp)
                .clickable(remember { MutableInteractionSource() }, null, onClick = onTitle),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            avatar()
            Row(
                Modifier.offset(y = (-6).dp).liquidGlass(currentGlass()).padding(start = 11.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    name, maxLines = 1, overflow = TextOverflow.Ellipsis, color = st.pal.onBase,
                    style = MaterialTheme.typography.labelLarge.copy(fontSize = 13.sp, fontWeight = FontWeight.SemiBold),
                    modifier = Modifier.weight(1f, fill = false),
                )
                Icon(Icons.Default.ChevronRight, null, Modifier.size(14.dp), tint = st.pal.muted)
            }
            if (status != null) Box(Modifier.offset(y = (-4).dp)) { status() }
        }
        GlassActions(Modifier.align(Alignment.TopEnd).padding(top = 4.dp)) { actions() }
    }
}

/** Łagodne wygaszenie treści przy dolnej krawędzi (pod szklanym polem wiadomości). */
fun Modifier.glassBottomFade(color: Color): Modifier = drawBehind {
    drawRect(Brush.verticalGradient(listOf(color.copy(alpha = 0f), color.copy(alpha = 0.6f), color.copy(alpha = 0.9f))))
}

/** Odstęp między elementami w rzędzie ikon (Messenger). */
val IconRowSpacing = Arrangement.spacedBy(0.dp)

/** Pusty odstęp o danej szerokości (skrót). */
@Composable
fun HSpace(w: Dp) = Spacer(Modifier.width(w))

/** Pusty odstęp o danej wysokości (skrót). */
@Composable
fun VSpace(h: Dp) = Spacer(Modifier.height(h))

/** Ikona buźki — w polach wiadomości otwiera wybór GIF-ów. */
val EmojiIcon: ImageVector get() = Icons.Outlined.EmojiEmotions
