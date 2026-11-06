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
                    assertTrue(Dates.label(now - d * Ephemeral.DAY, lv, now).isNotBlank())
                }
                assertTrue(Dates.scrub(now).isNotBlank() && Dates.moment(now).isNotBlank())
            }
        } finally { java.util.Locale.setDefault(saved) }
    }
}
