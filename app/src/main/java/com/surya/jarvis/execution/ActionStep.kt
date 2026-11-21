package com.surya.jarvis.execution

import android.content.Intent
import com.surya.jarvis.accessibility.UnifiedScreen
import com.surya.jarvis.knowledge.AppKnowledge

sealed class ActionStep {
    abstract val ref: String?
    abstract val target: String
    abstract val verification: VerificationConfig

    data class Tap(
        override val ref: String,
        override val target: String,
        override val verification: VerificationConfig = VerificationConfig(VerificationType.SCREEN_CHANGE)
    ) : ActionStep()

    data class Type(
        override val ref: String,
        val text: String,
        override val target: String,
        override val verification: VerificationConfig = VerificationConfig(VerificationType.TEXT_CONTAINS)
    ) : ActionStep()

    data class Scroll(
        override val ref: String,
        val direction: String,
        override val target: String,
        override val verification: VerificationConfig = VerificationConfig(VerificationType.ELEMENT_PRESENT)
    ) : ActionStep()

    data class LongPress(
        override val ref: String,
        override val target: String,
        override val verification: VerificationConfig = VerificationConfig(VerificationType.SCREEN_CHANGE)
    ) : ActionStep()

    data class Swipe(
        override val ref: String,
        val direction: String,
        override val target: String,
        override val verification: VerificationConfig = VerificationConfig(VerificationType.SCREEN_CHANGE)
    ) : ActionStep()

    data class Drag(
        override val ref: String,
        val targetX: Float,
        val targetY: Float,
        override val target: String,
        override val verification: VerificationConfig = VerificationConfig(VerificationType.SCREEN_CHANGE)
    ) : ActionStep()

    data class DoubleTap(
        override val ref: String,
        override val target: String,
        override val verification: VerificationConfig = VerificationConfig(VerificationType.SCREEN_CHANGE)
    ) : ActionStep()

    data class PinchZoom(
        override val ref: String,
        val pinchIn: Boolean,
        override val target: String,
        override val verification: VerificationConfig = VerificationConfig(VerificationType.SCREEN_CHANGE)
    ) : ActionStep()

    data class GlobalAction(
        val actionType: GlobalActionType,
        override val target: String,
        override val verification: VerificationConfig = VerificationConfig(VerificationType.GLOBAL_STATE)
    ) : ActionStep() {
        override val ref: String? get() = null
    }

    enum class GlobalActionType { BACK, HOME, RECENTS, NOTIFICATIONS, QUICK_SETTINGS }

    data class IntentAction(
        val intent: Intent,
        override val target: String,
        override val verification: VerificationConfig = VerificationConfig(VerificationType.SCREEN_CHANGE)
    ) : ActionStep() {
        override val ref: String? get() = null
    }

    data class Wait(
        val durationMs: Long,
        override val target: String,
        override val verification: VerificationConfig = VerificationConfig(VerificationType.ELEMENT_PRESENT)
    ) : ActionStep() {
        override val ref: String? get() = null
    }

    data class VerifyScreen(
        val expectedElements: List<String>,
        override val target: String,
        override val verification: VerificationConfig = VerificationConfig(VerificationType.SCREEN_CHANGE)
    ) : ActionStep() {
        override val ref: String? get() = null
    }
}

data class VerificationConfig(
    val type: VerificationType,
    val params: Map<String, String> = emptyMap()
)

data class ActionPlan(
    val steps: List<ActionStep>,
    val requiresConfirmation: Boolean,
    val estimatedDurationMs: Long = 0
)

data class TaskSession(
    val intent: String,
    val targetPackage: String,
    var stepCount: Int = 0,
    var currentStep: ActionStep? = null,
    var lastResult: ExecutionResult? = null,
    var awaitingConfirmation: Boolean = false,
    var confirmationGranted: Boolean = false,
    val executedSteps: MutableList<ExecutedStep> = mutableListOf(),
    var currentPlan: ActionPlan? = null,
    var context: PlanningContext? = null
) {
    fun cancel() {
        awaitingConfirmation = false
    }
}

data class ExecutedStep(
    val step: ActionStep,
    val result: ExecutionResult,
    val verification: VerificationResult
)

data class PlanningContext(
    val userIntent: String,
    val currentScreen: UnifiedScreen,
    val targetApp: AppKnowledge?,
    val taskHistory: List<ExecutedStep>,
    val globalState: GlobalState,
    val safetyConstraints: SafetyConstraints
) {
    fun withScreen(newScreen: UnifiedScreen): PlanningContext {
        return copy(currentScreen = newScreen)
    }
}

data class GlobalState(
    val isAccessibilityEnabled: Boolean,
    val isMicrophoneAvailable: Boolean,
    val currentPackage: String?,
    val currentActivity: String?,
    val screenOn: Boolean,
    val keyboardVisible: Boolean
)

data class SafetyConstraints(
    val requireConfirmationForSensitive: Boolean = true,
    val maxSteps: Int = 24,
    val timeoutMs: Long = 300_000L
)

data class TaskResult(
    val status: String,
    val message: String,
    val stepCount: Int = 0,
    val verified: Boolean = false
) {
    companion object {
        fun success(msg: String, stepCount: Int = 0) = TaskResult("success", msg, stepCount)
        fun failed(msg: String) = TaskResult("failed", msg)
        fun confirmationRequired(msg: String) = TaskResult("confirmation_required", msg)
        fun cancelled(msg: String) = TaskResult("cancelled", msg)
        fun completed(msg: String, stepCount: Int = 0) = TaskResult("completed", msg, stepCount, verified = true)
    }
}