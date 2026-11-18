package com.surya.jarvis.execution

import com.surya.jarvis.accessibility.UnifiedScreen
import java.util.Locale

/**
 * Central policy for actions that may affect external systems or protected UI.
 * This gate is intentionally conservative and is not a replacement for the
 * final checks in JarvisAccessibilityService.
 */
object SafetyGates {
    private val elevatedKeywords = setOf(
        "send", "submit", "purchase", "buy", "pay", "delete", "remove",
        "call", "share", "post", "publish", "transfer", "confirm", "install"
    )

    private val protectedKeywords = setOf(
        "password", "passcode", "pin", "biometric", "fingerprint", "face unlock",
        "captcha", "verification code", "security code", "one-time password", "otp"
    )

    fun evaluate(
        step: ActionStep,
        screen: UnifiedScreen,
        confirmed: Boolean
    ): SafetyDecision {
        if (step is ActionStep.RequestConfirmation) {
            return SafetyDecision(
                risk = RiskLevel.ELEVATED,
                outcome = SafetyOutcome.REQUIRE_CONFIRMATION,
                reason = step.reason
            )
        }

        val target = targetText(step, screen)
        val typedText = (step as? ActionStep.TypeText)?.text.orEmpty()
        val actionContext = normalize(
            buildString {
                append(target)
                append(' ')
                append(typedText)
                append(' ')
                append(screen.packageName)
                append(' ')
                append(screen.activityName)
            }
        )
        val screenText = normalize(screen.elements.joinToString(" ") { "${it.text} ${it.description}" })

        if (isProtectedScreen(screen, actionContext, screenText)) {
            return SafetyDecision(
                risk = RiskLevel.BLOCKED,
                outcome = SafetyOutcome.BLOCK,
                reason = "Protected or security-sensitive UI must be handled by the user; MARK will not bypass it."
            )
        }

        val elevated = elevatedKeywords.any(actionContext::contains)
        if (elevated && !confirmed) {
            return SafetyDecision(
                risk = RiskLevel.ELEVATED,
                outcome = SafetyOutcome.REQUIRE_CONFIRMATION,
                reason = "This action may send, delete, purchase, call, install, or share something. Explicit confirmation is required."
            )
        }
        return SafetyDecision(
            risk = if (elevated) RiskLevel.ELEVATED else RiskLevel.SAFE,
            outcome = SafetyOutcome.ALLOW,
            reason = if (elevated) "Confirmed elevated-risk action." else "Safe reversible action."
        )
    }

    private fun targetText(step: ActionStep, screen: UnifiedScreen): String = when (step) {
        is ActionStep.Tap -> screen.findElementByRef(step.elementRef)?.label().orEmpty()
        is ActionStep.TypeText -> screen.findElementByRef(step.elementRef)?.label().orEmpty()
        is ActionStep.Scroll -> step.elementRef?.let { screen.findElementByRef(it)?.label() }.orEmpty()
        is ActionStep.Back -> "back navigation"
        is ActionStep.RequestConfirmation -> step.reason
    }

    private fun isProtectedScreen(
        screen: UnifiedScreen,
        actionContext: String,
        screenText: String
    ): Boolean {
        val systemPermissionScreen = screen.packageName.lowercase(Locale.ROOT).contains("permissioncontroller") ||
            screen.activityName.lowercase(Locale.ROOT).contains("permission")
        if (systemPermissionScreen) return true

        val protectedTarget = protectedKeywords.any(actionContext::contains)
        val protectedVisibleUi = protectedKeywords.any(screenText::contains)
        return protectedTarget || protectedVisibleUi
    }

    private fun normalize(value: String): String = value.lowercase(Locale.ROOT)

    private fun com.surya.jarvis.accessibility.SemanticElement.label(): String =
        text.ifBlank { description }
}

enum class RiskLevel {
    SAFE,
    ELEVATED,
    BLOCKED
}

enum class SafetyOutcome {
    ALLOW,
    REQUIRE_CONFIRMATION,
    BLOCK
}

data class SafetyDecision(
    val risk: RiskLevel,
    val outcome: SafetyOutcome,
    val reason: String
)
