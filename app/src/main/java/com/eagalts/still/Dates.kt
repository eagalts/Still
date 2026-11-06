package com.eagalts.still

import android.text.format.DateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Подписи времени в ленте: в языке телефона, коротко, без лишнего года. */
object Dates {
    private val locale: Locale get() = Locale.getDefault()

    fun label(ms: Long, level: Level, now: Long): String {
        val c = Calendar.getInstance().apply { timeInMillis = ms }
        val n = Calendar.getInstance().apply { timeInMillis = now }
        val sameYear = c.get(Calendar.YEAR) == n.get(Calendar.YEAR)
        return when (level) {
            Level.YEARS -> c.get(Calendar.YEAR).toString()
            Level.MONTHS -> fmt(if (sameYear) "LLLL" else "LLLLy", ms).replaceFirstChar { it.titlecase(locale) }
            Level.DAYS -> {
                val days = dayNumber(n) - dayNumber(c)
                when {
                    days == 0L -> t("Сегодня")
                    days == 1L -> t("Вчера")
                    else -> fmt(if (sameYear) "EEEEdMMMM" else "dMMMMy", ms).replaceFirstChar { it.titlecase(locale) }
                }
            }
        }
    }

    /** Крупная подпись быстрой перемотки: «Ноябрь 2024». */
    fun scrub(ms: Long): String = fmt("LLLLy", ms).replaceFirstChar { it.titlecase(locale) }

    /** Время в просмотре: «5 ноября 2024, 18:42». */
    fun moment(ms: Long): String = fmt("dMMMMyHHmm", ms)

    private fun dayNumber(c: Calendar) = (c.timeInMillis + c.get(Calendar.ZONE_OFFSET) + c.get(Calendar.DST_OFFSET)) / 86_400_000L

    // ICU, а не java.text: шаблоны Android для русского содержат «cccc» (день недели отдельно), java.text на них падает
    private fun fmt(skeleton: String, ms: Long): String =
        android.icu.text.SimpleDateFormat(DateFormat.getBestDateTimePattern(locale, skeleton), locale).format(Date(ms))
}
