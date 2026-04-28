package com.mercurylabs.headspace

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File

/**
 * Publishes a finished recording into user-visible storage so the wearer
 * (and anyone with USB MTP / system Files app access) can find it without
 * knowing about Android scoped-storage paths.
 *
 *   video.mp4        -> Movies/Headspace/<session>/video.mp4    (gallery + Files)
 *   imu.imu          -> Download/Headspace/<session>/imu.imu     (Files / MTP)
 *   meta.json        -> Download/Headspace/<session>/meta.json
 *
 * Idempotent: on success we drop a `.exported` marker in the source session
 * directory, so backfill on app launch can skip already-published sessions
 * without re-querying MediaStore (which would require READ_MEDIA_VIDEO on
 * Android 13+ and isn't worth the permission).
 *
 * On Android 9 and below this is a no-op. The fleet phones we care about
 * are all Android 11+.
 */
object MediaStoreExporter {
    private const val TAG = "MediaStoreExporter"
    private const val MARKER = ".exported"
    private const val BUCKET = "Headspace"

    /** Export one finished session. Returns true if everything published
     *  (or was already published earlier), false if any file failed and a
     *  later retry should be attempted. Never throws. */
    fun exportSession(ctx: Context, sessionDir: File): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return true
        if (!sessionDir.isDirectory) return false
        if (File(sessionDir, MARKER).exists()) return true

        val name = sessionDir.name
        val video = File(sessionDir, "video.mp4")
        val imu   = File(sessionDir, "imu.imu")
        val meta  = File(sessionDir, "meta.json")

        // We only insist on the video; IMU/meta are best-effort. A session
        // without video is genuinely useless so don't bother publishing.
        if (!video.exists() || video.length() == 0L) {
            Log.w(TAG, "skip $name: no video.mp4")
            return false
        }

        val okV = publish(ctx, video,
            relPath = "${Environment.DIRECTORY_MOVIES}/$BUCKET/$name",
            mime = "video/mp4",
            collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY))
        val okI = if (imu.exists())  publish(ctx, imu,
            relPath = "${Environment.DIRECTORY_DOWNLOADS}/$BUCKET/$name",
            mime = "application/octet-stream",
            collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY))
            else true
        val okM = if (meta.exists()) publish(ctx, meta,
            relPath = "${Environment.DIRECTORY_DOWNLOADS}/$BUCKET/$name",
            mime = "application/json",
            collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY))
            else true

        val success = okV && okI && okM
        if (success) {
            try { File(sessionDir, MARKER).writeText(System.currentTimeMillis().toString()) }
            catch (e: Throwable) { Log.w(TAG, "marker write failed for $name: ${e.message}") }
            Log.i(TAG, "published $name")
        } else {
            Log.w(TAG, "partial publish $name: video=$okV imu=$okI meta=$okM")
        }
        return success
    }

    /** Walk every session directory and export anything missing the
     *  marker. Cheap: only scans local FS and copies files that haven't
     *  been published yet. Safe to call on every service start. */
    fun backfill(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val root = Recordings.root(ctx)
        val sessions = root.listFiles { f -> f.isDirectory && f.name.startsWith("spc2_") }
            ?: return
        var done = 0; var skipped = 0; var failed = 0
        for (s in sessions) {
            if (File(s, MARKER).exists()) { skipped++; continue }
            if (exportSession(ctx, s)) done++ else failed++
        }
        if (done + failed > 0) {
            Log.i(TAG, "backfill: published=$done already=$skipped failed=$failed")
        }
    }

    private fun publish(
        ctx: Context,
        src: File,
        relPath: String,
        mime: String,
        collection: android.net.Uri,
    ): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        val resolver = ctx.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, src.name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relPath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = try { resolver.insert(collection, values) }
                  catch (e: Throwable) { Log.w(TAG, "insert failed for ${src.name}: ${e.message}"); null }
            ?: return false
        try {
            resolver.openOutputStream(uri, "w").use { out ->
                if (out == null) { Log.w(TAG, "openOutputStream null for ${src.name}"); return false }
                src.inputStream().use { input -> input.copyTo(out, bufferSize = 1 shl 16) }
            }
            val finalize = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            resolver.update(uri, finalize, null, null)
            return true
        } catch (e: Throwable) {
            Log.w(TAG, "copy failed for ${src.name}: ${e.message}")
            try { resolver.delete(uri, null, null) } catch (_: Throwable) {}
            return false
        }
    }
}
