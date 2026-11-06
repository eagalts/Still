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
    private fun paint(it: MediaItem, w: Int, h: Int): Bitmap {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val hue = ((it.id * 47) % 360).toFloat()
        val c1 = android.graphics.Color.HSVToColor(floatArrayOf(hue, 0.55f, 0.9f))
        val c2 = android.graphics.Color.HSVToColor(floatArrayOf((hue + 40) % 360, 0.7f, 0.55f))
        Canvas(b).drawRect(0f, 0f, w.toFloat(), h.toFloat(), Paint().apply { shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), c1, c2, Shader.TileMode.CLAMP) })
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
        assertTrue(months.isNotEmpty() && months.all { Regex("\\d{4}").containsMatchIn(it) && it.any(Char::isLetter) })
        shot("zoom-months")
        rule.runOnIdle { still.zoomTo(13) }
        until { still.feed.cols == 13 }
        val years = still.feed.cells.filterIsInstance<Cell.Header>().map { it.label }
        assertTrue(years.isNotEmpty() && years.all { Regex("\\d{4}").matches(it) })
        shot("zoom-years")
    }

    /** Мозаика: между соседними кадрами нет зазора — на стыке столбцов пиксель кадра, а не фона. */
    @Test fun mosaicHasNoGaps() {
        show()
        val b = shot("mosaic")
        val tile = b.width / 3
        val y = b.height / 2
        val seam = b.getPixel(tile, y)
        assertTrue("на стыке фон, а не кадр", seam != android.graphics.Color.WHITE)
    }

    /**
     * Физика: на быстрой прокрутке кадры сужаются поперёк движения — на стыке столбцов проступает фон.
     * Остановилась — пружинят обратно, стык снова сплошной.
     */
    @Test fun fastScrollSquashesTiles() {
        show()
        rule.mainClock.autoAdvance = false
        rule.runOnIdle { ctl.velocity = 400f }
        rule.mainClock.advanceTimeByFrame(); rule.waitForIdle()
        val fast = shot("physics-fast", settle = false)
        val y = fast.height / 2
        val tile = fast.width / 3
        assertEquals("на скорости стык не разошёлся", android.graphics.Color.WHITE, fast.getPixel(tile, y))
        rule.mainClock.autoAdvance = true
        rule.runOnIdle { ctl.velocity = 0f }
        val calm = shot("physics-calm")
        assertTrue(calm.getPixel(tile, y) != android.graphics.Color.WHITE)
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
