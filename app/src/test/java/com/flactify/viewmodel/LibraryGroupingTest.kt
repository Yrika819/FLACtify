package com.flactify.viewmodel

import android.net.Uri
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/**
 * Equivalence coverage for the Phase 2A library grouping.
 *
 * The reference implementation below is a verbatim copy of the inline grouping that used to live in
 * `PlayerViewModel.updateLibraryState` and `PlayerViewModel.refreshTrackAfterEdit`. Every case here
 * asserts that the new `LibraryGrouping.group` produces the same de-duplicated track order, the same
 * group keys in the same display order, and the same track order inside every group. Comparisons
 * look at the iteration order of the keys as well as their contents, because `Map.equals` alone does
 * not observe ordering.
 */
class LibraryGroupingTest {

    private var counter = 0

    private fun uri(value: String = "content://test/${counter++}"): Uri = mockUri(value)

    private fun track(
        title: String,
        artist: String,
        album: String = "不明なアルバム",
        uri: Uri = uri()
    ): TrackData = TrackData(
        title = title, artist = artist, album = album, originalIndex = counter, uri = uri
    )

    @Test
    fun `empty library groups to nothing`() {
        val actual = LibraryGrouping.group(emptyList())
        assertTrue(actual.tracks.isEmpty())
        assertTrue(actual.artistMap.isEmpty())
        assertTrue(actual.albumMap.isEmpty())
        assertEquivalent(emptyList())
    }

    @Test
    fun `single track forms one artist and one album group`() {
        assertEquivalent(listOf(track("Title", "Artist", "Album")))
    }

    @Test
    fun `tracks by the same artist and album keep a single group`() {
        assertEquivalent(
            listOf(
                track("A1", "Artist", "Album"),
                track("A2", "Artist", "Album"),
                track("A3", "Artist", "Album")
            )
        )
    }

    @Test
    fun `case-different artist spellings merge into the first-seen key`() {
        val tracks = listOf(
            track("A1", "the beatles", "Album1"),
            track("A2", "The Beatles", "Album2"),
            track("A3", "THE BEATLES", "Album3"),
            track("B1", "Radiohead", "AlbumA")
        )
        val actual = LibraryGrouping.group(tracks)
        assertEquals(listOf("Radiohead", "the beatles"), actual.artistMap.keys.toList())
        assertEquals(
            listOf("A1", "A2", "A3"),
            actual.artistMap.getValue("the beatles").map { it.title }
        )
        assertEquals(4, actual.albumMap.size)
        assertEquivalent(tracks)
    }

    @Test
    fun `first spelling seen wins even when it is not the alphabetically first`() {
        val tracks = listOf(
            track("A1", "zebra", "Album"),
            track("A2", "ZEBRA", "Album"),
            track("A3", "apple", "Album")
        )
        val actual = LibraryGrouping.group(tracks)
        assertEquals(listOf("apple", "zebra"), actual.artistMap.keys.toList())
        assertEquals(2, actual.artistMap.getValue("zebra").size)
        assertEquivalent(tracks)
    }

    @Test
    fun `identical album names from different artists share one album group`() {
        val tracks = listOf(
            track("A", "Alpha", "Greatest Hits"),
            track("B", "Beta", "Greatest Hits"),
            track("C", "Gamma", "Greatest Hits")
        )
        val actual = LibraryGrouping.group(tracks)
        assertEquals(listOf("Greatest Hits"), actual.albumMap.keys.toList())
        assertEquals(listOf("A", "B", "C"), actual.albumMap.getValue("Greatest Hits").map { it.title })
        assertEquals(3, actual.artistMap.size)
        assertEquivalent(tracks)
    }

    @Test
    fun `empty artist and album strings are grouped as empty keys`() {
        val tracks = listOf(
            track("A", "", ""),
            track("B", "", ""),
            track("C", "Known", "Known")
        )
        val actual = LibraryGrouping.group(tracks)
        assertTrue(actual.artistMap.keys.contains(""))
        assertEquals(listOf("A", "B"), actual.artistMap.getValue("").map { it.title })
        assertEquals(listOf("Known"), actual.artistMap.keys.filter { it.isNotEmpty() })
        assertEquivalent(tracks)
    }

    @Test
    fun `whitespace-only differences stay separate groups`() {
        val tracks = listOf(
            track("A", "Artist ", "Album "),
            track("B", "Artist", "Album"),
            track("C", " Artist", " Album")
        )
        val actual = LibraryGrouping.group(tracks)
        assertEquals(3, actual.artistMap.size)
        assertEquals(3, actual.albumMap.size)
        assertEquivalent(tracks)
    }

    @Test
    fun `duplicate URIs collapse to the first occurrence`() {
        val shared = uri("content://test/duplicate")
        val tracks = listOf(
            track("First", "Artist", "Album", shared),
            track("Second", "Other", "Other", shared),
            track("Third", "Third", "Third", uri("content://test/unique"))
        )
        val actual = LibraryGrouping.group(tracks)
        assertEquals(listOf("First", "Third"), actual.tracks.map { it.title })
        assertEquals(
            1,
            actual.artistMap.values.sumOf { group -> group.count { it.uri.toString() == "content://test/duplicate" } }
        )
        assertEquals(
            1,
            actual.albumMap.values.sumOf { group -> group.count { it.uri.toString() == "content://test/duplicate" } }
        )
        assertEquivalent(tracks)
    }

    @Test
    fun `an object repeated twice still only contributes one track`() {
        val shared = uri("content://test/shared")
        val repeated = track("Dup", "DupArtist", "DupAlbum", shared)
        val tracks = listOf(repeated, repeated, track("Z", "ZArtist", "ZAlbum"))
        val actual = LibraryGrouping.group(tracks)
        assertEquals(listOf("Dup", "Z"), actual.tracks.map { it.title })
        assertEquals(listOf("DupArtist", "ZArtist"), actual.artistMap.keys.toList())
        assertEquivalent(tracks)
    }

    @Test
    fun `unknown artist and album fallbacks behave like any other value`() {
        val tracks = listOf(
            track("A", "不明なアーティスト", "不明なアルバム"),
            track("B", "不明なアーティスト", "別のアルバム"),
            track("C", "KNOWN", "不明なアルバム")
        )
        val actual = LibraryGrouping.group(tracks)
        assertEquals(listOf("A", "B"), actual.artistMap.getValue("不明なアーティスト").map { it.title })
        assertEquals(listOf("A", "C"), actual.albumMap.getValue("不明なアルバム").map { it.title })
        assertEquivalent(tracks)
    }

    @Test
    fun `unicode case folding of sigma, long s and dotted I matches the reference`() {
        val tracks = listOf(
            track("A", "Σigma", "Ωmega"),
            track("B", "σigma", "ωmega"),
            track("C", "ςigma", "ΩMEGA"),
            track("D", "ſinger", "Ǆung"),
            track("E", "Singer", "ǆung"),
            track("F", "İstanbul", "ǅad"),
            track("G", "istanbul", "ǆAD"),
            track("H", "羽家", "Ψ")
        )
        val actual = LibraryGrouping.group(tracks)
        assertEquals(4, actual.artistMap.size)
        assertEquals(listOf("A", "B", "C"), actual.artistMap.getValue("Σigma").map { it.title })
        assertEquals(listOf("D", "E"), actual.artistMap.getValue("ſinger").map { it.title })
        assertEquals(listOf("F", "G"), actual.artistMap.getValue("İstanbul").map { it.title })
        assertEquals(listOf("İstanbul", "ſinger", "Σigma", "羽家"), actual.artistMap.keys.toList())
        assertEquals(4, actual.albumMap.size)
        assertEquivalent(tracks)
    }

    @Test
    fun `Cyrillic and Latin look-alike names stay separate groups`() {
        val tracks = listOf(
            track("A", "Artist", "Альбом"),   // Cyrillic Альбом
            track("B", "Artist", "АЛЬБОМ"),
            track("C", "Artist", "Album")     // Latin Album
        )
        val actual = LibraryGrouping.group(tracks)
        assertTrue(actual.albumMap.keys.contains("Альбом"))
        assertTrue(actual.albumMap.keys.contains("Album"))
        assertEquals(2, actual.albumMap.size)
        assertEquivalent(tracks)
    }

    @Test
    fun `supplementary plane code units group with the reference`() {
        // Deseret capitals vs smalls stay separate under the pinned device relation.
        val deseretUpper = "\uD801\uDC00"
        val deseretLower = "\uD801\uDC28"
        val tracks = listOf(
            track("A", "$deseretUpper x", "Album"),
            track("B", "$deseretLower x", "Album"),
            track("C", "${deseretUpper}y", "Album")
        )
        val actual = LibraryGrouping.group(tracks)
        assertEquals(2, actual.artistMap.size)
        assertEquivalent(tracks)
    }

    @Test
    fun `track order inside a group follows input order and is never sorted`() {
        val titles = listOf("z", "y", "x", "w", "v", "u", "t", "s", "r", "q")
        val tracks = titles.map { track(it, "Artist", "Album") }
        val actual = LibraryGrouping.group(tracks)
        assertEquals(1, actual.artistMap.size)
        assertEquals(titles, actual.artistMap.getValue("Artist").map { it.title })
        assertEquals(titles, actual.albumMap.getValue("Album").map { it.title })
        assertEquivalent(tracks)
    }

    @Test
    fun `reordering the input changes which spelling becomes the display key`() {
        val first = listOf(
            track("A", "Alpha", "Album"),
            track("B", "ALPHA", "Album")
        )
        assertEquals(listOf("Alpha"), LibraryGrouping.group(first).artistMap.keys.toList())
        assertEquals(
            listOf("ALPHA"),
            LibraryGrouping.group(first.reversed()).artistMap.keys.toList()
        )
        assertEquivalent(first.reversed())
    }

    @Test
    fun `a few distinct artists and albums across many tracks match the reference`() {
        val tracks = (0 until 600).map { index ->
            val group = index % 5
            track("Song $index", "Artist $group", "Album $group")
        }
        val actual = LibraryGrouping.group(tracks)
        assertEquals(5, actual.artistMap.size)
        assertEquals(5, actual.albumMap.size)
        assertEquals(600, actual.tracks.size)
        assertEquivalent(tracks)
    }

    @Test
    fun `every track having its own artist still matches the reference`() {
        val tracks = (0 until 300).map { index ->
            track("Song $index", "Artist $index", "Album $index")
        }
        val actual = LibraryGrouping.group(tracks)
        assertEquals(300, actual.artistMap.size)
        assertEquals(300, actual.albumMap.size)
        assertEquals(
            (0 until 300).map { "Album $it" }.sortedBy { it.lowercase() },
            actual.albumMap.keys.toList()
        )
        assertEquivalent(tracks)
    }

    @Test
    fun `tag edit renaming an artist into an existing group matches the reference`() {
        val tracks = listOf(
            track("A1", "Beta", "Album B"),
            track("A2", "alpha", "Album A"),
            track("A3", "ALPHA", "Album A2"),
            track("A4", "gamma", "Album G")
        )
        val before = LibraryGrouping.group(tracks)
        assertEquals(listOf("alpha", "Beta", "gamma"), before.artistMap.keys.toList())

        // The tag-edit path rebuilds groups from the flatten of the current artist map, so the order
        // it sees is the sorted display order, not the original scan order.
        val afterEdit = before.artistMap.values.flatten().map { track ->
            if (track.title == "A4") track.copy(artist = "ALPHA") else track
        }
        val actual = LibraryGrouping.group(afterEdit)
        assertEquals(listOf("alpha", "Beta"), actual.artistMap.keys.toList())
        assertEquals(listOf("A2", "A3", "A4"), actual.artistMap.getValue("alpha").map { it.title })
        assertEquivalent(afterEdit)
    }

    @Test
    fun `tag edit renaming only an album merges into the existing group`() {
        val tracks = listOf(
            track("A1", "Alpha", "Album One"),
            track("A2", "Alpha", "ALBUM ONE"),
            track("A3", "Alpha", "Other")
        )
        val edited = tracks.map { if (it.title == "A3") it.copy(album = "album one") else it }
        val actual = LibraryGrouping.group(edited)
        assertEquals(1, actual.artistMap.size)
        assertEquals(listOf("Album One"), actual.albumMap.keys.toList())
        assertEquals(listOf("A1", "A2", "A3"), actual.albumMap.getValue("Album One").map { it.title })
        assertEquivalent(edited)
    }

    @Test
    fun `randomised libraries with a fixed seed match the reference`() {
        val random = Random(20260511L)
        val artists = listOf(
            "Artist", "artist", "ARTIST", "Artist ", "", "Σ", "σ", "ς", "ſ", "S",
            "İ", "i", "羽", "Beta", "beta", "BETA"
        )
        val albums = listOf(
            "Album", "ALBUM", "album", "", "Unknown", "不明なアルバム", "語", "Ω", "ω", "X"
        )
        repeat(200) {
            val size = random.nextInt(40)
            val tracks = (0 until size).map {
                track(
                    title = "T$it",
                    artist = artists[random.nextInt(artists.size)],
                    album = albums[random.nextInt(albums.size)]
                )
            }
            assertEquivalent(tracks)
        }
    }

    @Test
    fun `randomised many-distinct libraries with a fixed seed match the reference`() {
        val random = Random(987654321L)
        repeat(20) {
            val size = 200 + random.nextInt(200)
            val tracks = (0 until size).map {
                track(
                    title = "T$it",
                    artist = "A$it${random.nextInt(3)}",
                    album = "B$it${random.nextInt(3)}"
                )
            }
            assertEquivalent(tracks)
        }
    }

    @Test
    fun `grouping is a pure function so repeated calls return equal results`() {
        val tracks = listOf(
            track("A", "alpha", "Album"),
            track("B", "ALPHA", "album"),
            track("C", "alpha", "ALBUM")
        )
        assertEquals(LibraryGrouping.group(tracks), LibraryGrouping.group(tracks))
    }

    // ── Reference implementation ──

    /**
     * Verbatim copy of the inline grouping that was removed from PlayerViewModel, including its
     * `toSortedMap(compareBy { it.lowercase() })` display order. The key lookup pins
     * [charBasedEqualsIgnoreCase] instead of calling the running JDK's `equals(ignoreCase = true)`
     * directly: both are identical on the BMP and on JDK 11 and older, but newer desktop JDKs
     * (17+) merge some supplementary-plane case pairs that the Android runtime keeps separate, and
     * this reference must describe the device behaviour the optimisation promised to preserve.
     */
    private fun legacyGroup(tracks: List<TrackData>): LegacyGroups {
        val distinctTracks = tracks.distinctBy { it.uri.toString() }
        val tempArtistMap = mutableMapOf<String, MutableList<TrackData>>()
        val tempAlbumMap = mutableMapOf<String, MutableList<TrackData>>()

        distinctTracks.forEach { track ->
            val artistKey = track.artist
            val albumKey = track.album

            val existingArtistKey = tempArtistMap.keys.find { charBasedEqualsIgnoreCase(it, artistKey) }
            tempArtistMap.getOrPut(existingArtistKey ?: artistKey) { mutableListOf() }.add(track)

            val existingAlbumKey = tempAlbumMap.keys.find { charBasedEqualsIgnoreCase(it, albumKey) }
            tempAlbumMap.getOrPut(existingAlbumKey ?: albumKey) { mutableListOf() }.add(track)
        }

        return LegacyGroups(
            tracks = distinctTracks,
            artistMap = tempArtistMap.toSortedMap(compareBy { it.lowercase() }),
            albumMap = tempAlbumMap.toSortedMap(compareBy { it.lowercase() })
        )
    }

    private data class LegacyGroups(
        val tracks: List<TrackData>,
        val artistMap: Map<String, List<TrackData>>,
        val albumMap: Map<String, List<TrackData>>
    )

    private fun assertEquivalent(tracks: List<TrackData>) {
        val expected = legacyGroup(tracks)
        val actual = LibraryGrouping.group(tracks)

        assertEquals("track order", expected.tracks.map { it.uri.toString() }, actual.tracks.map { it.uri.toString() })
        assertEquals("artist key order", expected.artistMap.keys.toList(), actual.artistMap.keys.toList())
        assertEquals("album key order", expected.albumMap.keys.toList(), actual.albumMap.keys.toList())
        assertEquals("artist key set", expected.artistMap.keys, actual.artistMap.keys)
        assertEquals("album key set", expected.albumMap.keys, actual.albumMap.keys)

        expected.artistMap.forEach { (key, value) ->
            assertEquals(
                "artist group '$key' contents",
                value.map { it.uri.toString() },
                actual.artistMap.getValue(key).map { it.uri.toString() }
            )
        }
        expected.albumMap.forEach { (key, value) ->
            assertEquals(
                "album group '$key' contents",
                value.map { it.uri.toString() },
                actual.albumMap.getValue(key).map { it.uri.toString() }
            )
        }
        assertEquals(
            "grouped track count",
            expected.artistMap.values.sumOf { it.size },
            actual.artistMap.values.sumOf { it.size }
        )
        assertFalse(actual.artistMap.values.any { it.isEmpty() })
        assertFalse(actual.albumMap.values.any { it.isEmpty() })
    }

    private fun mockUri(value: String): Uri = mockk<Uri>(relaxed = true).also { mock ->
        every { mock.toString() } returns value
        every { mock.hashCode() } returns value.hashCode()
        every { mock.equals(any()) } answers { firstArg<Uri>() === mock }
    }
}
