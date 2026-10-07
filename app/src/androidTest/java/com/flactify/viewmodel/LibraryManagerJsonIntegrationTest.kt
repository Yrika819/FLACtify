package com.flactify.viewmodel

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LibraryManagerJsonIntegrationTest {
    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val preferences by lazy {
        context.getSharedPreferences("flactify_library", Context.MODE_PRIVATE)
    }
    private var originalStats: String? = null
    private var hadOriginalStats = false

    @Before
    fun saveExistingStats() {
        hadOriginalStats = preferences.contains("stats")
        originalStats = preferences.getString("stats", null)
        preferences.edit().remove("stats").commit()
    }

    @After
    fun restoreExistingStats() {
        val editor = preferences.edit()
        if (hadOriginalStats) editor.putString("stats", originalStats)
        else editor.remove("stats")
        editor.commit()
    }

    @Test
    fun frameworkJsonAdapterParsesValidEntriesAndSkipsMalformedSiblings() {
        val stored = """{"content://one":{"pc":3,"sc":-2,"lp":9},"broken":[]}"""
        preferences.edit().putString("stats", stored).commit()

        val manager = LibraryManager(context)
        val stats = manager.getAllStats()

        assertEquals(
            LibraryManager.PlayStats(playCount = 3, skipCount = 0, lastPlayed = 9L),
            stats["content://one"]
        )
        assertTrue(!stats.containsKey("broken"))

        manager.recordPlay("content://new")
        assertEquals(
            "malformed siblings must prevent rewriting stored state",
            stored,
            preferences.getString("stats", null)
        )
    }

    @Test
    fun malformedStoredJsonIsNotOverwrittenByStatsUpdates() {
        val stored = "not-json"
        preferences.edit().putString("stats", stored).commit()

        val manager = LibraryManager(context)
        assertTrue(manager.getAllStats().isEmpty())
        manager.recordSkip("content://new")

        assertEquals(stored, preferences.getString("stats", null))
    }
}
