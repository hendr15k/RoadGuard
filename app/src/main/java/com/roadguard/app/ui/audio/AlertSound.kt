package com.roadguard.app.ui.audio

import com.roadguard.app.domain.model.AlertSignal
import com.roadguard.app.domain.model.WarningType

/**
 * Which alarm tone the driver should hear.
 *
 * Deliberately free of Android types (the [AlertTonePlayer] maps each case to a
 * concrete `ToneGenerator` tone) so the urgency rules stay unit-testable on the
 * JVM — the same split [com.roadguard.app.domain.model.AlertPolicy] uses.
 */
enum class AlertSound {
    /** Gentle, short beep for a lane departure. */
    LANE,

    /** Attention tone for a confirmed forward collision. */
    COLLISION,

    /** Harsher, longer tone once a sustained collision escalates. */
    COLLISION_URGENT
}

fun soundFor(signal: AlertSignal): AlertSound = when (signal.type) {
    is WarningType.LaneDepartureLeft, is WarningType.LaneDepartureRight -> AlertSound.LANE
    is WarningType.ForwardCollision ->
        if (signal.urgent) AlertSound.COLLISION_URGENT else AlertSound.COLLISION
}
