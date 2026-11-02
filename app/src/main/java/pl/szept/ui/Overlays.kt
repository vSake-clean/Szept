package pl.szept.ui

import android.os.Build
import android.view.WindowManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import pl.szept.ui.style.Anim
import pl.szept.ui.style.Chrome
import pl.szept.ui.style.DialogAnim
import pl.szept.ui.style.LocalAnim
import pl.szept.ui.style.LocalStyle
import pl.szept.ui.style.MenuAnim
import pl.szept.ui.style.StyleState
import pl.szept.ui.style.bevel
import pl.szept.ui.style.liquidGlass
import pl.szept.ui.style.currentGlass

/*
 * Menu i okna dialogowe w stylu oryginału — wygląd i animacja otwierania zależą od stylu
 * (i od ustawień „Animacje zaawansowane” → „Otwieranie i zamykanie menu”, „Okna i panele”).
 */

/** Położenie menu przy przycisku: pod nim (albo nad, gdy brak miejsca), wyrównane do bliższej krawędzi. */
private class MenuPosition(val density: Density, val offset: DpOffset, val onOrigin: (TransformOrigin) -> Unit) : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        val margin = with(density) { 8.dp.roundToPx() }
        val dx = with(density) { offset.x.roundToPx() }
        val dy = with(density) { offset.y.roundToPx() }
        val rightAligned = anchorBounds.center.x > windowSize.width / 2
        var x = if (rightAligned) anchorBounds.right - popupContentSize.width + dx else anchorBounds.left + dx
        x = x.coerceIn(margin, (windowSize.width - popupContentSize.width - margin).coerceAtLeast(margin))
        val below = anchorBounds.bottom + popupContentSize.height + margin <= windowSize.height
        var y = if (below) anchorBounds.bottom + dy else anchorBounds.top - popupContentSize.height - dy
        y = y.coerceIn(margin, (windowSize.height - popupContentSize.height - margin).coerceAtLeast(margin))
        val ox = if (popupContentSize.width > 0) ((anchorBounds.center.x - x).toFloat() / popupContentSize.width).coerceIn(0f, 1f) else 0.5f
        onOrigin(TransformOrigin(ox, if (below) 0f else 1f))
        return IntOffset(x, y)
    }
}

/** Kształt, kolor i ozdoby menu w stylu. */
@Composable
private fun Modifier.menuSurface(st: StyleState): Modifier {
    val cs = MaterialTheme.colorScheme
    val dark = st.dark
    return when (st.spec.chrome) {
        // menu to osobne okno — szkło matowe (bez nagrania treści z głównego okna)
        Chrome.GLASS -> this.liquidGlass(currentGlass(), 22.dp, backdrop = null).clip(RoundedCornerShape(22.dp))
        Chrome.WIN95 -> this.background(Color(0xFFC0C0C0)).drawWithContent { drawContent(); bevel(this, size, raised = true, px = density) }
        Chrome.WINXP -> this.shadow(4.dp, RectangleShape).background(Color.White).border(BorderStroke(1.dp, Color(0xFFACA899)))
        Chrome.WIN7 -> this.shadow(4.dp, RectangleShape).background(Color(0xFFF0F0F0)).border(BorderStroke(1.dp, Color(0xFF979797)))
        Chrome.WIN11 -> this.shadow(8.dp, RoundedCornerShape(8.dp)).clip(RoundedCornerShape(8.dp))
            .background(if (dark) Color(0xFF2C2C2C) else Color(0xFFF9F9F9)).border(BorderStroke(1.dp, if (dark) Color(0xFF3A3A3A) else Color(0xFFE5E5E5)), RoundedCornerShape(8.dp))
        Chrome.TERMINAL -> this.background(Color.Black).border(BorderStroke(1.dp, st.pal.primary))
        Chrome.ONEUI, Chrome.HYPER, Chrome.OXYGEN, Chrome.COLOROS, Chrome.EMUI, Chrome.NOTHING -> {
            val shape = RoundedCornerShape(if (st.spec.chrome == Chrome.ONEUI) 26.dp else 20.dp)
            this.shadow(12.dp, shape, ambientColor = Color.Black.copy(alpha = 0.2f), spotColor = Color.Black.copy(alpha = 0.2f)).clip(shape)
                .background((st.pal.card ?: st.pal.theirs).solid(st.pal.base))
        }
        Chrome.EXPRESSIVE -> this.shadow(3.dp, RoundedCornerShape(16.dp)).clip(RoundedCornerShape(16.dp)).background(cs.surfaceContainer)
        Chrome.TELEGRAM, Chrome.MESSENGER, Chrome.FACEBOOK -> this.shadow(6.dp, RoundedCornerShape(10.dp)).clip(RoundedCornerShape(10.dp)).background(cs.surfaceContainerLowest)
        Chrome.IOS6 -> this.shadow(6.dp, RoundedCornerShape(8.dp)).clip(RoundedCornerShape(8.dp)).background(Color(0xFFF5F6F8))
        else -> this.shadow(3.dp, RoundedCornerShape(4.dp)).clip(RoundedCornerShape(4.dp)).background(cs.surfaceContainer)
    }
}

/** Menu (zamiennik menu Material) — wygląd i animacja otwierania zależne od stylu. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StyleMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    offset: DpOffset = DpOffset(0.dp, 0.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    val st = LocalStyle.current
    val anim = LocalAnim.current
    val kind = if (anim[Anim.MENUS]) st.motion.menu else MenuAnim.NONE
    if (st.motion.menu == MenuAnim.SHEET) {
        // czat serwerowy: menu jako arkusz wysuwany od dołu
        if (expanded) ModalBottomSheet(onDismissRequest = onDismissRequest, containerColor = Color(0xFF2B2D31), contentColor = Color(0xFFF2F3F5)) {
            Column(modifier.navigationBarsPadding().padding(bottom = 12.dp), content = content)
        }
        return
    }
    val state = remember { MutableTransitionState(false) }
    state.targetState = expanded
    if (!state.currentState && !state.targetState && state.isIdle) return
    var origin by remember { mutableStateOf(TransformOrigin(1f, 0f)) }
    val density = LocalDensity.current
    val m = st.motion
    val f = anim.factor
    val enter: EnterTransition = when (kind) {
        MenuAnim.GROW -> scaleIn(spring(dampingRatio = m.damping.coerceAtMost(0.78f), stiffness = 520f / f), initialScale = 0.45f, transformOrigin = origin) + fadeIn(tween((110 * f).toInt()))
        MenuAnim.DROP -> scaleIn(tween((120 * f).toInt(), easing = m.easing), initialScale = 0.8f, transformOrigin = origin) + fadeIn(tween((90 * f).toInt()))
        MenuAnim.SLIDE -> slideInVertically(tween((200 * f).toInt(), easing = m.easing)) { -it / 5 } + fadeIn(tween((160 * f).toInt()))
        MenuAnim.FADE -> fadeIn(tween((150 * f).toInt()))
        else -> EnterTransition.None
    }
    val exit: ExitTransition = when (kind) {
        MenuAnim.GROW -> scaleOut(tween((130 * f).toInt()), targetScale = 0.6f, transformOrigin = origin) + fadeOut(tween((110 * f).toInt()))
        MenuAnim.DROP, MenuAnim.FADE, MenuAnim.SLIDE -> fadeOut(tween((90 * f).toInt()))
        else -> ExitTransition.None
    }
    Popup(
        popupPositionProvider = remember(density, offset) { MenuPosition(density, offset) { origin = it } },
        onDismissRequest = onDismissRequest,
        properties = PopupProperties(focusable = true),
    ) {
        AnimatedVisibility(visibleState = state, enter = enter, exit = exit) {
            Column(
                modifier.width(IntrinsicSize.Max).widthIn(min = 196.dp, max = 300.dp).menuSurface(st)
                    .padding(vertical = 6.dp).verticalScroll(rememberScrollState()),
                content = content,
            )
        }
    }
}

/** Wejście okna dialogowego w charakterze stylu. */
@Composable
private fun Modifier.dialogEnter(kind: DialogAnim): Modifier {
    if (kind == DialogAnim.NONE) return this
    val p = remember { Animatable(0f) }
    val f = LocalAnim.current.factor
    LaunchedEffect(Unit) {
        when (kind) {
            // iOS 6: alert „podskakuje” — powiększenie z odbiciem
            DialogAnim.BOUNCE -> p.animateTo(1f, keyframes { durationMillis = (420 * f).toInt(); 0.6f at (180 * f).toInt(); 0.85f at (300 * f).toInt() })
            DialogAnim.SLIDE_UP -> p.animateTo(1f, spring(dampingRatio = 0.86f, stiffness = 380f / f))
            DialogAnim.ZOOM -> p.animateTo(1f, spring(dampingRatio = 0.9f, stiffness = 500f / f))
            else -> p.animateTo(1f, tween((220 * f).toInt()))
        }
    }
    return graphicsLayer {
        val v = p.value
        alpha = (v * 1.6f).coerceIn(0f, 1f)
        when (kind) {
            DialogAnim.ZOOM -> { val s = 1.15f - 0.15f * v; scaleX = s; scaleY = s }
            DialogAnim.BOUNCE -> { val s = if (v < 0.6f) 0.3f + v / 0.6f * 0.8f else if (v < 0.85f) 1.1f - (v - 0.6f) / 0.25f * 0.15f else 0.95f + (v - 0.85f) / 0.15f * 0.05f; scaleX = s; scaleY = s }
            DialogAnim.SCALE -> { val s = 0.85f + 0.15f * v; scaleX = s; scaleY = s }
            DialogAnim.SLIDE_UP -> translationY = 90.dp.toPx() * (1f - v)
            else -> {}
        }
    }
}

/** Rozmycie tła pod oknem dialogowym (Android 12+) w stylach z przezroczystością. */
@Composable
private fun DialogWindowFx(blur: Boolean) {
    val view = LocalView.current
    DisposableEffect(blur) {
        val w = (view.parent as? DialogWindowProvider)?.window
        if (w != null && blur && Build.VERSION.SDK_INT >= 31) {
            try {
                w.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                w.attributes = w.attributes.apply { blurBehindRadius = (28 * view.resources.displayMetrics.density).toInt() }
                w.setDimAmount(0.25f)
            } catch (_: Throwable) { }
        }
        onDispose { }
    }
}

private fun dialogShape(st: StyleState): Shape = when (st.spec.chrome) {
    Chrome.GLASS -> RoundedCornerShape(34.dp)
    Chrome.IOS6 -> RoundedCornerShape(10.dp)
    Chrome.WIN95, Chrome.TERMINAL -> RectangleShape
    Chrome.WINXP -> RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp)
    Chrome.WIN7 -> RoundedCornerShape(6.dp)
    Chrome.WIN11, Chrome.DISCORD -> RoundedCornerShape(8.dp)
    Chrome.ONEUI -> RoundedCornerShape(26.dp)
    Chrome.HYPER, Chrome.OXYGEN, Chrome.COLOROS, Chrome.EMUI -> RoundedCornerShape(24.dp)
    Chrome.NOTHING -> RoundedCornerShape(20.dp)
    Chrome.TELEGRAM -> RoundedCornerShape(10.dp)
    Chrome.MESSENGER, Chrome.FACEBOOK -> RoundedCornerShape(12.dp)
    else -> RoundedCornerShape(28.dp)
}

/** Okno dialogowe w stylu (zamiennik AlertDialog) — kształt, kolory, rozmycie tła i animacja jak w oryginale. */
@Composable
fun StyleAlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: (@Composable () -> Unit)? = null,
    icon: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
    properties: DialogProperties = DialogProperties(),
) {
    val st = LocalStyle.current
    val anim = LocalAnim.current
    val kind = if (anim[Anim.PANELS]) st.motion.dialog else DialogAnim.NONE
    val chrome = st.spec.chrome
    val blur = anim[Anim.BLUR] && chrome in setOf(Chrome.GLASS, Chrome.OXYGEN, Chrome.HYPER, Chrome.COLOROS, Chrome.ONEUI, Chrome.WIN7, Chrome.WIN11)
    val cs = MaterialTheme.colorScheme
    val ios6 = chrome == Chrome.IOS6
    val container = when (chrome) {
        // szkło: okno półprzezroczyste nad rozmytym tłem (Android 12+)
        Chrome.GLASS -> if (st.dark || st.pal.onBase == Color.White) Color(0xFF1C1C1E).copy(alpha = 0.82f) else Color(0xFFF7F7F8).copy(alpha = 0.84f)
        Chrome.IOS6 -> Color(0xFF233B70).copy(alpha = 0.96f)
        Chrome.WIN95 -> Color(0xFFC0C0C0)
        Chrome.WINXP -> Color(0xFFECE9D8)
        Chrome.WIN7 -> Color(0xFFF0F0F0)
        Chrome.WIN11 -> if (st.dark) Color(0xFF2B2B2B) else Color(0xFFF9F9F9)
        Chrome.TERMINAL -> Color.Black
        Chrome.DISCORD -> Color(0xFF313338)
        Chrome.ONEUI, Chrome.HYPER, Chrome.OXYGEN, Chrome.COLOROS, Chrome.EMUI, Chrome.NOTHING -> (st.pal.card ?: st.pal.theirs).solid(st.pal.base)
        else -> cs.surfaceContainerHigh
    }
    val textColor = if (ios6) Color.White else if (chrome == Chrome.WIN95 || chrome == Chrome.WINXP || chrome == Chrome.WIN7) Color.Black else cs.onSurface
    val decor = when (chrome) {
        Chrome.WIN95 -> Modifier.drawWithContent { drawContent(); bevel(this, size, raised = true, px = density) }
        Chrome.WINXP -> Modifier.border(BorderStroke(3.dp, Color(0xFF0054E3)), RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp))
        Chrome.TERMINAL -> Modifier.border(BorderStroke(1.dp, st.pal.primary))
        Chrome.IOS6 -> Modifier.border(BorderStroke(2.dp, Color.White.copy(alpha = 0.85f)), RoundedCornerShape(10.dp))
        Chrome.WIN11, Chrome.WIN7 -> Modifier.border(BorderStroke(1.dp, if (st.dark) Color(0xFF3A3A3A) else Color(0xFFD5D5D5)), dialogShape(st))
        else -> Modifier
    }
    AlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = { DialogWindowFx(blur); confirmButton() },
        modifier = modifier.dialogEnter(kind).then(decor),
        dismissButton = dismissButton,
        icon = icon,
        title = title,
        text = text,
        shape = dialogShape(st),
        containerColor = container,
        titleContentColor = textColor,
        textContentColor = if (ios6) Color.White.copy(alpha = 0.9f) else if (textColor == Color.Black) Color(0xFF202020) else cs.onSurfaceVariant,
        iconContentColor = if (ios6) Color.White else cs.secondary,
        properties = properties,
    )
}
