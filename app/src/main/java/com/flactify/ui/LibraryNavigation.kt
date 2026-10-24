package com.flactify.ui

internal enum class LibraryBackAction {
    CLOSE_PLAYLIST,
    CLOSE_ARTIST,
    CLOSE_ALBUM,
    EXIT_LIBRARY
}

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
