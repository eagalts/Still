package com.eagalts.still.ui

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eagalts.still.Still
import com.eagalts.still.media.Ephemeral
import com.eagalts.still.t
import kotlin.math.roundToInt

/** Сколько вытянуть «Поток» (dp, уже с сопротивлением), чтобы, отпустив, он открылся. */
const val FlowPullAt = 96f

/**
 * «Поток» выглядывает под самым свежим рядом ленты: заголовок и два ряда последних кадров, бледные и размытые.
 * Ленту дальше не листают — «Поток» вытягивают: тянешь от конца ленты, она упруго уходит вверх, а «Поток» выезжает
 * навстречу и проявляется в такт пальцу; на пороге щелчок, отпустил — он открывается целиком (самое свежее сверху).
 * Размытие — одно на весь выглядывающий кусок, а не на каждую плитку; на слабых телефонах — только бледность.
 */
@Composable
fun FlowPeek(s: Still, ctl: FeedController, bottomOfFeed: () -> Float?, pull: () -> Float) {
    val p = LocalPalette.current
    val density = LocalDensity.current
    val at = with(density) { FlowPullAt.dp.toPx() }
    val cols = ctl.cols.coerceIn(2, 5)
    // кадры — как плитки ленты у низа экрана под линзой: чуть меньше ячейки, с воздухом между ними
    val gap = with(density) { 10.dp.toPx() }
    val tile = with(density) { ((ctl.tilePx.takeIf { it > 0 } ?: 300) * 0.84f).toDp() }
    val items = s.flowPeek.take(cols * 2)
    val count = s.flowPeek.sumOf { it.members.size }
    val steps = Ephemeral.STEPS
    val step = steps.indexOfFirst { it >= s.flowTtl }.let { if (it < 0) steps.size - 1 else it }
    val radius = with(density) { 14.dp.toPx() }
    Column(
        Modifier.fillMaxWidth().testTag("flow-peek")
            .offset {
                // под самым свежим рядом, с воздухом; ряда не видно — «Поток» за краем экрана
                val y = bottomOfFeed() ?: 100_000f
                IntOffset(0, (y + 22.dp.toPx() - pull()).roundToInt())
            }
            .graphicsLayer {
                val k = (pull() / at).coerceIn(0f, 1f)
                alpha = 0.35f + 0.65f * k
                renderEffect = ctl.flowBlur((1f - k) * 11.dp.toPx())
            },
    ) {
        Row(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 10.dp), verticalAlignment = Alignment.Bottom) {
            Text(t("Поток"), color = p.text, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, fontFamily = UiFont)
            Spacer(Modifier.width(10.dp))
            Text(t("{0} · уходят через {1}", count, flowStep(step)), color = p.muted, fontSize = 14.sp, fontFamily = UiFont,
                modifier = Modifier.padding(bottom = 3.dp))
        }
        for (row in items.chunked(cols)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(with(density) { gap.toDp() }, Alignment.CenterHorizontally)) {
                for (e in row) {
                    val thumb = rememberThumb(s, e.cover, ctl.tilePx.takeIf { it > 0 } ?: 256)
                    val ph = placeholder(s, e.cover, p)
                    Box(Modifier.size(tile).drawBehind {
                        val b = thumb.value
                        val r = androidx.compose.ui.geometry.RoundRect(Rect(0f, 0f, size.width, size.height), androidx.compose.ui.geometry.CornerRadius(radius))
                        val path = androidx.compose.ui.graphics.Path().apply { addRoundRect(r) }
                        clipPath(path) {
                            if (b != null) drawCover(b, Rect(0f, 0f, size.width, size.height)) else drawRect(ph)
                        }
                    })
                }
            }
            Spacer(Modifier.height(with(density) { gap.toDp() }))
        }
    }
}

/** Щелчок на пороге — один раз за потягивание, в обе стороны. */
class PullClick {
    var ready = false
    fun update(view: android.view.View, now: Boolean) {
        if (now != ready) { ready = now; if (now) view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK) }
    }
}
