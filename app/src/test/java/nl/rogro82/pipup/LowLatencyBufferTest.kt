package nl.rogro82.pipup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LowLatencyBufferTest {
    @Test
    fun absentKeepsExoPlayerDefaults() {
        assertNull(lowLatencyBufferDurations(null))
    }

    @Test
    fun reportersSettingStartsAfter150ms() {
        // min, max, start, rebuffer
        assertEquals(listOf(1_000, 5_000, 150, 300), lowLatencyBufferDurations(150))
    }

    @Test
    fun clampedToTheRange() {
        assertEquals(100, lowLatencyBufferDurations(0)!![2])
        assertEquals(10_000, lowLatencyBufferDurations(60_000)!![2])
    }

    @Test
    fun alwaysPassesDefaultLoadControlChecks() {
        for (ms in listOf(-5, 0, 100, 150, 300, 499, 500, 2_500, 9_999, 10_000, 50_000)) {
            val (min, max, start, rebuffer) = lowLatencyBufferDurations(ms)!!
            assertTrue("start >= 0 ($ms)", start >= 0)
            assertTrue("min >= start ($ms)", min >= start)
            assertTrue("min >= rebuffer ($ms)", min >= rebuffer)
            assertTrue("max >= min ($ms)", max >= min)
        }
    }
}
