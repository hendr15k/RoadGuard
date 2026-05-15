package com.roadguard.app.domain.usecase

import com.roadguard.app.domain.model.AppSettings
import com.roadguard.app.data.repository.SettingsRepository
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

class GetSettingsUseCase @Inject constructor(
    private val repository: SettingsRepository
) {
    operator fun invoke(): StateFlow<AppSettings> = repository.settings
}

class UpdateSettingsUseCase @Inject constructor(
    private val repository: SettingsRepository
) {
    operator fun invoke(settings: AppSettings) = repository.updateSettings(settings)
}
