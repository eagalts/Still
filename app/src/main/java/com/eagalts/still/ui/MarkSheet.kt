package com.eagalts.still.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eagalts.still.Filter
import com.eagalts.still.Still
import com.eagalts.still.media.MediaItem
import com.eagalts.still.t

/**
 * Шторка знака: строка «как помнишь» (правило 5), под ней — тихие подборки и альбомы. Выбрал — лента
 * отфильтровалась на месте, шторка уехала.
 */
@Composable
fun MarkSheet(s: Still, ctl: FeedController) {
    val pal = LocalPalette.current
    // стекло: лента под шторкой размыта (см. App), шторка полупрозрачна и окрашена светом кадров
    val glass = android.os.Build.VERSION.SDK_INT >= 31
    val sheetColor = tint(pal.sheet, ctl.ambient, 0.16f).copy(alpha = if (glass) 0.8f else 1f)
    Sheet(s.sheet, onDismiss = { s.sheet = false }, color = sheetColor) {
        val p = LocalPalette.current
        var text by remember { mutableStateOf("") }
        val counts = remember(s.all) { s.counts() }
        val albums = remember(s.all) { s.albums() }
        Column(Modifier.imePadding()) {
            if (s.access == com.eagalts.still.Access.PARTIAL) Text(
                t("Still видит только часть фото · Открыть все"), color = p.accent, fontSize = 13.sp, fontFamily = UiFont,
                modifier = Modifier.padding(start = 24.dp, bottom = 10.dp).bouncy(0.96f) { s.askAccess() },
            )
            Row(
                Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(50)).background(p.chip)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Search, null, tint = p.muted, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Box(Modifier.weight(1f)) {
                    if (text.isEmpty()) Text(t("синее летом 2023 вечером"), color = p.muted.copy(alpha = 0.7f), fontSize = 16.sp, fontFamily = UiFont)
                    CompositionLocalProvider(LocalTextSelectionColors provides TextSelectionColors(p.accent, p.accent.copy(alpha = 0.28f))) {
                        BasicTextField(
                            text, { text = it },
                            singleLine = true,
                            textStyle = TextStyle(color = p.text, fontSize = 16.sp, fontFamily = UiFont),
                            cursorBrush = SolidColor(p.accent),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = { if (text.isNotBlank()) { s.search(text); text = "" } }),
                            modifier = Modifier.fillMaxWidth().testTag("search-field"),
                        )
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Chip(Icons.Flow, t("Поток"), counts[Filter.Flow]) { s.open(Filter.Flow) }
                Chip(Icons.Video, t("Видео"), counts[Filter.Videos]) { s.open(Filter.Videos) }
                Chip(Icons.Screen, t("Скриншоты"), counts[Filter.Screens]) { s.open(Filter.Screens) }
                Chip(Icons.Heart, t("Избранное"), counts[Filter.Favorites]) { s.open(Filter.Favorites) }
                Chip(Icons.Trash, t("Недавно удалённые"), null) { s.open(Filter.Trash) }
            }
            if (albums.isNotEmpty()) {
                Text(
                    t("Альбомы"), color = p.muted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, fontFamily = UiFont,
                    modifier = Modifier.padding(start = 24.dp, top = 18.dp, bottom = 6.dp),
                )
                Column(Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState())) {
                    albums.forEach { (f, cover) -> AlbumRow(s, f, cover) }
                }
            }
            Spacer(Modifier.height(6.dp))
        }
    }
}

@Composable
private fun Chip(icon: ImageVector, label: String, count: Int?, onClick: () -> Unit) {
    val p = LocalPalette.current
    Row(
        Modifier.bouncy(0.92f, onClick = onClick).clip(RoundedCornerShape(50)).background(p.chip)
            .padding(start = 12.dp, end = 14.dp, top = 9.dp, bottom = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = p.text, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(7.dp))
        Text(label, color = p.text, fontSize = 14.sp, fontWeight = FontWeight.Medium, fontFamily = UiFont)
        if (count != null && count > 0) {
            Spacer(Modifier.width(6.dp))
            Text(count.toString(), color = p.muted, fontSize = 13.sp, fontFamily = UiFont)
        }
    }
}

@Composable
private fun AlbumRow(s: Still, f: Filter.Album, cover: MediaItem) {
    val p = LocalPalette.current
    val px = with(androidx.compose.ui.platform.LocalDensity.current) { 44.dp.roundToPx() }
    val thumb = rememberThumb(s, cover, px)
    Row(
        Modifier.fillMaxWidth().bouncy(0.97f) { s.open(f) }.padding(horizontal = 20.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(44.dp).clip(SquircleShape(Radius.xs)).background(p.tile).drawBehind {
            thumb.value?.let { drawCover(it, Rect(0f, 0f, size.width, size.height)) }
        })
        Spacer(Modifier.width(14.dp))
        Text(f.name, color = p.text, fontSize = 16.sp, fontFamily = UiFont, maxLines = 1, modifier = Modifier.weight(1f))
    }
}
