package com.flactify.viewmodel

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerViewModelLifecycleTest {
    @Test
    fun `repeated initialization is accepted only once`() {
        val gate = ViewModelInitializationGate()

        assertTrue(gate.begin())
        assertFalse(gate.begin())
        assertFalse(gate.begin())
    }
}
