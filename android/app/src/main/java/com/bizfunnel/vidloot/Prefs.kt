package com.bizfunnel.vidloot

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class Settings(
    val defaultMode: String = "video",
    val defaultQuality: String = "1080",
    val defaultAudio: String = "mp3",
    val subtitles: Boolean = false,
    val subtitleLangs: String = "en.*,en",
    val embedThumbnail: Boolean = true,
    val keepChapters: Boolean = true,
    val watchClipboard: Boolean = true,
)

object Prefs {
    private lateinit var sp: SharedPreferences
    private val _settings = MutableStateFlow(Settings())
    val settings: StateFlow<Settings> = _settings

    fun init(context: Context) {
        sp = context.getSharedPreferences("vidloot", Context.MODE_PRIVATE)
        val d = Settings()
        _settings.value = Settings(
            defaultMode = sp.getString("defaultMode", d.defaultMode)!!,
            defaultQuality = sp.getString("defaultQuality", d.defaultQuality)!!,
            defaultAudio = sp.getString("defaultAudio", d.defaultAudio)!!,
            subtitles = sp.getBoolean("subtitles", d.subtitles),
            subtitleLangs = sp.getString("subtitleLangs", d.subtitleLangs)!!,
            embedThumbnail = sp.getBoolean("embedThumbnail", d.embedThumbnail),
            keepChapters = sp.getBoolean("keepChapters", d.keepChapters),
            watchClipboard = sp.getBoolean("watchClipboard", d.watchClipboard),
        )
    }

    fun update(change: (Settings) -> Settings) {
        val s = change(_settings.value)
        _settings.value = s
        sp.edit()
            .putString("defaultMode", s.defaultMode)
            .putString("defaultQuality", s.defaultQuality)
            .putString("defaultAudio", s.defaultAudio)
            .putBoolean("subtitles", s.subtitles)
            .putString("subtitleLangs", s.subtitleLangs)
            .putBoolean("embedThumbnail", s.embedThumbnail)
            .putBoolean("keepChapters", s.keepChapters)
            .putBoolean("watchClipboard", s.watchClipboard)
            .apply()
    }
}
