package com.mercurylabs.headspace

import org.junit.Assert.*
import org.junit.Test

/**
 * Validates the Kotlin SEI parser against a real H.264 stream captured from
 * the Pi's daemon (with IMU SEI injected). Same fixture decoded by Python
 * earlier on the Mac side — these numbers are the ground truth.
 *
 * Expected (from `python3 sei-dump.h264`):
 *   total NALs: 12, SEI: 6, slices: 6
 *   each SEI: payload_type=5, size=624 (16 UUID + 19 samples × 32 B)
 *   sample[0].boottime_ns ≈ 463_323 ms with Δ ≈ 1750 µs between samples
 */
class SeiParserTest {

    private fun fixture(): ByteArray =
        SeiParserTest::class.java.getResourceAsStream("/sei-fixture.h264")!!
            .use { it.readBytes() }

    @Test fun parses_all_imu_samples() {
        val samples = mutableListOf<SeiParser.ImuSample>()
        val p = SeiParser { samples.add(it) }
        p.feed(fixture())
        // 6 SEI NALs × 19 samples each = 114
        assertEquals("samples parsed", 114L, p.samplesParsed)
        assertEquals("samples collected", 114, samples.size)
    }

    @Test fun timestamps_are_monotonic_and_close_to_native_imu_rate() {
        val samples = mutableListOf<SeiParser.ImuSample>()
        SeiParser { samples.add(it) }.feed(fixture())
        assertTrue("got samples", samples.size >= 2)
        for (k in 1 until samples.size) {
            assertTrue("sample $k timestamp goes forward",
                samples[k].boottimeNs >= samples[k-1].boottimeNs)
        }
        val deltas = (1 until samples.size).map { samples[it].boottimeNs - samples[it-1].boottimeNs }
        // Most deltas should be ~1.75 ms (562 Hz); allow stragglers between SEIs.
        val medianDeltaUs = deltas.sorted()[deltas.size / 2] / 1000
        assertTrue("median Δ ≈ 1.75 ms (got ${medianDeltaUs}us)",
            medianDeltaUs in 1500L..2000L)
    }

    @Test fun streamed_bytes_equivalent_to_one_shot() {
        // Feed the same data in tiny chunks (simulating USB packet arrivals)
        // and confirm we get the same samples in the same order.
        val data = fixture()
        val full = mutableListOf<SeiParser.ImuSample>()
        SeiParser { full.add(it) }.feed(data)
        val streamed = mutableListOf<SeiParser.ImuSample>()
        val p = SeiParser { streamed.add(it) }
        var i = 0
        while (i < data.size) {
            val chunk = (1 + (i % 47)).coerceAtMost(data.size - i)   // jagged sizes
            p.feed(data, i, chunk)
            i += chunk
        }
        assertEquals(full.size, streamed.size)
        for (k in full.indices) {
            assertEquals("sample $k boottime", full[k].boottimeNs, streamed[k].boottimeNs)
            assertEquals("sample $k ax", full[k].ax, streamed[k].ax)
            assertEquals("sample $k mag_valid", full[k].magValid, streamed[k].magValid)
        }
    }

    @Test fun sample_fields_match_python_decoded_first_sample() {
        // python3 SEI decode (verified live on Mac) of sample[0]:
        //   ts=463323ms, ax=153, ay=3143, az=2546, gx=-16, gy=16, gz=5, magValid=false
        val s0 = run {
            val out = mutableListOf<SeiParser.ImuSample>()
            SeiParser { out.add(it) }.feed(fixture())
            out.first()
        }
        assertEquals(153, s0.ax.toInt())
        assertEquals(3143, s0.ay.toInt())
        assertEquals(2546, s0.az.toInt())
        assertEquals(-16, s0.gx.toInt())
        assertEquals(16, s0.gy.toInt())
        assertEquals(5, s0.gz.toInt())
        assertFalse(s0.magValid)
        // boottime is a real value, not 0
        assertTrue(s0.boottimeNs > 100_000_000_000L)
    }
}
