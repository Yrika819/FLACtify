package com.flactify.viewmodel

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class Playlist(
    val id: String = java.util.UUID.randomUUID().toString(),
    val name: String,
    val trackUris: List<String> = emptyList()
)

internal data class PlayStatsValues(
    val playCount: Int,
    val skipCount: Int,
    val lastPlayed: Long
)

class LibraryManager(context: Context) {
    private val favPrefs: SharedPreferences = context.getSharedPreferences("flactify_library", Context.MODE_PRIVATE)
    private val cacheDir: File = LibraryScanner.libraryCacheDirectory(context)
    private var playlistsCanBeRewritten = true
    private var statsCanBeRewritten = true

    data class PlayStats(
        val playCount: Int = 0,
        val skipCount: Int = 0,
        val lastPlayed: Long = 0L
    )

    fun getFavorites(): Set<String> {
        return favPrefs.getStringSet("favorites", emptySet()) ?: emptySet()
    }

    fun saveFavorite(uri: String) {
        val favs = getFavorites().toMutableSet()
        favs.add(uri)
        favPrefs.edit().putStringSet("favorites", favs).apply()
    }

    fun removeFavorite(uri: String) {
        val favs = getFavorites().toMutableSet()
        favs.remove(uri)
        favPrefs.edit().putStringSet("favorites", favs).apply()
    }

    fun toggleFavorite(uri: String) {
        val favs = getFavorites().toMutableSet()
        if (favs.contains(uri)) favs.remove(uri) else favs.add(uri)
        favPrefs.edit().putStringSet("favorites", favs).apply()
    }

    fun getPlaylists(): List<Playlist> {
        val json = favPrefs.getString("playlists", null) ?: return emptyList()
        val parsed = parsePlaylists(json)
        playlistsCanBeRewritten = runCatching { parsed.size == JSONArray(json).length() }.getOrDefault(false)
        return parsed
    }

    private fun savePlaylists(playlists: List<Playlist>) {
        if (!playlistsCanBeRewritten) return
        val arr = JSONArray()
        playlists.forEach { p ->
            val obj = JSONObject().apply {
                put("id", p.id)
                put("name", p.name)
                put("trackUris", JSONArray(p.trackUris))
            }
            arr.put(obj)
        }
        favPrefs.edit().putString("playlists", arr.toString()).apply()
    }

    fun savePlaylist(playlist: Playlist) {
        val all = getPlaylists().toMutableList()
        val idx = all.indexOfFirst { it.id == playlist.id }
        if (idx != -1) all[idx] = playlist else all.add(playlist)
        savePlaylists(all)
    }

    fun deletePlaylist(id: String) {
        val all = getPlaylists().filter { it.id != id }
        savePlaylists(all)
    }

    fun addTrackToPlaylist(playlistId: String, uri: String) {
        val all = getPlaylists().toMutableList()
        val idx = all.indexOfFirst { it.id == playlistId }
        if (idx != -1) {
            val p = all[idx]
            if (!p.trackUris.contains(uri)) {
                all[idx] = p.copy(trackUris = p.trackUris + uri)
                savePlaylists(all)
            }
        }
    }

    fun getCacheDirSize(): Long {
        return calculateDirSize(cacheDir)
    }

    fun clearCache() {
        cacheDir.deleteRecursively()
    }

    private val statsKey = "stats"

    fun getAllStats(): Map<String, PlayStats> {
        val json = favPrefs.getString(statsKey, null) ?: return emptyMap()
        val parsed = parseStats(json)
        statsCanBeRewritten = runCatching {
            parsed.size == JSONObject(json).keys().asSequence().count()
        }.getOrDefault(false)
        return parsed
    }

    fun getStatsFor(uri: String): PlayStats {
        return getAllStats()[uri] ?: PlayStats()
    }

    private fun saveAllStats(stats: Map<String, PlayStats>) {
        if (!statsCanBeRewritten) return
        val root = JSONObject()
        stats.forEach { (uri, s) ->
            val obj = JSONObject().apply {
                put("pc", s.playCount)
                put("sc", s.skipCount)
                put("lp", s.lastPlayed)
            }
            root.put(uri, obj)
        }
        favPrefs.edit().putString(statsKey, root.toString()).apply()
    }

    fun recordPlay(uri: String) {
        val all = getAllStats().toMutableMap()
        val current = all[uri] ?: PlayStats()
        all[uri] = PlayStats(
            playCount = current.playCount + 1,
            skipCount = current.skipCount,
            lastPlayed = System.currentTimeMillis()
        )
        saveAllStats(all)
    }

    fun recordSkip(uri: String) {
        val all = getAllStats().toMutableMap()
        val current = all[uri] ?: PlayStats()
        all[uri] = PlayStats(
            playCount = current.playCount,
            skipCount = current.skipCount + 1,
            lastPlayed = current.lastPlayed
        )
        saveAllStats(all)
    }

    internal companion object Parsers {
        fun calculateDirSize(dir: File): Long {
            val files = dir.listFiles() ?: return 0L
            return files.sumOf {
                if (it.isDirectory) it.walkTopDown().filter { f -> f.isFile }.sumOf { f -> f.length() } else it.length()
            }
        }

        fun parsePlaylists(json: String): List<Playlist> = try {
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { index ->
                runCatching {
                    val obj = arr.getJSONObject(index)
                    val id = obj.optString("id").takeIf { it.isNotBlank() } ?: return@runCatching null
                    val name = obj.optString("name").takeIf { it.isNotBlank() } ?: return@runCatching null
                    val uris = obj.optJSONArray("trackUris")
                    Playlist(
                        id = id,
                        name = name,
                        trackUris = if (uris == null) emptyList() else {
                            (0 until uris.length()).mapNotNull { uriIndex ->
                                runCatching { uris.getString(uriIndex) }.getOrNull()
                            }
                        }
                    )
                }.getOrNull()
            }
        } catch (_: Exception) {
            emptyList()
        }

        fun parseStats(json: String): Map<String, PlayStats> = try {
            val obj = JSONObject(json)
            val entries = buildList {
                val keys = obj.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val values = runCatching {
                        val item = obj.getJSONObject(key)
                        PlayStatsValues(
                            playCount = item.optInt("pc", 0),
                            skipCount = item.optInt("sc", 0),
                            lastPlayed = item.optLong("lp", 0L)
                        )
                    }.getOrNull()
                    add(key to values)
                }
            }
            parseStatsEntries(entries)
        } catch (_: Exception) {
            emptyMap()
        }

        fun parseStatsEntries(entries: Iterable<Pair<String, PlayStatsValues?>>): Map<String, PlayStats> =
            buildMap {
                entries.forEach { (uri, values) ->
                    if (values != null) {
                        put(
                            uri, PlayStats(
                                playCount = values.playCount.coerceAtLeast(0),
                                skipCount = values.skipCount.coerceAtLeast(0),
                                lastPlayed = values.lastPlayed.coerceAtLeast(0L)
                            )
                        )
                    }
                }
            }
    }
}
