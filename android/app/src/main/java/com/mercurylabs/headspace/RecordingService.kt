package com.mercurylabs.headspace

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.concurrent.thread

/**
 * Recording session — coordinates the camera (via CameraHelper using
 * libuvc-bundled AndroidUSBCamera) and the MP4 muxer.
 *
 * The phone receives MJPEG-decoded NV21 frames, encodes to H.264 via
 * MediaCodec on the device's HW encoder, muxes to .mp4. IMU samples
 * arrive separately over the ACM serial channel and land in .imu CSV.
 */
class RecordingService : LifecycleService() {

    private var helper: CameraHelper? = null
    private var sessionDir: File? = null
    private var startedAt: Date? = null
    private var bytesWritten: Long = 0L
    private var frameCount: Long = 0L
    private var imuCount: Long = 0L
    private val tracker = ImuRateTracker()

    // Encoder + muxer
    private var encoder: MediaCodec? = null
    private var muxer: MediaMuxer? = null
    private var muxerStarted = false
    private var videoTrackId = -1
    private var encoderInputBufferIdx = -1

    override fun onCreate() {
        super.onCreate()
        CrashLog.line(this, "RecordingService", "onCreate")
        ensureChannel()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIF_ID, buildNotification("Headspace recorder ready"),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
            } else {
                startForeground(NOTIF_ID, buildNotification("Headspace recorder ready"))
            }
        } catch (e: Throwable) {
            CrashLog.writeException(this, "startForeground", e)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_DEVICE_ATTACHED -> {
                ensureHelper()
                RecorderState.setReady("Headspace SPC2")
                updateNotification("Device ready — tap RECORD")
            }
            ACTION_START_RECORDING -> startRecording()
            ACTION_STOP_RECORDING  -> stopRecording("user_stopped")
        }
        return START_STICKY
    }

    private fun ensureHelper() {
        if (helper != null) return
        helper = CameraHelper(this,
            onFrame = { nv21, w, h -> onNv21Frame(nv21, w, h) },
            onState = { opened, msg ->
                CrashLog.line(this, "Camera", "state opened=$opened msg=$msg")
                if (opened) {
                    RecorderState.setReady("Headspace SPC2")
                    updateNotification("Device ready — tap RECORD")
                } else {
                    RecorderState.setIdle()
                }
            },
        ).also { it.start() }
    }

    private fun startRecording() {
        if (encoder != null) {
            CrashLog.line(this, "Recorder", "already recording, ignored"); return
        }
        try {
            val dir = Recordings.newSession(this).also { sessionDir = it }
            startedAt = Date()
            bytesWritten = 0; frameCount = 0; imuCount = 0
            tracker.reset()

            // Set up MediaCodec H.264 encoder for 1920×1080@30
            val fmt = MediaFormat.createVideoFormat("video/avc", 1920, 1080).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar)
                setInteger(MediaFormat.KEY_BIT_RATE, 8_000_000)
                setInteger(MediaFormat.KEY_FRAME_RATE, 30)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }
            encoder = MediaCodec.createEncoderByType("video/avc").apply {
                configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                start()
            }
            muxer = MediaMuxer(File(dir, "video.mp4").absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxerStarted = false; videoTrackId = -1
            // IMU writer
            val imuFile = File(dir, "imu.imu")
            imuOut = FileOutputStream(imuFile).also { fos ->
                fos.write("boottime_ns,ax,ay,az,gx,gy,gz,mx,my,mz,mag_valid\n".toByteArray())
            }
            // Drain encoder in a loop on its own thread
            thread(name = "encoder-drain", isDaemon = true) { drainEncoder() }
            CrashLog.line(this, "Recorder", "session started: ${dir.absolutePath}")
            RecorderState.setRecording(dir.absolutePath, dir.name, "Headspace SPC2")
            updateNotification("Recording…")
        } catch (e: Throwable) {
            CrashLog.writeException(this, "startRecording", e)
            RecorderState.setError(e.message ?: "start failed")
        }
    }

    private var imuOut: FileOutputStream? = null

    private fun onNv21Frame(nv21: ByteArray, w: Int, h: Int) {
        val enc = encoder ?: return
        try {
            val idx = enc.dequeueInputBuffer(10_000)
            if (idx >= 0) {
                val buf = enc.getInputBuffer(idx)!!
                buf.clear()
                buf.put(nv21, 0, minOf(nv21.size, buf.capacity()))
                val pts = (frameCount * 1_000_000L / 30L)  // 30 fps
                enc.queueInputBuffer(idx, 0, nv21.size, pts, 0)
            }
            frameCount++
            if (frameCount % 30L == 0L) postStatus()
        } catch (e: Throwable) {
            CrashLog.writeException(this, "onNv21Frame", e)
        }
    }

    private fun drainEncoder() {
        val enc = encoder ?: return
        val mux = muxer ?: return
        val info = MediaCodec.BufferInfo()
        try {
            while (encoder != null) {
                val outIdx = enc.dequeueOutputBuffer(info, 10_000)
                when {
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        videoTrackId = mux.addTrack(enc.outputFormat)
                        mux.start()
                        muxerStarted = true
                        CrashLog.line(this, "Encoder", "muxer started, track=$videoTrackId")
                    }
                    outIdx >= 0 -> {
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                            // SPS/PPS already absorbed via outputFormat — drop
                        } else if (muxerStarted && info.size > 0) {
                            val out = enc.getOutputBuffer(outIdx)!!
                            out.position(info.offset)
                            out.limit(info.offset + info.size)
                            mux.writeSampleData(videoTrackId, out, info)
                            bytesWritten += info.size
                        }
                        enc.releaseOutputBuffer(outIdx, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    }
                }
            }
        } catch (e: Throwable) {
            CrashLog.writeException(this, "drainEncoder", e)
        }
    }

    private fun stopRecording(reason: String) {
        val dir = sessionDir ?: return
        try { encoder?.signalEndOfInputStream() } catch (_: Throwable) {}
        Thread.sleep(200)
        try { encoder?.stop() } catch (_: Throwable) {}
        try { encoder?.release() } catch (_: Throwable) {}
        encoder = null
        try { if (muxerStarted) muxer?.stop() } catch (_: Throwable) {}
        try { muxer?.release() } catch (_: Throwable) {}
        muxer = null
        try { imuOut?.flush(); imuOut?.close() } catch (_: Throwable) {}
        imuOut = null

        val meta = JSONObject().apply {
            put("device", "Headspace SPC2 MJPEG")
            put("started_at_utc", isoUtc(startedAt ?: Date()))
            put("ended_at_utc", isoUtc(Date()))
            put("video_file", "video.mp4")
            put("imu_file", "imu.imu")
            put("video_bytes", bytesWritten)
            put("frames", frameCount)
            put("imu_samples", imuCount)
            put("imu_observed_hz", "%.1f".format(tracker.hz))
            put("stop_reason", reason)
        }
        File(dir, "meta.json").writeText(meta.toString(2))
        CrashLog.line(this, "Recorder", "session stopped ($reason): ${dir.absolutePath} / ${bytesWritten} bytes / ${frameCount} frames")
        sessionDir = null; startedAt = null
        RecorderState.setReady("Headspace SPC2")
        updateNotification("Saved · $bytesWritten bytes · $frameCount frames")
    }

    override fun onDestroy() {
        super.onDestroy()
        if (sessionDir != null) stopRecording("service_destroyed")
        helper?.stop(); helper = null
    }

    override fun onBind(intent: Intent): IBinder? { super.onBind(intent); return null }

    private var lastStatsAt = System.currentTimeMillis()
    private var lastBytes = 0L
    private fun postStatus() {
        val dir = sessionDir ?: return
        val now = System.currentTimeMillis()
        val mbps = ((bytesWritten - lastBytes) * 8.0) / ((now - lastStatsAt).coerceAtLeast(1) * 1000.0)
        lastStatsAt = now; lastBytes = bytesWritten
        RecorderState.updateStats(bytesWritten, imuCount, tracker.hz, mbps)
        updateNotification("Rec %s · %d frames · %s".format(
            dir.name.removePrefix("spc2_"), frameCount, bytesWritten.humanBytes()))
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = getSystemService(NotificationManager::class.java)
        val ch = NotificationChannel(CH_ID, "Recording", NotificationManager.IMPORTANCE_LOW)
        mgr.createNotificationChannel(ch)
    }

    private fun buildNotification(text: String): Notification {
        val openMain = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
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
        const val ACTION_DEVICE_ATTACHED = "com.mercurylabs.headspace.DEVICE_ATTACHED"
        const val ACTION_START_RECORDING = "com.mercurylabs.headspace.START"
        const val ACTION_STOP_RECORDING  = "com.mercurylabs.headspace.STOP"
        private const val CH_ID = "rec"
        private const val NOTIF_ID = 1
        private const val TAG = "RecordingService"

        fun start(ctx: android.content.Context, device: android.hardware.usb.UsbDevice) {
            ctx.startForegroundService(
                Intent(ctx, RecordingService::class.java).setAction(ACTION_DEVICE_ATTACHED))
        }
        fun startRecording(ctx: android.content.Context) {
            ctx.startForegroundService(
                Intent(ctx, RecordingService::class.java).setAction(ACTION_START_RECORDING))
        }
        fun stopRecording(ctx: android.content.Context) {
            ctx.startForegroundService(
                Intent(ctx, RecordingService::class.java).setAction(ACTION_STOP_RECORDING))
        }
    }
}

private val ISO_UTC = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
    timeZone = TimeZone.getTimeZone("UTC")
}
private fun isoUtc(d: Date) = ISO_UTC.format(d)

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
