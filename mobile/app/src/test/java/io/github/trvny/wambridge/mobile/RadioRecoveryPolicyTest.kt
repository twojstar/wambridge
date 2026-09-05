package io.github.trvny.wambridge.mobile

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class RadioRecoveryPolicyTest {
    @Test
    fun recoveringRadioStillOwnsPlayback() {
        assertTrue(radioOwnerActive(starting = false, running = false, recovering = true))
        assertFalse(radioOwnerActive(starting = false, running = false, recovering = false))
    }

    @Test
    fun reconnectRetriesAreBoundedButOfflineWaitingIsNot() {
        assertTrue(RadioService.canRetryWifi(attempt = 5, waitingForNetwork = false))
        assertFalse(RadioService.canRetryWifi(attempt = 6, waitingForNetwork = false))
        assertTrue(RadioService.canRetryWifi(attempt = 6, waitingForNetwork = true))
    }

    @Test
    fun reconnectBackoffCapsAtOneMinute() {
        assertEquals(2_500L, RadioService.wifiRetryDelayMs(0, waitingForNetwork = false))
        assertEquals(40_000L, RadioService.wifiRetryDelayMs(4, waitingForNetwork = false))
        assertEquals(60_000L, RadioService.wifiRetryDelayMs(5, waitingForNetwork = false))
        assertEquals(5_000L, RadioService.wifiRetryDelayMs(8, waitingForNetwork = true))
    }
}
