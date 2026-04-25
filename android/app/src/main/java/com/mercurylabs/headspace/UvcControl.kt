package com.mercurylabs.headspace

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * UVC class control plumbing — the bytes we have to send before the camera
 * will produce frames.  Reference: USB Video Class 1.1 spec, section 4.3.1.1
 * (VideoStreaming Interface Control Requests).
 *
 * Sequence the host has to follow to start streaming:
 *   1) SET_INTERFACE to a non-zero alt setting on the streaming interface
 *      (alt 0 has no bandwidth — pure default).
 *   2) SET_CUR on VS_PROBE_CONTROL with our preferred format/frame/interval.
 *   3) GET_CUR on VS_PROBE_CONTROL — device echoes back the negotiated
 *      values, including dwMaxVideoFrameSize and dwMaxPayloadTransferSize
 *      (which we'd have to honour but for bulk endpoints we just read).
 *   4) SET_CUR on VS_COMMIT_CONTROL with those negotiated values.
 *   5) Start reading the bulk/iso IN endpoint.
 *
 * This file deals with steps 2-4 — pure byte construction. The actual
 * controlTransfer() calls live in UvcCamera.kt.
 */
object UvcControl {
    // bmRequestType
    const val BM_REQ_SET = 0x21    // Class | Interface | Host-to-Device
    const val BM_REQ_GET = 0xA1    // Class | Interface | Device-to-Host

    // bRequest
    const val SET_CUR = 0x01
    const val GET_CUR = 0x81
    const val GET_MIN = 0x82
    const val GET_MAX = 0x83
    const val GET_DEF = 0x87

    // wValue control selectors (high byte)
    const val VS_PROBE_CONTROL  = 0x01
    const val VS_COMMIT_CONTROL = 0x02

    /** Wire size of the probe/commit control struct. UVC 1.1 = 34 bytes,
     *  UVC 1.5 = 48. We always negotiate 34 — works for our gadget and is
     *  a strict subset of 1.5 (devices that need 48 will report it via
     *  GET_LEN, which we'd then handle). */
    const val PROBE_LEN = 34

    /** Build a UVC 1.1 probe control payload. Most fields are 0 — the
     *  device is required to fill them in on GET_CUR.
     *
     *  @param formatIndex  bFormatIndex — 1-based index of the chosen
     *      VS_FORMAT_FRAMEBASED descriptor. For our gadget the only
     *      framebased format is index 1 (H.264).
     *  @param frameIndex   bFrameIndex — 1-based index of the chosen
     *      VS_FRAME_FRAMEBASED descriptor inside the format. We expose
     *      one frame size (1920x1080), so this is 1.
     *  @param frameInterval100ns  desired frame interval in 100 ns units.
     *      33333 = 30 fps (3.3333 ms × 10 = 333 333 → /10 from 100ns =
     *      33333.3, so 333333). Our gadget pins this so the value is fixed.
     */
    fun buildProbe(
        formatIndex: Int,
        frameIndex: Int,
        frameInterval100ns: Int,
    ): ByteArray {
        val b = ByteBuffer.allocate(PROBE_LEN).order(ByteOrder.LITTLE_ENDIAN)
        // bmHint: bit 0 set = "frame interval is fixed", others negotiable.
        b.putShort(0x0001)
        b.put(formatIndex.toByte())                // bFormatIndex
        b.put(frameIndex.toByte())                 // bFrameIndex
        b.putInt(frameInterval100ns)               // dwFrameInterval
        b.putShort(0)                              // wKeyFrameRate
        b.putShort(0)                              // wPFrameRate
        b.putShort(0)                              // wCompQuality
        b.putShort(0)                              // wCompWindowSize
        b.putShort(0)                              // wDelay
        b.putInt(0)                                // dwMaxVideoFrameSize
        b.putInt(0)                                // dwMaxPayloadTransferSize
        b.putInt(0)                                // dwClockFrequency
        b.put(0)                                   // bmFramingInfo
        b.put(0)                                   // bPreferedVersion
        b.put(0)                                   // bMinVersion
        b.put(0)                                   // bMaxVersion
        return b.array()
    }

    /** Decode the dwMaxPayloadTransferSize the device echoed back in
     *  GET_CUR(probe) — that's the maximum bytes per USB transfer the
     *  device wants us to use. Useful for sizing iso reads. */
    fun parseMaxPayloadTransferSize(probe: ByteArray): Int {
        require(probe.size >= PROBE_LEN) { "probe too short: ${probe.size}" }
        val b = ByteBuffer.wrap(probe).order(ByteOrder.LITTLE_ENDIAN)
        return b.getInt(22)   // offset 22 in the 1.1 layout
    }

    fun parseMaxVideoFrameSize(probe: ByteArray): Int {
        require(probe.size >= PROBE_LEN) { "probe too short: ${probe.size}" }
        val b = ByteBuffer.wrap(probe).order(ByteOrder.LITTLE_ENDIAN)
        return b.getInt(18)   // dwMaxVideoFrameSize
    }
}
