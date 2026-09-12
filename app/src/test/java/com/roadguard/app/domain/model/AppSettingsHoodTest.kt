package com.roadguard.app.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppSettingsHoodTest {

    @Test
    fun hoodFractionIsClampedToTheSupportedRange() {
        assertEquals(
            AppSettings.MAX_HOOD_FRACTION,
            AppSettings(hoodFraction = 0.9f).sanitized().hoodFraction,
            0.0001f
        )
        assertEquals(
            AppSettings.MIN_HOOD_FRACTION,
            AppSettings(hoodFraction = -0.2f).sanitized().hoodFraction,
            0.0001f
        )
        assertEquals(
            0.15f,
            AppSettings(hoodFraction = 0.15f).sanitized().hoodFraction,
            0.0001f
        )
    }

    @Test
    fun defaultHoodFractionIsWithinBounds() {
        val f = AppSettings.DEFAULT_HOOD_FRACTION
        assertTrue(f >= AppSettings.MIN_HOOD_FRACTION && f <= AppSettings.MAX_HOOD_FRACTION)
    }
}
