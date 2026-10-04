package com.roadguard.app.domain.model

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * One completed drive, as persisted in the drive log.
 *
 * Pure JVM and timestamp-driven like [DriveSessionStats]: a record is
 * a plain snapshot the repository serializes and the UI renders, with
 * no Android dependencies so the acceptance rules are unit-testable.
 */
data class DriveRecord(
    val startedAtMs: Long,
    val endedAtMs: Long,
    val laneDepartureCount: Int = 0,
    val collisionCount: Int = 0,
    val urgentCollisionCount: Int = 0,
    val warningTimeMs: Long = 0L,
    val safetyScore: Int = 100
) {
    val durationMs: Long get() = (endedAtMs - startedAtMs).coerceAtLeast(0L)

    val totalIncidents: Int get() = laneDepartureCount + collisionCount

    /** Share of the drive spent under an active warning, 0f..1f. */
    val warningTimeFraction: Float
        get() = if (durationMs <= 0L) 0f
        else (warningTimeMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)

    /**
     * A record is only worth persisting when it either took some time
     * or captured something. Without this rule every cold start that
     * was closed a heartbeat later (home swipe, rotation-triggered
     * recreation before the fix, a child mode) would litter the log
     * with zero-second entries.
     */
    fun isMeaningful(): Boolean = durationMs >= MIN_MEANINGFUL_DURATION_MS || totalIncidents > 0

    /** Compact one-line summary for the history list. */
    fun summary(): String =
        STARTED_FORMAT.format(Date(startedAtMs)) + " · " + formatDuration()

    private fun formatDuration(): String {
        val totalSeconds = (durationMs / 1000).toInt().coerceAtLeast(0)
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return if (minutes >= 60) {
            String.format(Locale.US, "%d:%02d:%02d", minutes / 60, minutes % 60, seconds)
        } else {
            String.format(Locale.US, "%d:%02d", minutes, seconds)
        }
    }

    companion object {
        /** Shorter openings than this are noise unless they carried an incident. */
        const val MIN_MEANINGFUL_DURATION_MS = 1_000L

        /** Maps a session snapshot onto a persisted record. */
        fun from(stats: DriveSessionStats, endedAtMs: Long): DriveRecord =
            DriveRecord(
                startedAtMs = stats.startedAtMs,
                endedAtMs = endedAtMs,
                laneDepartureCount = stats.laneDepartureCount,
                collisionCount = stats.collisionCount,
                urgentCollisionCount = stats.urgentCollisionCount,
                warningTimeMs = stats.warningTimeMs,
                safetyScore = stats.safetyScore
            )
    }
}

private val STARTED_FORMAT = SimpleDateFormat("dd.MM. HH:mm", Locale.US)

/**
 * Bounded, newest-first log of completed drives. Pure JVM: the
 * repository handles persistence and feeds [replaceAll] from storage.
 *
 * Insert order and cap are the whole contract — an unbounded log in
 * SharedPreferences is a growing JSON blob that eventually makes every
 * app start parse it, and an oldest-first list forces the UI to sort
 * on every render.
 */
class DriveLog(private val maxEntries: Int = DEFAULT_MAX_ENTRIES) {

    companion object {
        const val DEFAULT_MAX_ENTRIES = 30
    }

    private val _records = ArrayDeque<DriveRecord>()

    /** Newest drive first — the order the history screen renders. */
    val records: List<DriveRecord> get() = _records.toList()

    /**
     * Adds a completed drive. Returns true when it was stored.
     * Meaningless records (see [DriveRecord.isMeaningful]) are
     * dropped here so the caller never has to remember the rule.
     */
    fun add(record: DriveRecord): Boolean {
        if (!record.isMeaningful()) return false
        _records.addFirst(record)
        while (_records.size > maxEntries) _records.removeLast()
        return true
    }

    /**
     * Restores the log from persisted storage. The input may be in
     * any order (an unbounded store would append oldest-first), so
     * this keeps the newest [maxEntries] records and renders
     * newest-first.
     */
    fun replaceAll(records: List<DriveRecord>) {
        _records.clear()
        val newest = records.sortedByDescending { it.startedAtMs }.take(maxEntries)
        newest.forEach { _records.addLast(it) }
    }

    fun clear() {
        _records.clear()
    }
}
