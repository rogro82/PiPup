package nl.rogro82.pipup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HaManagedUpdatesTest {

    private val now = 1_800_000_000_000L
    private val hour = 60L * 60 * 1000
    private val day = 24 * hour

    @Test
    fun headerValues() {
        assertEquals(true, HaManagedUpdates.parse("on"))
        assertEquals(true, HaManagedUpdates.parse(" ON "))
        assertEquals(false, HaManagedUpdates.parse("off"))
        assertNull(HaManagedUpdates.parse("yes"))
        assertNull(HaManagedUpdates.parse(""))
        assertNull(HaManagedUpdates.parse(null))
    }

    @Test
    fun activeForAWeekAfterTheLastOn() {
        assertFalse(HaManagedUpdates.active(null, now))
        assertTrue(HaManagedUpdates.active(now, now))
        assertTrue(HaManagedUpdates.active(now - 6 * day, now))
        assertFalse(HaManagedUpdates.active(now - 7 * day, now))
        // a clock that went backwards does not switch it off
        assertTrue(HaManagedUpdates.active(now + hour, now))
    }

    @Test
    fun storedTimeIsRewrittenAtMostHourly() {
        assertTrue(HaManagedUpdates.needsRefresh(null, now))
        assertFalse(HaManagedUpdates.needsRefresh(now - 59 * 60 * 1000, now))
        assertTrue(HaManagedUpdates.needsRefresh(now - hour, now))
        assertTrue(HaManagedUpdates.needsRefresh(now + 1000, now))
    }
}
