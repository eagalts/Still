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
import androidx.compose.ui.graphics.drawscope.rotate
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
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
 * Знак Still — диафрагма: три лопасти, закрученные вокруг просвета. Плоский, как у Linear и Apple, —
 * от фото его отделяет только мягкая тень.
 *
 * Контур строится один раз на размер и раскрытие и кэшируется; поворот — преобразованием при отрисовке,
 * поэтому вращение вслед за лентой ничего не аллоцирует.
 */
class MarkShape {
    private var s = -1f
    private var iris = -1f
    private var path = Path()

    fun path(size: Float, open: Float): Path {
        val o = (open * 50f).roundToInt() / 50f
        if (size == s && o == iris) return path
        s = size; iris = o
        val c = size / 2f; val r = size * 0.48f
        val blades = Path()
        for (a in intArrayOf(0, 120, 240)) {
            var first = true
            var t = 0
            while (t <= 180) {
                val ang = Math.toRadians((a + t * 0.8).toDouble())
                val rr = r * sin(Math.toRadians(t.toDouble())).toFloat()
                val x = c + rr * cos(ang).toFloat(); val y = c - rr * sin(ang).toFloat()
                if (first) { blades.moveTo(x, y); first = false } else blades.lineTo(x, y)
                t += 6
            }
            blades.close()
        }
        // просвет: при «глотке» сходится в точку
        val h = r * 0.17f * o
        path = if (h < 0.5f) blades else Path().apply {
            op(blades, Path().apply { addOval(androidx.compose.ui.geometry.Rect(c - h, c - h, c + h, c + h)) }, androidx.compose.ui.graphics.PathOperation.Difference)
        }
        return path
    }
}

private val MarkFill = listOf(Color(0xFF6EA5FF), Color(0xFFA591FF), Color(0xFFFFB9A0))

/** [turn] — поворот в градусах; [open] — раскрытие просвета 0…1. */
fun DrawScope.drawStillMark(s: Float, turn: Float = 0f, open: Float = 1f, shape: MarkShape = MarkShape()) {
    val p = shape.path(s, open.coerceIn(0f, 1f))
    rotate(turn, Offset(s / 2f, s / 2f)) {
        // мягкая тень: три слоя со сдвигом — читается на любом кадре, без подложки
        for (i in 1..3) translate(0f, s * 0.018f * i) { drawPath(p, Color.Black, alpha = 0.07f) }
        drawPath(p, Brush.linearGradient(MarkFill, start = Offset(s * 0.2f, s * 0.15f), end = Offset(s * 0.8f, s * 0.9f)))
    }
}

/**
 * Знак внизу — единственная кнопка Still. Нажатие: проседает пружиной; отпустил — лопасти проворачиваются,
 * как затвор, просвет вздрагивает. Листаешь — знак медленно поворачивается вслед за лентой.
 */
@Composable
fun StillMark(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    /** Поворот вслед за лентой, в градусах (читается при отрисовке — без рекомпозиций). */
    phase: () -> Float = { 0f },
    /** Растёт, когда в знак стекают пиксели удалённых кадров: знак «глотает». */
    gulp: Int = 0,
) {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val view = LocalView.current
    val press by animateFloatAsState(
        if (pressed) 0.82f else 1f,
        spring(dampingRatio = if (pressed) 0.9f else 0.42f, stiffness = if (pressed) 1400f else 520f), label = "markPress",
    )
    val taps = remember { mutableIntStateOf(0) }
    val spin = remember { Animatable(0f) }
    val iris = remember { Animatable(1f) }
    val shape = remember { MarkShape() }
    // тап: лопасти проворачиваются на треть оборота с перелётом, как затвор, и просвет вздрагивает
    LaunchedEffect(taps.intValue) {
        if (taps.intValue == 0) return@LaunchedEffect
        spin.animateTo(spin.value + 120f, spring(0.55f, 260f))
    }
    LaunchedEffect(taps.intValue) {
        if (taps.intValue == 0) return@LaunchedEffect
        iris.animateTo(0.55f, spring(1f, 1600f)); iris.animateTo(1f, spring(0.45f, 420f))
    }
    val swallow = remember { Animatable(1f) }
    LaunchedEffect(gulp) {
        if (gulp == 0) return@LaunchedEffect
        // пыль долетает — диафрагма закрывается, «проглатывает» и раскрывается обратно
        kotlinx.coroutines.delay(480)
        launch { spin.animateTo(spin.value + 60f, spring(0.7f, 300f)) }
        launch { swallow.animateTo(1.12f, spring(0.9f, 1400f)); swallow.animateTo(1f, spring(0.4f, 380f)) }
        iris.animateTo(0f, spring(1f, 900f)); iris.animateTo(1f, spring(0.5f, 300f))
    }
    Box(
        // диск со знаком проседает при нажатии и «сглатывает» целиком, вместе со стеклом
        Modifier.graphicsLayer {
            val k = (press * swallow.value).coerceAtLeast(0f); scaleX = k; scaleY = k
        }.then(modifier).size(56.dp).testTag("still-mark")
            .semantics { contentDescription = t("Поиск и альбомы") }
            .clickable(src, indication = null) {
                view.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                taps.intValue++
                onClick()
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(
            Modifier.size(44.dp)
        ) {
            // вслед за лентой знак медленно поворачивается — плавно, без ряби
            drawStillMark(size.width, spin.value + phase(), iris.value, shape)
        }
    }
}
