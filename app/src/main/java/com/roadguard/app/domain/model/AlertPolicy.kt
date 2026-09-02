package com.roadguard.app.domain.model

/**
 * Gate between raw detections and the driver-facing alarm.
 *
 * Raw [LaneInfo]/[VehicleDistance] samples arrive at ~5 Hz from two independent
 * pipelines (live camera or a video file that can be paused). Feeding them
 * straight into vibration alarms meant: one spurious frame vibrated, a paused
 * video kept the last alarm ringing forever, and a lane drift could mask an
 * imminent collision. This class fixes all three:
 *
 *  - **Confirmation** — a hazard must persist for a window before alarming
 *    (collisions confirm faster than lane drifts).
 *  - **Staleness** — detections that stop arriving (or distance samples far
 *    older than the current frame) drop the alarm instead of holding it.
 *  - **Priority** — ForwardCollision always outranks a lane departure, and
 *    changing hazard type restarts confirmation.
 *  - **Repeats** — an ongoing hazard re-alarms on a bounded cadence; a
 *    sustained collision re-alarms at the minimum cadence and escalates to
 *    urgent after repeated warnings regardless of the user's repeat setting.
 *
 * Pure JVM: no Android imports, driven by explicit timestamps so it is fully
 * unit-testable.
 */
class AlertPolicy {

    companion object {
        /** A lane departure must be visible this long before the first alarm. */
        const val LANE_CONFIRM_MS = 1_000L

        /** A collision threat alarms twice as fast — it is the deadlier hazard. */
        const val COLLISION_CONFIRM_MS = 500L

        /** A distance sample older than this (relative to the current frame) is stale. */
        const val STALE_MS = 2_000L

        /** Observations further apart than this are treated as unrelated hazards. */
        const val OBSERVATION_GAP_MS = 5_000L

        /** A hazard seen this recently may survive isolated single-frame dropouts. */
        const val ABSENCE_GRACE_MS = 500L

        /** Collision re-alarm cadence, independent of the (lane-oriented) user setting. */
        const val MIN_REPEAT_INTERVAL_MS = 1_000L

        /** After this many repeats a sustained collision escalates to urgent. */
        const val ESCALATION_REPEATS = 3

        const val MIN_LANE_CONFIDENCE = 0.4f
    }

    private var current: WarningType? = null
    private var hazardStartMs: Long = 0
    private var confirmedAtMs: Long = 0
    private var lastRepeatMs: Long = 0
    private var repeatCount: Int = 0
    private var lastSeenMs: Long = 0

    fun snapshot(): AlertState = buildState(ageMs = if (lastSeenMs > 0) lastSeenMs - hazardStartMs else 0)

    fun reset() {
        current = null
        hazardStartMs = 0
        confirmedAtMs = 0
        lastRepeatMs = 0
        repeatCount = 0
        lastSeenMs = 0
    }

    fun evaluate(
        settings: AppSettings,
        laneInfo: LaneInfo?,
        distance: VehicleDistance?,
        nowMs: Long
    ): AlertEvaluation {
        val laneHazard = laneHazard(settings, laneInfo)
        val collisionHazard = collisionHazard(settings, distance, nowMs)
        val hazard = collisionHazard ?: laneHazard

        if (hazard == null) {
            // Brief dropouts (one missed frame) must not reset a pending hazard,
            // but silence that outlives the grace period clears it.
            val silenceMs = nowMs - lastSeenMs
            if (current == null || silenceMs > ABSENCE_GRACE_MS) {
                reset()
                return AlertEvaluation(AlertState.Idle, null)
            }
            return AlertEvaluation(buildState(ageMs = nowMs - hazardStartMs), null)
        }

        if (current != hazard || nowMs - lastSeenMs > (if (confirmedAtMs > 0L) OBSERVATION_GAP_MS else STALE_MS)) {
            // New hazard (or a long detection gap): confirmation starts over.
            // A pending hazard expires quicker (STALE_MS) than an already
            // confirmed alarm (OBSERVATION_GAP_MS) — otherwise a 2 s
            // dropout would either kill every repeat or never clear a
            // spurious pending confirmation.
            current = hazard
            hazardStartMs = nowMs
            confirmedAtMs = 0
            lastRepeatMs = 0
            repeatCount = 0
        }
        lastSeenMs = nowMs

        val ageMs = nowMs - hazardStartMs
        val confirmMs = if (hazard is WarningType.ForwardCollision) {
            COLLISION_CONFIRM_MS
        } else {
            LANE_CONFIRM_MS
        }

        if (confirmedAtMs == 0L && ageMs >= confirmMs) {
            confirmedAtMs = nowMs
            lastRepeatMs = nowMs
            return AlertEvaluation(buildState(ageMs), AlertSignal(hazard, 0, urgent = false))
        }

        if (confirmedAtMs > 0L) {
            val interval = if (hazard is WarningType.ForwardCollision) {
                // A sustained collision ignores the (lane-oriented) repeat setting.
                MIN_REPEAT_INTERVAL_MS
            } else {
                // Lane repeats honor the user setting directly (they may choose < 1s).
                (settings.alertRepeatSeconds * 1000).toLong().coerceAtLeast(200L)
            }
            if (nowMs - lastRepeatMs >= interval) {
                repeatCount++
                lastRepeatMs = nowMs
                val urgent = hazard is WarningType.ForwardCollision &&
                    repeatCount >= ESCALATION_REPEATS
                return AlertEvaluation(buildState(ageMs), AlertSignal(hazard, repeatCount, urgent))
            }
        }

        return AlertEvaluation(buildState(ageMs), null)
    }

    private fun collisionHazard(
        settings: AppSettings,
        distance: VehicleDistance?,
        nowMs: Long
    ): WarningType? {
        if (!settings.collisionWarningEnabled) return null
        if (distance == null || !distance.isTooClose) return null
        // A distance sample computed from an old frame must not hold an alarm
        // (e.g. paused video, stalled pipeline). Fresh samples carry the frame's
        // capture timestamp; a sample that never updates is treated as stale.
        if (nowMs - distance.timestamp > STALE_MS) return null
        return WarningType.ForwardCollision
    }

    private fun laneHazard(settings: AppSettings, laneInfo: LaneInfo?): WarningType? {
        if (!settings.laneWarningEnabled) return null
        if (laneInfo == null || laneInfo.confidence < MIN_LANE_CONFIDENCE) return null
        return when {
            laneInfo.isDriftingLeft -> WarningType.LaneDepartureLeft
            laneInfo.isDriftingRight -> WarningType.LaneDepartureRight
            else -> null
        }
    }

    private fun buildState(ageMs: Long): AlertState {
        val type = current ?: return AlertState.Idle
        return AlertState.Warning(
            type = type,
            phase = if (confirmedAtMs > 0L) AlertPhase.ACTIVE else AlertPhase.CONFIRMING,
            ageMs = ageMs,
            repeatCount = repeatCount
        )
    }
}

enum class AlertPhase { CONFIRMING, ACTIVE }

sealed class AlertState {
    data object Idle : AlertState()
    data class Warning(
        val type: WarningType,
        val phase: AlertPhase,
        val ageMs: Long,
        val repeatCount: Int
    ) : AlertState()
}

/** A driver-facing alarm event: the first alarm has repeatIndex 0, repeats count up. */
data class AlertSignal(val type: WarningType, val repeatIndex: Int, val urgent: Boolean)

data class AlertEvaluation(val state: AlertState, val signal: AlertSignal?)
