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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
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

/**
 * Геометрия стопки отпечатков — одна на рисунок в ленте ([drawPile]) и на полёт кадров при открытии и закрытии
 * ([StackFlight]): в последнем кадре полёта карточки совпадают с нарисованной стопкой до пикселя.
 * Доли — от стороны плитки: поля, скругление, белая кромка, тень; у нижних карточек — поворот, сдвиг и притемнение.
 */
object Pile {
    const val MARGIN = 0.08f
    const val RADIUS = 0.07f
    const val EDGE = 0.022f
    const val DROP = 0.014f
    class Card(val deg: Float, val dx: Float, val dy: Float, val dim: Float)
    val TOP = Card(0f, 0f, 0f, 0f)
    val B1 = Card(6f, 0.018f, -0.006f, 0.12f)
    val B2 = Card(-8f, -0.018f, 0.008f, 0.22f)
    fun paper(p: Palette) = if (p.dark) Color(0xFFECEAE5) else Color.White
    fun shadow(p: Palette) = Color.Black.copy(alpha = if (p.dark) 0.42f else 0.2f)

    /** Какие кадры выглядывают из-под верхнего: середина и последний из остальных (второй — если он другой). */
    fun backs(e: com.eagalts.still.media.Entry): Pair<MediaItem?, MediaItem?> {
        val o = e.members.filter { it.id != e.cover.id }
        val b1 = o.getOrNull(o.size / 2); val b2 = o.lastOrNull()?.takeIf { it != b1 }
        return b1 to b2
    }
}

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
        val m = side * Pile.MARGIN
        val r = (side * Pile.RADIUS).coerceIn(2.dp.toPx(), 10.dp.toPx())
        val edge = (side * Pile.EDGE).coerceIn(1.dp.toPx(), 2.5.dp.toPx())
        val card = Rect(m, m, side - m, side - m)
        c.innerRect = card.deflate(edge)
        c.outer.reset(); c.outer.addRoundRect(RoundRect(card, CornerRadius(r)))
        c.inner.reset(); c.inner.addRoundRect(RoundRect(c.innerRect, CornerRadius((r - edge).coerceAtLeast(0f))))
    }
    val paper = Pile.paper(p)
    val drop = side * Pile.DROP
    fun card(b: ImageBitmap?, deg: Float, dx: Float, dy: Float, dim: Float) {
        translate(dx * side, dy * side) {
            rotate(deg, Offset(side / 2f, side / 2f)) {
                translate(0f, drop) { drawPath(c.outer, Pile.shadow(p)) }
                drawPath(c.outer, paper)
                clipPath(c.inner) {
                    if (b != null) drawCover(b, c.innerRect) else drawRect(ph)
                    if (dim > 0f) drawRect(Color.Black, alpha = dim)
                }
            }
        }
    }
    // повороты и сдвиги подобраны так, чтобы углы карточек и тень оставались в полях 8%
    card(b2, Pile.B2.deg, Pile.B2.dx, Pile.B2.dy, Pile.B2.dim)
    card(b1, Pile.B1.deg, Pile.B1.dx, Pile.B1.dy, Pile.B1.dim)
    // стопка открыта — верхний снимок «вынут» (он сейчас в сетке стопки), нижние лежат на месте
    if (!coverOut) card(cover, 0f, 0f, 0f, 0f)
    if (shade > 0.01f) drawRect(p.page, alpha = shade)
}

/**
 * Открытая стопка — как стопка отпечатков, которую разложили на столе. Открытие: верхний снимок поднимается со
 * стопки и встаёт на своё место в сетке, остальные выходят из-под него на свои; лента в это время уходит вглубь
 * и в размытие. Закрытие — то же назад, кадр в кадр: снимки складываются в стопку, верхний ложится сверху,
 * два выглядывающих — под своими углами, остальные — под ними; в последнем кадре это ровно та стопка, что в ленте.
 * Тап по кадру — просмотр только этих кадров.
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
    // места кадров в сетке — наблюдаемые: слой полёта перерисуется, как только они измерены (обычная таблица
    // этого не сообщала, и при открытии карточки не появлялись вовсе — только размытие, а потом сразу сетка)
    val slots = remember { androidx.compose.runtime.mutableStateMapOf<Long, Rect>() }
    val grid = rememberLazyGridState()
    val (b1, b2) = remember(e) { Pile.backs(e) }
    // во время полёта сетка спрятана — кадры летят отдельным слоем поверх всего (сетка обрезала бы их по краю)
    val settled by remember { androidx.compose.runtime.derivedStateOf { open.value >= 0.999f && !closing } }

    fun close() {
        if (closing) return
        closing = true
        scope.launch {
            // куда складываться — то же место, откуда открывали: лента под стопкой не двигалась, а сейчас она отодвинута
            // вглубь (уменьшена) — пересчёт отсюда дал бы смещённую стопку и прыжок в последнем кадре
            launch { pull.animateTo(0f, spring(1f, 500f)) }
            open.animateTo(0f, spring(1f, 420f, visibilityThreshold = FineThreshold))
            ctl.stackVeil = 0f
            s.closeStack()
        }
    }
    LaunchedEffect(e.key) {
        // лёгкая живость (без заметного перелёта); всё, что не может быть отрицательным, ниже зажато
        open.animateTo(1f, spring(0.88f, 360f, visibilityThreshold = FineThreshold))
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
        onDispose { ctl.overlayBounds = null; ctl.overlayReveal = null; ctl.stackVeil = 0f; ctl.pileTaken = false }
    }
    // «назад» — предиктивный жест Android: тянешь от края — сетка уже сворачивается к своей стопке в такт пальцу
    // (до половины пути), лента проясняется; отпустил — доезжает отсюда же; передумал — разворачивается обратно
    androidx.activity.compose.PredictiveBackHandler { progress ->
        try {
            progress.collect { ev ->
                if (!closing) {
                    val p = ev.progress.coerceIn(0f, 1f)
                    open.snapTo(1f - 0.5f * (1f - (1f - p) * (1f - p)))
                }
            }
            close()
        } catch (c: kotlinx.coroutines.CancellationException) {
            if (!closing) scope.launch { open.animateTo(1f, spring(0.86f, 500f, visibilityThreshold = FineThreshold)) }
            throw c
        }
    }

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
        var col by remember { mutableStateOf(Rect.Zero) }
        Column(
            Modifier.fillMaxSize().statusBarsPadding().nestedScroll(drag)
                .onGloballyPositioned { col = it.boundsInRoot() }
                .graphicsLayer { translationY = pull.value * 0.6f }
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
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = bottom + 16.dp),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                itemsIndexed(members, key = { _, m -> m.id }) { _, m -> StackTile(s, m, slots) { settled } }
            }
        }
        if (!settled) StackFlight(s, ctl, e, b1, b2, slots, { open.value })
    }
}

/**
 * Кадры в полёте между стопкой в ленте и сеткой: каждый — карточка с той же кромкой, тенью, скруглением и наклоном,
 * что в стопке, и плавно превращается в плитку сетки. Порядок — как в стопке: верхний сверху, два выглядывающих
 * под ним, остальные ниже (их не видно, пока они в стопке).
 */
@Composable
private fun StackFlight(
    s: Still, ctl: FeedController, e: com.eagalts.still.media.Entry, b1: MediaItem?, b2: MediaItem?,
    slots: MutableMap<Long, Rect>, open: () -> Float,
) {
    val p = LocalPalette.current
    val order = e.members.filter { it.id != e.cover.id && it != b1 && it != b2 } + listOfNotNull(b2, b1) + e.cover
    Box(Modifier.fillMaxSize()) {
        var drawn = false
        for (m in order) {
            val slot = slots[m.id] ?: continue
            val card = when (m) { e.cover -> Pile.TOP; b1 -> Pile.B1; b2 -> Pile.B2; else -> Pile.TOP }
            FlyingCard(s, ctl, m, slot, card, open, p)
            drawn = true
        }
        // стопка в ленте уходит ровно в тот кадр, когда её подменили карточки (места в сетке уже измерены)
        if (drawn) androidx.compose.runtime.SideEffect { ctl.pileTaken = true }
    }
}

@Composable
private fun FlyingCard(s: Still, ctl: FeedController, item: MediaItem, slot: Rect, card: Pile.Card, open: () -> Float, p: Palette) {
    val density = LocalDensity.current
    val bmp = rememberThumb(s, item, 320)
    val ph = placeholder(s, item, p)
    val gridR = with(density) { 14.dp.toPx() }
    val path = remember { Path() }
    Spacer(
        Modifier.testTag("flying-${item.id}")
            .offset { androidx.compose.ui.unit.IntOffset(slot.left.toInt(), slot.top.toInt()) }
            .size(with(density) { slot.width.toDp() }, with(density) { slot.height.toDp() })
            .graphicsLayer {
                val f = open().coerceIn(0f, 1.05f)
                val from = ctl.stackFrom ?: return@graphicsLayer
                // где эта карточка лежит в стопке на экране: поля, сдвиг, наклон — как в drawPile
                val side = from.width
                val c = from.deflate(side * Pile.MARGIN).translate(card.dx * side, card.dy * side)
                val sc = (c.width / slot.width + (1f - c.width / slot.width) * f).coerceAtLeast(0f)
                scaleX = sc; scaleY = sc
                translationX = (c.center.x - slot.center.x) * (1f - f)
                translationY = (c.center.y - slot.center.y) * (1f - f)
                rotationZ = card.deg * (1f - f)
            }
            .drawBehind {
                val f = open().coerceIn(0f, 1f)
                val from = ctl.stackFrom
                val side = from?.width ?: size.width
                // всё в долях стороны стопки на экране, переведённое в масштаб этой плитки (слой её сожмёт)
                val sc = if (from == null) 1f else ((side * (1f - 2 * Pile.MARGIN)) / size.width).let { it + (1f - it) * f }.coerceAtLeast(0.05f)
                val k = 1f - f
                val pileR = (side / ctl.pileScale() * Pile.RADIUS).coerceIn(2.dp.toPx(), 10.dp.toPx()) * ctl.pileScale()
                val r = (pileR + (gridR - pileR) * f) / sc
                val edge = ((side / ctl.pileScale() * Pile.EDGE).coerceIn(1.dp.toPx(), 2.5.dp.toPx()) * ctl.pileScale() / sc) * k
                val drop = side * Pile.DROP / sc * k
                val full = Rect(0f, 0f, size.width, size.height)
                if (k > 0.001f) {
                    drawRoundRect(Pile.shadow(p).copy(alpha = Pile.shadow(p).alpha * k), Offset(0f, drop), size, CornerRadius(r))
                    drawRoundRect(Pile.paper(p), Offset.Zero, size, CornerRadius(r))
                }
                val inner = full.deflate(edge)
                path.reset(); path.addRoundRect(RoundRect(inner, CornerRadius((r - edge).coerceAtLeast(0f))))
                clipPath(path) {
                    val b = bmp.value
                    if (b != null) drawCover(b, inner) else drawRect(ph)
                    if (card.dim * k > 0.001f) drawRect(Color.Black, alpha = card.dim * k)
                }
            }
    )
}

@Composable
private fun StackTile(s: Still, item: MediaItem, slots: MutableMap<Long, Rect>, settled: () -> Boolean) {
    val p = LocalPalette.current
    val bmp = rememberThumb(s, item, 320)
    val r = with(LocalDensity.current) { 14.dp.toPx() }
    val shape = remember(r) { RoundedCornerShape(r) }
    Box(
        Modifier.aspectRatio(1f).testTag("stack-${item.id}")
            .onGloballyPositioned { val r = it.boundsInRoot(); if (slots[item.id] != r) slots[item.id] = r }
            .graphicsLayer {
                // в полёте кадр нарисован карточкой в слое полёта — здесь его нет
                if (!settled() || (s.viewing != null && flyingIdState.longValue == item.id)) alpha = 0f
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
