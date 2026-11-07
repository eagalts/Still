package com.eagalts.still

import com.eagalts.still.media.Classify
import com.eagalts.still.media.DayPart
import com.eagalts.still.media.Ephemeral
import com.eagalts.still.media.Hue
import com.eagalts.still.media.MediaItem
import com.eagalts.still.media.Query
import com.eagalts.still.media.Similar
import com.eagalts.still.media.Source
import com.eagalts.still.media.Stacks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Calendar
import java.util.TimeZone

/** Правила поведения ленты: источник, временное, стопки, «ещё такие», строка поиска. */
@RunWith(RobolectricTestRunner::class)
class RulesTest {
    private val day = Ephemeral.DAY
    private val now = 1_800_000_000_000L

    private fun item(
        id: Long, source: Source = Source.CAMERA, taken: Long = now, added: Long = taken, size: Long = 1000,
        video: Boolean = false, bucketId: Long = 1, bucket: String = "Camera", w: Int = 4000, h: Int = 3000,
    ) = MediaItem(id, video, taken, added, source, bucketId, bucket, "image/jpeg", w, h, size)

    @Test fun sourceByPathAndOwner() {
        assertEquals(Source.CAMERA, Classify.source("DCIM/Camera/", "Camera", "com.google.android.GoogleCamera"))
        assertEquals(Source.SCREENSHOT, Classify.source("DCIM/Screenshots/", "Screenshots", null))
        assertEquals(Source.SCREENSHOT, Classify.source("Pictures/Screenshots", "Screenshots", null))
        assertEquals(Source.MESSENGER, Classify.source("Pictures/Telegram/", "Telegram", null))
        assertEquals(Source.MESSENGER, Classify.source("Android/media/com.whatsapp/WhatsApp/Media/", "WhatsApp Images", "com.whatsapp"))
        assertEquals(Source.DOWNLOAD, Classify.source("Download/", "Download", null))
        assertEquals(Source.OTHER, Classify.source("Pictures/Edits/", "Edits", null))
    }

    @Test fun temporaryLeavesAfterFourteenDays() {
        val shot = item(1, Source.SCREENSHOT, added = now - 13 * day)
        val old = item(2, Source.SCREENSHOT, added = now - 15 * day)
        val camera = item(3, added = now - 400 * day)
        assertTrue(Ephemeral.inFeed(shot, now, emptySet()))
        assertFalse(Ephemeral.inFeed(old, now, emptySet()))
        assertTrue(Ephemeral.inFeed(camera, now, emptySet()))
        // оставленное — навсегда
        assertTrue(Ephemeral.inFeed(old, now, setOf(2L)))
        assertEquals(1, Ephemeral.daysLeft(shot, now, emptySet()))
        assertEquals(null, Ephemeral.daysLeft(camera, now, emptySet()))
        assertEquals(null, Ephemeral.daysLeft(old, now, setOf(2L)))
    }

    @Test fun burstStacksAndBestIsHeaviest() {
        val items = listOf(
            item(1, taken = now, size = 900), item(2, taken = now - 500, size = 2000), item(3, taken = now - 1200, size = 1500),
            item(4, taken = now - 60_000),
        )
        val e = Stacks.build(items) { 0L }
        assertEquals(2, e.size)
        assertEquals(3, e[0].members.size)
        assertEquals(2L, e[0].cover.id)
        assertFalse(e[1].stacked)
    }

    @Test fun nearDuplicatesStackOnlyByHash() {
        val a = item(1, taken = now); val b = item(2, taken = now - 40_000); val c = item(3, taken = now - 80_000)
        val hashes = mapOf(1L to 0x0F0F0F0F0F0F0F0FL, 2L to 0x0F0F0F0F0F0F0F0EL, 3L to 0x7777777700000000L)
        val e = Stacks.build(listOf(a, b, c)) { hashes[it] ?: 0L }
        assertEquals(listOf(2, 1), e.map { it.members.size })
        // без отпечатков — только серия по времени
        assertEquals(3, Stacks.build(listOf(a, b, c)) { 0L }.size)
    }

    /** Похожие кадры в окне 5–10 минут — одна стопка; чужой снимок между ними её не рвёт. */
    @Test fun similarShotsMinutesApartStackEvenWithOtherShotBetween() {
        val a = item(1, taken = now); val other = item(2, taken = now - 2 * 60_000); val a2 = item(3, taken = now - 7 * 60_000)
        val far = item(4, taken = now - 25 * 60_000)
        val hashes = mapOf(1L to 0x0F0F0F0F0F0F0F0FL, 2L to 0x7070707070707070L, 3L to 0x0F0F0F0F0F0F0F0EL, 4L to 0x0F0F0F0F0F0F0F0FL)
        val e = Stacks.build(listOf(a, other, a2, far)) { hashes[it] ?: 0L }
        val stack = e.first { it.stacked }
        assertEquals(setOf(1L, 3L), stack.members.map { it.id }.toSet())
        // через 25 минут — уже другой момент, даже если кадр такой же
        assertTrue(e.any { !it.stacked && it.cover.id == 4L })
        assertTrue(e.any { !it.stacked && it.cover.id == 2L })
    }

    /** При заметной разнице отпечатков складываем, только если совпадает и основной цвет. */
    @Test fun looserMatchNeedsSameColor() {
        val a = item(1, taken = now); val b = item(2, taken = now - 5 * 60_000)
        val h = mapOf(1L to 0L.inv(), 2L to (0L.inv() xor 0x3FFL))   // 10 бит разницы
        val blue = android.graphics.Color.rgb(40, 90, 220); val red = android.graphics.Color.rgb(220, 40, 40)
        assertEquals(1, Stacks.build(listOf(a, b), color = { blue }) { h[it]!! }.size)
        assertEquals(2, Stacks.build(listOf(a, b), color = { if (it == 1L) blue else red }) { h[it]!! }.size)
    }

    @Test fun videoAndOtherSourcesNeverStack() {
        val e = Stacks.build(listOf(item(1), item(2, video = true, taken = now - 100), item(3, Source.SCREENSHOT, taken = now - 200))) { 0L }
        assertEquals(3, e.size)
    }

    @Test fun similarFindsSameKindOfNoiseButNeverCamera() {
        val shots = (1L..5L).map { item(it, Source.SCREENSHOT, bucketId = 9, w = 1080, h = 2400) }
        val wide = item(6, Source.SCREENSHOT, bucketId = 9, w = 2400, h = 1080)   // тот же формат, повёрнут — та же форма
        val chat = item(7, Source.MESSENGER, bucketId = 5, w = 1080, h = 2400)
        val all = shots + wide + chat + item(8)
        assertEquals(setOf(1L, 2L, 3L, 4L, 5L, 6L), Similar.of(listOf(shots[0]), all).map { it.id }.toSet())
        assertTrue(Similar.of(listOf(item(8)), all).isEmpty())
        // смешанный выбор — подсказки нет
        assertTrue(Similar.of(listOf(shots[0], chat), all).isEmpty())
    }

    @Test fun queryParsesHowPeopleRemember() {
        val q = Query.parse("синее летом 2023 вечером")
        assertEquals(setOf(Hue.BLUE), q.colors)
        assertEquals(setOf(5, 6, 7), q.months)
        assertEquals(setOf(2023), q.years)
        assertEquals(setOf(DayPart.EVENING), q.dayParts)
        assertTrue(q.words.isEmpty())

        val v = Query.parse("видео март")
        assertEquals(true, v.video); assertEquals(setOf(2), v.months)

        val tg = Query.parse("скрины телеграм")
        assertEquals(setOf(Source.SCREENSHOT, Source.MESSENGER), tg.sources)
        assertEquals(listOf("telegram"), tg.words)

        val en = Query.parse("blue summer evening")
        assertEquals(setOf(Hue.BLUE), en.colors); assertEquals(setOf(DayPart.EVENING), en.dayParts)
    }

    @Test fun queryMatchesTimeAndColor() {
        val tz = TimeZone.getTimeZone("UTC")
        val c = Calendar.getInstance(tz).apply { set(2023, Calendar.JULY, 14, 19, 30) }
        val it = item(1, taken = c.timeInMillis)
        val q = Query.parse("синее летом 2023 вечером")
        assertTrue(q.matches(it, android.graphics.Color.rgb(40, 90, 220), tz))
        assertFalse(q.matches(it, android.graphics.Color.rgb(220, 40, 40), tz))
        // цвет ещё не посчитан — по цвету не проходит
        assertFalse(q.matches(it, null, tz))
        assertFalse(Query.parse("зимой").matches(it, null, tz))
    }

    @Test fun huesAreWhatPeopleCallThem() {
        assertEquals(Hue.RED, Hue.of(android.graphics.Color.rgb(210, 30, 40)))
        assertEquals(Hue.GREEN, Hue.of(android.graphics.Color.rgb(40, 160, 60)))
        assertEquals(Hue.BLUE, Hue.of(android.graphics.Color.rgb(30, 110, 230)))
        assertEquals(Hue.WHITE, Hue.of(android.graphics.Color.rgb(245, 245, 240)))
        assertEquals(Hue.BLACK, Hue.of(android.graphics.Color.rgb(10, 10, 12)))
    }

    @Test fun motionPhotoLengthFromXmp() {
        // новый формат Pixel: контейнер с элементом MotionPhoto
        val xmp = """<x:xmpmeta><rdf:Description GCamera:MotionPhoto="1"><Container:Directory><rdf:Seq>
            <rdf:li><Container:Item Item:Mime="image/jpeg" Item:Semantic="Primary" Item:Length="0" Item:Padding="0"/></rdf:li>
            <rdf:li><Container:Item Item:Mime="video/mp4" Item:Semantic="MotionPhoto" Item:Length="2345678"/></rdf:li>
            </rdf:Seq></Container:Directory></rdf:Description></x:xmpmeta>"""
        assertEquals(2345678L, com.eagalts.still.media.Motion.videoLength(xmp))
        // атрибуты в другом порядке
        assertEquals(42L, com.eagalts.still.media.Motion.videoLength("""<Container:Item Item:Length="42" Item:Semantic="MotionPhoto" Item:Mime="video/mp4"/>"""))
        // старый формат
        assertEquals(1000L, com.eagalts.still.media.Motion.videoLength("""GCamera:MicroVideo="1" GCamera:MicroVideoOffset="1000""""))
        // обычное фото
        assertEquals(null, com.eagalts.still.media.Motion.videoLength("<x:xmpmeta/>"))
        assertEquals(null, com.eagalts.still.media.Motion.videoLength(null))
    }

    @Test fun detailsFormatting() {
        assertEquals(55.7558 to 37.6173, com.eagalts.still.media.DetailsLoader.parseIso6709("+55.7558+037.6173/"))
        assertEquals(-33.8688 to 151.2093, com.eagalts.still.media.DetailsLoader.parseIso6709("-33.8688+151.2093+012.000/"))
        assertEquals("1/120", com.eagalts.still.media.DetailsLoader.shutter(1.0 / 120))
        assertEquals("2", com.eagalts.still.media.DetailsLoader.shutter(2.0))
    }

    @Test fun pinchStepsAreContinuousAndStable() {
        val Z = com.eagalts.still.ui.Zoom
        assertEquals(3, Z.nearest(3.2f)); assertEquals(5, Z.nearest(4.6f)); assertEquals(13, Z.nearest(20f))
        // раскладка — ступень не крупнее пальцев: сетка рисуется крупнее себя, края заполнены
        assertEquals(4, Z.next(3, 3.5f)); assertEquals(8, Z.next(3, 7.2f)); assertEquals(2, Z.next(8, 1.9f))
        // у самой ступени раскладка не дребезжит
        assertEquals(5, Z.next(5, 5.1f)); assertEquals(5, Z.next(5, 4.9f))
        // линза слабеет плавно
        assertEquals(1f, Z.lens(3f), 0.001f); assertEquals(0.5f, Z.lens(5f), 0.001f); assertEquals(0f, Z.lens(8f), 0.001f)
        assertTrue(Z.lens(4f) in 0.5f..1f)
    }

    @Test fun crashTextHasNoPaths() {
        val s = CrashReporter.scrub("java.io.FileNotFoundException: /storage/emulated/0/DCIM/Camera/PXL_1.jpg (content://media/external/images/1)")
        assertFalse("/storage" in s || "PXL" in s || "media/external" in s)
    }
}

/** Подписи дат во всех языках Still — шаблоны Android бывают с символами, которых нет в java.text (краш 0.1.0 до выпуска). */
@RunWith(RobolectricTestRunner::class)
class DatesTest {
    @Test fun labelsInEveryLanguage() {
        val now = 1_800_000_000_000L
        val saved = java.util.Locale.getDefault()
        try {
            for (l in listOf("ru") + I18n.languages) {
                java.util.Locale.setDefault(java.util.Locale.forLanguageTag(l))
                for (lv in Level.entries) for (d in listOf(0L, 3L, 400L)) {
                    if (lv != Level.NONE) assertTrue(Dates.label(now - d * Ephemeral.DAY, lv).isNotBlank())
                }
                assertTrue(Dates.scrub(now).isNotBlank() && Dates.moment(now).isNotBlank() && Dates.full(now).isNotBlank())
            }
        } finally { java.util.Locale.setDefault(saved) }
    }
}
