package com.roadguard.app.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
/**
 * The drive log turns an in-memory session into a persistent
 * history. Two contracts matter: the meaningful-record rule
 * (a cold start that dies a heartbeat after opening must not
 * litter the log) and the cap (an unbounded JSON blob in
 * SharedPreferences would make every app start parse it).
 */
class DriveRecordTest {

    @Test
    fun aDriveWithIncidentsIsMeaningfulEvenWhenShort() {
        val rushed = DriveRecord(
            startedAtMs = 0L,
            endedAtMs = 500L,
            laneDepartureCount = 1
        )
        assertTrue(rushed.isMeaningful())
    }

    @Test
    fun aShortCleanDriveIsNoise() {
        val rushed = DriveRecord(startedAtMs = 0L, endedAtMs = 500L)
        assertFalse(
            "a sub-second clean opening is app noise, not a drive",
            rushed.isMeaningful()
        )
    }

    @Test
    fun aNormalDriveIsMeaningful() {
        val commute = DriveRecord(
            startedAtMs = 0L,
            endedAtMs = 25 * 60_000L
        )
        assertTrue(commute.isMeaningful())
    }

    @Test
    fun theRecordExposesItsOwnDerivedValues() {
        val record = DriveRecord(
            startedAtMs = 0L,
            endedAtMs = 60_000L,
            laneDepartureCount = 1,
            collisionCount = 1,
            urgentCollisionCount = 1,
            warningTimeMs = 30_000L,
            safetyScore = 82
        )
        assertEquals(60_000L, record.durationMs)
        assertEquals(2, record.totalIncidents)
        assertEquals(0.5f, record.warningTimeFraction, 1e-6f)
        // The summary must at least carry the start marker and the
        // duration, so a history row is never blank.
        assertTrue(record.summary().length >= 2)
    }

    @Test
    fun aReversedRecordHasZeroDurationAndFraction() {
        val backwards = DriveRecord(
            startedAtMs = 10_000L,
            endedAtMs = 5_000L,
            warningTimeMs = 1_000L
        )
        assertEquals(0L, backwards.durationMs)
        assertEquals(0f, backwards.warningTimeFraction, 0f)
    }
}

class DriveLogTest {

    @Test
    fun addReturnsFalseAndKeepsNothingForAMeaninglessRecord() {
        val log = DriveLog()
        val noise = DriveRecord(startedAtMs = 0L, endedAtMs = 100L)

        assertFalse(log.add(noise))
        assertTrue(log.records.isEmpty())
    }

    @Test
    fun recordsComeBackNewestFirst() {
        val log = DriveLog()
        val morning = DriveRecord(startedAtMs = 1L, endedAtMs = 60_000L)
        val evening = DriveRecord(startedAtMs = 2L, endedAtMs = 120_000L)

        assertTrue(log.add(morning))
        assertTrue(log.add(evening))

        assertEquals(listOf(evening, morning), log.records)
    }

    @Test
    fun theLogIsBoundedToTheCap() {
        val log = DriveLog(maxEntries = 3)
        for (i in 1..10) {
            log.add(
                DriveRecord(
                    startedAtMs = i.toLong(),
                    endedAtMs = i + 60_000L
                )
            )
        }

        assertEquals(3, log.records.size)
        // Newest three survive, oldest are dropped.
        assertEquals(10L, log.records[0].startedAtMs)
        assertEquals(8L, log.records[2].startedAtMs)
    }

    @Test
    fun replaceAllKeepsTheNewestEntriesOfTheInput() {
        val log = DriveLog(maxEntries = 2)
        // Input is oldest-first (as a re-read from an unbounded
        // store would be): replaceAll must keep the NEWEST two,
        // not the oldest two it happens to encounter first.
        val stored = (1..5).reversed().map {
            DriveRecord(startedAtMs = it.toLong(), endedAtMs = it + 60_000L)
        }

        log.replaceAll(stored)

        assertEquals(2, log.records.size)
        assertEquals(5L, log.records[0].startedAtMs)
        assertEquals(4L, log.records[1].startedAtMs)
    }

    @Test
    fun clearEmptiesTheLog() {
        val log = DriveLog()
        log.add(DriveRecord(startedAtMs = 1L, endedAtMs = 60_000L))

        log.clear()

        assertTrue(log.records.isEmpty())
    }

    @Test
    fun theDefaultCapMatchesTheDocumentedHistorySize() {
        assertEquals(30, DriveLog.DEFAULT_MAX_ENTRIES)
    }

    @Test
    fun aDriveStoredAtTheCapEvictsTheOldest() {
        // The cap must hold on the WRITE path too, not only on
        // re-read: a stored drive pushes the oldest out.
        val log = DriveLog(maxEntries = 3)
        for (i in 1..4) {
            log.add(DriveRecord(startedAtMs = i.toLong(), endedAtMs = i + 60_000L))
        }

        assertEquals(3, log.records.size)
        assertEquals(4L, log.records.first().startedAtMs)
        assertEquals(2L, log.records.last().startedAtMs)
    }

    @Test
    fun withAppendedReturnsANewInstanceWithTheRecord() {
        val original = DriveLog()
        original.add(DriveRecord(startedAtMs = 1L, endedAtMs = 61_000L))

        val updated = original.withAppended(
            DriveRecord(startedAtMs = 2L, endedAtMs = 121_000L)
        )

        // NEW instance: callers publish the result through a
        // StateFlow, which conflates equal values.
        assertTrue(updated !== original)
        assertEquals(
            listOf(2L, 1L),
            updated!!.records.map { it.startedAtMs }
        )
        // The source log is untouched — it may still be held
        // by a collector that has not recomposed yet.
        assertEquals(1, original.records.size)
    }

    @Test
    fun withAppendedReturnsNullForAMeaninglessRecord() {
        val noise = DriveRecord(startedAtMs = 0L, endedAtMs = 100L)

        assertNull(DriveLog().withAppended(noise))
    }

    @Test
    fun clearedReturnsAnEmptyNewInstance() {
        val original = DriveLog()
        original.add(DriveRecord(startedAtMs = 1L, endedAtMs = 61_000L))

        val cleared = original.cleared()

        assertTrue(cleared !== original)
        assertTrue(cleared.records.isEmpty())
    }
}
