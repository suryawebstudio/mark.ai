package com.surya.jarvis.execution

import com.surya.jarvis.accessibility.UnifiedScreen
import kotlinx.coroutines.delay
import kotlin.math.abs

class RecoveryEngine(
    private val screenObserver: ScreenObserver,
    private val planner: ActionPlanner,
    private val executor: ActionExecutor
) {

    fun classifyAndRecover(
        task: TaskSession,
        step: ActionStep,
        result: ExecutionResult
    ): RecoveryAction {
        val errorType = result.errorType ?: ErrorType.UNKNOWN

        return when (errorType) {
            ErrorType.STALE_REF -> RecoveryAction.ReinspectAndReplan("Screen changed, ref is stale")
            ErrorType.AMBIGUOUS_TARGET -> RecoveryAction.ReinspectAndReplan("Multiple matching elements found")
            ErrorType.DISABLED_TARGET -> RecoveryAction.WaitAndRetry(2000, "Element is disabled, waiting for enable")
            ErrorType.NOT_FOUND -> RecoveryAction.Explore("Element not found, exploring screen")
            ErrorType.NOT_VISIBLE -> RecoveryAction.Explore("Element not visible, scrolling to reveal")
            ErrorType.NOT_EDITABLE -> RecoveryAction.Explore("Element not editable, finding alternative")
            ErrorType.NOT_SCROLLABLE -> RecoveryAction.Explore("Element not scrollable, finding alternative")
            ErrorType.PERMISSION_DIALOG -> RecoveryAction.UserGuidance("Permission dialog shown. Please respond to continue.")
            ErrorType.LOADING_SCREEN -> RecoveryAction.WaitAndRetry(10000, "Loading screen detected, waiting")
            ErrorType.UNEXPECTED_SCREEN -> RecoveryAction.NavigateBackToKnown("Unexpected screen, returning to known state")
            ErrorType.GESTURE_REJECTED -> RecoveryAction.RetryWithAdjustment("Gesture rejected, adjusting coordinates")
            ErrorType.TIMEOUT -> RecoveryAction.WaitAndRetry(5000, "Verification timeout, retrying")
            ErrorType.SECURE_WINDOW -> RecoveryAction.Escalate("Secure window (banking/auth) - cannot interact")
            ErrorType.INTENT_FAILED -> RecoveryAction.Escalate("Intent launch failed")
            ErrorType.TYPE_FAILED -> RecoveryAction.RetryWithAdjustment("Text input failed, retrying")
            ErrorType.SCROLL_FAILED -> RecoveryAction.RetryWithAdjustment("Scroll failed, trying alternative")
            ErrorType.VERIFICATION_FAILED -> RecoveryAction.ReinspectAndReplan("Verification failed, re-planning")
            else -> RecoveryAction.Escalate("Unknown error: $errorType")
        }
    }

    suspend fun executeRecovery(
        action: RecoveryAction,
        task: TaskSession,
        currentScreen: UnifiedScreen?
    ): RecoveryResult {
        return when (action) {
            is RecoveryAction.ReinspectAndReplan -> {
                val newScreen = screenObserver.inspectSemantic()
                val context = task.context ?: return RecoveryResult.Failed("No task context available")
                val newPlan = planner.createPlan(context.withScreen(newScreen!!))
                task.currentPlan = newPlan
                RecoveryResult.Success("Re-planned after: ${action.reason}", newPlan)
            }

            is RecoveryAction.WaitAndRetry -> {
                delay(action.delayMs)
                RecoveryResult.Success("Waited ${action.delayMs}ms, retrying: ${action.reason}", null)
            }

            is RecoveryAction.Explore -> {
                val screen = currentScreen ?: screenObserver.inspectSemantic()!!
                var attempts = 0
                var exploredScreen = screen

                // Try to find a known state by navigating back
                while (attempts < 5) {
                    if (isKnownScreen(exploredScreen, task.targetPackage)) {
                        break
                    }
                    val context = task.context ?: return RecoveryResult.Failed("No task context available")
                    val backResult = executor.execute(
                        ActionStep.GlobalAction(ActionStep.GlobalActionType.BACK, "back", VerificationConfig(VerificationType.GLOBAL_STATE)),
                        exploredScreen
                    )
                    if (!backResult.success) break
                    exploredScreen = screenObserver.inspectSemantic() ?: exploredScreen
                    attempts++
                }

                val context = task.context ?: return RecoveryResult.Failed("No task context available")
                val newPlan = planner.createPlan(context.withScreen(exploredScreen))
                task.currentPlan = newPlan
                RecoveryResult.Success("Explored to known state: ${action.reason}", newPlan)
            }

            is RecoveryAction.UserGuidance -> {
                RecoveryResult.ConfirmationRequired(action.message)
            }

            is RecoveryAction.NavigateBackToKnown -> {
                var attempts = 0
                var screen = currentScreen ?: screenObserver.inspectSemantic()!!

                while (attempts < 5) {
                    if (screen.packageName == task.targetPackage && isKnownScreen(screen, task.targetPackage)) {
                        break
                    }
                    val context = task.context ?: return RecoveryResult.Failed("No task context available")
                    val backResult = executor.execute(
                        ActionStep.GlobalAction(ActionStep.GlobalActionType.BACK, "back", VerificationConfig(VerificationType.GLOBAL_STATE)),
                        screen
                    )
                    if (!backResult.success) break
                    screen = screenObserver.inspectSemantic() ?: screen
                    attempts++
                }

                RecoveryResult.Success("Returned to known screen: ${action.reason}", null)
            }

            is RecoveryAction.RetryWithAdjustment -> {
                RecoveryResult.RetryWithAdjustment("Retrying with adjustment: ${action.reason}")
            }

            is RecoveryAction.Escalate -> {
                task.cancel()
                RecoveryResult.Failed("Recovery failed: ${action.reason}")
            }
        }
    }

    private fun isKnownScreen(screen: UnifiedScreen, targetPackage: String): Boolean {
        return screen.packageName == targetPackage &&
               screen.elements.isNotEmpty() &&
               (screen.elements.any { it.role in setOf("Tab", "BottomNavigationView", "NavigationBarView", "ActionBar", "Toolbar") } ||
                classifyScreen(screen) != ScreenClass.UNKNOWN)
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

sealed class RecoveryAction {
    data class ReinspectAndReplan(val reason: String) : RecoveryAction()
    data class WaitAndRetry(val delayMs: Long, val reason: String) : RecoveryAction()
    data class Explore(val reason: String) : RecoveryAction()
    data class UserGuidance(val message: String) : RecoveryAction()
    data class NavigateBackToKnown(val reason: String) : RecoveryAction()
    data class RetryWithAdjustment(val reason: String) : RecoveryAction()
    data class Escalate(val reason: String) : RecoveryAction()

    companion object {
        fun reinspectAndReplan(reason: String) = ReinspectAndReplan(reason)
        fun waitAndRetry(delayMs: Long, reason: String) = WaitAndRetry(delayMs, reason)
        fun explore(reason: String) = Explore(reason)
        fun userGuidance(message: String) = UserGuidance(message)
        fun navigateBackToKnown(reason: String) = NavigateBackToKnown(reason)
        fun retryWithAdjustment(reason: String) = RetryWithAdjustment(reason)
        fun escalate(reason: String) = Escalate(reason)
    }
}

sealed class RecoveryResult {
    data class Success(val message: String, val newPlan: ActionPlan?) : RecoveryResult()
    data class ConfirmationRequired(val message: String) : RecoveryResult()
    data class RetryWithAdjustment(val message: String) : RecoveryResult()
    data class Failed(val message: String) : RecoveryResult()
}

enum class ErrorType {
    STALE_REF,
    AMBIGUOUS_TARGET,
    DISABLED_TARGET,
    NOT_FOUND,
    NOT_VISIBLE,
    NOT_EDITABLE,
    NOT_SCROLLABLE,
    PERMISSION_DIALOG,
    LOADING_SCREEN,
    UNEXPECTED_SCREEN,
    GESTURE_REJECTED,
    TIMEOUT,
    SECURE_WINDOW,
    INTENT_FAILED,
    TYPE_FAILED,
    SCROLL_FAILED,
    VERIFICATION_FAILED,
    GLOBAL_ACTION_FAILED,
    UNKNOWN;

    fun isRetryable(): Boolean = this in setOf(
        TIMEOUT, LOADING_SCREEN, STALE_REF, DISABLED_TARGET
    )
}