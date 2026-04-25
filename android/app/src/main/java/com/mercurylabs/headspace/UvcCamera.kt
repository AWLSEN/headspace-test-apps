package com.mercurylabs.headspace

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.hardware.usb.UsbRequest
import android.os.Build
import android.util.Log
import java.nio.ByteBuffer
import kotlin.concurrent.thread

/**
 * Minimal UVC reader for the Mercury Labs Headspace SPC2.
 *
 * Why hand-rolled instead of saki4510t/UVCCamera: that library is JitPack-
 * only, drags an old NDK module, and assumes MJPEG/YUYV. Our gadget speaks
 * H.264 framebased on a single isochronous endpoint, and we just want raw
 * payload bytes streamed back to the caller. Doing it through the public
 * UsbManager + UsbRequest path is ~150 lines and avoids the dep.
 *
 * What this DOES NOT do (yet): UVC class control negotiation (probe/commit).
 * Most modern Android USB hosts will accept the device as-is in its default
 * alt setting. If a particular device requires explicit format negotiation
 * we add it here using controlTransfer() with the standard UVC class
 * requests; for now we use the heuristic of picking the highest-bandwidth
 * alt setting we see on the streaming interface.
 */
class UvcCamera(
    private val ctx: Context,
    private val device: UsbDevice,
    private val onFrame: (ByteArray, Int, Int) -> Unit,    // (buf, off, len) — payload bytes
    private val onError: (String) -> Unit,
) {
    private val mgr = ctx.getSystemService(Context.USB_SERVICE) as UsbManager
    private var conn: UsbDeviceConnection? = null
    private var iface: UsbInterface? = null
    private var endpoint: UsbEndpoint? = null
    @Volatile private var running = false
    private var readerThread: Thread? = null

    val devName: String get() = device.productName ?: "USB Device"
    val isStreaming: Boolean get() = running

    /** Request user permission, then call [onReady] when granted. */
    fun requestPermissionAndOpen(onReady: () -> Unit) {
        if (mgr.hasPermission(device)) { onReady(); return }
        val action = "com.mercurylabs.headspace.USB_PERMISSION"
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        else PendingIntent.FLAG_UPDATE_CURRENT
        val pi = PendingIntent.getBroadcast(ctx, 0, Intent(action).setPackage(ctx.packageName), flags)
        val rcv = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                ctx.unregisterReceiver(this)
                val granted = i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                if (granted) onReady() else onError("USB permission denied")
            }
        }
        val filter = IntentFilter(action)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ctx.registerReceiver(rcv, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            ctx.registerReceiver(rcv, filter)
        }
        mgr.requestPermission(device, pi)
    }

    fun start() {
        if (running) return
        val openConn = mgr.openDevice(device) ?: run {
            onError("openDevice returned null"); return
        }
        conn = openConn
        // Find the first video-class streaming interface. UVC: bInterfaceClass=14.
        var streamIf: UsbInterface? = null
        for (i in 0 until device.interfaceCount) {
            val it = device.getInterface(i)
            if (it.interfaceClass == UsbConstants.USB_CLASS_VIDEO &&
                it.interfaceSubclass == 2 /* SC_VIDEOSTREAMING */) {
                streamIf = it
                break
            }
        }
        if (streamIf == null) {
            onError("no UVC streaming interface (class=14, subclass=2)"); release(); return
        }
        if (!openConn.claimInterface(streamIf, true)) {
            onError("claimInterface failed"); release(); return
        }
        iface = streamIf
        // Pick the bulk OR isochronous IN endpoint with the largest packet
        // size — that's the one carrying our 1080p H.264 frames.
        var best: UsbEndpoint? = null
        for (e in 0 until streamIf.endpointCount) {
            val ep = streamIf.getEndpoint(e)
            if (ep.direction != UsbConstants.USB_DIR_IN) continue
            if (best == null || ep.maxPacketSize > best.maxPacketSize) best = ep
        }
        if (best == null) { onError("no IN endpoint"); release(); return }
        endpoint = best
        Log.i(TAG, "streaming endpoint: addr=0x${best.address.toString(16)} type=${best.type} mps=${best.maxPacketSize}")

        running = true
        readerThread = thread(name = "uvc-reader", isDaemon = true) { readerLoop(openConn, best) }
    }

    private fun readerLoop(c: UsbDeviceConnection, ep: UsbEndpoint) {
        val mps = ep.maxPacketSize.coerceAtLeast(512)
        val req = UsbRequest()
        if (!req.initialize(c, ep)) {
            onError("UsbRequest.initialize failed"); return
        }
        // For isochronous endpoints we issue many small reads; for bulk one
        // big read. Either way we feed the bytes downstream as they arrive.
        val bufSize = if (ep.type == UsbConstants.USB_ENDPOINT_XFER_ISOC) mps * 8 else 64 * 1024
        var lastRefill = 0L
        while (running) {
            val bb = ByteBuffer.allocate(bufSize)
            if (!req.queue(bb, bufSize)) {
                onError("queue() failed"); break
            }
            val resp = c.requestWait(500)  // ms
            if (resp == null) {
                // Timeout — UVC alt-setting may need explicit selection. Log
                // and continue so we don't spin forever.
                if (System.currentTimeMillis() - lastRefill > 2000) {
                    Log.w(TAG, "no UVC packets in 500ms — device may need probe/commit")
                    lastRefill = System.currentTimeMillis()
                }
                continue
            }
            val n = bb.position()
            if (n > 0) {
                val arr = ByteArray(n)
                bb.rewind()
                bb.get(arr, 0, n)
                onFrame(arr, 0, n)
            }
        }
        req.close()
    }

    fun stop() {
        if (!running) return
        running = false
        readerThread?.join(1000)
        release()
    }

    private fun release() {
        val c = conn
        val i = iface
        if (c != null && i != null) c.releaseInterface(i)
        c?.close()
        conn = null; iface = null; endpoint = null
    }

    companion object {
        private const val TAG = "UvcCamera"
    }
}
