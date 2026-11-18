package com.surya.jarvis.execution

import com.surya.jarvis.JarvisAccessibilityService
import com.surya.jarvis.accessibility.SemanticElement
import com.surya.jarvis.accessibility.UnifiedScreen

/**
 * Executes planned actions through MARK's existing guarded accessibility API.
 *
 * This class does not retain AccessibilityNodeInfo instances and does not
 * report an action as verified. The accessibility service remains the final
 * enforcement point for stale refs, sensitive actions, and Android failures.
 */
class ActionExecutor(
    private val observer: ScreenObserver,
    private val control: ControlGateway = ControlGateway { request ->
        JarvisAccessibilityService.control(
            snapshotId = request.snapshotId,
            ref = request.ref,
            action = request.action,
            target = request.target,
            text = request.text,
            direction = request.direction,
            confirmed = request.confirmed,
            visualCell = "",
            visualToken = ""
        )
    }
) {
    fun executeLatest(step: ActionStep, confirmed: Boolean = false): ExecutionResult {
        val screen = observer.inspect()
            ?: return ExecutionResult.rejected(step, "No current accessible screen is available.")
        return execute(step, screen, confirmed)
    }

    /**
     * Execute against an explicitly observed snapshot. Passing the snapshot ID
     * through to the service makes stale-screen execution fail closed.
     */
    fun execute(
        step: ActionStep,
        screen: UnifiedScreen,
        confirmed: Boolean = false
    ): ExecutionResult {
        if (step is ActionStep.RequestConfirmation) {
            return ExecutionResult.confirmationRequired(step.reason)
        }

        val request = when (step) {
            is ActionStep.Tap -> buildElementRequest(step, screen, confirmed, "tap")
            is ActionStep.TypeText -> {
                if (step.text.isEmpty()) {
                    return ExecutionResult.rejected(step, "Text input cannot be empty.")
                }
                buildElementRequest(step, screen, confirmed, "type", text = step.text)
            }
            is ActionStep.Scroll -> {
                val ref = step.elementRef
                    ?: return ExecutionResult.rejected(step, "Scroll requires a ref from the current screen snapshot.")
                val element = screen.findElementByRef(ref)
                    ?: return ExecutionResult.rejected(step, "The scroll ref is not present in the current screen snapshot.")
                if (!element.scrollable) {
                    return ExecutionResult.rejected(step, "The selected element is not marked scrollable.")
                }
                ControlRequest(
                    snapshotId = screen.snapshotId,
                    ref = ref,
                    action = "scroll",
                    target = element.label(),
                    direction = step.direction.name.lowercase(),
                    confirmed = confirmed
                )
            }
            is ActionStep.Back -> ControlRequest(
                snapshotId = screen.snapshotId,
                ref = "",
                action = "back",
                confirmed = confirmed
            )
            is ActionStep.RequestConfirmation -> error("Handled above")
        }

        request.invalidReason?.let { reason ->
            return ExecutionResult.rejected(step, reason)
        }

        val safety = SafetyGates.evaluate(step, screen, confirmed)
        when (safety.outcome) {
            SafetyOutcome.ALLOW -> Unit
            SafetyOutcome.REQUIRE_CONFIRMATION ->
                return ExecutionResult.confirmationRequiredFor(step, safety.reason)
            SafetyOutcome.BLOCK ->
                return ExecutionResult.rejected(step, safety.reason)
        }

        val (accepted, message) = runCatching { control.invoke(request) }
            .getOrElse { return ExecutionResult.failed(step, "Accessibility control failed: ${it.message ?: "unknown error"}") }

        return if (accepted) {
            ExecutionResult.acceptedUnverified(step, message)
        } else {
            ExecutionResult.failed(step, message)
        }
    }

    private fun buildElementRequest(
        step: ActionStep,
        screen: UnifiedScreen,
        confirmed: Boolean,
        action: String,
        text: String = ""
    ): ControlRequest {
        val ref = when (step) {
            is ActionStep.Tap -> step.elementRef
            is ActionStep.TypeText -> step.elementRef
            else -> ""
        }
        val element = screen.findElementByRef(ref)
            ?: return ControlRequest.invalid("The action ref is not present in the current screen snapshot.")
        if (!element.enabled) return ControlRequest.invalid("The selected element is disabled.")
        if (action == "tap" && !element.clickable) {
            return ControlRequest.invalid("The selected element is not marked clickable.")
        }
        if (action == "type" && !element.editable) {
            return ControlRequest.invalid("The selected element is not marked editable.")
        }
        return ControlRequest(
            snapshotId = screen.snapshotId,
            ref = ref,
            action = action,
            target = element.label(),
            text = text,
            confirmed = confirmed
        )
    }

    private fun SemanticElement.label(): String = text.ifBlank { description }

    fun interface ControlGateway {
        operator fun invoke(request: ControlRequest): Pair<Boolean, String>
    }
}

data class ControlRequest(
    val snapshotId: String,
    val ref: String,
    val action: String,
    val target: String = "",
    val text: String = "",
    val direction: String = "down",
    val confirmed: Boolean = false,
    val invalidReason: String? = null
) {
    companion object {
        fun invalid(reason: String): ControlRequest =
            ControlRequest(snapshotId = "", ref = "", action = "", invalidReason = reason)
    }
}

enum class ExecutionStatus {
    ACCEPTED_UNVERIFIED,
    CONFIRMATION_REQUIRED,
    REJECTED,
    FAILED
}

data class ExecutionResult(
    val status: ExecutionStatus,
    val message: String,
    val step: ActionStep?
) {
    val accepted: Boolean
        get() = status == ExecutionStatus.ACCEPTED_UNVERIFIED

    val requiresVerification: Boolean
        get() = accepted && step?.verification?.required == true

    companion object {
        fun acceptedUnverified(step: ActionStep, message: String) =
            ExecutionResult(ExecutionStatus.ACCEPTED_UNVERIFIED, message, step)

        fun confirmationRequired(reason: String) =
            ExecutionResult(ExecutionStatus.CONFIRMATION_REQUIRED, reason, ActionStep.RequestConfirmation(reason))

        fun confirmationRequiredFor(step: ActionStep, reason: String) =
            ExecutionResult(ExecutionStatus.CONFIRMATION_REQUIRED, reason, step)

        fun rejected(step: ActionStep, message: String) =
            ExecutionResult(ExecutionStatus.REJECTED, message, step)

        fun failed(step: ActionStep, message: String) =
            ExecutionResult(ExecutionStatus.FAILED, message, step)
    }
}
