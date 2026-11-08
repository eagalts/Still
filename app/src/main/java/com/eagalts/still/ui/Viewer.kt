package com.eagalts.still.ui

import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.foundation.layout.offset

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.eagalts.still.Dates
import com.eagalts.still.Still
import com.eagalts.still.media.MediaItem
import com.eagalts.still.t
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Прямоугольник кадра с пропорциями [w]×[h], вписанный в [box] по центру. */
fun fit(w: Int, h: Int, box: Size): Rect {
    if (w <= 0 || h <= 0) return Rect(Offset.Zero, box)
    val k = min(box.width / w, box.height / h)
    val fw = w * k; val fh = h * k
    return Rect((box.width - fw) / 2f, (box.height - fh) / 2f, (box.width + fw) / 2f, (box.height + fh) / 2f)
}

/**
 * Где кадр стоит в просмотре. Подписи видны ([k] → 1) — кадр, который залез бы под них (скриншот во весь экран,
 * высокое фото), встаёт между верхними подписями и кнопками; подписи спрятаны — во весь экран. Кадр, который и так
 * помещается между ними, не двигается.
 */
fun shownRect(w: Int, h: Int, box: Size, top: Float, bottom: Float, k: Float): Rect {
    val full = fit(w, h, box)
    if (k <= 0f || (full.top >= top && full.bottom <= box.height - bottom)) return full
    val inner = fit(w, h, Size(box.width, (box.height - top - bottom).coerceAtLeast(1f))).translate(0f, top)
    return lerpRect(full, inner, k.coerceIn(0f, 1f))
}

/** Для тестов: где нарисован кадр текущей страницы просмотра. */
internal object ViewerProbe { var rect: Rect? = null }

private fun lerpRect(a: Rect, b: Rect, f: Float) =
    Rect(lerp(a.left, b.left, f), lerp(a.top, b.top, f), lerp(a.right, b.right, f), lerp(a.bottom, b.bottom, f))

/**
 * Просмотр поверх ленты. Фото вылетает из своей плитки (обрезка квадратом плавно раскрывается в целый кадр,
 * скругление уходит в ноль), смахивание вниз — кадр тянется за пальцем, уменьшается, фон светлеет, и на отпускании
 * кадр летит обратно в свою плитку. Влево-вправо — соседние, двойной тап и щипок — зум.
 */
@Composable
fun Viewer(s: Still, ctl: FeedController) {
    val v = s.viewing ?: return
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val view = LocalView.current
    val pager = rememberPagerState(v.start) { v.items.size }
    val item = v.items.getOrNull(pager.currentPage) ?: return

    // полёт: 0 — кадр в плитке, 1 — на весь экран
    val flight = remember(v) { Animatable(0f) }
    var closing by remember(v) { mutableStateOf(false) }
    var from by remember(v) { mutableStateOf<Rect?>(null) }
    // смахивание вниз: сдвиг и доля «отпускания»
    val drag = remember(v) { Animatable(Offset.Zero, Offset.VectorConverter) }
    var dismissing by remember(v) { mutableStateOf(false) }
    var chrome by remember(v) { mutableStateOf(true) }
    var zoomed by remember(v) { mutableStateOf(false) }
    // высота подписей сверху и кнопок снизу — меряются, как только подписи собраны (кадр встаёт между ними)
    var chromeTop by remember { mutableStateOf(0f) }
    var chromeBottom by remember { mutableStateOf(0f) }
    // подробности: свайп вверх — кадр уезжает вверх, снизу выезжает панель (0 — закрыта, 1 — открыта)
    val info = remember(v) { Animatable(0f) }

    // один плеер на весь просмотр: создаётся при открытии, освобождается при закрытии
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val pb = remember(v) { Playback(ctx) }
    DisposableEffect(v) { onDispose { pb.release() } }
    val settled = v.items.getOrNull(pager.settledPage)

    LaunchedEffect(item.id) { flyingIdState.longValue = item.id }
    DisposableEffect(v) { onDispose { flyingIdState.longValue = -1L } }
    LaunchedEffect(v) {
        from = ctl.bounds(v.items[v.start].id)
        flight.animateTo(1f, spring(0.9f, 420f, visibilityThreshold = FineThreshold))
    }

    // строка состояния и навигация: тап по фото прячет их вместе с подписями — остаётся только кадр
    DisposableEffect(chrome) {
        val w = (view.context as? android.app.Activity)?.window
        val c = w?.let { WindowCompat.getInsetsController(it, view) }
        c?.isAppearanceLightStatusBars = false
        if (chrome) c?.show(WindowInsetsCompat.Type.systemBars()) else {
            c?.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            c?.hide(WindowInsetsCompat.Type.systemBars())
        }
        onDispose { }
    }
    DisposableEffect(Unit) {
        onDispose {
            val w = (view.context as? android.app.Activity)?.window ?: return@onDispose
            val c = WindowCompat.getInsetsController(w, view)
            c.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    fun close() {
        if (closing) return
        closing = true
        scope.launch {
            // лента под просмотром докручивается к кадру, и он летит в свою плитку; плитки не видно — тает на месте
            ctl.reveal(item.id)
            androidx.compose.runtime.withFrameNanos { }
            from = ctl.bounds(item.id)
            launch { drag.animateTo(Offset.Zero, spring(0.9f, 380f)) }
            flight.animateTo(0f, spring(0.9f, 380f, visibilityThreshold = FineThreshold))
            s.viewing = null
            if (v.external) s.onExternalClosed()
        }
    }
    BackHandler { if (info.value > 0.01f) scope.launch { info.animateTo(0f, spring(0.86f, 420f)) } else close() }

    BoxWithConstraints(Modifier.fillMaxSize().testTag("viewer")) {
        val box = with(density) { Size(maxWidth.toPx(), maxHeight.toPx()) }
        val h = box.height
        val panelH = with(density) { 330.dp.toPx() + WindowInsets.navigationBars.getBottom(this).toFloat() }.coerceAtMost(h * 0.5f)
        val pull = (drag.value.y / (h * 0.6f)).coerceIn(0f, 1f)
        val f = flight.value
        // фон — не чёрный, а свечение самого кадра; тянешь вниз — проступает лента
        val bgAlpha = (f.coerceIn(0f, 1f) * (1f - pull * 1.15f)).coerceIn(0f, 1f)
        Glow(s, item, Modifier.fillMaxSize().graphicsLayer { alpha = bgAlpha })

        val inFlight = f < 0.999f || closing || dismissing
        // видео запускается само, когда долистал до него и кадр долетел; ушёл — останавливается
        LaunchedEffect(settled?.id, f >= 0.999f, closing) {
            val it = settled
            if (it != null && it.video && f >= 0.999f && !closing) pb.playVideo(it.id, s.lib.uri(it)) else pb.stop()
        }
        val thumb = rememberLarge(s, item)
        // подписи видны (или вот-вот появятся) — кадр уступает им место; тап, зум, смахивание, подробности — во весь экран
        val chromeOn = chrome && !dismissing && !closing && !zoomed && info.value < 0.05f
        val room by animateFloatAsState(if (chromeOn) 1f else 0f, fineSpring(1f, 420f), label = "room")
        if (inFlight) {
            // летящий кадр: от квадрата плитки к целому кадру, скругление плитки → 0
            val target = shownRect(item.width, item.height, box, chromeTop, chromeBottom, room).let { r ->
                val k = 1f - pull * 0.45f
                val c = r.center + drag.value
                Rect(c.x - r.width * k / 2f, c.y - r.height * k / 2f, c.x + r.width * k / 2f, c.y + r.height * k / 2f)
            }
            val start = from
            val rect = if (start != null) lerpRect(start, target, f.coerceIn(-0.1f, 1.1f)) else target
            val alpha = if (start == null) f.coerceIn(0f, 1f) else 1f
            // скругление плитки под линзой — тот же радиус, что у плитки в ленте: без скачка формы на взлёте
            val r = with(density) { radiusFor(ctl.cols).toPx() + 14.dp.toPx() * ctl.lensNow() } * (1f - f).coerceIn(0f, 1f) +
                with(density) { 18.dp.toPx() } * pull
            Canvas(Modifier.fillMaxSize()) {
                val b = thumb ?: return@Canvas
                clipPath(Path().apply { addRoundRect(androidx.compose.ui.geometry.RoundRect(rect, androidx.compose.ui.geometry.CornerRadius(r))) }) {
                    drawCover(b, rect, alpha)
                }
            }
        }

        HorizontalPager(
            state = pager,
            modifier = Modifier.fillMaxSize().graphicsLayer {
                alpha = if (inFlight) 0f else 1f
                translationY = -info.value.coerceIn(0f, 1.1f) * panelH * 0.62f
            },
            pageSpacing = 16.dp,
            userScrollEnabled = !zoomed && !dismissing && info.value < 0.01f,
            key = { v.items[it].id },
            beyondViewportPageCount = 1,
        ) { page ->
            val it = v.items[page]
            Page(
                s, it, box, pb, active = page == pager.settledPage && !inFlight,
                rect = shownRect(it.width, it.height, box, chromeTop, chromeBottom, room),
                onTap = { if (info.value > 0.01f) scope.launch { info.animateTo(0f, spring(0.86f, 420f)) } else chrome = !chrome },
                infoOpen = { info.value > 0.01f },
                onInfoDrag = { dy -> scope.launch { info.snapTo((info.value - dy / panelH).coerceIn(0f, 1.15f)) } },
                onInfoEnd = { vy ->
                    val open = if (abs(vy) > 900f) vy < 0 else info.value > 0.35f
                    scope.launch { info.animateTo(if (open) 1f else 0f, spring(0.82f, 380f)) }
                },
                onZoom = { z -> if (page == pager.currentPage) zoomed = z },
                onDrag = { d ->
                    dismissing = true
                    scope.launch { drag.snapTo(drag.value + d) }
                },
                onDragEnd = { vy ->
                    val far = drag.value.y > h * 0.12f || vy > 1800f
                    if (far && drag.value.y > 0f) close() else scope.launch {
                        drag.animateTo(Offset.Zero, spring(0.72f, 520f)); dismissing = false
                    }
                },
            )
        }

        val ui by animateFloatAsState(if (chromeOn && f > 0.98f) 1f else 0f, tween(180), label = "chrome")
        // собраны и пока невидимы — чтобы их размер был известен ещё до того, как кадр долетит
        if (ui > 0.001f || chromeOn) Chrome(
            s, item, v.fromFilter != null, ui, pb, onShowInFeed = { s.showInFeed(item) },
            onTop = { chromeTop = it }, onBottom = { chromeBottom = it },
        )
        if (info.value > 0.001f) InfoPanel(s, item, panelH, info.value)
    }
}

private val GlowFilter = androidx.compose.ui.graphics.ColorFilter.colorMatrix(
    androidx.compose.ui.graphics.ColorMatrix().apply {
        setToSaturation(1.55f)
        // яркость ×0.82: свечение — фон, кадр поверх него должен быть ярче
        timesAssign(androidx.compose.ui.graphics.ColorMatrix(floatArrayOf(
            0.82f, 0f, 0f, 0f, 0f, 0f, 0.82f, 0f, 0f, 0f, 0f, 0f, 0.82f, 0f, 0f, 0f, 0f, 0f, 1f, 0f,
        )))
    }
)

/**
 * Свечение кадра: миниатюра, сжатая до 12×12 и растянутая на весь экран со сглаживанием, — мягкое облако
 * его цветов, почти бесплатно и на любом Android. Сверху — затемнение, чтобы кадр читался. Листаешь —
 * свечение перетекает в цвета следующего кадра.
 */
@Composable
private fun Glow(s: Still, item: MediaItem, modifier: Modifier) {
    val thumb = rememberLarge(s, item)
    val small by produceState<ImageBitmap?>(null, item.id, thumb) {
        val t = thumb ?: return@produceState
        value = withContext(Dispatchers.Default) {
            runCatching {
                val src = t.asAndroidBitmap().let { if (it.config == Bitmap.Config.HARDWARE) it.copy(Bitmap.Config.ARGB_8888, false) else it }
                Bitmap.createScaledBitmap(src, 12, 12, true).asImageBitmap()
            }.getOrNull()
        }
    }
    androidx.compose.animation.Crossfade(small, modifier, animationSpec = tween(420), label = "glow") { g ->
        Canvas(Modifier.fillMaxSize().graphicsLayer {
            // на Android 12+ облако ещё и размыто видеокартой — без следов растяжения
            if (android.os.Build.VERSION.SDK_INT >= 31) renderEffect =
                androidx.compose.ui.graphics.BlurEffect(48.dp.toPx(), 48.dp.toPx(), androidx.compose.ui.graphics.TileMode.Clamp)
        }) {
            drawRect(Color.Black)
            if (g != null) {
                // чуть крупнее экрана: края облака не видны
                val pad = size.minDimension * 0.2f
                drawImage(
                    g, dstOffset = androidx.compose.ui.unit.IntOffset(-pad.toInt(), -pad.toInt()),
                    dstSize = androidx.compose.ui.unit.IntSize((size.width + 2 * pad).toInt(), (size.height + 2 * pad).toInt()),
                    filterQuality = androidx.compose.ui.graphics.FilterQuality.High,
                    // насыщеннее и чуть темнее исходника: облако чистого цвета, а не серо-болотная муть
                    colorFilter = GlowFilter,
                )
                // затемнение только к краям, где подписи и кнопки; середина остаётся цветной
                drawRect(
                    androidx.compose.ui.graphics.Brush.radialGradient(
                        listOf(Color.Black.copy(alpha = 0.08f), Color.Black.copy(alpha = 0.5f)),
                        center = center, radius = size.maxDimension * 0.62f,
                    )
                )
            }
        }
    }
}

/** Кадр для полёта и первой отрисовки: самая крупная миниатюра из памяти, иначе грузим. */
@Composable
private fun rememberLarge(s: Still, item: MediaItem): ImageBitmap? {
    val st = produceState(s.thumbs.peek(item.id, 160)?.asImageBitmap(), item.id) {
        s.thumbs.load(item, 1024)?.let { value = it.asImageBitmap() }
    }
    return st.value
}

/**
 * Страница просмотра. Один жест на всё: два пальца — зум к точке между ними; один палец при зуме — сдвиг;
 * без зума — вертикаль ведёт смахивание вниз, горизонталь отдаётся листанию.
 */
@Composable
private fun Page(
    s: Still, item: MediaItem, box: Size, pb: Playback, active: Boolean, rect: Rect,
    infoOpen: () -> Boolean, onInfoDrag: (Float) -> Unit, onInfoEnd: (Float) -> Unit,
    onTap: () -> Unit, onZoom: (Boolean) -> Unit, onDrag: (Offset) -> Unit, onDragEnd: (Float) -> Unit,
) {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val maxPx = remember { max(box.width, box.height).toInt().coerceAtMost(4096) * 2 }
    val thumb = rememberLarge(s, item)
    val full by produceState<ImageBitmap?>(null, item.id) {
        if (item.video) return@produceState
        value = withContext(Dispatchers.IO) { s.lib.full(item, maxPx) }?.asImageBitmap()
    }
    val scale = remember { Animatable(1f) }
    val pan = remember { Animatable(Offset.Zero, Offset.VectorConverter) }
    // резкость при зуме: пальцы остановились — видимая часть догружается из оригинала в полном разрешении
    var region by remember(item.id) { mutableStateOf<Pair<ImageBitmap, Rect>?>(null) }
    LaunchedEffect(item.id, active) {
        if (!active || item.video || item.orientation != 0 || item.width <= 0) { region = null; return@LaunchedEffect }
        snapshotFlow { scale.value to pan.value }.collectLatest { (k, pn) ->
            if (k < 1.8f) { region = null; return@collectLatest }
            kotlinx.coroutines.delay(180)
            region = withContext(Dispatchers.IO) { decodeRegion(s, item, box, fit(item.width, item.height, box), k, pn) }
        }
    }
    val view = LocalView.current
    val ctx = androidx.compose.ui.platform.LocalContext.current
    // живое фото: зажал — оживает, отпустил — замирает; тап после удержания — не тап
    var held by remember { mutableStateOf(false) }

    fun clampPan(o: Offset, k: Float): Offset {
        val mx = max(0f, (rect.width * k - box.width) / 2f); val my = max(0f, (rect.height * k - box.height) / 2f)
        return Offset(o.x.coerceIn(-mx, mx), o.y.coerceIn(-my, my))
    }

    Box(
        Modifier.fillMaxSize().pointerInput(item.id, active) {
            if (item.video || !active) return@pointerInput
            val slop = viewConfiguration.touchSlop
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val moved = withTimeoutOrNull(380) {
                    while (true) {
                        val e = awaitPointerEvent()
                        if (e.changes.none { it.pressed } || e.changes.size > 1) return@withTimeoutOrNull true
                        if ((e.changes.first().position - down.position).getDistance() > slop) return@withTimeoutOrNull true
                    }
                    @Suppress("UNREACHABLE_CODE") true
                }
                if (moved != null || scale.value > 1.01f) return@awaitEachGesture
                held = true
                scope.launch {
                    val off = withContext(Dispatchers.IO) { com.eagalts.still.media.Motion.videoOffset(ctx, s.lib.uri(item)) }
                    if (off != null && held) {
                        view.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                        pb.playMotion(item.id, ctx, s.lib.uri(item), off)
                    }
                }
                do { val e = awaitPointerEvent() } while (e.changes.any { it.pressed })
                pb.stopMotion(item.id)
            }
        }.pointerInput(item.id) {
            val slop = viewConfiguration.touchSlop
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                var mode = 0 // 0 — решаем, 1 — смахивание, 2 — листание (не наше), 3 — зум/сдвиг, 4 — подробности
                var total = Offset.Zero
                var lastSpan = 0f
                var tapCandidate = true
                var vy = 0f
                var lastT = down.uptimeMillis
                while (true) {
                    val ev = awaitPointerEvent(PointerEventPass.Main)
                    val pressed = ev.changes.filter { it.pressed }
                    if (pressed.isEmpty()) {
                        if (mode == 1) onDragEnd(vy)
                        if (mode == 4) onInfoEnd(vy)
                        if (mode == 3 && scale.value < 1f) scope.launch {
                            launch { scale.animateTo(1f, spring(0.8f, 500f)) }
                            pan.animateTo(Offset.Zero, spring(0.8f, 500f))
                        }
                        if (held) { held = false; break }
                        if (tapCandidate && mode == 0) {
                            // двойной тап: ждём второе касание; нет — это одиночный тап
                            val second = withTimeoutOrNull(viewConfiguration.doubleTapTimeoutMillis) { awaitFirstDown(requireUnconsumed = false) }
                            if (second == null) onTap() else {
                                second.consume()
                                val target = if (scale.value > 1.05f) 1f else 2.6f
                                val p = second.position - Offset(box.width / 2f, box.height / 2f)
                                val toPan = if (target == 1f) Offset.Zero else clampPan(-p * (target - 1f), target)
                                onZoom(target > 1f)
                                scope.launch { scale.animateTo(target, spring(0.86f, 420f)) }
                                scope.launch { pan.animateTo(toPan, spring(0.86f, 420f)) }
                                // отпускание второго тапа — не новый жест
                                do { val e = awaitPointerEvent() } while (e.changes.any { it.pressed })
                            }
                        }
                        break
                    }
                    if (pressed.size >= 2) {
                        tapCandidate = false
                        mode = 3
                        val a = pressed[0].position; val b = pressed[1].position
                        val span = (a - b).getDistance()
                        val c = (a + b) / 2f - Offset(box.width / 2f, box.height / 2f)
                        if (lastSpan > 0f) {
                            val k0 = scale.value
                            // ниже 1 — с сопротивлением, отпустил — вернётся
                            val raw = k0 * span / lastSpan
                            val k = if (raw < 1f) 1f - (1f - raw) * 0.4f else raw.coerceAtMost(8f)
                            val pc = pressed.map { it.positionChange() }.reduce { x, y -> x + y } / pressed.size.toFloat()
                            val np = (pan.value - c) * (k / k0) + c + pc
                            scope.launch { scale.snapTo(k) }
                            scope.launch { pan.snapTo(clampPan(np, k)) }
                            onZoom(k > 1.01f)
                        }
                        lastSpan = span
                        ev.changes.forEach { it.consume() }
                        continue
                    }
                    lastSpan = 0f
                    val ch = pressed.first()
                    val d = ch.positionChange()
                    total += d
                    val dt = (ch.uptimeMillis - lastT).coerceAtLeast(1)
                    vy = d.y / dt * 1000f
                    lastT = ch.uptimeMillis
                    if (total.getDistance() > slop) tapCandidate = false
                    if (scale.value > 1.01f) {
                        mode = 3
                        val np = clampPan(pan.value + d, scale.value)
                        // упёрлись в край по горизонтали — отдаём жест листанию
                        if (np.x == pan.value.x && abs(d.x) > abs(d.y)) continue
                        ch.consume()
                        scope.launch { pan.snapTo(np) }
                        continue
                    }
                    when (mode) {
                        0 -> if (total.getDistance() > slop) {
                            val vertical = abs(total.y) > abs(total.x) * 1.2f
                            mode = when {
                                // открыты подробности — вертикаль двигает панель (и вниз тоже: закрыть)
                                vertical && infoOpen() -> 4
                                vertical && total.y < 0 -> 4
                                vertical -> 1
                                else -> 2
                            }
                            if (mode == 1) { ch.consume(); onDrag(total) }
                            if (mode == 4) { ch.consume(); onInfoDrag(total.y) }
                        }
                        1 -> { ch.consume(); onDrag(d) }
                        4 -> { ch.consume(); onInfoDrag(d.y) }
                        2 -> {}
                    }
                    if (mode == 2) break
                }
            }
        }
    ) {
        Canvas(Modifier.fillMaxSize().graphicsLayer {
            val k = scale.value.coerceAtLeast(0.1f)
            scaleX = k; scaleY = k
            translationX = pan.value.x; translationY = pan.value.y
        }) {
            val b = full ?: thumb ?: return@Canvas
            drawCover(b, rect)
            if (active) ViewerProbe.rect = rect
            region?.let { (bmp, src) ->
                val dx = rect.left + src.left / item.width * rect.width; val dy = rect.top + src.top / item.height * rect.height
                val dw = src.width / item.width * rect.width; val dh = src.height / item.height * rect.height
                drawImage(
                    bmp, dstOffset = androidx.compose.ui.unit.IntOffset(dx.toInt(), dy.toInt()),
                    dstSize = androidx.compose.ui.unit.IntSize(dw.toInt().coerceAtLeast(1), dh.toInt().coerceAtLeast(1)),
                    filterQuality = androidx.compose.ui.graphics.FilterQuality.High,
                )
            }
        }
        // видео и ожившее фото — поверх кадра, в его же рамке; появляются с первым кадром (без чёрной вспышки)
        val mine = pb.owner == item.id && active
        val shown by animateFloatAsState(if (mine && pb.firstFrame) 1f else 0f, tween(if (mine) 120 else 220), label = "video")
        if (mine || shown > 0.01f) AndroidView(
            factory = { c -> android.view.TextureView(c) },
            update = { tv -> if (mine) pb.attach(tv) },
            // в рамке кадра: кадр может стоять не по центру — между подписями
            modifier = Modifier.offset { androidx.compose.ui.unit.IntOffset(rect.left.toInt(), rect.top.toInt()) }
                .size(with(density) { rect.width.toDp() }, with(density) { rect.height.toDp() })
                .graphicsLayer { alpha = shown },
        )
    }
}

/** Немногие элементы просмотра: время сверху, три действия снизу. Тап по фото — прячутся. */
@Composable
private fun Chrome(
    s: Still, item: MediaItem, fromFilter: Boolean, a: Float, pb: Playback, onShowInFeed: () -> Unit,
    onTop: (Float) -> Unit, onBottom: (Float) -> Unit,
) {
    val left = s.left(item)
    Box(Modifier.fillMaxSize().graphicsLayer { alpha = a.coerceIn(0f, 1f) }) {
        Column(
            Modifier.fillMaxWidth().background(
                androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color(0x99000000), Color.Transparent))
            ).onGloballyPositioned { onTop(it.size.height.toFloat()) }.testTag("viewer-top")
                .statusBarsPadding().padding(top = 6.dp, bottom = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                Dates.moment(item.taken), color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, fontFamily = UiFont,
                modifier = if (fromFilter) Modifier.bouncy(0.95f, onClick = onShowInFeed) else Modifier,
            )
            if (fromFilter) Text(
                t("Показать в ленте"), color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp, fontFamily = UiFont,
                modifier = Modifier.padding(top = 2.dp).bouncy(0.95f, onClick = onShowInFeed),
            )
            if (left != null) Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (left == 0L) t("В Потоке") else t("Уйдёт в Поток через {0}", flowLeft(left)),
                    color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp, fontFamily = UiFont,
                )
                Text(
                    t("Оставить"), color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, fontFamily = UiFont,
                    modifier = Modifier.padding(start = 8.dp).bouncy(0.9f) { s.keep(listOf(item.id)) }
                        .clip(RoundedCornerShape(50)).background(Color(0x33FFFFFF)).padding(horizontal = 10.dp, vertical = 3.dp),
                )
            }
        }
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(
                androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color.Transparent, Color(0x99000000)))
            ).onGloballyPositioned { onBottom(it.size.height.toFloat()) }.testTag("viewer-bottom")
                .navigationBarsPadding().padding(top = 24.dp, bottom = 10.dp),
        ) {
        if (item.video && pb.owner == item.id) VideoBar(pb)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            ChromeButton(Icons.Share, t("Поделиться")) { s.share(listOf(item)) }
            ChromeButton(if (item.favorite) Icons.HeartFilled else Icons.Heart, t("Избранное")) { s.favorite(listOf(item), !item.favorite) }
            ChromeButton(Icons.Trash, t("Удалить")) { s.trash(listOf(item)) }
        }
        }
    }
}

/**
 * Видео: пауза и тонкая полоса времени. Полосу можно тянуть — кадр идёт за пальцем; при касании полоса
 * утолщается пружиной. Позиция читается по кадрам, только пока видео играет и полоса на экране.
 */
@Composable
private fun VideoBar(pb: Playback) {
    val density = LocalDensity.current
    var pos by remember { mutableStateOf(pb.position()) }
    var scrub by remember { mutableStateOf<Float?>(null) }
    LaunchedEffect(pb.playing, scrub) {
        while (pb.playing && scrub == null) { androidx.compose.runtime.withFrameNanos { }; pos = pb.position() }
        pos = pb.position()
    }
    val dur = pb.duration().coerceAtLeast(1L)
    val frac = scrub ?: (pos.toFloat() / dur).coerceIn(0f, 1f)
    val thick by animateFloatAsState(if (scrub != null) 1f else 0f, fineSpring(0.6f, 700f), label = "thick")
    Row(Modifier.fillMaxWidth().testTag("video-bar").padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(44.dp).bouncy(0.82f) { pb.toggle() }, contentAlignment = Alignment.Center) {
            androidx.compose.animation.Crossfade(pb.playing, label = "pp") { on ->
                Icon(if (on) Icons.Pause else Icons.Play, null, tint = Color.White, modifier = Modifier.size(if (on) 22.dp else 20.dp))
            }
        }
        BoxWithConstraints(
            Modifier.weight(1f).height(32.dp).pointerInput(Unit) {
                awaitEachGesture {
                    val d = awaitFirstDown(); d.consume()
                    val w = size.width.toFloat().coerceAtLeast(1f)
                    scrub = (d.position.x / w).coerceIn(0f, 1f); pb.seek((scrub!! * dur).toLong())
                    while (true) {
                        val e = awaitPointerEvent(); val c = e.changes.first()
                        if (!c.pressed) break
                        c.consume(); scrub = (c.position.x / w).coerceIn(0f, 1f); pb.seek((scrub!! * dur).toLong())
                    }
                    pos = pb.position(); scrub = null
                }
            },
            contentAlignment = Alignment.CenterStart,
        ) {
            val h = with(density) { (3.dp + 3.dp * thick).toPx() }
            Canvas(Modifier.fillMaxWidth().height(12.dp)) {
                val y = size.height / 2f
                drawRoundRect(Color.White.copy(alpha = 0.28f), Offset(0f, y - h / 2f), Size(size.width, h), androidx.compose.ui.geometry.CornerRadius(h / 2f))
                drawRoundRect(Color.White, Offset(0f, y - h / 2f), Size(size.width * frac, h), androidx.compose.ui.geometry.CornerRadius(h / 2f))
            }
        }
        Text(
            clock(((1f - frac) * dur).toLong()), color = Color.White.copy(alpha = 0.85f), fontSize = 12.sp, fontFamily = UiFont,
            modifier = Modifier.padding(start = 10.dp, end = 6.dp).width(44.dp),
        )
    }
}

private fun clock(ms: Long): String { val s = (ms / 1000).toInt(); return "−%d:%02d".format(s / 60, s % 60) }

/**
 * Плеер просмотра (Media3): один на весь просмотр. [owner] — чей кадр он сейчас показывает (видео или живое фото).
 * Состояние для экрана — в Compose-полях; слушатель плеера только переключает их.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class Playback(ctx: android.content.Context) {
    private val player = androidx.media3.exoplayer.ExoPlayer.Builder(ctx).build()
    var owner by mutableStateOf(-1L); private set
    var firstFrame by mutableStateOf(false); private set
    var playing by mutableStateOf(false); private set
    private var surface: android.view.TextureView? = null

    init {
        player.addListener(object : androidx.media3.common.Player.Listener {
            override fun onRenderedFirstFrame() { firstFrame = true }
            override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying }
            override fun onPlaybackStateChanged(state: Int) {
                // досмотрел — встаёт на начало, на паузу: следующее нажатие — заново
                if (state == androidx.media3.common.Player.STATE_ENDED) { player.pause(); player.seekTo(0) }
            }
        })
    }

    fun attach(tv: android.view.TextureView) { if (surface !== tv) { surface = tv; player.setVideoTextureView(tv) } }

    fun playVideo(id: Long, uri: android.net.Uri) {
        if (owner == id) return
        owner = id; firstFrame = false
        player.setMediaItem(androidx.media3.common.MediaItem.fromUri(uri))
        player.prepare(); player.play()
    }

    fun playMotion(id: Long, ctx: android.content.Context, uri: android.net.Uri, offset: Long) {
        owner = id; firstFrame = false
        player.setMediaSource(com.eagalts.still.media.Motion.source(ctx, uri, offset))
        player.prepare(); player.play()
    }

    fun stopMotion(id: Long) { if (owner == id) stop() }

    fun stop() {
        if (owner == -1L) return
        owner = -1L; firstFrame = false
        player.stop(); player.clearMediaItems()
    }

    fun toggle() { if (player.isPlaying) player.pause() else player.play() }
    fun seek(ms: Long) = player.seekTo(ms)
    fun position(): Long = player.currentPosition
    fun duration(): Long = player.duration.takeIf { it > 0 } ?: 1L
    fun release() { player.release(); surface = null }
}

@Composable
private fun ChromeButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Box(Modifier.size(52.dp).testTag("viewer-$label").bouncy(0.82f, onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, label, tint = Color.White, modifier = Modifier.size(24.dp))
    }
}



/**
 * Видимая при зуме часть кадра — из оригинала, с прореживанием ровно до разрешения экрана (не больше):
 * памяти — на один экран, сколько ни приближай.
 */
private fun decodeRegion(s: Still, item: MediaItem, box: Size, rect: Rect, k: Float, pan: Offset): Pair<ImageBitmap, Rect>? = runCatching {
    val ctx = s.ctx ?: return null
    val dispW = rect.width * k; val dispH = rect.height * k
    val left = box.width / 2f + pan.x - dispW / 2f; val top = box.height / 2f + pan.y - dispH / 2f
    val vl = maxOf(0f, left); val vt = maxOf(0f, top)
    val vr = minOf(box.width, left + dispW); val vb = minOf(box.height, top + dispH)
    if (vr <= vl || vb <= vt) return null
    val iw = item.width.toFloat(); val ih = item.height.toFloat()
    // запас по краям — сдвиг пальцем на чуть-чуть не оголяет мыло
    val mx = (vr - vl) * 0.1f; val my = (vb - vt) * 0.1f
    val sl = (((vl - mx) - left) / dispW * iw).coerceIn(0f, iw); val st = (((vt - my) - top) / dispH * ih).coerceIn(0f, ih)
    val sr = (((vr + mx) - left) / dispW * iw).coerceIn(0f, iw); val sb = (((vb + my) - top) / dispH * ih).coerceIn(0f, ih)
    var sample = 1
    while ((sr - sl) / (sample * 2) >= (vr - vl) * 1.0f) sample *= 2
    val bmp = ctx.contentResolver.openInputStream(s.lib.uri(item))?.use { input ->
        val dec = if (android.os.Build.VERSION.SDK_INT >= 31) android.graphics.BitmapRegionDecoder.newInstance(input)
        else @Suppress("DEPRECATION") android.graphics.BitmapRegionDecoder.newInstance(input, false)
        try {
            dec?.decodeRegion(android.graphics.Rect(sl.toInt(), st.toInt(), sr.toInt(), sb.toInt()),
                android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })
        } finally { dec?.recycle() }
    } ?: return null
    bmp.asImageBitmap() to Rect(sl, st, sr, sb)
}.getOrNull()

/**
 * Подробности кадра: выезжают снизу стеклом, кадр уезжает вверх. Дата, камера и съёмка, файл, место —
 * всё, что записано в самом файле, без сети.
 */
@Composable
private fun InfoPanel(s: Still, item: MediaItem, height: Float, open: Float) {
    val density = LocalDensity.current
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val d by produceState<com.eagalts.still.media.Details?>(null, item.id) {
        value = withContext(Dispatchers.IO) { com.eagalts.still.media.DetailsLoader.load(ctx, item, s.lib.uri(item)) }
    }
    val hDp = with(density) { height.toDp() }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        Column(
            Modifier.fillMaxWidth().height(hDp).testTag("info-panel")
                .graphicsLayer { translationY = (1f - open.coerceIn(0f, 1.15f)) * height }
                .clip(SquircleShape(Radius.xl, Radius.xl, 0.dp, 0.dp)).background(Color(0xF2141318))
                .navigationBarsPadding().padding(horizontal = 22.dp, vertical = 20.dp),
        ) {
            Text(Dates.full(item.taken), color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, fontFamily = UiFont)
            Spacer(Modifier.height(14.dp))
            val x = d
            Column(Modifier.fillMaxWidth().clip(SquircleShape(Radius.m)).background(Color(0x14FFFFFF)).padding(16.dp)) {
                Text(x?.camera ?: if (item.video) t("Видео") else t("Без данных камеры"), color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, fontFamily = UiFont)
                if (x?.lens != null) Text(x.lens, color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp, fontFamily = UiFont, modifier = Modifier.padding(top = 2.dp))
                val shot = listOfNotNull(
                    x?.fNumber?.let { "ƒ/" + "%.1f".format(it) },
                    x?.exposure?.let { t("{0} с", com.eagalts.still.media.DetailsLoader.shutter(it)) },
                    x?.iso?.let { "ISO $it" },
                    x?.focal?.let { t("{0} мм", it) },
                )
                if (shot.isNotEmpty()) Text(shot.joinToString("   "), color = Color.White.copy(alpha = 0.85f), fontSize = 14.sp, fontFamily = UiFont, modifier = Modifier.padding(top = 10.dp))
                val mp = item.width.toLong() * item.height / 1_000_000.0
                val file = listOfNotNull(
                    x?.name,
                    if (item.video) "${item.width}×${item.height}" else t("{0} МП", "%.0f".format(mp)) + " · ${item.width}×${item.height}",
                    t("{0} МБ", "%.1f".format(item.size / 1_048_576.0)),
                    if (item.video) clock(item.durationMs).removePrefix("−") else null,
                )
                Text(file.joinToString(" · "), color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp, fontFamily = UiFont, modifier = Modifier.padding(top = 10.dp))
            }
            Spacer(Modifier.height(12.dp))
            val place = x?.let { if (it.lat != null && it.lon != null) com.eagalts.still.media.DetailsLoader.coords(it.lat, it.lon, t("с. ш.") to t("ю. ш."), t("в. д.") to t("з. д.")) else null }
            Row(Modifier.fillMaxWidth().clip(SquircleShape(Radius.m)).background(Color(0x14FFFFFF)).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(place ?: t("Место не записано"), color = Color.White.copy(alpha = if (place != null) 1f else 0.6f), fontSize = 14.sp, fontFamily = UiFont)
                    if (item.bucket.isNotEmpty()) Text(item.bucket, color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp, fontFamily = UiFont, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
    }
}
