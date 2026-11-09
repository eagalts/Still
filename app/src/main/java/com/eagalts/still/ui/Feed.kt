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
import androidx.compose.foundation.layout.wrapContentWidth
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
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.zIndex
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.input.nestedscroll.nestedScroll
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

/**
 * Сетка как в Фото Apple: квадратные кадры без скруглений, разделённые тонкой линией фона. Чем мельче сетка,
 * тем тоньше линия. Скругление появляется только у кадра, который «взяли» (выбор, полёт в просмотр).
 */
fun gapFor(cols: Int): Dp = when { cols <= 5 -> 1.5.dp; cols <= 8 -> 1.dp; else -> 0.5.dp }
fun radiusFor(@Suppress("UNUSED_PARAMETER") cols: Int): Dp = 0.dp
/** Скругление взятого кадра. */
val LiftRadius = 10.dp

/** Положение плитки в сетке — пишется на раскладке, читается в отрисовке (без рекомпозиций). */
class Spot { var x = 0f; var y = 0f; var w = 0f; var h = 0f }

/**
 * Связь ленты с просмотром: где на экране плитка кадра (откуда фото вылетает и куда возвращается)
 * и как до неё докрутить. Без подписки на положение каждой плитки — считается по запросу из раскладки сетки.
 */
class FeedController(val grid: LazyGridState) {
    var origin = Offset.Zero
    var cells: List<Cell> = emptyList()
    var cols = 3
    /** Где на экране была стопка, которую открыли: из неё вылетают кадры и в неё же складываются. */
    var stackFrom: Rect? = null
    /** Карточки стопки уже на столе (в полёте или в сетке) — стопку в ленте можно убрать, пустого кадра не будет. */
    var pileTaken by mutableStateOf(false)
    /** На сколько сдвинуть начало, чтобы первая строка встала под капсулу, а не в полэкрана (поле линзы). */
    var topShift = 0
    /** Слабый телефон: «Поток» под лентой только бледнеет, без размытия. */
    var weak = false
    private val blurs = HashMap<Int, androidx.compose.ui.graphics.RenderEffect>()
    /** Размытие кадра «Потока» — из кэша по целым пикселям (на кадре ничего не создаётся); 0 и слабый телефон — без него. */
    fun flowBlur(r: Float): androidx.compose.ui.graphics.RenderEffect? {
        val k = (r / 2f).toInt() * 2
        if (k < 2 || weak || android.os.Build.VERSION.SDK_INT < 31) return null
        return blurs.getOrPut(k) { androidx.compose.ui.graphics.BlurEffect(k.toFloat(), k.toFloat(), androidx.compose.ui.graphics.TileMode.Decal) }
    }
    /** Масштаб линзы у плитки открытой стопки: стопка в ленте нарисована в её размере и уменьшена линзой. */
    fun pileScale(): Float = if (tilePx > 0) ((stackFrom?.width ?: tilePx.toFloat()) / tilePx).coerceIn(0.3f, 2f) else 1f
    /** Насколько открыта стопка (0…1): лента под ней уходит вглубь и в размытие ровно в такт. */
    var stackVeil by androidx.compose.runtime.mutableFloatStateOf(0f)
    /**
     * Пока открыта стопка, кадры просмотра вылетают из её сетки и возвращаются в неё, а не в ленту:
     * [overlayBounds] — где кадр в сетке стопки, [overlayReveal] — докрутить к нему.
     */
    var overlayBounds: ((Long) -> Rect?)? = null
    var overlayReveal: ((Long) -> Unit)? = null
    /** Лента уже встала на место (в конец) и для какого фильтра; стоит ли она в самом конце сейчас. */
    var placed = false
    var shownFilter: Filter? = null
    var atEnd = true
    /** Размер плитки в пикселях — считается один раз на раскладку, а не в каждой плитке. */
    var tilePx = 0
    private val shapes = HashMap<Int, androidx.compose.ui.graphics.Shape>()
    /** Скругление плитки — из кэша по целым пикселям: на кадре ничего не создаётся. */
    fun shape(r: Float): androidx.compose.ui.graphics.Shape {
        val k = r.roundToInt().coerceAtLeast(0)
        return shapes.getOrPut(k) { if (k == 0) androidx.compose.ui.graphics.RectangleShape else RoundedCornerShape(k.toFloat()) }
    }
    /** Идёт щипок: силу линзы ведут пальцы. */
    var pinching = false
    /**
     * Идёт масштабирование (щипок и доводка после него): перестановка плиток не анимируется — плитки встают
     * на новые места сразу, ровно под пальцами. Иначе каждая ступень запускала полёт всех плиток врассыпную.
     */
    var zooming by mutableStateOf(false)

    // ——— линза ———
    /** Сила линзы сейчас (анимируется: выбор, отдаление). Быстрая прокрутка ослабляет её ещё — см. [lensNow]. */
    var lens by androidx.compose.runtime.mutableFloatStateOf(1f)
    var fastUnit = 1f
    /** Сила с учётом скорости: несёшься по ленте — линза выпрямляется в плотную сетку. */
    fun lensNow(): Float = lens * (1f - (abs(velocity) / fastUnit).coerceIn(0f, 1f) * 0.85f)
    private val pose = FloatArray(4)

    /** Где кадр ячейки виден на самом деле (линза), в координатах сетки. */
    fun visual(i: LazyGridItemInfo): Rect {
        val lo = grid.layoutInfo
        val top = i.offset.y - lo.viewportStartOffset
        val w = i.size.width.toFloat(); val h = i.size.height.toFloat()
        val cx = i.offset.x + w / 2f; val cy = top + h / 2f
        Lens.pose(cx, cy, lo.viewportSize.width.toFloat(), lo.viewportSize.height.toFloat(), lensNow(), pose)
        val s = pose[Lens.SCALE]
        val vx = cx + pose[Lens.TX]; val vy = cy + pose[Lens.TY]
        return Rect(vx - w * s / 2f, vy - h * s / 2f, vx + w * s / 2f, vy + h * s / 2f)
    }

    // ——— физика ———
    /** Скорость прокрутки, px за кадр (сглаженная, со знаком): от неё плитки тянутся, а ряды расходятся. */
    var velocity by androidx.compose.runtime.mutableFloatStateOf(0f)
    /** Сколько проехала лента всего — фаза ряби на глади знака: волна бежит вместе с лентой. */
    var phase by androidx.compose.runtime.mutableFloatStateOf(0f)
    /** Цвет того, что сейчас на экране: им подсвечены дымка, шторка и строка состояния. */
    var ambient by mutableStateOf<Color?>(null)

    fun push(dy: Float) {
        velocity = velocity * 0.55f + dy * 0.45f
        phase += dy
    }

    private fun info(id: Long): LazyGridItemInfo? = grid.layoutInfo.visibleItemsInfo.firstOrNull { it.key == id }

    fun bounds(id: Long): Rect? {
        overlayBounds?.let { return it(id) }
        return feedBounds(id)
    }

    /** Где плитка кадра в самой ленте (под открытой стопкой тоже). */
    fun feedBounds(id: Long): Rect? {
        val i = info(id) ?: return null
        val r = visual(i).translate(origin)
        val vp = grid.layoutInfo.viewportSize.height
        // плитка под строкой состояния или знаком: прилететь в неё можно, но не в невидимую
        return if (r.bottom < origin.y || r.top > origin.y + vp) null else r
    }

    /** Докрутить, чтобы плитка оказалась на экране (если её не видно), — перед возвращением фото. */
    fun reveal(id: Long) {
        overlayReveal?.let { it(id); return }
        val idx = cells.indexOfFirst { it.key == id }
        if (idx < 0) return
        val vis = grid.layoutInfo.visibleItemsInfo
        val i = vis.firstOrNull { it.index == idx }
        val vp = grid.layoutInfo.viewportSize.height
        // под линзой ряд кадра встаёт ровно в центр (поля в полэкрана: «в начало» = в центр), как у магнитов
        if (Lens.strengthFor(cols) > 0f) { grid.requestScrollToItem(idx, 0); return }
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
        // по тому, что видно: под линзой кадр меньше своей ячейки и сдвинут к центру
        return lo.visibleItemsInfo.firstOrNull { visual(it).contains(p) }
            ?: lo.visibleItemsInfo.minByOrNull { (visual(it).center - p).getDistanceSquared() }
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
    val appCtx = androidx.compose.ui.platform.LocalContext.current
    ctl.weak = remember { (appCtx.getSystemService(android.content.Context.ACTIVITY_SERVICE) as? android.app.ActivityManager)?.isLowRamDevice == true }
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val capsule by animateFloatAsState(if (s.filter != null) 1f else 0f, fineSpring(0.86f, 420f), label = "capsule")
    val ambient by androidx.compose.animation.animateColorAsState(ctl.ambient ?: p.page, tween(700), label = "ambient")

    /*
     * Непрерывный щипок. Под пальцами живёт «виртуальное» число столбцов [vcols] — оно меняется плавно.
     * Сетка раскладывается на ближайшую ступень, а рисуется с масштабом «ступень / виртуальное»: когда раскладка
     * меняется, меняется и масштаб — в тот же кадр, и глаз прыжка не видит. Кадр под пальцами остаётся под ними.
     */
    val vcols = remember { Animatable(feed.cols.toFloat()) }
    var pivot by remember { mutableStateOf(Offset.Zero) }
    // сдвиг по горизонтали при смене ступени: ставится в том же кадре, что и раскладка, потом мягко уходит в 0
    val panX = remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
    var panJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    /** Держать кадр под пальцами: докрутка и сдвиг — синхронно с новой раскладкой (без кадра «не того места»). */
    fun applyAnchor(a: Triple<Long, Float, Float>, cells: List<Cell>, cols: Int) {
        val (key, fx, fy) = a
        val idx = cells.indexOfFirst { it.key == key }
        val lo = ctl.grid.layoutInfo
        if (idx < 0 || lo.viewportSize.width <= 0) return
        val w = lo.viewportSize.width.toFloat(); val h = lo.viewportSize.height.toFloat()
        val gap = with(density) { gapFor(cols).toPx() }
        val tile = (w - gap * (cols - 1)) / cols
        // поле сверху — как у новой раскладки: под линзой полэкрана, иначе под капсулу фильтра
        val padTopPx = with(density) { ((top + 56.dp) * capsule.coerceIn(0f, 1f)).toPx() }
        val before = if (Lens.strengthFor(cols) > 0f) maxOf(padTopPx, (h - tile) / 2f) else padTopPx
        // позиция — «кадр ряда выше и смещение от начала содержимого»: она не зависит от полей, поэтому неважно,
        // в каком порядке применятся новая раскладка и новые поля (линза включилась/выключилась)
        val rowH = tile + gap
        val want = pivot.y - before - fy * tile          // где верх ряда кадра — от начала содержимого (после поля)
        val rowStart = idx - idx % cols
        val above = if (want > 0f) kotlin.math.ceil(want / rowH).toInt() else 0
        val ref = (rowStart - above * cols).coerceAtLeast(0)
        val off = ((rowStart - ref) / cols * rowH - want).coerceAtLeast(0f)
        ctl.grid.requestScrollToItem(ref, off.toInt())
        // по горизонтали кадр встаёт в свой столбец новой сетки (как у Apple): сдвигать всю сетку вбок нельзя —
        // по краю открывалась пустая полоса
    }
    // кадр под пальцами и где на нём пальцы (доли ширины и высоты) — его держим на месте при смене раскладки
    var anchor by remember { mutableStateOf<Triple<Long, Float, Float>?>(null) }
    // ступень сменилась не щипком — виртуальные столбцы встают на неё же (масштаб 1)
    LaunchedEffect(feed.cols) { if (!ctl.pinching && !vcols.isRunning) vcols.snapTo(feed.cols.toFloat()) }
    // линза уступает, когда делаешь: выбор и отдаление выпрямляют её в обычную сетку
    ctl.fastUnit = with(density) { 48.dp.toPx() }
    val lensTarget = if (s.selection.isNotEmpty()) 0f else Lens.strengthFor(feed.cols)
    LaunchedEffect(lensTarget) {
        if (ctl.pinching) return@LaunchedEffect   // во время щипка линзу ведут пальцы
        androidx.compose.animation.core.animate(ctl.lens, lensTarget, animationSpec = spring(1f, 260f)) { v, _ -> ctl.lens = v }
    }
    // фото под строкой состояния? тогда значки светлые и под ними тень; лента короче экрана — как обычно
    val topPx = with(density) { top.toPx() }
    val overPhotos by remember {
        androidx.compose.runtime.derivedStateOf {
            val first = ctl.grid.layoutInfo.visibleItemsInfo.firstOrNull()
            first != null && first.offset.y - ctl.grid.layoutInfo.viewportStartOffset < topPx * 0.5f && s.filter == null
        }
    }
    val lightTheme = !p.dark
    androidx.compose.runtime.DisposableEffect(overPhotos, lightTheme, s.viewing == null) {
        val w = (view.context as? android.app.Activity)?.window
        if (w != null && s.viewing == null) androidx.core.view.WindowCompat.getInsetsController(w, view)
            .isAppearanceLightStatusBars = lightTheme && !overPhotos
        onDispose { }
    }
    // остановилась — плитки пружинят обратно, с небольшим перелётом (желе), и считается цвет экрана
    LaunchedEffect(Unit) {
        snapshotFlow { ctl.grid.isScrollInProgress }.collect { moving ->
            if (!moving) {
                ctl.ambient = ambientOf(s, ctl)
                androidx.compose.animation.core.animate(ctl.velocity, 0f, animationSpec = spring(0.32f, 240f)) { v, _ -> ctl.velocity = v }
            }
        }
    }
    LaunchedEffect(feed.gen) { ctl.ambient = ambientOf(s, ctl) }
    // «Поток» вытягивают: лента кончилась, а палец тянет дальше — он выезжает снизу (px, с сопротивлением вдвое)
    val flowPull = remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
    val flowClick = remember { PullClick() }
    val flowAt = with(density) { FlowPullAt.dp.toPx() }
    val physics = remember {
        object : androidx.compose.ui.input.nestedscroll.NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: androidx.compose.ui.input.nestedscroll.NestedScrollSource): Offset {
                // потянул и повёл обратно — сначала «Поток» уезжает назад, потом едет лента
                val d = flowPull.floatValue
                if (d > 0f && available.y > 0f) {
                    val v = (d - available.y * 0.5f).coerceAtLeast(0f)
                    flowPull.floatValue = v; flowClick.update(view, v >= flowAt)
                    return Offset(0f, (d - v) * 2f)
                }
                return Offset.Zero
            }
            override fun onPostScroll(consumed: Offset, available: Offset, source: androidx.compose.ui.input.nestedscroll.NestedScrollSource): Offset {
                ctl.push(consumed.y)
                if (s.filter == null && s.flowPeek.isNotEmpty() && source == androidx.compose.ui.input.nestedscroll.NestedScrollSource.UserInput &&
                    available.y < 0f && !ctl.zooming && s.selection.isEmpty()) {
                    val v = flowPull.floatValue - available.y * 0.5f
                    flowPull.floatValue = v; flowClick.update(view, v >= flowAt)
                    return Offset(0f, available.y)
                }
                return Offset.Zero
            }
            override suspend fun onPreFling(available: androidx.compose.ui.unit.Velocity): androidx.compose.ui.unit.Velocity {
                val d = flowPull.floatValue
                if (d <= 0f) return androidx.compose.ui.unit.Velocity.Zero
                flowClick.ready = false
                if (d >= flowAt) {
                    // вытянул — «Поток» доезжает вверх и открывается целиком
                    androidx.compose.animation.core.animate(d, d + flowAt * 1.2f, animationSpec = spring(1f, 900f)) { v, _ -> flowPull.floatValue = v }
                    s.open(Filter.Flow)
                    flowPull.floatValue = 0f
                } else androidx.compose.animation.core.animate(d, 0f, animationSpec = spring(0.8f, 500f)) { v, _ -> flowPull.floatValue = v.coerceAtLeast(0f) }
                return available
            }
        }
    }
    // лента открывается в конце — на самом свежем; если были в конце, новый кадр не уводит вверх
    LaunchedEffect(Unit) { snapshotFlow { ctl.grid.canScrollForward }.collect { ctl.atEnd = !it } }
    var prevCols by remember { mutableStateOf(feed.cols) }
    var area by remember { mutableStateOf(Size.Zero) }
    LaunchedEffect(feed.gen) {
        val fresh = ctl.shownFilter != s.filter || !ctl.placed
        val zoomed = feed.cols != prevCols; prevCols = feed.cols
        // смена ступени — не повод уезжать в конец ленты: кадр под пальцами держит applyAnchor
        if (anchor == null && !zoomed && !ctl.pinching && feed.cells.isNotEmpty() && (fresh || ctl.atEnd)) {
            // лента — на самом свежем внизу; «Поток» — на самом свежем сверху
            // «Поток» — с первой строки прямо под капсулой (поле сверху в полэкрана — для линзы, не для открытия)
            if (s.filter == Filter.Flow) ctl.grid.requestScrollToItem(0, ctl.topShift) else ctl.grid.requestScrollToItem(feed.feedEnd.coerceAtLeast(0))
            ctl.placed = true; ctl.shownFilter = s.filter
        }
    }

    // выбор протягиванием: якорь, снимок выбора на старте, режим (выделять или снимать)
    var dragAnchor by remember { mutableStateOf(-1) }

    Box(
        Modifier.fillMaxSize().background(p.page).testTag("feed")
            .onGloballyPositioned { ctl.origin = it.positionInWindow() }
            .onSizeChanged { area = Size(it.width.toFloat(), it.height.toFloat()) }
            .pointerInput(Unit) {
                val slop = viewConfiguration.touchSlop
                val longPress = viewConfiguration.longPressTimeoutMillis.coerceAtMost(380L)
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    var moved = false
                    var pinching = false
    /**
     * Идёт масштабирование (щипок и доводка после него): перестановка плиток не анимируется — плитки встают
     * на новые места сразу, ровно под пальцами. Иначе каждая ступень запускала полёт всех плиток врассыпную.
     */
    var zooming by mutableStateOf(false)
                    var startSpan = 0f
                    var startV = 0f
                    // кадр под пальцами выбирается один раз — в начале щипка — и держится до конца жеста
                    // (если выбирать заново на каждой ступени, выбор уползает и лента дрейфует)
                    var held: Triple<Long, Float, Float>? = null
                    fun pick() {
                        val lo = ctl.grid.layoutInfo
                        val info = lo.visibleItemsInfo.firstOrNull {
                            val t = it.offset.y - lo.viewportStartOffset
                            pivot.x >= it.offset.x && pivot.x < it.offset.x + it.size.width && pivot.y >= t && pivot.y < t + it.size.height
                        } ?: lo.visibleItemsInfo.minByOrNull { kotlin.math.abs(it.offset.y - lo.viewportStartOffset + it.size.height / 2f - pivot.y) }
                        val cell = info?.let { ctl.cells.getOrNull(it.index) } as? Cell.Tile ?: return
                        val t = info.offset.y - lo.viewportStartOffset
                        held = Triple(cell.item.id, ((pivot.x - info.offset.x) / info.size.width).coerceIn(0f, 1f), ((pivot.y - t) / info.size.height).coerceIn(0f, 1f))
                    }
                    fun step(target: Int) {
                        anchor = held
                        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        s.zoomTo(target)
                        // раскладка сменилась сразу — держим кадр под пальцами в этом же кадре
                        if (s.feed.cols == target) anchor?.let { applyAnchor(it, s.feed.cells, target); anchor = null }
                    }
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
                        val ids = (lo..hi).flatMap { (tiles.getOrNull(it) as? Cell.Tile)?.let(::idsOf) ?: emptyList() }
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
                            // отпустил — сетка доезжает до ближайшей ступени, линза — до её силы
                            if (pinching) {
                                val target = Zoom.nearest(vcols.value)
                                if (target != s.columns) step(target)
                                ctl.pinching = false
                                scope.launch {
                                    vcols.animateTo(target.toFloat(), spring(0.85f, 320f))
                                    // раскладка доехала — перестановка снова анимируется (стопки, удаление)
                                    kotlinx.coroutines.delay(120); if (!ctl.pinching) ctl.zooming = false
                                }
                                scope.launch {
                                    androidx.compose.animation.core.animate(ctl.lens, if (s.selection.isNotEmpty()) 0f else Lens.strengthFor(target), animationSpec = spring(1f, 260f)) { v, _ -> ctl.lens = v }
                                }
                            }
                            if (!moved && !pinching && ev.changes.none { it.isConsumed }) {
                                ctl.hit(down.position)?.let { tap(s, ctl, it.index) }
                            }
                            return true
                        }
                        if (pressed.size >= 2 && !selecting) {
                            val a = pressed[0].position; val b = pressed[1].position
                            val span = (a - b).getDistance()
                            if (!pinching) {
                                pinching = true; ctl.pinching = true; ctl.zooming = true
                                startSpan = span; pivot = (a + b) / 2f; startV = vcols.value
                                pick()
                            }
                            ev.changes.forEach { it.consume() }
                            // пальцы шире — столбцов меньше; за пределами 2 и 13 — с сопротивлением
                            val v = Zoom.rubber(startV * startSpan.coerceAtLeast(1f) / span.coerceAtLeast(1f))
                            scope.launch { vcols.snapTo(v) }
                            ctl.lens = if (s.selection.isNotEmpty()) 0f else Zoom.lens(v)
                            val target = Zoom.next(s.columns, v)
                            if (target != s.columns) step(target)
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
                        // палец поехал — это прокрутка сетки (её ведёт сама сетка), но жест слушаем дальше:
                        // второй палец может коснуться позже — тогда это щипок
                        return false
                    }
                    // долгое нажатие — по таймеру: неподвижный палец событий не шлёт
                    val early = withTimeoutOrNull(longPress) {
                        while (true) {
                            val ev = awaitPointerEvent(PointerEventPass.Initial)
                            if (step(ev)) return@withTimeoutOrNull true
                            if (pinching || moved) return@withTimeoutOrNull false
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
        // фото идут под строку состояния; место сверху — только под капсулу фильтра
        // пружина капсулы проскакивает ноль — отступ отрицательным не бывает
        val padTop = (top + 56.dp) * capsule.coerceIn(0f, 1f)
        // под линзой лента ходит «по магнитам»: после прокрутки ближайший ряд встаёт ровно в центр экрана.
        // Поля сверху и снизу — по полэкрана: и самый старый, и самый свежий ряд тоже доезжают до центра линзы
        val lensOn = Lens.strengthFor(feed.cols) > 0f
        val center = remember(feed.cols, lensOn) { CenterSnap }
        val snap = androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior(
            remember(ctl.grid, center) { androidx.compose.foundation.gestures.snapping.SnapLayoutInfoProvider(ctl.grid, center) }
        )
        val fling = if (lensOn) snap else androidx.compose.foundation.gestures.ScrollableDefaults.flingBehavior()
        // поле линзы — от размера экрана, в основной композиции: меняется в том же кадре, что и число столбцов
        // (внутри BoxWithConstraints оно считалось бы на этапе раскладки — позже докрутки к кадру под пальцами)
        ctl.tilePx = if (area.width > 0) ((area.width - with(density) { gapFor(feed.cols).toPx() } * (feed.cols - 1)) / feed.cols).toInt() else 0
        val half = if (lensOn && area.height > 0) with(density) { ((area.height - area.width / feed.cols) / 2f).coerceAtLeast(0f).toDp() } else 0.dp
        // под капсулу — во всю её высоту (в миг открытия она ещё выезжает, и её текущий размер — ноль)
        ctl.topShift = with(density) { (half - (top + 56.dp)).toPx().coerceAtLeast(0f).toInt() }
        LazyVerticalGrid(
            columns = GridCells.Fixed(feed.cols),
            state = ctl.grid,
            modifier = Modifier.fillMaxSize().nestedScroll(physics).graphicsLayer {
                val k = (feed.cols / vcols.value).coerceIn(0.35f, 3f)
                scaleX = k; scaleY = k
                translationX = panX.floatValue
                translationY = -flowPull.floatValue
                transformOrigin = TransformOrigin(
                    (pivot.x / size.width.coerceAtLeast(1f)).coerceIn(0f, 1f),
                    (pivot.y / size.height.coerceAtLeast(1f)).coerceIn(0f, 1f),
                )
            },
            contentPadding = PaddingValues(top = maxOf(padTop, half), bottom = maxOf(bottom, half)),
            flingBehavior = fling,
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(gapFor(feed.cols)),
            // кадров меньше, чем экран, — они лежат внизу, у знака, а не висят сверху
            // «Поток» — сверху вниз (самое свежее сверху); его кадров меньше экрана — лежат сверху
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(gapFor(feed.cols), if (s.filter == Filter.Flow) Alignment.Top else Alignment.Bottom),
        ) {
            if (s.filter == Filter.Flow) {
                val old = s.oldScreens()
                if (old.isNotEmpty()) item(key = "suggest", span = { GridItemSpan(maxLineSpan) }) {
                    FlowSuggestion(old.size) { s.trash(old) }
                }
                // срок до «Потока» — наверху, рядом с самым свежим
                item(key = "flow-timer", span = { GridItemSpan(maxLineSpan) }) { FlowTimer(s) }
            }
            val n = feed.cells.size
            items(
                count = n,
                key = { feed.cells[it].key },
                contentType = { 1 },
            ) { i ->
                when (val c = feed.cells[i]) {
                    is Cell.Tile -> Tile(
                        s, ctl, c, i, feed.cols,
                        // проявление плиток не нужно нигде (у стопок свой веер, у удаления — пыль): лента его принимала
                        // за «новые плитки» на каждой смене ступени и гасила весь экран в белое
                        Modifier.animateItem(
                            fadeInSpec = null,
                            placementSpec = if (ctl.zooming) null else spring(0.86f, 420f, visibilityThreshold = IntOffset(1, 1)),
                            fadeOutSpec = null,
                        ),
                    )
                }
            }
        }
        if (s.loaded && feed.cells.isEmpty() && s.filter != Filter.Flow) Empty(s.filter)
        // «Поток» выглядывает под самым свежим рядом — бледный и размытый; вытягиваешь — проявляется
        if (s.filter == null && s.flowPeek.isNotEmpty() && !ctl.zooming) FlowPeek(
            s, ctl,
            bottomOfFeed = {
                val lo = ctl.grid.layoutInfo
                lo.visibleItemsInfo.lastOrNull { it.index == lo.totalItemsCount - 1 }
                    ?.let { (it.offset.y + it.size.height - lo.viewportStartOffset).toFloat() }
            },
            pull = { flowPull.floatValue },
        )
        // строка состояния над фото: лёгкая тень и светлые значки — без белого тумана поверх кадров
        val scrim by animateFloatAsState(if (overPhotos) 1f else 0f, tween(220), label = "scrim")
        if (scrim > 0.001f) Box(
            Modifier.fillMaxWidth().height(top + 20.dp).graphicsLayer { alpha = scrim }
                .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.34f), Color.Black.copy(alpha = 0f))))
        )
        FilterCapsule(s, top, capsule)
        FastScroll(s, ctl, top, bottom)
    }
}

private fun tap(s: Still, ctl: FeedController, index: Int) {
    val c = ctl.cells.getOrNull(index) as? Cell.Tile ?: return
    when {
        s.selection.isNotEmpty() -> s.selection = if (c.item.id in s.selection) s.selection - idsOf(c).toSet() else s.selection + idsOf(c)
        // стопка открывается как папка: кадры вылетают из неё поверх размытой ленты
        c.entry.stacked -> { ctl.stackFrom = ctl.bounds(c.item.id); s.openStack(c.entry) }
        else -> s.view(c.item)
    }
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
    val gone by animateFloatAsState(if (leaving) 1f else 0f, if (leaving) tween(820, easing = androidx.compose.animation.core.LinearEasing) else tween(0), label = "gone")

    val stacked = c.entry.stacked
    // открытая стопка: её плитка в ленте пустеет — кадры как будто вынули (как фото в просмотре)
    val opened = s.stack?.let { st -> st.members.any { it.id == item.id } } == true
    val pile = remember { PileCache() }

    val spot = remember { Spot() }
    val pose = remember { FloatArray(4) }
    val lensRadius = with(density) { 14.dp.toPx() }
    val lift = with(density) { LiftRadius.toPx() }
    // обычный Box (не BoxWithConstraints: подкомпозиция в каждой плитке — главный тормоз ленивых сеток);
    // размер плитки знает лента
    Box(
        modifier.zIndex(if (leaving) 2f else if (c.label != null) 1f else 0f).aspectRatio(1f).testTag("tile-${item.id}")
            .onPlaced { val o = it.positionInParent(); spot.x = o.x; spot.y = o.y; spot.w = it.size.width.toFloat(); spot.h = it.size.height.toFloat() }
            .graphicsLayer {
                // линза: место и размер кадра — от его положения на экране (читается при отрисовке, без рекомпозиций)
                val vp = ctl.grid.layoutInfo.viewportSize
                Lens.pose(spot.x + spot.w / 2f, spot.y + spot.h / 2f, vp.width.toFloat(), vp.height.toFloat(), ctl.lensNow(), pose)
                translationX = pose[Lens.TX]
                translationY = pose[Lens.TY]
                val k = pose[Lens.SCALE] * lerp(1f, 0.88f, pick)
                scaleX = k.coerceAtLeast(0f); scaleY = k.coerceAtLeast(0f)
                // открытая стопка: её кадры сейчас на столе (в сетке или в полёте) — в ленте её нет, пока не сложится
                val base = if (hidden || (opened && ctl.pileTaken)) 0f else 1f
                // дымка линзы: к краям кадр уходит в фон. Она — прозрачность слоя, а не заливка внутри плитки:
                // слой пересчитывается на каждом кадре прокрутки, а нарисованное внутри видеокарта не перерисовывает —
                // заливка застревала, и кадр, долистанный до центра, оставался мутным. На светлой теме — слабее.
                val haze = if (leaving) 0f else pose[Lens.SHADE] * (if (p.dark) 1f else 0.55f)
                alpha = base * (1f - haze).coerceIn(0f, 1f)
                // появление у краёв: кадр, въезжающий сверху или снизу, сначала бледный и размытый и проявляется,
                // пройдя пятую часть экрана
                val cy = spot.y + spot.h / 2f; val vh = vp.height.toFloat()
                val edge = edgeClarity(cy, vh)
                val clear = edge
                if (clear < 0.999f && !leaving) {
                    alpha *= 0.3f + 0.7f * clear
                    renderEffect = ctl.flowBlur((1f - clear) * 6.dp.toPx())
                } else renderEffect = null
                // скругление — обрезкой слоя видеокартой (без контуров на каждом кадре); радиус не зависит
                // от скорости прокрутки, только от силы линзы и выбора — меняется редко, форма берётся из кэша
                // (масштаб слоя уменьшает и радиус — делим на него, чтобы на экране он был ровно таким)
                val r = (radius.toPx() + lift * pick + lensRadius * ctl.lens) / k.coerceAtLeast(0.3f)
                shape = ctl.shape(r)
                // стопка рисует свои карточки сама: повёрнутые края не должны срезаться квадратом ячейки
                clip = !leaving && !stacked
            }
    ) {
        val px = ctl.tilePx.takeIf { it > 0 } ?: 256
        val bmp = rememberThumb(s, item, px)
        val shown by animateFloatAsState(if (bmp.value != null) 1f else 0f, if (bmp.fresh) tween(140) else tween(0), label = "load")
        val dust = remember(leaving, bmp.value) { if (leaving) bmp.value?.let(::dustOf) else null }
        // под обложкой — два других кадра стопки, маленькими миниатюрами (их почти не видно, крупнее не нужно)
        val backs = if (stacked) Pile.backs(c.entry) else null to null
        val look = remember(c.entry.key, c.entry.members.size) { Pile.look(c.entry.key, c.entry.members.size) }
        val back1 = backs.first?.let { rememberThumb(s, it, 160) }
        val back2 = backs.second?.let { rememberThumb(s, it, 160) }
        if (stacked && !leaving) Spacer(
            Modifier.fillMaxSize().drawBehind {
                drawPile(pile, bmp.value?.takeIf { shown > 0f }, back1?.value, back2?.value, placeholder(s, item, p), p, cols, 0f, look)
            }
        ) else Spacer(
            Modifier.fillMaxSize().drawBehind {
                val area = Rect(0f, 0f, size.width, size.height)
                val b = bmp.value
                if (gone > 0f && b != null) {
                    // удалённый кадр рассыпается на пиксели, и они стекают в знак
                    drawDust(b, dust, area, gone, dissolveTarget(ctl, spot))
                    return@drawBehind
                }
                // миниатюры ещё нет — плитка залита основным цветом этого кадра (посчитан для поиска и стопок)
                if (shown < 1f) drawRect(placeholder(s, item, p))
                if (b != null && shown > 0f) drawCover(b, area, shown)
            }
        )
        if (item.video && cols <= 5) VideoBadge(item.durationMs)
        if (c.label != null) PeriodLabel(c.label, levelOf(cols))
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
    // скрытый кадр — только очень размытая копия (крошечная, растянутая со сглаживанием)
    if (item.id in s.veiled) {
        val st = produceState(Thumb(s.thumbs.peekVeil(item.id)?.asImageBitmap(), false), item.id, true) {
            if (value.value == null) s.thumbs.loadVeil(item)?.let { value = Thumb(it.asImageBitmap(), true) }
        }
        return st.value
    }
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
internal fun androidx.compose.foundation.layout.BoxScope.VideoBadge(ms: Long) {
    val sec = (ms / 1000).toInt()
    Text(
        "%d:%02d".format(sec / 60, sec % 60), color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, fontFamily = UiFont,
        style = androidx.compose.ui.text.TextStyle(shadow = androidx.compose.ui.graphics.Shadow(Color(0x80000000), Offset(0f, 1f), 4f)),
        modifier = Modifier.align(Alignment.BottomEnd).padding(end = 6.dp, bottom = 4.dp),
    )
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



/** Цвет экрана: средний «памятный» цвет кадров на виду (уже посчитан для поиска — здесь только сложение). */
private fun ambientOf(s: Still, ctl: FeedController): Color? {
    var r = 0f; var g = 0f; var b = 0f; var n = 0
    for (info in ctl.grid.layoutInfo.visibleItemsInfo) {
        val id = info.key as? Long ?: continue
        val c = s.index.color(id) ?: continue
        r += android.graphics.Color.red(c); g += android.graphics.Color.green(c); b += android.graphics.Color.blue(c); n++
    }
    if (n == 0) return null
    return Color(r / n / 255f, g / n / 255f, b / n / 255f)
}

/** Фон, чуть окрашенный светом кадров: на светлой теме — едва, на тёмной — заметнее (как отсвет). */
fun tint(page: Color, light: Color?, amount: Float = 0.22f): Color =
    if (light == null) page else androidx.compose.ui.graphics.lerp(page, light, if (page.luminance() < 0.5f) amount * 1.4f else amount)

private fun Color.luminance() = 0.2126f * red + 0.7152f * green + 0.0722f * blue

/** Куда стекают пиксели: к знаку внизу по центру, в координатах плитки. */
private fun DrawScope.dissolveTarget(ctl: FeedController, spot: Spot): Offset {
    val vp = ctl.grid.layoutInfo.viewportSize
    val mark = Offset(vp.width / 2f, vp.height - 40.dp.toPx())
    return mark - Offset(spot.x + spot.w / 2f, spot.y + spot.h / 2f)
}

/** Цвета пыли: миниатюра, сжатая до [n]×[n], — по частице на клетку. Считается один раз, когда кадр начал уходить. */
fun dustOf(b: ImageBitmap, n: Int = DustGrid): IntArray? = runCatching {
    val src = b.asAndroidBitmap().let { if (it.config == Bitmap.Config.HARDWARE) it.copy(Bitmap.Config.ARGB_8888, false) else it }
    val small = Bitmap.createScaledBitmap(src, n, n, true)
    IntArray(n * n).also { small.getPixels(it, 0, n, 0, 0, n, n) }
}.getOrNull()

const val DustGrid = 20

/**
 * Распад кадра в пыль: в первые мгновения кадр тает, а на его месте остаются частицы его же цветов
 * (по одной на клетку 20×20). Каждая со своей задержкой — сначала нижние — сдувается вбок и вверх и по дуге
 * с ускорением втягивается в знак, уменьшаясь до точки. Аллокаций в кадре нет: цвета посчитаны заранее.
 */
private fun DrawScope.drawDust(b: ImageBitmap, dust: IntArray?, area: Rect, t: Float, target: Offset) {
    // сам кадр тает за первую четверть
    val img = (1f - t * 4f).coerceIn(0f, 1f)
    if (img > 0f) drawCover(b, area, img)
    val cols = dust ?: return
    val n = DustGrid
    val cw = area.width / n; val ch = area.height / n
    val end = Offset(target.x + area.width / 2f, target.y + area.height / 2f)
    val lift = 46.dp.toPx(); val side = 38.dp.toPx(); val tiny = 1.2.dp.toPx()
    for (j in 0 until n) for (i in 0 until n) {
        val h = ((i * 73 + j * 151) % 97) / 97f          // своё «случайное» для частицы, без Random
        val d = ((n - 1 - j).toFloat() / n) * 0.28f + h * 0.12f
        val p = ((t - d) / 0.6f).coerceIn(0f, 1f)
        if (p >= 1f) continue
        val x0 = area.left + (i + 0.5f) * cw; val y0 = area.top + (j + 0.5f) * ch
        // дуга: сначала вбок и вверх (дуновение), потом — в знак
        val c1x = x0 + (h - 0.5f) * 2f * side; val c1y = y0 - lift * (0.5f + h)
        val e = p * p * (1.6f - 0.6f * p)
        val u = 1f - e
        val x = u * u * x0 + 2f * u * e * c1x + e * e * end.x
        val y = u * u * y0 + 2f * u * e * c1y + e * e * end.y
        val appear = (t * 7f).coerceIn(0f, 1f)
        val r = lerp(minOf(cw, ch) * 0.62f, tiny, (p * 1.8f).coerceIn(0f, 1f))
        val a = appear * (1f - p * p * p)
        if (a <= 0.01f) continue
        drawCircle(Color(cols[j * n + i]), r, Offset(x, y), alpha = a)
    }
}


/**
 * Подпись месяца или года лежит на первом кадре периода, поверх фото (как в Фото Apple), — сетка остаётся
 * сплошной. Подпись шире кадра — свободно заходит на соседние.
 */
@Composable
private fun androidx.compose.foundation.layout.BoxScope.PeriodLabel(label: String, level: Level) {
    Text(
        label, color = Color.White, maxLines = 1, softWrap = false,
        fontSize = if (level == Level.YEARS) 22.sp else 15.sp, fontWeight = FontWeight.Bold, fontFamily = UiFont,
        style = androidx.compose.ui.text.TextStyle(shadow = androidx.compose.ui.graphics.Shadow(Color(0x99000000), Offset(0f, 1f), 8f)),
        modifier = Modifier.align(Alignment.TopStart).wrapContentWidth(Alignment.Start, unbounded = true)
            .padding(start = 6.dp, top = 4.dp),
    )
}


/**
 * Магнит линзы: центр ряда — в центр всего экрана (а не области между полями), там, где линза
 * рисует кадр в полный размер.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
private object CenterSnap : androidx.compose.foundation.gestures.snapping.SnapPosition {
    override fun position(layoutSize: Int, itemSize: Int, beforeContentPadding: Int, afterContentPadding: Int, itemIndex: Int, itemCount: Int): Int =
        layoutSize / 2 - itemSize / 2 - beforeContentPadding
}


/** Ступени щипка и переходы между ними — в логарифмическом масштабе (как воспринимается размер). */
object Zoom {
    private val ln = ColumnSteps.map { kotlin.math.ln(it.toFloat()) }

    fun nearest(v: Float): Int {
        val l = kotlin.math.ln(v.coerceAtLeast(0.5f))
        return ColumnSteps[ln.indices.minBy { kotlin.math.abs(ln[it] - l) }]
    }

    /**
     * Ступень для раскладки во время щипка: всегда та, что мельче или равна пальцам (столбцов ≥ виртуальных), —
     * тогда сетка рисуется крупнее своей раскладки и края экрана заполнены кадрами (ленивая сетка не рисует
     * то, что за пределами экрана, и при уменьшении по краям были бы пустые поля). У самой ступени — небольшой
     * запас, чтобы раскладка не дребезжала, когда пальцы стоят на границе.
     */
    fun next(current: Int, v: Float): Int {
        fun ceilStep(x: Float) = ColumnSteps.firstOrNull { it >= x } ?: ColumnSteps.last()
        val low = ceilStep(v * 0.95f); val high = ceilStep(v * 0.99f)
        return if (current in low..high) current else high
    }

    /** За крайними ступенями — с сопротивлением. */
    fun rubber(v: Float): Float {
        val lo = ColumnSteps.first().toFloat(); val hi = ColumnSteps.last().toFloat()
        // за крайними ступенями — с сопротивлением; сильно меньше 13 столбцов уменьшать нельзя (пустые края)
        return when { v < lo -> lo - (lo - v) * 0.3f; v > hi -> hi + ((v - hi) * 0.15f).coerceAtMost(hi * 0.04f); else -> v }
    }

    /** Сила линзы плавно по виртуальным столбцам: полная до 3, половина на 5, нет с 8. */
    fun lens(v: Float): Float = when {
        v <= 3f -> 1f
        v <= 5f -> 1f - 0.5f * (v - 3f) / 2f
        v <= 8f -> 0.5f * (1f - (v - 5f) / 3f)
        else -> 0f
    }
}


/** Цвет пустой плитки: приглушённый основной цвет кадра, если он уже известен, иначе — цвет пустой плитки темы. */
internal fun placeholder(s: Still, item: MediaItem, p: Palette): Color {
    val c = s.index.color(item.id) ?: return p.tile
    return androidx.compose.ui.graphics.lerp(Color(c), p.tile, 0.35f)
}


/**
 * Что выбирает плитка: стопка — все свои кадры (иначе «удалить стопку» отправляло в корзину одну обложку,
 * наверх вставал следующий кадр, и казалось, что удаление не работает), одиночный кадр — себя.
 */
internal fun idsOf(c: Cell.Tile): List<Long> = if (c.entry.stacked) c.entry.members.map { it.id } else listOf(c.item.id)


/** Чёткость кадра у краёв экрана: у самого края — 0, пройдя пятую часть высоты (сверху или снизу) — 1. */
fun edgeClarity(centerY: Float, viewport: Float): Float {
    if (viewport <= 0f) return 1f
    val d = minOf(centerY, viewport - centerY)
    return (d / (viewport * 0.2f)).coerceIn(0f, 1f)
}
