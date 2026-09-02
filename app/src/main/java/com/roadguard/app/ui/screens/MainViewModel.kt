package com.roadguard.app.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.roadguard.app.domain.model.AlertPolicy
import com.roadguard.app.domain.model.AlertSignal
import com.roadguard.app.domain.model.AlertState
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

    private val policy = AlertPolicy()
    private val _alertState = MutableStateFlow<AlertState>(AlertState.Idle)
    val alertState: StateFlow<AlertState> = _alertState.asStateFlow()

    /** One-shot vibration/sound event emitted exactly when the gate fires. */
    private val _alertSignal = MutableStateFlow<AlertSignal?>(null)
    val alertSignal: StateFlow<AlertSignal?> = _alertSignal.asStateFlow()

    /** Kept for the Composables' StatusBar/WarningOverlay — derived from alertState. */
    private val _activeWarning = MutableStateFlow<WarningType?>(null)
    val activeWarning: StateFlow<WarningType?> = _activeWarning.asStateFlow()

    fun updateLaneInfo(laneInfo: LaneInfo) {
        viewModelScope.launch {
            _laneInfo.value = laneInfo
            reevaluate(nowMs = System.currentTimeMillis(), currentSettings = settings.value)
        }
    }

    fun updateVehicleDistance(distance: VehicleDistance) {
        viewModelScope.launch {
            _vehicleDistance.value = distance
            reevaluate(nowMs = System.currentTimeMillis(), currentSettings = settings.value)
        }
    }

    fun updateSettings(settings: AppSettings) {
        viewModelScope.launch {
            updateSettingsUseCase(settings)
            // Re-evaluate against the settings that were just persisted. Reading
            // the flow back is not guaranteed to have emitted yet, so pass them in.
            reevaluate(nowMs = System.currentTimeMillis(), currentSettings = settings)
        }
    }

    fun clearDetectionState() {
        viewModelScope.launch {
            _laneInfo.value = null
            _vehicleDistance.value = null
            policy.reset()
            _alertState.value = AlertState.Idle
            _activeWarning.value = null
            // Do not clear _alertSignal here — it is consumed by the UI.
        }
    }

    private fun reevaluate(currentSettings: AppSettings, nowMs: Long) {
        val evaluation = policy.evaluate(
            settings = currentSettings,
            laneInfo = _laneInfo.value,
            distance = _vehicleDistance.value,
            nowMs = nowMs
        )
        _alertState.value = evaluation.state
        _activeWarning.value = (evaluation.state as? AlertState.Warning)?.type
        if (evaluation.signal != null) _alertSignal.value = evaluation.signal
    }

    fun consumeAlertSignal() {
        _alertSignal.value = null
    }
}
