package com.roadguard.app.data.update

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class UpdateChecker(context: Context) {

    companion object {
        private const val TAG = "UpdateChecker"
        private const val PREFS_NAME = "update_prefs"
        private const val KEY_LAST_SEEN_VERSION = "last_seen_version"
        private const val KEY_UPDATE_DISMISSED = "update_dismissed"
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val apiService = GitHubApiService(context)

    private val _updateState = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val updateState: StateFlow<UpdateState> = _updateState.asStateFlow()

    private val _currentVersion = MutableStateFlow(getCurrentAppVersion(context))
    val currentVersion: StateFlow<String> = _currentVersion.asStateFlow()

    private fun getCurrentAppVersion(context: Context): String {
        return try {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            packageInfo.versionName ?: "1.0"
        } catch (e: Exception) {
            "1.0"
        }
    }

    suspend fun checkForUpdates() {
        _updateState.value = UpdateState.Checking

        apiService.getLatestRelease().fold(
            onSuccess = { release ->
                Log.d(TAG, "Latest release: ${release.tagName}")

                val currentVersionName = _currentVersion.value
                val isNewer = isNewerVersion(release.tagName, currentVersionName)

                Log.d(TAG, "Comparing versions: release=${release.tagName} vs current=$currentVersionName, isNewer=$isNewer")

                val lastSeenVersion = prefs.getString(KEY_LAST_SEEN_VERSION, null)
                val wasPreviouslySeen = lastSeenVersion == release.tagName
                val isDismissed = prefs.getBoolean("${KEY_UPDATE_DISMISSED}_${release.tagName}", false)

                if (isNewer && !wasPreviouslySeen) {
                    Log.d(TAG, "New update available: ${release.tagName}")
                    prefs.edit().putString(KEY_LAST_SEEN_VERSION, release.tagName).apply()

                    _updateState.value = UpdateState.UpdateAvailable(
                        UpdateInfo(
                            tagName = release.tagName,
                            versionName = release.name.ifEmpty { release.tagName },
                            releaseNotes = release.body ?: "New version available",
                            downloadUrl = release.htmlUrl,
                            isNewer = true
                        )
                    )
                } else if (isNewer && wasPreviouslySeen && !isDismissed) {
                    Log.d(TAG, "Previously seen update still available: ${release.tagName}")
                    _updateState.value = UpdateState.UpdateAvailable(
                        UpdateInfo(
                            tagName = release.tagName,
                            versionName = release.name.ifEmpty { release.tagName },
                            releaseNotes = release.body ?: "New version available",
                            downloadUrl = release.htmlUrl,
                            isNewer = true
                        )
                    )
                } else if (isDismissed) {
                    Log.d(TAG, "Update ${release.tagName} was dismissed by user")
                    _updateState.value = UpdateState.UpToDate
                } else {
                    Log.d(TAG, "App is up to date")
                    _updateState.value = UpdateState.UpToDate
                }
            },
            onFailure = { error ->
                Log.e(TAG, "Failed to check for updates", error)
                _updateState.value = UpdateState.Error(error.message ?: "Unknown error")
            }
        )
    }

    fun dismissUpdate() {
        val currentState = _updateState.value
        if (currentState is UpdateState.UpdateAvailable) {
            prefs.edit()
                .putBoolean("${KEY_UPDATE_DISMISSED}_${currentState.updateInfo.tagName}", true)
                .apply()
            _updateState.value = UpdateState.UpToDate
        }
    }

    fun resetDismissed() {
        val currentState = _updateState.value
        if (currentState is UpdateState.UpdateAvailable) {
            prefs.edit()
                .remove("${KEY_UPDATE_DISMISSED}_${currentState.updateInfo.tagName}")
                .apply()
        } else {
            prefs.all.keys
                .filter { it.startsWith(KEY_UPDATE_DISMISSED) }
                .forEach { key -> prefs.edit().remove(key).apply() }
        }
        _updateState.value = UpdateState.Idle
    }

    private fun isNewerVersion(releaseTag: String, currentVersion: String): Boolean {
        val releaseBuildNumber = extractBuildNumber(releaseTag)
        val currentBuildNumber = extractBuildNumber(currentVersion)

        return when {
            releaseBuildNumber > 0 && currentBuildNumber > 0 -> releaseBuildNumber > currentBuildNumber
            releaseTag.startsWith("v") && currentVersion.startsWith("v") -> compareSemanticVersions(releaseTag, currentVersion) > 0
            releaseTag.startsWith("build-") && currentVersion.startsWith("v") -> true
            releaseTag.startsWith("v") && currentVersion.startsWith("build-") -> false
            else -> compareSemanticVersions(releaseTag, currentVersion) > 0
        }
    }

    private fun extractBuildNumber(version: String): Long {
        val regex = Regex("build[_-]?(\\d+)")
        val match = regex.find(version)
        return match?.groupValues?.getOrNull(1)?.toLongOrNull() ?: 0L
    }

    private fun compareSemanticVersions(version1: String, version2: String): Int {
        val v1Numbers = extractVersionNumbers(version1)
        val v2Numbers = extractVersionNumbers(version2)

        for (i in 0 until maxOf(v1Numbers.size, v2Numbers.size)) {
            val v1Part = v1Numbers.getOrElse(i) { 0 }
            val v2Part = v2Numbers.getOrElse(i) { 0 }
            if (v1Part != v2Part) {
                return v1Part.compareTo(v2Part)
            }
        }
        return 0
    }

    private fun extractVersionNumbers(version: String): List<Int> {
        val cleanVersion = version
            .replace(Regex("[^0-9.]"), "")
            .trim('.')
        return cleanVersion.split(".")
            .mapNotNull { it.toIntOrNull() }
    }
}

sealed class UpdateState {
    data object Idle : UpdateState()
    data object Checking : UpdateState()
    data object UpToDate : UpdateState()
    data class UpdateAvailable(val updateInfo: UpdateInfo) : UpdateState()
    data class Error(val message: String) : UpdateState()
}
