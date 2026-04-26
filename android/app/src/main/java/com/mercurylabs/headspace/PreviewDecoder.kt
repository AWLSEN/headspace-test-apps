package com.mercurylabs.headspace

import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import kotlin.concurrent.thread

/**
 * Tiny H.264 decoder that pumps bytes from the UVC reader into a
 * MediaCodec-backed Surface for live preview.
 *
 * The codec needs SPS+PPS in csd-0 BEFORE the first frame, otherwise it
 * silently drops everything. We sniff SPS (NAL type 7) and PPS (NAL type
 * 8) from the incoming stream, configure the codec on the first time we
 * have both, and from then on feed everything through.
 *
 * H.264 from rpicam-vid is Annex-B framed. MediaCodec accepts Annex-B
 * directly when configured with `csd-0` containing SPS+PPS in that
 * format — no AVCC conversion needed.
 */
class PreviewDecoder(
    private val width: Int = 1920,
    private val height: Int = 1080,
) {
    private var codec: MediaCodec? = null
    private var sps: ByteArray? = null
    private var pps: ByteArray? = null
    @Volatile private var running = false
    private var outThread: Thread? = null
    @Volatile var framesDecoded: Long = 0; private set
    @Volatile var lastError: String? = null; private set

    fun start(surface: Surface) {
        if (running) return
        try {
            val c = MediaCodec.createDecoderByType("video/avc")
            codec = c
            running = true
            // We'll defer configure() until SPS+PPS arrive
            // Surface is held until we configure
            this.surface = surface
        } catch (e: Throwable) {
            lastError = "createDecoder: ${e.message}"
            Log.e(TAG, "decoder create failed", e)
        }
    }

    private var surface: Surface? = null
    private var configured = false

    fun feed(data: ByteArray, off: Int, len: Int) {
        val c = codec ?: return
        if (!running) return

        // Sniff SPS/PPS if we haven't yet — needed before configure
        if (!configured) {
            sniffParameterSets(data, off, len)
            if (sps != null && pps != null) {
                try {
                    val fmt = MediaFormat.createVideoFormat("video/avc", width, height)
                    // csd-0: SPS+PPS in Annex-B framed form
                    val csd0 = ByteArray(sps!!.size + pps!!.size)
                    System.arraycopy(sps!!, 0, csd0, 0, sps!!.size)
                    System.arraycopy(pps!!, 0, csd0, sps!!.size, pps!!.size)
                    fmt.setByteBuffer("csd-0", ByteBuffer.wrap(csd0))
                    c.configure(fmt, surface, null, 0)
                    c.start()
                    configured = true
                    outThread = thread(name = "decoder-out", isDaemon = true) {
                        outputLoop(c)
                    }
                    Log.i(TAG, "decoder configured ${width}x${height}")
                } catch (e: Throwable) {
                    lastError = "configure: ${e.message}"
                    Log.e(TAG, "decoder configure failed", e)
                    return
                }
            } else {
                return  // still waiting for SPS+PPS
            }
        }

        // Feed AU bytes into input buffer
        try {
            val idx = c.dequeueInputBuffer(10_000)
            if (idx >= 0) {
                val buf = c.getInputBuffer(idx)!!
                buf.clear()
                val toCopy = minOf(len, buf.capacity())
                buf.put(data, off, toCopy)
                c.queueInputBuffer(idx, 0, toCopy, System.nanoTime() / 1000, 0)
            }
        } catch (e: Throwable) {
            // Codec may have been released — ignore
        }
    }

    private fun outputLoop(c: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        while (running) {
            try {
                val idx = c.dequeueOutputBuffer(info, 50_000)
                when {
                    idx >= 0 -> {
                        c.releaseOutputBuffer(idx, true)  // render to surface
                        framesDecoded++
                    }
                    idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        Log.i(TAG, "output format: ${c.outputFormat}")
                    }
                    idx == MediaCodec.INFO_TRY_AGAIN_LATER -> { /* spin */ }
                    else -> { /* INFO_OUTPUT_BUFFERS_CHANGED — deprecated, ignore */ }
                }
            } catch (e: Throwable) { break }
        }
    }

    /** Find SPS (NAL type 7) and PPS (NAL type 8) in the buffer. They're
     *  bracketed by Annex-B start codes; we slice those bytes out and
     *  cache for codec configure. */
    private fun sniffParameterSets(data: ByteArray, off: Int, len: Int) {
        var i = off
        val end = off + len
        while (i + 4 < end) {
            // Find start code
            val sc4 = data[i] == 0.toByte() && data[i+1] == 0.toByte() &&
                      data[i+2] == 0.toByte() && data[i+3] == 1.toByte()
            val sc3 = !sc4 && i + 3 < end &&
                      data[i] == 0.toByte() && data[i+1] == 0.toByte() &&
                      data[i+2] == 1.toByte()
            if (!sc3 && !sc4) { i++; continue }
            val scLen = if (sc4) 4 else 3
            val nalStart = i + scLen
            if (nalStart >= end) break
            val nt = data[nalStart].toInt() and 0x1f
            // Find next start code → NAL end
            var j = nalStart + 1
            while (j + 2 < end) {
                if (data[j] == 0.toByte() && data[j+1] == 0.toByte() &&
                    (data[j+2] == 1.toByte() ||
                     (j + 3 < end && data[j+2] == 0.toByte() && data[j+3] == 1.toByte()))) {
                    break
                }
                j++
            }
            // Capture SPS/PPS — INCLUDE the start code so MediaCodec sees Annex-B framing
            if (nt == 7 && sps == null) {
                sps = data.copyOfRange(i, j)
                Log.i(TAG, "captured SPS (${sps!!.size} bytes)")
            } else if (nt == 8 && pps == null) {
                pps = data.copyOfRange(i, j)
                Log.i(TAG, "captured PPS (${pps!!.size} bytes)")
            }
            i = j
        }
    }

    fun stop() {
        running = false
        try { outThread?.join(500) } catch (_: Throwable) {}
        try { codec?.stop() } catch (_: Throwable) {}
        try { codec?.release() } catch (_: Throwable) {}
        codec = null; sps = null; pps = null; configured = false
    }

    companion object { private const val TAG = "PreviewDecoder" }
}
