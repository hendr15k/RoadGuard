package com.roadguard.app.data.repository

import android.content.Context
import android.content.SharedPreferences
import com.roadguard.app.domain.model.AppSettings
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
        minFollowingDistanceMeters = prefs.getFloat(KEY_MIN_FOLLOWING_DISTANCE, 20f),
        laneDepartureSensitivity = prefs.getFloat(KEY_LANE_SENSITIVITY, 0.5f),
        alertRepeatSeconds = prefs.getFloat(KEY_ALERT_REPEAT, 3f)
    )

    // Deprecated entry point — kept for backwards compat with
    // RoadGuardApp.onCreate() which calls it. No-op now because
    // prefs is initialized in the constructor.
    @Suppress("unused")
    fun initialize(@Suppress("UNUSED_PARAMETER") context: Context) {
        // no-op: see constructor for the real initialization
    }

    fun updateSettings(settings: AppSettings) {
        _settings.value = settings
        // apply() ist asynchron (Disk-IO im Hintergrund). Wenn der User
        // direkt danach die Activity schließt und der Prozess gekillt
        // wird, kann der Write verloren gehen. In Production würde man
        // DataStore statt SharedPreferences verwenden, das asynchron
        // committed und sichere Transaktionen garantiert.
        prefs.edit()
            .putBoolean(KEY_LANE_WARNING, settings.laneWarningEnabled)
            .putBoolean(KEY_COLLISION_WARNING, settings.collisionWarningEnabled)
            .putFloat(KEY_MIN_FOLLOWING_DISTANCE, settings.minFollowingDistanceMeters)
            .putFloat(KEY_LANE_SENSITIVITY, settings.laneDepartureSensitivity)
            .putFloat(KEY_ALERT_REPEAT, settings.alertRepeatSeconds)
            .apply()
    }
}
