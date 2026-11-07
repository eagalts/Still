package com.eagalts.still.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eagalts.still.Dates
import com.eagalts.still.Still
import com.eagalts.still.media.MediaItem
import com.eagalts.still.t
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Пути карточек стопки — один раз на размер плитки, не на каждом кадре. */
class PileCache {
    var side = -1f
    val outer = Path()
    val inner = Path()
    var innerRect = Rect.Zero
}

/**
 * Стопка в ленте — стопка отпечатков: сверху лучший кадр, из-под него выглядывают ещё два, повёрнутые в разные
 * стороны. Белая кромка и тень — как у бумажных снимков; число кадров не пишется — видно, что их несколько.
 */
fun DrawScope.drawPile(
    c: PileCache, cover: ImageBitmap?, b1: ImageBitmap?, b2: ImageBitmap?, ph: Color, p: Palette,
    @Suppress("UNUSED_PARAMETER") cols: Int, shade: Float, coverOut: Boolean = false,
) {
    val side = size.minDimension
    if (c.side != side) {
        c.side = side
        // поля под повёрнутые карточки: угол 8° выносит угол карточки на ~5,5% стороны, сдвиг — ещё ~2%
        val m = side * 0.08f
        val r = (side * 0.07f).coerceIn(2.dp.toPx(), 10.dp.toPx())
        val edge = (side * 0.022f).coerceIn(1.dp.toPx(), 2.5.dp.toPx())
        val card = Rect(m, m, side - m, side - m)
        c.innerRect = card.deflate(edge)
        c.outer.reset(); c.outer.addRoundRect(RoundRect(card, CornerRadius(r)))
        c.inner.reset(); c.inner.addRoundRect(RoundRect(c.innerRect, CornerRadius((r - edge).coerceAtLeast(0f))))
    }
    val paper = if (p.dark) Color(0xFFECEAE5) else Color.White
    val drop = side * 0.014f
    fun card(b: ImageBitmap?, deg: Float, dx: Float, dy: Float, dim: Float) {
        translate(dx * side, dy * side) {
            rotate(deg, Offset(side / 2f, side / 2f)) {
                translate(0f, drop) { drawPath(c.outer, Color.Black.copy(alpha = if (p.dark) 0.42f else 0.2f)) }
                drawPath(c.outer, paper)
                clipPath(c.inner) {
                    if (b != null) drawCover(b, c.innerRect) else drawRect(ph)
                    if (dim > 0f) drawRect(Color.Black, alpha = dim)
                }
            }
        }
    }
    // повороты и сдвиги подобраны так, чтобы углы карточек и тень оставались в полях 8%
    card(b2, -8f, -0.018f, 0.008f, 0.22f)
    card(b1, 6f, 0.018f, -0.006f, 0.12f)
    // стопка открыта — верхний снимок «вынут» (он сейчас в сетке стопки), нижние лежат на месте
    if (!coverOut) card(cover, 0f, 0f, 0f, 0f)
    if (shade > 0.01f) drawRect(p.page, alpha = shade)
}

/**
 * Открытая стопка — как папка: лента уходит в размытие, верхний снимок стопки перелетает на своё место в сетке,
 * остальные проявляются на своих местах волной от него; сверху — когда это было. Тап по кадру — просмотр только
 * этих кадров; «назад», тап мимо кадров или смахивание вниз — остальные гаснут, верхний улетает обратно в стопку.
 * Ничего не вращается и не летит толпой: в движении всегда один кадр.
 */
@Composable
fun StackView(s: Still, ctl: FeedController) {
    val e = s.stack ?: return
    val p = LocalPalette.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val members = e.members
    val open = remember { Animatable(0f) }
    val pull = remember { Animatable(0f) }
    var closing by remember { mutableStateOf(false) }
    val slots = remember { HashMap<Long, Rect>() }
    val grid = rememberLazyGridState()
    // у каждого кадра свой полёт: вылетают по очереди, складываются вместе
    val fly = remember(e.key) { HashMap<Long, Animatable<Float, AnimationVector1D>>() }
    fun flyOf(id: Long) = fly.getOrPut(id) { Animatable(0f) }

    fun close() {
        if (closing) return
        closing = true
        scope.launch {
            ctl.stackFrom = ctl.feedBounds(e.cover.id) ?: ctl.stackFrom
            launch { pull.animateTo(0f, spring(1f, 500f)) }
            launch { open.animateTo(0f, spring(1f, 380f)) }
            members.forEach { m -> if (m.id != e.cover.id) launch { flyOf(m.id).animateTo(0f, spring(1f, 900f, visibilityThreshold = FineThreshold)) } }
            flyOf(e.cover.id).animateTo(0f, spring(1f, 420f, visibilityThreshold = FineThreshold))
            s.closeStack()
        }
    }

    LaunchedEffect(e.key) {
        launch { open.animateTo(1f, spring(1f, 380f)) }
        // верхний снимок — сразу; остальные — волной от его места в сетке (ближние раньше)
        val c = members.indexOfFirst { it.id == e.cover.id }.coerceAtLeast(0)
        members.forEachIndexed { i, m ->
            launch {
                if (i == c) flyOf(m.id).animateTo(1f, spring(0.92f, 360f, visibilityThreshold = FineThreshold))
                else {
                    val d = Math.abs(i / 3 - c / 3) + Math.abs(i % 3 - c % 3)
                    delay((90L + d * 35L).coerceAtMost(300L))
                    flyOf(m.id).animateTo(1f, spring(1f, 420f, visibilityThreshold = FineThreshold))
                }
            }
        }
    }
    // кадры просмотра вылетают из сетки стопки и возвращаются в неё
    DisposableEffect(ctl) {
        ctl.overlayBounds = { id -> slots[id]?.translate(0f, pull.value * 0.6f) }
        ctl.overlayReveal = { id ->
            val i = members.indexOfFirst { it.id == id }
            val vis = grid.layoutInfo.visibleItemsInfo
            if (i >= 0 && vis.none { it.index == i && it.offset.y >= 0 && it.offset.y + it.size.height <= grid.layoutInfo.viewportSize.height }) {
                grid.requestScrollToItem(i)
            }
        }
        onDispose { ctl.overlayBounds = null; ctl.overlayReveal = null }
    }
    BackHandler { close() }

    val drag = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (pull.value > 0f && available.y < 0f) {
                    val v = (pull.value + available.y).coerceAtLeast(0f)
                    val used = v - pull.value
                    scope.launch { pull.snapTo(v) }
                    return Offset(0f, used)
                }
                return Offset.Zero
            }
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (available.y > 0f && source == NestedScrollSource.UserInput) {
                    scope.launch { pull.snapTo(pull.value + available.y) }
                    return Offset(0f, available.y)
                }
                return Offset.Zero
            }
            override suspend fun onPreFling(available: Velocity): Velocity {
                if (pull.value <= 0f) return Velocity.Zero
                if (pull.value > with(density) { 90.dp.toPx() } || available.y > 2400f) close()
                else pull.animateTo(0f, spring(0.86f, 500f))
                return available
            }
        }
    }

    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val shownP = { (open.value * (1f - pull.value / with(density) { 400.dp.toPx() })).coerceIn(0f, 1f) }
    Box(Modifier.fillMaxSize().testTag("stack")) {
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = shownP() }.background(p.page.copy(alpha = if (p.dark) 0.66f else 0.74f)))
        var origin by remember { mutableStateOf(Offset.Zero) }
        Column(
            Modifier.fillMaxSize().statusBarsPadding().nestedScroll(drag)
                .graphicsLayer { translationY = pull.value * 0.6f }
                .onGloballyPositioned { origin = it.positionInRoot() }
                // тап мимо кадров (поля, заголовок, пустое место под сеткой) — стопка складывается
                .pointerInput(Unit) {
                    detectTapGestures { at -> val r = at + origin; if (slots.values.none { it.contains(r) }) close() }
                }
        ) {
            val first = members.first().taken; val last = members.last().taken
            Column(
                Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 10.dp)
                    .graphicsLayer { val k = shownP(); alpha = k; translationY = (1f - k) * -12.dp.toPx() }
            ) {
                Text(Dates.span(first, last), color = p.text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, fontFamily = UiFont)
                Spacer(Modifier.height(2.dp))
                Text(t("{0} фото", members.size), color = p.muted, fontSize = 14.sp, fontFamily = UiFont)
            }
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                state = grid,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = bottom + 16.dp),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                itemsIndexed(members, key = { _, m -> m.id }) { i, m ->
                    StackTile(s, ctl, m, m.id == e.cover.id, slots, flyOf(m.id))
                }
            }
        }
    }
}

@Composable
private fun StackTile(s: Still, ctl: FeedController, item: MediaItem, cover: Boolean, slots: HashMap<Long, Rect>, fly: Animatable<Float, AnimationVector1D>) {
    val p = LocalPalette.current
    val bmp = rememberThumb(s, item, 320)
    val r = with(LocalDensity.current) { 14.dp.toPx() }
    val shape = remember(r) { RoundedCornerShape(r) }
    Box(
        Modifier.aspectRatio(1f).testTag("stack-${item.id}")
            .onGloballyPositioned { slots[item.id] = it.boundsInRoot() }
            .graphicsLayer {
                val f = fly.value
                val from = ctl.stackFrom
                val slot = slots[item.id]
                if (cover && from != null && slot != null && slot.width > 0f) {
                    // верхний снимок летит из стопки в своё место: стартует ровно там, где лежал в стопке (поля 8%)
                    val card = from.deflate(from.width * 0.08f)
                    val k = card.width / slot.width
                    translationX = (card.center.x - slot.center.x) * (1f - f)
                    translationY = (card.center.y - slot.center.y) * (1f - f)
                    val sc = (k + (1f - k) * f).coerceAtLeast(0f)
                    scaleX = sc; scaleY = sc
                } else {
                    // остальные — проявляются на своём месте, чуть поднимаясь навстречу
                    val sc = (0.94f + 0.06f * f).coerceAtLeast(0f); scaleX = sc; scaleY = sc
                    alpha = f.coerceIn(0f, 1f)
                }
                if (s.viewing != null && flyingIdState.longValue == item.id) alpha = 0f
                this.shape = shape; clip = true
            }
            .bouncy(0.95f) { s.viewInStack(item) }
    ) {
        val ph = placeholder(s, item, p)
        Spacer(Modifier.fillMaxSize().drawBehind {
            val b = bmp.value
            if (b != null) drawCover(b, Rect(0f, 0f, size.width, size.height)) else drawRect(ph)
        })
        if (item.video) VideoBadge(item.durationMs)
    }
}
