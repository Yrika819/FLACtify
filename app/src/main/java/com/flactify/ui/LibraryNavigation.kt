package com.flactify.ui

internal enum class LibraryBackAction {
    CLOSE_PLAYLIST,
    CLOSE_ARTIST,
    CLOSE_ALBUM,
    EXIT_LIBRARY
}

internal sealed interface PlaylistTabContent {
    data object List : PlaylistTabContent
    data class Tracks(val playlistId: String) : PlaylistTabContent
}

internal fun playlistTabContent(showPlaylistContent: String?): PlaylistTabContent =
    showPlaylistContent?.let { PlaylistTabContent.Tracks(it) } ?: PlaylistTabContent.List

internal fun libraryBackAction(
    selectedTab: String,
    selectedArtist: String?,
    selectedAlbum: String?,
    showPlaylistContent: String?
): LibraryBackAction = when {
    showPlaylistContent != null -> LibraryBackAction.CLOSE_PLAYLIST
    selectedTab == "artists" && selectedArtist != null -> LibraryBackAction.CLOSE_ARTIST
    selectedTab == "albums" && selectedAlbum != null -> LibraryBackAction.CLOSE_ALBUM
    else -> LibraryBackAction.EXIT_LIBRARY
}
