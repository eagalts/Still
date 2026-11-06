package com.eagalts.still.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eagalts.still.Filter
import com.eagalts.still.Still
import com.eagalts.still.media.Ephemeral
import com.eagalts.still.t

/**
 * Низ экрана: дымка, под ней — знак. Пока что-то выбрано, на месте знака — действия (знак уходит вниз,
 * действия поднимаются пружиной). Над действиями, если выбран «шум», — «Ещё N таких».
 */
@Composable
fun BottomLayer(s: Still, ctl: FeedController) {
    val p = LocalPalette.current
    val selecting = s.selection.isNotEmpty()
    val ambient by androidx.compose.animation.animateColorAsState(ctl.ambient ?: p.page, tween(700), label = "ambient")
    BackHandler(selecting) { s.selection = emptySet() }
    BackHandler(!selecting && s.filter != null && s.viewing == null && !s.sheet) { s.open(null) }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        // без дымки и плашки: фото идут до самого низа, знак и действия висят на них стеклом
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val similar = remember(s.selection, s.all) { if (s.selection.isEmpty()) emptyList() else s.similarToSelection() }
            AnimatedVisibility(
                selecting && similar.isNotEmpty(),
                enter = fadeIn(tween(160)) + scaleIn(spring(0.6f, 600f), initialScale = 0.8f),
                exit = fadeOut(tween(120)) + scaleOut(targetScale = 0.9f),
            ) {
                Row(
                    Modifier.padding(bottom = 8.dp).testTag("similar").bouncy(0.92f) { s.selectSimilar() }
                        .glass(RoundedCornerShape(50), p, ctl.ambient).padding(start = 12.dp, end = 16.dp, top = 9.dp, bottom = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Similar, null, tint = p.accent, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(t("Ещё {0} таких", similar.size), color = p.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, fontFamily = UiFont)
                }
            }
            AnimatedContent(
                selecting,
                transitionSpec = {
                    (slideInVertically(spring(0.8f, 500f)) { it / 2 } + fadeIn(tween(160))) togetherWith
                        (slideOutVertically(spring(1f, 600f)) { it / 2 } + fadeOut(tween(100)))
                },
                contentAlignment = Alignment.Center,
                label = "bottom",
            ) { sel ->
                Box(Modifier.fillMaxWidth().height(64.dp), contentAlignment = Alignment.Center) {
                    if (!sel) {
                        val unit = with(androidx.compose.ui.platform.LocalDensity.current) { 40.dp.toPx() }
                        val gulps = remember { androidx.compose.runtime.mutableIntStateOf(0) }
                        androidx.compose.runtime.LaunchedEffect(s.leaving) { if (s.leaving.isNotEmpty()) gulps.intValue++ }
                        StillMark(
                            modifier = Modifier.glass(androidx.compose.foundation.shape.CircleShape, p, ctl.ambient),
                            onClick = { s.sheet = true },
                            energy = { kotlin.math.abs(ctl.velocity) / unit },
                            phase = { ctl.phase / unit },
                            gulp = gulps.intValue,
                        )
                    }
                    else SelectionBar(s, ctl)
                }
            }
        }
    }
}

@Composable
private fun SelectionBar(s: Still, ctl: FeedController) {
    val p = LocalPalette.current
    val trash = s.filter == Filter.Trash
    val items = remember(s.selection) { s.selected() }
    val temporary = remember(items) { items.any { Ephemeral.daysLeft(it, System.currentTimeMillis(), s.index.keptIds()) != null } }
    Row(
        Modifier.padding(horizontal = 16.dp).fillMaxWidth().glass(RoundedCornerShape(50), p, ctl.ambient)
            .padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        // счётчик: тап — снять выбор
        Row(
            Modifier.bouncy(0.92f) { s.selection = emptySet() }.padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Close, t("Снять выбор"), tint = p.muted, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            RollingCount(s.selection.size, p.text)
        }
        Row {
            if (trash) {
                BarButton(Icons.Restore, t("Вернуть")) { s.trash(items, restore = true) }
            } else {
                BarButton(Icons.Share, t("Поделиться")) { s.share(items) }
                if (temporary) BarButton(Icons.Pin, t("Оставить")) { s.keep(items.map { it.id }); s.selection = emptySet() }
                BarButton(Icons.Heart, t("Избранное")) { s.favorite(items, true) }
                BarButton(Icons.Trash, t("Удалить")) { s.trash(items) }
            }
        }
    }
}

@Composable
private fun BarButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    val p = LocalPalette.current
    Box(Modifier.size(48.dp).testTag("bar-$label").bouncy(0.8f, onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, label, tint = p.text, modifier = Modifier.size(23.dp))
    }
}

