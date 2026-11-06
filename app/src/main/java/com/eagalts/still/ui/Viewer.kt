package com.eagalts.still.ui

import android.graphics.Bitmap
import android.widget.VideoView
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
    BackHandler { close() }

    BoxWithConstraints(Modifier.fillMaxSize().testTag("viewer")) {
        val box = with(density) { Size(maxWidth.toPx(), maxHeight.toPx()) }
        val h = box.height
        val pull = (drag.value.y / (h * 0.6f)).coerceIn(0f, 1f)
        val f = flight.value
        // фон — не чёрный, а свечение самого кадра; тянешь вниз — проступает лента
        val bgAlpha = (f.coerceIn(0f, 1f) * (1f - pull * 1.15f)).coerceIn(0f, 1f)
        Glow(s, item, Modifier.fillMaxSize().graphicsLayer { alpha = bgAlpha })

        val inFlight = f < 0.999f || closing || dismissing
        val thumb = rememberLarge(s, item)
        if (inFlight) {
            // летящий кадр: от квадрата плитки к целому кадру, скругление плитки → 0
            val target = fit(item.width, item.height, box).let { r ->
                val k = 1f - pull * 0.45f
                val c = r.center + drag.value
                Rect(c.x - r.width * k / 2f, c.y - r.height * k / 2f, c.x + r.width * k / 2f, c.y + r.height * k / 2f)
            }
            val start = from
            val rect = if (start != null) lerpRect(start, target, f.coerceIn(-0.1f, 1.1f)) else target
            val alpha = if (start == null) f.coerceIn(0f, 1f) else 1f
            val r = with(density) { radiusFor(ctl.cols).toPx() } * (1f - f).coerceIn(0f, 1f) +
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
            modifier = Modifier.fillMaxSize().graphicsLayer { alpha = if (inFlight) 0f else 1f },
            pageSpacing = 16.dp,
            userScrollEnabled = !zoomed && !dismissing,
            key = { v.items[it].id },
            beyondViewportPageCount = 1,
        ) { page ->
            val it = v.items[page]
            Page(
                s, it, box,
                onTap = { chrome = !chrome },
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

        val ui by animateFloatAsState(if (chrome && !dismissing && !closing && f > 0.98f) 1f else 0f, tween(180), label = "chrome")
        if (ui > 0.001f) Chrome(s, item, v.fromFilter != null, ui, onShowInFeed = { s.showInFeed(item) })
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
    s: Still, item: MediaItem, box: Size,
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
    var playing by remember { mutableStateOf(false) }
    val rect = fit(item.width, item.height, box)

    fun clampPan(o: Offset, k: Float): Offset {
        val mx = max(0f, (rect.width * k - box.width) / 2f); val my = max(0f, (rect.height * k - box.height) / 2f)
        return Offset(o.x.coerceIn(-mx, mx), o.y.coerceIn(-my, my))
    }

    Box(
        Modifier.fillMaxSize().pointerInput(item.id) {
            val slop = viewConfiguration.touchSlop
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                var mode = 0 // 0 — решаем, 1 — смахивание, 2 — листание (не наше), 3 — зум/сдвиг
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
                        if (mode == 3 && scale.value < 1f) scope.launch {
                            launch { scale.animateTo(1f, spring(0.8f, 500f)) }
                            pan.animateTo(Offset.Zero, spring(0.8f, 500f))
                        }
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
                            mode = if (abs(total.y) > abs(total.x) * 1.2f && total.y > 0) 1 else 2
                            if (mode == 1) { ch.consume(); onDrag(total) }
                        }
                        1 -> { ch.consume(); onDrag(d) }
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
        }
        if (item.video) {
            if (playing) AndroidView(
                factory = { ctx ->
                    VideoView(ctx).apply {
                        setVideoURI(s.lib.uri(item))
                        setOnPreparedListener { it.isLooping = false; start() }
                        setOnCompletionListener { playing = false }
                    }
                },
                modifier = Modifier.align(Alignment.Center).size(
                    with(density) { rect.width.toDp() }, with(density) { rect.height.toDp() }
                ),
                onRelease = { it.stopPlayback() },
            ) else Box(
                Modifier.align(Alignment.Center).size(68.dp).clip(CircleShape).background(Color(0x66000000))
                    .bouncy(0.85f) { playing = true },
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Play, t("Смотреть"), tint = Color.White, modifier = Modifier.size(28.dp).padding(start = 3.dp)) }
        }
    }
}


/** Немногие элементы просмотра: время сверху, три действия снизу. Тап по фото — прячутся. */
@Composable
private fun Chrome(s: Still, item: MediaItem, fromFilter: Boolean, a: Float, onShowInFeed: () -> Unit) {
    val left = s.daysLeft(item)
    Box(Modifier.fillMaxSize().graphicsLayer { alpha = a.coerceIn(0f, 1f) }) {
        Column(
            Modifier.fillMaxWidth().background(
                androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color(0x99000000), Color.Transparent))
            ).statusBarsPadding().padding(top = 6.dp, bottom = 22.dp),
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
                    if (left == 0) t("В Потоке") else t("Уйдёт в Поток через {0} дн.", left),
                    color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp, fontFamily = UiFont,
                )
                Text(
                    t("Оставить"), color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, fontFamily = UiFont,
                    modifier = Modifier.padding(start = 8.dp).bouncy(0.9f) { s.keep(listOf(item.id)) }
                        .clip(RoundedCornerShape(50)).background(Color(0x33FFFFFF)).padding(horizontal = 10.dp, vertical = 3.dp),
                )
            }
        }
        Row(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(
                androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color.Transparent, Color(0x99000000)))
            ).navigationBarsPadding().padding(top = 24.dp, bottom = 10.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            ChromeButton(Icons.Share, t("Поделиться")) { s.share(listOf(item)) }
            ChromeButton(if (item.favorite) Icons.HeartFilled else Icons.Heart, t("Избранное")) { s.favorite(listOf(item), !item.favorite) }
            ChromeButton(Icons.Trash, t("Удалить")) { s.trash(listOf(item)) }
        }
    }
}

@Composable
private fun ChromeButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Box(Modifier.size(52.dp).bouncy(0.82f, onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, label, tint = Color.White, modifier = Modifier.size(24.dp))
    }
}

