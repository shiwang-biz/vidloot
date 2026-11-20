package com.bizfunnel.vidloot.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/** Colours from the Vidloot design (same as the desktop app). */
object C {
    val bg = Color(0xFFF6F7F9)
    val surface = Color(0xFFFFFFFF)
    val text = Color(0xFF14161A)
    val text2 = Color(0xFF3A404C)
    val muted = Color(0xFF5B6270)
    val border = Color(0xFFE3E6EB)
    val border2 = Color(0xFFD5D9E0)
    val soft = Color(0xFFF0F2F5)
    val accent = Color(0xFF2F5BEA)
    val accentInk = Color(0xFF1E44C2)
    val accentSoft = Color(0xFFE9EEFD)
    val ok = Color(0xFF1F7A4D)
    val err = Color(0xFFB42318)
    val errSoft = Color(0xFFFDECEA)
    val thumb = Color(0xFF2A2F3A)
}

@Composable
fun VidlootTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = C.accent, onPrimary = Color.White, primaryContainer = C.accentSoft, onPrimaryContainer = C.accentInk,
            background = C.bg, onBackground = C.text, surface = C.surface, onSurface = C.text,
            surfaceVariant = C.soft, onSurfaceVariant = C.muted, outline = C.border2, outlineVariant = C.border,
            error = C.err, secondaryContainer = C.accentSoft, onSecondaryContainer = C.accentInk,
            surfaceContainer = C.surface, surfaceContainerLow = C.surface, surfaceContainerHigh = C.surface,
        ),
        content = content,
    )
}

/** Line icons built from the same SVG paths as the design (24×24, 2px stroke). */
object Ic {
    private fun icon(name: String, vararg paths: String, filled: Boolean = false): ImageVector {
        val b = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
        for (d in paths) {
            if (filled) b.addPath(addPathNodes(d), fill = SolidColor(Color.Black))
            else b.addPath(
                addPathNodes(d), fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
            )
        }
        return b.build()
    }

    val download = icon("download", "M12 4v11", "M7 10l5 5 5-5", "M5 20h14")
    val list = icon("list", "M8 6h13M8 12h13M8 18h13", "M3 6h.01M3 12h.01M3 18h.01")
    val settings = icon("settings", "M9 12a3 3 0 1 0 6 0a3 3 0 1 0 -6 0", "M12 2v3M12 19v3M2 12h3M19 12h3M4.9 4.9L7 7M17 17l2.1 2.1M4.9 19.1L7 17M17 7l2.1-2.1")
    val link = icon("link", "M10 14a5 5 0 0 0 7 0l3-3a5 5 0 0 0-7-7l-1 1", "M14 10a5 5 0 0 0-7 0l-3 3a5 5 0 0 0 7 7l1-1")
    val clipboard = icon("clip", "M8 3h8v4H8z", "M8 5H6a2 2 0 0 0-2 2v12a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V7a2 2 0 0 0-2-2h-2")
    val arrow = icon("arrow", "M5 12h14M13 6l6 6-6 6")
    val pause = icon("pause", "M9 5v14M15 5v14")
    val play = icon("play", "M8 5v14l11-7z", filled = true)
    val close = icon("close", "M6 6l12 12M18 6L6 18")
    val retry = icon("retry", "M3 12a9 9 0 1 0 3-6.7L3 8", "M3 3v5h5")
    val share = icon("share", "M4 12v7a1 1 0 0 0 1 1h14a1 1 0 0 0 1-1v-7", "M16 6l-4-4-4 4", "M12 2v13")
    val lock = icon("lock", "M5 11h14v9H5z", "M8 11V8a4 4 0 0 1 8 0v3")
    val music = icon("music", "M9 18V6l11-2v12", "M3 18a3 3 0 1 0 6 0a3 3 0 1 0 -6 0", "M14 16a3 3 0 1 0 6 0a3 3 0 1 0 -6 0")
    val video = icon("video", "M3 8a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z", "M16 10l5-3v10l-5-3")
    val info = icon("info", "M3 12a9 9 0 1 0 18 0a9 9 0 1 0 -18 0", "M12 8h.01M11 12h1v5h1")
}
