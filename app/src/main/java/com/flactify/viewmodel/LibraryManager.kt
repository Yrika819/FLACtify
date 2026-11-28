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

class LibraryManager private constructor(
    context: Context,
    preferences: SharedPreferences
) {
    constructor(context: Context) : this(
        context,
        context.getSharedPreferences("flactify_library", Context.MODE_PRIVATE)
    )

    private val favPrefs: SharedPreferences = preferences
    private val cacheDir: File = LibraryScanner.libraryCacheDirectory(context)
    // A pending restore is user data, not a disposable cache entry. Keep it in the app's
    // non-backed-up files area so Android cache eviction cannot discard the recovery intent.
    private val restoreJournal = LibraryBackupJournal(File(context.noBackupFilesDir, "library-restore.pending"))
    private var playlistsCanBeRewritten = true

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
        val json = favPrefs.getString("playlists", null) ?: run {
            playlistsCanBeRewritten = true
            return emptyList()
        }
        val parsed = parsePlaylists(json)
        playlistsCanBeRewritten = canSafelyRewritePlaylists(json, parsed)
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
        synchronized(STATS_LOCK) {
            val existingPlaylists = getPlaylists()
            if (!playlistsCanBeRewritten) return false
            val existingById = existingPlaylists.associateBy { it.id }
            val mergedPlaylists = existingPlaylists + incoming.playlists.filterNot { existingById.containsKey(it.id) }
            val storedStats = readStats()
            // A merge must not replace undecodable bytes with an incomplete parsed view.
            if (!storedStats.canRewrite) return false
            val existingStats = storedStats.stats
            val mergedStats = existingStats + incoming.stats.filterKeys { it !in existingStats }
            val merged = LibraryBackup(
                favorites = getFavorites() + incoming.favorites,
                playlists = mergedPlaylists,
                stats = mergedStats
            )
            return restoreBackup(LibraryBackupCodec.encode(merged))
        }
    }

    fun getCacheDirSize(): Long {
        return calculateDirSize(cacheDir)
    }

    fun clearCache() {
        cacheDir.deleteRecursively()
    }

    private val statsKey = "stats"

    fun getAllStats(): Map<String, PlayStats> = synchronized(STATS_LOCK) { readStats().stats }

    private fun readStats(): LibraryStatsCodec.ReadResult {
        val json = favPrefs.getString(statsKey, null) ?: return LibraryStatsCodec.ReadResult(emptyMap(), true)
        return LibraryStatsCodec.decode(json)
    }

    fun getStatsFor(uri: String): PlayStats {
        return getAllStats()[uri] ?: PlayStats()
    }

    /** Creates a user-data-only backup; no audio or cached artwork bytes are included. */
    fun createBackup(): String = synchronized(STATS_LOCK) {
        val storedStats = readStats()
        check(storedStats.canRewrite) {
            "Stored playback statistics contain unrecognized or conflicting data; backup was not generated"
        }
        val storedPlaylists = getPlaylists()
        check(playlistsCanBeRewritten) {
            "Stored playlists contain unrecognized data; backup was not generated"
        }
        LibraryBackupCodec.encode(
            LibraryBackup(
                favorites = getFavorites(),
                playlists = storedPlaylists,
                stats = storedStats.stats
            )
        )
    }

    /**
     * Validates the entire backup before replacing preference state. SharedPreferences applies
     * these values in one editor transaction; malformed or unsupported backups leave data intact.
     */
    fun restoreBackup(json: String): Boolean {
        val backup = LibraryBackupCodec.decode(json) ?: return false
        synchronized(STATS_LOCK) {
            if (runCatching { restoreJournal.stage(json) }.isFailure) return false
            // Keep the original preference representations so failed restore/rollback cannot
            // normalize or discard data that this version does not understand.
            val hadFavorites = favPrefs.contains("favorites")
            val previousFavorites = favPrefs.getStringSet("favorites", emptySet())?.toSet()
            val hadPlaylists = favPrefs.contains("playlists")
            val previousPlaylists = favPrefs.getString("playlists", null)
            val hadStats = favPrefs.contains(statsKey)
            val previousStats = favPrefs.getString(statsKey, null)
            val restored = favPrefs.edit()
                .putStringSet("favorites", backup.favorites)
                .putString("playlists", LibraryBackupCodec.encodePlaylists(backup.playlists))
                .putString(statsKey, LibraryStatsCodec.encode(backup.stats))
                .commit()
            if (!restored) {
                val rolledBack = runCatching {
                    favPrefs.edit().apply {
                        if (hadFavorites && previousFavorites != null) putStringSet("favorites", previousFavorites)
                        else remove("favorites")
                        if (hadPlaylists && previousPlaylists != null) putString("playlists", previousPlaylists)
                        else remove("playlists")
                        if (hadStats && previousStats != null) putString(statsKey, previousStats)
                        else remove(statsKey)
                    }.commit()
                }.getOrDefault(false)
                if (rolledBack) restoreJournal.clear()
                // If rollback fails, leave the staged incoming backup for a deliberate retry.
                return false
            }
            playlistsCanBeRewritten = true
            restoreJournal.clear()
            return true
        }
    }

    fun pendingRestorePreview(): LibraryBackupPreview? = restoreJournal.read()?.let(LibraryBackupCodec::preview)

    fun retryPendingRestore(): Boolean = synchronized(STATS_LOCK) {
        restoreJournal.read()?.let(::restoreBackup) ?: false
    }

    fun discardPendingRestore() = restoreJournal.clear()

    private fun saveAllStats(stats: Map<String, PlayStats>) {
        if (!readStats().canRewrite) return
        favPrefs.edit().putString(statsKey, LibraryStatsCodec.encode(stats)).apply()
    }

    fun recordPlay(uri: String) {
        synchronized(STATS_LOCK) {
            val all = readStats().stats.toMutableMap()
            val current = all[uri] ?: PlayStats()
            all[uri] = PlayStats(
                playCount = current.playCount + 1,
                skipCount = current.skipCount,
                lastPlayed = System.currentTimeMillis()
            )
            saveAllStats(all)
        }
    }

    fun recordSkip(uri: String) {
        synchronized(STATS_LOCK) {
            val all = readStats().stats.toMutableMap()
            val current = all[uri] ?: PlayStats()
            all[uri] = PlayStats(
                playCount = current.playCount,
                skipCount = current.skipCount + 1,
                lastPlayed = current.lastPlayed
            )
            saveAllStats(all)
        }
    }

    internal companion object Parsers {
        internal fun withPreferencesForTesting(context: Context, preferences: SharedPreferences): LibraryManager =
            LibraryManager(context, preferences)

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

        fun parseStats(json: String): Map<String, PlayStats> = LibraryStatsCodec.decode(json).stats

        private fun canSafelyRewritePlaylists(json: String, parsed: List<Playlist>): Boolean = runCatching {
            val array = JSONArray(json)
            if (parsed.size != array.length()) return false
            val playlistFields = setOf("id", "name", "trackUris", "smartQuery")
            val queryTextFields = setOf(
                "titleContains", "artistContains", "albumContains", "genreContains", "yearContains",
                "composerContains", "albumArtistContains", "formatContains"
            )
            for (index in 0 until array.length()) {
                val raw = array.getJSONObject(index)
                if (raw.keys().asSequence().any { it !in playlistFields }) return false
                if (raw.opt("id") !is String || raw.opt("name") !is String) return false
                if (raw.getString("id") != parsed[index].id || raw.getString("name") != parsed[index].name) return false
                if (raw.has("trackUris")) {
                    val uris = raw.optJSONArray("trackUris") ?: return false
                    val values = buildList {
                        for (uriIndex in 0 until uris.length()) {
                            add(uris.get(uriIndex) as? String ?: return false)
                        }
                    }
                    if (values != parsed[index].trackUris) return false
                } else if (parsed[index].trackUris.isNotEmpty()) {
                    return false
                }
                if (raw.has("smartQuery")) {
                    val query = raw.optJSONObject("smartQuery") ?: return false
                    if (query.keys().asSequence().any { it !in queryTextFields && it != "preset" && it != "order" && it != "limit" }) {
                        return false
                    }
                    queryTextFields.forEach { key ->
                        if (query.has(key) && (query.opt(key) !is String || query.getString(key).isBlank())) return false
                    }
                    if (query.has("preset") && (query.opt("preset") !is String ||
                            runCatching { SmartPlaylistPreset.valueOf(query.getString("preset")) }.isFailure)) return false
                    if (query.has("order") && (query.opt("order") !is String ||
                            runCatching { SmartPlaylistOrder.valueOf(query.getString("order")) }.isFailure)) return false
                    if (query.has("limit")) {
                        val limit = query.opt("limit") as? Number ?: return false
                        if (limit.toDouble() % 1.0 != 0.0 || limit.toLong() !in 1..100_000) return false
                    }
                    if (smartQueryFromJson(query) == null) return false
                }
            }
            true
        }.getOrDefault(false)

        private val STATS_LOCK = Any()
    }
}
