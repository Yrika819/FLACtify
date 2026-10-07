package com.flactify.viewmodel

import android.content.Context

class UserLibraryController internal constructor(
    private val libraryManager: LibraryManager
) {
    constructor(context: Context) : this(LibraryManager(context))

    fun getFavorites(): Set<String> = libraryManager.getFavorites()

    fun toggleFavorite(uri: String) = libraryManager.toggleFavorite(uri)

    fun getPlaylists(): List<Playlist> = libraryManager.getPlaylists()

    fun savePlaylist(playlist: Playlist) = libraryManager.savePlaylist(playlist)

    fun deletePlaylist(id: String) = libraryManager.deletePlaylist(id)

    fun addTrackToPlaylist(playlistId: String, uri: String) =
        libraryManager.addTrackToPlaylist(playlistId, uri)

    fun getPlaylistTracks(playlistId: String, allTracks: List<TrackData>): List<TrackData> {
        val playlist = getPlaylists().find { it.id == playlistId } ?: return emptyList()
        val uriMap = allTracks.associateBy { it.uri.toString() }
        return playlist.trackUris.mapNotNull { uriMap[it] }
    }

    fun getCacheDirSize(): Long = libraryManager.getCacheDirSize()

    fun clearCache() = libraryManager.clearCache()

    fun recordPlay(uri: String) = libraryManager.recordPlay(uri)

    fun recordSkip(uri: String) = libraryManager.recordSkip(uri)

    fun getAllStats(): Map<String, LibraryManager.PlayStats> = libraryManager.getAllStats()

    fun getStatsFor(uri: String): LibraryManager.PlayStats = libraryManager.getStatsFor(uri)
}
