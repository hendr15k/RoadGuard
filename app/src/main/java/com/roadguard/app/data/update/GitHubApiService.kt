package com.roadguard.app.data.update

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

class GitHubApiService(private val context: Context) {

    companion object {
        private const val TAG = "GitHubApiService"
        // ?per_page=1: nur das aktuellste Release holen. Spart Bandbreite
        // (war: alle Releases als JSON-Array) und Parsing-Zeit.
        private const val RELEASES_URL = "https://api.github.com/repos/hendr15k/RoadGuard/releases?per_page=1"
    }

    private val gson = Gson()

    /**
     * Timestamp bis zu dem kein erneuter API-Call gemacht werden soll
     * (GitHub Rate-Limit 403-Handling). In Memory gehalten, geht beim
     * App-Restart verloren — dann ist der Backoff nur eine Session lang,
     * das ist akzeptabel.
     */
    @Volatile
    private var rateLimitUntilMs: Long = 0L

    suspend fun getLatestRelease(): Result<GitHubRelease> = withContext(Dispatchers.IO) {
        // Backoff-Check: wenn 403 noch "aktiv" ist, gar nicht erst versuchen.
        val now = System.currentTimeMillis()
        if (now < rateLimitUntilMs) {
            val remainingSec = (rateLimitUntilMs - now) / 1000
            return@withContext Result.failure(
                Exception("Rate limited. Try again in $remainingSec seconds.")
            )
        }

        val connection = try {
            val url = URL(RELEASES_URL)
            url.openConnection() as HttpURLConnection
        } catch (e: Exception) {
            Log.e(TAG, "Exception opening connection", e)
            return@withContext Result.failure(e)
        }

        try {
            connection.requestMethod = "GET"
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("User-Agent", "RoadGuard-Android")
            connection.connectTimeout = 10000
            connection.readTimeout = 10000

            val responseCode = connection.responseCode
            if (responseCode == 403) {
                val retryAfter = connection.getHeaderField("Retry-After")?.toLongOrNull() ?: 60L
                rateLimitUntilMs = System.currentTimeMillis() + retryAfter * 1000L
                Log.w(TAG, "Rate limited. Retry after: $retryAfter seconds (backoff until ${rateLimitUntilMs})")
                return@withContext Result.failure(Exception("Rate limited. Try again in $retryAfter seconds."))
            }

            if (responseCode == HttpURLConnection.HTTP_OK) {
                val response = connection.inputStream.bufferedReader().use { it.readText() }
                val releases = gson.fromJson(response, Array<GitHubRelease>::class.java)
                if (releases.isNotEmpty()) {
                    Result.success(releases.first())
                } else {
                    Result.failure(Exception("No releases found"))
                }
            } else {
                Log.e(TAG, "HTTP Error: $responseCode")
                Result.failure(Exception("HTTP Error: $responseCode"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception fetching releases", e)
            Result.failure(e)
        } finally {
            connection.disconnect()
        }
    }

    suspend fun getLatestApkUrl(): Result<String> = withContext(Dispatchers.IO) {
        getLatestRelease().map { release ->
            release.assets.find { it.name.endsWith(".apk") }?.downloadUrl
                ?: release.htmlUrl
        }
    }
}
