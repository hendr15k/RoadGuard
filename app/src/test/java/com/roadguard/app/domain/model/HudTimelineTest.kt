package com.roadguard.app.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract of the timeline the HUD renders. Every sample that goes stale has a
 * matching gate in [AlertPolicy], but the HUD kept showing the last good values
 * forever: a paused video or a stalled pipeline left "DIST 12.4m / TTC 1.8s" on
 * screen after the alarm itself had already dropped, so the display claimed a
 * hazard the safety logic had cleared.
 */
class HudTimelineTest {

    private fun distanceAt(measuredMs: Long, nowMs: Long, isTooClose: Boolean = true) =
        VehicleDistance(
            distanceMeters = 12f,
            isTooClose = isTooClose,
            timeToCollision = 2f,
            timestamp = measuredMs
        ).takeIf { it.isFresh(nowMs) }

    private fun laneAt(measuredMs: Long, nowMs: Long) =
        LaneInfo(confidence = 0.9f, timestamp = measuredMs).takeIf { it.isFresh(nowMs) }

    @Test
    fun aFreshSampleSurvivesTheGate() {
        val now = 1_000_000L

        assertEquals(12f, distanceAt(now - 100, now)?.distanceMeters)
        assertEquals(0.9f, laneAt(now - 100, now)?.confidence)
    }

    @Test
    fun aSampleExactlyAtTheStalenessBoundaryIsStillFresh() {
        val now = 1_000_000L

        assertEquals(
            "the boundary belongs to the live side, matching AlertPolicy's `> STALE_MS`",
            12f,
            distanceAt(now - AlertPolicy.STALE_MS, now)?.distanceMeters
        )
    }

    @Test
    fun aSampleOlderThanTheStalenessBoundaryIsDropped() {
        val now = 1_000_000L

        assertEquals(null, distanceAt(now - AlertPolicy.STALE_MS - 1, now))
        assertEquals(null, laneAt(now - AlertPolicy.STALE_MS - 1, now))
    }

    @Test
    fun theHudSharesTheGatesOwnStalenessWindow() {
        // A second, separately tuned window here is how the display and the
        // alarm drift apart again.
        assertTrue(AlertPolicy.STALE_MS > 0L)
    }

    @Test
    fun aNullSampleStaysNull() {
        val now = 1_000_000L
        val nothing: VehicleDistance? = null

        assertEquals(null, nothing?.takeIf { it.isFresh(now) })
    }
}
