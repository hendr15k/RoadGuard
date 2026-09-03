package com.roadguard.app.ui.screens

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.roadguard.app.data.update.UpdateChecker
import com.roadguard.app.data.update.UpdateState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class UpdateViewModel @Inject constructor(
    application: Application,
    private val updateChecker: UpdateChecker
) : AndroidViewModel(application) {

    val updateState: StateFlow<UpdateState> = updateChecker.updateState

    init {
        // Check once per ViewModel lifetime (survives rotation). The old
        // LaunchedEffect(Unit) in MainScreen refired on every rotation and
        // spammed the GitHub API into its rate limit.
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
