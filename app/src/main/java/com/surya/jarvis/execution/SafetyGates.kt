package com.surya.jarvis.execution

class SafetyGates {
    
    private val sensitiveActionPatterns = listOf(
        "send", "submit", "purchase", "buy", "pay", "delete", "remove",
        "call", "share", "post", "publish", "transfer", "confirm", "install",
        "uninstall", "reset", "format", "wipe", "block", "report", "grant"
    )
    
    private val sensitiveTargetPatterns = listOf(
        "send", "submit", "purchase", "buy", "pay", "delete", "remove",
        "call", "share", "post", "publish", "transfer", "install",
        "password", "payment", "card", "pin", "otp", "auth", "security"
    )
    
    private val sensitiveTextPatterns = listOf(
        "send", "submit", "purchase", "buy", "pay", "delete", "remove",
        "call", "share", "post", "publish", "transfer", "confirm", "install"
    )
    
    fun requiresConfirmation(step: ActionStep): Boolean {
        val combinedText = extractCombinedText(step).lowercase()
        
        return sensitiveActionPatterns.any { combinedText.contains(it) } ||
               sensitiveTargetPatterns.any { combinedText.contains(it) } ||
               sensitiveTextPatterns.any { combinedText.contains(it) }
    }
    
    fun getConfirmationPrompt(step: ActionStep): String {
        val actionDesc = describeAction(step)
        return "This will $actionDesc. Confirm?"
    }
    
    private fun extractCombinedText(step: ActionStep): String {
        val verification = step.verification
        return when (step) {
            is ActionStep.Tap -> "${step.target} ${verification.params.values.joinToString(" ")}"
            is ActionStep.Type -> "${step.target} ${step.text} ${verification.params.values.joinToString(" ")}"
            is ActionStep.Scroll -> "${step.target} ${step.direction} ${verification.params.values.joinToString(" ")}"
            is ActionStep.LongPress -> "${step.target} ${verification.params.values.joinToString(" ")}"
            is ActionStep.Swipe -> "${step.target} ${step.direction} ${verification.params.values.joinToString(" ")}"
            is ActionStep.Drag -> "${step.target} ${verification.params.values.joinToString(" ")}"
            is ActionStep.DoubleTap -> "${step.target} ${verification.params.values.joinToString(" ")}"
            is ActionStep.PinchZoom -> "${step.target} ${verification.params.values.joinToString(" ")}"
            is ActionStep.GlobalAction -> "${step.target} ${verification.params.values.joinToString(" ")}"
            is ActionStep.IntentAction -> "${step.target} ${verification.params.values.joinToString(" ")}"
            is ActionStep.Wait -> "${step.target} ${verification.params.values.joinToString(" ")}"
            is ActionStep.VerifyScreen -> "${step.target} ${step.expectedElements.joinToString(" ")} ${verification.params.values.joinToString(" ")}"
        }
    }
    
    private fun describeAction(step: ActionStep): String {
        val targetText = step.target.lowercase()
        return when {
            targetText.contains("send") || targetText.contains("message") -> "send a message"
            targetText.contains("delete") || targetText.contains("remove") -> "delete content"
            targetText.contains("call") -> "make a phone call"
            targetText.contains("pay") || targetText.contains("purchase") -> "make a payment"
            targetText.contains("share") -> "share content"
            targetText.contains("post") || targetText.contains("publish") -> "post publicly"
            targetText.contains("install") -> "install an app"
            targetText.contains("uninstall") -> "uninstall an app"
            targetText.contains("reset") || targetText.contains("format") || targetText.contains("wipe") -> "reset system settings"
            targetText.contains("password") || targetText.contains("pin") || targetText.contains("otp") -> "enter authentication credentials"
            else -> "tap ${step.target}"
        }
    }
    
    fun validateSensitiveAction(step: ActionStep): SensitiveActionValidation {
        if (!requiresConfirmation(step)) {
            return SensitiveActionValidation.Allowed
        }
        return SensitiveActionValidation.ConfirmationRequired(getConfirmationPrompt(step))
    }
    
    sealed class SensitiveActionValidation {
        object Allowed : SensitiveActionValidation()
        data class ConfirmationRequired(val prompt: String) : SensitiveActionValidation()
        data class Blocked(val reason: String) : SensitiveActionValidation()
    }
}