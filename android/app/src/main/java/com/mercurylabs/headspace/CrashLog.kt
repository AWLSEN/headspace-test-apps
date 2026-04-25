package com.mercurylabs.headspace

import android.content.Context
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Tiny on-device crash + diagnostic log so we can debug a real-phone
 * problem without ADB. All writes go to a single rolling text file in
 * app-private storage. The Crashes screen reads it back.
 */
object CrashLog {
    private const val MAX_BYTES = 256 * 1024
    private val TS = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    fun init(ctx: Context) {
        val app = ctx.applicationContext
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                writeException(app, "UNCAUGHT in ${t.name}", e)
            } catch (_: Throwable) {}
            prev?.uncaughtException(t, e)
        }
    }

    fun file(ctx: Context): File =
        File(ctx.applicationContext.getExternalFilesDir(null), "crashes.log")

    @Synchronized
    fun line(ctx: Context, tag: String, msg: String) {
        val f = file(ctx)
        try {
            // Roll if oversized
            if (f.exists() && f.length() > MAX_BYTES) f.delete()
            f.appendText("[${TS.format(Date())}] $tag: $msg\n")
        } catch (e: Throwable) {
            Log.w("CrashLog", "couldn't write log: $e")
        }
        Log.i(tag, msg)
    }

    @Synchronized
    fun writeException(ctx: Context, where: String, t: Throwable) {
        val sw = StringWriter().also { t.printStackTrace(PrintWriter(it)) }
        line(ctx, "CRASH", "$where\n$sw")
    }

    fun read(ctx: Context, lastN: Int = 200): List<String> {
        val f = file(ctx)
        if (!f.exists()) return emptyList()
        return try {
            f.readLines().takeLast(lastN)
        } catch (_: Throwable) {
            emptyList()
        }
    }

    fun clear(ctx: Context) { file(ctx).delete() }
}
