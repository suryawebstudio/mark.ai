package com.surya.jarvis.execution

import com.surya.jarvis.accessibility.RectBounds
import com.surya.jarvis.accessibility.SemanticElement
import com.surya.jarvis.accessibility.UnifiedScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SafetyGatesTest {
    @Test
    fun elevatedDeleteRequiresConfirmation() {
        val screen = screenWith("Delete message", clickable = true)

        val decision = SafetyGates.evaluate(ActionStep.Tap("@snapshot:e1"), screen, confirmed = false)

        assertEquals(RiskLevel.ELEVATED, decision.risk)
        assertEquals(SafetyOutcome.REQUIRE_CONFIRMATION, decision.outcome)
    }

    @Test
    fun confirmedElevatedActionIsAllowedButStillNeedsVerification() {
        val screen = screenWith("Delete message", clickable = true)

        val decision = SafetyGates.evaluate(ActionStep.Tap("@snapshot:e1"), screen, confirmed = true)

        assertEquals(SafetyOutcome.ALLOW, decision.outcome)
        assertTrue(decision.risk == RiskLevel.ELEVATED)
    }

    @Test
    fun protectedCredentialUiIsBlockedEvenWhenConfirmed() {
        val screen = screenWith("Password", editable = true)

        val decision = SafetyGates.evaluate(
            ActionStep.TypeText("@snapshot:e1", "secret"),
            screen,
            confirmed = true
        )

        assertEquals(RiskLevel.BLOCKED, decision.risk)
        assertEquals(SafetyOutcome.BLOCK, decision.outcome)
    }

    @Test
    fun executorDoesNotCallGatewayBeforeElevatedConfirmation() {
        val screen = screenWith("Send message", clickable = true)
        var called = false
        val executor = ActionExecutor(
            observer = ScreenObserver { screen },
            control = ActionExecutor.ControlGateway {
                called = true
                true to "unexpected"
            }
        )

        val result = executor.executeLatest(ActionStep.Tap("@snapshot:e1"))

        assertEquals(ExecutionStatus.CONFIRMATION_REQUIRED, result.status)
        assertFalse(called)
    }

    private fun screenWith(
        label: String,
        clickable: Boolean = false,
        editable: Boolean = false
    ) = UnifiedScreen(
        snapshotId = "snapshot",
        packageName = "com.example",
        activityName = "ExampleActivity",
        timestamp = 1L,
        elements = listOf(
            SemanticElement(
                ref = "@snapshot:e1",
                role = if (editable) "EditText" else "Button",
                text = label,
                description = "",
                nativeId = null,
                bounds = RectBounds(0, 0, 100, 50),
                clickable = clickable,
                editable = editable,
                scrollable = false,
                enabled = true,
                states = setOf("enabled")
            )
        )
    )
}
