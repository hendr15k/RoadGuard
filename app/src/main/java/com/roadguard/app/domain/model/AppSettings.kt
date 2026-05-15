package com.roadguard.app.domain.model

data class AppSettings(
    val laneWarningEnabled: Boolean = true,
    val collisionWarningEnabled: Boolean = true,
    val minFollowingDistanceMeters: Float = 20f,
    val laneDepartureSensitivity: Float = 0.5f
)
