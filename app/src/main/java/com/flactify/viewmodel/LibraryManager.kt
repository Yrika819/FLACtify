package com.flactify.viewmodel

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class Playlist(
    val id: String = java.util.UUID.randomUUID().toString(),
    val name: String,
    val trackUris: List<String> = emptyList(),
    val smartQuery: SmartPlaylistQuery? = null
)

internal data class PlayStatsValues(
    val playCount: Int,
    val skipCount: Int,
    val lastPlayed: Long
)

class LibraryManager(context: Context) {
    private val favPrefs: SharedPreferences = context.getSharedPreferences("flactify_library", Context.MODE_PRIVATE)
    private val cacheDir: File = LibraryScanner.libraryCacheDirectory(context)
    // Keep the journal beside (but outside) the library cache directory: clearCache() must not
    // erase an interrupted restore intent before the user can retry it.
    private val restoreJournal = LibraryBackupJournal(File(context.cacheDir, "library-restore.pending"))
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
                p.smartQuery?.let { put("smartQuery", smartQueryToJson(it)) }
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

    fun mergeBackup(json: String): Boolean {
        val incoming = LibraryBackupCodec.decode(json) ?: return false
        val existingPlaylists = getPlaylists()
        val existingById = existingPlaylists.associateBy { it.id }
        val mergedPlaylists = existingPlaylists + incoming.playlists.filterNot { existingById.containsKey(it.id) }
        val existingStats = getAllStats()
        val mergedStats = existingStats + incoming.stats.filterKeys { it !in existingStats }
        val merged = LibraryBackup(
            favorites = getFavorites() + incoming.favorites,
            playlists = mergedPlaylists,
            stats = mergedStats
        )
        return restoreBackup(LibraryBackupCodec.encode(merged))
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

    /** Creates a user-data-only backup; no audio or cached artwork bytes are included. */
    fun createBackup(): String = LibraryBackupCodec.encode(
        LibraryBackup(
            favorites = getFavorites(),
            playlists = getPlaylists(),
            stats = getAllStats()
        )
    )

    /**
     * Validates the entire backup before replacing preference state. SharedPreferences applies
     * these values in one editor transaction; malformed or unsupported backups leave data intact.
     */
    fun restoreBackup(json: String): Boolean {
        val backup = LibraryBackupCodec.decode(json) ?: return false
        val staged = runCatching { restoreJournal.stage(json) }.isSuccess
        if (!staged) return false
        val previous = LibraryBackup(
            favorites = getFavorites(),
            playlists = getPlaylists(),
            stats = getAllStats()
        )
        val restored = favPrefs.edit()
            .putStringSet("favorites", backup.favorites)
            .putString("playlists", LibraryBackupCodec.encodePlaylists(backup.playlists))
            .putString("stats", LibraryBackupCodec.encodeStats(backup.stats))
            .commit()
        if (!restored) {
            // SharedPreferences normally leaves values untouched on a failed commit, but the
            // contract does not promise that. Best-effort rollback keeps a transient failure from
            // exposing a partially replaced in-memory library.
            val rolledBack = runCatching {
                favPrefs.edit()
                    .putStringSet("favorites", previous.favorites)
                    .putString("playlists", LibraryBackupCodec.encodePlaylists(previous.playlists))
                    .putString("stats", LibraryBackupCodec.encodeStats(previous.stats))
                    .commit()
            }.getOrDefault(false)
            if (rolledBack) {
                restoreJournal.clear()
            }
            // Keep the staged incoming backup when rollback itself fails. A later process can
            // inspect pendingRestorePreview() and retry rather than silently losing the intent.
        }
        if (restored) {
            playlistsCanBeRewritten = true
            statsCanBeRewritten = true
            restoreJournal.clear()
        }
        return restored
    }

    fun pendingRestorePreview(): LibraryBackupPreview? = restoreJournal.read()?.let(LibraryBackupCodec::preview)

    fun retryPendingRestore(): Boolean = restoreJournal.read()?.let(::restoreBackup) ?: false

    fun discardPendingRestore() = restoreJournal.clear()

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
                        },
                        smartQuery = runCatching { obj.getJSONObject("smartQuery") }
                            .getOrNull()
                            ?.let(::smartQueryFromJson)
                    )
                }.getOrNull()
            }
        } catch (_: Exception) {
            emptyList()
        }

        private fun smartQueryToJson(query: SmartPlaylistQuery): JSONObject = JSONObject().apply {
            query.titleContains?.let { put("titleContains", it) }
            query.artistContains?.let { put("artistContains", it) }
            query.albumContains?.let { put("albumContains", it) }
            query.genreContains?.let { put("genreContains", it) }
            query.yearContains?.let { put("yearContains", it) }
            query.composerContains?.let { put("composerContains", it) }
            query.albumArtistContains?.let { put("albumArtistContains", it) }
            query.formatContains?.let { put("formatContains", it) }
            put("preset", query.preset.name)
            put("order", query.order.name)
            query.limit?.let { put("limit", it) }
        }

        private fun smartQueryFromJson(obj: JSONObject): SmartPlaylistQuery? {
            fun value(key: String): String? = obj.optString(key).takeIf { it.isNotBlank() }
            val query = SmartPlaylistQuery(
                titleContains = value("titleContains"),
                artistContains = value("artistContains"),
                albumContains = value("albumContains"),
                genreContains = value("genreContains"),
                yearContains = value("yearContains"),
                composerContains = value("composerContains"),
                albumArtistContains = value("albumArtistContains"),
                formatContains = value("formatContains"),
                preset = runCatching { SmartPlaylistPreset.valueOf(obj.optString("preset")) }
                    .getOrDefault(SmartPlaylistPreset.NONE),
                order = runCatching { SmartPlaylistOrder.valueOf(obj.optString("order")) }
                    .getOrDefault(SmartPlaylistOrder.LIBRARY),
                limit = obj.optInt("limit", 0).takeIf { it in 1..100_000 }
            )
            return query.takeUnless {
                it.titleContains == null && it.artistContains == null && it.albumContains == null &&
                    it.genreContains == null && it.yearContains == null && it.composerContains == null &&
                    it.albumArtistContains == null && it.formatContains == null &&
                    it.preset == SmartPlaylistPreset.NONE && it.order == SmartPlaylistOrder.LIBRARY &&
                    it.limit == null
            }
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
