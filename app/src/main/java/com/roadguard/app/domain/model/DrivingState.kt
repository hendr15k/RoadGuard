package com.roadguard.app.domain.model

data class LaneInfo(
    val isDriftingLeft: Boolean = false,
    val isDriftingRight: Boolean = false,
    val confidence: Float = 0f
)

data class VehicleDistance(
    val distanceMeters: Float,
    val isTooClose: Boolean,
    val timestamp: Long = System.currentTimeMillis()
)

sealed class WarningType {
    data object LaneDepartureLeft : WarningType()
    data object LaneDepartureRight : WarningType()
    data object ForwardCollision : WarningType()
}
