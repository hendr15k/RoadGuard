package com.roadguard.app.ui.screens

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.roadguard.app.data.update.UpdateChecker
import com.roadguard.app.data.update.UpdateState
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class UpdateViewModel(application: Application) : AndroidViewModel(application) {

    private val updateChecker = UpdateChecker(application)

    val updateState: StateFlow<UpdateState> = updateChecker.updateState

    init {
        checkForUpdates()
    }

    fun checkForUpdates() {
        viewModelScope.launch {
            updateChecker.checkForUpdates()
        }
    }

    fun dismissUpdate() {
        updateChecker.dismissUpdate()
    }

    fun resetDismissed() {
        updateChecker.resetDismissed()
        checkForUpdates()
    }
}
