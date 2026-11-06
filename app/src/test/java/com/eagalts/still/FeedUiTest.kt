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
import androidx.compose.ui.test.onNodeWithTag
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
        still = Still(rule.activity, FakeLibrary(items()), Index(null), MainScope(), now = { now })
        still.access = Access.FULL
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
        assertTrue("в обычной ленте подписей нет", cells.none { it is Cell.Header })
        // от старого к новому: последняя плитка — свежая серия
        assertEquals(103L, (cells.last() as Cell.Tile).item.id)
        val tiles = cells.filterIsInstance<Cell.Tile>().map { it.item.taken }
        assertEquals(tiles.sorted(), tiles)
        // и лента открылась на ней: плитка на экране, у нижнего края
        val r = rule.onNodeWithTag("tile-103").fetchSemanticsNode().boundsInRoot
        val h = rule.activity.window.decorView.height
        assertTrue("свежее не внизу: ${r.bottom} из $h", r.bottom > h * 0.7f)
        shot("feed-bottom")
    }

    @Test fun zoomOutShowsMonthsThenOnlyYears() {
        show()
        rule.runOnIdle { still.zoomTo(8) }
        until { still.feed.cols == 8 }
        val months = still.feed.cells.filterIsInstance<Cell.Header>().map { it.label }
        assertTrue(months.toString(), months.isNotEmpty() && months.all { Regex("^\\p{L}+ \\d{4}$").matches(it) })
        shot("zoom-months")
        rule.runOnIdle { still.zoomTo(13) }
        until { still.feed.cols == 13 }
        val years = still.feed.cells.filterIsInstance<Cell.Header>().map { it.label }
        assertTrue(years.isNotEmpty() && years.all { Regex("\\d{4}").matches(it) })
        shot("zoom-years")
    }

    /** Сетка как у Apple: между кадрами тонкая линия фона — есть, но не толще 2 dp. */
    @Test fun thinGapsLikeApple() {
        show()
        val b = shot("grid")
        val y = b.height / 2
        val page = android.graphics.Color.WHITE
        // ищем линию у стыка первого и второго столбца
        val near = (b.width / 3 - 8..b.width / 3 + 8).filter { b.getPixel(it, y) == page }
        val d = rule.activity.resources.displayMetrics.density
        assertTrue("линии между кадрами нет", near.isNotEmpty())
        assertTrue("линия толще 2 dp: ${near.size}px", near.size <= (2 * d).toInt() + 1)
    }

    /**
     * Физика: на быстрой прокрутке кадры тянутся вдоль движения — картинка меняется, — но полотно не рвётся:
     * ни на стыке столбцов, ни между рядами не проступает фон. Остановилась — всё как было.
     */
    @Test fun fastScrollStretchesWithoutTearing() {
        show()
        val calm0 = shot("physics-calm")
        rule.mainClock.autoAdvance = false
        rule.runOnIdle { ctl.velocity = 400f }
        rule.mainClock.advanceTimeByFrame(); rule.waitForIdle()
        val fast = shot("physics-fast", settle = false)
        val tile = fast.width / 3
        val page = android.graphics.Color.WHITE
        // стыки столбцов и рядов по всей высоте сетки, кроме самых краёв
        // разрыв — это фон там, где в покое был кадр (тонкие линии сетки на своих местах не в счёт)
        var torn = 0; var changed = 0
        for (y in fast.height / 8 until fast.height * 3 / 4 step 3) for (x in listOf(tile / 2, tile + tile / 2)) {
            if (fast.getPixel(x, y) == page && calm0.getPixel(x, y) != page) torn++
            if (fast.getPixel(x, y) != calm0.getPixel(x, y)) changed++
        }
        assertEquals("полотно порвалось", 0, torn)
        assertTrue("на скорости ничего не тянется", changed > 20)
        rule.mainClock.autoAdvance = true
        rule.runOnIdle { ctl.velocity = 0f }
        val calm = shot("physics-calm2")
        assertEquals(calm0.getPixel(tile / 2, fast.height / 2), calm.getPixel(tile / 2, fast.height / 2))
    }

    /** Удалённое рассыпается и стекает к знаку, а потом исчезает из ленты. */
    @Test fun deletedTilesDissolveIntoMark() {
        show()
        val r = rule.onNodeWithTag("tile-300").fetchSemanticsNode().boundsInRoot
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

    @Test fun darkFeed() { show(dark = true); shot("feed-dark") }

    @Test fun tapOnStackFansItOut() {
        show()
        rule.onNodeWithTag("tile-103").performClick()
        until { still.feed.cells.any { it.key == 100L } }
        val ids = still.feed.cells.filterIsInstance<Cell.Tile>().map { it.item.id }
        assertTrue(ids.containsAll(listOf(100L, 101L, 102L, 103L)))
        shot("stack-open")
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
