package com.mercurylabs.headspace

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** A single recording session living on disk. */
data class Recording(
    val dir: File,
    val videoFile: File,
    val imuFile: File,
    val metaFile: File,
    val startedAt: Date,
    val sizeBytes: Long,
) {
    val name: String get() = dir.name
    val durationSecondsApprox: Long
        get() {
            val ageMs = System.currentTimeMillis() - startedAt.time
            return ageMs / 1000
        }
}

/** Where on disk we stash recordings: app-private, no SAF prompts.
 *  Also accessible via "Files" app: Internal storage → Android → data → com.mercurylabs.headspace → files → recordings/ */
object Recordings {
    private val TS_FMT = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }
    private val DIR_PREFIX = "spc2_"

    fun root(ctx: Context): File =
        File(ctx.getExternalFilesDir(null), "recordings").also { it.mkdirs() }

    /** Make a fresh session folder named with the current UTC time. */
    fun newSession(ctx: Context): File {
        val name = DIR_PREFIX + TS_FMT.format(Date())
        return File(root(ctx), name).also { it.mkdirs() }
    }

    fun list(ctx: Context): List<Recording> {
        val r = root(ctx)
        return (r.listFiles { f -> f.isDirectory && f.name.startsWith(DIR_PREFIX) } ?: emptyArray())
            .mapNotNull { d ->
                val v = File(d, "video.mp4")
                val i = File(d, "imu.imu")
                val m = File(d, "meta.json")
                val name = d.name.removePrefix(DIR_PREFIX)
                val date = try { TS_FMT.parse(name) } catch (_: Exception) { Date(d.lastModified()) }
                if (date == null) return@mapNotNull null
                Recording(d, v, i, m, date, dirSize(d))
            }
            .sortedByDescending { it.startedAt }
    }

    private fun dirSize(d: File): Long =
        (d.listFiles() ?: emptyArray()).sumOf { if (it.isFile) it.length() else dirSize(it) }
}

fun Long.humanBytes(): String = when {
    this >= 1L shl 30 -> "%.1f GB".format(this.toDouble() / (1L shl 30))
    this >= 1L shl 20 -> "%.1f MB".format(this.toDouble() / (1L shl 20))
    this >= 1L shl 10 -> "%.1f KB".format(this.toDouble() / (1L shl 10))
    else              -> "$this B"
}
