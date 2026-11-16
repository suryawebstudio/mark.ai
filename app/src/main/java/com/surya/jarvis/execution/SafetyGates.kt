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
        val combinedText = "${
            step.action
        } ${
            step.target
        } ${step.params.values.joinToString(" ")}".lowercase()
        
        return sensitiveActionPatterns.any { combinedText.contains(it) } ||
               sensitiveTargetPatterns.any { combinedText.contains(it) } ||
               sensitiveTextPatterns.any { combinedText.contains(it) }
    }
    
    fun getConfirmationPrompt(step: ActionStep): String {
        val actionDesc = describeAction(step)
        return "This will $actionDesc. Confirm?"
    }
    
    private fun describeAction(step: ActionStep): String {
        val target = step.target.lowercase()
        return when {
            target.contains("send") || target.contains("message") -> "send a message"
            target.contains("delete") || target.contains("remove") -> "delete content"
            target.contains("call") -> "make a phone call"
            target.contains("pay") || target.contains("purchase") -> "make a payment"
            target.contains("share") -> "share content"
            target.contains("post") || target.contains("publish") -> "post publicly"
            target.contains("install") -> "install an app"
            target.contains("uninstall") -> "uninstall an app"
            target.contains("reset") || target.contains("format") || target.contains("wipe") -> "reset system settings"
            target.contains("password") || target.contains("pin") || target.contains("otp") -> "enter authentication credentials"
            else -> "tap ${step.target}"
        }
    }
    
    fun validateSensitiveAction(step: ActionStep): SensitiveActionValidation {
        if (!requiresConfirmation(step)) {
            return SensitiveActionValidation.Allowed()
        }
        return SensitiveActionValidation.ConfirmationRequired(getConfirmationPrompt(step))
    }
}

sealed class SensitiveActionValidation {
    data class Allowed() : SensitiveActionValidation()
    data class ConfirmationRequired(val prompt: String) : SensitiveActionValidation()
    data class Blocked(val reason: String) : SensitiveActionValidation()
}