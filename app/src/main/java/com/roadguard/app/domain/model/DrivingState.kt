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
    val imageHeight: Int = 0
)

data class VehicleDistance(
    val distanceMeters: Float,
    val isTooClose: Boolean,
    val timeToCollision: Float = Float.MAX_VALUE,
    val relativeSpeed: Float = 0f,
    val timestamp: Long = System.currentTimeMillis()
)

sealed class WarningType {
    data object LaneDepartureLeft : WarningType()
    data object LaneDepartureRight : WarningType()
    data object ForwardCollision : WarningType()
}
