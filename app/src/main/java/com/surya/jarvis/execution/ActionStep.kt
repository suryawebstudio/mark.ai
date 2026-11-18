package com.surya.jarvis.execution

/**
 * A planned action. Execution is intentionally implemented in a later layer;
 * these types only describe intent and its verification requirements.
 */
sealed class ActionStep {
    data class Tap(
        val elementRef: String,
        override val verification: VerificationConfig = VerificationConfig()
    ) : ActionStep()

    data class TypeText(
        val elementRef: String,
        val text: String,
        override val verification: VerificationConfig = VerificationConfig()
    ) : ActionStep()

    data class Scroll(
        val elementRef: String? = null,
        val direction: ScrollDirection = ScrollDirection.DOWN,
        override val verification: VerificationConfig = VerificationConfig()
    ) : ActionStep()

    data class Back(
        override val verification: VerificationConfig = VerificationConfig()
    ) : ActionStep()

    /** Explicit confirmation remains required for consequential operations. */
    data class RequestConfirmation(
        val reason: String
    ) : ActionStep() {
        override val verification: VerificationConfig = VerificationConfig.none()
    }

    abstract val verification: VerificationConfig
}

enum class ScrollDirection {
    UP,
    DOWN,
    LEFT,
    RIGHT
}

data class VerificationConfig(
    val required: Boolean = true,
    val timeoutMs: Long = DEFAULT_TIMEOUT_MS
) {
    companion object {
        const val DEFAULT_TIMEOUT_MS = 2_000L

        fun none(): VerificationConfig = VerificationConfig(required = false)
    }
}
