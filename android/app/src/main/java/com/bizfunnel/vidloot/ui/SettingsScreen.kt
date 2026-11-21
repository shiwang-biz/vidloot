package com.bizfunnel.vidloot.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bizfunnel.vidloot.Prefs
import com.bizfunnel.vidloot.engine.DownloadManager
import com.bizfunnel.vidloot.license.License
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SettingsScreen(modifier: Modifier, onMessage: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    val settings by Prefs.settings.collectAsState()
    val license by License.info.collectAsState()
    val engineError by DownloadManager.engineError.collectAsState()
    var engineVersion by remember { mutableStateOf<String?>(null) }
    var updating by remember { mutableStateOf(false) }
    var key by remember { mutableStateOf("") }
    var activating by remember { mutableStateOf(false) }

    LaunchedEffect(engineError) {
        if (engineError == "") engineVersion = withContext(Dispatchers.IO) { DownloadManager.engineVersion() }
    }

    Column(modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Settings", fontSize = 26.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 20.dp))

        // ---- License
        Section("License") {
            val e = license.entitlements
            Row(verticalAlignment = Alignment.CenterVertically) {
                val (bg, fg, label) = when (license.status) {
                    "active" -> Triple(C.accentSoft, C.ok, "Active")
                    "grace" -> Triple(C.soft, C.text2, "Offline grace period")
                    "invalid" -> Triple(C.errSoft, C.err, "Not valid")
                    else -> Triple(C.soft, C.text2, "No license")
                }
                Text(label, color = fg, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.background(bg, RoundedCornerShape(99.dp)).padding(horizontal = 10.dp, vertical = 3.dp))
                Spacer(Modifier.width(10.dp))
                Text("${e.plan} plan", fontWeight = FontWeight.SemiBold)
            }
            license.keyHint?.let { Text("Key $it", color = C.muted, fontSize = 13.sp) }
            license.licensedTo?.let { Text("Licensed to $it", color = C.muted, fontSize = 13.sp) }
            license.updatesUntil?.let { Text("Updates until $it", color = C.muted, fontSize = 13.sp) }
            license.expiresAt?.let { Text("Renews or expires $it", color = C.muted, fontSize = 13.sp) }
            license.message?.let { Notice(it, isError = license.status == "invalid") }
            Text(
                "Video up to ${if (e.maxHeight == 0) "4K" else "${e.maxHeight}p"} · Playlists ${if (e.playlists) "yes" else "no"} · " +
                    "Subtitles ${if (e.subtitles) "yes" else "no"} · ${e.parallelDownloads} at a time",
                color = C.text2, fontSize = 13.sp,
            )
            if (!license.connected) {
                Notice("Licensing isn't set up in this build, so Vidloot runs on the Free plan.")
            } else if (!license.isPro) {
                OutlinedTextField(
                    value = key, onValueChange = { key = it.uppercase() }, singleLine = true,
                    placeholder = { Text("XXXX-XXXX-XXXX-XXXX") },
                    textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                    shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = {
                        activating = true
                        scope.launch {
                            License.activate(key.trim())
                                .onSuccess { onMessage("Welcome to ${it.entitlements.plan}!"); key = ""; DownloadManager.pump() }
                                .onFailure { onMessage(it.message ?: "Activation failed.") }
                            activating = false
                        }
                    },
                    enabled = key.isNotBlank() && !activating, modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = RoundedCornerShape(12.dp), colors = ButtonDefaults.buttonColors(containerColor = C.accent),
                ) { Text(if (activating) "Activating…" else "Activate", fontWeight = FontWeight.SemiBold) }
            } else {
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            val i = License.refresh(force = true)
                            onMessage(if (i.status == "active") "License is valid" else i.message ?: "License checked")
                        }
                    },
                    modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp),
                ) { Text("Check now", color = C.text) }
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            License.deactivate()
                                .onSuccess { onMessage("This phone was deactivated. You can use your key on another device.") }
                                .onFailure { onMessage(it.message ?: "Couldn't deactivate.") }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp),
                ) { Text("Deactivate this phone", color = C.err) }
            }
        }

        // ---- Downloads
        Section("Downloads") {
            Text("Files are saved to Downloads/Vidloot.", color = C.muted, fontSize = 13.sp)
            Picker("Start in", settings.defaultMode, listOf("video" to "Video", "audio" to "Audio only")) { v -> Prefs.update { it.copy(defaultMode = v) } }
            Picker(
                "Preferred video quality", settings.defaultQuality,
                listOf("2160" to "4K", "1440" to "1440p", "1080" to "1080p", "720" to "720p", "480" to "480p", "360" to "360p"),
            ) { v -> Prefs.update { it.copy(defaultQuality = v) } }
            Picker("Preferred audio format", settings.defaultAudio, listOf("mp3" to "MP3", "m4a" to "M4A", "opus" to "Opus")) { v ->
                Prefs.update { it.copy(defaultAudio = v) }
            }
            Toggle("Add cover image / thumbnail", settings.embedThumbnail) { v -> Prefs.update { it.copy(embedThumbnail = v) } }
            Toggle("Keep chapters", settings.keepChapters) { v -> Prefs.update { it.copy(keepChapters = v) } }
            Toggle("Offer links copied to the clipboard", settings.watchClipboard) { v -> Prefs.update { it.copy(watchClipboard = v) } }
        }

        // ---- Engine
        Section("Download engine") {
            Text(
                when {
                    engineError == null -> "Preparing…"
                    engineError!!.isNotEmpty() -> "Couldn't start: $engineError"
                    else -> "yt-dlp ${engineVersion ?: ""} · FFmpeg included"
                },
                color = C.muted, fontSize = 13.sp,
            )
            Text("Sites change often. If a site stops working, update the engine first.", color = C.muted, fontSize = 12.sp)
            OutlinedButton(
                onClick = {
                    updating = true
                    scope.launch {
                        try {
                            onMessage(DownloadManager.updateEngine())
                            engineVersion = withContext(Dispatchers.IO) { DownloadManager.engineVersion() }
                        } catch (e: Exception) {
                            onMessage("Update failed: ${e.message}")
                        }
                        updating = false
                    }
                },
                enabled = !updating && engineError == "", shape = RoundedCornerShape(12.dp),
            ) { Text(if (updating) "Updating…" else "Update engine", color = C.text) }
        }

        Section("About") {
            Text("Vidloot 1.0.1 · Powered by yt-dlp and FFmpeg (LGPL).", color = C.muted, fontSize = 12.sp)
            Text(
                "Only download content you own, content with a Creative Commons or public-domain license, or content you have permission to save. " +
                    "Respect each site's terms of service and copyright law.",
                color = C.muted, fontSize = 12.sp,
            )
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().card().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        content()
    }
}

@Composable
private fun Toggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = C.text2, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Switch(checked, onChange, colors = SwitchDefaults.colors(checkedTrackColor = C.accent))
    }
}

@Composable
private fun Picker(label: String, value: String, options: List<Pair<String, String>>, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).clickable { open = true }, verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = C.text2, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Text(options.firstOrNull { it.first == value }?.second ?: value, color = C.accentInk, fontWeight = FontWeight.SemiBold)
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { (v, l) -> DropdownMenuItem(text = { Text(l) }, onClick = { onPick(v); open = false }) }
        }
    }
}
