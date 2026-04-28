package com.mercurylabs.headspace

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaCodec
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

/**
 * Recording session — new architecture (2026-04-26 pivot).
 *
 * Pi runs `rpicam-vid` (HW H.264 encoder) → CDC-ACM bytes on `/dev/ttyGS1`.
 * Phone reads bytes via `H264AcmReader`, parses access units, MUXES them
 * into MP4 with no decode, no re-encode. Sync via SEI-injected `boottime_ns`
 * per AU (Pi side, h264_bridge); IMU samples on `/dev/ttyGS0` carry the
 * same `boottime_ns`. One clock, no drift.
 */
class RecordingService : LifecycleService() {

    private var sessionDir: File? = null
    private var startedAt: Date? = null
    private var bytesWritten: Long = 0L
    private var auCount: Long = 0L
    private var imuCount: Long = 0L
    private var firstAuPtsNs: Long = 0L
    private val tracker = ImuRateTracker()

    private var acm: AcmSerial? = null
    private var h264: H264AcmReader? = null
    private var acmConn: android.hardware.usb.UsbDeviceConnection? = null

    // Muxer state — populated once we see SPS+PPS in the first AU after
    // recording begins. Until then we drop AUs silently.
    private var muxer: MediaMuxer? = null
    private var muxerStarted = false
    private var videoTrackId = -1
    @Volatile private var recording = false

    // Preview decoder — runs ALWAYS (recording or not) so user sees a live
    // feed in the SurfaceView. Configured once we have the preview Surface
    // AND the first IDR (for SPS/PPS). HW decode is essentially free on any
    // modern phone — encode is what's expensive, not decode.
    private var previewDec: MediaCodec? = null
    private var previewSurface: android.view.Surface? = null
    private var previewLogs = 0

    @Volatile private var h264StatBytes = 0L
    @Volatile private var h264StatAus = 0L

    private val detachReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(ctx: android.content.Context, intent: Intent) {
            if (intent.action != android.hardware.usb.UsbManager.ACTION_USB_DEVICE_DETACHED) return
            val device: android.hardware.usb.UsbDevice? =
                intent.getParcelableExtra(android.hardware.usb.UsbManager.EXTRA_DEVICE)
            // Only react to OUR device detaching (not e.g. an unrelated USB
            // device that the phone might also be talking to via OTG).
            if (device != null && device.vendorId != 0x1d6b && device.productId != 0x0104 &&
                device.productName?.contains("Headspace", ignoreCase = true) != true) {
                return
            }
            CrashLog.line(this@RecordingService, "USB", "device detached — tearing down")
            teardownUsb()
        }
    }

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
        val filter = android.content.IntentFilter(
            android.hardware.usb.UsbManager.ACTION_USB_DEVICE_DETACHED)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(detachReceiver, filter, android.content.Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(detachReceiver, filter)
        }
        // Catch any sessions that finished before we shipped the exporter,
        // or that failed to publish previously. Idempotent + off-thread.
        Thread({
            try { MediaStoreExporter.backfill(this) }
            catch (e: Throwable) { CrashLog.writeException(this, "exportBackfill", e) }
        }, "session-backfill").start()
    }

    /** Cleanly tear down all USB-attached state so a re-plug creates fresh
     *  connections. Stops a recording in progress, closes ACM + H.264 readers,
     *  releases the USB connection, and resets state to IDLE so the UI shows
     *  "WAITING FOR DEVICE". */
    private fun teardownUsb() {
        if (recording) stopRecording("device_detached")
        try { acm?.close() } catch (_: Throwable) {}
        try { h264?.close() } catch (_: Throwable) {}
        try { previewDec?.stop() } catch (_: Throwable) {}
        try { previewDec?.release() } catch (_: Throwable) {}
        try { acmConn?.close() } catch (_: Throwable) {}
        acm = null; h264 = null; previewDec = null; acmConn = null
        previewSurface = null
        RecorderState.acmSender = null
        RecorderState.setIdle()
        updateNotification("Plug in Headspace SPC2")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_DEVICE_ATTACHED -> {
                ensureUsb()
                RecorderState.setReady("Headspace SPC2")
                updateNotification("Device ready — tap RECORD")
            }
            ACTION_START_RECORDING -> startRecording()
            ACTION_STOP_RECORDING  -> stopRecording("user_stopped")
        }
        return START_STICKY
    }

    private fun ensureUsb() {
        if (acm != null) return
        val mgr = getSystemService(android.content.Context.USB_SERVICE) as android.hardware.usb.UsbManager
        val device = mgr.deviceList.values.firstOrNull {
            it.vendorId == 0x1d6b && it.productId == 0x0104
        } ?: run {
            CrashLog.line(this, "USB", "no Headspace SPC2 device attached")
            return
        }
        if (mgr.hasPermission(device)) {
            openAcm(device)
        } else {
            requestUsbPermission(mgr, device)
        }
    }

    private fun requestUsbPermission(mgr: android.hardware.usb.UsbManager, device: android.hardware.usb.UsbDevice) {
        val ACTION = "com.mercurylabs.headspace.USB_PERMISSION"
        val rcv = object : android.content.BroadcastReceiver() {
            override fun onReceive(ctx: android.content.Context, intent: Intent) {
                if (intent.action != ACTION) return
                val granted = intent.getBooleanExtra(android.hardware.usb.UsbManager.EXTRA_PERMISSION_GRANTED, false)
                CrashLog.line(this@RecordingService, "USB", "permission grant result: $granted")
                try { ctx.unregisterReceiver(this) } catch (_: Throwable) {}
                if (granted) openAcm(device)
                else RecorderState.setError("USB permission denied")
            }
        }
        val flags = if (Build.VERSION.SDK_INT >= 33) android.content.Context.RECEIVER_NOT_EXPORTED else 0
        registerReceiver(rcv, android.content.IntentFilter(ACTION), flags)
        val pi = PendingIntent.getBroadcast(this, 0, Intent(ACTION).setPackage(packageName),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        mgr.requestPermission(device, pi)
        CrashLog.line(this, "USB", "permission requested for ${device.productName}")
    }

    private fun openAcm(device: android.hardware.usb.UsbDevice) {
        if (acm != null) return
        try {
            val mgr = getSystemService(android.content.Context.USB_SERVICE) as android.hardware.usb.UsbManager
            val conn = mgr.openDevice(device) ?: run {
                CrashLog.line(this, "ACM", "openDevice returned null"); return
            }
            acmConn = conn
            val a = AcmSerial.open(conn, device) ?: run {
                CrashLog.line(this, "ACM", "AcmSerial.open returned null")
                conn.close(); acmConn = null; return
            }
            acm = a
            a.startReader { line -> handleAcmLine(line) }
            // Expose to UI components (e.g. WiFi setup) so they can send
            // commands without opening a second UsbDeviceConnection — that
            // would call claimInterface(force=true) and steal the IMU
            // endpoint, leaving this reader silently dead.
            RecorderState.acmSender = { cmd ->
                try { a.sendLine(cmd) } catch (e: Throwable) {
                    CrashLog.line(this, "ACM", "sendLine failed: ${e.message}")
                }
            }
            CrashLog.line(this, "ACM", "IMU reader started (ttyGS0)")

            // Open the SECOND ACM (acm.GS1) for raw H.264 bytes.
            val h = H264AcmReader.openSecond(conn, device)
            if (h != null) {
                h264 = h
                h.startReader(
                    onAccessUnit = { au, len, isKey -> onAccessUnit(au, len, isKey) },
                    onByteStats = { bps, aps, total ->
                        Log.i(TAG, "H264: ${bps/1024} KB/s, ${aps} AU/s, total=${total/1024} KB")
                        h264StatBytes = total
                        h264StatAus = h264StatAus  // updated in onAccessUnit
                    },
                )
                CrashLog.line(this, "H264", "reader started (ttyGS1)")
                RecorderState.setReady("Headspace SPC2")
                updateNotification("Device ready — tap RECORD")
            } else {
                CrashLog.line(this, "H264", "openSecond returned null — only one ACM iface")
            }
        } catch (e: Throwable) {
            CrashLog.writeException(this, "openAcm", e)
        }
    }

    private fun handleAcmLine(line: String) {
        if (line.length >= 2 && line[0] == 'I' && line[1] == ':') {
            val payload = line.substring(2)
            val csv = payload.replace(' ', ',') + "\n"
            val ts = payload.substringBefore(' ').toLongOrNull() ?: 0L
            try {
                imuOut?.write(csv.toByteArray())
                imuCount++
                tracker.tick(ts)
            } catch (_: Throwable) { /* writer closed / not recording */ }
            return
        }
        // Anything else (PING/STATUS/WIFI/TAILSCALE replies) → broadcast to
        // the UI. Keeps the WiFi screen from needing its own USB connection.
        RecorderState.emitAcmResponse(line)
    }

    /** Called by H264AcmReader for each complete H.264 access unit.
     *  - If we're not recording: bytes counted, AU dropped.
     *  - If recording but muxer not started yet: parse SPS+PPS, configure
     *    MediaFormat, addTrack, start muxer. Then write THIS AU.
     *  - Otherwise: just write the AU with PTS. */
    private var muxerSearchLogs = 0
    private var seiLogs = 0

    /** Scan an AU for our SEI marker. Returns the embedded boottime_ns,
     *  or 0 if no marker found. Walks all SEI NALs and looks for the
     *  16-byte UUID "MERCURY-LABS-IMU" followed by 8 bytes of timestamp.
     *  Handles RBSP emulation-prevention 0x03 bytes inside the payload. */
    private fun extractSeiTimestamp(au: ByteArray, len: Int): Long {
        // Find each NAL of type 6 (SEI), then scan its payload.
        var i = 0
        while (i + 5 < len) {
            val sc4 = au[i] == 0.toByte() && au[i+1] == 0.toByte() &&
                      au[i+2] == 0.toByte() && au[i+3] == 1.toByte()
            val sc3 = au[i] == 0.toByte() && au[i+1] == 0.toByte() &&
                      au[i+2] == 1.toByte()
            if (!(sc4 || sc3)) { i++; continue }
            val scLen = if (sc4) 4 else 3
            val nalStart = i + scLen
            if (nalStart >= len) break
            val nalType = au[nalStart].toInt() and 0x1f
            // Find end of this NAL (next start code or EOF)
            var nalEnd = nalStart + 1
            while (nalEnd + 2 < len) {
                val ss = (au[nalEnd] == 0.toByte() && au[nalEnd+1] == 0.toByte() &&
                          (au[nalEnd+2] == 1.toByte() ||
                           (nalEnd + 3 < len && au[nalEnd+2] == 0.toByte() && au[nalEnd+3] == 1.toByte())))
                if (ss) break
                nalEnd++
            }
            if (nalType == 6) {
                // Walk SEI messages — but we know the layout: payload_type
                // (≤127, 1 byte), payload_size (255-coded), payload bytes.
                // We do a simple scan for the UUID since it's unique.
                var p = nalStart + 1  // skip NAL header
                while (p + 24 < nalEnd) {
                    if (au[p] == 0x4D.toByte() && au[p+1] == 0x45.toByte() &&
                        au[p+2] == 0x52.toByte() && au[p+3] == 0x43.toByte() &&
                        au[p+4] == 0x55.toByte() && au[p+5] == 0x52.toByte() &&
                        au[p+6] == 0x59.toByte() && au[p+7] == 0x2D.toByte() &&
                        au[p+8] == 0x4C.toByte() && au[p+9] == 0x41.toByte() &&
                        au[p+10] == 0x42.toByte() && au[p+11] == 0x53.toByte() &&
                        au[p+12] == 0x2D.toByte() && au[p+13] == 0x49.toByte() &&
                        au[p+14] == 0x4D.toByte() && au[p+15] == 0x55.toByte()) {
                        // After UUID (16 bytes) comes 0+ imu_sample_shm structs
                        // (32 bytes each, native little-endian on the Pi). The
                        // struct's first 8 bytes are u64 boottime_ns — the
                        // first sample's boottime is our per-frame anchor.
                        // Need at least 8 bytes after UUID (a partial sample
                        // is enough for the timestamp; full sample isn't
                        // required for PTS). Handle EBSP emulation-prevention:
                        // drop 0x03 when preceded by 00 00.
                        var q = p + 16
                        var ts = 0L
                        var read = 0
                        while (read < 8 && q < nalEnd) {
                            val b = au[q].toInt() and 0xff
                            if (b == 0x03 && q >= 2 &&
                                au[q-1] == 0.toByte() && au[q-2] == 0.toByte()) {
                                q++; continue
                            }
                            // Little-endian: low byte first (struct layout
                            // matches Pi-side imu_sample_shm.boottime_ns).
                            ts = ts or (b.toLong() shl (8 * read))
                            q++; read++
                        }
                        if (read == 8) return ts
                    }
                    p++
                }
            }
            i = nalEnd
        }
        return 0L
    }

    private fun onAccessUnit(au: ByteArray, len: Int, isKey: Boolean) {
        h264StatAus++
        // Always feed the preview decoder (cheap, runs whether recording or not)
        try { feedPreview(au, len, isKey) } catch (e: Throwable) {
            if (previewLogs < 3) { Log.w(TAG, "preview feed: $e"); previewLogs++ }
        }
        if (!recording) return
        try {
            val seiTs = extractSeiTimestamp(au, len)
            if (seiLogs < 3 && seiTs != 0L) {
                Log.i(TAG, "SEI ts=$seiTs (au #$h264StatAus)")
                seiLogs++
            }
            val nowNs = if (seiTs != 0L) seiTs else System.nanoTime()
            if (!muxerStarted) {
                // Need SPS (NAL type 7) and PPS (NAL type 8) to build the
                // MediaFormat. With rpicam-vid --inline, every IDR AU starts
                // with SPS + PPS + (optional SEI) + IDR.
                if (muxerSearchLogs < 5) {
                    Log.i(TAG, "muxer search: au=${len}B isKey=$isKey nalTypes=${nalTypesIn(au, len)}")
                    muxerSearchLogs++
                }
                if (!isKey) return  // wait for IDR
                val sps = extractNal(au, len, 7)
                if (sps == null) { Log.w(TAG, "IDR has no SPS — dropping"); return }
                val pps = extractNal(au, len, 8)
                if (pps == null) { Log.w(TAG, "IDR has no PPS — dropping"); return }
                val fmt = MediaFormat.createVideoFormat("video/avc", 1920, 1080).apply {
                    setByteBuffer("csd-0", ByteBuffer.wrap(sps))
                    setByteBuffer("csd-1", ByteBuffer.wrap(pps))
                }
                videoTrackId = muxer!!.addTrack(fmt)
                muxer!!.start()
                muxerStarted = true
                firstAuPtsNs = nowNs
                CrashLog.line(this, "Muxer", "started; track=$videoTrackId sps=${sps.size}B pps=${pps.size}B")
            }
            val ptsUs = (nowNs - firstAuPtsNs) / 1000L
            val info = MediaCodec.BufferInfo().apply {
                offset = 0
                size = len
                presentationTimeUs = ptsUs
                this.flags = if (isKey) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
            }
            // The muxer needs a ByteBuffer view, not a raw ByteArray.
            val bb = ByteBuffer.wrap(au, 0, len)
            muxer!!.writeSampleData(videoTrackId, bb, info)
            bytesWritten += len
            auCount++
            if (auCount % 30L == 0L) postStatus()
        } catch (e: Throwable) {
            CrashLog.writeException(this, "onAccessUnit", e)
        }
    }

    /** Decode this AU into the preview SurfaceView so the user sees the
     *  live camera feed. Lazy: only configures the decoder once we have BOTH
     *  the preview Surface AND an IDR (so we have valid SPS+PPS). If the
     *  user's surface goes away (app backgrounded), we tear down and
     *  re-configure on the next IDR. */
    private fun feedPreview(au: ByteArray, len: Int, isKey: Boolean) {
        val curSurface = RecorderState.previewSurface
        // Surface changed (or appeared/disappeared) — drop any existing decoder.
        if (curSurface !== previewSurface) {
            try { previewDec?.stop() } catch (_: Throwable) {}
            try { previewDec?.release() } catch (_: Throwable) {}
            previewDec = null
            previewSurface = curSurface
        }
        if (curSurface == null) return  // no UI surface — nothing to render to

        // Configure on first IDR after we have a surface.
        if (previewDec == null) {
            if (!isKey) return
            val sps = extractNal(au, len, 7) ?: return
            val pps = extractNal(au, len, 8) ?: return
            val fmt = MediaFormat.createVideoFormat("video/avc", 1920, 1080).apply {
                setByteBuffer("csd-0", ByteBuffer.wrap(sps))
                setByteBuffer("csd-1", ByteBuffer.wrap(pps))
            }
            val dec = MediaCodec.createDecoderByType("video/avc")
            dec.configure(fmt, curSurface, null, 0)
            dec.start()
            previewDec = dec
            CrashLog.line(this, "Preview", "decoder started; sps=${sps.size}B pps=${pps.size}B")
        }

        val dec = previewDec ?: return
        // Queue input
        val inIdx = dec.dequeueInputBuffer(0)
        if (inIdx >= 0) {
            val ib = dec.getInputBuffer(inIdx)
            if (ib != null) {
                ib.clear()
                ib.put(au, 0, len)
                dec.queueInputBuffer(inIdx, 0, len, 0, 0)
            }
        }
        // Drain output (render to surface)
        val info = MediaCodec.BufferInfo()
        var outIdx = dec.dequeueOutputBuffer(info, 0)
        while (outIdx >= 0) {
            dec.releaseOutputBuffer(outIdx, true)  // render=true
            outIdx = dec.dequeueOutputBuffer(info, 0)
        }
    }

    /** List all NAL types found in an AU (for diagnostics). */
    private fun nalTypesIn(au: ByteArray, len: Int): String {
        val sb = StringBuilder()
        var i = 0
        while (i + 4 < len) {
            val sc4 = au[i] == 0.toByte() && au[i+1] == 0.toByte() &&
                      au[i+2] == 0.toByte() && au[i+3] == 1.toByte()
            val sc3 = au[i] == 0.toByte() && au[i+1] == 0.toByte() &&
                      au[i+2] == 1.toByte()
            if (sc4 || sc3) {
                val scLen = if (sc4) 4 else 3
                if (i + scLen < len) {
                    val nt = au[i + scLen].toInt() and 0x1f
                    if (sb.isNotEmpty()) sb.append(',')
                    sb.append(nt)
                }
                i += scLen
            } else i++
        }
        return sb.toString()
    }

    /** Find a NAL of the given type in an Annex-B AU, return its body
     *  (without the start code, INCLUDING the NAL header byte). */
    private fun extractNal(au: ByteArray, len: Int, type: Int): ByteArray? {
        var i = 0
        while (i + 4 < len) {
            // Find next start code
            val sc4 = au[i] == 0.toByte() && au[i+1] == 0.toByte() &&
                      au[i+2] == 0.toByte() && au[i+3] == 1.toByte()
            val sc3 = au[i] == 0.toByte() && au[i+1] == 0.toByte() &&
                      au[i+2] == 1.toByte()
            if (!sc4 && !sc3) { i++; continue }
            val scLen = if (sc4) 4 else 3
            val nalStart = i + scLen
            if (nalStart >= len) return null
            val nt = au[nalStart].toInt() and 0x1f
            // Find end of this NAL (next start code or EOF)
            var j = nalStart + 1
            while (j + 2 < len) {
                val ss4 = j + 3 < len && au[j] == 0.toByte() && au[j+1] == 0.toByte() &&
                          au[j+2] == 0.toByte() && au[j+3] == 1.toByte()
                val ss3 = au[j] == 0.toByte() && au[j+1] == 0.toByte() && au[j+2] == 1.toByte()
                if (ss4 || ss3) break
                j++
            }
            if (nt == type) {
                // Build [start code 4-byte] + [NAL]. MediaFormat expects
                // csd-0/csd-1 with start codes included.
                val nalLen = j - nalStart
                val out = ByteArray(4 + nalLen)
                out[0] = 0; out[1] = 0; out[2] = 0; out[3] = 1
                System.arraycopy(au, nalStart, out, 4, nalLen)
                return out
            }
            i = j
        }
        return null
    }

    private fun startRecording() {
        if (recording) {
            CrashLog.line(this, "Recorder", "already recording, ignored"); return
        }
        try {
            val dir = Recordings.newSession(this).also { sessionDir = it }
            startedAt = Date()
            bytesWritten = 0; auCount = 0; imuCount = 0
            firstAuPtsNs = 0L
            tracker.reset()

            muxer = MediaMuxer(File(dir, "video.mp4").absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxerStarted = false; videoTrackId = -1

            val imuFile = File(dir, "imu.imu")
            imuOut = FileOutputStream(imuFile).also { fos ->
                fos.write("boottime_ns,ax,ay,az,gx,gy,gz,mx,my,mz,mag_valid\n".toByteArray())
            }

            recording = true
            CrashLog.line(this, "Recorder", "session started: ${dir.absolutePath}")
            RecorderState.setRecording(dir.absolutePath, dir.name, "Headspace SPC2")
            updateNotification("Recording…")
        } catch (e: Throwable) {
            CrashLog.writeException(this, "startRecording", e)
            RecorderState.setError(e.message ?: "start failed")
        }
    }

    private var imuOut: FileOutputStream? = null

    private fun stopRecording(reason: String) {
        val dir = sessionDir ?: return
        recording = false
        try { if (muxerStarted) muxer?.stop() } catch (e: Throwable) {
            CrashLog.writeException(this, "muxer.stop", e)
        }
        try { muxer?.release() } catch (_: Throwable) {}
        muxer = null; muxerStarted = false
        try { imuOut?.flush(); imuOut?.close() } catch (_: Throwable) {}
        imuOut = null

        val meta = JSONObject().apply {
            put("device", "Headspace SPC2 CDC")
            put("started_at_utc", isoUtc(startedAt ?: Date()))
            put("ended_at_utc", isoUtc(Date()))
            put("video_file", "video.mp4")
            put("imu_file", "imu.imu")
            put("video_bytes", bytesWritten)
            put("access_units", auCount)
            put("imu_samples", imuCount)
            put("imu_observed_hz", "%.1f".format(tracker.hz))
            put("stop_reason", reason)
            put("first_au_phone_ns", firstAuPtsNs)
        }
        File(dir, "meta.json").writeText(meta.toString(2))
        CrashLog.line(this, "Recorder", "session stopped ($reason): ${dir.absolutePath} / $bytesWritten bytes / $auCount AUs")
        // Publish to user-visible storage (Movies/Headspace, Download/Headspace)
        // off the recording thread so a slow MediaStore call can never stall
        // the UI. Idempotent — drops a marker on success.
        Thread({
            try { MediaStoreExporter.exportSession(this, dir) }
            catch (e: Throwable) { CrashLog.writeException(this, "exportSession", e) }
        }, "session-export").start()
        sessionDir = null; startedAt = null
        RecorderState.setReady("Headspace SPC2")
        updateNotification("Saved · $bytesWritten bytes · $auCount AUs")
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(detachReceiver) } catch (_: Throwable) {}
        if (sessionDir != null) stopRecording("service_destroyed")
        try { acm?.close() } catch (_: Throwable) {}
        try { h264?.close() } catch (_: Throwable) {}
        try { previewDec?.stop() } catch (_: Throwable) {}
        try { previewDec?.release() } catch (_: Throwable) {}
        try { acmConn?.close() } catch (_: Throwable) {}
        acm = null; h264 = null; previewDec = null; acmConn = null
        RecorderState.acmSender = null
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
        updateNotification("Rec %s · %d AUs · %s".format(
            dir.name.removePrefix("spc2_"), auCount, bytesWritten.humanBytes()))
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
