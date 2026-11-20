package com.bizfunnel.vidloot.engine

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import kotlin.coroutines.resume

/**
 * yt-dlp writes into the app's private work folder; finished files are then
 * copied into the phone's Downloads/Vidloot folder, where gallery, music and
 * file apps can see them.
 */
object Saver {
    const val FOLDER = "Vidloot"
    private val SKIP = setOf("part", "ytdl", "temp", "tmp", "json", "webp", "jpg", "png", "vtt", "srt")

    data class Saved(val uri: Uri, val name: String)

    /** Copies every finished media file under [dir] and returns them, first one first. */
    suspend fun export(context: Context, dir: File): List<Saved> {
        val files = dir.walkTopDown()
            .filter { it.isFile && it.extension.lowercase() !in SKIP && !it.name.endsWith(".part") }
            .sortedBy { it.path }
            .toList()
        return files.map { f ->
            val sub = f.parentFile?.takeIf { it != dir }?.name
            save(context, f, sub)
        }
    }

    private fun mimeOf(f: File): String =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(f.extension.lowercase()) ?: "application/octet-stream"

    private suspend fun save(context: Context, f: File, subFolder: String?): Saved {
        val relative = listOfNotNull(Environment.DIRECTORY_DOWNLOADS, FOLDER, subFolder).joinToString("/")
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, f.name)
                put(MediaStore.Downloads.MIME_TYPE, mimeOf(f))
                put(MediaStore.Downloads.RELATIVE_PATH, relative)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: error("Couldn't create the file in Downloads.")
            resolver.openOutputStream(uri)!!.use { out -> f.inputStream().use { it.copyTo(out) } }
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            Saved(uri, f.name)
        } else {
            @Suppress("DEPRECATION")
            val base = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val target = File(listOfNotNull(base.path, FOLDER, subFolder).joinToString("/"), f.name)
            target.parentFile?.mkdirs()
            f.copyTo(target, overwrite = true)
            val uri = suspendCancellableCoroutine { cont ->
                MediaScannerConnection.scanFile(context, arrayOf(target.path), arrayOf(mimeOf(f))) { _, u ->
                    cont.resume(u ?: Uri.fromFile(target))
                }
            }
            Saved(uri, f.name)
        }
    }
}
