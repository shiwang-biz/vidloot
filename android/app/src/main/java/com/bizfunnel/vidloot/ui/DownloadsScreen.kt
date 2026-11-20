package com.bizfunnel.vidloot.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bizfunnel.vidloot.engine.DownloadManager
import com.bizfunnel.vidloot.engine.Job
import com.bizfunnel.vidloot.engine.Status

fun openJob(context: Context, j: Job, onError: (String) -> Unit) {
    val uri = j.fileUri ?: return onError("This file isn't available.")
    try {
        val type = context.contentResolver.getType(Uri.parse(uri)) ?: if (j.mode == "audio") "audio/*" else "video/*"
        context.startActivity(
            Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(uri), type).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
        )
    } catch (_: Exception) {
        onError("No app found to open this file, or it was deleted.")
    }
}

fun shareJob(context: Context, j: Job) {
    val uri = j.fileUri ?: return
    val send = Intent(Intent.ACTION_SEND).setType(if (j.mode == "audio") "audio/*" else "video/*")
        .putExtra(Intent.EXTRA_STREAM, Uri.parse(uri)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(send, null))
}

@Composable
fun DownloadsScreen(modifier: Modifier, onError: (String) -> Unit) {
    val jobs by DownloadManager.jobs.collectAsState()
    val active = jobs.filter { !Status.isFinished(it.status) }.sortedBy { it.createdAt }
    val finished = jobs.filter { Status.isFinished(it.status) }.sortedByDescending { it.finishedAt ?: it.createdAt }
    val running = active.count { Status.isActive(it.status) }

    LazyColumn(modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Column(Modifier.padding(top = 20.dp, bottom = 4.dp)) {
                Text("Downloads", fontSize = 26.sp, fontWeight = FontWeight.Bold)
                Text(
                    when {
                        running > 0 -> "$running in progress · runs in the background"
                        active.isNotEmpty() -> "${active.size} waiting or paused"
                        else -> "Nothing downloading"
                    },
                    color = C.muted, fontSize = 14.sp,
                )
            }
        }
        if (jobs.isEmpty()) {
            item {
                Column(
                    Modifier.fillMaxWidth().border(1.5.dp, C.border2, RoundedCornerShape(16.dp)).padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(Ic.download, null, tint = C.accent, modifier = Modifier.size(28.dp))
                    Spacer(Modifier.height(8.dp))
                    Text("No downloads yet", fontWeight = FontWeight.SemiBold)
                    Text("Share a video to Vidloot, or paste a link on the Get tab.", color = C.muted, fontSize = 13.sp)
                }
            }
        }
        if (active.isNotEmpty()) {
            item { SectionLabel("In progress") }
            items(active, key = { it.id }) { ActiveRow(it) }
        }
        if (finished.isNotEmpty()) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SectionLabel("Finished")
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { DownloadManager.clearFinished() }) { Text("Clear") }
                }
            }
            items(finished, key = { it.id }) { FinishedRow(it, onError) }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text.uppercase(), color = C.muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.5.sp, modifier = Modifier.padding(top = 6.dp))
}

@Composable
private fun SmallButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(44.dp).border(1.dp, C.border2, RoundedCornerShape(12.dp))) {
        Icon(icon, contentDescription = label, tint = C.text2, modifier = Modifier.size(16.dp))
    }
}

@Composable
private fun ActiveRow(j: Job) {
    Column(Modifier.fillMaxWidth().card().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Thumb(j.thumbnail, 72.dp, 44.dp, audio = j.mode == "audio")
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(j.title, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(if (j.playlist) "Playlist · ${j.label}" else j.label, color = C.muted, fontSize = 12.sp)
            }
            Spacer(Modifier.width(8.dp))
            if (j.status == Status.PAUSED) SmallButton(Ic.play, "Resume") { DownloadManager.resume(j.id) }
            else SmallButton(Ic.pause, "Pause") { DownloadManager.pause(j.id) }
            Spacer(Modifier.width(6.dp))
            SmallButton(Ic.close, "Cancel") { DownloadManager.cancel(j.id) }
        }
        val busy = j.status == Status.PROCESSING || (j.status == Status.DOWNLOADING && j.percent == 0.0)
        if (busy) {
            LinearProgressIndicator(Modifier.fillMaxWidth().height(6.dp), color = C.accent, trackColor = C.soft, strokeCap = StrokeCap.Round)
        } else {
            LinearProgressIndicator(
                progress = { (j.percent / 100.0).toFloat() }, modifier = Modifier.fillMaxWidth().height(6.dp),
                color = if (j.status == Status.PAUSED || j.status == Status.QUEUED) C.muted else C.accent,
                trackColor = C.soft, strokeCap = StrokeCap.Round, gapSize = 0.dp, drawStopIndicator = {},
            )
        }
        Row {
            val status = when (j.status) {
                Status.QUEUED -> "Waiting"
                Status.PAUSED -> "Paused · ${j.percent.toInt()}%"
                Status.PROCESSING -> j.stage
                else -> "${j.percent.toInt()}%"
            }
            Text(status, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
            Spacer(Modifier.weight(1f))
            val detail = buildList {
                if (j.itemCount > 1) add("Item ${j.itemIndex} of ${j.itemCount}")
                if (j.status == Status.DOWNLOADING) {
                    if (j.total > 0) add("${fmtBytes(j.downloaded)} / ${fmtBytes(j.total)}")
                    if (j.eta.isNotEmpty()) add("${j.eta} left")
                }
            }.joinToString(" · ")
            Text(detail, color = C.muted, fontSize = 12.sp)
        }
    }
}

@Composable
private fun FinishedRow(j: Job, onError: (String) -> Unit) {
    val context = LocalContext.current
    Row(Modifier.fillMaxWidth().card(14.dp).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
        Thumb(j.thumbnail, 72.dp, 44.dp, audio = j.mode == "audio")
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(j.title, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            when (j.status) {
                Status.DONE -> Text(
                    "Saved · ${j.label}" + if (j.savedCount > 1) " · ${j.savedCount} files" else "",
                    color = C.ok, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                )
                Status.ERROR -> Text(j.error ?: "Failed", color = C.err, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                else -> Text("Canceled", color = C.muted, fontSize = 12.sp)
            }
        }
        Spacer(Modifier.width(8.dp))
        when (j.status) {
            Status.DONE -> {
                Box(
                    Modifier.size(44.dp).background(C.accentSoft, RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    IconButton(onClick = { openJob(context, j, onError) }) { Icon(Ic.play, "Play", tint = C.accentInk, modifier = Modifier.size(16.dp)) }
                }
                Spacer(Modifier.width(6.dp))
                SmallButton(Ic.share, "Share") { shareJob(context, j) }
            }
            Status.ERROR -> {
                SmallButton(Ic.retry, "Retry") { DownloadManager.resume(j.id) }
                Spacer(Modifier.width(6.dp))
                SmallButton(Ic.close, "Remove") { DownloadManager.remove(j.id) }
            }
            else -> SmallButton(Ic.close, "Remove") { DownloadManager.remove(j.id) }
        }
    }
}
