package com.flactify.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Test

class M3uSafResolverTest {
    private val files = listOf(
        M3uGrantFile("Music/夜明け.flac", "content://synthetic/one"),
        M3uGrantFile("Music/other.flac", "content://synthetic/two"),
        M3uGrantFile("Dupe/song.flac", "content://synthetic/three"),
        M3uGrantFile("Other/song.flac", "content://synthetic/four")
    )

    @Test
    fun `unicode percent encoding and separators resolve only enumerated tree files`() {
        val result = M3uSafResolver.resolve(
            listOf(
                M3uEntry("Music/%E5%A4%9C%E6%98%8E%E3%81%91.flac"),
                M3uEntry("Music\\other.flac"),
                M3uEntry("../outside.flac"),
                M3uEntry("https://example.test/no-fetch.flac")
            ),
            files
        )

        assertEquals(listOf("content://synthetic/one", "content://synthetic/two"), result.resolvedUris)
        assertEquals(listOf("../outside.flac", "https://example.test/no-fetch.flac"), result.unresolved)
        assertEquals(emptyList<String>(), result.ambiguous)
    }

    @Test
    fun `ambiguous relative paths are never guessed`() {
        val result = M3uSafResolver.resolve(listOf(M3uEntry("song.flac")), files)

        assertEquals(emptyList<String>(), result.resolvedUris)
        assertEquals(emptyList<String>(), result.unresolved)
        assertEquals(listOf("song.flac"), result.ambiguous)
    }

    @Test
    fun `direct content URI resolves only when it belongs to the selected tree`() {
        val result = M3uSafResolver.resolve(
            listOf(M3uEntry("content://synthetic/two"), M3uEntry("content://outside/no")),
            files
        )

        assertEquals(listOf("content://synthetic/two"), result.resolvedUris)
        assertEquals(listOf("content://outside/no"), result.unresolved)
    }

    @Test
    fun `URI schemes are never treated as relative paths`() {
        val result = M3uSafResolver.resolve(
            listOf(M3uEntry("https://host/song.flac"), M3uEntry("file:///Music/song.flac")),
            listOf(M3uGrantFile("https:/host/song.flac", "content://synthetic/lookalike"))
        )

        assertEquals(emptyList<String>(), result.resolvedUris)
        assertEquals(listOf("https://host/song.flac", "file:///Music/song.flac"), result.unresolved)
    }
}
