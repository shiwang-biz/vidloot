package com.bizfunnel.vidloot.ui

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.bizfunnel.vidloot.Prefs
import com.bizfunnel.vidloot.engine.DownloadManager
import com.bizfunnel.vidloot.engine.DownloadRequest
import com.bizfunnel.vidloot.engine.MediaInfo
import com.bizfunnel.vidloot.engine.ProRequiredException
import com.bizfunnel.vidloot.engine.Status
import com.bizfunnel.vidloot.license.License
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The user's choices in the format sheet. */
data class Choice(
    val mode: String, val video: String, val audio: String,
    val playlist: Boolean, val subtitles: Boolean, val thumb: Boolean, val chapters: Boolean,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GetScreen(
    modifier: Modifier,
    sharedUrl: String?,
    onSharedUrlUsed: () -> Unit,
    onQueued: () -> Unit,
    onUpgrade: (String) -> Unit,
    onError: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    val settings by Prefs.settings.collectAsState()
    val license by License.info.collectAsState()
    val engineError by DownloadManager.engineError.collectAsState()
    val jobs by DownloadManager.jobs.collectAsState()

    var url by rememberSaveable { mutableStateOf("") }
    var fetching by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var info by remember { mutableStateOf<MediaInfo?>(null) }
    var choice by remember { mutableStateOf<Choice?>(null) }
    var sheetOpen by remember { mutableStateOf(false) }
    var clipUrl by remember { mutableStateOf<String?>(null) }
    var lastClip by rememberSaveable { mutableStateOf("") }

    fun fetch(link: String) {
        val u = link.trim()
        if (u.isEmpty()) return
        url = u; clipUrl = null; error = null; info = null; fetching = true
        focus.clearFocus()
        scope.launch {
            // On first launch the engine may still be unpacking.
            while (DownloadManager.engineError.value == null) delay(200)
            DownloadManager.engineError.value?.takeIf { it.isNotEmpty() }?.let { error = it; fetching = false; return@launch }
            try {
                val i = DownloadManager.fetchInfo(u)
                val ent = License.entitlements
                val wanted = settings.defaultQuality.toIntOrNull() ?: 1080
                val allowed = i.videoOptions.filter { it.height == 0 || ent.allowsHeight(it.height) }
                val pick = allowed.firstOrNull { it.height <= wanted } ?: allowed.lastOrNull() ?: i.videoOptions.first()
                choice = Choice(
                    mode = settings.defaultMode, video = pick.id, audio = settings.defaultAudio,
                    playlist = i.isPlaylist, subtitles = settings.subtitles && ent.subtitles,
                    thumb = settings.embedThumbnail, chapters = settings.keepChapters,
                )
                info = i
                sheetOpen = true
            } catch (e: Exception) {
                error = e.message ?: "Couldn't read this link."
            } finally {
                fetching = false
            }
        }
    }

    LaunchedEffect(sharedUrl) {
        if (sharedUrl != null) { fetch(sharedUrl); onSharedUrlUsed() }
    }

    // Offer a copied link whenever the app comes back to the front.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, settings.watchClipboard) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME && settings.watchClipboard) {
                scope.launch {
                    delay(400) // Android only lets the app read the clipboard once it has focus.
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val t = try { cm.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()?.trim() } catch (_: Exception) { null }
                    if (isUrl(t) && t != lastClip && t != url && t != info?.url) { lastClip = t!!; clipUrl = t }
                }
            }
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }

    Column(modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(Modifier.padding(top = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(32.dp).background(C.accent, RoundedCornerShape(9.dp)), contentAlignment = Alignment.Center) {
                Icon(Ic.download, null, tint = Color.White, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(10.dp))
            Text("Vidloot", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }

        clipUrl?.let { cu ->
            Column(
                Modifier.fillMaxWidth().background(C.accent, RoundedCornerShape(20.dp)).clickable { fetch(cu) }.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Ic.clipboard, null, tint = Color(0xFFDCE4FC), modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Link found on your clipboard", color = Color(0xFFDCE4FC), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
                Text(cu.removePrefix("https://").removePrefix("www."), color = Color.White, fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Row(
                    Modifier.background(Color.White, RoundedCornerShape(12.dp)).padding(horizontal = 18.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Get this video", color = C.accentInk, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.width(8.dp))
                    Icon(Ic.arrow, null, tint = C.accentInk, modifier = Modifier.size(16.dp))
                }
            }
        }

        OutlinedTextField(
            value = url, onValueChange = { url = it }, singleLine = true,
            placeholder = { Text("Or paste any link…") },
            leadingIcon = { Icon(Ic.link, null, tint = C.muted, modifier = Modifier.size(18.dp)) },
            trailingIcon = {
                if (fetching) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                else TextButton(onClick = { fetch(url) }, enabled = url.isNotBlank()) { Text("Fetch") }
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { fetch(url) }),
            shape = RoundedCornerShape(14.dp),
            colors = OutlinedTextFieldDefaults.colors(unfocusedContainerColor = C.surface, focusedContainerColor = C.surface, unfocusedBorderColor = C.border2),
            modifier = Modifier.fillMaxWidth(),
        )
        Text("Tip: tap Share in the YouTube app and pick Vidloot. No copying needed.", color = C.muted, fontSize = 13.sp)

        if (engineError == null) {
            Notice("Preparing the download engine (first launch only)…")
        } else if (engineError!!.isNotEmpty()) {
            Notice("The download engine couldn't start: $engineError", isError = true)
        }
        error?.let { Notice(it, isError = true) }

        info?.let { i ->
            val c = choice!!
            Row(
                Modifier.fillMaxWidth().card().clickable { sheetOpen = true }.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Thumb(i.thumbnail, 112.dp, 63.dp, tag = if (i.isPlaylist) "${i.entryCount ?: "?"} videos" else fmtDuration(i.duration).ifEmpty { null })
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(i.title, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(listOfNotNull(i.uploader, i.site).joinToString(" · "), color = C.muted, fontSize = 12.sp, maxLines = 1)
                    Text("Choose format", color = C.accentInk, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 4.dp))
                }
            }
            if (sheetOpen) {
                FormatSheet(
                    info = i, choice = c, onChoice = { choice = it }, onDismiss = { sheetOpen = false },
                    onUpgrade = onUpgrade,
                    onDownload = {
                        try {
                            DownloadManager.enqueue(
                                DownloadRequest(
                                    url = i.url, title = i.title, thumbnail = i.thumbnail, mode = c.mode,
                                    quality = if (c.mode == "video") c.video else c.audio, playlist = c.playlist,
                                    subtitles = c.mode == "video" && c.subtitles, embedThumbnail = c.thumb, chapters = c.chapters,
                                ),
                            )
                            sheetOpen = false; info = null; url = ""
                            onQueued()
                        } catch (e: ProRequiredException) {
                            onUpgrade(e.message ?: "")
                        } catch (e: Exception) {
                            onError(e.message ?: "Couldn't start the download.")
                        }
                    },
                )
            }
        }

        val recent = jobs.filter { it.status == Status.DONE }.sortedByDescending { it.finishedAt ?: 0 }.take(4)
        if (recent.isNotEmpty() && info == null) {
            Text("Recent", fontWeight = FontWeight.SemiBold, fontSize = 16.sp, modifier = Modifier.padding(top = 4.dp))
            recent.forEach { j ->
                Row(
                    Modifier.fillMaxWidth().card(14.dp).clickable { openJob(context, j, onError) }.padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Thumb(j.thumbnail, 72.dp, 44.dp, audio = j.mode == "audio")
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(j.title, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(j.label, color = C.muted, fontSize = 12.sp)
                    }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
fun Notice(text: String, isError: Boolean = false) {
    Row(
        Modifier.fillMaxWidth().background(if (isError) C.errSoft else C.soft, RoundedCornerShape(12.dp)).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Ic.info, null, tint = if (isError) C.err else C.text2, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Text(text, color = if (isError) C.err else C.text2, fontSize = 13.sp)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FormatSheet(
    info: MediaInfo, choice: Choice, onChoice: (Choice) -> Unit, onDismiss: () -> Unit,
    onUpgrade: (String) -> Unit, onDownload: () -> Unit,
) {
    val ent = License.entitlements
    val video = choice.mode == "video"
    val playlistBlocked = info.isPlaylist && !ent.playlists
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = C.surface) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Thumb(info.thumbnail, 112.dp, 63.dp, tag = fmtDuration(info.duration).ifEmpty { null })
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(info.title, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(listOfNotNull(info.uploader, info.site).joinToString(" · "), color = C.muted, fontSize = 12.sp, maxLines = 1)
                }
            }

            // Video / Audio only
            Row(Modifier.fillMaxWidth().background(C.soft, RoundedCornerShape(12.dp)).padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf("video" to "Video", "audio" to "Audio only").forEach { (m, label) ->
                    val on = choice.mode == m
                    Box(
                        Modifier.weight(1f).heightIn(min = 44.dp)
                            .background(if (on) C.surface else Color.Transparent, RoundedCornerShape(9.dp))
                            .clickable { onChoice(choice.copy(mode = m)) },
                        contentAlignment = Alignment.Center,
                    ) { Text(label, fontWeight = FontWeight.SemiBold, color = if (on) C.text else C.muted) }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (video) {
                    info.videoOptions.forEach { o ->
                        val locked = o.height > 0 && !ent.allowsHeight(o.height)
                        OptionRow(
                            selected = choice.video == o.id, label = o.label,
                            note = when (o.id) { "1080" -> "Recommended"; "720" -> "Good for phones"; "480", "360" -> "Saves data"; "2160" -> "Best quality, big file"; else -> "" },
                            size = fmtBytes(o.size), locked = locked,
                        ) {
                            if (locked) onUpgrade("${o.label} needs Pro. The ${ent.plan} plan downloads up to ${ent.maxHeight}p.")
                            else onChoice(choice.copy(video = o.id))
                        }
                    }
                } else {
                    info.audioOptions.forEach { o ->
                        OptionRow(choice.audio == o.id, o.label, o.note, fmtBytes(o.size), false) { onChoice(choice.copy(audio = o.id)) }
                    }
                }
            }

            if (info.isPlaylist || info.inPlaylist) {
                ToggleRow(
                    if (info.isPlaylist) "Whole playlist (${info.entryCount ?: "?"} videos)" else "Download the whole playlist",
                    choice.playlist, locked = !ent.playlists,
                ) { on -> if (!ent.playlists) onUpgrade("Whole playlists need Pro.") else onChoice(choice.copy(playlist = on)) }
            }
            if (video) {
                ToggleRow("Download subtitles", choice.subtitles, locked = !ent.subtitles) { on ->
                    if (!ent.subtitles) onUpgrade("Subtitles need Pro.") else onChoice(choice.copy(subtitles = on))
                }
            }

            if (playlistBlocked) Notice("This link is a playlist. The ${ent.plan} plan downloads single videos; share one video's link, or upgrade.")

            Button(
                onClick = { if (playlistBlocked) onUpgrade("Whole playlists need Pro.") else onDownload() },
                modifier = Modifier.fillMaxWidth().height(56.dp), shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = C.accent),
            ) {
                Icon(Ic.download, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(10.dp))
                val chosen = if (video) info.videoOptions.firstOrNull { it.id == choice.video }?.label else info.audioOptions.firstOrNull { it.id == choice.audio }?.label
                Text(
                    if (playlistBlocked) "Unlock playlists" else "Download ${if (choice.playlist) "playlist · " else ""}${chosen ?: ""}",
                    fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

@Composable
private fun OptionRow(selected: Boolean, label: String, note: String, size: String, locked: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 60.dp)
            .background(if (selected) C.accentSoft else C.surface, RoundedCornerShape(12.dp))
            .border(if (selected) 2.dp else 1.dp, if (selected) C.accent else C.border, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(20.dp).border(if (selected) 6.dp else 2.dp, if (selected) C.accent else Color(0xFF8A909C), CircleShape),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = if (locked) C.muted else C.text)
                if (locked) { Spacer(Modifier.width(8.dp)); ProBadge() }
            }
            if (note.isNotEmpty()) Text(note, color = C.muted, fontSize = 12.sp)
        }
        if (size.isNotEmpty()) Text(size, color = C.text2, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, locked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = C.text2, fontSize = 14.sp, modifier = Modifier.weight(1f))
        if (locked) { ProBadge(); Spacer(Modifier.width(8.dp)) }
        Switch(
            checked = checked && !locked, onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedTrackColor = C.accent),
        )
    }
}
