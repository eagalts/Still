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
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.layout.onPlaced
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
 * Открытая стопка — как папка в iOS: стопка разворачивается в сетку своих кадров. В первый миг вся сетка умещается
 * ровно в плитку стопки (крошечные кадры на её месте), потом растёт на весь экран; лента в это время отступает вглубь
 * и уходит в размытие под почти сплошной фон. Закрытие — то же назад, кадр в кадр: размытие уходит вместе с полётом,
 * и стопка проявляется на месте ровно тогда, когда сетка в неё сложилась. Тап по кадру — просмотр только этих кадров.
 */
@Composable
fun StackView(s: Still, ctl: FeedController) {
    val e = s.stack ?: return
    val p = LocalPalette.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val members = e.members
    val open = remember(e.key) { Animatable(0f) }
    val pull = remember { Animatable(0f) }
    var closing by remember(e.key) { mutableStateOf(false) }
    val slots = remember { HashMap<Long, Rect>() }
    val grid = rememberLazyGridState()
    var gridBox by remember { mutableStateOf(Rect.Zero) }

    fun close() {
        if (closing) return
        closing = true
        scope.launch {
            ctl.stackFrom = ctl.feedBounds(e.cover.id) ?: ctl.stackFrom
            launch { pull.animateTo(0f, spring(1f, 500f)) }
            open.animateTo(0f, spring(1f, 520f, visibilityThreshold = FineThreshold))
            ctl.stackVeil = 0f
            s.closeStack()
        }
    }
    LaunchedEffect(e.key) {
        // лёгкая живость (без заметного перелёта); всё, что не может быть отрицательным, ниже зажато
        open.animateTo(1f, spring(0.86f, 380f, visibilityThreshold = FineThreshold))
    }
    // лента под стопкой — в такт с ней (и при смахивании вниз — тоже)
    LaunchedEffect(e.key) {
        androidx.compose.runtime.snapshotFlow { open.value * (1f - (pull.value / with(density) { 500.dp.toPx() }).coerceIn(0f, 1f)) }
            .collect { ctl.stackVeil = it }
    }
    DisposableEffect(ctl) {
        ctl.overlayBounds = { id -> slots[id]?.translate(0f, pull.value * 0.6f) }
        ctl.overlayReveal = { id ->
            val i = members.indexOfFirst { it.id == id }
            val vis = grid.layoutInfo.visibleItemsInfo
            if (i >= 0 && vis.none { it.index == i && it.offset.y >= 0 && it.offset.y + it.size.height <= grid.layoutInfo.viewportSize.height }) {
                grid.requestScrollToItem(i)
            }
        }
        onDispose { ctl.overlayBounds = null; ctl.overlayReveal = null; ctl.stackVeil = 0f }
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
    Box(Modifier.fillMaxSize().testTag("stack")) {
        // фон почти сплошной: под ним лента только угадывается — ничего не спорит с кадрами стопки
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = ctl.stackVeil.coerceIn(0f, 1f) }
            .background(p.page.copy(alpha = if (p.dark) 0.8f else 0.86f)))
        // замеры — до преобразования (снаружи слоя), иначе полёт искажал бы сам себя
        var col by remember { mutableStateOf(Rect.Zero) }
        var gridAt by remember { mutableStateOf(Offset.Zero) }
        Column(
            Modifier.fillMaxSize().statusBarsPadding().nestedScroll(drag)
                .onGloballyPositioned { col = it.boundsInRoot() }
                .graphicsLayer {
                    val f = open.value
                    val from = ctl.stackFrom
                    translationY = pull.value * 0.6f
                    if (from != null && gridBox.width > 0f) {
                        // сетка стопки сжата в плитку стопки и растёт из неё: масштаб — от ширины плитки к ширине сетки,
                        // центр сетки едет из центра плитки на своё место
                        val k0 = from.width / gridBox.width
                        val k = (k0 + (1f - k0) * f).coerceAtLeast(0f)
                        val c = gridBox.center
                        transformOrigin = androidx.compose.ui.graphics.TransformOrigin(
                            ((c.x - col.left) / size.width).coerceIn(0f, 1f), ((c.y - col.top) / size.height).coerceIn(0f, 1f),
                        )
                        scaleX = k; scaleY = k
                        translationX = (from.center.x - c.x) * (1f - f)
                        translationY += (from.center.y - c.y) * (1f - f)
                        // крошечная сетка на месте стопки проявляется сразу, а не тает весь полёт
                        alpha = (f * 4f).coerceIn(0f, 1f)
                    } else {
                        val k = (0.9f + 0.1f * f).coerceAtLeast(0f); scaleX = k; scaleY = k
                        alpha = f.coerceIn(0f, 1f)
                    }
                }
                // тап мимо кадров (поля, заголовок, пустое место под сеткой) — стопка складывается
                .pointerInput(Unit) {
                    detectTapGestures { at -> val r = at + col.topLeft; if (slots.values.none { it.contains(r) }) close() }
                }
        ) {
            val first = members.first().taken; val last = members.last().taken
            Column(
                Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 10.dp)
                    // заголовок появляется, когда сетка почти выросла, и уходит первым
                    .graphicsLayer { alpha = ((open.value - 0.6f) / 0.4f).coerceIn(0f, 1f) }
            ) {
                Text(Dates.span(first, last), color = p.text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, fontFamily = UiFont)
                Spacer(Modifier.height(2.dp))
                Text(t("{0} фото", members.size), color = p.muted, fontSize = 14.sp, fontFamily = UiFont)
            }
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                state = grid,
                modifier = Modifier.fillMaxSize().onPlaced { c ->
                    // видимая часть сетки — от первого ряда до последнего кадра (не вся высота экрана), в координатах экрана
                    val pp = c.positionInParent()
                    val b = Rect(col.left + pp.x, col.top + pp.y, col.left + pp.x + c.size.width, col.top + pp.y + c.size.height)
                    val rows = (members.size + 2) / 3
                    val tile = (b.width - with(density) { 24.dp.toPx() + 10.dp.toPx() }) / 3f
                    val h = (rows * tile + (rows - 1) * with(density) { 5.dp.toPx() } + with(density) { 4.dp.toPx() }).coerceAtMost(b.height)
                    gridBox = Rect(b.left, b.top, b.right, b.top + h)
                },
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = bottom + 16.dp),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                itemsIndexed(members, key = { _, m -> m.id }) { _, m -> StackTile(s, m, slots) }
            }
        }
    }
}

@Composable
private fun StackTile(s: Still, item: MediaItem, slots: HashMap<Long, Rect>) {
    val p = LocalPalette.current
    val bmp = rememberThumb(s, item, 320)
    val r = with(LocalDensity.current) { 14.dp.toPx() }
    val shape = remember(r) { RoundedCornerShape(r) }
    Box(
        Modifier.aspectRatio(1f).testTag("stack-${item.id}")
            .onGloballyPositioned { slots[item.id] = it.boundsInRoot() }
            .graphicsLayer {
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
