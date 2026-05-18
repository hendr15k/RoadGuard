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
        private const val RELEASES_URL = "https://api.github.com/repos/hendr15k/RoadGuard/releases"
    }

    private val gson = Gson()

    suspend fun getLatestRelease(): Result<GitHubRelease> = withContext(Dispatchers.IO) {
        try {
            val url = URL(RELEASES_URL)
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("User-Agent", "RoadGuard-Android")
            connection.connectTimeout = 10000
            connection.readTimeout = 10000

            val responseCode = connection.responseCode
            if (responseCode == HttpURLConnection.HTTP_OK) {
                val response = connection.inputStream.bufferedReader().readText()
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
        }
    }

    suspend fun getLatestApkUrl(): Result<String> = withContext(Dispatchers.IO) {
        getLatestRelease().map { release ->
            release.assets.find { it.name.endsWith(".apk") }?.downloadUrl
                ?: release.htmlUrl
        }
    }
}
