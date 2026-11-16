package com.callagent.rtcmedia

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayCacheAgeTest {
    private val now = 10_000_000_000L
    private val day = 86_400_000L

    @Test fun externalCredentialsAreRefetchedHourlyEvenWhenTheyLiveLonger() {
        assertTrue(RelayRepository.reusable(now + day, now - 59 * 60_000L, external = true, now = now))
        assertFalse(RelayRepository.reusable(now + day, now - 60 * 60_000L, external = true, now = now))
    }

    @Test fun nodeCredentialsFollowTheirExpiry() {
        assertTrue(RelayRepository.reusable(now + day, now - 5 * 3_600_000L, external = false, now = now))
    }

    @Test fun nothingIsReusedWithinHalfAnHourOfExpiry() {
        assertFalse(RelayRepository.reusable(now + 30 * 60_000L, now, external = false, now = now))
        assertFalse(RelayRepository.reusable(now + 30 * 60_000L, now, external = true, now = now))
    }

    @Test fun aClockStepBackwardsForcesARefetch() {
        assertFalse(RelayRepository.reusable(now + day, now + 60_000L, external = true, now = now))
    }
}
