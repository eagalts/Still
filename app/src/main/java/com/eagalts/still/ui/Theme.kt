package com.eagalts.still.ui

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import com.eagalts.still.R

/**
 * Палитра Still (подход из Plume, `ui/Theme.kt`). Фон ленты нейтральный — фото сами дают цвет,
 * тёплая бумага Plume искажала бы их. В тёмной теме фон чёрный (OLED: пиксель выключен, фото «висят»).
 */
@Immutable
data class Palette(
    val dark: Boolean,
    val page: Color,      // фон ленты
    val sheet: Color,     // шторки
    val tile: Color,      // пустая плитка, пока миниатюра декодируется
    val chip: Color,      // таблетки в шторке
    val text: Color,
    val muted: Color,
    val faint: Color,     // тонкие разделители и подписи третьего плана
    val scrim: Color,
    val accent: Color,
)

val LightPalette = Palette(
    dark = false,
    page = Color(0xFFFFFFFF),
    sheet = Color(0xFFF4F3F6),
    tile = Color(0xFFEDECF0),
    chip = Color(0xFFFFFFFF),
    text = Color(0xFF16151A),
    muted = Color(0xFF7D7A85),
    faint = Color(0x1A16151A),
    scrim = Color(0x5216151A),
    accent = Color(0xFF4A3AA8),
)

val DarkPalette = Palette(
    dark = true,
    page = Color(0xFF000000),
    sheet = Color(0xFF151419),
    tile = Color(0xFF16151A),
    chip = Color(0xFF24232A),
    text = Color(0xFFF2F0F5),
    muted = Color(0xFF8F8B99),
    faint = Color(0x1FFFFFFF),
    scrim = Color(0x8C000000),
    accent = Color(0xFFA99BFF),
)

val LocalPalette = staticCompositionLocalOf { LightPalette }

/** Manrope (OFL) — шрифт Plume: спокойный гротеск для тихих подписей. */
@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
val UiFont = FontFamily(
    listOf(300 to FontWeight.Light, 400 to FontWeight.Normal, 500 to FontWeight.Medium, 600 to FontWeight.SemiBold, 700 to FontWeight.Bold)
        .map { (w, fw) -> androidx.compose.ui.text.font.Font(R.font.manrope, fw, variationSettings = FontVariation.Settings(FontVariation.weight(w))) }
)
