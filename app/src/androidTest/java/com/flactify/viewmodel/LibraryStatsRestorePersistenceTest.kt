package com.flactify.viewmodel

import android.content.Context
import android.content.SharedPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

@RunWith(AndroidJUnit4::class)
class LibraryStatsRestorePersistenceTest {
    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val preferences: SharedPreferences
        get() = context.getSharedPreferences("flactify_library", Context.MODE_PRIVATE)
    private val journalFile: File
        get() = File(context.noBackupFilesDir, "library-restore.pending")
    private var original: Map<String, Any?> = emptyMap()
    private val keys = listOf("favorites", "playlists", "stats")

    @Before
    fun isolatePreferences() {
        original = keys.associateWith { preferences.all[it] }
        preferences.edit().remove("favorites").remove("playlists").remove("stats").commit()
        LibraryBackupJournal(journalFile).clear()
    }

    @After
    fun restorePreferences() {
        val editor = preferences.edit()
        keys.forEach { key ->
            when (val value = original[key]) {
                is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
                is String -> editor.putString(key, value)
                else -> editor.remove(key)
            }
        }
        editor.commit()
        LibraryBackupJournal(journalFile).clear()
    }

    @Test
    fun v252StatsSurviveBackupRestoreManagerRestartAndNextEvents() {
        val one = "content://synthetic/one"
        val two = "content://synthetic/two"
        val fixedTime = 1_725_000_000_123L
        preferences.edit().putString("stats", """{"$one":{"pc":17,"sc":4,"lp":$fixedTime},"$two":{"pc":2,"sc":1,"lp":$fixedTime}}""").commit()

        val source = LibraryManager(context)
        assertEquals(LibraryManager.PlayStats(17, 4, fixedTime), source.getStatsFor(one))
        val backup = source.createBackup()
        val portableRow = JSONObject(backup).getJSONObject("stats").getJSONObject(one)
        assertEquals(17, portableRow.getInt("playCount"))
        assertEquals(4, portableRow.getInt("skipCount"))
        assertEquals(fixedTime, portableRow.getLong("lastPlayed"))

        assertTrue(LibraryManager(context).restoreBackup(backup))
        var manager = LibraryManager(context)
        assertEquals(LibraryManager.PlayStats(17, 4, fixedTime), manager.getStatsFor(one))
        assertEquals(LibraryManager.PlayStats(2, 1, fixedTime), manager.getStatsFor(two))
        manager = LibraryManager(context) // process recreation boundary: reload from SharedPreferences
        manager.recordPlay(one)
        manager.recordSkip(one)
        val afterEvents = manager.getStatsFor(one)
        assertEquals(18, afterEvents.playCount)
        assertEquals(5, afterEvents.skipCount)
        assertTrue(afterEvents.lastPlayed >= fixedTime)
        assertEquals(LibraryManager.PlayStats(2, 1, fixedTime), manager.getStatsFor(two))
        assertEquals(LibraryManager.PlayStats(18, 5, afterEvents.lastPlayed),
            LibraryBackupCodec.decode(manager.createBackup())?.stats?.get(one))
    }

    @Test
    fun mergeAddsOnlyMissingTracksAndReplacementUsesInternalPreferenceCodec() {
        val existing = "content://synthetic/existing"
        val incoming = "content://synthetic/incoming"
        val oldTime = 1_700_000_000_000L
        preferences.edit().putString("stats", """{"$existing":{"pc":17,"sc":4,"lp":$oldTime}}""").commit()
        val manager = LibraryManager(context)
        val incomingBackup = backup(mapOf(
            existing to LibraryManager.PlayStats(99, 99, oldTime + 3),
            incoming to LibraryManager.PlayStats(6, 1, oldTime + 1)
        ))

        assertTrue(manager.mergeBackup(incomingBackup))
        assertEquals(LibraryManager.PlayStats(17, 4, oldTime), manager.getStatsFor(existing))
        assertEquals(LibraryManager.PlayStats(6, 1, oldTime + 1), manager.getStatsFor(incoming))
        val internal = JSONObject(preferences.getString("stats", "{}")!!)
        assertTrue(internal.getJSONObject(existing).has("pc"))
        assertFalse(internal.getJSONObject(existing).has("playCount"))

        assertTrue(manager.restoreBackup(backup(mapOf(incoming to LibraryManager.PlayStats(7, 2, oldTime + 2)))))
        assertEquals(mapOf(incoming to LibraryManager.PlayStats(7, 2, oldTime + 2)), manager.getAllStats())
        assertFalse(manager.getAllStats().containsKey(existing))
    }

    @Test
    fun failedCommitRollbackPreservesOriginalRawStatsAndAllowsReload() {
        val uri = "content://synthetic/one"
        val fixedTime = 1_725_000_000_123L
        val originalRaw = """{"$uri":{"pc":17,"sc":4,"lp":$fixedTime},"unparsed":[]}"""
        preferences.edit().putString("stats", originalRaw).commit()
        val failingOnce = CommitReportsFailure(preferences, failureCount = 1)
        val manager = LibraryManager.withPreferencesForTesting(context, failingOnce)

        assertFalse(manager.restoreBackup(backup(mapOf(uri to LibraryManager.PlayStats(1, 0, fixedTime + 1)))))
        assertEquals(originalRaw, preferences.getString("stats", null))
        assertEquals(originalRaw, LibraryManager(context).let { preferences.getString("stats", null) })
        assertEquals(LibraryManager.PlayStats(17, 4, fixedTime), LibraryManager(context).getStatsFor(uri))
    }

    @Test
    fun failedRollbackLeavesJournalAndNewManagerCanRetryIt() {
        val uri = "content://synthetic/retry-after-rollback"
        val fixedTime = 1_725_000_000_123L
        val originalRaw = """{"$uri":{"pc":17,"sc":4,"lp":$fixedTime}}"""
        val incoming = backup(mapOf(uri to LibraryManager.PlayStats(1, 0, fixedTime + 1)))
        preferences.edit().putString("stats", originalRaw).commit()

        assertFalse(LibraryManager.withPreferencesForTesting(context, CommitReportsFailure(preferences, failureCount = 2))
            .restoreBackup(incoming))
        assertTrue(LibraryBackupJournal(journalFile).read() != null)
        assertEquals(originalRaw, preferences.getString("stats", null))

        val recreated = LibraryManager(context)
        assertTrue(recreated.retryPendingRestore())
        assertEquals(LibraryManager.PlayStats(1, 0, fixedTime + 1), recreated.getStatsFor(uri))
        assertNull(LibraryBackupJournal(journalFile).read())
    }

    @Test
    fun journalRetryRestoresBackupShapedStatsAndReopensAsInternalData() {
        val uri = "content://synthetic/retry"
        val fixedTime = 1_725_000_000_123L
        val pending = backup(mapOf(uri to LibraryManager.PlayStats(17, 4, fixedTime)))
        LibraryBackupJournal(journalFile).stage(pending)

        assertTrue(LibraryManager(context).retryPendingRestore())
        assertEquals(LibraryManager.PlayStats(17, 4, fixedTime), LibraryManager(context).getStatsFor(uri))
        assertNull(LibraryBackupJournal(journalFile).read())
        val stored = JSONObject(preferences.getString("stats", "{}")!!).getJSONObject(uri)
        assertEquals(setOf("pc", "sc", "lp"), stored.keys().asSequence().toSet())
    }

    @Test
    fun accidentalBackupShapedRowsAreRecoveredButConflictsAndMissingKeysArePreserved() {
        val good = "content://synthetic/recoverable"
        val conflict = "content://synthetic/conflict"
        val missing = "content://synthetic/missing"
        val fixedTime = 1_725_000_000_123L
        val raw = """{"$good":{"playCount":17,"skipCount":4,"lastPlayed":$fixedTime},"$conflict":{"pc":1,"sc":0,"lp":1,"playCount":2,"skipCount":0,"lastPlayed":2},"$missing":{"pc":7,"sc":1}}"""
        preferences.edit().putString("stats", raw).commit()
        val manager = LibraryManager(context)

        assertEquals(LibraryManager.PlayStats(17, 4, fixedTime), manager.getStatsFor(good))
        manager.recordPlay(good)
        manager.recordSkip(good)
        assertEquals(raw, preferences.getString("stats", null))
        assertThrows(IllegalStateException::class.java) { manager.createBackup() }
        assertEquals(raw, preferences.getString("stats", null))
        assertFalse(manager.mergeBackup(backup(emptyMap())))

        val recoverableOnly = """{"$good":{"playCount":17,"skipCount":4,"lastPlayed":$fixedTime}}"""
        preferences.edit().putString("stats", recoverableOnly).commit()
        val recovered = LibraryManager(context)
        assertEquals(LibraryManager.PlayStats(17, 4, fixedTime), recovered.getStatsFor(good))
        recovered.recordPlay(good)
        assertEquals(18, recovered.getStatsFor(good).playCount)
        assertTrue(recovered.getStatsFor(good).lastPlayed >= fixedTime)
        val exported = JSONObject(recovered.createBackup()).getJSONObject("stats").getJSONObject(good)
        assertEquals(18, exported.getInt("playCount"))
        assertEquals(4, exported.getInt("skipCount"))
    }

    @Test
    fun mergeRefusesToOverwriteUnparseablePlaylistBytes() {
        val raw = """[{"id":"kept","name":"Kept","trackUris":[],"smartQuery":[]}]"""
        preferences.edit().putString("playlists", raw)
            .putString("stats", "{}")
            .commit()
        val manager = LibraryManager(context)

        assertFalse(manager.mergeBackup(backup(emptyMap())))
        assertEquals(raw, preferences.getString("playlists", null))
    }

    @Test
    fun concurrentPlayAndSkipEventsAcrossManagerInstancesDoNotLoseUpdates() {
        val uri = "content://synthetic/concurrent"
        val workers = 6
        val eventsPerWorker = 30
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(workers)
        try {
            val tasks = (0 until workers).map { worker ->
                pool.submit {
                    val manager = LibraryManager(context)
                    start.await()
                    repeat(eventsPerWorker) {
                        if (worker % 2 == 0) manager.recordPlay(uri) else manager.recordSkip(uri)
                    }
                }
            }
            start.countDown()
            tasks.forEach { it.get() }
        } finally {
            pool.shutdownNow()
        }
        assertEquals(LibraryManager.PlayStats(90, 90, LibraryManager(context).getStatsFor(uri).lastPlayed),
            LibraryManager(context).getStatsFor(uri))
    }

    private fun backup(stats: Map<String, LibraryManager.PlayStats>): String =
        LibraryBackupCodec.encode(LibraryBackup(emptySet(), emptyList(), stats))

    /** Delegates all real storage to Android SharedPreferences, but makes the first commit report failure. */
    private class CommitReportsFailure(
        private val delegate: SharedPreferences,
        private var failureCount: Int
    ) : SharedPreferences by delegate {

        override fun edit(): SharedPreferences.Editor {
            val editor = delegate.edit()
            return object : SharedPreferences.Editor by editor {
                override fun commit(): Boolean {
                    val committed = editor.commit()
                    if (failureCount > 0) {
                        failureCount -= 1
                        return false
                    }
                    return committed
                }
            }
        }
    }
}
