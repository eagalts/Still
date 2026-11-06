package com.eagalts.still.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.eagalts.still.t
import kotlin.math.PI
import kotlin.math.sin

/**
 * Знак Still — родня знаку Plume. У Plume волна и точка; у Still та же волна успокоилась в гладь,
 * а точка поднялась над ней солнцем: «тихий кадр». Стиль тот же — стикер: тень, белая окантовка, градиент.
 *
 * [ripple] — рябь по глади (знак со знаком: пружина проходит через ноль — волна переворачивается, как настоящая);
 * [lift] — насколько точка подпрыгнула (доля размера).
 */
fun DrawScope.drawStillMark(s: Float, ripple: Float = 0f, lift: Float = 0f) {
    val y0 = s * 0.67f
    val x0 = s * 0.15f; val x1 = s * 0.85f
    val line = Path().apply {
        val n = 28
        for (i in 0..n) {
            val f = i / n.toFloat()
            // огибающая sin(πf): концы на месте, рябь — в середине
            val y = y0 + ripple * s * 0.075f * sin(f * 3f * PI.toFloat()) * sin(f * PI.toFloat())
            val x = x0 + (x1 - x0) * f
            if (i == 0) moveTo(x, y) else lineTo(x, y)
        }
    }
    val dot = Offset(s * 0.5f, s * (0.33f - lift))
    val rOut = s * 0.155f; val rIn = s * 0.1f
    val fill = Brush.linearGradient(
        listOf(Color(0xFF8FB8FF), Color(0xFFB5A8FF), Color(0xFFFFC2A8)),
        start = Offset(s * 0.1f, s * 0.15f), end = Offset(s * 0.9f, s * 0.8f),
    )
    val outline = Stroke(s * 0.25f, cap = StrokeCap.Round, join = StrokeJoin.Round)
    val body = Stroke(s * 0.16f, cap = StrokeCap.Round, join = StrokeJoin.Round)
    val shadow = Color(0x33404A8C)
    translate(0f, s * 0.03f) {
        drawPath(line, shadow, style = outline)
        drawCircle(shadow, rOut, dot)
    }
    drawPath(line, Color.White, style = outline)
    drawCircle(Color.White, rOut, dot)
    drawPath(line, fill, style = body)
    drawCircle(fill, rIn, dot)
}

/**
 * Знак внизу — единственная кнопка Still. Нажатие: проседает пружиной; отпустил — по глади бежит рябь,
 * солнце подпрыгивает и садится. Гладь успокаивается сама — к моменту, когда шторка доехала.
 */
@Composable
fun StillMark(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val view = LocalView.current
    val press by animateFloatAsState(
        if (pressed) 0.82f else 1f,
        spring(dampingRatio = if (pressed) 0.9f else 0.42f, stiffness = if (pressed) 1400f else 520f), label = "markPress",
    )
    val taps = remember { mutableIntStateOf(0) }
    val ripple = remember { Animatable(0f) }
    val lift = remember { Animatable(0f) }
    LaunchedEffect(taps.intValue) {
        if (taps.intValue == 0) return@LaunchedEffect
        ripple.snapTo(1f)
        ripple.animateTo(0f, spring(0.3f, 260f))
    }
    LaunchedEffect(taps.intValue) {
        if (taps.intValue == 0) return@LaunchedEffect
        lift.animateTo(0.07f, spring(0.9f, 1800f))
        lift.animateTo(0f, spring(0.38f, 420f))
    }
    Box(
        modifier.size(64.dp).testTag("still-mark")
            .semantics { contentDescription = t("Поиск и альбомы") }
            .clickable(src, indication = null) {
                view.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                taps.intValue++
                onClick()
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(
            Modifier.size(40.dp).graphicsLayer {
                val k = press.coerceAtLeast(0f); scaleX = k; scaleY = k
                transformOrigin = TransformOrigin(0.5f, 0.7f)
            }
        ) { drawStillMark(size.width, ripple.value, lift.value) }
    }
}
