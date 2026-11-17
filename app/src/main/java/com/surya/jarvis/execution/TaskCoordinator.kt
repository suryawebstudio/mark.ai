package com.surya.jarvis.execution

import com.surya.jarvis.accessibility.UnifiedScreen
import com.surya.jarvis.knowledge.AppKnowledge
import com.surya.jarvis.knowledge.AppKnowledgeManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.pow

class TaskCoordinator(
    private val planner: ActionPlanner,
    private val executor: ActionExecutor,
    private val verifier: VerificationEngine,
    private val recovery: RecoveryEngine,
    private val safetyGates: SafetyGates,
    private val screenObserver: ScreenObserver,
    private val appKnowledgeManager: AppKnowledgeManager
) {
    
    private var currentTask: TaskSession? = null
    private val maxSteps = 24
    private val retryPolicy = RetryPolicy()
    
    suspend fun executeTask(intent: String): TaskResult {
        currentTask = TaskSession(
            intent = intent,
            targetPackage = extractPackage(intent) ?: "unknown"
        )
        
        // Initial observation
        var screen = screenObserver.inspectSemantic()
            ?: return TaskResult.failed("Cannot inspect screen")
        
        while (currentTask != null && currentTask!!.stepCount < maxSteps) {
            val task = currentTask!!
            
            // Check if waiting for confirmation
            if (task.awaitingConfirmation) {
                return TaskResult.confirmationRequired("Waiting for user confirmation")
            }
            
            // Plan next step
            val appKnowledge = appKnowledgeManager.getKnowledge(task.targetPackage)
            val plan = planner.createPlan(PlanningContext(
                userIntent = task.intent,
                currentScreen = screen,
                targetApp = appKnowledge,
                taskHistory = task.executedSteps,
                globalState = getGlobalState(),
                safetyConstraints = SafetyConstraints()
            ))
            
            if (plan.steps.isEmpty()) {
                return TaskResult.failed("No plan generated")
            }
            
            val step = plan.steps.first()
            
            // Safety check
            val validation = safetyGates.validateSensitiveAction(step)
            when (validation) {
                is SafetyGates.SensitiveActionValidation.ConfirmationRequired -> {
                    task.awaitingConfirmation = true
                    return TaskResult.confirmationRequired(validation.prompt)
                }
                is SafetyGates.SensitiveActionValidation.Blocked -> {
                    return TaskResult.failed(validation.reason)
                }
                else -> {
                    // Allowed or any other case - proceed
                }
            }
            
            // Execute with retries
            var attempt = 0
            var lastResult: ExecutionResult? = null
            
            while (attempt <= retryPolicy.maxRetries) {
                lastResult = executor.execute(step, screen)
                
                if (lastResult!!.success) {
                    // Verify
                    val verification = verifier.verify(step, screen, appKnowledge)
                    if (verification.passed) {
                        task.stepCount++
                        task.executedSteps.add(ExecutedStep(step, lastResult!!, verification))
                        task.awaitingConfirmation = false
                        
                        if (verification.taskComplete || stepIsTerminal(step)) {
                            return completeTask(task)
                        }
                        
                        // Next observation
                        screen = screenObserver.inspectSemantic() ?: screen
                        break  // Exit retry loop, continue to next step
                    } else {
                        // Verification failed
                        lastResult = ExecutionResult.failed(
                            ErrorType.VERIFICATION_FAILED, 
                            verification.message
                        )
                    }
                } else {
                    // Execution failed
                }
                
                // Recovery
                val recoveryAction = recovery.classifyAndRecover(task, step, lastResult!!)
                val recoveryResult = recovery.executeRecovery(recoveryAction, task, screen)
                
                when (recoveryResult) {
                    is RecoveryResult.ConfirmationRequired -> {
                        task.awaitingConfirmation = true
                        return TaskResult.confirmationRequired(recoveryResult.message)
                    }
                    is RecoveryResult.Failed -> {
                        return TaskResult.failed(recoveryResult.message)
                    }
                    is RecoveryResult.Success -> {
                        task.currentPlan = recoveryResult.newPlan
                        screen = screenObserver.inspectSemantic() ?: screen
                    }
                    is RecoveryResult.RetryWithAdjustment -> {
                        // Continue retry loop
                    }
                }
                
                attempt++
                if (attempt <= retryPolicy.maxRetries) {
                    delay(retryPolicy.getDelay(attempt))
                    screen = screenObserver.inspectSemantic() ?: screen
                }
            }
            
            if (attempt > retryPolicy.maxRetries) {
                return TaskResult.failed("Max retries exceeded for step: ${step.javaClass.simpleName}")
            }
        }
        
        return TaskResult.failed("Max steps ($maxSteps) exceeded")
    }
    
    suspend fun confirmAndRetry(): TaskResult {
        val task = currentTask ?: return TaskResult.failed("No active task")
        if (!task.awaitingConfirmation) {
            return TaskResult.failed("No confirmation pending")
        }
        task.awaitingConfirmation = false
        return executeTask(task.intent)
    }
    
    fun cancelTask(): TaskResult {
        currentTask?.cancel()
        currentTask = null
        return TaskResult.cancelled("Task cancelled")
    }
    
    fun getCurrentTask(): TaskSession? = currentTask
    
    private fun completeTask(task: TaskSession): TaskResult {
        currentTask = null
        return TaskResult.completed("Task completed in ${task.stepCount} steps", task.stepCount)
    }
    
    private fun stepIsTerminal(step: ActionStep): Boolean {
        return step is ActionStep.VerifyScreen || 
               step.verification.params["terminal"] == "true"
    }
    
    private fun extractPackage(intent: String): String? {
        val lower = intent.lowercase()
        return when {
            lower.contains("whatsapp") -> "com.whatsapp"
            lower.contains("gmail") -> "com.google.android.gm"
            lower.contains("youtube") -> "com.google.android.youtube"
            lower.contains("maps") || lower.contains("google maps") -> "com.google.android.apps.maps"
            lower.contains("chrome") -> "com.android.chrome"
            lower.contains("instagram") -> "com.instagram.android"
            else -> null
        }
    }
    
    private fun getGlobalState(): GlobalState {
        // This would be populated from actual device state
        return GlobalState(
            isAccessibilityEnabled = true,
            isMicrophoneAvailable = true,
            currentPackage = null,
            currentActivity = null,
            screenOn = true,
            keyboardVisible = false
        )
    }
}

class RetryPolicy {
    val maxRetries = 3
    val baseDelayMs = 500L
    val maxDelayMs = 5000L
    
    fun shouldRetry(errorType: ErrorType, attempt: Int): Boolean {
        return attempt < maxRetries && errorType.isRetryable()
    }
    
    fun getDelay(attempt: Int): Long {
        val delay = baseDelayMs * (2.0.pow(attempt)).toLong()
        return delay.coerceAtMost(maxDelayMs)
    }
}