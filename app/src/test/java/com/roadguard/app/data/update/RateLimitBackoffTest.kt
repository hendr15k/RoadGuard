package com.roadguard.app.data.update

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Contract for the 403 backoff. GitHub sends a primary rate limit as 403 +
 * `X-RateLimit-Reset` with no `Retry-After`; treating the missing header as
 * "60 seconds" made the updater poll once a minute through a one-hour window
 * and spend the remaining quota on the polls.
 */
class RateLimitBackoffTest {

    private val nowMs = 1_700_000_000_000L
    private val default = 300_000L

    @Test
    fun retryAfterWinsWhenPresent() {
        assertEquals(45_000L, rateLimitBackoffMs(45L, null, nowMs, default))
    }

    @Test
    fun aPrimaryLimitUsesTheResetStampNotTheDefault() {
        // Reset 20 minutes in the future.
        val resetEpochSeconds = (nowMs + 20 * 60_000L) / 1000L

        assertEquals(20 * 60_000L, rateLimitBackoffMs(null, resetEpochSeconds, nowMs, default))
    }

    @Test
    fun retryAfterBeatsTheResetStampWhenBothArePresent() {
        val resetEpochSeconds = (nowMs + 20 * 60_000L) / 1000L

        assertEquals(10_000L, rateLimitBackoffMs(10L, resetEpochSeconds, nowMs, default))
    }

    @Test
    fun anAlreadyElapsedResetDoesNotProduceANegativeWait() {
        val resetEpochSeconds = (nowMs - 60_000L) / 1000L

        assertEquals(0L, rateLimitBackoffMs(null, resetEpochSeconds, nowMs, default))
    }

    @Test
    fun withoutAnyHeaderTheDefaultIsUsed() {
        assertEquals(default, rateLimitBackoffMs(null, null, nowMs, default))
    }

    @Test
    fun theDefaultIsMuchLongerThanTheOldMinute() {
        assertEquals(
            "a flat minute kept the updater hammering a one-hour rate-limit window",
            true,
            default >= 5 * 60_000L
        )
    }
}
