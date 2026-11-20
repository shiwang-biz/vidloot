package com.bizfunnel.vidloot.engine

/**
 * yt-dlp arguments and progress parsing. Kept free of Android classes so it can
 * be unit-tested on a plain JVM. Mirrors the desktop app's engine.rs.
 */
object Ytdlp {
    const val PROGRESS_TEMPLATE =
        "download:VLPROG|%(progress.status)s|%(progress._percent_str)s|%(progress._speed_str)s|%(progress._eta_str)s|" +
            "%(progress.downloaded_bytes)s|%(progress.total_bytes)s|%(progress.total_bytes_estimate)s|" +
            "%(info.playlist_index)s|%(info.n_entries)s|%(info.vcodec)s|%(info.acodec)s"
    const val PP_TEMPLATE = "postprocess:VLPP|%(progress.postprocessor)s|%(progress.status)s"

    /** Everything except the URL (the library adds that). [workDir] is where files are written. */
    fun buildArgs(job: Job, workDir: String, subtitleLangs: String): List<String> {
        val a = mutableListOf(
            "--newline", "--progress", "--color", "never", "--no-mtime", "--embed-metadata", "--continue",
            "--progress-template", PROGRESS_TEMPLATE,
            "--progress-template", PP_TEMPLATE,
            "--print", "after_move:VLFILE|%(filepath)s",
            "-P", workDir,
        )
        if (job.playlist) {
            a += listOf("-o", "%(playlist_title,playlist_id|Playlist).80B/%(playlist_index)03d - %(title).120B.%(ext)s", "--yes-playlist")
        } else {
            a += listOf("-o", "%(title).150B [%(id)s].%(ext)s", "--no-playlist")
        }
        if (job.mode == "video") {
            val h = job.quality.toIntOrNull()
            a += listOf("-f", "bv*+ba/b", "-S", if (h != null) "res:$h,vcodec:h264,acodec:aac" else "vcodec:h264,acodec:aac")
            a += listOf("--merge-output-format", "mp4")
            if (job.subtitles) a += listOf("--write-subs", "--sub-langs", subtitleLangs.ifBlank { "en.*,en" }, "--embed-subs")
            if (job.chapters) a += "--embed-chapters"
            if (job.embedThumbnail) a += "--embed-thumbnail"
        } else {
            a += listOf("-f", "ba/b", "-x", "--audio-format", job.quality, "--audio-quality", if (job.quality == "mp3") "320K" else "0")
            if (job.embedThumbnail) a += "--embed-thumbnail"
            if (job.chapters) a += "--embed-chapters"
        }
        return a
    }

    private fun clean(s: String): String {
        val t = s.trim()
        return if (t == "NA" || t.contains("Unknown")) "" else t
    }

    private fun num(s: String): Long? = s.trim().toDoubleOrNull()?.toLong()

    private fun stageName(pp: String) = when (pp) {
        "Merger" -> "Merging audio + video"
        "ExtractAudio" -> "Converting audio"
        "EmbedSubtitle" -> "Adding subtitles"
        "EmbedThumbnail" -> "Adding cover image"
        "FFmpegMetadata", "Metadata" -> "Saving chapters and info"
        "VideoConvertor", "VideoRemuxer" -> "Converting video"
        else -> "Finishing"
    }

    /** Apply one output line to the job; returns the updated job, or null if the line wasn't ours. */
    fun applyLine(j: Job, line: String): Job? {
        line.removePrefix("VLPROG|").takeIf { it != line }?.let { rest ->
            val p = rest.split('|')
            // status|percent|speed|eta|downloaded|total|total_est|playlist_index|n_entries|vcodec|acodec
            if (p.size < 11) return null
            val pct = p[1].trim().removeSuffix("%").trim().toDoubleOrNull() ?: 0.0
            val videoOnly = p[10].trim() == "none"
            val audioOnly = p[9].trim() == "none"
            // Video arrives as a video stream then an audio stream: one bar, video ≈ 90 %.
            val itemPct = when {
                j.mode == "video" && videoOnly -> pct * 0.9
                j.mode == "video" && audioOnly -> 90 + pct * 0.1
                else -> pct
            }
            var index = j.itemIndex
            var count = j.itemCount
            val i = num(p[7]); val n = num(p[8])
            if (i != null && n != null) { index = i.toInt(); count = n.toInt() }
            val overall = if (count > 1 && index > 0) ((index - 1) + itemPct / 100.0) / count * 100.0 else itemPct
            return j.copy(
                status = Status.DOWNLOADING,
                stage = if (j.mode == "video" && audioOnly) "Downloading audio" else "Downloading",
                percent = overall.coerceIn(0.0, 100.0),
                speed = clean(p[2]), eta = clean(p[3]),
                downloaded = num(p[4]) ?: j.downloaded,
                total = num(p[5]) ?: num(p[6]) ?: j.total,
                itemIndex = index, itemCount = count,
            )
        }
        line.removePrefix("VLPP|").takeIf { it != line }?.let { rest ->
            return j.copy(status = Status.PROCESSING, stage = stageName(rest.substringBefore('|')), speed = "", eta = "")
        }
        return null
    }

    fun labelFor(mode: String, quality: String): String = if (mode == "video") {
        when (quality) { "best" -> "Best · MP4"; "2160" -> "4K · MP4"; else -> "${quality}p · MP4" }
    } else quality.uppercase()
}
