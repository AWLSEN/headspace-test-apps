package com.mercurylabs.headspace

import org.junit.Assert.*
import org.junit.Test

/**
 * Bit-exact verification of the UVC class-control byte structure against
 * the USB Video Class 1.1 spec, section 4.3.1.1 (Probe & Commit Controls).
 *
 * Why this matters: the host has to send these exact bytes over a control
 * transfer before the camera will produce any frames. Off-by-one in the
 * struct layout = device sits idle. We catch that here, in CI, no phone
 * needed.
 *
 * Spec layout for the 1.1 probe payload (34 bytes, little-endian):
 *
 *  off  size  field
 *   0   2    bmHint
 *   2   1    bFormatIndex
 *   3   1    bFrameIndex
 *   4   4    dwFrameInterval     (100 ns units)
 *   8   2    wKeyFrameRate
 *  10   2    wPFrameRate
 *  12   2    wCompQuality
 *  14   2    wCompWindowSize
 *  16   2    wDelay
 *  18   4    dwMaxVideoFrameSize
 *  22   4    dwMaxPayloadTransferSize
 *  26   4    dwClockFrequency
 *  30   1    bmFramingInfo
 *  31   1    bPreferedVersion
 *  32   1    bMinVersion
 *  33   1    bMaxVersion
 */
class UvcControlTest {

    @Test fun probe_payload_is_exactly_34_bytes() {
        val p = UvcControl.buildProbe(1, 1, 333_333)
        assertEquals(34, p.size)
    }

    @Test fun bmHint_is_0x0001_le() {
        val p = UvcControl.buildProbe(1, 1, 333_333)
        assertEquals(0x01.toByte(), p[0])
        assertEquals(0x00.toByte(), p[1])
    }

    @Test fun format_and_frame_indices_in_right_slots() {
        val p = UvcControl.buildProbe(1, 1, 333_333)
        assertEquals(1, p[2].toInt() and 0xff)   // bFormatIndex
        assertEquals(1, p[3].toInt() and 0xff)   // bFrameIndex

        val p2 = UvcControl.buildProbe(7, 4, 0)
        assertEquals(7, p2[2].toInt() and 0xff)
        assertEquals(4, p2[3].toInt() and 0xff)
    }

    @Test fun frame_interval_is_le_uint32_at_offset_4() {
        // 333_333 (100 ns units = 33.3333 ms) → 30 fps
        // 333333 = 0x00051615 → little-endian bytes 15 16 05 00
        val p = UvcControl.buildProbe(1, 1, 333_333)
        assertEquals(0x15.toByte(), p[4])
        assertEquals(0x16.toByte(), p[5])
        assertEquals(0x05.toByte(), p[6])
        assertEquals(0x00.toByte(), p[7])
    }

    @Test fun trailing_fields_are_zero_per_host_request() {
        // The host MUST send 0 for the device-fillable fields on SET_CUR;
        // the device echoes back its negotiated values on GET_CUR.
        val p = UvcControl.buildProbe(1, 1, 333_333)
        for (i in 8 until 34) {
            assertEquals("byte $i should be 0", 0.toByte(), p[i])
        }
    }

    @Test fun roundtrip_parsing_negotiated_payload() {
        // Synthesise a "device echoed back" payload with non-zero negotiated
        // sizes and check our parsers read them from the right offsets.
        val p = UvcControl.buildProbe(1, 1, 333_333).copyOf()
        // dwMaxVideoFrameSize = 0x000A1000 (≈ 660 KB) at offset 18
        p[18] = 0x00; p[19] = 0x10; p[20] = 0x0A; p[21] = 0x00
        // dwMaxPayloadTransferSize = 0x00010000 (64 KB) at offset 22
        p[22] = 0x00; p[23] = 0x00; p[24] = 0x01; p[25] = 0x00
        assertEquals(0x000A1000, UvcControl.parseMaxVideoFrameSize(p))
        assertEquals(0x00010000, UvcControl.parseMaxPayloadTransferSize(p))
    }

    @Test fun control_request_constants_match_uvc_spec_table_4_5() {
        // bmRequestType for class interface SET = 0010 0001 = 0x21
        assertEquals(0x21, UvcControl.BM_REQ_SET)
        // bmRequestType for class interface GET = 1010 0001 = 0xA1
        assertEquals(0xA1, UvcControl.BM_REQ_GET)
        // bRequest opcodes from UVC 1.1 Table 4-5
        assertEquals(0x01, UvcControl.SET_CUR)
        assertEquals(0x81, UvcControl.GET_CUR)
        // wValue control selectors from Table 4-44
        assertEquals(0x01, UvcControl.VS_PROBE_CONTROL)
        assertEquals(0x02, UvcControl.VS_COMMIT_CONTROL)
    }
}
