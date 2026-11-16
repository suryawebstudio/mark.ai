package execution-package

import kotlinx.serialization.Serializable

sealed class RecoveryAction {
    data class Retry(val delayMs: Long) : RecoveryAction()
    data class RetryAll() : RecoveryAction()
    data class RetrySpecific(val exceptionTypes: Set<Class<Throwable>>) : RecoveryAction()
    data class Bypass() : RecoveryAction()
    data class BypassAll() : RecoveryAction()
    data class LogAndContinue() : RecoveryAction()
    data class LogAndFail() : RecoveryAction()
    data class FailImmediately() : RecoveryAction()
}

@Serializable
data class VerificationResult(
    val success: Boolean,
    val errorTypes: List<String>,
    val recoveryActions: List<RecoveryAction>,
    val finalStatus: String
)

class RecoveryEngine {
    private val errorRecoveryMap = mapOf(
        "TimeoutException" to RecoveryAction.RetryAll(),
        "IOException" to RecoveryAction.RetrySpecific(setOf(TimedOutError::class.java, IOException::class.java)),
        "SQLException" to RecoveryAction.RetrySpecific(setOf(SQLTimeoutException::class.java, SQLIntegrityConstraintViolationException::class.java)),
        "ParseException" to RecoveryAction.RetrySpecific(setOf(IllegalArgumentException::class.java)),
        "JsonProcessingException" to RecoveryAction.RetrySpecific(setOf(JsonSyntaxException::class.java)),
        "NetworkOnMainThreadException" to RecoveryAction.RetrySpecific(setOf(IllegalStateException::class.java)),
        "OutOfMemoryError" to RecoveryAction.RetrySpecific(setOf(OutOfMemoryError::class.java)),
        "ConcurrentModificationException" to RecoveryAction.RetrySpecific(setOf(ConcurrentModificationException::class.java)),
        "IllegalStateException" to RecoveryAction.RetrySpecific(setOf(IllegalStateException::class.java)),
        "NullPointerException" to RecoveryAction.Bypass()
    )

    fun recoverFromError(error: Throwable): VerificationResult {
        val action = when (error) {
            is TimeoutException -> RecoveryAction.RetryAll()
            is IOException -> RecoveryAction.RetrySpecific(errorRecoveryMap["IOException"]!!)
            is SQLException -> RecoveryAction.RetrySpecific(errorRecoveryMap["SQLException"]!!)
            is ParseException -> RecoveryAction.RetrySpecific(errorRecoveryMap["ParseException"]!!)
            is JsonProcessingException -> RecoveryAction.RetrySpecific(errorRecoveryMap["JsonProcessingException"]!!)
            is NetworkOnMainThreadException -> RecoveryAction.RetrySpecific(errorRecoveryMap["NetworkOnMainThreadException"]!!)
            is OutOfMemoryError -> RecoveryAction.RetrySpecific(errorRecoveryMap["OutOfMemoryError"]!!)
            is ConcurrentModificationException -> RecoveryAction.RetrySpecific(errorRecoveryMap["ConcurrentModificationException"]!!)
            is IllegalStateException -> RecoveryAction.RetrySpecific(errorRecoveryMap["IllegalStateException"]!!)
            is NullPointerException -> RecoveryAction.Bypass()
            else -> RecoveryAction.FailImmediately()
        }

        val errorTypes = listOf(error::class.java.simpleName)
        val recoveryActions = listOf(action)
        val finalStatus = when (action) {
            is RecoveryAction.Retry -> "Retrying"
            is RecoveryAction.RetryAll -> "Retrying all"
            is RecoveryAction.RetrySpecific -> "Retrying specific"
            is RecoveryAction.Bypass -> "Bypassing"
            is RecoveryAction.BypassAll -> "Bypassing all"
            is RecoveryAction.LogAndContinue -> "Logging and continuing"
            is RecoveryAction.LogAndFail -> "Logging and failing"
            RecoveryAction.FailImmediately -> "Failing immediately"
            else -> "Unknown"
        }

        return VerificationResult(
            success = false,
            errorTypes = errorTypes,
            recoveryActions = recoveryActions,
            finalStatus = finalStatus
        )
    }
}

class TimedOutError : IOException("Operation timed out")