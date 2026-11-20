package com.bizfunnel.vidloot.engine

import android.content.Context
import android.util.Log
import com.bizfunnel.vidloot.Prefs
import com.bizfunnel.vidloot.license.License
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Thrown when a plan limit blocks a download; the UI shows the upgrade sheet. */
class ProRequiredException(val kind: String, message: String) : Exception(message)

/**
 * The download queue. Runs yt-dlp (through youtubedl-android) for each job,
 * parses progress, and exposes the list as a StateFlow for the screens and
 * the notification.
 */
object DownloadManager {
    private const val TAG = "Vidloot"
    private const val HISTORY_LIMIT = 300

    private lateinit var app: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()

    private val _jobs = MutableStateFlow<List<Job>>(emptyList())
    val jobs: StateFlow<List<Job>> = _jobs

    /** null = engine not ready yet; otherwise the startup error, or "" when OK. */
    private val _engineError = MutableStateFlow<String?>(null)
    val engineError: StateFlow<String?> = _engineError

    private val errorLines = mutableMapOf<String, StringBuilder>()

    private fun historyFile() = File(app.filesDir, "jobs.json")
    private fun workDir(id: String) = File(app.filesDir, "work/$id")
    private fun cacheDir() = File(app.filesDir, "ytdlp-cache").apply { mkdirs() }

    fun init(context: Context) {
        app = context.applicationContext
        _jobs.value = try {
            val arr = JSONArray(historyFile().readText())
            (0 until arr.length()).map { Job.fromJson(arr.getJSONObject(it)) }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun setEngineReady(error: String?) {
        _engineError.value = error ?: ""
    }

    // ---------------------------------------------------------------- info

    suspend fun fetchInfo(url: String): MediaInfo = withContext(Dispatchers.IO) {
        val u = url.trim()
        require(u.startsWith("http://") || u.startsWith("https://")) { "That doesn't look like a link. It should start with https://" }
        val request = YoutubeDLRequest(u)
            .addOption("-J")
            .addOption("--flat-playlist")
            .addOption("--no-playlist")
            .addOption("--no-warnings")
            .addOption("--cache-dir", cacheDir().absolutePath)
        val out = try {
            YoutubeDL.getInstance().execute(request, null, false, null).out
        } catch (e: Exception) {
            throw IllegalStateException(InfoParser.cleanError(e.message))
        }
        val json = out.lineSequence().firstOrNull { it.trimStart().startsWith("{") }
            ?: throw IllegalStateException("The engine returned something unexpected for this link.")
        InfoParser.parse(u, JSONObject(json))
    }

    // ---------------------------------------------------------------- queue ops

    private fun save() {
        val list = _jobs.value.takeLast(HISTORY_LIMIT)
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        try { historyFile().writeText(arr.toString()) } catch (e: Exception) { Log.w(TAG, "save failed", e) }
    }

    private fun update(id: String, change: (Job) -> Job): Job? = synchronized(lock) {
        var result: Job? = null
        _jobs.value = _jobs.value.map { if (it.id == id) change(it).also { j -> result = j } else it }
        result
    }

    private fun find(id: String) = _jobs.value.firstOrNull { it.id == id }

    fun enqueue(req: DownloadRequest): Job {
        val ent = License.entitlements
        val video = req.mode == "video"
        if (video) {
            req.quality.toIntOrNull()?.let { h ->
                if (!ent.allowsHeight(h)) throw ProRequiredException("quality", "The ${ent.plan} plan downloads up to ${ent.maxHeight}p.")
            }
        } else require(req.quality in setOf("mp3", "m4a", "opus")) { "Unknown audio format." }
        if (req.playlist && !ent.playlists) throw ProRequiredException("playlists", "Whole playlists need Pro.")
        if (req.subtitles && video && !ent.subtitles) throw ProRequiredException("subtitles", "Subtitles need Pro.")

        val job = Job(
            id = UUID.randomUUID().toString(), url = req.url.trim(), title = req.title, thumbnail = req.thumbnail,
            mode = req.mode, quality = req.quality, label = Ytdlp.labelFor(req.mode, req.quality),
            playlist = req.playlist, subtitles = req.subtitles && video,
            embedThumbnail = req.embedThumbnail, chapters = req.chapters,
        )
        synchronized(lock) { _jobs.value = _jobs.value + job }
        save()
        pump()
        return job
    }

    /** Start queued jobs while there's room under the plan's parallel limit. */
    fun pump() {
        val limit = License.entitlements.parallelDownloads.coerceAtLeast(1)
        val start = synchronized(lock) {
            val running = _jobs.value.count { Status.isActive(it.status) }
            val ids = _jobs.value.filter { it.status == Status.QUEUED }.take((limit - running).coerceAtLeast(0)).map { it.id }.toSet()
            _jobs.value = _jobs.value.map { if (it.id in ids) it.copy(status = Status.DOWNLOADING, stage = "Starting…") else it }
            ids
        }
        if (start.isNotEmpty()) DownloadService.start(app)
        start.forEach { id -> scope.launch { run(id) } }
    }

    fun pause(id: String) {
        val wasQueued = find(id)?.status == Status.QUEUED
        update(id) { if (Status.isFinished(it.status)) it else it.copy(status = Status.PAUSED, stage = "Paused", speed = "", eta = "") }
        if (!wasQueued) kill(id)
        save()
    }

    fun resume(id: String) {
        update(id) {
            if (it.status == Status.PAUSED || it.status == Status.ERROR) it.copy(status = Status.QUEUED, stage = "Waiting", error = null) else it
        }
        pump()
    }

    fun cancel(id: String) {
        update(id) {
            if (Status.isFinished(it.status)) it
            else it.copy(status = Status.CANCELED, stage = "Canceled", finishedAt = System.currentTimeMillis())
        }
        kill(id)
        scope.launch {
            kotlinx.coroutines.delay(800)
            workDir(id).deleteRecursively()
        }
        save()
    }

    fun remove(id: String) {
        synchronized(lock) { _jobs.value = _jobs.value.filterNot { it.id == id && Status.isFinished(it.status) } }
        save()
    }

    fun clearFinished() {
        synchronized(lock) { _jobs.value = _jobs.value.filterNot { Status.isFinished(it.status) } }
        save()
    }

    fun pauseAll() {
        _jobs.value.filter { !Status.isFinished(it.status) && it.status != Status.PAUSED }.forEach { pause(it.id) }
    }

    private fun kill(id: String) {
        try { YoutubeDL.getInstance().destroyProcessById(id) } catch (_: Exception) { }
    }

    // ---------------------------------------------------------------- running

    private suspend fun run(id: String) {
        val job = find(id) ?: return
        val dir = workDir(id).apply { mkdirs() }
        val settings = Prefs.settings.value
        val request = YoutubeDLRequest(job.url)
            .addOption("--cache-dir", cacheDir().absolutePath)
            .addCommands(Ytdlp.buildArgs(job, dir.absolutePath, settings.subtitleLangs))
        val errors = StringBuilder()
        synchronized(lock) { errorLines[id] = errors }
        var lastEmit = 0L

        val ok = try {
            // redirectErrorStream = true: post-processing progress and errors arrive as lines too.
            YoutubeDL.getInstance().execute(request, id, true) { _, _, line ->
                if (line.startsWith("ERROR:")) errors.appendLine(line)
                val now = System.currentTimeMillis()
                synchronized(lock) {
                    val cur = _jobs.value.firstOrNull { it.id == id }
                    if (cur != null && !Status.isFinished(cur.status) && cur.status != Status.PAUSED) {
                        val next = Ytdlp.applyLine(cur, line)
                        // Keep the UI smooth without flooding it: ~6 updates a second.
                        if (next != null && (next.status != Status.DOWNLOADING || now - lastEmit > 150)) {
                            _jobs.value = _jobs.value.map { if (it.id == id) next else it }
                            lastEmit = now
                        }
                    }
                }
            }
            true
        } catch (e: YoutubeDL.CanceledException) {
            false
        } catch (e: Exception) {
            if (errors.isEmpty()) errors.appendLine(e.message ?: "Download failed.")
            false
        }

        val current = find(id)
        if (current == null || current.status == Status.PAUSED || current.status == Status.CANCELED) {
            finishRun(id)
            return
        }

        if (ok) {
            update(id) { it.copy(status = Status.PROCESSING, stage = "Saving to Downloads", speed = "", eta = "") }
            try {
                val saved = Saver.export(app, dir)
                if (saved.isEmpty()) error("No file was produced.")
                update(id) {
                    it.copy(
                        status = Status.DONE, stage = "Finished", percent = 100.0,
                        fileUri = saved.first().uri.toString(), fileName = saved.first().name, savedCount = saved.size,
                        finishedAt = System.currentTimeMillis(),
                    )
                }
                dir.deleteRecursively()
            } catch (e: Exception) {
                update(id) { it.copy(status = Status.ERROR, stage = "Failed", error = "Couldn't save to Downloads: ${e.message}", finishedAt = System.currentTimeMillis()) }
            }
        } else {
            update(id) {
                it.copy(status = Status.ERROR, stage = "Failed", error = InfoParser.cleanError(errors.toString()), finishedAt = System.currentTimeMillis())
            }
        }
        finishRun(id)
    }

    private fun finishRun(id: String) {
        synchronized(lock) { errorLines.remove(id) }
        save()
        pump()
    }

    // ---------------------------------------------------------------- engine

    fun engineVersion(): String? = try { YoutubeDL.getInstance().version(app) } catch (_: Exception) { null }

    suspend fun updateEngine(): String = withContext(Dispatchers.IO) {
        val status = YoutubeDL.getInstance().updateYoutubeDL(app, YoutubeDL.UpdateChannel.STABLE)
        val v = engineVersion() ?: ""
        if (status == YoutubeDL.UpdateStatus.DONE) "Updated to $v" else "Already up to date ($v)"
    }
}
