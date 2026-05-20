package com.roadguard.app.data.repository

import android.content.Context
import android.content.SharedPreferences
import com.roadguard.app.domain.model.AppSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SettingsRepository @Inject constructor() {
    companion object {
        private const val PREFS_NAME = "roadguard_settings"
        private const val KEY_LANE_WARNING = "lane_warning_enabled"
        private const val KEY_COLLISION_WARNING = "collision_warning_enabled"
        private const val KEY_MIN_FOLLOWING_DISTANCE = "min_following_distance"
        private const val KEY_LANE_SENSITIVITY = "lane_departure_sensitivity"
    }

    private var prefs: SharedPreferences? = null

    private val _settings = MutableStateFlow(AppSettings())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    fun initialize(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        loadSettings()
    }

    private fun loadSettings() {
        prefs?.let { p ->
            _settings.value = AppSettings(
                laneWarningEnabled = p.getBoolean(KEY_LANE_WARNING, true),
                collisionWarningEnabled = p.getBoolean(KEY_COLLISION_WARNING, true),
                minFollowingDistanceMeters = p.getFloat(KEY_MIN_FOLLOWING_DISTANCE, 20f),
                laneDepartureSensitivity = p.getFloat(KEY_LANE_SENSITIVITY, 0.5f)
            )
        }
    }

    fun updateSettings(settings: AppSettings) {
        _settings.value = settings
        prefs?.let { p ->
            p.edit()
                .putBoolean(KEY_LANE_WARNING, settings.laneWarningEnabled)
                .putBoolean(KEY_COLLISION_WARNING, settings.collisionWarningEnabled)
                .putFloat(KEY_MIN_FOLLOWING_DISTANCE, settings.minFollowingDistanceMeters)
                .putFloat(KEY_LANE_SENSITIVITY, settings.laneDepartureSensitivity)
                .apply()
        }
    }
}
