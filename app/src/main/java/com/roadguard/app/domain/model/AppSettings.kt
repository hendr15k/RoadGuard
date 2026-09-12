package com.roadguard.app.domain.model

data class AppSettings(
    val laneWarningEnabled: Boolean = true,
    val collisionWarningEnabled: Boolean = true,
    val audioAlertsEnabled: Boolean = true,
    val vibrationAlertsEnabled: Boolean = true,
    val minFollowingDistanceMeters: Float = 20f,
    val laneDepartureSensitivity: Float = 0.5f,
    /** Minimum seconds between repeated alarms for the same ongoing hazard. */
    val alertRepeatSeconds: Float = 3f
) {
    companion object {
        const val MIN_FOLLOWING_DISTANCE_M = 10f
        const val MAX_FOLLOWING_DISTANCE_M = 50f
        const val MIN_SENSITIVITY = 0f
        const val MAX_SENSITIVITY = 1f
        const val MIN_REPEAT_SECONDS = 0.5f
        const val MAX_REPEAT_SECONDS = 5f
    }
}

/**
 * Clamps every ranged value into the window the Settings UI offers. Stored
 * prefs predate some of these bounds (and nothing stopped a hand-edited prefs
 * XML), so an unclamped 500 m following distance used to silently extend the
 * collision range via AlertPolicy.collisionRangeFor.
 */
fun AppSettings.sanitized(): AppSettings = copy(
    minFollowingDistanceMeters = minFollowingDistanceMeters.coerceIn(
        AppSettings.MIN_FOLLOWING_DISTANCE_M,
        AppSettings.MAX_FOLLOWING_DISTANCE_M
    ),
    laneDepartureSensitivity = laneDepartureSensitivity.coerceIn(
        AppSettings.MIN_SENSITIVITY,
        AppSettings.MAX_SENSITIVITY
    ),
    alertRepeatSeconds = alertRepeatSeconds.coerceIn(
        AppSettings.MIN_REPEAT_SECONDS,
        AppSettings.MAX_REPEAT_SECONDS
    )
)
