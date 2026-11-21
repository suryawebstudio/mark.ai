package com.surya.jarvis.execution

import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class SafetyGatesTest {
    private val gates = SafetyGates()

    @Test fun consequentialTapRequiresConfirmation() {
        val step = ActionStep.Tap("@s:e1", "Send message")
        assertTrue(gates.requiresConfirmation(step))
    }

    @Test fun ordinaryScrollDoesNotRequireConfirmation() {
        val step = ActionStep.Scroll("@s:e1", "down", "Results")
        assertFalse(gates.requiresConfirmation(step))
    }
}
