package com.mercurylabs.headspace

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.util.Log
import java.io.IOException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Raw H.264 reader over a CDC-ACM channel (the SECOND ACM on the Pi
 * gadget — `/dev/ttyGS1`). The Pi pipes `rpicam-vid` H.264 bytes (Annex-B,
 * 24 Mbps, 1080p30) straight into this channel; we read them on the phone
 * and parse out access units (AUs).
 *
 * No format negotiation, no decoding — bytes in, AUs out, ready to feed
 * `MediaMuxer.writeSampleData()`.
 *
 * AU framing: a new AU starts at each VCL NAL (slice = type 1, IDR slice =
 * type 5). Anything before the first VCL belongs to that AU (SPS/PPS/SEI).
 * We accumulate bytes until we see the *next* VCL NAL, then emit the
 * preceding bytes as a complete AU.
 */
class H264AcmReader(
    private val conn: UsbDeviceConnection,
    private val dataIface: UsbInterface,
    private val commsIfaceId: Int,
    private val epIn: UsbEndpoint,
) {
    private val claimed = conn.claimInterface(dataIface, true)
    @Volatile private var running = true
    private var readerThread: Thread? = null
    private var writerThread: Thread? = null

    /** A complete AU sitting in the queue. Owns its bytes (caller may keep). */
    private class QueuedAu(val data: ByteArray, val len: Int, val isKey: Boolean)

    // Bounded queue between the USB reader thread and the writer thread.
    // 32 slots × ~100KB IDR worst case = ~3.2MB ceiling. Sized for ~1s of
    // 30fps slack so a brief muxer/SD-card stall doesn't backpressure into
    // the USB read path (which would let the dwc2 buffer overflow on Pi).
    private val auQueue = ArrayBlockingQueue<QueuedAu>(32)
    @Volatile private var droppedAus = 0L

    init {
        if (!claimed) throw IOException("claimInterface(${dataIface.id}) failed")
        // Same control-transfer dance as AcmSerial — most hosts/devices
        // ignore these for f_acm but they're cheap.
        val lineCoding = byteArrayOf(
            0x00.toByte(), 0xC2.toByte(), 0x01.toByte(), 0x00.toByte(),
            0x00, 0x00, 0x08
        )
        conn.controlTransfer(0x21, 0x20, 0, commsIfaceId, lineCoding, lineCoding.size, 200)
        conn.controlTransfer(0x21, 0x22, 0x03, commsIfaceId, null, 0, 200)
        Log.i(TAG, "H264AcmReader ready (data=${dataIface.id} comms=$commsIfaceId in=0x${epIn.address.toString(16)})")
    }

    /** Start a reader thread.
     *  @param onAccessUnit called per complete AU with (bytes, lengthInBytes, isKeyframe).
     *                     The caller MUST copy the bytes if it wants to keep them past
     *                     the callback — the buffer is reused between AUs.
     *  @param onByteStats called every ~1s with rolling bytes/sec and AU count.
     */
    fun startReader(
        onAccessUnit: (au: ByteArray, len: Int, isKeyframe: Boolean) -> Unit,
        onByteStats: (bytesPerSec: Long, ausPerSec: Int, totalBytes: Long) -> Unit = { _, _, _ -> },
    ) {
        // Writer thread: drains the queue and runs the muxer/decoder callback.
        // Decoupled from USB read so a slow disk write can't block bulkTransfer.
        writerThread = thread(name = "h264-writer", isDaemon = true) {
            while (running || auQueue.isNotEmpty()) {
                val q = auQueue.poll(200, TimeUnit.MILLISECONDS) ?: continue
                try {
                    onAccessUnit(q.data, q.len, q.isKey)
                } catch (e: Throwable) {
                    Log.w(TAG, "writer onAccessUnit threw: $e")
                }
            }
        }
        readerThread = thread(name = "h264-reader", isDaemon = true) {
            // 64KB read buffer — H.264 IDR frames at 1080p 24Mbps are ~100KB,
            // a P-frame is much smaller. Bulk transfer max packet on USB-HS = 512.
            // Larger reads = fewer syscalls.
            val readBuf = ByteArray(65536)
            // 2MB AU accumulator — IDR can be large, plus we hold one full AU
            // until we see the next VCL boundary.
            var au = ByteArray(2 * 1024 * 1024)
            var auLen = 0
            var auHasKeyframe = false
            var lastNalType = -1   // type of most recent NAL appended to current AU
            var sawAny = false     // we've seen at least one NAL — trigger emit on next boundary

            // For onByteStats
            var statBytes = 0L
            var statAus = 0
            var statT0 = android.os.SystemClock.uptimeMillis()
            var totalBytes = 0L

            while (running) {
                val n = conn.bulkTransfer(epIn, readBuf, readBuf.size, 1000)
                if (n < 0) continue  // timeout or error; loop
                if (n == 0) continue
                statBytes += n
                totalBytes += n

                // For each byte, look for Annex-B start codes (00 00 01 or 00 00 00 01)
                // followed by a VCL NAL. When we find one, emit accumulated AU and
                // start a new one.
                var i = 0
                while (i < n) {
                    // Check if writing this byte would create a start code at the
                    // boundary auLen-2..auLen+0. Simpler: just append, then scan.
                    if (auLen >= au.size - 1) {
                        // grow
                        val bigger = ByteArray(au.size * 2)
                        System.arraycopy(au, 0, bigger, 0, auLen)
                        au = bigger
                    }
                    au[auLen++] = readBuf[i]
                    i++

                    // After appending, check if last 4 (or 3) bytes are a start
                    // code AND the byte BEFORE that start code+nal-header isn't yet
                    // checked. We check on every byte because start codes can span
                    // bulk-transfer boundaries.
                    //
                    // Looking for the NAL header byte (the byte AFTER the start
                    // code). Pattern: ...00 00 01 NN or ...00 00 00 01 NN.
                    if (auLen >= 5) {
                        val nalIdx = auLen - 1  // last byte appended
                        val sc4 = au[nalIdx - 4] == 0.toByte() &&
                                  au[nalIdx - 3] == 0.toByte() &&
                                  au[nalIdx - 2] == 0.toByte() &&
                                  au[nalIdx - 1] == 1.toByte()
                        val sc3 = au[nalIdx - 3] == 0.toByte() &&
                                  au[nalIdx - 2] == 0.toByte() &&
                                  au[nalIdx - 1] == 1.toByte()
                        if (sc4 || sc3) {
                            val nalType = au[nalIdx].toInt() and 0x1f
                            // AU boundary detection — start a new AU when:
                            //   • new SPS (7) arrives → always begins a new AU,
                            //   • a slice (1 or 5) arrives AND the previous NAL
                            //     was also a slice → P→P or P→IDR-without-SPS
                            //     transition (rare but possible),
                            //   • SEI/PPS/AUD on their own arriving after a
                            //     slice → they belong to the upcoming picture.
                            // Otherwise (SPS→PPS, PPS→SEI, SEI→IDR, IDR→slice
                            // within a contiguous keyframe AU) we just append.
                            val isVcl = (nalType == 1 || nalType == 5)
                            val isAuStarter = (nalType == 7) ||
                                              (nalType == 9) ||
                                              (isVcl && (lastNalType == 1 || lastNalType == 5)) ||
                                              ((nalType == 6 || nalType == 8) && (lastNalType == 1 || lastNalType == 5))
                            if (isAuStarter && sawAny) {
                                val scLen = if (sc4) 4 else 3
                                val emitLen = nalIdx - scLen
                                if (emitLen > 0) {
                                    // Copy to a fresh buffer (the AU accumulator
                                    // is reused for the next AU). 30 IDRs/sec
                                    // × ~100KB = 3MB/s of memcpy — trivial.
                                    val copy = ByteArray(emitLen)
                                    System.arraycopy(au, 0, copy, 0, emitLen)
                                    val q = QueuedAu(copy, emitLen, auHasKeyframe)
                                    if (!auQueue.offer(q)) {
                                        // Queue full → writer is stalled. Drop
                                        // the OLDEST AU to make room (favors
                                        // freshness; avoids unbounded latency).
                                        auQueue.poll()
                                        auQueue.offer(q)
                                        droppedAus++
                                        if (droppedAus % 30L == 1L) {
                                            Log.w(TAG, "writer queue full — dropped $droppedAus AUs total")
                                        }
                                    }
                                    statAus++
                                }
                                val keepStart = emitLen
                                val keepLen = auLen - keepStart
                                System.arraycopy(au, keepStart, au, 0, keepLen)
                                auLen = keepLen
                                auHasKeyframe = false   // reset; will re-set when we see IDR below
                            }
                            sawAny = true
                            if (nalType == 5) auHasKeyframe = true
                            lastNalType = nalType
                        }
                    }
                }

                // Stats every 1s
                val now = android.os.SystemClock.uptimeMillis()
                if (now - statT0 >= 1000) {
                    val dt = (now - statT0).coerceAtLeast(1)
                    val bps = statBytes * 1000L / dt
                    val aps = (statAus * 1000 / dt).toInt()
                    try { onByteStats(bps, aps, totalBytes) } catch (_: Throwable) {}
                    statBytes = 0; statAus = 0; statT0 = now
                }
            }
        }
    }

    fun close() {
        running = false
        try { readerThread?.join(1000) } catch (_: InterruptedException) {}
        try { writerThread?.join(2000) } catch (_: InterruptedException) {}
        try { conn.releaseInterface(dataIface) } catch (_: Throwable) {}
    }

    companion object {
        private const val TAG = "H264AcmReader"

        /** Find the SECOND CDC-Data interface (acm.GS1 on the Pi gadget) and
         *  open a reader. Returns null if the device only has one ACM. */
        fun openSecond(conn: UsbDeviceConnection, device: UsbDevice): H264AcmReader? {
            // Collect all CDC-Data interfaces in iface-id order.
            val dataIfaces = (0 until device.interfaceCount)
                .map { device.getInterface(it) }
                .filter { it.interfaceClass == UsbConstants.USB_CLASS_CDC_DATA }
                .sortedBy { it.id }
            if (dataIfaces.size < 2) {
                Log.w(TAG, "device only has ${dataIfaces.size} CDC-Data iface(s); need 2")
                return null
            }
            val data = dataIfaces[1]
            // The matching Comms iface is the one with id = data.id - 1 typically.
            val comms = (0 until device.interfaceCount)
                .map { device.getInterface(it) }
                .firstOrNull { it.interfaceClass == 2 && it.interfaceSubclass == 2 && it.id == data.id - 1 }
                ?: (0 until device.interfaceCount)
                    .map { device.getInterface(it) }
                    .filter { it.interfaceClass == 2 && it.interfaceSubclass == 2 }
                    .sortedBy { it.id }
                    .getOrNull(1)
            val commsId = comms?.id ?: data.id
            var epIn: UsbEndpoint? = null
            for (e in 0 until data.endpointCount) {
                val ep = data.getEndpoint(e)
                if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK &&
                    ep.direction == UsbConstants.USB_DIR_IN) {
                    epIn = ep; break
                }
            }
            if (epIn == null) {
                Log.w(TAG, "second CDC-Data has no bulk IN endpoint"); return null
            }
            Log.i(TAG, "found 2nd ACM: data=${data.id} comms=$commsId in=0x${epIn.address.toString(16)}")
            return H264AcmReader(conn, data, commsId, epIn)
        }
    }
}
