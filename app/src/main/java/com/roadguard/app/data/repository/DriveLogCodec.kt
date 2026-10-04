package com.roadguard.app.data.repository

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.roadguard.app.domain.model.DriveRecord

/**
 * The (de)serialization half of drive-history persistence, pure JVM.
 *
 * Keeping JSON handling out of [DriveLogRepository] is not tidiness:
 * the acceptance rules of the persisted form — newest-first order,
 * bounded length, and a corrupt blob degrading to "no history"
 * instead of a crash on the next app start — are policy, and
 * policy belongs where a plain JUnit test can pin it. The
 * repository then only glues this to SharedPreferences.
 */
object DriveLogCodec {
    private val gson = Gson()
    private val recordListType = object : TypeToken<List<DriveRecord>>() {}.type

    fun serialize(records: List<DriveRecord>): String = gson.toJson(records)

    /**
     * Returns null for absent or unusable input. Callers treat null
     * as "nothing persisted" — a hand-edited or half-written blob
     * must never take the app down on startup.
     */
    fun deserialize(raw: String?): List<DriveRecord>? {
        if (raw == null) return null
        return try {
            gson.fromJson<List<DriveRecord>>(raw, recordListType) ?: emptyList()
        } catch (e: Exception) {
            null
        }
    }
}
