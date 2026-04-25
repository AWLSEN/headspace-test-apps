package com.mercurylabs.headspace

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.util.Log
import java.io.IOException
import kotlin.concurrent.thread

/**
 * USB CDC-ACM (USB serial) reader/writer for the Headspace SPC2's
 * companion control channel. Same composite device as our UVC stream;
 * we just claim the CDC-Data interface (class=10 subclass=0) and use
 * its bulk IN+OUT endpoints.
 *
 * Pi side runs a tiny shell script (wifi-config.sh) that reads
 * newline-delimited commands and replies the same way:
 *
 *     PING                          -> OK pong
 *     STATUS                        -> OK wifi=...
 *     WIFI <ssid> <password>        -> OK wifi joined …  | ERR …
 *     TAILSCALE                     -> OK tailscale_ip=… | ERR …
 *
 * Notes on CDC-ACM:
 *   * The "Communications" interface (class=2 subclass=2) carries an
 *     interrupt endpoint we don't need for our protocol — we ignore it.
 *   * The "Data" interface (class=10 subclass=0) has bulk IN + bulk OUT
 *     endpoints that move payload bytes — we use these.
 *   * SET_LINE_CODING / SET_CONTROL_LINE_STATE control transfers are
 *     not strictly required because the gadget doesn't enforce baud
 *     etc., but we send them anyway for compatibility with hosts that
 *     expect them.
 */
class AcmSerial(
    private val conn: UsbDeviceConnection,
    private val dataIface: UsbInterface,
    private val epIn: UsbEndpoint,
    private val epOut: UsbEndpoint,
) {
    private val claimed = conn.claimInterface(dataIface, true)
    @Volatile private var running = true
    private var readerThread: Thread? = null

    init {
        if (!claimed) throw IOException("claimInterface(${dataIface.id}) failed")
        // SET_LINE_CODING — 115200, 8N1. Bytes: dwDTERate (4 LE) + bCharFormat (1)
        //                                        + bParityType (1) + bDataBits (1)
        val lineCoding = byteArrayOf(
            0x00.toByte(), 0xC2.toByte(), 0x01.toByte(), 0x00.toByte(),  // 115200
            0x00, 0x00, 0x08
        )
        conn.controlTransfer(0x21, 0x20, 0, dataIface.id, lineCoding, lineCoding.size, 200)
        // SET_CONTROL_LINE_STATE — DTR + RTS asserted
        conn.controlTransfer(0x21, 0x22, 0x03, dataIface.id, null, 0, 200)
        Log.i(TAG, "AcmSerial ready (iface=${dataIface.id} in=0x${epIn.address.toString(16)} out=0x${epOut.address.toString(16)})")
    }

    /** Send a single line; appends "\n". Blocks up to `timeoutMs`. */
    @Throws(IOException::class)
    fun sendLine(line: String, timeoutMs: Int = 500) {
        val data = (line + "\n").toByteArray()
        var sent = 0
        while (sent < data.size && running) {
            val n = conn.bulkTransfer(epOut, data, sent, data.size - sent, timeoutMs)
            if (n < 0) throw IOException("bulkTransfer(out) failed at byte $sent")
            sent += n
        }
    }

    /** Start reading lines in the background; each complete line goes to
     *  `onLine`. Stops when [close] is called or the device disappears. */
    fun startReader(onLine: (String) -> Unit) {
        readerThread = thread(name = "acm-reader", isDaemon = true) {
            val buf = ByteArray(512)
            val acc = StringBuilder()
            while (running) {
                val n = conn.bulkTransfer(epIn, buf, buf.size, 500)
                if (n < 0) {
                    // Either timeout (-1) or device gone — keep looping on timeout,
                    // exit on persistent failure. There's no way to distinguish
                    // perfectly via bulkTransfer's int return; rely on running flag.
                    continue
                }
                for (i in 0 until n) {
                    val b = buf[i].toInt() and 0xff
                    if (b == '\n'.code || b == '\r'.code) {
                        if (acc.isNotEmpty()) {
                            val line = acc.toString()
                            acc.clear()
                            try { onLine(line) } catch (e: Throwable) {
                                Log.w(TAG, "onLine threw: $e")
                            }
                        }
                    } else {
                        acc.append(b.toChar())
                    }
                }
            }
        }
    }

    fun close() {
        running = false
        try { readerThread?.join(1000) } catch (_: InterruptedException) {}
        try { conn.releaseInterface(dataIface) } catch (_: Throwable) {}
    }

    companion object {
        private const val TAG = "AcmSerial"

        /** Find the CDC-Data interface + bulk endpoints on a composite device.
         *  Returns null if the device isn't a CDC-ACM composite (or the
         *  interface alt setting we want isn't present).
         *
         *  CDC class assignments per USB-IF Class Definitions for Comm Devices:
         *    Communications + Control (class 0x02 subclass 0x02)
         *    Data interface             (class 0x0A subclass 0x00) ← we want this
         */
        fun findDataInterface(device: UsbDevice): UsbInterface? {
            for (i in 0 until device.interfaceCount) {
                val it = device.getInterface(i)
                if (it.interfaceClass == UsbConstants.USB_CLASS_CDC_DATA) return it
            }
            return null
        }

        /** Open ACM on the given (already-permitted) device. Returns null on
         *  any failure — caller logs / surfaces the error to the user. */
        fun open(conn: UsbDeviceConnection, device: UsbDevice): AcmSerial? {
            val data = findDataInterface(device) ?: run {
                Log.w(TAG, "no CDC-Data (class 0x0A) interface on ${device.productName}")
                return null
            }
            var epIn: UsbEndpoint? = null
            var epOut: UsbEndpoint? = null
            for (e in 0 until data.endpointCount) {
                val ep = data.getEndpoint(e)
                if (ep.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
                if (ep.direction == UsbConstants.USB_DIR_IN)  epIn  = ep
                else                                          epOut = ep
            }
            if (epIn == null || epOut == null) {
                Log.w(TAG, "CDC-Data missing bulk endpoints"); return null
            }
            return AcmSerial(conn, data, epIn, epOut)
        }
    }
}
