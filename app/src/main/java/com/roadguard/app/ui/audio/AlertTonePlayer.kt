package com.roadguard.app.ui.audio

import android.media.AudioManager
import android.media.ToneGenerator

/**
 * Plays the short, urgency-scaled alarm tones promised by the README's
 * "Audio & Vibration Alerts".
 *
 * A [ToneGenerator] owns a persistent native AudioTrack, so exactly one instance
 * is created and released explicitly by the composition that uses it — never per
 * alarm. Alerts repeat for as long as a hazard persists (a sustained collision
 * re-alarms every second), so allocating a track per beep would leak audio
 * resources and add audible latency to the first alarm.
 *
 * Tones are sent to [AudioManager.STREAM_ALARM]: a safety warning must be heard
 * even while the user is playing media or has the ringer muted.
 */
class AlertTonePlayer {

    private companion object {
        /** Full alarm-stream volume; the system/user still scales the stream. */
        const val VOLUME = 100

        /** Frequencies picked to be distinguishable from typical navigation beeps. */
        const val COLLISION_DURATION_MS = 250
        const val URGENT_DURATION_MS = 500
    }

    @Volatile
    private var generator: ToneGenerator? = null

    /** No-op when the audio device is unavailable (see [ensureGenerator]). */
    @Synchronized
    fun play(sound: AlertSound) {
        val toneGenerator = ensureGenerator() ?: return
        val tone = when (sound) {
            AlertSound.LANE -> ToneGenerator.TONE_PROP_BEEP
            AlertSound.COLLISION -> ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD
            AlertSound.COLLISION_URGENT -> ToneGenerator.TONE_CDMA_ABBR_ALERT
        }
        val durationMs = when (sound) {
            AlertSound.LANE -> 150
            AlertSound.COLLISION -> COLLISION_DURATION_MS
            AlertSound.COLLISION_URGENT -> URGENT_DURATION_MS
        }
        try {
            toneGenerator.startTone(tone, durationMs)
        } catch (e: RuntimeException) {
            // A dying audio session must never take down the analysis pipeline.
            release()
        }
    }

    @Synchronized
    fun release() {
        generator?.release()
        generator = null
    }

    private fun ensureGenerator(): ToneGenerator? {
        generator?.let { return it }
        return try {
            ToneGenerator(AudioManager.STREAM_ALARM, VOLUME).also { generator = it }
        } catch (e: RuntimeException) {
            // Emulators and devices without an audio HAL throw here. Vibration
            // still fires, so a missing tone must not crash the alarm path.
            null
        }
    }
}
