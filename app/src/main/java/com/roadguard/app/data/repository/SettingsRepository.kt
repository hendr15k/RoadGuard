package com.roadguard.app.data.repository

import android.content.Context
import android.content.SharedPreferences
import com.roadguard.app.domain.model.AppSettings
import com.roadguard.app.domain.model.sanitized
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext context: Context
) {
    companion object {
        private const val PREFS_NAME = "roadguard_settings"
        private const val KEY_LANE_WARNING = "lane_warning_enabled"
        private const val KEY_COLLISION_WARNING = "collision_warning_enabled"
        private const val KEY_AUDIO_ALERTS = "audio_alerts_enabled"
        private const val KEY_VIBRATION_ALERTS = "vibration_alerts_enabled"
        private const val KEY_MIN_FOLLOWING_DISTANCE = "min_following_distance"
        private const val KEY_LANE_SENSITIVITY = "lane_departure_sensitivity"
        private const val KEY_ALERT_REPEAT = "alert_repeat_seconds"
    }

    // SharedPreferences ist bereits thread-safe, aber unser _settings State
    // wird im Hilt-Initialisierungs-Window gesetzt. @Volatile verhindert
    // visibility issues bei dem Fall, dass Hilt die SettingsUseCases
    // injected BEVOR die Application.onCreate() settingsRepository.initialize()
    // aufgerufen hat.
    @Volatile
    private var prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(loadSettings())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    private fun loadSettings(): AppSettings = AppSettings(
        laneWarningEnabled = prefs.getBoolean(KEY_LANE_WARNING, true),
        collisionWarningEnabled = prefs.getBoolean(KEY_COLLISION_WARNING, true),
        audioAlertsEnabled = prefs.getBoolean(KEY_AUDIO_ALERTS, true),
        vibrationAlertsEnabled = prefs.getBoolean(KEY_VIBRATION_ALERTS, true),
        minFollowingDistanceMeters = prefs.getFloat(KEY_MIN_FOLLOWING_DISTANCE, 20f),
        laneDepartureSensitivity = prefs.getFloat(KEY_LANE_SENSITIVITY, 0.5f),
        alertRepeatSeconds = prefs.getFloat(KEY_ALERT_REPEAT, 3f)
    ).sanitized()

    // Deprecated entry point — kept for backwards compat with
    // RoadGuardApp.onCreate() which calls it. No-op now because
    // prefs is initialized in the constructor.
    @Suppress("unused")
    fun initialize(@Suppress("UNUSED_PARAMETER") context: Context) {
        // no-op: see constructor for the real initialization
    }

    fun updateSettings(settings: AppSettings) {
        val safe = settings.sanitized()
        _settings.value = safe
        // apply() ist asynchron (Disk-IO im Hintergrund). Wenn der User
        // direkt danach die Activity schließt und der Prozess gekillt
        // wird, kann der Write verloren gehen. In Production würde man
        // DataStore statt SharedPreferences verwenden, das asynchron
        // committed und sichere Transaktionen garantiert.
        prefs.edit()
            .putBoolean(KEY_LANE_WARNING, safe.laneWarningEnabled)
            .putBoolean(KEY_COLLISION_WARNING, safe.collisionWarningEnabled)
            .putBoolean(KEY_AUDIO_ALERTS, safe.audioAlertsEnabled)
            .putBoolean(KEY_VIBRATION_ALERTS, safe.vibrationAlertsEnabled)
            .putFloat(KEY_MIN_FOLLOWING_DISTANCE, safe.minFollowingDistanceMeters)
            .putFloat(KEY_LANE_SENSITIVITY, safe.laneDepartureSensitivity)
            .putFloat(KEY_ALERT_REPEAT, safe.alertRepeatSeconds)
            .apply()
    }
}
