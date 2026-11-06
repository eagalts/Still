package com.eagalts.still.media

import java.util.Calendar
import java.util.TimeZone

/**
 * Правило 5 — строка вместо меню. Пишешь как помнишь: «синее летом 2023 вечером», «видео март», «скрины telegram».
 * Всё локально, по уже известным данным: время съёмки, тип, источник, средний цвет, папка. Слова, которых парсер
 * не знает, ищутся в названиях папок. Понимает русский и английский.
 */
class Query(
    val years: Set<Int> = emptySet(),
    val months: Set<Int> = emptySet(),     // 0…11
    val dayParts: Set<DayPart> = emptySet(),
    val colors: Set<Hue> = emptySet(),
    val video: Boolean? = null,
    val sources: Set<Source> = emptySet(),
    val words: List<String> = emptyList(),
) {
    val empty: Boolean get() = years.isEmpty() && months.isEmpty() && dayParts.isEmpty() && colors.isEmpty() &&
        video == null && sources.isEmpty() && words.isEmpty()
    val needsColor: Boolean get() = colors.isNotEmpty()

    /** [color] — средний цвет кадра (ARGB) или null, если ещё не посчитан: тогда по цвету кадр не проходит. */
    fun matches(it: MediaItem, color: Int?, tz: TimeZone = TimeZone.getDefault()): Boolean {
        if (video != null && it.video != video) return false
        if (sources.isNotEmpty() && it.source !in sources) return false
        if (years.isNotEmpty() || months.isNotEmpty() || dayParts.isNotEmpty()) {
            val c = Calendar.getInstance(tz).apply { timeInMillis = it.taken }
            if (years.isNotEmpty() && c.get(Calendar.YEAR) !in years) return false
            if (months.isNotEmpty() && c.get(Calendar.MONTH) !in months) return false
            if (dayParts.isNotEmpty() && DayPart.of(c.get(Calendar.HOUR_OF_DAY)) !in dayParts) return false
        }
        if (colors.isNotEmpty()) {
            if (color == null) return false
            if (Hue.of(color) !in colors) return false
        }
        if (words.isNotEmpty()) {
            val b = it.bucket.lowercase()
            if (words.any { w -> w !in b }) return false
        }
        return true
    }

    companion object {
        private val monthStems = listOf(
            listOf("янв", "jan"), listOf("фев", "feb"), listOf("мар", "mar"), listOf("апр", "apr"),
            listOf("мая", "май", "may"), listOf("июн", "jun"), listOf("июл", "jul"), listOf("авг", "aug"),
            listOf("сен", "sep"), listOf("окт", "oct"), listOf("ноя", "nov"), listOf("дек", "dec"),
        )
        private val seasons = listOf(
            listOf("зим", "winter") to setOf(11, 0, 1),
            listOf("весн", "spring") to setOf(2, 3, 4),
            listOf("лет", "summer") to setOf(5, 6, 7),
            listOf("осен", "осень", "autumn", "fall") to setOf(8, 9, 10),
        )
        private val parts = listOf(
            listOf("утр", "morning") to DayPart.MORNING,
            listOf("днём", "днем", "день", "afternoon") to DayPart.DAY,
            listOf("вечер", "evening") to DayPart.EVENING,
            listOf("ноч", "night") to DayPart.NIGHT,
        )
        private val hues = listOf(
            listOf("красн", "red") to Hue.RED, listOf("оранж", "orange") to Hue.ORANGE,
            listOf("жёлт", "желт", "yellow") to Hue.YELLOW, listOf("зелён", "зелен", "green") to Hue.GREEN,
            listOf("голуб", "син", "blue") to Hue.BLUE, listOf("фиолет", "purple", "violet") to Hue.PURPLE,
            listOf("розов", "pink") to Hue.PINK, listOf("бел", "white") to Hue.WHITE,
            listOf("чёрн", "черн", "black") to Hue.BLACK, listOf("сер", "gray", "grey") to Hue.GRAY,
        )
        private val kinds = listOf(
            listOf("видео", "video", "ролик") to Kind.VIDEO, listOf("фото", "photo", "снимк", "кадр") to Kind.PHOTO,
            listOf("скрин", "screenshot", "screen") to Kind.SCREEN, listOf("загруз", "скачан", "download") to Kind.DOWNLOAD,
            listOf("telegram", "телеграм", "whatsapp", "ватсап", "вотсап", "мессенджер", "messenger", "чат") to Kind.CHAT,
            listOf("камер", "camera") to Kind.CAMERA,
        )
        private enum class Kind { VIDEO, PHOTO, SCREEN, DOWNLOAD, CHAT, CAMERA }

        fun parse(text: String): Query {
            val years = HashSet<Int>(); val months = HashSet<Int>(); val dp = HashSet<DayPart>()
            val colors = HashSet<Hue>(); val src = HashSet<Source>(); val words = ArrayList<String>()
            var video: Boolean? = null
            for (raw in text.lowercase().split(Regex("[\\s,.;]+"))) {
                val w = raw.trim('«', '»', '"', '\'')
                if (w.isEmpty()) continue
                // предлоги и связки — не слова поиска
                if (w in setOf("в", "во", "на", "с", "со", "и", "за", "in", "on", "at", "the", "of", "and", "from")) continue
                val year = w.toIntOrNull()
                if (year != null && year in 1900..2100) { years += year; continue }
                fun has(stems: List<String>) = stems.any { w.startsWith(it) }
                val month = monthStems.indexOfFirst(::has)
                if (month >= 0) { months += month; continue }
                val season = seasons.firstOrNull { has(it.first) }
                if (season != null) { months += season.second; continue }
                val part = parts.firstOrNull { has(it.first) }
                if (part != null) { dp += part.second; continue }
                val hue = hues.firstOrNull { has(it.first) }
                if (hue != null) { colors += hue.second; continue }
                when (kinds.firstOrNull { has(it.first) }?.second) {
                    Kind.VIDEO -> { video = true; continue }
                    Kind.PHOTO -> { video = false; continue }
                    Kind.SCREEN -> { src += Source.SCREENSHOT; continue }
                    Kind.DOWNLOAD -> { src += Source.DOWNLOAD; continue }
                    Kind.CHAT -> {
                        src += Source.MESSENGER
                        // «telegram» ищем и по папке; общее «чат»/«мессенджер» — нет
                        if (!w.startsWith("мессендж") && !w.startsWith("messeng") && w != "чат") words += normalizeApp(w)
                        continue
                    }
                    Kind.CAMERA -> { src += Source.CAMERA; continue }
                    null -> {}
                }
                words += w
            }
            return Query(years, months, dp, colors, video, src, words)
        }

        /** «телеграм» → «telegram»: папки мессенджеров называются по-английски. */
        private fun normalizeApp(w: String) = when {
            w.startsWith("телеграм") -> "telegram"
            w.startsWith("ватсап") || w.startsWith("вотсап") -> "whatsapp"
            else -> w
        }
    }
}

enum class DayPart {
    MORNING, DAY, EVENING, NIGHT;
    companion object {
        fun of(hour: Int) = when (hour) { in 5..10 -> MORNING; in 11..16 -> DAY; in 17..21 -> EVENING; else -> NIGHT }
    }
}

/** Грубый цвет кадра: на память люди помнят «синее», а не оттенок. */
enum class Hue {
    RED, ORANGE, YELLOW, GREEN, BLUE, PURPLE, PINK, WHITE, BLACK, GRAY;
    companion object {
        fun of(argb: Int): Hue {
            val hsv = FloatArray(3)
            android.graphics.Color.colorToHSV(argb, hsv)
            val (h, s, v) = Triple(hsv[0], hsv[1], hsv[2])
            return when {
                v < 0.18f -> BLACK
                s < 0.14f -> if (v > 0.82f) WHITE else GRAY
                h < 15f || h >= 345f -> RED
                h < 40f -> ORANGE
                h < 68f -> YELLOW
                h < 165f -> GREEN
                h < 255f -> BLUE
                h < 290f -> PURPLE
                else -> PINK
            }
        }
    }
}
