package com.mercurylabs.headspace

import java.io.OutputStream

/**
 * Parses Mercury Labs IMU SEI NALs out of a raw H.264 Annex-B byte stream.
 *
 * H.264 SEI NAL on the wire:
 *   00 00 00 01      Annex-B start code
 *   06               NAL header  (forbidden=0, ref_idc=00, type=00110 = 6 SEI)
 *   05               sei_message: payload_type = 5 (user_data_unregistered)
 *   PSIZE            payload_size — multi-byte: each 0xFF byte adds 255,
 *                    final byte (<255) is the remainder
 *   [16] UUID        "MERCURY-LABS-IMU"
 *   [N*32] samples   sequence of imu_sample_shm structs (little-endian)
 *   80               RBSP trailing bits (single 1 + zero pad)
 *
 * Bytes in the NAL body are RBSP-escaped: any sequence 00 00 00, 00 00 01,
 * 00 00 02, 00 00 03 in the raw payload gets a 0x03 byte inserted between
 * the second 00 and the next byte. We strip those before decoding.
 *
 * Sample layout (32 bytes, little-endian, matches src/imu_shm.h on the Pi):
 *   u64 boottime_ns
 *   i16 ax, ay, az
 *   i16 gx, gy, gz
 *   i16 mx, my, mz
 *   u8  mag_valid
 *   u8  pad
 */
class SeiParser(private val onSample: (ImuSample) -> Unit) {

    data class ImuSample(
        val boottimeNs: Long,
        val ax: Short, val ay: Short, val az: Short,
        val gx: Short, val gy: Short, val gz: Short,
        val mx: Short, val my: Short, val mz: Short,
        val magValid: Boolean,
    )

    companion object {
        // ASCII "MERCURY-LABS-IMU"
        val UUID = byteArrayOf(
            0x4D, 0x45, 0x52, 0x43, 0x55, 0x52, 0x59, 0x2D,
            0x4C, 0x41, 0x42, 0x53, 0x2D, 0x49, 0x4D, 0x55,
        )
        const val SAMPLE_BYTES = 32
    }

    private val acc = ByteArrayOutputStream(1 shl 20)   // accumulates input
    private var lastNalScan = 0
    var samplesParsed: Long = 0; private set
    var bytesParsed: Long = 0; private set

    /** Feed bytes from the live stream — finds + decodes any SEI NALs we
     *  recognise, ignores everything else (slice/SPS/PPS pass through).
     *  Safe to call with arbitrary chunk sizes including across NAL splits. */
    fun feed(data: ByteArray, off: Int = 0, len: Int = data.size) {
        acc.write(data, off, len)
        bytesParsed += len
        scan()
    }

    private fun scan() {
        val buf = acc.buf
        val end = acc.size
        var i = lastNalScan
        // Find Annex-B start codes (00 00 00 01 or 00 00 01) at or after i.
        while (i + 5 < end) {
            val sc4 = buf[i] == 0.toByte() && buf[i+1] == 0.toByte() &&
                      buf[i+2] == 0.toByte() && buf[i+3] == 1.toByte()
            val sc3 = !sc4 && buf[i] == 0.toByte() && buf[i+1] == 0.toByte() &&
                      buf[i+2] == 1.toByte()
            if (!sc4 && !sc3) { i++; continue }
            val scLen = if (sc4) 4 else 3
            val nalStart = i + scLen
            if (nalStart >= end) break
            val nt = buf[nalStart].toInt() and 0x1f
            // Find next start-code → that's the NAL boundary.
            val nextSc = findNextStartCode(buf, nalStart + 1, end)
            if (nextSc < 0) break  // need more data to know NAL end
            if (nt == 6) {
                // Try to decode as our IMU SEI; non-matching SEIs are skipped.
                tryDecodeSei(buf, nalStart + 1, nextSc)
            }
            i = nextSc
        }
        lastNalScan = i
        // Compact: drop everything before the unparsed tail to keep memory bounded.
        if (lastNalScan > (1 shl 16)) {
            val tail = end - lastNalScan
            System.arraycopy(buf, lastNalScan, buf, 0, tail)
            acc.reset(tail)
            lastNalScan = 0
        }
    }

    private fun findNextStartCode(buf: ByteArray, from: Int, end: Int): Int {
        var i = from
        while (i + 2 < end) {
            if (buf[i] == 0.toByte() && buf[i+1] == 0.toByte()) {
                if (i + 3 < end && buf[i+2] == 0.toByte() && buf[i+3] == 1.toByte()) return i
                if (buf[i+2] == 1.toByte()) return i
            }
            i++
        }
        return -1
    }

    /** SEI NAL body bytes (after the NAL header byte, up to next start code). */
    private fun tryDecodeSei(buf: ByteArray, start: Int, end: Int) {
        // Strip 00 00 03 → 00 00 (RBSP unescape)
        val rbsp = ByteArray(end - start)
        var ri = 0
        var i = start
        while (i < end) {
            if (i + 2 < end &&
                buf[i] == 0.toByte() && buf[i+1] == 0.toByte() &&
                buf[i+2] == 0x03.toByte()) {
                rbsp[ri++] = 0; rbsp[ri++] = 0
                i += 3
            } else {
                rbsp[ri++] = buf[i]; i++
            }
        }
        var p = 0
        if (p >= ri) return
        val payloadType = rbsp[p].toInt() and 0xff; p++
        if (payloadType != 5) return    // not user_data_unregistered
        // Multi-byte size: 0xFF*n + remainder
        var size = 0
        while (p < ri && (rbsp[p].toInt() and 0xff) == 0xff) { size += 255; p++ }
        if (p >= ri) return
        size += rbsp[p].toInt() and 0xff; p++
        if (p + size > ri) return
        if (size < 16) return
        // UUID match
        for (k in 0 until 16) {
            if (rbsp[p + k] != UUID[k]) return
        }
        p += 16
        size -= 16
        // Each sample = 32 bytes.
        var off = p
        var remaining = size
        while (remaining >= SAMPLE_BYTES) {
            val s = ImuSample(
                boottimeNs = readLeU64(rbsp, off),
                ax = readLeI16(rbsp, off + 8),
                ay = readLeI16(rbsp, off + 10),
                az = readLeI16(rbsp, off + 12),
                gx = readLeI16(rbsp, off + 14),
                gy = readLeI16(rbsp, off + 16),
                gz = readLeI16(rbsp, off + 18),
                mx = readLeI16(rbsp, off + 20),
                my = readLeI16(rbsp, off + 22),
                mz = readLeI16(rbsp, off + 24),
                magValid = rbsp[off + 26] != 0.toByte(),
            )
            onSample(s)
            samplesParsed++
            off += SAMPLE_BYTES
            remaining -= SAMPLE_BYTES
        }
    }

    private fun readLeU64(b: ByteArray, o: Int): Long {
        var v = 0L
        for (i in 0 until 8) v = v or ((b[o + i].toLong() and 0xffL) shl (8 * i))
        return v
    }
    private fun readLeI16(b: ByteArray, o: Int): Short {
        val v = ((b[o].toInt() and 0xff) or ((b[o + 1].toInt() and 0xff) shl 8))
        return (if (v >= 0x8000) v - 0x10000 else v).toShort()
    }
}

/** A growable byte buffer with public `buf` for in-place scanning. */
class ByteArrayOutputStream(initial: Int = 4096) : OutputStream() {
    var buf: ByteArray = ByteArray(initial); private set
    var size: Int = 0; private set

    override fun write(b: Int) { ensure(size + 1); buf[size++] = b.toByte() }
    override fun write(b: ByteArray, off: Int, len: Int) {
        ensure(size + len); System.arraycopy(b, off, buf, size, len); size += len
    }
    fun reset(newSize: Int = 0) { size = newSize }
    private fun ensure(cap: Int) {
        if (cap <= buf.size) return
        var c = buf.size
        while (c < cap) c = (c * 2).coerceAtLeast(cap)
        buf = buf.copyOf(c)
    }
}

/** Format a sample as a CSV row matching the recorder's .imu file layout. */
fun SeiParser.ImuSample.toCsvRow(): String =
    "$boottimeNs,$ax,$ay,$az,$gx,$gy,$gz,$mx,$my,$mz,${if (magValid) 1 else 0}\n"

/** Convenience: stream samples directly to a file. */
class ImuCsvWriter(private val out: OutputStream) {
    init {
        out.write("boottime_ns,ax,ay,az,gx,gy,gz,mx,my,mz,mag_valid\n".toByteArray())
    }
    fun write(s: SeiParser.ImuSample) { out.write(s.toCsvRow().toByteArray()) }
    fun close() = out.close()
}
