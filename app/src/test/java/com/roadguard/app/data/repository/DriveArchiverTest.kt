package com.roadguard.app.data.repository

import com.roadguard.app.domain.model.DriveRecord
import com.roadguard.app.domain.model.DriveSessionStats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The ViewModel's archive seam: which sessions become records,
 * and what the caller must do about the answer. Kept JVM-level —
 * the mapping rule lives in [DriveRecord.from], this pins the
 * decision the ViewModel's finishAndArchiveDrive() implements.
 */
class DriveArchiverTest {

    @Test
    fun aNormalDriveArchivesAndResets() {
        val stats = DriveSessionStats(
            startedAtMs = 0L,
            nowMs = 25 * 60_000L,
            laneDepartureCount = 1,
            collisionCount = 1,
            warningTimeMs = 120_000L
        )

        val record = DriveRecord.from(stats, endedAtMs = stats.nowMs)

        assertEquals(25 * 60_000L, record.durationMs)
        assertEquals(2, record.totalIncidents)
        // Rate penalty = 8*(2/25) = 0.64 (toInt truncates to 0);
        // severity = collisionCount*10 + urgentCount*10 = 10 -> 90.
        assertEquals(90, record.safetyScore)
        assertTrue(record.isMeaningful())
    }

    @Test
    fun anIncidentCarriesAShortDriveIntoTheLog() {
        val stats = DriveSessionStats(
            startedAtMs = 0L,
            nowMs = 300L,
            laneDepartureCount = 1
        )

        val record = DriveRecord.from(stats, endedAtMs = 300L)

        // The score of a record mirrors the session's own
        // DriveSessionStats.safetyScore — the persisted snapshot
        // must not invent a different number than the live HUD.
        assertEquals(stats.safetyScore, record.safetyScore)
        assertTrue(record.isMeaningful())
    }

    @Test
    fun aCleanSubSecondOpeningIsDropped() {
        val stats = DriveSessionStats(startedAtMs = 0L, nowMs = 200L)

        val record = DriveRecord.from(stats, endedAtMs = 200L)

        assertFalse(record.isMeaningful())
    }

    @Test
    fun theArchiverUsesThePassedEndTime() {
        // The archive call can happen seconds after the stats snapshot
        // was taken; the record must end where the caller says, not
        // where the snapshot was frozen.
        val stats = DriveSessionStats(
            startedAtMs = 0L,
            nowMs = 60_000L,
            collisionCount = 1
        )

        val record = DriveRecord.from(stats, endedAtMs = 90_000L)

        assertEquals(90_000L, record.endedAtMs)
        assertEquals(90_000L, record.durationMs)
    }

    @Test
    fun theScoreNeverLeavesItsScale() {
        val awful = DriveSessionStats(
            startedAtMs = 0L,
            nowMs = 1_000L,
            laneDepartureCount = 50,
            collisionCount = 50,
            urgentCollisionCount = 50
        )
        val record = DriveRecord.from(awful, endedAtMs = 1_000L)
        assertEquals(0, record.safetyScore)

        val clean = DriveSessionStats(startedAtMs = 0L, nowMs = 60_000L)
        val cleanRecord = DriveRecord.from(clean, endedAtMs = 60_000L)
        assertEquals(100, cleanRecord.safetyScore)
    }
}
