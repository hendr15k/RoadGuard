package com.roadguard.app.data.repository

import android.content.Context
import android.content.SharedPreferences
import com.roadguard.app.domain.model.DriveLog
import com.roadguard.app.domain.model.DriveRecord
import com.roadguard.app.domain.model.cleared
import com.roadguard.app.domain.model.withAppended
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persists completed drives across app restarts.
 *
 * The in-memory [DriveSession] in MainViewModel is a ViewModel:
 * it dies with the composition's ViewModelStore owner and with the
 * process. That lost every drive the moment the app went away, so
 * "Drive" stats were really "since the last open". This repository
 * appends each finalized drive to SharedPreferences (JSON via Gson,
 * which the app already ships for the update checker) and keeps the
 * newest-first [DriveLog] the history screen renders.
 *
 * JSON (not one prefs key per field) because a record is a fixed
 * seven-field snapshot; adding a field later would otherwise need a
 * migration per version. The cap lives in [DriveLog], so the blob
 * cannot grow without bound.
 *
 * Every publication is a NEW [DriveLog] instance (via
 * [withAppended] and [cleared]): StateFlow conflates
 * equal values and DriveLog compares by identity, so
 * re-emitting a mutated shared instance would silently
 * drop the update and the History tab would never
 * refresh.
 */
@Singleton
class DriveLogRepository @Inject constructor(
    @ApplicationContext context: Context
) {
    companion object {
        private const val PREFS_NAME = "roadguard_drive_log"
        private const val KEY_RECORDS = "drive_records_v1"
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _driveLog = MutableStateFlow(DriveLog())
    val driveLog: StateFlow<DriveLog> = _driveLog.asStateFlow()

    init {
        _driveLog.value.replaceAll(loadRecords())
    }

    private fun loadRecords(): List<DriveRecord> =
        DriveLogCodec.deserialize(prefs.getString(KEY_RECORDS, null)) ?: emptyList()

    /**
     * Stores a completed drive. Returns true when it was
     * meaningful enough to keep (see [DriveRecord.isMeaningful]).
     */
    fun addCompletedDrive(record: DriveRecord): Boolean {
        val updated = _driveLog.value.withAppended(record) ?: return false
        _driveLog.value = updated
        persist(updated.records)
        return true
    }

    fun clearHistory() {
        _driveLog.value = _driveLog.value.cleared()
        prefs.edit().remove(KEY_RECORDS).apply()
    }

    private fun persist(records: List<DriveRecord>) {
        prefs.edit().putString(KEY_RECORDS, DriveLogCodec.serialize(records)).apply()
    }
}
