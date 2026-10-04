package com.roadguard.app.data.repository

import com.roadguard.app.domain.model.DriveLog
import com.roadguard.app.domain.model.DriveRecord
import com.roadguard.app.domain.model.DriveSessionStats
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Bridge between the per-drive recorder ([DriveSessionStats])
 * and the persistent history ([DriveLogRepository]).
 *
 * Pure JVM on purpose: the archive rule (which stats become a
 * record) is policy, not plumbing, so it belongs where it can
 * be unit-tested — the mapping itself is [DriveRecord.from].
 */
@Singleton
class DriveArchiver @Inject constructor(
    private val driveLogRepository: DriveLogRepository
) {
    /**
     * Finalizes the session snapshot into the persistent history.
     * Returns true when a record was stored — the caller uses
     * that to reset its in-memory session only for real saves,
     * so a meaningless opening leaves the running drive alone.
     */
    fun archiveNow(stats: DriveSessionStats, endedAtMs: Long): Boolean =
        driveLogRepository.addCompletedDrive(DriveRecord.from(stats, endedAtMs))

    val driveLog: StateFlow<DriveLog> get() = driveLogRepository.driveLog

    fun clearHistory() {
        driveLogRepository.clearHistory()
    }
}
