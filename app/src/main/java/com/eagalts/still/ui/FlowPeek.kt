package com.eagalts.still.ui

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.asAndroidBitmap
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
/** Воздух между самым свежим рядом ленты и выглядывающим «Потоком», dp. */
const val FlowPeekGap = 22f

/**
 * Заголовок «Потока» — один и тот же под лентой и в открытом «Потоке» (поэтому вытянутый кусок подменяется
 * «Потоком» без шва): «Поток · 260 · уходят через 3 дня». «Уходят через…» раскрывает ползунок срока; справа —
 * крестик (под лентой его нет — только место под ним, чтобы строка не сдвинулась).
 */
@Composable
fun FlowHead(s: Still, timerOpen: Boolean, onTimer: (() -> Unit)?, onClose: (() -> Unit)?, closeAlpha: () -> Float = { 1f }) {
    val p = LocalPalette.current
    val steps = Ephemeral.STEPS
    val step = steps.indexOfFirst { it >= s.flowTtl }.let { if (it < 0) steps.size - 1 else it }
    val count = s.flowPeek.sumOf { it.members.size }
    Row(
        Modifier.fillMaxWidth().testTag("flow-head").padding(start = 20.dp, end = 8.dp).height(48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(t("Поток"), color = p.text, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, fontFamily = UiFont)
        Spacer(Modifier.width(10.dp))
        Text(
            t("{0} · уходят через {1}", count, flowStep(step)),
            color = if (timerOpen) p.accent else p.muted, fontSize = 14.sp, fontFamily = UiFont,
            modifier = Modifier.weight(1f).padding(top = 4.dp)
                .then(if (onTimer != null) Modifier.testTag("flow-ttl-open").bouncy(0.96f, onClick = onTimer) else Modifier),
        )
        Box(
            Modifier.size(40.dp).graphicsLayer { alpha = closeAlpha() }
                .then(if (onClose != null) Modifier.testTag("flow-close").bouncy(0.8f, onClick = onClose) else Modifier),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Close, t("Вся лента"), tint = p.muted, modifier = Modifier.size(18.dp)) }
    }
}

/**
 * «Поток» выглядывает под самым свежим рядом ленты: его заголовок и два ряда, бледные и размытые. Нарисован ровно
 * той же сеткой, что открытый «Поток» (те же кадры в том же порядке, тот же размер, промежутки, стопки), — когда его
 * вытянули, он доезжает до места заголовка в «Потоке» и подменяется им в один кадр. Размытие — одно на весь кусок;
 * на слабых телефонах — только бледность.
 */
@Composable
fun FlowPeek(s: Still, ctl: FeedController, bottomOfFeed: () -> Float?, pull: () -> Float) {
    val p = LocalPalette.current
    val density = LocalDensity.current
    val at = with(density) { FlowPullAt.dp.toPx() }
    val cols = ctl.cols
    val gapDp = gapFor(cols)
    val tilePx = ctl.tilePx.takeIf { it > 0 } ?: 300
    val tile = with(density) { tilePx.toDp() }
    // в покое — два ряда (дальше всё равно край экрана); потянули — все ряды до низа экрана, чтобы на подмене
    // «Потоком» ниже куска ничего не возникало разом
    val pulling by remember { androidx.compose.runtime.derivedStateOf { pull() > 0f } }
    val screenH = with(density) { androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp.dp.roundToPx() }
    val rows = if (pulling) (screenH / (tilePx + 1)).coerceAtLeast(1) + 1 else 2
    val entries = s.flowPeek.take(cols * rows)
    Column(
        Modifier.fillMaxWidth().testTag("flow-peek")
            .offset {
                // под самым свежим рядом, с воздухом; ряда не видно — «Поток» за краем экрана
                val y = bottomOfFeed() ?: 100_000f
                IntOffset(0, (y + FlowPeekGap.dp.toPx() - pull()).roundToInt())
            }
            .graphicsLayer {
                val k = (pull() / at).coerceIn(0f, 1f)
                // «Поток» встал на место — кусок исчезает в тот же кадр (не висит лишний кадр поверх)
                alpha = if (ctl.shownFilter == com.eagalts.still.Filter.Flow && ctl.placed) 0f else 0.35f + 0.65f * k
                renderEffect = ctl.flowBlur((1f - k) * 11.dp.toPx())
            },
    ) {
        // крестик проявляется по мере вытягивания — в «Потоке» он уже есть
        FlowHead(s, false, null, null, closeAlpha = { (pull() / at).coerceIn(0f, 1f) })
        Spacer(Modifier.height(gapDp))
        for (row in entries.chunked(cols)) {
            Row(horizontalArrangement = Arrangement.spacedBy(gapDp)) {
                for (e in row) PeekTile(s, e, tile, tilePx, cols)
            }
            Spacer(Modifier.height(gapDp))
        }
    }
}

/** Кадр выглядывающего «Потока» — как плитка плоской сетки: квадрат без скругления; стопка — стопкой. */
@Composable
private fun PeekTile(s: Still, e: com.eagalts.still.media.Entry, tile: androidx.compose.ui.unit.Dp, px: Int, cols: Int) {
    val p = LocalPalette.current
    val cover = rememberThumb(s, e.cover, px)
    // этот же снимок сейчас понадобится плитке открытого «Потока» — придержать, чтобы там не мелькнула заглушка
    androidx.compose.runtime.LaunchedEffect(cover.value) {
        cover.value?.let { s.thumbs.hold(e.cover.id, it.asAndroidBitmap()) }
    }
    val ph = placeholder(s, e.cover, p)
    Box(Modifier.size(tile)) {
        if (e.stacked) {
            val (b1, b2) = remember(e) { Pile.backs(e) }
            val look = remember(e.key, e.members.size) { Pile.look(e.key, e.members.size) }
            val t1 = b1?.let { rememberThumb(s, it, 160) }
            val t2 = b2?.let { rememberThumb(s, it, 160) }
            val cache = remember { PileCache() }
            Spacer(Modifier.fillMaxSize().drawBehind { drawPile(cache, cover.value, t1?.value, t2?.value, ph, p, cols, 0f, look) })
        } else {
            Spacer(Modifier.fillMaxSize().drawBehind {
                val b = cover.value
                if (b != null) drawCover(b, Rect(0f, 0f, size.width, size.height)) else drawRect(ph)
            })
        }
        if (e.cover.video && cols <= 5) VideoBadge(e.cover.durationMs)
    }
}

/** Щелчок на пороге — один раз за потягивание, в обе стороны. */
class PullClick {
    var ready = false
    fun update(view: android.view.View, now: Boolean) {
        if (now != ready) { ready = now; if (now) view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK) }
    }
}
