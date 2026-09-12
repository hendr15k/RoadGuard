package com.roadguard.app.ui.audio

import com.roadguard.app.domain.model.AlertSignal
import com.roadguard.app.domain.model.WarningType
import org.junit.Assert.assertEquals
import org.junit.Test

class AlertSoundTest {

    @Test
    fun laneDeparturesMapToLaneTone() {
        assertEquals(
            AlertSound.LANE,
            soundFor(AlertSignal(WarningType.LaneDepartureLeft, 0, urgent = false))
        )
        assertEquals(
            AlertSound.LANE,
            soundFor(AlertSignal(WarningType.LaneDepartureRight, 2, urgent = false))
        )
    }

    @Test
    fun normalCollisionMapsToCollisionTone() {
        assertEquals(
            AlertSound.COLLISION,
            soundFor(AlertSignal(WarningType.ForwardCollision, 0, urgent = false))
        )
    }

    @Test
    fun escalatedCollisionMapsToUrgentTone() {
        assertEquals(
            AlertSound.COLLISION_URGENT,
            soundFor(AlertSignal(WarningType.ForwardCollision, 3, urgent = true))
        )
    }
}
