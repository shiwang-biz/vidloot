package com.bizfunnel.vidloot.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.bizfunnel.vidloot.engine.DownloadManager
import com.bizfunnel.vidloot.engine.Status
import com.bizfunnel.vidloot.license.License
import kotlinx.coroutines.launch

/** What the upgrade sheet says, or null when closed. */
data class UpgradeReason(val text: String)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VidlootApp(sharedUrl: String?, onSharedUrlUsed: () -> Unit, requestedTab: String, onTabShown: () -> Unit) {
    var tab by rememberSaveable { mutableStateOf("get") }
    var upgrade by remember { mutableStateOf<UpgradeReason?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val jobs by DownloadManager.jobs.collectAsState()
    val context = LocalContext.current

    LaunchedEffect(requestedTab) {
        if (requestedTab.isNotEmpty()) { tab = requestedTab; onTabShown() }
    }

    // Ask for notifications (Android 13+) and storage (Android 9 and older) when needed.
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }
    fun askPermissions() {
        val wanted = buildList {
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
            if (Build.VERSION.SDK_INT <= 28) add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }.filter { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }
        if (wanted.isNotEmpty()) permLauncher.launch(wanted.toTypedArray())
    }

    val activeCount = jobs.count { !Status.isFinished(it.status) }

    Scaffold(
        containerColor = C.bg,
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            NavigationBar(containerColor = C.surface) {
                val colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = C.accentInk, selectedTextColor = C.accentInk, indicatorColor = C.accentSoft,
                    unselectedIconColor = C.muted, unselectedTextColor = C.muted,
                )
                NavigationBarItem(tab == "get", { tab = "get" }, { Icon(Ic.download, null) }, label = { Text("Get") }, colors = colors)
                NavigationBarItem(
                    tab == "downloads", { tab = "downloads" }, { Icon(Ic.list, null) },
                    label = { Text(if (activeCount > 0) "Downloads ($activeCount)" else "Downloads") }, colors = colors,
                )
                NavigationBarItem(tab == "settings", { tab = "settings" }, { Icon(Ic.settings, null) }, label = { Text("Settings") }, colors = colors)
            }
        },
    ) { pad ->
        val mod = Modifier.fillMaxSize().padding(pad)
        when (tab) {
            "get" -> GetScreen(
                modifier = mod,
                sharedUrl = sharedUrl,
                onSharedUrlUsed = onSharedUrlUsed,
                onQueued = {
                    askPermissions()
                    scope.launch {
                        val r = snackbar.showSnackbar("Added to your downloads", actionLabel = "View")
                        if (r == androidx.compose.material3.SnackbarResult.ActionPerformed) tab = "downloads"
                    }
                },
                onUpgrade = { upgrade = UpgradeReason(it) },
                onError = { msg -> scope.launch { snackbar.showSnackbar(msg) } },
            )
            "downloads" -> DownloadsScreen(mod, onError = { msg -> scope.launch { snackbar.showSnackbar(msg) } })
            else -> SettingsScreen(mod, onMessage = { msg -> scope.launch { snackbar.showSnackbar(msg) } })
        }
    }

    upgrade?.let { reason ->
        ModalBottomSheet(onDismissRequest = { upgrade = null }, containerColor = C.surface) {
            Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "PRO", color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 12.sp,
                    modifier = Modifier.background(C.accent, RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 4.dp),
                )
                Text("Unlock everything with Vidloot Pro", fontSize = 21.sp, fontWeight = FontWeight.Bold)
                Text(reason.text.ifEmpty { "You're on the ${License.entitlements.plan} plan." }, color = C.muted)
                listOf("Up to 4K and 1440p video", "Whole playlists in one go", "Subtitles in your videos", "Up to 3 downloads at once")
                    .forEach { Text("✓  $it", color = C.text2) }
                Spacer(Modifier.height(4.dp))
                if (BUY_URL.isNotEmpty()) {
                    Button(
                        onClick = { openUrl(context, BUY_URL) }, modifier = Modifier.fillMaxWidth().height(52.dp),
                        shape = RoundedCornerShape(14.dp), colors = ButtonDefaults.buttonColors(containerColor = C.accent),
                    ) { Text("Get Pro", fontWeight = FontWeight.SemiBold) }
                }
                OutlinedButton(
                    onClick = { upgrade = null; tab = "settings" }, modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(14.dp),
                ) { Text("I have a license key", fontWeight = FontWeight.SemiBold, color = C.text) }
            }
        }
    }
}

/** Your checkout page (https). While empty, the "Get Pro" button is hidden. */
const val BUY_URL = ""

fun openUrl(context: android.content.Context, url: String) {
    try {
        context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)))
    } catch (_: Exception) { }
}
