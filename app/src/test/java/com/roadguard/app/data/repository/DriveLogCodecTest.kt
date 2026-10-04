package com.roadguard.app.data.repository

import com.roadguard.app.domain.model.DriveRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The persisted form of the drive history. These tests pin the
 * round trip through the JSON blob: what goes in must come back
 * newest-first and intact, and a corrupt blob must read as "no
 * history" rather than throwing on the next app start.
 */
class DriveLogCodecTest {

    private fun record(startedAtMs: Long, durationMs: Long = 60_000L) =
        DriveRecord(
            startedAtMs = startedAtMs,
            endedAtMs = startedAtMs + durationMs,
            laneDepartureCount = 1,
            collisionCount = 1,
            urgentCollisionCount = 1,
            warningTimeMs = 30_000L,
            safetyScore = 76
        )

    @Test
    fun aRoundTripPreservesEveryFieldAndOrder() {
        val morning = record(1L)
        val evening = record(2L, durationMs = 120_000L)
        val newestFirst = listOf(evening, morning)

        val restored = DriveLogCodec.deserialize(
            DriveLogCodec.serialize(newestFirst)
        )

        assertEquals(newestFirst, restored)
        assertEquals(2, restored?.size)
        assertEquals(76, restored?.first()?.safetyScore)
        assertEquals(120_000L, restored?.first()?.durationMs)
        assertEquals(30_000L, restored?.first()?.warningTimeMs)
    }

    @Test
    fun anEmptyListRoundTrips() {
        val restored = DriveLogCodec.deserialize(DriveLogCodec.serialize(emptyList()))

        assertEquals(emptyList<DriveRecord>(), restored)
    }

    @Test
    fun absentInputReadsAsAbsent() {
        assertNull(DriveLogCodec.deserialize(null))
    }

    @Test
    fun aCorruptBlobReadsAsAbsentInsteadOfThrowing() {
        // What a killed process can leave behind mid-write.
        assertNull(DriveLogCodec.deserialize("{\"startedAtMs\": 1"))
        assertNull(DriveLogCodec.deserialize("not json at all"))
    }

    @Test
    fun anEmptyStringRoundTripsAsAnEmptyLog() {
        // Gson treats "" as an empty array, not as an error — the
        // log reads back empty, which is the safe outcome either way.
        assertEquals(emptyList<DriveRecord>(), DriveLogCodec.deserialize(""))
    }
}
