package com.bizfunnel.vidloot.engine

import org.json.JSONObject

data class QualityOption(val id: String, val label: String, val height: Int, val size: Long?)
data class AudioOption(val id: String, val label: String, val note: String, val size: Long?)

data class MediaInfo(
    val url: String,
    val isPlaylist: Boolean,
    val title: String,
    val uploader: String?,
    val site: String,
    val duration: Double?,
    val thumbnail: String?,
    val entryCount: Long?,
    val inPlaylist: Boolean,
    val maxHeight: Int,
    val hasSubtitles: Boolean,
    val videoOptions: List<QualityOption>,
    val audioOptions: List<AudioOption>,
)

object Status {
    const val QUEUED = "queued"
    const val DOWNLOADING = "downloading"
    const val PROCESSING = "processing"
    const val PAUSED = "paused"
    const val DONE = "done"
    const val ERROR = "error"
    const val CANCELED = "canceled"

    fun isActive(s: String) = s == DOWNLOADING || s == PROCESSING
    fun isFinished(s: String) = s == DONE || s == ERROR || s == CANCELED
}

/** One download. Mutable fields are only changed by DownloadManager under its lock. */
data class Job(
    val id: String,
    val url: String,
    val title: String,
    val thumbnail: String?,
    /** video | audio */
    val mode: String,
    /** "1080", "best", or an audio format ("mp3"). */
    val quality: String,
    val label: String,
    val playlist: Boolean,
    val subtitles: Boolean,
    val embedThumbnail: Boolean,
    val chapters: Boolean,
    val status: String = Status.QUEUED,
    val stage: String = "Waiting",
    val percent: Double = 0.0,
    val speed: String = "",
    val eta: String = "",
    val downloaded: Long = 0,
    val total: Long = 0,
    val itemIndex: Int = 0,
    val itemCount: Int = 0,
    /** content:// address of the saved file (first file for playlists). */
    val fileUri: String? = null,
    val fileName: String? = null,
    val savedCount: Int = 0,
    val error: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val finishedAt: Long? = null,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("url", url); put("title", title); put("thumbnail", thumbnail ?: JSONObject.NULL)
        put("mode", mode); put("quality", quality); put("label", label); put("playlist", playlist)
        put("subtitles", subtitles); put("embedThumbnail", embedThumbnail); put("chapters", chapters)
        put("status", status); put("percent", percent); put("itemIndex", itemIndex); put("itemCount", itemCount)
        put("fileUri", fileUri ?: JSONObject.NULL); put("fileName", fileName ?: JSONObject.NULL)
        put("savedCount", savedCount); put("error", error ?: JSONObject.NULL)
        put("createdAt", createdAt); put("finishedAt", finishedAt ?: JSONObject.NULL)
    }

    companion object {
        private fun JSONObject.str(k: String): String? = if (isNull(k)) null else optString(k, "").ifEmpty { null }

        fun fromJson(o: JSONObject): Job {
            var status = o.optString("status", Status.PAUSED)
            // Anything unfinished when the app was closed comes back paused.
            if (!Status.isFinished(status)) status = Status.PAUSED
            return Job(
                id = o.getString("id"), url = o.getString("url"), title = o.optString("title"),
                thumbnail = o.str("thumbnail"), mode = o.optString("mode", "video"),
                quality = o.optString("quality", "best"), label = o.optString("label"),
                playlist = o.optBoolean("playlist"), subtitles = o.optBoolean("subtitles"),
                embedThumbnail = o.optBoolean("embedThumbnail", true), chapters = o.optBoolean("chapters", true),
                status = status, stage = if (status == Status.PAUSED) "Paused" else "",
                percent = o.optDouble("percent", 0.0), itemIndex = o.optInt("itemIndex"), itemCount = o.optInt("itemCount"),
                fileUri = o.str("fileUri"), fileName = o.str("fileName"), savedCount = o.optInt("savedCount"),
                error = o.str("error"), createdAt = o.optLong("createdAt"),
                finishedAt = if (o.isNull("finishedAt")) null else o.optLong("finishedAt"),
            )
        }
    }
}

data class DownloadRequest(
    val url: String,
    val title: String,
    val thumbnail: String?,
    val mode: String,
    val quality: String,
    val playlist: Boolean,
    val subtitles: Boolean,
    val embedThumbnail: Boolean,
    val chapters: Boolean,
)
