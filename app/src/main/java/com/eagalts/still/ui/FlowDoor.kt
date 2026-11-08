package com.eagalts.still.ui

import android.view.HapticFeedbackConstants
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
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

/** Порог потягивания (dp, уже с сопротивлением): дальше — отпустил, и «Поток» открылся. */
const val FlowDoorPull = 72f

/**
 * Вход в «Поток» — в пустом месте под самым свежим рядом ленты: маленькая стопка последних кадров «Потока» и его имя.
 * Долистал до конца и тянешь дальше — лента упруго уходит вверх, стопка растёт навстречу, на пороге — щелчок;
 * отпустил — открывается «Поток». Никакой кнопки: это продолжение самой ленты.
 * [bottomOfFeed] — где кончается самый свежий ряд (координаты ленты) или null, если его не видно; [pull] — потянуто, px.
 */
@Composable
fun FlowDoor(s: Still, bottomOfFeed: () -> Float?, pull: () -> Float) {
    val p = LocalPalette.current
    val density = LocalDensity.current
    // последние три кадра «Потока» — нижние карточки и обложка маленькой стопки
    val recent = remember(s.all, s.paper) {
        s.all.asSequence().filter { Ephemeral.temporary(it, s.paper) }.sortedByDescending { it.added }.take(3).toList()
    }
    val count = remember(s.all, s.paper) { s.all.count { Ephemeral.temporary(it, s.paper) } }
    if (recent.isEmpty()) return
    val threshold = with(density) { FlowDoorPull.dp.toPx() }
    val ready = pull() >= threshold
    val tint by animateColorAsState(if (ready) p.accent else p.muted, tween(160), label = "door")
    val cover = rememberThumb(s, recent[0], 160)
    val b1 = recent.getOrNull(1)?.let { rememberThumb(s, it, 160) }
    val b2 = recent.getOrNull(2)?.let { rememberThumb(s, it, 160) }
    val pile = remember { PileCache() }
    val ph = placeholder(s, recent[0], p)
    Column(
        Modifier.fillMaxWidth().testTag("flow-door")
            .offset {
                // под самым свежим рядом, с воздухом; ряда не видно — дверь за краем экрана
                val y = bottomOfFeed() ?: 100_000f
                IntOffset(0, (y + 28.dp.toPx() - pull()).roundToInt())
            }
            .graphicsLayer {
                val k = (pull() / threshold).coerceIn(0f, 1.2f)
                alpha = (0.55f + 0.45f * k).coerceIn(0f, 1f)
                val sc = 1f + 0.12f * k
                scaleX = sc; scaleY = sc
            },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Canvas(Modifier.size(64.dp)) {
            drawPile(pile, cover.value, b1?.value, b2?.value, ph, p, 3, 0f)
        }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(t("Поток"), color = tint, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, fontFamily = UiFont)
            Spacer(Modifier.width(6.dp))
            Text(count.toString(), color = p.muted, fontSize = 13.sp, fontFamily = UiFont)
        }
    }
}

/** Щелчок на пороге — один раз за потягивание, в обе стороны. */
class DoorClick { var ready = false
    fun update(view: android.view.View, now: Boolean) {
        if (now != ready) { ready = now; if (now) view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK) }
    }
}
