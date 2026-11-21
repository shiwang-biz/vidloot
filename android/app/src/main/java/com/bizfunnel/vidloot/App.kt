package com.bizfunnel.vidloot

import android.app.Application
import android.util.Log
import com.bizfunnel.vidloot.engine.DownloadManager
import com.bizfunnel.vidloot.license.License
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        License.init(this)
        DownloadManager.init(this)
        // Check the stored license (no network until a re-check is due).
        CoroutineScope(Dispatchers.IO).launch { License.refresh(false); DownloadManager.pump() }
        // Unpacking Python and FFmpeg takes a moment on first launch: do it off the main thread.
        CoroutineScope(Dispatchers.IO).launch {
            val error = try {
                YoutubeDL.getInstance().init(this@App)
                FFmpeg.getInstance().init(this@App)
                null
            } catch (e: Exception) {
                Log.e("Vidloot", "engine init failed", e)
                e.message ?: "The download engine could not start."
            }
            DownloadManager.setEngineReady(error)
        }
    }
}
