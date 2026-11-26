package com.flactify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MainActivitySplashStateTest {
    @Test
    fun `completed startup advances splash to player`() {
        assertEquals("player", splashCompletionTarget("splash", leaveSplash = true))
    }

    @Test
    fun `completed startup preserves a restored non-splash screen`() {
        assertNull(splashCompletionTarget("library", leaveSplash = true))
        assertNull(splashCompletionTarget("settings", leaveSplash = true))
    }

    @Test
    fun `initializing startup does not leave splash`() {
        assertNull(splashCompletionTarget("splash", leaveSplash = false))
    }
}
