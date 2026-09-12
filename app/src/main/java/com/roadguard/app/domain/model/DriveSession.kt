package com.roadguard.app.domain.model

/** One driver-facing alarm, trimmed for the in-memory history. */
data class AlertLogEntry(
    val type: WarningType,
    val atMs: Long,
    val repeatIndex: Int,
    val urgent: Boolean
) {
    /** Only the first alarm of a hazard is a new incident; repeats are the same one. */
    val isFirstOfHazard: Boolean get() = repeatIndex == 0
}

/** Immutable snapshot of the current drive, safe to hand to the UI. */
data class DriveSessionStats(
    val startedAtMs: Long = 0L,
    val nowMs: Long = 0L,
    val laneDepartureCount: Int = 0,
    val collisionCount: Int = 0,
    val urgentCollisionCount: Int = 0,
    val warningTimeMs: Long = 0L
) {
    val durationMs: Long get() = (nowMs - startedAtMs).coerceAtLeast(0L)

    val totalIncidents: Int get() = laneDepartureCount + collisionCount

    /** Share of the drive spent under an active warning, 0f..1f. */
    val warningTimeFraction: Float
        get() = if (durationMs <= 0L) 0f
        else (warningTimeMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)

    /**
     * 100 for a clean drive, falling with the incident *rate* (not the raw
     * count, so a long motorway stint is not punished for one slip) and with
     * the severity of collisions.
     *
     * Formula: 100 − 8·(incidents per minute) − 10·collisions − 10·urgent.
     * Clamped to 0..100.
     */
    val safetyScore: Int
        get() {
            if (durationMs <= 0L) return 100
            val minutes = durationMs / 60_000f
            val ratePenalty = ((totalIncidents / minutes) * 8f).toInt()
            val severityPenalty = collisionCount * 10 + urgentCollisionCount * 10
            return (100 - ratePenalty - severityPenalty).coerceIn(0, 100)
        }
}

/**
 * Per-drive recorder: incident tallies, time-under-warning and a bounded
 * history of the alarms the driver actually saw.
 *
 * Pure JVM and driven by explicit timestamps so the HUD can render a stable
 * snapshot and the behaviour is unit-testable — the same split as
 * [AlertPolicy]. Only the first alarm of a hazard increments a tally (a
 * sustained collision re-alarms every second); the log keeps every entry so
 * the user can see how insistent a hazard was.
 */
class DriveSession(private val maxEntries: Int = DEFAULT_MAX_ENTRIES) {

    companion object {
        const val DEFAULT_MAX_ENTRIES = 50
    }

    private var startedAtMs = 0L
    private var warningSinceMs: Long? = null
    private var accumulatedWarningMs = 0L
    private var laneDepartures = 0
    private var collisions = 0
    private var urgentCollisions = 0

    /** Newest entries last while recording; [log] reverses for display. */
    private val entries = ArrayDeque<AlertLogEntry>()

    fun start(nowMs: Long) {
        if (startedAtMs == 0L) startedAtMs = nowMs
    }

    /** Records a fired alarm. Returns true when it was a new hazard incident. */
    fun recordSignal(signal: AlertSignal, nowMs: Long): Boolean {
        val isLane = signal.type is WarningType.LaneDepartureLeft ||
            signal.type is WarningType.LaneDepartureRight
        val isCollision = signal.type is WarningType.ForwardCollision

        // The repeatIndex is per hazard, so 0 means "this hazard just started".
        val isNewIncident = signal.repeatIndex == 0
        if (isNewIncident) {
            when {
                isLane -> laneDepartures++
                isCollision -> collisions++
            }
        }
        // The first urgent repetition is the escalation of a collision already
        // counted, so it is a severity tally, not a new incident.
        if (signal.urgent && signal.repeatIndex == AlertPolicy.ESCALATION_REPEATS) {
            urgentCollisions++
        }

        entries.addLast(AlertLogEntry(signal.type, nowMs, signal.repeatIndex, signal.urgent))
        while (entries.size > maxEntries) entries.removeFirst()
        return isNewIncident
    }

    /**
     * Accumulates time under an active warning. Called on every evaluation
     * tick (~5 Hz), not only when an alarm fires, so the fraction reflects the
     * whole drive rather than the alert cadence.
     */
    fun recordState(state: AlertState, nowMs: Long) {
        val isWarning = state is AlertState.Warning && state.phase == AlertPhase.ACTIVE
        val since = warningSinceMs
        if (isWarning) {
            if (since == null) warningSinceMs = nowMs
        } else if (since != null) {
            accumulatedWarningMs += (nowMs - since).coerceAtLeast(0L)
            warningSinceMs = null
        }
    }

    fun stats(nowMs: Long): DriveSessionStats {
        val openWarning = warningSinceMs?.let { (nowMs - it).coerceAtLeast(0L) } ?: 0L
        return DriveSessionStats(
            startedAtMs = startedAtMs,
            nowMs = nowMs,
            laneDepartureCount = laneDepartures,
            collisionCount = collisions,
            urgentCollisionCount = urgentCollisions,
            warningTimeMs = accumulatedWarningMs + openWarning
        )
    }

    /** Most recent entry first, for a scrolling UI list. */
    fun log(): List<AlertLogEntry> = entries.reversed()

    fun reset(nowMs: Long) {
        startedAtMs = nowMs
        warningSinceMs = null
        accumulatedWarningMs = 0L
        laneDepartures = 0
        collisions = 0
        urgentCollisions = 0
        entries.clear()
    }
}
