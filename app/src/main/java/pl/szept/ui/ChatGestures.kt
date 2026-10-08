package pl.szept.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Odpowiedź gestem: przesunięcie wiadomości w prawo = odpowiedz.
 * (Przytrzymanie wiadomości otwiera menu z szybkimi reakcjami — obsługuje to sam dymek.)
 */
@Composable
fun SwipeToReply(enabled: Boolean, onReply: () -> Unit, content: @Composable () -> Unit) {
    if (!enabled) { content(); return }
    val density = LocalDensity.current
    val threshold = with(density) { 64.dp.toPx() }
    val max = with(density) { 96.dp.toPx() }
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val reply by rememberUpdatedState(onReply)
    val dx = remember { Animatable(0f) }
    var armed by remember { mutableStateOf(false) }
    Box(
        Modifier
            .semantics { customActions = listOf(CustomAccessibilityAction("Odpowiedz") { reply(); true }) }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragEnd = {
                        if (armed) reply()
                        armed = false
                        scope.launch { dx.animateTo(0f) }
                    },
                    onDragCancel = { armed = false; scope.launch { dx.animateTo(0f) } },
                ) { change, amount ->
                    val next = (dx.value + amount).coerceIn(0f, max)
                    if (next != dx.value) change.consume()
                    scope.launch { dx.snapTo(next) }
                    val now = next >= threshold
                    if (now && !armed) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    armed = now
                }
            },
    ) {
        val p = (dx.value / threshold).coerceIn(0f, 1f)
        if (dx.value > 1f) Icon(
            Icons.AutoMirrored.Filled.Reply, null,
            Modifier.align(Alignment.CenterStart).padding(start = 12.dp).size(24.dp).alpha(p),
            tint = MaterialTheme.colorScheme.primary,
        )
        Box(Modifier.offset { IntOffset(dx.value.roundToInt(), 0) }) { content() }
    }
}
