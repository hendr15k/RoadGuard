package com.roadguard.app.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Contract for the alert gate between raw detections and the driver-facing
 * alarm. Raw LaneInfo/VehicleDistance arrive at ~5 Hz from two independent
 * pipelines (live camera or a video file that can be paused), so a single frame
 * must never trigger an alarm, a stale sample must not keep one ringing, and a
 * collision must always outrank a lane drift.
 */
class AlertPolicyTest {

    private lateinit var policy: AlertPolicy

    private val driftingLeft = LaneInfo(
        isDriftingLeft = true,
        confidence = 0.9f,
        leftLaneVisible = true,
        rightLaneVisible = true
    )
    private val driftingRight = LaneInfo(
        isDriftingRight = true,
        confidence = 0.9f,
        leftLaneVisible = true,
        rightLaneVisible = true
    )
    private val tooClose = VehicleDistance(
        distanceMeters = 8f,
        isTooClose = true,
        timeToCollision = 1.5f,
        timestamp = 0L
    )

    @Before
    fun setUp() {
        policy = AlertPolicy()
    }

    private fun feed(
        nowMs: Long,
        laneInfo: LaneInfo? = null,
        distance: VehicleDistance? = null,
        settings: AppSettings = AppSettings()
    ) = policy.evaluate(
        settings = settings,
        // Tests feed logical frames: stamp them fresh so the STALE_MS gate
        // (meant for paused video/stalled pipelines) never fires here.
        laneInfo = laneInfo?.copy(timestamp = nowMs),
        distance = distance?.copy(timestamp = nowMs),
        nowMs = nowMs
    )

    private fun AlertState.typeOrNull(): WarningType? = (this as? AlertState.Warning)?.type
    private fun AlertState.phaseOrNull(): AlertPhase? = (this as? AlertState.Warning)?.phase

    // --- confirmation -------------------------------------------------------

    @Test
    fun aSingleHazardFrameDoesNotAlarm() {
        val evaluation = feed(nowMs = 1_000, laneInfo = driftingLeft)

        assertEquals(WarningType.LaneDepartureLeft, evaluation.state.typeOrNull())
        assertEquals(AlertPhase.CONFIRMING, evaluation.state.phaseOrNull())
        assertNull("no alarm before the confirmation window elapsed", evaluation.signal)
    }

    @Test
    fun aHazardAlarmsAfterItPersistsLongEnough() {
        feed(nowMs = 1_000, laneInfo = driftingLeft)
        val evaluation = feed(nowMs = 1_000 + AlertPolicy.LANE_CONFIRM_MS, laneInfo = driftingLeft)

        assertEquals(WarningType.LaneDepartureLeft, evaluation.state.typeOrNull())
        assertEquals(AlertPhase.ACTIVE, evaluation.state.phaseOrNull())
        assertEquals(WarningType.LaneDepartureLeft, evaluation.signal?.type)
        assertEquals(0, evaluation.signal?.repeatIndex)
    }

    @Test
    fun anIntermittentHazardNeverConfirms() {
        feed(nowMs = 1_000, laneInfo = driftingLeft)
        feed(nowMs = 1_200, laneInfo = null)
        feed(nowMs = 1_400, laneInfo = driftingLeft)
        feed(nowMs = 1_600, laneInfo = null)
        val evaluation = feed(nowMs = 1_800, laneInfo = driftingLeft)

        assertEquals(AlertPhase.CONFIRMING, evaluation.state.phaseOrNull())
        assertNull(evaluation.signal)
    }

    @Test
    fun collisionConfirmsFasterThanLaneDrift() {
        feed(nowMs = 1_000, distance = tooClose)
        val evaluation = feed(nowMs = 1_000 + AlertPolicy.COLLISION_CONFIRM_MS, distance = tooClose)

        assertEquals(WarningType.ForwardCollision, evaluation.state.typeOrNull())
        assertEquals(AlertPhase.ACTIVE, evaluation.state.phaseOrNull())
        assertTrue(AlertPolicy.COLLISION_CONFIRM_MS < AlertPolicy.LANE_CONFIRM_MS)
    }

    // --- priority -----------------------------------------------------------

    @Test
    fun collisionOutranksLaneDeparture() {
        val evaluation = feed(nowMs = 1_100, laneInfo = driftingLeft, distance = tooClose)

        assertEquals(WarningType.ForwardCollision, evaluation.state.typeOrNull())
    }

    @Test
    fun priorityHandoverRestartsConfirmation() {
        feed(nowMs = 1_000, laneInfo = driftingLeft, distance = tooClose)
        val collision = feed(
            nowMs = 1_000 + AlertPolicy.COLLISION_CONFIRM_MS,
            laneInfo = driftingLeft,
            distance = tooClose
        )

        assertEquals(WarningType.ForwardCollision, collision.state.typeOrNull())
        assertEquals(AlertPhase.ACTIVE, collision.state.phaseOrNull())

        // Collision clears, lane drift takes over — it must start from scratch.
        val afterHandover = feed(
            nowMs = 1_200 + AlertPolicy.COLLISION_CONFIRM_MS,
            laneInfo = driftingLeft
        )
        assertEquals(WarningType.LaneDepartureLeft, afterHandover.state.typeOrNull())
        assertEquals(AlertPhase.CONFIRMING, afterHandover.state.phaseOrNull())
        assertNull(afterHandover.signal)
    }

    @Test
    fun switchingDriftDirectionRestartsConfirmation() {
        feed(nowMs = 1_000, laneInfo = driftingLeft)
        feed(nowMs = 1_000 + AlertPolicy.LANE_CONFIRM_MS, laneInfo = driftingLeft)

        val evaluation = feed(nowMs = 1_100 + AlertPolicy.LANE_CONFIRM_MS, laneInfo = driftingRight)

        assertEquals(WarningType.LaneDepartureRight, evaluation.state.typeOrNull())
        assertEquals(AlertPhase.CONFIRMING, evaluation.state.phaseOrNull())
    }

    // --- settings -----------------------------------------------------------

    @Test
    fun aDisabledWarningIsNeverRaised() {
        val laneOff = AppSettings(laneWarningEnabled = false)
        feed(nowMs = 1_000, laneInfo = driftingLeft, distance = tooClose, settings = laneOff)
        val withoutLane = feed(
            nowMs = 2_000,
            laneInfo = driftingLeft,
            distance = tooClose,
            settings = laneOff
        )
        assertEquals(WarningType.ForwardCollision, withoutLane.state.typeOrNull())

        policy.reset()
        val collisionOff = AppSettings(collisionWarningEnabled = false)
        feed(nowMs = 1_000, laneInfo = driftingLeft, distance = tooClose, settings = collisionOff)
        val withoutCollision = feed(
            nowMs = 2_000,
            laneInfo = driftingLeft,
            distance = tooClose,
            settings = collisionOff
        )
        assertEquals(WarningType.LaneDepartureLeft, withoutCollision.state.typeOrNull())
    }

    // --- stale data ---------------------------------------------------------

    @Test
    fun aStaleDistanceSampleDoesNotHoldAnAlarm() {
        val staleAt = 1_000 + AlertPolicy.COLLISION_CONFIRM_MS + AlertPolicy.STALE_MS
        val evaluation = policy.evaluate(
            settings = AppSettings(),
            laneInfo = null,
            // feed() stamps fresh — bypass it to simulate a paused pipeline.
            distance = tooClose.copy(timestamp = tooClose.timestamp),
            nowMs = staleAt
        )

        assertEquals(AlertState.Idle, evaluation.state)
        assertNull(evaluation.signal)
    }

    @Test
    fun detectionFramesThatStopArrivingDropThePendingHazard() {
        feed(nowMs = 1_000, laneInfo = driftingLeft)
        val evaluation = feed(nowMs = 1_000 + AlertPolicy.STALE_MS + 1, laneInfo = driftingLeft)

        assertEquals(
            "a hazard seen once and then silent for STALE_MS must not carry over",
            AlertPhase.CONFIRMING,
            evaluation.state.phaseOrNull()
        )
        assertEquals(0L, (evaluation.state as AlertState.Warning).ageMs)
    }

    @Test
    fun aClearedHazardReturnsToIdle() {
        feed(nowMs = 1_000, laneInfo = driftingLeft)
        feed(nowMs = 1_000 + AlertPolicy.LANE_CONFIRM_MS, laneInfo = driftingLeft)

        val evaluation = feed(nowMs = 2_000 + AlertPolicy.LANE_CONFIRM_MS, laneInfo = null)

        assertEquals(AlertState.Idle, evaluation.state)
        assertNull(evaluation.signal)
    }

    @Test
    fun resetDropsAPendingHazard() {
        feed(nowMs = 1_000, laneInfo = driftingLeft)
        assertEquals(WarningType.LaneDepartureLeft, policy.snapshot().typeOrNull())

        policy.reset()

        assertEquals(AlertState.Idle, policy.snapshot())
    }

    @Test
    fun aLowConfidenceDriftIsIgnored() {
        val weak = driftingLeft.copy(confidence = 0.1f)

        val evaluation = feed(nowMs = 1_000, laneInfo = weak)

        assertEquals(AlertState.Idle, evaluation.state)
        assertNull(evaluation.signal)
    }

    // --- repeat / escalation ------------------------------------------------

    @Test
    fun aConfirmedAlarmOnlyRepeatsAfterTheConfiguredInterval() {
        feed(nowMs = 1_000, laneInfo = driftingLeft)
        val first = feed(nowMs = 1_000 + AlertPolicy.LANE_CONFIRM_MS, laneInfo = driftingLeft)
        assertEquals(0, first.signal?.repeatIndex)

        val tooSoon = feed(nowMs = 1_000 + AlertPolicy.LANE_CONFIRM_MS + 200, laneInfo = driftingLeft)
        assertNull("no re-alarm inside the repeat interval", tooSoon.signal)

        val later = feed(
            nowMs = 1_000 + AlertPolicy.LANE_CONFIRM_MS +
                (AppSettings().alertRepeatSeconds * 1000).toLong(),
            laneInfo = driftingLeft
        )
        assertEquals(1, later.signal?.repeatIndex)
        assertEquals(1, (later.state as AlertState.Warning).repeatCount)
    }

    @Test
    fun aSpeededUpRepeatSettingAlarmsMoreOften() {
        val settings = AppSettings(alertRepeatSeconds = 0.5f)
        feed(nowMs = 1_000, laneInfo = driftingLeft, settings = settings)
        feed(nowMs = 1_000 + AlertPolicy.LANE_CONFIRM_MS, laneInfo = driftingLeft, settings = settings)

        val evaluation = feed(
            nowMs = 1_000 + AlertPolicy.LANE_CONFIRM_MS + 500,
            laneInfo = driftingLeft,
            settings = settings
        )

        assertEquals(1, evaluation.signal?.repeatIndex)
    }

    @Test
    fun aSustainedCollisionKeepsReAlarmingAndEscalates() {
        val settings = AppSettings(alertRepeatSeconds = 5f)
        feed(nowMs = 1_000, distance = tooClose, settings = settings)
        feed(nowMs = 1_000 + AlertPolicy.COLLISION_CONFIRM_MS, distance = tooClose, settings = settings)

        var lastSignal: AlertSignal? = null
        for (step in 0..6) {
            val nowMs = 1_000 + AlertPolicy.COLLISION_CONFIRM_MS + step * 500L
            lastSignal = feed(
                nowMs = nowMs,
                distance = tooClose.copy(timestamp = nowMs),
                settings = settings
            ).signal ?: lastSignal
        }

        assertTrue(
            "a collision must keep re-alarming even with a long lane repeat interval",
            (lastSignal?.repeatIndex ?: -1) >= 1
        )
        assertTrue("a sustained collision must escalate to urgent", lastSignal?.urgent == true)
    }

    @Test
    fun aStaleLaneSampleNeverConfirmsOrHoldsAnAlarm() {
        // Paused video / stalled pipeline: the lane sample keeps the frame's
        // capture timestamp. Older than STALE_MS it must behave like "no lane".
        // NOTE: feed() stamps samples fresh, so bypass it here to simulate
        // the stale frame — that stamping is exactly what this test avoids.
        val staleAt = AlertPolicy.STALE_MS + 1_000
        val staleEval = policy.evaluate(
            settings = AppSettings(),
            laneInfo = driftingLeft.copy(timestamp = 0L),
            distance = null,
            nowMs = staleAt
        )
        assertEquals(AlertState.Idle, staleEval.state)
        assertNull(staleEval.signal)

        // Fresh samples confirm as before.
        val now = AlertPolicy.STALE_MS + 1_000
        feed(nowMs = now, laneInfo = driftingLeft.copy(timestamp = now))
        val confirmed = feed(
            nowMs = now + AlertPolicy.LANE_CONFIRM_MS,
            laneInfo = driftingLeft.copy(timestamp = now + AlertPolicy.LANE_CONFIRM_MS)
        )
        assertEquals(AlertPhase.ACTIVE, confirmed.state.phaseOrNull())
        assertEquals(WarningType.LaneDepartureLeft, confirmed.signal?.type)
    }
}
