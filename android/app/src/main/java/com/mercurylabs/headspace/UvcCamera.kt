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
 * UVC reader for the Mercury Labs Headspace SPC2.
 *
 * What it does, in order:
 *   1. Find the streaming interface (class=14, subclass=2).
 *   2. Pick the alt setting with the largest IN endpoint — that's the one
 *      that actually carries video bytes (alt 0 has zero-bandwidth EPs).
 *   3. Send VS_PROBE_CONTROL SET_CUR with our format/frame/interval.
 *   4. GET_CUR — device echoes back its negotiated values (incl. the
 *      max payload transfer size we should use for reads).
 *   5. SET_CUR on VS_COMMIT_CONTROL to lock the negotiated settings in.
 *   6. SET_INTERFACE to that alt — now bytes flow.
 *   7. Drain the endpoint into the onFrame callback.
 *
 * The probe/commit byte layout is in UvcControl.kt (UVC 1.1 spec exact).
 * Mirrors saki4510t/UVCCamera's startup sequence; no Java/JNI dependency.
 */
class UvcCamera(
    private val ctx: Context,
    private val device: UsbDevice,
    private val onFrame: (ByteArray, Int, Int) -> Unit,
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

        // ---- 1. Find streaming interface + best alt setting ---------------
        // UsbDevice.getInterface() enumerates *all* alt settings as separate
        // UsbInterface objects. We pick the one with the largest IN endpoint
        // on a video-streaming class (14/2).
        var bestIface: UsbInterface? = null
        var bestEp: UsbEndpoint? = null
        var streamingIfaceId = -1
        for (i in 0 until device.interfaceCount) {
            val it = device.getInterface(i)
            if (it.interfaceClass != UsbConstants.USB_CLASS_VIDEO ||
                it.interfaceSubclass != 2) continue
            for (e in 0 until it.endpointCount) {
                val ep = it.getEndpoint(e)
                if (ep.direction != UsbConstants.USB_DIR_IN) continue
                if (bestEp == null || ep.maxPacketSize > bestEp.maxPacketSize) {
                    bestEp = ep
                    bestIface = it
                    streamingIfaceId = it.id
                }
            }
        }
        if (bestIface == null || bestEp == null) {
            onError("no UVC streaming endpoint found"); release(); return
        }
        Log.i(TAG, "streaming iface id=$streamingIfaceId alt=${bestIface.alternateSetting} " +
                   "ep=0x${bestEp.address.toString(16)} type=${bestEp.type} " +
                   "maxPacket=${bestEp.maxPacketSize}")

        // Claim the interface BEFORE setInterface — Android API requirement.
        if (!openConn.claimInterface(bestIface, true)) {
            onError("claimInterface failed"); release(); return
        }
        iface = bestIface

        // ---- 2-4. Probe / commit -----------------------------------------
        // Format index 1, frame index 1, 30 fps (333333 × 100ns). These are
        // the values our gadget exposes via configfs.
        val probe = UvcControl.buildProbe(
            formatIndex = 1, frameIndex = 1, frameInterval100ns = 333_333,
        )

        // SET_CUR(probe)
        val setRc = openConn.controlTransfer(
            UvcControl.BM_REQ_SET, UvcControl.SET_CUR,
            UvcControl.VS_PROBE_CONTROL shl 8,
            streamingIfaceId,
            probe, probe.size, CTRL_TIMEOUT_MS,
        )
        if (setRc != probe.size) {
            // Some devices reject the request before they're committed once;
            // log it and continue — many will accept GET_CUR / SET_INTERFACE
            // anyway and start streaming on default values.
            Log.w(TAG, "SET_CUR(probe) wrote $setRc/${probe.size}, continuing")
        }

        // GET_CUR(probe) — read what the device negotiated
        val negotiated = ByteArray(UvcControl.PROBE_LEN)
        val getRc = openConn.controlTransfer(
            UvcControl.BM_REQ_GET, UvcControl.GET_CUR,
            UvcControl.VS_PROBE_CONTROL shl 8,
            streamingIfaceId,
            negotiated, negotiated.size, CTRL_TIMEOUT_MS,
        )
        val negotiatedOK = getRc == negotiated.size
        if (negotiatedOK) {
            Log.i(TAG, "negotiated dwMaxVideoFrameSize=${UvcControl.parseMaxVideoFrameSize(negotiated)} " +
                       "dwMaxPayloadTransferSize=${UvcControl.parseMaxPayloadTransferSize(negotiated)}")
        } else {
            Log.w(TAG, "GET_CUR(probe) returned $getRc, falling back to our request bytes")
        }

        // SET_CUR(commit) with the negotiated bytes (or our originals as fallback)
        val commitPayload = if (negotiatedOK) negotiated else probe
        val commitRc = openConn.controlTransfer(
            UvcControl.BM_REQ_SET, UvcControl.SET_CUR,
            UvcControl.VS_COMMIT_CONTROL shl 8,
            streamingIfaceId,
            commitPayload, commitPayload.size, CTRL_TIMEOUT_MS,
        )
        if (commitRc != commitPayload.size) {
            Log.w(TAG, "SET_CUR(commit) wrote $commitRc/${commitPayload.size}")
        }

        // ---- 5. SET_INTERFACE — switch to the data-carrying alt ---------
        // Android's UsbDeviceConnection has setInterface() but it actually
        // does claimInterface again on the *new* alt setting. The official
        // way to switch alt is via controlTransfer SET_INTERFACE.
        // Reference: USB 2.0 spec 9.4.10.
        val setIfaceRc = openConn.controlTransfer(
            0x01,                                 // bmReqType: standard, interface
            0x0B,                                 // bRequest: SET_INTERFACE
            bestIface.alternateSetting,           // wValue: alt setting
            streamingIfaceId,                     // wIndex: interface
            null, 0, CTRL_TIMEOUT_MS,
        )
        if (setIfaceRc < 0) {
            Log.w(TAG, "SET_INTERFACE returned $setIfaceRc (alt=${bestIface.alternateSetting})")
        } else {
            Log.i(TAG, "SET_INTERFACE alt=${bestIface.alternateSetting} OK")
        }

        endpoint = bestEp
        running = true
        readerThread = thread(name = "uvc-reader", isDaemon = true) {
            readerLoop(openConn, bestEp,
                maxPayload = if (negotiatedOK) UvcControl.parseMaxPayloadTransferSize(negotiated) else 0)
        }
    }

    private fun readerLoop(c: UsbDeviceConnection, ep: UsbEndpoint, maxPayload: Int) {
        val mps = ep.maxPacketSize.coerceAtLeast(512)
        val req = UsbRequest()
        if (!req.initialize(c, ep)) {
            onError("UsbRequest.initialize failed"); return
        }
        val readSize = when {
            maxPayload in 1..(64 * 1024) -> maxPayload
            ep.type == UsbConstants.USB_ENDPOINT_XFER_ISOC -> mps * 8
            else -> 64 * 1024
        }
        var idleSince = System.currentTimeMillis()
        while (running) {
            val bb = ByteBuffer.allocate(readSize)
            if (!req.queue(bb, readSize)) {
                onError("queue() failed"); break
            }
            val resp = c.requestWait(500)
            if (resp == null) {
                if (System.currentTimeMillis() - idleSince > 2000) {
                    Log.w(TAG, "no UVC packets in 2s — re-trying probe/commit may help")
                    idleSince = System.currentTimeMillis()
                }
                continue
            }
            val n = bb.position()
            if (n > 0) {
                idleSince = System.currentTimeMillis()
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
        // Send SET_INTERFACE alt 0 to release bandwidth before close.
        try {
            conn?.controlTransfer(0x01, 0x0B, 0, iface?.id ?: 0, null, 0, CTRL_TIMEOUT_MS)
        } catch (_: Throwable) {}
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
        private const val CTRL_TIMEOUT_MS = 1000
    }
}
