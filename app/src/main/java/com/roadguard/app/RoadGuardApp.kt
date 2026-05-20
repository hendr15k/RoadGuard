package com.roadguard.app

import android.app.Application
import com.roadguard.app.data.repository.SettingsRepository
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class RoadGuardApp : Application() {
    @Inject
    lateinit var settingsRepository: SettingsRepository

    override fun onCreate() {
        super.onCreate()
        // Initialize SettingsRepository with app context for SharedPreferences persistence
        settingsRepository.initialize(this)
    }
}
