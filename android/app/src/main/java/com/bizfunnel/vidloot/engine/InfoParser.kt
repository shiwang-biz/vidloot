package com.bizfunnel.vidloot.engine

import org.json.JSONArray
import org.json.JSONObject

/**
 * Turns `yt-dlp -J` output into the choices the format sheet shows.
 * Same rules as the desktop app (src-tauri/src/info.rs).
 */
object InfoParser {
    private data class Band(val std: Int, val min: Int, val label: String)

    private val BANDS = listOf(
        Band(2160, 1800, "4K"), Band(1440, 1300, "1440p"), Band(1080, 1000, "1080p"),
        Band(720, 700, "720p"), Band(480, 440, "480p"), Band(360, 0, "360p"),
    )

    private fun bandOf(h: Int): Int = BANDS.firstOrNull { h >= it.min }?.std ?: 360

    private fun JSONObject.num(k: String): Double? =
        if (has(k) && !isNull(k)) optDouble(k).takeIf { !it.isNaN() } else null

    private fun JSONObject.text(k: String): String? =
        if (has(k) && !isNull(k)) optString(k).takeIf { it.isNotEmpty() } else null

    private fun JSONObject.isNone(k: String) = optString(k, "") == "none"

    private fun fmtSize(f: JSONObject, duration: Double?): Long? =
        (f.num("filesize") ?: f.num("filesize_approx")
            ?: f.num("tbr")?.let { tbr -> duration?.let { tbr * 125.0 * it } })?.toLong()

    fun parse(url: String, v: JSONObject): MediaInfo {
        val type = v.optString("_type", "video")
        val isPlaylist = type == "playlist" || type == "multi_video"
        val title = v.text("title") ?: "Untitled"
        val uploader = v.text("uploader") ?: v.text("channel") ?: v.text("playlist_uploader")
        val site = v.text("extractor_key") ?: v.text("webpage_url_domain") ?: "Web"
        val duration = v.num("duration")
        var thumbnail = v.text("thumbnail")

        if (isPlaylist) {
            val entries: JSONArray? = v.optJSONArray("entries")
            val count = v.num("playlist_count")?.toLong() ?: entries?.length()?.toLong()
            if (thumbnail == null && entries != null && entries.length() > 0) {
                val thumbs = entries.optJSONObject(0)?.optJSONArray("thumbnails")
                if (thumbs != null && thumbs.length() > 0) thumbnail = thumbs.optJSONObject(thumbs.length() - 1)?.text("url")
            }
            return MediaInfo(
                url, true, title, uploader, site, null, thumbnail, count, false, 2160, true,
                BANDS.map { QualityOption(it.std.toString(), it.label, it.std, null) },
                audioOptions(null, null, null),
            )
        }

        val formats = v.optJSONArray("formats")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } } ?: emptyList()

        val bestAudio = formats
            .filter { it.isNone("vcodec") && !it.isNone("acodec") }
            .maxByOrNull { it.num("abr") ?: it.num("tbr") ?: 0.0 }
        val audioSize = bestAudio?.let { fmtSize(it, duration) }
        val opusSize = formats
            .filter { it.isNone("vcodec") && it.optString("acodec").startsWith("opus") }
            .mapNotNull { fmtSize(it, duration) }.maxOrNull()

        val bands = linkedMapOf<Int, Long?>()
        for (f in formats) {
            if (f.isNone("vcodec")) continue
            val h = f.num("height")?.toInt()?.takeIf { it > 0 } ?: continue
            val band = bandOf(h)
            val hasAudio = f.has("acodec") && !f.isNone("acodec")
            val size = fmtSize(f, duration)?.let { if (hasAudio) it else it + (audioSize ?: 0) }
            val prev = bands[band]
            if (!bands.containsKey(band) || (size ?: -1) > (prev ?: -1)) bands[band] = size
        }
        val sorted = bands.entries.sortedByDescending { it.key }
        val maxHeight = sorted.firstOrNull()?.key ?: 0

        val videoOptions = if (sorted.isEmpty()) {
            listOf(QualityOption("best", "Best available", 0, (v.num("filesize") ?: v.num("filesize_approx"))?.toLong()))
        } else {
            sorted.map { (h, size) -> QualityOption(h.toString(), BANDS.first { it.std == h }.label, h, size) }
        }

        val subs = v.optJSONObject("subtitles")
        return MediaInfo(
            url = url, isPlaylist = false, title = title, uploader = uploader, site = site,
            duration = duration, thumbnail = thumbnail, entryCount = null,
            inPlaylist = url.contains("list="), maxHeight = maxHeight,
            hasSubtitles = subs != null && subs.length() > 0,
            videoOptions = videoOptions, audioOptions = audioOptions(duration, audioSize, opusSize),
        )
    }

    private fun audioOptions(duration: Double?, best: Long?, opus: Long?): List<AudioOption> {
        fun est(bytesPerSec: Double) = duration?.let { (it * bytesPerSec).toLong() }
        return listOf(
            AudioOption("mp3", "MP3", "320 kbps · plays everywhere", est(40_000.0)),
            AudioOption("m4a", "M4A", "Best quality", best ?: est(16_000.0)),
            AudioOption("opus", "Opus", "Smallest file", opus ?: est(16_000.0)),
        )
    }

    /** Turn yt-dlp's error output into one readable sentence. */
    fun cleanError(output: String?): String {
        val text = output.orEmpty()
        val line = text.lines().lastOrNull { it.startsWith("ERROR:") }
            ?: text.lines().lastOrNull { it.isNotBlank() }
            ?: "Something went wrong."
        val msg = line.removePrefix("ERROR:").trim()
        val lower = msg.lowercase()
        return when {
            "unsupported url" in lower -> "This site isn't supported, or the link isn't a video page."
            "private video" in lower || "sign in" in lower || "login" in lower ->
                "This video needs a sign-in (private, members-only or age-restricted)."
            "unable to connect" in lower || "timed out" in lower || "name resolution" in lower || "getaddrinfo" in lower ->
                "Couldn't reach the site. Check your internet connection."
            "http error 404" in lower || "not available" in lower || "removed" in lower -> "This video isn't available anymore."
            msg.startsWith("[") && "]" in msg -> msg.substringAfter("]").substringAfter(":").trim().take(220)
            else -> msg.take(220)
        }
    }
}
