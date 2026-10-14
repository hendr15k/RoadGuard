package com.roadguard.app.domain.model

/**
 * A copy of this log with [record] stored.
 *
 * Returns null when the record is not meaningful (see
 * [DriveRecord.isMeaningful]). The copy is a NEW instance
 * on purpose: callers publish the result through a StateFlow,
 * and StateFlow conflates values that are equal while
 * DriveLog compares by identity — re-emitting a mutated
 * shared instance would silently drop the update and the
 * History tab would never refresh.
 */
fun DriveLog.withAppended(record: DriveRecord): DriveLog? {
    if (!record.isMeaningful()) return null
    // replaceAll keeps the cap, so reconstructing from the current records
    // yields an exact copy of this log. The copy MUST carry this log's cap:
    // a DriveLog() here would silently reset a non-default cap (the runtime
    // always uses the default, but the class is parametrised for tests and
    // future callers, and a reset cap lets the copy exceed its bound).
    val copy = DriveLog(maxEntries)
    copy.replaceAll(records)
    return if (copy.add(record)) copy else null
}

/** An empty copy with the same cap, for clearing. */
fun DriveLog.cleared(): DriveLog = DriveLog(maxEntries)
