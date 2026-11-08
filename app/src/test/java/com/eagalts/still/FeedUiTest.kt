package com.eagalts.still

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.net.Uri
import android.os.CancellationSignal
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.click
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.pinch
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.activity.ComponentActivity
import com.eagalts.still.media.Ephemeral
import com.eagalts.still.media.Index
import com.eagalts.still.media.Library
import com.eagalts.still.media.MediaItem
import com.eagalts.still.media.Source
import com.eagalts.still.ui.StillApp
import kotlinx.coroutines.MainScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Подделка MediaStore: кадры — цветные градиенты, у каждого свой оттенок. */
class FakeLibrary(val items: List<MediaItem>) : Library {
    val gone = HashSet<Long>()
    override fun load(trashed: Boolean) = if (trashed) emptyList() else items.filter { it.id !in gone }
    override fun uri(it: MediaItem): Uri = Uri.parse("content://fake/${it.id}")
    /** Кадр «как фото»: пейзаж, закат над водой, вечерний город или портрет — свет, планы, силуэты. */
    private fun paint(it: MediaItem, w: Int, h: Int): Bitmap {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val W = w.toFloat(); val H = h.toFloat()
        val rnd = java.util.Random(it.id * 7919)
        fun hsv(hh: Float, ss: Float, vv: Float) = android.graphics.Color.HSVToColor(floatArrayOf(((hh % 360) + 360) % 360, ss, vv))
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        fun sky(top: Int, bottom: Int) { p.shader = LinearGradient(0f, 0f, 0f, H, top, bottom, Shader.TileMode.CLAMP); c.drawRect(0f, 0f, W, H, p); p.shader = null }
        fun hills(y: Float, amp: Float, col: Int, k: Float) {
            val path = android.graphics.Path(); path.moveTo(0f, H)
            var x = 0f; val ph = rnd.nextFloat() * 6f
            while (x <= W) { path.lineTo(x, y + amp * kotlin.math.sin(x / W * k + ph).toFloat() + amp * 0.3f * kotlin.math.sin(x / W * k * 3.1f).toFloat()); x += W / 40f }
            path.lineTo(W, H); path.close(); p.color = col; c.drawPath(path, p)
        }
        val hue = rnd.nextFloat() * 360f
        when ((it.id % 4).toInt()) {
            0 -> { // горы днём
                sky(hsv(205f + hue * 0.05f, 0.55f, 0.95f), hsv(195f, 0.18f, 1f))
                p.color = hsv(50f, 0.15f, 1f); c.drawCircle(W * (0.2f + rnd.nextFloat() * 0.6f), H * 0.2f, W * 0.07f, p)
                hills(H * 0.5f, H * 0.08f, hsv(220f, 0.25f, 0.62f), 5f)
                hills(H * 0.62f, H * 0.06f, hsv(150f + hue * 0.1f, 0.45f, 0.45f), 4f)
                hills(H * 0.78f, H * 0.05f, hsv(110f + hue * 0.1f, 0.6f, 0.32f), 3f)
            }
            1 -> { // закат над водой
                val warm = 10f + hue * 0.08f
                sky(hsv(260f, 0.45f, 0.45f), hsv(warm + 20f, 0.75f, 1f))
                p.shader = android.graphics.RadialGradient(W * 0.5f, H * 0.55f, W * 0.45f, intArrayOf(hsv(warm + 35f, 0.6f, 1f), 0x00FFFFFF), null, Shader.TileMode.CLAMP)
                c.drawRect(0f, 0f, W, H, p); p.shader = null
                p.color = hsv(warm + 40f, 0.4f, 1f); c.drawCircle(W * 0.5f, H * 0.57f, W * 0.09f, p)
                p.shader = LinearGradient(0f, H * 0.6f, 0f, H, hsv(warm + 10f, 0.7f, 0.7f), hsv(230f, 0.6f, 0.25f), Shader.TileMode.CLAMP)
                c.drawRect(0f, H * 0.6f, W, H, p); p.shader = null
                p.color = hsv(warm + 40f, 0.3f, 1f); p.alpha = 150
                for (k in 0 until 7) c.drawRect(W * (0.42f + rnd.nextFloat() * 0.08f), H * (0.63f + k * 0.04f), W * (0.5f + rnd.nextFloat() * 0.08f), H * (0.635f + k * 0.04f), p)
                p.alpha = 255
            }
            2 -> { // вечерний город
                sky(hsv(230f, 0.6f, 0.3f), hsv(300f + hue * 0.1f, 0.4f, 0.75f))
                var x = 0f
                while (x < W) {
                    val bw = W * (0.08f + rnd.nextFloat() * 0.1f); val bh = H * (0.25f + rnd.nextFloat() * 0.4f)
                    p.color = hsv(240f, 0.3f, 0.12f + rnd.nextFloat() * 0.08f); c.drawRect(x, H - bh, x + bw, H, p)
                    p.color = hsv(40f, 0.6f, 1f)
                    var wy = H - bh + bw * 0.2f
                    while (wy < H - 4) { var wx = x + bw * 0.15f; while (wx < x + bw * 0.8f) { if (rnd.nextFloat() < 0.45f) c.drawRect(wx, wy, wx + bw * 0.1f, wy + bw * 0.1f, p); wx += bw * 0.22f }; wy += bw * 0.25f }
                    x += bw + 2f
                }
            }
            else -> { // портрет: мягкий фон, силуэт головы и плеч
                sky(hsv(hue, 0.25f, 0.85f), hsv(hue + 30f, 0.35f, 0.55f))
                p.shader = android.graphics.RadialGradient(W * 0.3f, H * 0.25f, W * 0.5f, intArrayOf(0x66FFFFFF, 0x00FFFFFF), null, Shader.TileMode.CLAMP)
                c.drawRect(0f, 0f, W, H, p); p.shader = null
                p.color = hsv(hue + 180f, 0.3f, 0.3f)
                c.drawOval(W * 0.18f, H * 0.68f, W * 0.82f, H * 1.25f, p)
                p.color = hsv(25f, 0.35f, 0.72f); c.drawOval(W * 0.36f, H * 0.3f, W * 0.64f, H * 0.66f, p)
                p.color = hsv(25f, 0.5f, 0.25f); c.drawOval(W * 0.34f, H * 0.26f, W * 0.66f, H * 0.42f, p)
            }
        }
        return b
    }
    override fun thumbnail(it: MediaItem, px: Int, cancel: CancellationSignal) =
        if (it.width >= it.height) paint(it, px, px * it.height / it.width) else paint(it, px * it.width / it.height, px)
    override fun full(it: MediaItem, maxPx: Int) = thumbnail(it, 600, CancellationSignal())
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w412dp-h915dp-420dpi")
class FeedUiTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val now = System.currentTimeMillis()
    private val day = Ephemeral.DAY
    private lateinit var still: Still

    private fun mk(id: Long, taken: Long, source: Source = Source.CAMERA, video: Boolean = false, w: Int = 4000, h: Int = 3000, size: Long = 1000, bucket: Long = 1) =
        MediaItem(id, video, taken, taken, source, bucket, if (source == Source.CAMERA) "Camera" else "Screenshots",
            if (video) "video/mp4" else "image/jpeg", w, h, size, durationMs = if (video) 42_000 else 0)

    private fun items(): List<MediaItem> = buildList {
        var t = now - 3_600_000
        // серия из 4 кадров
        for (i in 0 until 4) add(mk(100L + i, t - i * 400, size = 1000L + i * 10))
        t -= 3_600_000
        add(mk(200, t, video = true, w = 1920, h = 1080))
        for (i in 0 until 8) { t -= 5_400_000; add(mk(300L + i, t, w = if (i % 3 == 0) 3000 else 4000, h = if (i % 3 == 0) 4000 else 3000)) }
        // свежие скриншоты (в ленте) и старые (ушли в «Поток»)
        for (i in 0 until 3) add(mk(500L + i, now - (2 + i) * day, Source.SCREENSHOT, w = 1080, h = 2400, bucket = 9))
        for (i in 0 until 5) add(mk(600L + i, now - (40 + i) * day, Source.SCREENSHOT, w = 1080, h = 2400, bucket = 9))
        for (i in 0 until 30) add(mk(700L + i, now - (20 + i * 9) * day))
    }.sortedByDescending { it.taken }

    @Before fun setUp() {
        java.util.Locale.setDefault(java.util.Locale("ru", "RU"))
        I18n.init(rule.activity)
        still = Still(rule.activity, FakeLibrary(items()), Index(null), MainScope(), now = { now }, vectors = sceneVectors())
        still.access = Access.FULL
    }

    /** Векторы сцен, как их дала бы модель: серия 100–103 — одна сцена, остальное ещё не посчитано (по одному). */
    private fun sceneVectors() = com.eagalts.still.media.Vectors(null).apply {
        val r = java.util.Random(3)
        val c = FloatArray(1024) { r.nextGaussian().toFloat() }
        for (id in 100L..103L) put(id, com.eagalts.still.media.Embedder.quantize(FloatArray(1024) { c[it] + 0.4f * r.nextGaussian().toFloat() }))
    }

    /** Ждать, прокручивая главный поток Robolectric: работа ленты уходит в фон и возвращается в него. */
    private fun until(ms: Long = 5000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (!cond()) {
            check(System.currentTimeMillis() < end) { "не дождались" }
            rule.mainClock.advanceTimeBy(16)
            // и часы главного потока: задержки корутин на нём (распад перед перечиткой) идут по ним
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(16))
            Thread.sleep(2)
        }
        rule.waitForIdle()
    }

    private val ctl = com.eagalts.still.ui.FeedController(androidx.compose.foundation.lazy.grid.LazyGridState())

    private fun show(dark: Boolean = false) {
        rule.setContent { StillApp(still, dark, onAsk = {}, feedController = ctl) }
        rule.runOnIdle { still.reload(force = true) }
        until { still.feed.cells.isNotEmpty() }
        // миниатюры декодируются в фоне
        repeat(20) { org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle(); Thread.sleep(10) }
        rule.waitForIdle()
    }

    private fun shot(name: String, settle: Boolean = true): Bitmap {
        if (settle) { rule.mainClock.advanceTimeBy(1500); rule.waitForIdle() }
        // окно рисуется в картинку (captureToImage в Robolectric ждёт кадра вечно) — как в тестах Plume
        val v = rule.activity.window.decorView
        val b = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
        rule.activity.runOnUiThread { v.draw(Canvas(b)) }
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        val dir = File("build/shots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return b
    }

    @Test fun feedHasCameraAndFreshNoiseButNotOld() {
        show()
        val ids = still.feed.cells.filterIsInstance<Cell.Tile>().map { it.item.id }.toSet()
        assertTrue(500L in ids)
        assertTrue((600L..604L).none { it in ids })
        // серия — одна плитка-стопка, обложка — самый тяжёлый кадр
        assertTrue(103L in ids && 100L !in ids)
        shot("feed-light")
    }

    @Test fun opensAtNewestAtTheBottomWithoutDates() {
        show()
        val cells = still.feed.cells
        assertTrue("в обычной ленте подписей нет", cells.all { (it as Cell.Tile).label == null })
        // от старого к новому: последняя плитка — свежая серия
        assertEquals(103L, (cells.last() as Cell.Tile).item.id)
        val tiles = cells.filterIsInstance<Cell.Tile>().map { it.item.taken }
        assertEquals(tiles.sorted(), tiles)
        // и лента открылась на ней: свежий ряд — ровно в центре линзы, крупно, а не мелко у края
        val r = rule.onNodeWithTag("tile-103").fetchSemanticsNode().boundsInRoot
        val h = rule.onNodeWithTag("feed").fetchSemanticsNode().boundsInRoot.height
        assertEquals("свежее не в центре", h / 2f, r.center.y, 3f)
        shot("feed-bottom")
    }

    @Test fun zoomOutShowsMonthsThenOnlyYears() {
        show()
        rule.runOnIdle { still.zoomTo(8) }
        // раскладка — сразу, подписи досчитываются следом
        until { still.feed.cols == 8 && still.feed.cells.any { (it as Cell.Tile).label != null } }
        // подписи — на первых кадрах периодов, отдельных полос нет: все ячейки — кадры
        assertTrue(still.feed.cells.all { it is Cell.Tile })
        val months = still.feed.cells.filterIsInstance<Cell.Tile>().mapNotNull { it.label }
        assertTrue(months.toString(), months.isNotEmpty() && months.all { Regex("^\\p{L}+ \\d{4}$").matches(it) })
        shot("zoom-months")
        rule.runOnIdle { still.zoomTo(13) }
        until { still.feed.cols == 13 && still.feed.cells.any { (it as Cell.Tile).label?.all(Char::isDigit) == true } }
        val years = still.feed.cells.filterIsInstance<Cell.Tile>().mapNotNull { it.label }
        assertTrue(years.isNotEmpty() && years.all { Regex("\\d{4}").matches(it) })
        shot("zoom-years")
    }

    /** Отдалил — линзы нет, сетка как у Apple: между кадрами тонкая линия фона, не толще 2 dp. */
    @Test fun zoomedOutIsThinAppleGrid() {
        show()
        rule.runOnIdle { still.zoomTo(8) }
        // раскладка — сразу, подписи досчитываются следом
        until { still.feed.cols == 8 && still.feed.cells.any { (it as Cell.Tile).label != null } }
        val b = shot("grid-8")
        val y = b.height * 3 / 4
        val page = android.graphics.Color.WHITE
        val near = (b.width / 8 - 8..b.width / 8 + 8).filter { b.getPixel(it, y) == page }
        val d = rule.activity.resources.displayMetrics.density
        assertTrue("линии между кадрами нет", near.isNotEmpty())
        assertTrue("линия толще 2 dp: ${near.size}px", near.size <= (2 * d).toInt() + 1)
    }

    /** Сколько фона на горизонтали [y] — чем меньше кадры в этом ряду, тем больше фона между ними. */
    private fun background(b: Bitmap, y: Int): Int {
        val page = b.getPixel(2, y).let { if (it == android.graphics.Color.WHITE) it else android.graphics.Color.WHITE }
        return (0 until b.width step 2).count { b.getPixel(it, y) == page }
    }

    /**
     * Линза: в покое кадры у края экрана заметно меньше, чем в центре (между ними больше фона); на быстрой
     * прокрутке линза выпрямляется — ряды у края и в центре почти одинаковые.
     */
    @Test fun lensAtRestFlattensWhenFast() {
        show()
        // полный ряд над центральным (свежий ряд может быть неполным) и самый верхний видимый ряд
        val infos = ctl.grid.layoutInfo.visibleItemsInfo
        val rows = infos.groupBy { it.row }.filter { it.value.size == 3 }.toSortedMap()
        val near = rows.values.last()[0]; val far = rows.values.first()[0]
        fun rowY(i: androidx.compose.foundation.lazy.grid.LazyGridItemInfo) = (i.offset.y - ctl.grid.layoutInfo.viewportStartOffset + i.size.height / 2)
        val rest = shot("lens-rest")
        val d = rule.activity.resources.displayMetrics.density
        val nearBg = background(rest, rowY(near)); val farBg = background(rest, rowY(far))
        assertTrue("в покое дальний ряд не меньше ближнего: $farBg против $nearBg", farBg > nearBg + (12 * d).toInt())
        rule.mainClock.autoAdvance = false
        rule.runOnIdle { ctl.velocity = 4000f }
        rule.mainClock.advanceTimeByFrame(); rule.waitForIdle()
        val fast = shot("lens-fast", settle = false)
        val fastFar = background(fast, rowY(far))
        assertTrue("на скорости линза не выпрямилась: $fastFar, было $farBg", fastFar < farBg - (6 * d).toInt())
        rule.mainClock.autoAdvance = true
    }

    /**
     * Дымка линзы едет вместе с кадром: кадр у края притенён, долистал его до центра — он чистый, как сам снимок.
     * Раньше дымка была нарисована внутри плитки и не перерисовывалась при прокрутке — кадр оставался мутным и в центре.
     */
    @Test fun lensHazeLeavesTileScrolledToCenter() {
        show()
        val infos = ctl.grid.layoutInfo.visibleItemsInfo
        val far = infos.groupBy { it.row }.filter { it.value.size == 3 }.toSortedMap().values.first()[1]
        val id = far.key as Long
        val vp = ctl.grid.layoutInfo
        val farY = far.offset.y - vp.viewportStartOffset + far.size.height / 2f
        shot("haze-before")
        rule.mainClock.autoAdvance = false
        // ведём кадр от края к центру, как пальцем: несколько кадров прокрутки
        val total = vp.viewportSize.height / 2f - farY
        repeat(6) { rule.runOnIdle { ctl.grid.dispatchRawDelta(-total / 6f) }; rule.mainClock.advanceTimeByFrame() }
        rule.mainClock.advanceTimeBy(64); rule.waitForIdle()
        val b = shot("haze-after", settle = false)
        val r = checkNotNull(rule.runOnIdle { ctl.bounds(id) }) { "кадр ушёл с экрана" }
        val item = still.all.first { it.id == id }
        val thumb = still.lib.thumbnail(item, 256, android.os.CancellationSignal())!!
        val want = thumb.getPixel(thumb.width / 2, thumb.height / 2)
        val got = b.getPixel(r.center.x.toInt(), r.center.y.toInt())
        val diff = kotlin.math.abs(android.graphics.Color.red(want) - android.graphics.Color.red(got)) +
            kotlin.math.abs(android.graphics.Color.green(want) - android.graphics.Color.green(got)) +
            kotlin.math.abs(android.graphics.Color.blue(want) - android.graphics.Color.blue(got))
        assertTrue("кадр в центре остался в дымке: разница с самим снимком $diff", diff < 45)
        rule.mainClock.autoAdvance = true
    }

    /** Магниты: как ни листай, после остановки ближайший ряд встаёт ровно в центр экрана. */
    @Test fun rowsSnapToTheCenter() {
        show()
        val h = rule.onNodeWithTag("feed").fetchSemanticsNode().boundsInRoot.height
        for ((dy, ms) in listOf(-260f to 300L, 140f to 120L, -900f to 140L)) {
            rule.onNodeWithTag("feed").performTouchInput {
                swipe(androidx.compose.ui.geometry.Offset(width / 2f, height * 0.6f), androidx.compose.ui.geometry.Offset(width / 2f, height * 0.6f - dy), ms)
            }
            rule.mainClock.advanceTimeBy(2500); rule.waitForIdle()
            val centers = ctl.grid.layoutInfo.visibleItemsInfo.map { it.offset.y - ctl.grid.layoutInfo.viewportStartOffset + it.size.height / 2f }
            val nearest = centers.minBy { kotlin.math.abs(it - h / 2f) }
            assertEquals("ряд не встал в центр после прокрутки на $dy", h / 2f, nearest, 3f)
        }
    }

    /** Тап по кадру у края (под линзой он меньше своей ячейки и сдвинут к центру) открывает именно его. */
    @Test fun tapHitsWhatYouSee() {
        show()
        val tiles = ctl.grid.layoutInfo.visibleItemsInfo.filter { (it.key as? Long) != null }
        val top = tiles.minBy { it.offset.y }
        val r = rule.runOnIdle { ctl.visual(top) }
        // точка внутри видимого кадра, но у края его ячейки — там, где без учёта линзы был бы соседний кадр
        rule.onNodeWithTag("feed").performTouchInput { click(androidx.compose.ui.geometry.Offset(r.left + r.width * 0.15f, r.center.y)) }
        rule.mainClock.advanceTimeBy(600); rule.waitForIdle()
        assertEquals(top.key, still.viewing?.let { it.items[it.start].id })
    }

    /** Удалённое рассыпается и стекает к знаку, а потом исчезает из ленты. */
    @Test fun deletedTilesDissolveIntoMark() {
        show()
        val r = rule.runOnIdle { ctl.bounds(300L)!! }
        val before = shot("dissolve-before")
        rule.mainClock.autoAdvance = false
        rule.runOnIdle { still.removed(setOf(300L)) }
        // кадры по 16 мс: и часы Compose, и главный поток (отрисовка View идёт по его кадрам)
        repeat(20) {
            rule.mainClock.advanceTimeBy(16)
            org.robolectric.shadows.ShadowLooper.idleMainLooper(16, java.util.concurrent.TimeUnit.MILLISECONDS)
        }
        val mid = shot("dissolve-mid", settle = false)
        // низ кадра уже стёк: там, где был кадр, теперь фон или чужие пиксели
        val x = r.center.x.toInt(); val y = (r.bottom - r.height * 0.1f).toInt()
        assertTrue("кадр не рассыпается", before.getPixel(x, y) != mid.getPixel(x, y))
        rule.runOnIdle { (still.lib as FakeLibrary).gone += 300L }
        rule.mainClock.autoAdvance = true
        until { still.feed.cells.none { it.key == 300L } && still.leaving.isEmpty() }
    }

    /** Знак — диафрагма: на тап лопасти проворачиваются (картинка знака меняется), потом успокаиваются. */
    @Test fun markTurnsLikeShutterOnTap() {
        show()
        val r = rule.onNodeWithTag("still-mark").fetchSemanticsNode().boundsInRoot
        fun crop(b: Bitmap) = Bitmap.createBitmap(b, r.left.toInt(), r.top.toInt(), r.width.toInt(), r.height.toInt())
        val calm = crop(shot("mark-calm"))
        rule.mainClock.autoAdvance = false
        rule.onNodeWithTag("still-mark").performClick()
        repeat(8) { rule.mainClock.advanceTimeBy(16); org.robolectric.shadows.ShadowLooper.idleMainLooper(16, java.util.concurrent.TimeUnit.MILLISECONDS) }
        val turning = crop(shot("mark-turn", settle = false))
        assertTrue("знак не повернулся", !calm.sameAs(turning))
        rule.mainClock.autoAdvance = true
    }

    /**
     * Без доступа «Управление медиафайлами» первое удаление предлагает его один раз; «Не сейчас» — удаление
     * идёт как раньше, и больше Still не спрашивает.
     */
    @Test fun offersManageMediaOnceBeforeFirstDelete() {
        still.canManage = { false }   // как на телефоне до выдачи доступа
        show()
        val item = still.all.first { it.id == 300L }
        rule.runOnIdle { still.trash(listOf(item)) }
        rule.waitForIdle()
        rule.onNodeWithTag("manage-sheet").assertExists()
        shot("manage-sheet")
        rule.onNodeWithTag("manage-skip").performClick()
        rule.waitForIdle()
        assertEquals(null, still.askManage)
        rule.runOnIdle { still.trash(listOf(item)) }
        rule.waitForIdle()
        assertEquals("спросил второй раз", null, still.askManage)
    }

    /** Видео в просмотре запускается само: появляется полоса времени с паузой. */
    @Test fun videoStartsByItselfWithTimeBar() {
        show()
        rule.onNodeWithTag("tile-200").performClick()
        rule.mainClock.advanceTimeBy(1200); rule.waitForIdle()
        // снимок экрана тут не делаем: видеоповерхность (TextureView) Robolectric в картинку не рисует
        rule.onNodeWithTag("video-bar").assertExists()
    }

    /** Свайп вверх по фото — снизу выезжают подробности; вниз — закрываются, просмотр остаётся. */
    @Test fun swipeUpShowsDetails() {
        show()
        rule.onNodeWithTag("tile-300").performClick()
        rule.mainClock.advanceTimeBy(1200); rule.waitForIdle()
        rule.onNodeWithTag("viewer").performTouchInput { swipe(androidx.compose.ui.geometry.Offset(width / 2f, height * 0.6f), androidx.compose.ui.geometry.Offset(width / 2f, height * 0.25f), 250) }
        rule.mainClock.advanceTimeBy(1200); rule.waitForIdle()
        rule.onNodeWithTag("info-panel").assertExists()
        shot("info")
        rule.onNodeWithTag("viewer").performTouchInput { swipe(androidx.compose.ui.geometry.Offset(width / 2f, height * 0.3f), androidx.compose.ui.geometry.Offset(width / 2f, height * 0.7f), 250) }
        rule.mainClock.advanceTimeBy(1200); rule.waitForIdle()
        rule.onNodeWithTag("info-panel").assertDoesNotExist()
        assertTrue("закрылся сам просмотр", still.viewing != null)
    }

    /**
     * Непрерывный щипок: сводишь пальцы — сетка мельчает одним движением через ступени; кадр, на котором
     * были пальцы, остаётся под ними.
     */
    @Test fun pinchFlowsThroughStepsKeepingThePhotoUnderFingers() {
        // лента как у живого человека — сотни кадров, чтобы было куда держать кадр под пальцами
        val many = items() + (0 until 600).map { mk(10_000L + it, now - (60L + it) * day / 3) }
        still = Still(rule.activity, FakeLibrary(many.sortedByDescending { it.taken }), Index(null), MainScope(), now = { now })
        still.access = Access.FULL
        show()
        rule.runOnIdle { ctl.grid.requestScrollToItem(still.feed.cells.size / 2) }
        rule.mainClock.advanceTimeBy(800); rule.waitForIdle()
        val box = rule.onNodeWithTag("feed").fetchSemanticsNode().boundsInRoot
        val c = box.center
        val before = rule.runOnIdle { ctl.hit(c)?.key }
        rule.onNodeWithTag("feed").performTouchInput {
            pinch(
                androidx.compose.ui.geometry.Offset(c.x - width * 0.4f, c.y), androidx.compose.ui.geometry.Offset(c.x - width * 0.12f, c.y),
                androidx.compose.ui.geometry.Offset(c.x + width * 0.4f, c.y), androidx.compose.ui.geometry.Offset(c.x + width * 0.12f, c.y),
                600,
            )
        }
        rule.mainClock.advanceTimeBy(1500); rule.waitForIdle()
        // одним жестом — через несколько ступеней (3 → 4 → 5 → 6 → 8 → 10)
        until { still.feed.cols >= 8 }
        shot("pinch-end")
        val lo = ctl.grid.layoutInfo
        val under = lo.visibleItemsInfo.firstOrNull {
            val t = it.offset.y - lo.viewportStartOffset
            c.x >= it.offset.x && c.x < it.offset.x + it.size.width && c.y >= t && c.y < t + it.size.height
        }
        val near = lo.visibleItemsInfo.filter { kotlin.math.abs(it.offset.y - lo.viewportStartOffset + it.size.height / 2f - c.y) < it.size.height * 1.5f }.map { it.key }
        assertTrue("кадр ушёл из-под пальцев: был $before, под пальцами ${under?.key}", before in near)
    }

    /** Кадры щипка для глаз (build/pinch-frames): сводим пальцы по шагам и рисуем каждый шаг. */
    @Test fun pinchFrames() {
        val many = items() + (0 until 600).map { mk(10_000L + it, now - (60L + it) * day / 3) }
        still = Still(rule.activity, FakeLibrary(many.sortedByDescending { it.taken }), Index(null), MainScope(), now = { now })
        still.access = Access.FULL
        show()
        rule.runOnIdle { ctl.grid.requestScrollToItem(still.feed.cells.size / 2) }
        rule.mainClock.advanceTimeBy(800); rule.waitForIdle()
        val dir = File("build/pinch-frames").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
        val box = rule.onNodeWithTag("feed").fetchSemanticsNode().boundsInRoot
        val c = box.center; val w = box.width
        rule.mainClock.autoAdvance = false
        rule.onNodeWithTag("feed").performTouchInput {
            down(0, androidx.compose.ui.geometry.Offset(c.x - w * 0.42f, c.y)); down(1, androidx.compose.ui.geometry.Offset(c.x + w * 0.42f, c.y))
        }
        val n = 40
        for (i in 1..n) {
            // туда и обратно: 3 → 13 → 2 столбца одним касанием
            val t = i / n.toFloat()
            val half = if (t < 0.6f) w * (0.42f - 0.38f * (t / 0.6f)) else w * (0.04f + 0.44f * ((t - 0.6f) / 0.4f))
            rule.onNodeWithTag("feed").performTouchInput {
                moveTo(0, androidx.compose.ui.geometry.Offset(c.x - half, c.y)); moveTo(1, androidx.compose.ui.geometry.Offset(c.x + half, c.y))
            }
            repeat(2) { rule.mainClock.advanceTimeBy(16); org.robolectric.shadows.ShadowLooper.idleMainLooper(16, java.util.concurrent.TimeUnit.MILLISECONDS) }
            val b = shot("x", settle = false)
            File(dir, "%03d.png".format(i)).outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        rule.onNodeWithTag("feed").performTouchInput { up(0); up(1) }
        rule.mainClock.autoAdvance = true
    }

    /** Как у живого человека: первый палец уже поехал (лента едет), второй касается позже — это щипок. */
    @Test fun pinchWorksWhenSecondFingerLandsLate() {
        val many = items() + (0 until 600).map { mk(10_000L + it, now - (60L + it) * day / 3) }
        still = Still(rule.activity, FakeLibrary(many.sortedByDescending { it.taken }), Index(null), MainScope(), now = { now })
        still.access = Access.FULL
        show()
        val box = rule.onNodeWithTag("feed").fetchSemanticsNode().boundsInRoot
        val c = box.center; val w = box.width
        rule.onNodeWithTag("feed").performTouchInput {
            down(0, androidx.compose.ui.geometry.Offset(c.x - w * 0.3f, c.y))
            moveBy(0, androidx.compose.ui.geometry.Offset(0f, -40f))          // палец поехал раньше второго
            down(1, androidx.compose.ui.geometry.Offset(c.x + w * 0.3f, c.y - 40f))
            for (i in 1..12) {
                moveTo(0, androidx.compose.ui.geometry.Offset(c.x - w * (0.3f - 0.02f * i), c.y - 40f))
                moveTo(1, androidx.compose.ui.geometry.Offset(c.x + w * (0.3f - 0.02f * i), c.y - 40f))
            }
            up(0); up(1)
        }
        rule.mainClock.advanceTimeBy(1500); rule.waitForIdle()
        assertTrue("щипок не распознан: столбцов ${still.feed.cols}", still.feed.cols > 3)
    }

    /** Долгое нажатие на стопку выбирает все её кадры — «удалить» уносит в корзину стопку целиком, а не обложку. */
    @Test fun selectingStackSelectsAllItsPhotos() {
        show()
        rule.onNodeWithTag("tile-103").performTouchInput { longClick() }
        rule.waitForIdle()
        assertEquals(setOf(100L, 101L, 102L, 103L), still.selection)
        assertEquals(4, still.selected().size)
    }

    /** Корзина в просмотре без выданного доступа: просмотр остаётся открытым, сверху шторка про доступ. */
    @Test fun deleteInViewerKeepsViewerOpen() {
        still.canManage = { false }
        show()
        rule.onNodeWithTag("tile-300").performClick()
        rule.mainClock.advanceTimeBy(1200); rule.waitForIdle()
        rule.onNodeWithTag("viewer-Удалить").performClick()
        rule.mainClock.advanceTimeBy(1200); rule.waitForIdle()
        assertTrue("шторка не появилась", still.askManage != null)
        assertTrue("просмотр закрылся сам", still.viewing != null)
        // шторка видна поверх кадра: в центре кнопки «Разрешить» — её цвет, а не пиксели фото
        val b = shot("viewer-delete")
        val r = rule.onNodeWithTag("manage-allow").fetchSemanticsNode().boundsInRoot
        val px = b.getPixel(r.center.x.toInt(), r.center.y.toInt())
        val accent = com.eagalts.still.ui.LightPalette.accent
        val want = android.graphics.Color.rgb((accent.red * 255).toInt(), (accent.green * 255).toInt(), (accent.blue * 255).toInt())
        assertTrue("шторку закрывает просмотр", kotlin.math.abs(android.graphics.Color.red(px) - android.graphics.Color.red(want)) < 30 &&
            kotlin.math.abs(android.graphics.Color.blue(px) - android.graphics.Color.blue(want)) < 30)
    }

    @Test fun darkFeed() { show(dark = true); shot("feed-dark") }

    /**
     * Стопка в ленте выглядит стопкой: углы ячейки пустые (карточки с полями и повёрнуты), в центре — кадр;
     * у одиночного кадра угол — сам кадр.
     */
    @Test fun stackLooksLikeAPile() {
        show()
        val b = shot("stack-pile")
        val page = com.eagalts.still.ui.LightPalette.page
        fun isPage(px: Int) = kotlin.math.abs(android.graphics.Color.red(px) - (page.red * 255).toInt()) < 12 &&
            kotlin.math.abs(android.graphics.Color.green(px) - (page.green * 255).toInt()) < 12 &&
            kotlin.math.abs(android.graphics.Color.blue(px) - (page.blue * 255).toInt()) < 12
        // где плитки видны на самом деле (линза сдвигает и уменьшает их)
        val pile = rule.runOnIdle { ctl.bounds(103) }!!
        val single = rule.runOnIdle { ctl.bounds(300) }!!
        // середина левого края: у стопки там поле вокруг карточек, у одиночного кадра — сам кадр
        fun edge(r: androidx.compose.ui.geometry.Rect) = b.getPixel((r.left + r.width * 0.025f).toInt(), r.center.y.toInt())
        assertTrue("край стопки занят — стопка выглядит обычным кадром", isPage(edge(pile)))
        assertFalse("центр стопки пуст", isPage(b.getPixel(pile.center.x.toInt(), pile.center.y.toInt())))
        assertFalse("у одиночного кадра край пустой", isPage(edge(single)))
        // в ленте у стопки нет числа поверх кадра
        rule.onNodeWithText("4").assertDoesNotExist()
    }

    /**
     * Тап по стопке — она открывается как папка: все её кадры сеткой поверх ленты, сверху когда это было;
     * кадр открывается в просмотре и листаются только кадры стопки; «назад» — в стопку, ещё «назад» — в ленту.
     */
    @Test fun tapOpensStackLikeAFolder() {
        show()
        rule.onNodeWithTag("tile-103").performClick()
        rule.mainClock.advanceTimeBy(1200); rule.waitForIdle()
        // по порядку съёмки: в серии больший номер снят раньше
        val order = listOf(103L, 102L, 101L, 100L)
        assertEquals(order, still.stack?.members?.map { it.id })
        rule.onNodeWithText("4 фото").assertExists()
        // кадры легли сеткой в 3 столбца, на экране, по порядку съёмки
        val r = order.map { rule.onNodeWithTag("stack-$it").fetchSemanticsNode().boundsInRoot }
        assertTrue(r[0].left < r[1].left && r[1].left < r[2].left)
        assertTrue(r[3].top > r[0].bottom - 1f)
        val screen = rule.onNodeWithTag("stack").fetchSemanticsNode().boundsInRoot
        r.forEach { assertTrue("кадр стопки не на экране: $it", screen.contains(it.center)) }
        shot("stack-open")
        rule.onNodeWithTag("stack-102").performClick()
        rule.mainClock.advanceTimeBy(1200); rule.waitForIdle()
        assertEquals(order, still.viewing?.items?.map { it.id })
        assertEquals(1, still.viewing?.start)
        rule.runOnIdle { rule.activity.onBackPressedDispatcher.onBackPressed() }
        until(3000) { still.viewing == null }
        assertTrue("после просмотра стопка закрылась", still.stack != null)
        rule.runOnIdle { rule.activity.onBackPressedDispatcher.onBackPressed() }
        until(3000) { still.stack == null }
        rule.onNodeWithTag("stack").assertDoesNotExist()
    }

    /**
     * Стопка закрылась — лента сразу та же, что до открытия: не уменьшена, не притушена, стопка на месте. Раньше
     * размытие уходило уже после того, как снимок сел на место, и он на миг становился мутным.
     */
    @Test fun closingStackReturnsFeedInSameFrame() {
        show()
        val before = shot("stack-before")
        val pile = rule.runOnIdle { ctl.bounds(103) }!!
        rule.onNodeWithTag("tile-103").performClick()
        rule.mainClock.advanceTimeBy(1200); rule.waitForIdle()
        assertTrue("лента под стопкой не ушла вглубь", ctl.stackVeil > 0.99f)
        rule.mainClock.autoAdvance = false
        rule.runOnIdle { rule.activity.onBackPressedDispatcher.onBackPressed() }
        var frames = 0
        while (still.stack != null) { rule.mainClock.advanceTimeBy(16); frames++; check(frames < 200) }
        assertEquals("лента ещё притушена в кадре, где стопка закрылась", 0f, ctl.stackVeil, 0.001f)
        val after = shot("stack-after", settle = false)
        val c = pile.center
        val pb = before.getPixel(c.x.toInt(), c.y.toInt()); val pa = after.getPixel(c.x.toInt(), c.y.toInt())
        val d = kotlin.math.abs(android.graphics.Color.red(pb) - android.graphics.Color.red(pa)) +
            kotlin.math.abs(android.graphics.Color.green(pb) - android.graphics.Color.green(pa)) + kotlin.math.abs(android.graphics.Color.blue(pb) - android.graphics.Color.blue(pa))
        assertTrue("стопка в ленте не вернулась на место: разница $d", d < 24)
        rule.mainClock.autoAdvance = true
    }

    /**
     * Срок до «Потока» — ползунком в самом «Потоке»: по умолчанию 3 дня (скриншот двухдневной давности в ленте),
     * потянул к часу — он уходит из ленты; к трём месяцам — возвращаются и месячные.
     */
    @Test fun flowSliderChangesWhatLeaves() {
        show()
        fun inFeed(id: Long) = still.feed.cells.any { it.key == id }
        assertTrue(inFeed(500L))
        rule.runOnIdle { still.open(Filter.Flow) }
        until { still.feed.cells.isNotEmpty() }
        rule.runOnIdle { ctl.grid.requestScrollToItem(still.feed.cells.size) }
        rule.mainClock.advanceTimeBy(500); rule.waitForIdle()
        rule.onNodeWithText("3 дня").assertExists()
        shot("flow-timer")
        rule.onNodeWithTag("flow-slider").performTouchInput { swipeLeft(startX = right * 0.4f, endX = left) }
        rule.mainClock.advanceTimeBy(500); rule.waitForIdle()
        rule.onNodeWithText("1 час").assertExists()
        assertEquals(com.eagalts.still.media.Ephemeral.HOUR, still.flowTtl)
        rule.runOnIdle { still.open(null) }
        until { still.feed.cells.none { it.key == 500L } }
        rule.runOnIdle { still.open(Filter.Flow) }
        until { still.feed.cells.isNotEmpty() }
        rule.runOnIdle { ctl.grid.requestScrollToItem(still.feed.cells.size) }
        rule.mainClock.advanceTimeBy(500); rule.waitForIdle()
        rule.onNodeWithTag("flow-slider").performTouchInput { swipeRight(startX = left + 10f, endX = right) }
        rule.mainClock.advanceTimeBy(500); rule.waitForIdle()
        rule.onNodeWithText("3 месяца").assertExists()
        rule.runOnIdle { still.open(null) }
        until { still.feed.cells.any { it.key == 600L } }
    }

    /**
     * «Назад» жестом от края: стопка сворачивается за пальцем ещё до того, как его отпустили (лента под ней
     * проясняется); вернул палец — разворачивается обратно; отпустил — закрывается. Раньше полсекунды жеста ничего
     * не происходило, а потом стопка схлопывалась рывком.
     */
    @Test fun predictiveBackFoldsStackWithFinger() {
        show()
        rule.onNodeWithTag("tile-103").performClick()
        rule.mainClock.advanceTimeBy(1200); rule.waitForIdle()
        val d = rule.activity.onBackPressedDispatcher
        fun ev(p: Float) = androidx.activity.BackEventCompat(1000f, 1200f, p, androidx.activity.BackEventCompat.EDGE_RIGHT)
        rule.runOnIdle { d.dispatchOnBackStarted(ev(0f)) }
        rule.runOnIdle { d.dispatchOnBackProgressed(ev(0.3f)) }
        rule.runOnIdle { d.dispatchOnBackProgressed(ev(0.6f)) }
        rule.mainClock.advanceTimeBy(100); rule.waitForIdle()
        assertTrue("стопка не сворачивается за пальцем: ${ctl.stackVeil}", ctl.stackVeil < 0.8f)
        assertTrue(still.stack != null)
        rule.runOnIdle { d.dispatchOnBackCancelled() }
        rule.mainClock.advanceTimeBy(1000); rule.waitForIdle()
        assertTrue("передумал — стопка не развернулась: ${ctl.stackVeil}", ctl.stackVeil > 0.99f && still.stack != null)
        rule.runOnIdle { d.dispatchOnBackStarted(ev(0f)) }
        rule.runOnIdle { d.dispatchOnBackProgressed(ev(0.5f)) }
        rule.runOnIdle { d.onBackPressed() }
        until(3000) { still.stack == null }
    }

    /**
     * Стопка складывается физически: в последнем кадре закрытия разложенные кадры совпадают со стопкой в ленте —
     * верхний снимок сверху, два выглядывающих под своими углами, кромка и тень (скачка, когда стопка «встаёт» в
     * ленту, нет); и открытие начинается ровно с неё же. Раньше сетка сжималась в мозаику, а сверху стопки был другой кадр.
     */
    @Test fun stackFoldsIntoThePileExactly() {
        show()
        val pile = rule.runOnIdle { ctl.bounds(103) }!!
        val before = shot("pile-before")
        rule.mainClock.autoAdvance = false
        rule.onNodeWithTag("tile-103").performClick()
        rule.mainClock.advanceTimeByFrame(); rule.mainClock.advanceTimeByFrame()
        val first = shot("pile-open-first", settle = false)
        for (k in 0 until 12) { rule.mainClock.advanceTimeBy(48); shot("film2-open-%02d".format(k), settle = false) }
        rule.mainClock.advanceTimeBy(1000)
        rule.runOnIdle { rule.activity.onBackPressedDispatcher.onBackPressed() }
        var last: android.graphics.Bitmap? = null
        var k = 0
        while (still.stack != null) {
            rule.mainClock.advanceTimeByFrame()
            if (still.stack != null) last = shot("film2-close-%02d".format(k++), settle = false)
            check(k < 200)
        }
        // через кадр после закрытия — уже настоящая стопка в ленте (а не карточки)
        rule.mainClock.advanceTimeByFrame(); rule.mainClock.advanceTimeByFrame()
        val after = shot("pile-after", settle = false)
        fun diff(a: android.graphics.Bitmap, b: android.graphics.Bitmap): Double {
            var sum = 0L; var n = 0
            var y = pile.top.toInt(); while (y < pile.bottom.toInt()) { var x = pile.left.toInt(); while (x < pile.right.toInt()) {
                val p = a.getPixel(x, y); val q = b.getPixel(x, y)
                sum += kotlin.math.abs(android.graphics.Color.red(p) - android.graphics.Color.red(q)) + kotlin.math.abs(android.graphics.Color.green(p) - android.graphics.Color.green(q)) + kotlin.math.abs(android.graphics.Color.blue(p) - android.graphics.Color.blue(q))
                n++; x += 3 }; y += 3 }
            return sum.toDouble() / n
        }
        val landing = diff(last!!, after); val start = diff(first, before)
        println("PILE landing=$landing start=$start")
        assertTrue("в последнем кадре закрытия стопка не совпала со стопкой в ленте: $landing", landing < 12.0)
        assertTrue("открытие не начинается со стопки: $start", start < 25.0)
        rule.mainClock.autoAdvance = true
    }

    /** Тап мимо кадров стопки — она складывается обратно. */
    @Test fun tapOutsideClosesStack() {
        show()
        rule.onNodeWithTag("tile-103").performClick()
        rule.mainClock.advanceTimeBy(1200); rule.waitForIdle()
        val screen = rule.onNodeWithTag("stack").fetchSemanticsNode().boundsInRoot
        rule.onNodeWithTag("stack").performTouchInput { click(androidx.compose.ui.geometry.Offset(screen.width / 2f, screen.height * 0.85f)) }
        until(3000) { still.stack == null }
    }

    /**
     * Скриншот во весь экран в просмотре не лежит под подписями и кнопками: пока они видны, кадр стоит между ними
     * (раньше «Оставить» наезжало на заголовок скриншота, сердечко — на его кнопки); тап — подписи прячутся, кадр
     * разворачивается во весь экран.
     */
    @Test fun tallShotStandsBetweenViewerChrome() {
        show()
        rule.runOnIdle { ctl.grid.requestScrollToItem(still.feed.cells.indexOfFirst { it.key == 500L }) }
        rule.mainClock.advanceTimeBy(500); rule.waitForIdle()
        rule.onNodeWithTag("tile-500").performClick()
        rule.mainClock.advanceTimeBy(1500); rule.waitForIdle()
        shot("viewer-tall")
        val top = rule.onNodeWithTag("viewer-top").fetchSemanticsNode().boundsInRoot
        val bottom = rule.onNodeWithTag("viewer-bottom").fetchSemanticsNode().boundsInRoot
        val r = com.eagalts.still.ui.ViewerProbe.rect!!
        assertTrue("кадр залез под подписи сверху: ${r.top} < ${top.bottom}", r.top >= top.bottom - 1f)
        assertTrue("кадр залез под кнопки снизу: ${r.bottom} > ${bottom.top}", r.bottom <= bottom.top + 1f)
        val box = rule.onNodeWithTag("viewer").fetchSemanticsNode().boundsInRoot
        rule.onNodeWithTag("viewer").performTouchInput { click(androidx.compose.ui.geometry.Offset(box.width / 2f, box.height / 2f)) }
        rule.mainClock.advanceTimeBy(1500); rule.waitForIdle()
        shot("viewer-tall-full")
        val full = com.eagalts.still.ui.ViewerProbe.rect!!
        assertTrue("без подписей кадр не развернулся: ${full.height} из ${box.height}", full.height > r.height + 20f)
    }

    /**
     * Вход в «Поток» — под самым свежим рядом: долистал до конца и потянул дальше — «Поток» открылся; чуть потянул
     * и отпустил — лента вернулась на место, ничего не открылось.
     */
    @Test fun pullPastNewestOpensFlow() {
        show()
        rule.onNodeWithTag("flow-door").assertExists()
        rule.onNodeWithText("Поток").assertExists()
        shot("flow-door")
        val box = rule.onNodeWithTag("feed").fetchSemanticsNode().boundsInRoot
        // чуть-чуть — не открывается
        rule.onNodeWithTag("feed").performTouchInput {
            down(androidx.compose.ui.geometry.Offset(box.width / 2f, box.height * 0.6f))
            repeat(5) { moveBy(androidx.compose.ui.geometry.Offset(0f, -12f)) }
            up()
        }
        rule.mainClock.advanceTimeBy(800); rule.waitForIdle()
        assertEquals(null, still.filter)
        // дальше порога — «Поток»
        rule.onNodeWithTag("feed").performTouchInput {
            down(androidx.compose.ui.geometry.Offset(box.width / 2f, box.height * 0.8f))
            repeat(30) { moveBy(androidx.compose.ui.geometry.Offset(0f, -25f)) }
            up()
        }
        until(3000) { still.filter == Filter.Flow }
    }

    @Test fun tapOpensViewerAndBackFliesHome() {
        show()
        rule.onNodeWithTag("tile-300").performClick()
        rule.waitForIdle()
        assertEquals(300L, still.viewing?.items?.get(still.viewing!!.start)?.id)
        shot("viewer")
        rule.runOnIdle { rule.activity.onBackPressedDispatcher.onBackPressed() }
        until(3000) { still.viewing == null }
    }

    @Test fun longPressSelectsAndOffersSimilar() {
        show()
        rule.runOnIdle { ctl.grid.requestScrollToItem(still.feed.cells.indexOfFirst { it.key == 500L }) }
        rule.mainClock.advanceTimeBy(500); rule.waitForIdle()
        rule.onNodeWithTag("tile-500").performTouchInput { longClick() }
        rule.waitForIdle()
        assertEquals(setOf(500L), still.selection)
        rule.onNodeWithTag("similar").assertExists()
        shot("selection")
        rule.onNodeWithTag("similar").performClick()
        rule.waitForIdle()
        // все скриншоты, включая ушедшие в «Поток»
        assertEquals((setOf(500L, 501L, 502L) + (600L..604L)).toSet(), still.selection)
    }

    @Test fun markOpensSheetAndSearchFilters() {
        show()
        rule.onNodeWithTag("still-mark").performClick()
        rule.waitForIdle()
        shot("sheet")
        rule.runOnIdle { still.search("видео") }
        until(3000) { still.filter is Filter.Search && still.feed.cells.isNotEmpty() && still.feed.cells.filterIsInstance<Cell.Tile>().all { it.item.video } }
        val tiles = still.feed.cells.filterIsInstance<Cell.Tile>()
        assertTrue(tiles.isNotEmpty() && tiles.all { it.item.video })
        shot("search")
        rule.onNodeWithTag("filter-close").performClick()
        rule.waitForIdle()
        assertEquals(null, still.filter)
    }
}
