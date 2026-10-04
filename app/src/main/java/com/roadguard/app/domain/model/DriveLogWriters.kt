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
    // replaceAll keeps the cap, so reconstructing from the
    // current records yields an exact copy of this log.
    val copy = DriveLog()
    copy.replaceAll(records)
    return if (copy.add(record)) copy else null
}

/** An empty log with the default cap, for clearing. */
fun DriveLog.cleared(): DriveLog = DriveLog()
