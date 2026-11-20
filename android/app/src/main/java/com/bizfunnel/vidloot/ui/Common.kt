package com.bizfunnel.vidloot.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage

fun fmtBytes(n: Long?): String {
    if (n == null || n <= 0) return ""
    val units = listOf("B", "KB", "MB", "GB", "TB")
    var v = n.toDouble(); var i = 0
    while (v >= 1000 && i < units.size - 1) { v /= 1000; i++ }
    return if (v >= 100 || i == 0) "${v.toInt()} ${units[i]}" else "%.1f %s".format(v, units[i])
}

fun fmtDuration(s: Double?): String {
    if (s == null || s <= 0) return ""
    val t = s.toInt(); val h = t / 3600; val m = (t % 3600) / 60; val sec = t % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}

fun isUrl(t: String?) = t != null && Regex("^https?://\\S+$").matches(t.trim())

/** Video thumbnail, or a dark box with a play/music icon. */
@Composable
fun Thumb(url: String?, width: Dp, height: Dp, audio: Boolean = false, tag: String? = null) {
    Box(
        Modifier.size(width, height).clip(RoundedCornerShape(8.dp)).background(C.thumb),
        contentAlignment = Alignment.Center,
    ) {
        if (url != null && url.startsWith("https://")) {
            AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(width, height))
        } else {
            Icon(if (audio) Ic.music else Ic.play, null, tint = Color.White, modifier = Modifier.size(18.dp))
        }
        if (tag != null) {
            Text(
                tag, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp)
                    .background(Color(0xB8000000), RoundedCornerShape(5.dp)).padding(horizontal = 5.dp, vertical = 2.dp),
            )
        }
    }
}

@Composable
fun ProBadge() {
    Row(
        Modifier.background(C.accentSoft, RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Ic.lock, null, tint = C.accentInk, modifier = Modifier.size(11.dp))
        Text(" PRO", color = C.accentInk, fontSize = 10.sp, fontWeight = FontWeight.Bold)
    }
}

fun Modifier.card(radius: Dp = 16.dp) =
    this.clip(RoundedCornerShape(radius)).background(C.surface).border(1.dp, C.border, RoundedCornerShape(radius))
