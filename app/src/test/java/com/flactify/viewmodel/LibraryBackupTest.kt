package com.flactify.viewmodel

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LibraryBackupTest {
    @Test
    fun `backup round trip preserves favorites static and smart playlists and stats`() {
        val backup = LibraryBackup(
            favorites = setOf("content://synthetic/one", "content://synthetic/two"),
            playlists = listOf(
                Playlist("static", "夜明け", listOf("content://synthetic/one")),
                Playlist(
                    "smart", "日本語検索",
                    smartQuery = SmartPlaylistQuery(artistContains = "ｱｰﾃｨｽﾄ", yearContains = "2026")
                )
            ),
            stats = mapOf("content://synthetic/one" to LibraryManager.PlayStats(3, 1, 1234L))
        )

        assertEquals(backup, LibraryBackupCodec.decode(LibraryBackupCodec.encode(backup)))
    }

    @Test
    fun `unsupported versions and malformed backups are rejected`() {
        val encoded = LibraryBackupCodec.encode(LibraryBackup(emptySet(), emptyList(), emptyMap()))
        val wrongVersion = JSONObject(encoded).put("version", 2).toString()

        assertNull(LibraryBackupCodec.decode(wrongVersion))
        assertNull(LibraryBackupCodec.decode("{"))
        assertNull(LibraryBackupCodec.decode(" ".repeat(32 * 1024 * 1024 + 1)))
    }

    @Test
    fun `negative statistics are rejected before restore`() {
        val encoded = LibraryBackupCodec.encode(
            LibraryBackup(
                emptySet(), emptyList(),
                mapOf("content://synthetic/one" to LibraryManager.PlayStats(2, 1, 10L))
            )
        )
        val root = JSONObject(encoded)
        root.getJSONObject("stats").getJSONObject("content://synthetic/one").put("skipCount", -1)

        assertNull(LibraryBackupCodec.decode(root.toString()))
    }
}
