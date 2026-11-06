package com.eagalts.still.ui

import android.graphics.Bitmap
import android.view.HapticFeedbackConstants
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridItemInfo
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import com.eagalts.still.Cell
import com.eagalts.still.ColumnSteps
import com.eagalts.still.Filter
import com.eagalts.still.Level
import com.eagalts.still.Still
import com.eagalts.still.levelOf
import com.eagalts.still.media.MediaItem
import com.eagalts.still.t
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/** Зазор между плитками и скругление — от размера плитки: мелкая сетка плотнее и острее. */
fun gapFor(cols: Int): Dp = when { cols <= 5 -> 2.dp; cols <= 8 -> 1.5.dp; else -> 1.dp }
fun radiusFor(cols: Int): Dp = when (cols) { 2 -> 12.dp; 3 -> 8.dp; 5 -> 6.dp; 8 -> 4.dp; else -> 2.dp }

/**
 * Связь ленты с просмотром: где на экране плитка кадра (откуда фото вылетает и куда возвращается)
 * и как до неё докрутить. Без подписки на положение каждой плитки — считается по запросу из раскладки сетки.
 */
class FeedController(val grid: LazyGridState) {
    var origin = Offset.Zero
    var cells: List<Cell> = emptyList()
    var cols = 3
    /** Стопка, которая только что раскрылась (её кадры разлетаются из обложки) или складывается обратно. */
    var fanKey by mutableLongStateOf(-1L)
    var folding by mutableLongStateOf(-1L)
    /** Лента уже встала на место (в конец) и для какого фильтра; стоит ли она в самом конце сейчас. */
    var placed = false
    var shownFilter: Filter? = null
    var atEnd = true

    private fun info(id: Long): LazyGridItemInfo? = grid.layoutInfo.visibleItemsInfo.firstOrNull { it.key == id }

    fun bounds(id: Long): Rect? {
        val i = info(id) ?: return null
        val top = i.offset.y - grid.layoutInfo.viewportStartOffset
        val r = Rect(origin.x + i.offset.x, origin.y + top, origin.x + i.offset.x + i.size.width, origin.y + top + i.size.height)
        val vp = grid.layoutInfo.viewportSize.height
        // плитка под строкой состояния или знаком: прилететь в неё можно, но не в невидимую
        return if (r.bottom < origin.y || r.top > origin.y + vp) null else r
    }

    /** Докрутить, чтобы плитка оказалась на экране (если её не видно), — перед возвращением фото. */
    fun reveal(id: Long) {
        val idx = cells.indexOfFirst { it.key == id }
        if (idx < 0) return
        val vis = grid.layoutInfo.visibleItemsInfo
        val i = vis.firstOrNull { it.index == idx }
        val vp = grid.layoutInfo.viewportSize.height
        if (i != null) {
            val top = i.offset.y - grid.layoutInfo.viewportStartOffset
            if (top > vp * 0.12f && top + i.size.height < vp * 0.85f) return
        }
        grid.requestScrollToItem(idx, 0)
        grid.dispatchRawDelta(-(vp * 0.4f))
    }

    /** Ячейка под точкой (координаты сетки). */
    fun hit(p: Offset): LazyGridItemInfo? {
        val lo = grid.layoutInfo
        return lo.visibleItemsInfo.firstOrNull {
            val top = it.offset.y - lo.viewportStartOffset
            p.x >= it.offset.x && p.x < it.offset.x + it.size.width && p.y >= top && p.y < top + it.size.height
        }
    }
}

@Composable
fun FeedScreen(s: Still, ctl: FeedController) {
    val p = LocalPalette.current
    val density = LocalDensity.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val feed = s.feed
    ctl.cells = feed.cells; ctl.cols = feed.cols
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val capsule by animateFloatAsState(if (s.filter != null) 1f else 0f, fineSpring(0.86f, 420f), label = "capsule")

    // щипок: сетка тянется за пальцами, а на смене ступени новая раскладка подхватывает масштаб и доезжает до 1
    val zoom = remember { Animatable(1f) }
    var pivot by remember { mutableStateOf(Offset.Zero) }
    var anchor by remember { mutableStateOf<Pair<Long, Float>?>(null) }   // кадр под пальцами и его доля по высоте
    var pendingScale by remember { mutableStateOf<Float?>(null) }
    // лента открывается в конце — на самом свежем; если были в конце, новый кадр не уводит вверх
    LaunchedEffect(Unit) { snapshotFlow { ctl.grid.canScrollForward }.collect { ctl.atEnd = !it } }
    LaunchedEffect(feed.gen) {
        val fresh = ctl.shownFilter != s.filter || !ctl.placed
        if (anchor == null && feed.cells.isNotEmpty() && (fresh || ctl.atEnd)) {
            ctl.grid.requestScrollToItem(feed.cells.size - 1)
            ctl.placed = true; ctl.shownFilter = s.filter
        }
        anchor?.let { (key, frac) ->
            val idx = feed.cells.indexOfFirst { it.key == key }
            if (idx >= 0) {
                val tile = with(density) { (ctl.grid.layoutInfo.viewportSize.width / feed.cols).toFloat() }
                ctl.grid.requestScrollToItem(idx, 0)
                ctl.grid.dispatchRawDelta(-(pivot.y - with(density) { top.toPx() } - frac * tile))
            }
            anchor = null
        }
        pendingScale?.let { k ->
            zoom.snapTo(k); pendingScale = null
            zoom.animateTo(1f, spring(0.82f, 380f))
        }
    }

    // выбор протягиванием: якорь, снимок выбора на старте, режим (выделять или снимать)
    var dragAnchor by remember { mutableStateOf(-1) }

    Box(
        Modifier.fillMaxSize().background(p.page).testTag("feed")
            .onGloballyPositioned { ctl.origin = it.positionInWindow() }
            .pointerInput(Unit) {
                val slop = viewConfiguration.touchSlop
                val longPress = viewConfiguration.longPressTimeoutMillis.coerceAtMost(380L)
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    var moved = false
                    var pinching = false
                    // одна ступень за щипок: новая раскладка доезжает пружиной, пальцы её не дёргают
                    var stepped = false
                    var startSpan = 0f
                    var selecting = false
                    var autoJob: kotlinx.coroutines.Job? = null
                    var lastPos = down.position
                    var base: Set<Long> = emptySet()
                    var adding = true
                    fun applyRange(at: Offset) {
                        val hit = ctl.hit(at) ?: return
                        val tiles = ctl.cells
                        val a = dragAnchor; val b = hit.index
                        if (a < 0) return
                        val (lo, hi) = if (a <= b) a to b else b to a
                        val ids = (lo..hi).mapNotNull { (tiles.getOrNull(it) as? Cell.Tile)?.item?.id }
                        val next = if (adding) base + ids else base - ids.toSet()
                        if (next != s.selection) {
                            s.selection = next
                            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        }
                    }
                    fun startSelecting(): Boolean {
                        val h = ctl.hit(down.position)
                        val cell = h?.let { ctl.cells.getOrNull(it.index) } as? Cell.Tile ?: return false
                        selecting = true
                        dragAnchor = h.index
                        base = s.selection
                        adding = cell.item.id !in base
                        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                        applyRange(down.position)
                        return true
                    }
                    /** Одно событие жеста; true — жест окончен. */
                    fun step(ev: androidx.compose.ui.input.pointer.PointerEvent): Boolean {
                        val pressed = ev.changes.filter { it.pressed }
                        if (pressed.isEmpty()) {
                            autoJob?.cancel()
                            if (selecting) { dragAnchor = -1; return true }
                            // щипок не дотянули до ступени — сетка мягко возвращается
                            if (pinching && !stepped) scope.launch { zoom.animateTo(1f, spring(0.8f, 500f)) }
                            if (!moved && !pinching && ev.changes.none { it.isConsumed }) {
                                ctl.hit(down.position)?.let { tap(s, ctl, it.index) }
                            }
                            return true
                        }
                        if (pressed.size >= 2 && !selecting) {
                            val a = pressed[0].position; val b = pressed[1].position
                            val span = (a - b).getDistance()
                            if (!pinching) { pinching = true; startSpan = span; pivot = (a + b) / 2f }
                            ev.changes.forEach { it.consume() }
                            if (stepped) return false
                            val k = span / startSpan.coerceAtLeast(1f)
                            scope.launch { zoom.snapTo(k.coerceIn(0.7f, 1.45f)) }
                            val i = ColumnSteps.indexOf(s.columns)
                            val next = when { k > 1.28f && i > 0 -> i - 1; k < 0.78f && i < ColumnSteps.size - 1 -> i + 1; else -> i }
                            if (next != i && anchor == null) {
                                val h = ctl.hit(pivot)
                                val cell = h?.let { ctl.cells.getOrNull(it.index) } as? Cell.Tile
                                if (cell != null) {
                                    val tTop = h.offset.y - ctl.grid.layoutInfo.viewportStartOffset
                                    anchor = cell.item.id to ((pivot.y - tTop) / h.size.height).coerceIn(0f, 1f)
                                }
                                val ratio = s.columns.toFloat() / ColumnSteps[next]
                                // новая плитка в ratio раз крупнее: начинаем с масштаба, на котором она совпадает со старой
                                pendingScale = (k / ratio).coerceIn(0.5f, 2f)
                                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                s.zoomTo(ColumnSteps[next])
                                stepped = true
                            }
                            return false
                        }
                        if (pinching) return false
                        val c = pressed.first()
                        lastPos = c.position
                        if (selecting) {
                            c.consume()
                            applyRange(c.position)
                            // у края — лента сама едет, выделение продолжается
                            if (autoJob == null) autoJob = scope.launch {
                                val edge = with(density) { 72.dp.toPx() }
                                while (isActive) {
                                    androidx.compose.runtime.withFrameNanos { }
                                    val vp = ctl.grid.layoutInfo.viewportSize.height
                                    val y = lastPos.y
                                    val v = when { y < edge -> -(edge - y) / edge; y > vp - edge -> (y - (vp - edge)) / edge; else -> 0f }
                                    if (v != 0f) { ctl.grid.dispatchRawDelta(v * with(density) { 14.dp.toPx() }); applyRange(lastPos) }
                                }
                            }
                            return false
                        }
                        if (!moved && (c.position - down.position).getDistance() > slop) moved = true
                        // палец поехал — это прокрутка сетки; дальше жест не наш
                        return moved || c.isConsumed
                    }
                    // долгое нажатие — по таймеру: неподвижный палец событий не шлёт
                    val early = withTimeoutOrNull(longPress) {
                        while (true) {
                            val ev = awaitPointerEvent(PointerEventPass.Initial)
                            if (step(ev)) return@withTimeoutOrNull true
                            if (pinching) return@withTimeoutOrNull false
                        }
                        @Suppress("UNREACHABLE_CODE") false
                    }
                    if (early == true) return@awaitEachGesture
                    if (early == null && !startSelecting()) return@awaitEachGesture
                    while (true) {
                        val ev = awaitPointerEvent(PointerEventPass.Initial)
                        if (selecting) ev.changes.forEach { if (it.pressed) it.consume() }
                        if (step(ev)) break
                    }
                }
            }
    ) {
        val padTop = top + 8.dp + 52.dp * capsule
        LazyVerticalGrid(
            columns = GridCells.Fixed(feed.cols),
            state = ctl.grid,
            modifier = Modifier.fillMaxSize().graphicsLayer {
                val k = zoom.value.coerceIn(0.4f, 2.5f)
                scaleX = k; scaleY = k
                transformOrigin = TransformOrigin(
                    (pivot.x / size.width.coerceAtLeast(1f)).coerceIn(0f, 1f),
                    (pivot.y / size.height.coerceAtLeast(1f)).coerceIn(0f, 1f),
                )
            },
            contentPadding = PaddingValues(top = padTop, bottom = bottom + 96.dp),
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(gapFor(feed.cols)),
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(gapFor(feed.cols)),
        ) {
            if (s.filter == Filter.Flow) {
                val old = s.oldScreens()
                if (old.isNotEmpty()) item(key = "suggest", span = { GridItemSpan(maxLineSpan) }) {
                    FlowSuggestion(old.size) { s.trash(old) }
                }
            }
            val n = feed.cells.size
            items(
                count = n,
                key = { feed.cells[it].key },
                span = { if (feed.cells[it] is Cell.Header) GridItemSpan(maxLineSpan) else GridItemSpan(1) },
                contentType = { if (feed.cells[it] is Cell.Header) 0 else 1 },
            ) { i ->
                when (val c = feed.cells[i]) {
                    is Cell.Header -> Header(c.label, levelOf(feed.cols))
                    is Cell.Tile -> Tile(
                        s, ctl, c, i, feed.cols,
                        Modifier.animateItem(
                            fadeInSpec = tween(160),
                            placementSpec = spring(0.86f, 420f, visibilityThreshold = IntOffset(1, 1)),
                            fadeOutSpec = tween(140),
                        ),
                    )
                }
            }
        }
        if (s.loaded && feed.cells.isEmpty()) Empty(s.filter)
        // дымка под строкой состояния: время и батарея читаются поверх любого кадра
        Box(
            Modifier.fillMaxWidth().height(top + 12.dp)
                .background(Brush.verticalGradient(listOf(p.page.copy(alpha = 0.92f), p.page.copy(alpha = 0f))))
        )
        FilterCapsule(s, top, capsule)
        FastScroll(s, ctl, top, bottom)
    }
}

private fun tap(s: Still, ctl: FeedController, index: Int) {
    val c = ctl.cells.getOrNull(index) as? Cell.Tile ?: return
    when {
        s.selection.isNotEmpty() -> s.selection = if (c.item.id in s.selection) s.selection - c.item.id else s.selection + c.item.id
        c.entry.stacked && !c.inStack && c.entry.key !in s.expanded -> { ctl.fanKey = c.entry.key; s.toggleStack(c.entry.key) }
        else -> s.view(c.item)
    }
}

@Composable
private fun Header(label: String, level: Level) {
    val p = LocalPalette.current
    val (size, weight, padTop) = when (level) {
        Level.NONE -> Triple(15.sp, FontWeight.SemiBold, 18.dp)
        Level.MONTHS -> Triple(20.sp, FontWeight.Bold, 22.dp)
        Level.YEARS -> Triple(28.sp, FontWeight.Bold, 26.dp)
    }
    Text(
        label, color = p.text, fontSize = size, fontWeight = weight, fontFamily = UiFont,
        modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = padTop, bottom = 8.dp),
    )
}

@Composable
private fun Tile(s: Still, ctl: FeedController, c: Cell.Tile, index: Int, cols: Int, modifier: Modifier) {
    val p = LocalPalette.current
    val density = LocalDensity.current
    val item = c.item
    val selected = item.id in s.selection
    val hidden = s.viewing != null && flyingId(s) == item.id
    val leaving = item.id in s.leaving
    val radius = radiusFor(cols)

    val pick by animateFloatAsState(if (selected) 1f else 0f, fineSpring(0.62f, 700f), label = "pick")
    val gone by animateFloatAsState(if (leaving) 1f else 0f, fineSpring(0.9f, 500f), label = "gone")

    // кадры раскрытой стопки разлетаются из обложки по очереди и складываются обратно
    val fan = remember(c.entry.key, item.id) { Animatable(if (c.inStack && ctl.fanKey == c.entry.key) 0f else 1f) }
    val order = if (c.inStack) c.entry.members.indexOf(item).coerceAtLeast(1) else 0
    LaunchedEffect(c.entry.key, item.id) {
        if (fan.value < 1f) { delay((order * 16L).coerceAtMost(160L)); fan.animateTo(1f, spring(0.74f, 360f)) }
    }
    LaunchedEffect(ctl.folding) {
        if (c.inStack && ctl.folding == c.entry.key) fan.animateTo(0f, spring(1f, 700f))
    }
    val fanFrom = remember(c.entry.key, item.id) {
        if (!c.inStack) IntOffset.Zero else {
            val cover = ctl.grid.layoutInfo.visibleItemsInfo.firstOrNull { it.key == c.entry.key }
            if (cover == null) IntOffset.Zero else {
                val d = c.entry.members.indexOf(item)
                val col = cover.column + d
                val dx = (cover.column - col % cols); val dy = -(col / cols)
                IntOffset(dx * (cover.size.width), dy * cover.size.height)
            }
        }
    }

    BoxWithConstraints(
        modifier.aspectRatio(1f).testTag("tile-${item.id}")
            .graphicsLayer {
                val f = fan.value
                translationX = fanFrom.x * (1f - f); translationY = fanFrom.y * (1f - f)
                val k = lerp(1f, 0.86f, pick) * lerp(0.82f, 1f, f) * lerp(1f, 0.55f, gone)
                scaleX = k.coerceAtLeast(0f); scaleY = k.coerceAtLeast(0f)
                alpha = when {
                    hidden -> 0f
                    else -> (f.coerceIn(0f, 1f) * (1f - gone)).coerceIn(0f, 1f)
                }
            }
    ) {
        val px = with(density) { maxWidth.roundToPx() }
        val bmp = rememberThumb(s, item, px)
        val shown by animateFloatAsState(if (bmp.value != null) 1f else 0f, if (bmp.fresh) tween(140) else tween(0), label = "load")
        val stackDepth = if (c.entry.stacked && !c.inStack) 1f else 0f
        Spacer(
            Modifier.fillMaxSize().drawBehind {
                val inset = if (stackDepth > 0f) 3.dp.toPx() else 0f
                val r = (radius.toPx() * lerp(1f, 1.6f, pick)).coerceAtLeast(0f)
                if (inset > 0f) {
                    // края стопки: два листа под обложкой, видны над ней
                    drawSheet(Rect(inset * 2.2f, 0f, size.width - inset * 2.2f, size.height - inset * 2), r, p.muted.copy(alpha = 0.22f))
                    drawSheet(Rect(inset * 1.1f, inset * 0.55f, size.width - inset * 1.1f, size.height - inset), r, p.muted.copy(alpha = 0.38f))
                }
                val area = Rect(0f, inset * 1.1f, size.width, size.height)
                drawSheet(area, r, p.tile)
                val b = bmp.value
                if (b != null && shown > 0f) clipPath(androidx.compose.ui.graphics.Path().apply { addOutline(squircleOutline(area.left, area.top, area.right, area.bottom, r)) }) {
                    drawCover(b, area, shown)
                }
            }
        )
        if (item.video && cols <= 5) VideoBadge(item.durationMs)
        if (c.entry.stacked && !c.inStack && cols <= 8) StackBadge(s, ctl, c)
        if (pick > 0.01f) Box(Modifier.align(Alignment.TopEnd).padding(5.dp).size(22.dp).graphicsLayer {
            val k = pick.coerceAtLeast(0f); scaleX = k; scaleY = k; alpha = pick.coerceIn(0f, 1f)
        }.clip(CircleShape).background(p.accent), contentAlignment = Alignment.Center) {
            Icon(Icons.Check, null, tint = Color.White, modifier = Modifier.size(15.dp))
        }
    }
}

private fun androidx.compose.ui.graphics.Path.addOutline(o: androidx.compose.ui.graphics.Outline) = when (o) {
    is androidx.compose.ui.graphics.Outline.Generic -> addPath(o.path)
    is androidx.compose.ui.graphics.Outline.Rounded -> addRoundRect(o.roundRect)
    is androidx.compose.ui.graphics.Outline.Rectangle -> addRect(o.rect)
}

private fun DrawScope.drawSheet(r: Rect, radius: Float, color: Color) {
    when (val o = squircleOutline(r.left, r.top, r.right, r.bottom, radius)) {
        is androidx.compose.ui.graphics.Outline.Generic -> drawPath(o.path, color)
        is androidx.compose.ui.graphics.Outline.Rounded -> drawPath(androidx.compose.ui.graphics.Path().apply { addRoundRect(o.roundRect) }, color)
        is androidx.compose.ui.graphics.Outline.Rectangle -> drawRect(color, o.rect.topLeft, o.rect.size)
    }
}

/** Кадр «по размеру области с обрезкой» (как ContentScale.Crop), без лишних слоёв. */
fun DrawScope.drawCover(b: ImageBitmap, area: Rect, alpha: Float = 1f) {
    val k = maxOf(area.width / b.width, area.height / b.height)
    val sw = area.width / k; val sh = area.height / k
    val sx = (b.width - sw) / 2f; val sy = (b.height - sh) / 2f
    drawImage(
        b, srcOffset = IntOffset(sx.roundToInt(), sy.roundToInt()), srcSize = IntSize(sw.roundToInt().coerceAtLeast(1), sh.roundToInt().coerceAtLeast(1)),
        dstOffset = IntOffset(area.left.roundToInt(), area.top.roundToInt()), dstSize = IntSize(area.width.roundToInt(), area.height.roundToInt()),
        alpha = alpha.coerceIn(0f, 1f),
    )
}

class Thumb(val value: ImageBitmap?, val fresh: Boolean)

/** Миниатюра плитки: из памяти — сразу (без мигания), иначе декодируется; плитка ушла — декодирование отменено. */
@Composable
fun rememberThumb(s: Still, item: MediaItem, px: Int): Thumb {
    val cached = remember(item.id) { s.thumbs.peek(item.id, px)?.asImageBitmap() }
    val state = produceState(Thumb(cached, false), item.id, px / 64) {
        if (value.value != null && s.thumbs.peek(item.id, px) != null) return@produceState
        val b: Bitmap? = s.thumbs.load(item, px)
        if (b != null) value = Thumb(b.asImageBitmap(), cached == null)
    }
    return state.value
}

/** Какой кадр сейчас «в полёте» (открыт в просмотре): его плитка пустеет — фото как будто вынули из неё. */
var flyingIdState = mutableLongStateOf(-1L)
private fun flyingId(@Suppress("UNUSED_PARAMETER") s: Still) = flyingIdState.longValue

@Composable
private fun androidx.compose.foundation.layout.BoxScope.VideoBadge(ms: Long) {
    val sec = (ms / 1000).toInt()
    Text(
        "%d:%02d".format(sec / 60, sec % 60), color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, fontFamily = UiFont,
        style = androidx.compose.ui.text.TextStyle(shadow = androidx.compose.ui.graphics.Shadow(Color(0x80000000), Offset(0f, 1f), 4f)),
        modifier = Modifier.align(Alignment.BottomEnd).padding(end = 6.dp, bottom = 4.dp),
    )
}

@Composable
private fun androidx.compose.foundation.layout.BoxScope.StackBadge(s: Still, ctl: FeedController, c: Cell.Tile) {
    val p = LocalPalette.current
    val open = c.entry.key in s.expanded
    val tint by androidx.compose.animation.animateColorAsState(if (open) p.accent else Color(0x8C000000), tween(200), label = "badge")
    val scope = rememberCoroutineScope()
    Row(
        Modifier.align(Alignment.BottomEnd).padding(5.dp).clip(RoundedCornerShape(50)).background(tint)
            .bouncy(0.85f) {
                if (!open) { ctl.fanKey = c.entry.key; s.toggleStack(c.entry.key) }
                else scope.launch {
                    // складываем: кадры летят обратно в обложку, потом исчезают из сетки
                    ctl.folding = c.entry.key
                    delay(230)
                    s.toggleStack(c.entry.key)
                    ctl.folding = -1L
                }
            }
            .padding(horizontal = 7.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(c.entry.members.size.toString(), color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold, fontFamily = UiFont)
    }
}

@Composable
private fun FlowSuggestion(n: Int, onClean: () -> Unit) {
    val p = LocalPalette.current
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp).clip(SquircleShape(Radius.m)).background(p.sheet)
            .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(t("Скриншоты старше месяца: {0}", n), color = p.text, fontSize = 14.sp, fontFamily = UiFont, modifier = Modifier.weight(1f))
        Text(
            t("Убрать"), color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, fontFamily = UiFont,
            modifier = Modifier.bouncy(0.92f, onClick = onClean).clip(RoundedCornerShape(50)).background(p.accent)
                .padding(horizontal = 14.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun Empty(f: Filter?) {
    val p = LocalPalette.current
    Box(Modifier.fillMaxSize().padding(40.dp), contentAlignment = Alignment.Center) {
        Text(
            when (f) {
                null -> t("Здесь появятся снимки с камеры")
                is Filter.Search -> t("Ничего не нашлось")
                else -> t("Пусто")
            },
            color = p.muted, fontSize = 15.sp, fontFamily = UiFont,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

/** Капсула фильтра сверху: что показано и крестик, который возвращает всю ленту. */
@Composable
private fun FilterCapsule(s: Still, top: Dp, shown: Float) {
    val p = LocalPalette.current
    var last by remember { mutableStateOf<Filter?>(null) }
    s.filter?.let { last = it }
    val f = last ?: return
    if (shown <= 0.001f) return
    Box(Modifier.fillMaxWidth().padding(top = top + 8.dp), contentAlignment = Alignment.TopCenter) {
        Row(
            Modifier.graphicsLayer {
                translationY = -(1f - shown) * 40.dp.toPx()
                alpha = shown.coerceIn(0f, 1f)
                val k = lerp(0.9f, 1f, shown).coerceAtLeast(0f); scaleX = k; scaleY = k
            }.clip(RoundedCornerShape(50)).background(p.sheet).padding(start = 16.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(f.title, color = p.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, fontFamily = UiFont, maxLines = 1,
                modifier = Modifier.padding(vertical = 10.dp))
            Box(Modifier.size(40.dp).testTag("filter-close").bouncy(0.8f) { s.open(null) }, contentAlignment = Alignment.Center) {
                Icon(Icons.Close, t("Вся лента"), tint = p.muted, modifier = Modifier.size(18.dp))
            }
        }
    }
}

/**
 * Быстрая перемотка: пока лента едет, у правого края появляется ручка; тянешь — пролетают месяцы и годы,
 * крупная подпись рядом с пальцем, щелчок на каждой смене месяца. Отпустил — ручка тает через секунду.
 */
@Composable
private fun FastScroll(s: Still, ctl: FeedController, top: Dp, bottom: Dp) {
    val p = LocalPalette.current
    val view = LocalView.current
    val density = LocalDensity.current
    var dragging by remember { mutableStateOf(false) }
    var visible by remember { mutableStateOf(false) }
    var fingerY by remember { mutableStateOf(0f) }
    var label by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        snapshotFlow { ctl.grid.isScrollInProgress to dragging }.collect { (scrolling, drag) ->
            if (scrolling || drag) visible = true
            else { delay(1100); visible = false }
        }
    }
    val a by animateFloatAsState(if (visible && ctl.cells.size > 200) 1f else 0f, tween(if (visible) 120 else 260), label = "fs")
    if (a <= 0.001f && !dragging) return
    BoxWithConstraints(Modifier.fillMaxSize().padding(top = top + 12.dp, bottom = bottom + 90.dp)) {
        val h = with(density) { maxHeight.toPx() }
        val thumbH = with(density) { 44.dp.toPx() }
        val lo = ctl.grid.layoutInfo
        val total = lo.totalItemsCount.coerceAtLeast(1)
        val frac = if (dragging) (fingerY / h).coerceIn(0f, 1f) else (ctl.grid.firstVisibleItemIndex.toFloat() / total).coerceIn(0f, 1f)
        val y = frac * (h - thumbH)
        Box(
            Modifier.align(Alignment.TopEnd).width(28.dp).fillMaxSize().pointerInput(Unit) {
                awaitEachGesture {
                    val d = awaitFirstDown()
                    if (!visible) return@awaitEachGesture
                    d.consume(); dragging = true
                    var lastMonth = ""
                    fun go(py: Float) {
                        fingerY = py
                        val idx = ((py / h).coerceIn(0f, 1f) * (ctl.cells.size - 1)).roundToInt()
                        ctl.grid.requestScrollToItem(idx)
                        val item = (idx downTo 0).firstNotNullOfOrNull { (ctl.cells.getOrNull(it) as? Cell.Tile)?.item }
                        if (item != null) {
                            val l = com.eagalts.still.Dates.scrub(item.taken)
                            if (l != lastMonth) { if (lastMonth.isNotEmpty()) view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK); lastMonth = l; label = l }
                        }
                    }
                    go(d.position.y)
                    while (true) {
                        val ev = awaitPointerEvent()
                        val c = ev.changes.first()
                        if (!c.pressed) break
                        c.consume(); go(c.position.y)
                    }
                    dragging = false
                }
            }
        )
        val grow by animateFloatAsState(if (dragging) 1f else 0f, fineSpring(0.7f, 600f), label = "thumb")
        Box(
            Modifier.align(Alignment.TopEnd).padding(end = 3.dp)
                .graphicsLayer { translationY = y; alpha = a.coerceIn(0f, 1f) }
                .size(width = (4 + 3 * grow).dp, height = 44.dp).clip(RoundedCornerShape(50))
                .background(if (dragging) p.accent else p.muted.copy(alpha = 0.6f))
        )
        AnimatedVisibility(
            dragging && label.isNotEmpty(),
            modifier = Modifier.align(Alignment.TopEnd).graphicsLayer { translationY = (y - with(density) { 6.dp.toPx() }) },
            enter = fadeIn(tween(120)) + scaleIn(spring(0.7f, 600f), initialScale = 0.85f, transformOrigin = TransformOrigin(1f, 0.5f)),
            exit = fadeOut(tween(160)) + scaleOut(targetScale = 0.9f, transformOrigin = TransformOrigin(1f, 0.5f)),
        ) {
            Text(
                label, color = p.text, fontSize = 22.sp, fontWeight = FontWeight.Bold, fontFamily = UiFont,
                modifier = Modifier.padding(end = 26.dp).clip(RoundedCornerShape(50)).background(p.sheet)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    }
}

