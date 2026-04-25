package com.mercurylabs.headspace

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import org.json.JSONObject

/**
 * Foreground service that owns the UVC reader + the file writers for one
 * recording session. Lifecycle: started by MainActivity when a Headspace
 * device is detected, stopped on USB_DEVICE_DETACHED for that device.
 *
 * Output layout (in app-private external storage, no SAF needed):
 *   <getExternalFilesDir>/recordings/spc2_2026-04-25_15-30-22/
 *     ├ video.h264   raw Annex-B H.264 (with SEI NALs preserved)
 *     ├ imu.imu      CSV: boottime_ns,ax,ay,az,gx,gy,gz,mx,my,mz,mag_valid
 *     └ meta.json    session info (start time, device, samples count, …)
 *
 * (We write raw .h264 instead of muxing to MP4 live — keeps the recorder
 * simple and bit-perfect. A separate "muxer" pass converts to .mp4 on the
 * Recording detail screen so the user gets a playable file when they want
 * to share it. Same Awign frames either way.)
 */
class RecordingService : LifecycleService() {

    private var camera: UvcCamera? = null
    private var sessionDir: File? = null
    private var videoOut: FileOutputStream? = null
    private var imuOut: FileOutputStream? = null
    private var imuWriter: ImuCsvWriter? = null
    private var sei: SeiParser? = null
    private var startedAt: Date? = null
    private var firstFrameAt: Long = 0L
    private var bytesWritten: Long = 0L
    private var frameCount: Long = 0L
    private var imuCount: Long = 0L
    private val tracker = ImuRateTracker()

    private val detachReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (i.action != UsbManager.ACTION_USB_DEVICE_DETACHED) return
            val d: UsbDevice? = i.getParcelableExtra(UsbManager.EXTRA_DEVICE)
            if (d != null && d.deviceId == camera?.devName?.hashCode()) {
                Log.i(TAG, "device detached → stopping")
            }
            // We use deviceId-by-name as a coarse match; any detach during
            // a session warrants stopping (only one Headspace at a time).
            stopRecording("usb_detached")
        }
    }

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        startForeground(NOTIF_ID, buildNotification("Headspace recorder ready"))
        val filter = IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(detachReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(detachReceiver, filter)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        val device: UsbDevice? = intent?.getParcelableExtra(EXTRA_DEVICE)
        if (device == null) {
            Log.w(TAG, "onStartCommand without device extra — ignoring")
            return START_NOT_STICKY
        }
        if (camera != null) {
            Log.i(TAG, "session already running — ignoring re-start")
            return START_NOT_STICKY
        }
        startRecording(device)
        return START_STICKY
    }

    private fun startRecording(device: UsbDevice) {
        val dir = Recordings.newSession(this).also { sessionDir = it }
        val v = File(dir, "video.h264")
        val i = File(dir, "imu.imu")
        videoOut = FileOutputStream(v)
        imuOut = FileOutputStream(i)
        imuWriter = ImuCsvWriter(imuOut!!)
        startedAt = Date()
        bytesWritten = 0; frameCount = 0; imuCount = 0; firstFrameAt = 0
        sei = SeiParser { s ->
            imuWriter?.write(s)
            imuCount++
            tracker.tick(s.boottimeNs)
        }
        Log.i(TAG, "starting session: ${dir.absolutePath}")

        camera = UvcCamera(
            ctx = this,
            device = device,
            onFrame = { buf, off, len ->
                val out = videoOut ?: return@UvcCamera
                out.write(buf, off, len)
                bytesWritten += len
                if (firstFrameAt == 0L) firstFrameAt = System.currentTimeMillis()
                frameCount++   // approx — counts USB chunks, not frames
                sei?.feed(buf, off, len)
                if (frameCount % 30L == 0L) postStatus()
            },
            onError = { msg ->
                Log.e(TAG, "uvc error: $msg")
                updateNotification("Error: $msg")
            },
        )
        camera!!.requestPermissionAndOpen { camera!!.start() }
        updateNotification("Recording — ${device.productName ?: "device"}")
    }

    private fun stopRecording(reason: String) {
        val dir = sessionDir ?: return
        Log.i(TAG, "stopping session ($reason): ${dir.absolutePath}")
        camera?.stop(); camera = null
        try { videoOut?.flush(); videoOut?.close() } catch (_: Exception) {}
        try { imuOut?.flush(); imuOut?.close() } catch (_: Exception) {}
        videoOut = null; imuOut = null; imuWriter = null

        // Drop a meta.json so anyone opening the folder knows what they have.
        val meta = JSONObject().apply {
            put("device", camera?.devName ?: "Headspace SPC2 H264")
            put("started_at_utc", isoUtc(startedAt ?: Date()))
            put("ended_at_utc", isoUtc(Date()))
            put("video_file", "video.h264")
            put("imu_file", "imu.imu")
            put("video_bytes", bytesWritten)
            put("usb_chunks", frameCount)
            put("imu_samples", imuCount)
            put("imu_observed_hz", "%.1f".format(tracker.hz))
            put("stop_reason", reason)
        }
        File(dir, "meta.json").writeText(meta.toString(2))
        updateNotification("Saved · ${dir.name} · ${bytesWritten.humanBytes()}")
        // Flip back to idle but keep service alive so the next attach event
        // can restart cleanly. (System frees us when MainActivity finishes.)
        sessionDir = null; startedAt = null; sei = null
        tracker.reset()
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(detachReceiver) } catch (_: Exception) {}
        if (sessionDir != null) stopRecording("service_destroyed")
    }

    override fun onBind(intent: Intent): IBinder? { super.onBind(intent); return null }

    // ---- notification plumbing ----

    private fun postStatus() {
        val dir = sessionDir ?: return
        val text = "Rec %s · %d samples · %.0f Hz · %s".format(
            dir.name.removePrefix("spc2_"), imuCount, tracker.hz, bytesWritten.humanBytes())
        updateNotification(text)
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = getSystemService(NotificationManager::class.java)
        val ch = NotificationChannel(CH_ID, "Recording", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Headspace SPC2 capture status"
            setShowBadge(false)
        }
        mgr.createNotificationChannel(ch)
    }

    private fun buildNotification(text: String): Notification {
        val openMain = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, CH_ID)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("Headspace SPC2")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(openMain)
            .build()
    }
    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIF_ID, buildNotification(text))
    }

    companion object {
        const val EXTRA_DEVICE = "usb_device"
        private const val CH_ID = "rec"
        private const val NOTIF_ID = 1
        private const val TAG = "RecordingService"

        fun start(ctx: Context, device: UsbDevice) {
            val i = Intent(ctx, RecordingService::class.java).apply {
                putExtra(EXTRA_DEVICE, device)
            }
            ctx.startForegroundService(i)
        }
    }
}

private val ISO_UTC = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
    timeZone = TimeZone.getTimeZone("UTC")
}
private fun isoUtc(d: Date) = ISO_UTC.format(d)

/** Tracks running IMU sample rate from a stream of boottime_ns values. */
class ImuRateTracker {
    private var window = ArrayDeque<Long>()
    var hz: Double = 0.0; private set
    fun tick(ts: Long) {
        window.addLast(ts)
        while (window.size > 1 && (ts - window.first()) > 2_000_000_000L) window.removeFirst()
        if (window.size > 1) {
            val span = (window.last() - window.first()).toDouble()
            hz = if (span > 0) (window.size - 1) * 1e9 / span else 0.0
        }
    }
    fun reset() { window.clear(); hz = 0.0 }
}
