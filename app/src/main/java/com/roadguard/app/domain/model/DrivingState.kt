package com.roadguard.app.domain.model

data class LaneCurve(
    val a: Float = 0f,
    val b: Float = 0f,
    val c: Float = 0f,
    val yStart: Float = 0f,
    val yEnd: Float = 0f,
    val valid: Boolean = false
)

data class LaneInfo(
    val isDriftingLeft: Boolean = false,
    val isDriftingRight: Boolean = false,
    val confidence: Float = 0f,
    val centerOffset: Float = 0f,
    val laneWidth: Float = 0f,
    val leftLaneVisible: Boolean = false,
    val rightLaneVisible: Boolean = false,
    val leftCurve: LaneCurve = LaneCurve(),
    val rightCurve: LaneCurve = LaneCurve(),
    val imageWidth: Int = 0,
    val imageHeight: Int = 0,
    /** Capture time of the frame this sample was computed from. Lets the
     * alert gate expire lane hazards like collision samples — otherwise a
     * paused video or stalled pipeline holds the last lane alarm forever. */
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * True while this sample is young enough to be shown. The HUD renders raw
 * samples directly, so without this check a paused video kept the last
 * distance/TTC/lane on screen indefinitely while [AlertPolicy] had already
 * dropped the matching alarm — the display promised a hazard the safety logic
 * had cleared. Shares [AlertPolicy.STALE_MS] so display and alarm cannot drift
 * apart; the boundary is inclusive, matching the gate's `> STALE_MS` test.
 */
fun LaneInfo.isFresh(nowMs: Long = System.currentTimeMillis()): Boolean =
    nowMs - timestamp <= AlertPolicy.STALE_MS

data class VehicleDistance(
    val distanceMeters: Float,
    val isTooClose: Boolean,
    val timeToCollision: Float = Float.MAX_VALUE,
    val relativeSpeed: Float = 0f,
    val timestamp: Long = System.currentTimeMillis()
)

/** See [LaneInfo.isFresh] — same window, same boundary. */
fun VehicleDistance.isFresh(nowMs: Long = System.currentTimeMillis()): Boolean =
    nowMs - timestamp <= AlertPolicy.STALE_MS

sealed class WarningType {
    data object LaneDepartureLeft : WarningType()
    data object LaneDepartureRight : WarningType()
    data object ForwardCollision : WarningType()
}
