package io.github.sparkcyf.voicesipagent.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BatteryEstimateTest {
    private val min = 60_000L

    @Test
    fun timeLeftFollowsTheRealDrain() {
        var t = 0L
        BatteryEstimate.note(t, 80, charging = true)    // reset
        BatteryEstimate.note(t, 80, charging = false)
        // 1 % every 30 minutes, down to 76 %.
        for (p in 79 downTo 76) { t += 30 * min; BatteryEstimate.note(t, p, charging = false) }
        // 76 % at 30 min each, minus the 10 minutes since the last drop.
        assertEquals(76 * 30 - 10, BatteryEstimate.minutesLeft(t + 10 * min))
    }

    @Test
    fun noGuessFromOneDropOrAfterCharging() {
        BatteryEstimate.note(0, 90, charging = true)
        BatteryEstimate.note(0, 90, charging = false)
        BatteryEstimate.note(5 * min, 89, charging = false)
        assertNull(BatteryEstimate.minutesLeft(6 * min))
        BatteryEstimate.note(7 * min, 89, charging = true)
        assertNull(BatteryEstimate.minutesLeft(8 * min))
    }
}
