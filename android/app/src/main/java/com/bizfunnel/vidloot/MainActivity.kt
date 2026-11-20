package com.bizfunnel.vidloot

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import com.bizfunnel.vidloot.ui.VidlootApp
import com.bizfunnel.vidloot.ui.VidlootTheme

class MainActivity : ComponentActivity() {
    /** A link shared into Vidloot (Share → Vidloot), waiting to be fetched. */
    private val sharedUrl = mutableStateOf<String?>(null)
    private val startTab = mutableStateOf("get")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handle(intent)
        setContent {
            VidlootTheme {
                VidlootApp(
                    sharedUrl = sharedUrl.value,
                    onSharedUrlUsed = { sharedUrl.value = null },
                    requestedTab = startTab.value,
                    onTabShown = { startTab.value = "" },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        if (intent == null) return
        if (intent.action == Intent.ACTION_SEND) {
            val text = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
            Regex("https?://\\S+").find(text)?.value?.let { url ->
                sharedUrl.value = url
                startTab.value = "get"
            }
        }
        intent.getStringExtra(EXTRA_TAB)?.let { startTab.value = it }
    }

    companion object {
        const val EXTRA_TAB = "tab"
    }
}
