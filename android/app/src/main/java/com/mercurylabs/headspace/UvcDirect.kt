package com.mercurylabs.headspace

import android.content.Context
import android.hardware.usb.UsbDevice
import android.util.Log
import android.view.Surface
import com.jiangdg.ausbc.MultiCameraClient
import com.jiangdg.ausbc.callback.IDeviceConnectCallBack
import com.serenegiant.usb.IFrameCallback
import com.serenegiant.usb.USBMonitor
import com.serenegiant.usb.UVCCamera
import java.nio.ByteBuffer

/**
 * Direct UVCCamera wrapper (bypasses the high-level lib's NV21 conversion)
 * so we can get **raw MJPEG bytes** per frame.
 *
 * Why we need this: the Pi gadget injects an APP4 marker into every JPEG
 * containing the libcamera capture timestamp (`boottime_ns` — the same
 * clock the IMU samples use). The high-level `MultiCameraClient.Camera`
 * decodes MJPEG → NV21 internally and the marker is lost. By going one
 * level down to `serenegiant.usb.UVCCamera` (saki4510t's libuvc binding,
 * already bundled by `libausbc:libuvc:3.2.7`) we receive the raw payload
 * and can extract the timestamp ourselves.
 *
 * Two outputs are wired up:
 *   • `previewSurface` — UVCCamera renders frames there for the user UI.
 *   • `onRawFrame(mjpeg, captureNs)` — raw MJPEG with the parsed APP4 ts.
 */
class UvcDirect(
    private val ctx: Context,
    private val onState: (opened: Boolean, msg: String) -> Unit,
    private val onDevice: (UsbDevice) -> Unit,
    private val onRawFrame: (mjpeg: ByteBuffer, captureNs: Long) -> Unit,
) : IDeviceConnectCallBack {

    // We piggy-back on MultiCameraClient solely to reuse its USB permission
    // request + USBMonitor wiring (the lib does all that boilerplate already).
    private val client = MultiCameraClient(ctx, this)
    private var ucam: UVCCamera? = null

    fun start() {
        client.register()
        // Catch already-attached devices.
        val list = client.getDeviceList(null) ?: emptyList()
        Log.i(TAG, "register: ${list.size} pre-attached USB device(s)")
        // Defer to give the UI's SurfaceView time to publish its Surface.
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            for (d in list) onAttachDev(d)
        }, 600)
    }

    fun stop() {
        try { ucam?.stopPreview() } catch (_: Throwable) {}
        try { ucam?.destroy() } catch (_: Throwable) {}
        ucam = null
        client.unRegister(); client.destroy()
    }

    override fun onAttachDev(device: UsbDevice?) {
        device ?: return
        if (ucam != null) return
        val granted = client.hasPermission(device) == true
        Log.i(TAG, "device attached: ${device.productName} hasPermission=$granted")
        try { onDevice(device) } catch (_: Throwable) {}
        if (granted) {
            // Library short-circuits when permission is already granted, so
            // we open via the (private) USBMonitor directly via reflection.
            try {
                val f = client.javaClass.getDeclaredField("mUsbMonitor")
                f.isAccessible = true
                val mon = f.get(client) as USBMonitor
                val ctrl = mon.openDevice(device)
                onConnectDev(device, ctrl)
            } catch (e: Throwable) {
                Log.e(TAG, "openDevice via reflection failed", e); onState(false, "open failed")
            }
        } else {
            client.requestPermission(device)
        }
    }

    override fun onConnectDev(device: UsbDevice?, ctrlBlock: USBMonitor.UsbControlBlock?) {
        device ?: return; ctrlBlock ?: return
        try {
            val cam = UVCCamera()
            cam.open(ctrlBlock)
            // FRAME_FORMAT_MJPEG=1; ask the Pi for MJPEG @ 1080p30.
            cam.setPreviewSize(1920, 1080, UVCCamera.FRAME_FORMAT_MJPEG)
            // Live preview rendering — lib decodes MJPEG → RGB → user surface.
            val preview = RecorderState.previewSurface
            if (preview != null) cam.setPreviewDisplay(preview)
            // Raw MJPEG callback (PIXEL_FORMAT_RAW=0).
            cam.setFrameCallback(IFrameCallback { buf -> handleRawFrame(buf) },
                                 UVCCamera.PIXEL_FORMAT_RAW)
            cam.startPreview()
            ucam = cam
            onState(true, "opened")
        } catch (e: Throwable) {
            Log.e(TAG, "open camera failed", e)
            CrashLog.writeException(ctx, "uvc.open", e)
            onState(false, e.message ?: "open failed")
        }
    }

    /** Parse the APP4 marker injected by the Pi (`FF E4 00 0E "HSPC" <8-byte ns>`)
     *  and forward the raw MJPEG along with the timestamp (0 if no marker). */
    private fun handleRawFrame(buf: ByteBuffer) {
        var ts = 0L
        try {
            // saki4510t lib gives us a buffer where data is between [position, limit).
            // Don't assume position=0 — read relative to current position.
            val start = buf.position()
            val rem = buf.remaining()
            if (frameDumps < 3 && rem >= 24) {
                val sb = StringBuilder()
                for (i in 0 until 24) sb.append("%02x ".format(buf.get(start + i).toInt() and 0xff))
                Log.i(TAG, "rawMjpeg pos=$start lim=${buf.limit()} rem=$rem head=$sb")
                frameDumps++
            }
            // Scan first ~32 bytes for the SOI+APP4 marker. In practice it's at
            // offset +2 (right after SOI), but allow some slack.
            val maxScan = minOf(rem - 18, 32)
            for (off in 0..maxScan) {
                val p = start + off
                val b0 = buf.get(p).toInt() and 0xff
                val b1 = buf.get(p + 1).toInt() and 0xff
                val b2 = buf.get(p + 2).toInt() and 0xff
                val b3 = buf.get(p + 3).toInt() and 0xff
                if (b0 == 0xFF && b1 == 0xD8 && b2 == 0xFF && b3 == 0xE4) {
                    val len = ((buf.get(p + 4).toInt() and 0xff) shl 8) or (buf.get(p + 5).toInt() and 0xff)
                    if (len == 14
                        && buf.get(p + 6).toInt() == 'H'.code
                        && buf.get(p + 7).toInt() == 'S'.code
                        && buf.get(p + 8).toInt() == 'P'.code
                        && buf.get(p + 9).toInt() == 'C'.code) {
                        ts = 0L
                        for (i in 0 until 8) ts = (ts shl 8) or (buf.get(p + 10 + i).toLong() and 0xff)
                    }
                    break
                }
                // Also accept SOI without APP4 (marker missing) — just stop scanning
                if (b0 == 0xFF && b1 == 0xD8) break
            }
            if (ts == 0L && tsMissingLogs < 5) {
                Log.w(TAG, "no APP4 ts; pos=$start rem=$rem")
                tsMissingLogs++
            }
        } catch (e: Throwable) {
            Log.w(TAG, "handleRawFrame parse threw: $e")
        }
        try { onRawFrame(buf, ts) } catch (e: Throwable) {
            Log.w(TAG, "onRawFrame threw: $e")
        }
    }

    private var frameDumps = 0
    private var tsMissingLogs = 0

    override fun onDetachDec(device: UsbDevice?) {
        Log.i(TAG, "device detached: ${device?.productName}")
        try { ucam?.stopPreview() } catch (_: Throwable) {}
        try { ucam?.destroy() } catch (_: Throwable) {}
        ucam = null
        onState(false, "detached")
    }

    override fun onDisConnectDec(device: UsbDevice?, ctrlBlock: USBMonitor.UsbControlBlock?) {
        try { ucam?.stopPreview() } catch (_: Throwable) {}
        try { ucam?.destroy() } catch (_: Throwable) {}
        ucam = null
    }

    override fun onCancelDev(device: UsbDevice?) {
        Log.w(TAG, "USB permission denied"); onState(false, "permission denied")
    }

    companion object { private const val TAG = "UvcDirect" }
}
