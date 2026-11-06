package com.eagalts.still.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * Линейные иконки 24×24, линия 1.75, скруглённые концы (стиль и часть путей — из Plume, `ui/PlumeIcons.kt`).
 */
object Icons {
    private fun line(name: String, vararg paths: String, fill: Boolean = false): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            paths.forEach { d ->
                addPath(
                    pathData = addPathNodes(d),
                    fill = if (fill) SolidColor(Color.Black) else null,
                    stroke = SolidColor(Color.Black),
                    strokeLineWidth = 1.75f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                )
            }
        }.build()

    val Share = line("share", "M12 3.5v11", "M8 7.5l4-4 4 4", "M6 11.5v7a2 2 0 0 0 2 2h8a2 2 0 0 0 2-2v-7")
    val Trash = line("trash", "M4.5 6.5h15", "M9.5 6.5V4.5h5v2", "M6.5 6.5l.9 12.6a1.5 1.5 0 0 0 1.5 1.4h6.2a1.5 1.5 0 0 0 1.5-1.4l.9-12.6", "M10 10.5v6", "M14 10.5v6")
    private const val HEART = "M12 20s-7.5-4.6-7.5-10.2A4.3 4.3 0 0 1 12 7.2a4.3 4.3 0 0 1 7.5 2.6C19.5 15.4 12 20 12 20z"
    val Heart = line("heart", HEART)
    val HeartFilled = line("heartFilled", HEART, fill = true)
    val Close = line("close", "M6.5 6.5l11 11", "M17.5 6.5l-11 11")
    val Search = line("search", "M10.5 4a6.5 6.5 0 1 0 0 13a6.5 6.5 0 1 0 0-13z", "M20 20l-4.6-4.6")
    val Check = line("check", "M5 12.5l4.5 4.5L19 7.5")
    val Play = line("play", "M7.5 5.2v13.6a.8.8 0 0 0 1.2.7l10.6-6.8a.8.8 0 0 0 0-1.4L8.7 4.5a.8.8 0 0 0-1.2.7z", fill = true)
    val Pin = line("pin", "M12 16.5V21", "M8.5 3.5h7", "M10 3.5v5l-3.5 4.5v1.5h11V13L14 8.5v-5")
    val Restore = line("restore", "M4 12a8 8 0 1 0 2.34-5.66", "M4 4v4.5h4.5")
    /** «Ещё такие»: кадр и его отражения. */
    val Similar = line("similar", "M4.5 8.5h9a1 1 0 0 1 1 1v9a1 1 0 0 1-1 1h-9a1 1 0 0 1-1-1v-9a1 1 0 0 1 1-1z", "M7.5 5.5h9.5a1.5 1.5 0 0 1 1.5 1.5v9.5", "M10.5 2.5h9.5a1.5 1.5 0 0 1 1.5 1.5v9.5")
    /** «Поток»: три спокойные волны. */
    val Flow = line("flow", "M3.5 8c2.8-2 5.7-2 8.5 0s5.7 2 8.5 0", "M3.5 12.5c2.8-2 5.7-2 8.5 0s5.7 2 8.5 0", "M3.5 17c2.8-2 5.7-2 8.5 0s5.7 2 8.5 0")
    val Video = line("video", "M4.5 6.5h10a1.5 1.5 0 0 1 1.5 1.5v8a1.5 1.5 0 0 1-1.5 1.5h-10A1.5 1.5 0 0 1 3 16V8a1.5 1.5 0 0 1 1.5-1.5z", "M16 10.5l4.5-2.5v8l-4.5-2.5")
    val Screen = line("screen", "M8 3h8a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2z", "M10.5 18h3")
    val Album = line("album", "M5 4.5h14a1.5 1.5 0 0 1 1.5 1.5v12a1.5 1.5 0 0 1-1.5 1.5H5A1.5 1.5 0 0 1 3.5 18V6A1.5 1.5 0 0 1 5 4.5z", "M3.5 15.5l4.5-4.5 4 4 3-3 5.5 5.5", "M15.5 8.5h.01")
}
