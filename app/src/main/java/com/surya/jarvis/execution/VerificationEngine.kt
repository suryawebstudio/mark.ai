package com.surya.jarvis.execution

import com.surya.jarvis.accessibility.UnifiedScreen
import com.surya.jarvis.accessibility.SemanticElement
import com.surya.jarvis.knowledge.AppKnowledge
import kotlinx.coroutines.delay
import kotlin.math.abs

class VerificationEngine(
    private val screenObserver: ScreenObserver
) {

    suspend fun verify(
        step: ActionStep,
        previousScreen: UnifiedScreen?,
        appKnowledge: AppKnowledge?
    ): VerificationResult {
        val newScreen = screenObserver.inspectSemantic()
            ?: return VerificationResult.failed("Cannot inspect screen for verification")

        return when (step.verification.type) {
            VerificationType.ELEMENT_PRESENT -> verifyElementPresent(step, newScreen)
            VerificationType.ELEMENT_GONE -> verifyElementGone(step, newScreen)
            VerificationType.TEXT_CONTAINS -> verifyTextContains(step, newScreen)
            VerificationType.STATE_CHANGED -> verifyStateChanged(step, previousScreen, newScreen)
            VerificationType.SCREEN_CHANGE -> verifyScreenChange(step, previousScreen, newScreen)
            VerificationType.GLOBAL_STATE -> verifyGlobalState(step, newScreen)
            VerificationType.TREE_DIFF -> verifyTreeDiff(step, previousScreen, newScreen)
            else -> VerificationResult.passed()
        }
    }

    private suspend fun verifyElementPresent(step: ActionStep, screen: UnifiedScreen): VerificationResult {
        val targetRole = step.verification.params["targetRole"]
            ?: return VerificationResult.failed("No targetRole specified")
        val timeoutMs = step.verification.params["timeoutMs"]?.toLong() ?: 3000L

        val found = waitForCondition(timeoutMs) { current ->
            current.elements.any { element ->
                matchesRole(element, targetRole) ||
                element.text.contains(targetRole, ignoreCase = true) ||
                element.description.contains(targetRole, ignoreCase = true)
            }
        }

        return if (found) {
            VerificationResult.passed()
        } else {
            VerificationResult.failed("Element '$targetRole' not found within ${timeoutMs}ms")
        }
    }

    private suspend fun verifyElementGone(step: ActionStep, screen: UnifiedScreen): VerificationResult {
        val targetRole = step.verification.params["targetRole"]
            ?: return VerificationResult.failed("No targetRole specified")
        val timeoutMs = step.verification.params["timeoutMs"]?.toLong() ?: 3000L

        val gone = waitForCondition(timeoutMs) {
            screen.elements.none { element ->
                matchesRole(element, targetRole) ||
                element.text.contains(targetRole, ignoreCase = true)
            }
        }

        return if (gone) {
            VerificationResult.passed()
        } else {
            VerificationResult.failed("Element '$targetRole' still present after ${timeoutMs}ms")
        }
    }

    private fun verifyTextContains(step: ActionStep, screen: UnifiedScreen): VerificationResult {
        val expectedText = step.verification.params["expectedText"]
            ?: step.verification.params["expected"]
            ?: return VerificationResult.failed("No expected text specified")

        val targetRef = step.verification.params["targetRef"]

        val hasText = if (targetRef != null) {
            screen.findElementByRef(targetRef)?.text?.contains(expectedText, ignoreCase = true) == true
        } else {
            screen.elements.any { it.text.contains(expectedText, ignoreCase = true) }
        }

        return if (hasText) {
            VerificationResult.passed()
        } else {
            VerificationResult.failed("Expected text '$expectedText' not found")
        }
    }

    private fun verifyStateChanged(
        step: ActionStep,
        before: UnifiedScreen?,
        after: UnifiedScreen
    ): VerificationResult {
        val targetRef = step.ref ?: step.verification.params["targetRef"]
            ?: return VerificationResult.failed("No target ref for state verification")

        val expectedState = step.verification.params["expectedState"]
            ?: return VerificationResult.failed("No expectedState specified")

        val beforeElement = before?.findElementByRef(targetRef)
        val afterElement = after.findElementByRef(targetRef)

        if (beforeElement == null || afterElement == null) {
            return VerificationResult.failed("Target element not found for state comparison")
        }

        val beforeState = beforeElement.states.contains(expectedState)
        val afterState = afterElement.states.contains(expectedState)

        return if (beforeState != afterState) {
            VerificationResult.passed()
        } else {
            VerificationResult.failed("State '$expectedState' did not change (was $beforeState)")
        }
    }

    private fun verifyScreenChange(
        step: ActionStep,
        before: UnifiedScreen?,
        after: UnifiedScreen
    ): VerificationResult {
        if (before == null) {
            return VerificationResult.passed()
        }

        val packageChanged = before.packageName != after.packageName
        val activityChanged = before.activityName != after.activityName

        return if (packageChanged || activityChanged) {
            VerificationResult.passed()
        } else {
            VerificationResult.failed("Screen did not change (package/activity same)")
        }
    }

    private fun verifyGlobalState(step: ActionStep, screen: UnifiedScreen): VerificationResult {
        val expectedPackage = step.verification.params["expectedPackage"]
        val expectedAction = step.verification.params["expectedAction"]

        return when {
            expectedPackage != null && screen.packageName != expectedPackage -> {
                VerificationResult.failed("Expected package '$expectedPackage', got '${screen.packageName}'")
            }
            expectedAction != null -> {
                // Check global action result via screen class
                val screenClass = classifyScreen(screen)
                val matches = when (expectedAction) {
                    "back" -> screenClass != ScreenClass.HOME // Not home means we went back
                    "home" -> screenClass == ScreenClass.HOME
                    "recents" -> screen.packageName == "com.android.systemui"
                    else -> true
                }
                if (matches) VerificationResult.passed() else VerificationResult.failed("Global action '$expectedAction' did not produce expected state")
            }
            else -> VerificationResult.passed()
        }
    }

    private fun verifyTreeDiff(
        step: ActionStep,
        before: UnifiedScreen?,
        after: UnifiedScreen
    ): VerificationResult {
        if (before == null) return VerificationResult.passed()

        // Check for significant changes in tree structure
        val beforeCount = before.elements.size
        val afterCount = after.elements.size
        val countChanged = abs(beforeCount - afterCount) > 3

        // Check if specific target element state changed
        val targetRef = step.ref
        val targetChanged = targetRef?.let { ref ->
            val beforeEl = before.findElementByRef(ref)
            val afterEl = after.findElementByRef(ref)
            beforeEl != afterEl || beforeEl?.text != afterEl?.text || beforeEl?.states != afterEl?.states
        } ?: false

        return if (countChanged || targetChanged) {
            VerificationResult.passed()
        } else {
            VerificationResult.failed("No significant tree changes detected")
        }
    }

    private fun matchesRole(element: SemanticElement, rolePattern: String): Boolean {
        return element.role.equals(rolePattern, ignoreCase = true) ||
               element.role.contains(rolePattern, ignoreCase = true) ||
               rolePattern.contains(element.role, ignoreCase = true)
    }

    private suspend fun waitForCondition(timeoutMs: Long, condition: (UnifiedScreen) -> Boolean): Boolean {
        val startTime = System.currentTimeMillis()
        while (System.currentTimeMillis() - startTime < timeoutMs) {
            val current = screenObserver.inspectSemantic()
            if (current != null && condition(current)) return true
            delay(200)
        }
        return false
    }

    private fun classifyScreen(screen: UnifiedScreen): ScreenClass {
        if (screen.elements.any { it.role == "AlertDialog" || it.role.contains("Dialog") }) {
            return ScreenClass.DIALOG
        }
        if (screen.elements.any { it.role.contains("ProgressBar") || it.text.lowercase().contains("loading") }) {
            return ScreenClass.LOADING
        }
        if (screen.elements.any { it.editable }) {
            return ScreenClass.FORM
        }
        if (screen.elements.any { it.scrollable && it.childrenRefs.size > 3 }) {
            return ScreenClass.LIST
        }
        if (screen.packageName == screen.launcherPackageName) {
            return ScreenClass.HOME
        }
        return ScreenClass.DETAIL
    }
}

enum class VerificationType {
    ELEMENT_PRESENT,
    ELEMENT_GONE,
    TEXT_CONTAINS,
    STATE_CHANGED,
    SCREEN_CHANGE,
    GLOBAL_STATE,
    TREE_DIFF
}

data class VerificationResult(
    val passed: Boolean,
    val message: String = "",
    val taskComplete: Boolean = false
) {
    companion object {
        fun passed(message: String = "") = VerificationResult(true, message)
        fun failed(message: String) = VerificationResult(false, message)
    }
}

enum class ScreenClass {
    HOME, LIST, DETAIL, FORM, DIALOG, LOADING, UNKNOWN
}