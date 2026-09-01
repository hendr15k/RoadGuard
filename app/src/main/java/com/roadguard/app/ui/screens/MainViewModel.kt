package com.roadguard.app.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.roadguard.app.domain.model.AppSettings
import com.roadguard.app.domain.model.LaneInfo
import com.roadguard.app.domain.model.VehicleDistance
import com.roadguard.app.domain.model.WarningType
import com.roadguard.app.domain.usecase.GetSettingsUseCase
import com.roadguard.app.domain.usecase.UpdateSettingsUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    private val getSettingsUseCase: GetSettingsUseCase,
    private val updateSettingsUseCase: UpdateSettingsUseCase
) : ViewModel() {

    val settings: StateFlow<AppSettings> = getSettingsUseCase()

    private val _laneInfo = MutableStateFlow<LaneInfo?>(null)
    val laneInfo: StateFlow<LaneInfo?> = _laneInfo.asStateFlow()

    private val _vehicleDistance = MutableStateFlow<VehicleDistance?>(null)
    val vehicleDistance: StateFlow<VehicleDistance?> = _vehicleDistance.asStateFlow()

    private val _activeWarning = MutableStateFlow<WarningType?>(null)
    val activeWarning: StateFlow<WarningType?> = _activeWarning.asStateFlow()

    fun updateLaneInfo(laneInfo: LaneInfo) {
        viewModelScope.launch {
            _laneInfo.value = laneInfo
            checkForWarnings()
        }
    }

    fun updateVehicleDistance(distance: VehicleDistance) {
        viewModelScope.launch {
            _vehicleDistance.value = distance
            checkForWarnings()
        }
    }

    fun updateSettings(settings: AppSettings) {
        viewModelScope.launch {
            updateSettingsUseCase(settings)
            // Re-evaluate against the settings that were just persisted. Reading
            // the flow back is not guaranteed to be updated yet, so pass them in.
            checkForWarnings(settings)
        }
    }

    fun clearDetectionState() {
        viewModelScope.launch {
            _laneInfo.value = null
            _vehicleDistance.value = null
            _activeWarning.value = null
        }
    }

    private fun checkForWarnings(currentSettings: AppSettings = settings.value) {
        var warning: WarningType? = null

        _laneInfo.value?.let { lane ->
            if (currentSettings.laneWarningEnabled) {
                when {
                    lane.isDriftingLeft -> warning = WarningType.LaneDepartureLeft
                    lane.isDriftingRight -> warning = WarningType.LaneDepartureRight
                }
            }
        }

        _vehicleDistance.value?.let { dist ->
            if (currentSettings.collisionWarningEnabled && dist.isTooClose) {
                warning = WarningType.ForwardCollision
            }
        }

        _activeWarning.value = warning
    }
}
