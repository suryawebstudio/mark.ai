package com.surya.jarvis.execution

import com.surya.jarvis.accessibility.RectBounds
import com.surya.jarvis.accessibility.SemanticElement
import com.surya.jarvis.accessibility.UnifiedScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionExecutorTest {
    private val screen = UnifiedScreen(
        snapshotId = "snapshot-1",
        packageName = "com.example",
        activityName = "ExampleActivity",
        timestamp = 1L,
        elements = listOf(
            SemanticElement(
                ref = "@snapshot-1:e1",
                role = "Button",
                text = "Continue",
                description = "",
                nativeId = null,
                bounds = RectBounds(0, 0, 100, 50),
                clickable = true,
                editable = false,
                scrollable = false,
                enabled = true,
                states = setOf("enabled")
            )
        )
    )

    @Test
    fun acceptsTapButLeavesVerificationToLaterLayer() {
        var request: ControlRequest? = null
        val executor = ActionExecutor(
            observer = ScreenObserver { screen },
            control = ActionExecutor.ControlGateway {
                request = it
                true to "Requested tap."
            }
        )

        val result = executor.executeLatest(ActionStep.Tap("@snapshot-1:e1"))

        assertEquals(ExecutionStatus.ACCEPTED_UNVERIFIED, result.status)
        assertTrue(result.accepted)
        assertTrue(result.requiresVerification)
        assertEquals("tap", request?.action)
        assertEquals("snapshot-1", request?.snapshotId)
    }

    @Test
    fun rejectsMissingRefBeforeCallingAccessibilityService() {
        var called = false
        val executor = ActionExecutor(
            observer = ScreenObserver { screen },
            control = ActionExecutor.ControlGateway {
                called = true
                true to "unexpected"
            }
        )

        val result = executor.executeLatest(ActionStep.Tap("@snapshot-1:missing"))

        assertEquals(ExecutionStatus.REJECTED, result.status)
        assertFalse(called)
    }

    @Test
    fun surfacesConfirmationRequestWithoutExecutingIt() {
        var called = false
        val executor = ActionExecutor(
            observer = ScreenObserver { screen },
            control = ActionExecutor.ControlGateway {
                called = true
                true to "unexpected"
            }
        )

        val result = executor.executeLatest(ActionStep.RequestConfirmation("Sending a message"))

        assertEquals(ExecutionStatus.CONFIRMATION_REQUIRED, result.status)
        assertFalse(called)
    }
}
