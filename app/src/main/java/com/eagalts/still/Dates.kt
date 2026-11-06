package com.eagalts.still

import android.text.format.DateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Подписи времени: в языке телефона, коротко. */
object Dates {
    private val locale: Locale get() = Locale.getDefault()

    /** Подпись над группой при отдалённой ленте: «Ноябрь 2024» или «2024». */
    fun label(ms: Long, level: Level): String {
        val c = Calendar.getInstance().apply { timeInMillis = ms }
        return when (level) {
            Level.YEARS -> c.get(Calendar.YEAR).toString()
            Level.MONTHS -> monthYear(ms)
            Level.NONE -> ""
        }
    }

    /** Крупная подпись быстрой перемотки: «Ноябрь 2024». */
    fun scrub(ms: Long): String = monthYear(ms)

    /** «Апрель 2026» — без «г.», которое ICU добавляет в русском шаблоне месяца с годом. */
    private fun monthYear(ms: Long): String {
        val c = Calendar.getInstance().apply { timeInMillis = ms }
        return fmt("LLLL", ms).replaceFirstChar { it.titlecase(locale) } + " " + c.get(Calendar.YEAR)
    }

    /** Время в просмотре: «5 ноября 2024, 18:42». */
    fun moment(ms: Long): String = fmt("dMMMMyHHmm", ms)

    // ICU, а не java.text: шаблоны Android для русского содержат «cccc» (день недели отдельно), java.text на них падает
    private fun fmt(skeleton: String, ms: Long): String =
        android.icu.text.SimpleDateFormat(DateFormat.getBestDateTimePattern(locale, skeleton), locale).format(Date(ms))
}
