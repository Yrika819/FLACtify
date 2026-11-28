package com.flactify.viewmodel

import org.json.JSONArray
import org.json.JSONObject

/** Portable snapshot of user-owned library organization data. Audio files are never included. */
data class LibraryBackup(
    val favorites: Set<String>,
    val playlists: List<Playlist>,
    val stats: Map<String, LibraryManager.PlayStats>
)

data class LibraryBackupPreview(
    val version: Int,
    val favoriteCount: Int,
    val playlistCount: Int,
    val statsCount: Int
)

/** Versioned JSON codec. Decode validates the complete snapshot before a caller restores it. */
object LibraryBackupCodec {
    private const val VERSION = 1
    private const val MAX_ENTRIES = 100_000
    private const val MAX_TEXT = 16_384
    private const val MAX_TOTAL_TEXT = 8 * 1024 * 1024

    fun encode(backup: LibraryBackup): String {
        require(backup.favorites.size <= MAX_ENTRIES)
        require(backup.playlists.size <= MAX_ENTRIES)
        require(backup.stats.size <= MAX_ENTRIES)
        require(textBudget(backup) <= MAX_TOTAL_TEXT) { "Backup text budget exceeded" }
        val root = JSONObject()
            .put("version", VERSION)
            .put("favorites", JSONArray().apply {
                backup.favorites.sorted().forEach { put(validText(it, "favorite")) }
            })
            .put("playlists", JSONArray().apply {
                backup.playlists.forEach { playlist ->
                    require(playlist.trackUris.size <= MAX_ENTRIES)
                    val item = JSONObject()
                        .put("id", validText(playlist.id, "playlist id"))
                        .put("name", validText(playlist.name, "playlist name"))
                        .put("trackUris", JSONArray().apply {
                            playlist.trackUris.forEach { put(validText(it, "track URI")) }
                        })
                    playlist.smartQuery?.let { item.put("smartQuery", queryToJson(it)) }
                    put(item)
                }
            })
            .put("stats", JSONObject().apply {
                backup.stats.toSortedMap().forEach { (uri, stat) ->
                    require(stat.playCount >= 0 && stat.skipCount >= 0 && stat.lastPlayed >= 0L)
                    put(validText(uri, "stats key"), JSONObject()
                        .put("playCount", stat.playCount)
                        .put("skipCount", stat.skipCount)
                        .put("lastPlayed", stat.lastPlayed))
                }
            })
        return root.toString()
    }

    /** Returns null for an unsupported version, malformed JSON, or any invalid field. */
    fun decode(json: String): LibraryBackup? = runCatching {
        require(json.length <= 32 * 1024 * 1024)
        val root = JSONObject(json)
        require(root.getInt("version") == VERSION)

        val favoritesArray = root.getJSONArray("favorites")
        require(favoritesArray.length() <= MAX_ENTRIES)
        val favorites = buildSet {
            for (i in 0 until favoritesArray.length()) add(validText(favoritesArray.getString(i), "favorite"))
        }

        val playlistsArray = root.getJSONArray("playlists")
        require(playlistsArray.length() <= MAX_ENTRIES)
        val playlists = buildList {
            val ids = HashSet<String>()
            for (i in 0 until playlistsArray.length()) {
                val item = playlistsArray.getJSONObject(i)
                val id = validText(item.getString("id"), "playlist id")
                require(ids.add(id)) { "Duplicate playlist id" }
                val name = validText(item.getString("name"), "playlist name")
                val urisArray = item.getJSONArray("trackUris")
                require(urisArray.length() <= MAX_ENTRIES)
                val uris = List(urisArray.length()) { index ->
                    validText(urisArray.getString(index), "track URI")
                }
                add(Playlist(id, name, uris, item.optJSONObject("smartQuery")?.let(::queryFromJson)))
            }
        }

        val statsObject = root.getJSONObject("stats")
        require(statsObject.length() <= MAX_ENTRIES)
        val stats = buildMap {
            val keys = statsObject.keys()
            while (keys.hasNext()) {
                val key = validText(keys.next(), "stats key")
                val value = statsObject.getJSONObject(key)
                val plays = value.getInt("playCount")
                val skips = value.getInt("skipCount")
                val lastPlayed = value.getLong("lastPlayed")
                require(plays >= 0 && skips >= 0 && lastPlayed >= 0L)
                put(key, LibraryManager.PlayStats(plays, skips, lastPlayed))
            }
        }
        LibraryBackup(favorites, playlists, stats).also {
            require(textBudget(it) <= MAX_TOTAL_TEXT) { "Backup text budget exceeded" }
        }
    }.getOrNull()

    fun preview(json: String): LibraryBackupPreview? = decode(json)?.let {
        LibraryBackupPreview(
            version = VERSION,
            favoriteCount = it.favorites.size,
            playlistCount = it.playlists.size,
            statsCount = it.stats.size
        )
    }

    internal fun encodePlaylists(playlists: List<Playlist>): String =
        JSONObject(encode(LibraryBackup(emptySet(), playlists, emptyMap())))
            .getJSONArray("playlists").toString()

    private fun queryToJson(query: SmartPlaylistQuery) = JSONObject().apply {
        query.titleContains?.let { put("titleContains", validText(it, "smart query")) }
        query.artistContains?.let { put("artistContains", validText(it, "smart query")) }
        query.albumContains?.let { put("albumContains", validText(it, "smart query")) }
        query.genreContains?.let { put("genreContains", validText(it, "smart query")) }
        query.yearContains?.let { put("yearContains", validText(it, "smart query")) }
        query.composerContains?.let { put("composerContains", validText(it, "smart query")) }
        query.albumArtistContains?.let { put("albumArtistContains", validText(it, "smart query")) }
        query.formatContains?.let { put("formatContains", validText(it, "smart query")) }
        put("preset", query.preset.name)
        put("order", query.order.name)
        query.limit?.let { require(it > 0); put("limit", it) }
    }

    private fun queryFromJson(value: JSONObject): SmartPlaylistQuery = SmartPlaylistQuery(
        titleContains = value.optString("titleContains").takeIf(String::isNotEmpty)?.let { validText(it, "smart query") },
        artistContains = value.optString("artistContains").takeIf(String::isNotEmpty)?.let { validText(it, "smart query") },
        albumContains = value.optString("albumContains").takeIf(String::isNotEmpty)?.let { validText(it, "smart query") },
        genreContains = value.optString("genreContains").takeIf(String::isNotEmpty)?.let { validText(it, "smart query") },
        yearContains = value.optString("yearContains").takeIf(String::isNotEmpty)?.let { validText(it, "smart query") },
        composerContains = value.optString("composerContains").takeIf(String::isNotEmpty)?.let { validText(it, "smart query") },
        albumArtistContains = value.optString("albumArtistContains").takeIf(String::isNotEmpty)?.let { validText(it, "smart query") },
        formatContains = value.optString("formatContains").takeIf(String::isNotEmpty)?.let { validText(it, "smart query") },
        preset = runCatching { SmartPlaylistPreset.valueOf(value.optString("preset")) }.getOrDefault(SmartPlaylistPreset.NONE),
        order = runCatching { SmartPlaylistOrder.valueOf(value.optString("order")) }.getOrDefault(SmartPlaylistOrder.LIBRARY),
        limit = value.optInt("limit", 0).takeIf { it > 0 }?.also {
            require(it <= MAX_ENTRIES) { "Smart query limit exceeds the entry limit" }
        }
    )

    private fun validText(value: String, label: String): String {
        require(value.isNotEmpty() && value.length <= MAX_TEXT && value.none { it == '\u0000' }) {
            "Invalid $label"
        }
        return value
    }

    private fun textBudget(backup: LibraryBackup): Long {
        var total = backup.favorites.sumOf { it.length.toLong() }
        backup.playlists.forEach { playlist ->
            total += playlist.id.length + playlist.name.length
            total += playlist.trackUris.sumOf { it.length.toLong() }
            playlist.smartQuery?.let { query ->
                total += listOfNotNull(
                    query.titleContains, query.artistContains, query.albumContains,
                    query.genreContains, query.yearContains, query.composerContains,
                    query.albumArtistContains, query.formatContains
                ).sumOf { it.length.toLong() }
            }
        }
        total += backup.stats.keys.sumOf { it.length.toLong() }
        return total
    }
}
