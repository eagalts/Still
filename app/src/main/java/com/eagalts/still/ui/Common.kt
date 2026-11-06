package com.eagalts.still.ui

import android.view.HapticFeedbackConstants
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/*
 * Общие мелочи — из Plume (`ui/Common.kt`): пружины, нажатие, шторка, тост.
 */

/** Порог «приехали» для пружин, которые ведут долю 0…1: у Compose 0.01 — на экране это десятки пикселей рывка. */
const val FineThreshold = 0.0005f

fun fineSpring(dampingRatio: Float = 1f, stiffness: Float = 1500f) =
    spring(dampingRatio, stiffness, visibilityThreshold = FineThreshold)

/** Нажатие: пружинное проседание и тактильный щелчок (вместо material ripple). */
fun Modifier.bouncy(
    pressedScale: Float = 0.9f,
    haptic: Boolean = true,
    enabled: Boolean = true,
    onClick: () -> Unit,
): Modifier = composed {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val view = LocalView.current
    val s by animateFloatAsState(
        if (pressed) pressedScale else 1f,
        fineSpring(dampingRatio = if (pressed) 0.9f else 0.42f, stiffness = if (pressed) 1400f else 520f),
        label = "bouncy",
    )
    this
        .graphicsLayer { val k = s.coerceAtLeast(0f); scaleX = k; scaleY = k }
        .clickable(src, indication = null, enabled = enabled) {
            if (haptic) view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            onClick()
        }
}

/** Шторка снизу: выезд пружиной, затемнение, закрытие смахиванием вниз (тянется за пальцем, вверх — с сопротивлением). */
@Composable
fun Sheet(visible: Boolean, onDismiss: () -> Unit, color: Color? = null, content: @Composable ColumnScope.() -> Unit) {
    val p = LocalPalette.current
    AnimatedVisibility(visible, enter = fadeIn(tween(220)), exit = fadeOut(tween(200))) {
        Box(
            Modifier.fillMaxSize().background(p.scrim)
                .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onDismiss)
        )
    }
    AnimatedVisibility(
        visible,
        modifier = Modifier.fillMaxSize(),
        enter = slideInVertically(spring(dampingRatio = 0.82f, stiffness = 420f)) { it },
        exit = slideOutVertically(spring(stiffness = 500f)) { it },
    ) {
        val drag = remember { Animatable(0f) }
        val scope = rememberCoroutineScope()
        Box(Modifier.fillMaxSize().statusBarsPadding(), contentAlignment = Alignment.BottomCenter) {
            Column(
                Modifier
                    .offset { IntOffset(0, drag.value.roundToInt()) }
                    .widthIn(max = 560.dp)
                    .fillMaxWidth()
                    .clip(SquircleShape(Radius.xl, Radius.xl, 0.dp, 0.dp))
                    .background(color ?: p.sheet)
                    .pointerInput(Unit) {
                        detectVerticalDragGestures(
                            onDragEnd = {
                                if (drag.value > 56.dp.toPx()) onDismiss()
                                else scope.launch { drag.animateTo(0f, spring(0.6f, 500f)) }
                            },
                            onDragCancel = { scope.launch { drag.animateTo(0f) } },
                        ) { change, dy ->
                            change.consume()
                            val next = drag.value + dy
                            scope.launch { drag.snapTo(if (next < 0) next * 0.25f else next) }
                        }
                    }
                    .navigationBarsPadding()
                    .padding(top = 10.dp, bottom = 10.dp),
            ) {
                // ручка: подсказывает, что шторку можно смахнуть
                Box(
                    Modifier.align(Alignment.CenterHorizontally).padding(bottom = 10.dp)
                        .clip(RoundedCornerShape(50)).background(p.faint).padding(horizontal = 18.dp, vertical = 2.dp)
                )
                content()
            }
        }
    }
}

/** Тост — тёмная таблетка сверху; длинный перевод переносится, таблетка растёт пружиной. */
@Composable
fun ToastHost(toast: Pair<String, Long>?) {
    var shown by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(toast) { if (toast != null) { shown = toast.first; delay(1700); shown = null } }
    Box(
        Modifier.fillMaxSize().statusBarsPadding().padding(top = 12.dp, start = 24.dp, end = 24.dp),
        contentAlignment = Alignment.TopCenter,
    ) {
        AnimatedVisibility(
            shown != null,
            enter = slideInVertically(spring(0.62f, 500f)) { -it * 2 } + fadeIn() + scaleIn(initialScale = 0.8f),
            exit = slideOutVertically(spring(stiffness = 600f)) { -it * 2 } + fadeOut(),
        ) {
            var last by remember { mutableStateOf("") }
            shown?.let { last = it }
            Text(
                last, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium, fontFamily = UiFont,
                modifier = Modifier.animateContentSize(spring(0.8f, 600f)).clip(RoundedCornerShape(22.dp))
                    .background(Color(0xE61C1B1F)).padding(horizontal = 20.dp, vertical = 11.dp),
            )
        }
    }
}

/**
 * Число как на табло (из Plume, `RollingText`): прокручиваются только изменившиеся цифры — вверх, если выросло,
 * вниз, если уменьшилось. Разряды выровнены справа: единицы не прыгают при смене длины.
 */
@Composable
fun RollingCount(n: Int, color: Color) {
    val text = n.toString()
    val prev = remember { androidx.compose.runtime.mutableIntStateOf(n) }
    val up = remember(n) { n >= prev.intValue }
    androidx.compose.runtime.SideEffect { prev.intValue = n }
    androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalLayoutDirection provides androidx.compose.ui.unit.LayoutDirection.Ltr) {
        androidx.compose.foundation.layout.Row {
            text.forEachIndexed { i, ch ->
                androidx.compose.runtime.key(text.length - i) {
                    androidx.compose.animation.AnimatedContent(
                        ch,
                        transitionSpec = {
                            (slideInVertically(spring(0.8f, 600f)) { if (up) it else -it } + fadeIn(tween(140))) togetherWith
                                (slideOutVertically(spring(0.8f, 600f)) { if (up) -it else it } + fadeOut(tween(120)))
                        },
                        label = "roll",
                    ) { c -> Text(c.toString(), color = color, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, fontFamily = UiFont) }
                }
            }
        }
    }
}
