package com.flactify.viewmodel

import android.content.Context
import android.content.SharedPreferences
import com.flactify.BuildConfig
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

data class RecommendedTrack(
    val title: String,
    val artist: String,
    val reason: String,
    val sourceArtist: String,
    val albumArtUrl: String? = null
)

class RecommendationManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("flactify_recommendations", Context.MODE_PRIVATE)
    private val cacheKey = "last_recommendations"
    private val cacheDurationMs = 24L * 60 * 60 * 1000

    fun clearCache() {
        prefs.edit().remove(cacheKey).apply()
    }

    fun getRecommendations(
        artistMap: Map<String, List<TrackData>>,
        allStats: Map<String, LibraryManager.PlayStats>
    ): Result<List<RecommendedTrack>> {
        val apiKey = BuildConfig.LASTFM_API_KEY
        if (apiKey.isNullOrEmpty()) return Result.failure(Exception("API_KEY_NOT_SET"))

        val scoredStats = allStats.entries
            .filter { it.value.playCount > 0 }
            .map { (uri, stats) ->
                val recencyWeight = if (stats.lastPlayed > 0) {
                    val hoursAgo = (System.currentTimeMillis() - stats.lastPlayed) / (1000 * 60 * 60)
                    if (hoursAgo < 24) 3.0 else if (hoursAgo < 72) 2.0 else 1.0
                } else 0.5
                Triple(uri, stats, stats.playCount * recencyWeight)
            }
            .sortedByDescending { it.third }

        if (scoredStats.size < 5) return Result.failure(Exception("NEEDS_MORE_DATA"))

        val topPlayedUris = scoredStats.take(20).map { it.first }.toSet()
        val topArtists = artistMap.entries
            .filter { (_, tracks) -> tracks.any { it.uri.toString() in topPlayedUris } }
            .sortedByDescending { (_, tracks) ->
                tracks.sumOf { t -> allStats[t.uri.toString()]?.playCount ?: 0 }
            }
            .take(3)

        if (topArtists.isEmpty()) return Result.failure(Exception("NO_ARTISTS_FOUND"))

        val cached = loadCached()
        if (cached != null && cached.isNotEmpty()) return Result.success(cached)

        val results = mutableListOf<RecommendedTrack>()
        for ((artistName, _) in topArtists) {
            try {
                val artistEncoded = java.net.URLEncoder.encode(artistName, "UTF-8")
                val url = URL("https://ws.audioscrobbler.com/2.0/?method=artist.gettoptracks&artist=$artistEncoded&api_key=$apiKey&format=json&limit=5")
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 8000
                conn.readTimeout = 8000
                val responseCode = conn.responseCode
                val responseStream = if (responseCode in 200..299) conn.inputStream else conn.errorStream
                val reader = BufferedReader(InputStreamReader(responseStream))
                val response = reader.readText()
                reader.close()
                conn.disconnect()

                if (responseCode !in 200..299) {
                    throw Exception("HTTP_ERROR_$responseCode")
                }

                val json = JSONObject(response)
                val tracks = json.optJSONObject("toptracks")?.optJSONArray("track") ?: continue
                for (i in 0 until tracks.length()) {
                    val track = tracks.getJSONObject(i)
                    val title = track.optString("name", "")
                    val artist = track.optJSONObject("artist")?.optString("name", "") ?: ""
                    val imageArray = track.optJSONArray("image")
                    val albumArtUrl = if (imageArray != null && imageArray.length() > 0) {
                        imageArray.getJSONObject(imageArray.length() - 1).optString("#text", null)
                    } else null

                    if (title.isNotBlank()) {
                        results.add(RecommendedTrack(
                            title = title,
                            artist = artist,
                            reason = "$artistName が好きなあなたに",
                            sourceArtist = artistName,
                            albumArtUrl = albumArtUrl
                        ))
                    }
                }
            } catch (e: Exception) {
                if (results.isEmpty()) return Result.failure(Exception("NETWORK_ERROR"))
            }
        }

        if (results.isEmpty()) return Result.failure(Exception("NO_ARTISTS_FOUND"))

        saveCache(results)
        return Result.success(results)
    }

    private fun loadCached(): List<RecommendedTrack>? {
        val jsonStr = prefs.getString(cacheKey, null) ?: return null
        return try {
            val obj = JSONObject(jsonStr)
            val timestamp = obj.optLong("timestamp", 0L)
            if (System.currentTimeMillis() - timestamp > cacheDurationMs) return null
            val arr = obj.getJSONArray("data")
            (0 until arr.length()).map { i ->
                val item = arr.getJSONObject(i)
                RecommendedTrack(
                    title = item.getString("title"),
                    artist = item.getString("artist"),
                    reason = item.getString("reason"),
                    sourceArtist = item.getString("sourceArtist"),
                    albumArtUrl = item.optString("albumArtUrl", null)
                )
            }
        } catch (e: Exception) { null }
    }

    private fun saveCache(tracks: List<RecommendedTrack>) {
        try {
            val arr = JSONArray()
            tracks.forEach { t ->
                val obj = JSONObject().apply {
                    put("title", t.title)
                    put("artist", t.artist)
                    put("reason", t.reason)
                    put("sourceArtist", t.sourceArtist)
                    put("albumArtUrl", t.albumArtUrl ?: "")
                }
                arr.put(obj)
            }
            val root = JSONObject().apply {
                put("timestamp", System.currentTimeMillis())
                put("data", arr)
            }
            prefs.edit().putString(cacheKey, root.toString()).apply()
        } catch (e: Exception) {}
    }
}
