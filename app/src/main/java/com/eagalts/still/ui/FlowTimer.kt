package com.eagalts.still.ui

import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eagalts.still.Still
import com.eagalts.still.media.Ephemeral
import com.eagalts.still.t
import kotlin.math.roundToInt

/** Подпись ступени ползунка: «3 дня», «2 недели»… */
fun flowStep(i: Int): String = when (i) {
    0 -> t("1 час"); 1 -> t("3 часа"); 2 -> t("6 часов"); 3 -> t("12 часов")
    4 -> t("1 день"); 5 -> t("2 дня"); 6 -> t("3 дня"); 7 -> t("5 дней")
    8 -> t("1 неделю"); 9 -> t("2 недели"); 10 -> t("3 недели")
    11 -> t("1 месяц"); 12 -> t("2 месяца"); else -> t("3 месяца")
}

/** Сколько кадру осталось до «Потока»: «меньше часа», «5 ч», «2 дн.». */
fun flowLeft(ms: Long): String = when {
    ms < Ephemeral.HOUR -> t("меньше часа")
    ms < Ephemeral.DAY -> t("{0} ч", ((ms + Ephemeral.HOUR - 1) / Ephemeral.HOUR).toInt())
    else -> t("{0} дн.", ((ms + Ephemeral.DAY - 1) / Ephemeral.DAY).toInt())
}

/**
 * Срок до «Потока» — в самом «Потоке», последней строкой (у знака, где оказываешься): ползунок по ступеням
 * от часа до трёх месяцев, каждая ступень — тактильный щелчок. Лента пересобирается, когда палец отпущен.
 */
@Composable
fun FlowTimer(s: Still) {
    val p = LocalPalette.current
    val view = LocalView.current
    val steps = Ephemeral.STEPS
    val saved = steps.indexOfFirst { it >= s.flowTtl }.let { if (it < 0) steps.size - 1 else it }
    var idx by remember { mutableIntStateOf(saved) }
    val pos = remember { Animatable(saved.toFloat()) }
    LaunchedEffect(idx) { pos.animateTo(idx.toFloat(), fineSpring(0.8f, 900f)) }
    fun pick(i: Int) {
        val v = i.coerceIn(0, steps.size - 1)
        if (v != idx) { idx = v; view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK) }
    }
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp).clip(SquircleShape(Radius.m)).background(p.sheet)
            .padding(horizontal = 18.dp, vertical = 14.dp)
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(t("Уходят в Поток через"), color = p.muted, fontSize = 14.sp, fontFamily = UiFont, modifier = Modifier.weight(1f))
            Text(flowStep(idx), color = p.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, fontFamily = UiFont,
                modifier = Modifier.testTag("flow-ttl"))
        }
        Spacer(Modifier.height(12.dp))
        Canvas(
            Modifier.fillMaxWidth().height(28.dp).testTag("flow-slider")
                .pointerInput(Unit) {
                    detectTapGestures { o -> pick((o.x / size.width * (steps.size - 1)).roundToInt()); s.changeFlowTtl(steps[idx]) }
                }
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragEnd = { s.changeFlowTtl(steps[idx]) },
                        onDragCancel = { s.changeFlowTtl(steps[idx]) },
                    ) { ch, _ -> pick((ch.position.x / size.width * (steps.size - 1)).roundToInt()) }
                }
        ) {
            val r = 11.dp.toPx(); val track = 4.dp.toPx()
            val left = r; val right = size.width - r; val cy = size.height / 2f
            // пружина может проскочить край — положение зажато в пределы шкалы
            val k = (pos.value / (steps.size - 1)).coerceIn(0f, 1f)
            val x = left + (right - left) * k
            drawRoundRect(p.text.copy(alpha = 0.1f), Offset(left, cy - track / 2f), Size(right - left, track), CornerRadius(track / 2f))
            drawRoundRect(p.accent, Offset(left, cy - track / 2f), Size((x - left).coerceAtLeast(0f), track), CornerRadius(track / 2f))
            for (i in steps.indices) {
                val sx = left + (right - left) * i / (steps.size - 1)
                drawCircle(if (sx <= x) Color.White.copy(alpha = 0.7f) else p.text.copy(alpha = 0.25f), 1.5.dp.toPx(), Offset(sx, cy))
            }
            drawCircle(Color.Black.copy(alpha = 0.16f), r + 1.dp.toPx(), Offset(x, cy + 1.dp.toPx()))
            drawCircle(Color.White, r, Offset(x, cy))
        }
        Spacer(Modifier.height(6.dp))
        Text(
            t("Скриншоты, записи экрана, мессенджеры, загрузки, а ещё снятые камерой чеки, квитанции, счётчики и экраны"),
            color = p.muted.copy(alpha = 0.8f), fontSize = 12.sp, lineHeight = 16.sp, fontFamily = UiFont,
        )
        // пока идёт фоновый разбор — тихая строка, сколько осталось: бумаги и экраны находятся по мере разбора
        s.sorting?.let { (done, all) ->
            Text(
                t("Разбираю фото: {0} из {1}", done, all), color = p.muted.copy(alpha = 0.8f), fontSize = 12.sp, fontFamily = UiFont,
                modifier = Modifier.padding(top = 4.dp).testTag("flow-sorting"),
            )
        }
    }
}
