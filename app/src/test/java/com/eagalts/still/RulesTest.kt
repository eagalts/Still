package com.eagalts.still

import com.eagalts.still.media.Classify
import com.eagalts.still.media.DayPart
import com.eagalts.still.media.Ephemeral
import com.eagalts.still.media.Hue
import com.eagalts.still.media.MediaItem
import com.eagalts.still.media.Query
import com.eagalts.still.media.Similar
import com.eagalts.still.media.Source
import com.eagalts.still.media.Moments
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
        // приложения сохраняют картинки в DCIM/<своя папка> — это не камера (мем из Reddit ушёл бы в «Поток»)
        assertEquals(Source.OTHER, Classify.source("DCIM/Reddit/", "Reddit", "com.reddit.frontpage"))
        assertEquals(Source.OTHER, Classify.source("DCIM/Pinterest/", "Pinterest", null))
        // а камеры некоторых телефонов снимают в DCIM/100ANDRO, DCIM/100MEDIA, кадры прямо в DCIM/
        assertEquals(Source.CAMERA, Classify.source("DCIM/100ANDRO/", "100ANDRO", null))
        assertEquals(Source.CAMERA, Classify.source("DCIM/", "DCIM", null))
        assertEquals(Source.CAMERA, Classify.source("DCIM/Camera/", "Camera", "com.google.android.apps.photos"))
    }

    /** Временное уходит в «Поток» через выбранный срок: по умолчанию 3 дня, ползунком — от часа до трёх месяцев. */
    @Test fun temporaryLeavesAfterChosenTime() {
        val shot = item(1, Source.SCREENSHOT, added = now - 2 * day)
        val old = item(2, Source.SCREENSHOT, added = now - 4 * day)
        val camera = item(3, added = now - 400 * day)
        assertTrue(Ephemeral.inFeed(shot, now, emptySet()))
        assertFalse(Ephemeral.inFeed(old, now, emptySet()))
        assertTrue(Ephemeral.inFeed(camera, now, emptySet()))
        // оставленное — навсегда
        assertTrue(Ephemeral.inFeed(old, now, setOf(2L)))
        assertEquals(day, Ephemeral.left(shot, now, emptySet()))
        assertEquals(null, Ephemeral.left(camera, now, emptySet()))
        assertEquals(null, Ephemeral.left(old, now, setOf(2L)))
        // срок на ползунке: час — скриншот двухчасовой давности уже в «Потоке», три месяца — и старый в ленте
        val fresh = item(4, Source.SCREENSHOT, added = now - 2 * Ephemeral.HOUR)
        assertFalse(Ephemeral.inFeed(fresh, now, emptySet(), ttl = Ephemeral.STEPS.first()))
        assertTrue(Ephemeral.inFeed(old, now, emptySet(), ttl = Ephemeral.STEPS.last()))
        assertEquals(Ephemeral.DEFAULT, Ephemeral.STEPS[6])
    }

    /** Бумага с камеры уходит в «Поток», как скриншот; камера без бумаги — нет. */
    @Test fun paperFromCameraLeavesLikeScreenshots() {
        val receipt = item(1, added = now - 4 * day); val sunset = item(2, added = now - 4 * day)
        assertFalse(Ephemeral.inFeed(receipt, now, emptySet(), paper = setOf(1L)))
        assertTrue(Ephemeral.inFeed(sunset, now, emptySet(), paper = setOf(1L)))
    }

    /**
     * Решение «информация» — по средней оценке стопки: прогулка в тумане, где два серых кадра из восьми похожи на
     * бумагу, остаётся в ленте; лекция, где часть экранов чуть не дотянула до порога, уходит целиком; одиночный
     * кадр — по своей оценке.
     */
    @Test fun paperIsDecidedPerStack() {
        val walk = com.eagalts.still.media.Entry(item(1), (1L..8L).map { item(it) })
        val lecture = com.eagalts.still.media.Entry(item(20), (20L..23L).map { item(it) })
        val single = com.eagalts.still.media.Entry(item(30), listOf(item(30)))
        val near = com.eagalts.still.media.Entry(item(31), listOf(item(31)))
        // магазин: три снимка клавиатуры и фото экрана телефона с карточкой товара — уходит только экран
        val shop = com.eagalts.still.media.Entry(item(40), (40L..43L).map { item(it) })
        val score = mapOf(1L to -6f, 2L to -5f, 3L to 4f, 4L to -7f, 5L to 3.5f, 6L to -6f, 7L to -4f, 8L to -5f,
            20L to 5f, 21L to 2.6f, 22L to 6f, 23L to 2.8f, 30L to 3.5f, 31L to 2.9f, 40L to -3f, 41L to -2f, 42L to 5.2f, 43L to -4f)
        val out = com.eagalts.still.media.Paperwork.flowIds(listOf(walk, lecture, single, near, shop), 3f, 3f, { score[it] })
        assertEquals(setOf(20L, 21L, 22L, 23L, 30L, 42L), out)
    }

    /**
     * Обновили распознаватель — решения «бумага или нет», сделанные старыми весами, забываются и считаются заново
     * (иначе фото экранов, признанные в прошлой версии «не бумагой», так и остались бы в ленте); новые — переживают
     * перезапуск.
     */
    @Test fun paperDecisionsFollowModelVersion() {
        val f = java.io.File.createTempFile("scenes", ".bin").apply { delete() }
        val a = com.eagalts.still.media.Vectors(f).apply { load() }
        a.usePaperModel(1L); a.put(7L, ByteArray(1024) { 1 }); a.putPaper(7L, -2.5f); a.saveIfDirty()
        val b = com.eagalts.still.media.Vectors(f).apply { load() }
        b.usePaperModel(1L)
        assertEquals(-2.5f, b.paper(7L))
        b.usePaperModel(2L)
        assertEquals(null, b.paper(7L))
        assertTrue(b.has(7L))
        f.delete()
    }

    /** Скрытые размытием кадры помнятся после перезапуска (зашифрованный индекс); «Показать» снимает скрытие. */
    @Test fun veiledSurvivesRestart() {
        val f = java.io.File.createTempFile("index", ".bin").apply { delete() }
        com.eagalts.still.media.Index(f).apply { veil(listOf(1L, 2L), true); saveIfDirty() }
        val b = com.eagalts.still.media.Index(f)
        assertEquals(setOf(1L, 2L), b.veiledIds())
        b.veil(listOf(1L), false)
        assertEquals(setOf(2L), b.veiledIds())
        f.delete()
    }

    /** Распознавание в Kotlin даёт те же оценки, что и обученное: бумаги и фото экранов — высоко, обычные фото — низко. */
    @Test fun paperworkMatchesTraining() {
        val pw = java.io.File("src/main/assets/paperwork.bin").inputStream().use(com.eagalts.still.media.Paperwork::read)!!
        val b = java.nio.ByteBuffer.wrap(java.io.File("src/test/resources/paperwork_fixture.bin").readBytes()).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        repeat(b.int) { i ->
            val v = ByteArray(1024); b.get(v); val want = b.float
            assertEquals("кадр $i", want, pw.score(v), 0.02f)
            assertEquals("кадр $i", i < 6, pw.isPaper(v))
        }
    }

    // ——— стопки: выходы по ритму съёмки, сцены по смыслу кадра ———

    private val rnd = java.util.Random(7)
    /** Сцена — «что на кадре»: у кадров одной сцены векторы рядом, у разных — в разные стороны (как у модели). */
    private fun scene(): FloatArray = FloatArray(1024) { rnd.nextGaussian().toFloat() }
    private fun shot(c: FloatArray): ByteArray =
        com.eagalts.still.media.Embedder.quantize(FloatArray(c.size) { c[it] + 0.45f * rnd.nextGaussian().toFloat() })

    /** Кадры по возрастанию времени с паузами [gaps] (минуты): первый — в [start]. */
    private fun timeline(start: Long, gaps: List<Int>, from: Long = 1): List<MediaItem> {
        var t = start
        return gaps.mapIndexed { i, g -> t += g * 60_000L; item(from + i, taken = t) }
    }

    private fun build(asc: List<MediaItem>, vec: Map<Long, ByteArray>) = Moments.build(asc.asReversed(), { vec[it] })

    /** Экраны на паре по кадру в 2–4 минуты — одна стопка; доска посреди пары — своя, экраны она не рвёт. */
    @Test fun sceneSurvivesOtherShotsInTheMiddle() {
        val screens = scene(); val board = scene()
        val asc = timeline(now, listOf(0) + List(19) { 2 + it % 3 })
        val vec = asc.associate { it.id to shot(if (it.id == 8L || it.id == 9L) board else screens) }
        val e = build(asc, vec)
        assertEquals(listOf(18, 2), e.filter { it.stacked }.map { it.members.size }.sortedDescending())
        assertEquals(2, e.size)
    }

    /** Прогулка 18:27–20:39 с паузами до 40 минут — один выход, один туман — одна стопка; чужой кадр посреди — сам по себе. */
    @Test fun longWalkIsOneStackAndCounterStaysAlone() {
        val fog = scene(); val counter = scene()
        val asc = timeline(now, listOf(0, 12, 35, 6, 40, 18, 9, 4, 3, 5))
        val vec = asc.associate { it.id to shot(fog) } + (99L to shot(counter))
        val withCounter = (asc + item(99, taken = asc[4].taken + 60_000)).sortedBy { it.taken }
        val e = build(withCounter, vec)
        assertEquals(10, e.single { it.stacked }.members.size)
        assertTrue(e.any { !it.stacked && it.cover.id == 99L })
    }

    /**
     * Пауза — довод, а не стена: браузер 18:26–18:28 (кадр в минуту), час с лишним перерыва, и снова браузер
     * 19:35–21:25 — одна стопка. Раньше пауза в 67 минут резала их на две, хотя на кадрах одно и то же.
     */
    @Test fun sameContentAcrossLongPauseIsOneStack() {
        val browser = scene()
        val asc = timeline(now, listOf(0, 1, 1, 67) + List(17) { 5 + it % 3 })
        val e = build(asc, asc.associate { it.id to shot(browser) })
        assertEquals(1, e.size)
        assertEquals(21, e[0].members.size)
        // а другое содержимое после такой же паузы — отдельно
        val other = scene()
        val e2 = build(asc, asc.associate { it.id to shot(if (it.id <= 3L) other else browser) })
        assertEquals(listOf(18, 3), e2.map { it.members.size })
    }

    /**
     * Одно дело за четверть часа — одна стопка, даже если на кадрах разное: посудомойка в 22:35, угол стены и трубы
     * в 22:47–22:50. Модель видит три разные сцены, но снято подряд — значит, одно. Раньше было три группы.
     */
    @Test fun shortTaskIsOneStackEvenIfContentDiffers() {
        // одна кухня, один свет: у сцен есть общая часть — между ними расстояние 0.92–0.96, как у настоящих кадров
        val room = scene()
        fun part() = scene().let { v -> FloatArray(v.size) { v[it] + 0.38f * room[it] } }
        val dishwasher = part(); val corner = part(); val pipes = part()
        val asc = timeline(now, listOf(0, 12, 1, 1, 2))
        val vec = mapOf(1L to shot(dishwasher), 2L to shot(corner), 3L to shot(pipes), 4L to shot(corner), 5L to shot(pipes))
        val e = build(asc, vec)
        assertEquals(1, e.size)
        assertEquals(5, e[0].members.size)
    }

    /** Одинаковое утром и вечером — разные выходы: стопки не склеиваются через полдня. */
    @Test fun sameContentInDifferentOutingsIsTwoStacks() {
        val desk = scene()
        val asc = timeline(now, listOf(0, 3, 3, 3)) + timeline(now + 8 * 3_600_000L, listOf(0, 3, 3), from = 10)
        val e = build(asc, asc.associate { it.id to shot(desk) })
        assertEquals(listOf(3, 4), e.map { it.members.size })
    }

    /** Пауза режет выход, только если она намного длиннее обычных пауз вокруг: 50 минут после серии по 2 — режет. */
    @Test fun outingsFollowShootingRhythm() {
        val min = 60_000L
        fun times(vararg gaps: Int): LongArray { var t = 0L; return LongArray(gaps.size) { t += gaps[it] * min; t } }
        assertEquals(listOf(0, 0, 0, 1, 1, 1), Moments.outings(times(0, 2, 2, 50, 2, 2)).toList())
        // прогулка с паузами по 30–40 минут — один выход
        assertEquals(1, Moments.outings(times(0, 35, 30, 40, 38, 33)).distinct().size)
        // больше 4 часов — всегда граница
        assertEquals(listOf(0, 1, 2, 3), Moments.outings(times(0, 300, 300, 300)).toList())
    }

    /** Сцены не обязаны идти подряд: A A B B A A — стопка из четырёх A и стопка из двух B. */
    @Test fun scenesNeedNotBeContiguous() {
        val a = scene(); val b = scene()
        val asc = timeline(now, listOf(0, 4, 4, 4, 4, 4))
        val vec = asc.associate { it.id to shot(if (it.id == 3L || it.id == 4L) b else a) }
        val e = build(asc, vec)
        assertEquals(setOf(setOf(1L, 2L, 5L, 6L), setOf(3L, 4L)), e.map { s -> s.members.map { it.id }.toSet() }.toSet())
        // стопка стоит на месте своего самого свежего кадра, кадры внутри — в порядке съёмки
        assertEquals(6L, e.first().members.last().id)
        assertEquals(listOf(1L, 2L, 5L, 6L), e.first().members.map { it.id })
    }

    /** Обложка — самое тяжёлое фото; видео в стопке участвуют, но обложкой становятся, только если фото нет. */
    @Test fun coverIsHeaviestPhotoAndVideosJoin() {
        val c = scene()
        val asc = listOf(item(1, taken = now, size = 900), item(2, taken = now + 60_000, size = 5000, video = true),
            item(3, taken = now + 120_000, size = 2000), item(4, taken = now + 180_000, size = 1500))
        val e = build(asc, asc.associate { it.id to shot(c) })
        assertEquals(1, e.size)
        assertEquals(3L, e[0].cover.id)
        assertEquals(4, e[0].members.size)
    }

    /** Без векторов (ещё не посчитаны) — каждый кадр сам по себе, ничего не склеивается наугад. */
    @Test fun withoutVectorsNothingStacks() {
        val asc = timeline(now, listOf(0, 1, 1, 1))
        assertEquals(4, build(asc, emptyMap()).size)
    }

    /** Сжатие вектора в байты не меняет его направления: косинус до и после — почти 1. */
    @Test fun quantizedVectorKeepsDirection() {
        val v = scene(); val q = com.eagalts.still.media.Embedder.quantize(v)
        var dot = 0.0; var nv = 0.0; var nq = 0.0
        for (i in v.indices) { dot += v[i] * q[i]; nv += v[i] * v[i]; nq += q[i] * q[i].toDouble() }
        assertTrue(dot / Math.sqrt(nv * nq) > 0.999)
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
