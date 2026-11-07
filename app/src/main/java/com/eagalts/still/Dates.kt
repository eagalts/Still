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

    /**
     * Заголовок стопки: «7 ноября · 18:27–20:39»; год — только если не этот; через полночь —
     * «7 ноября 23:10 – 8 ноября 01:20».
     */
    fun span(from: Long, to: Long, now: Long = System.currentTimeMillis()): String {
        val y = year(from) != year(now)
        fun day(ms: Long) = fmt("dMMMM", ms) + if (y) " " + year(ms) else ""
        val a = Calendar.getInstance().apply { timeInMillis = from }; val b = Calendar.getInstance().apply { timeInMillis = to }
        val sameDay = a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
        return when {
            !sameDay -> day(from) + " " + fmt("HHmm", from) + " – " + day(to) + " " + fmt("HHmm", to)
            fmt("HHmm", from) == fmt("HHmm", to) -> day(from) + " · " + fmt("HHmm", from)
            else -> day(from) + " · " + fmt("HHmm", from) + "–" + fmt("HHmm", to)
        }
    }

    private fun year(ms: Long) = Calendar.getInstance().apply { timeInMillis = ms }.get(Calendar.YEAR)

    private fun fmt(skeleton: String, ms: Long): String =
        android.icu.text.SimpleDateFormat(DateFormat.getBestDateTimePattern(locale, skeleton), locale).format(Date(ms))
}
