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

    /** Заголовок подробностей: «Суббота, 7 ноября 2026 · 05:41» — без «г.», которое ICU ставит в русском. */
    fun full(ms: Long): String = (fmt("EEEEdMMMM", ms) + " " + year(ms)).replaceFirstChar { it.titlecase(locale) } + " · " + fmt("HHmm", ms)

    /** Время над фото: «6 ноября 2026, 10:15». */
    fun moment(ms: Long): String = fmt("dMMMM", ms) + " " + year(ms) + ", " + fmt("HHmm", ms)

    private fun year(ms: Long) = Calendar.getInstance().apply { timeInMillis = ms }.get(Calendar.YEAR)

    private fun fmt(skeleton: String, ms: Long): String =
        android.icu.text.SimpleDateFormat(DateFormat.getBestDateTimePattern(locale, skeleton), locale).format(Date(ms))
}
