package com.surya.jarvis.execution

import com.surya.jarvis.accessibility.UnifiedScreen
import com.surya.jarvis.knowledge.AppKnowledge
import com.surya.jarvis.knowledge.AppKnowledgeManager
import com.surya.jarvis.knowledge.Workflow
import com.surya.jarvis.knowledge.WorkflowStep
import com.surya.jarvis.knowledge.VerificationTemplate
import kotlinx.coroutines.delay

class ActionPlanner(
    private val appKnowledgeManager: AppKnowledgeManager,
    private val llmClient: LlmClient?
) {

    fun createPlan(context: PlanningContext): ActionPlan {
        // 1. Identify target app from intent
        val targetPackage = extractPackage(context.userIntent)
            ?: context.currentScreen.packageName
            ?: context.targetApp?.packageName
            ?: return genericPlan(context)

        // 2. Load app knowledge
        val appKnowledge = appKnowledgeManager.getKnowledge(targetPackage)
            ?: return genericPlan(context)

        // 3. Classify current screen
        val currentScreenType = classifyScreen(context.currentScreen)

        // 4. Find matching workflow
        val workflow = findWorkflow(appKnowledge, context.userIntent, currentScreenType)

        if (workflow != null) {
            return instantiateWorkflow(workflow, context)
        }

        // 5. No workflow found → LLM-based planning
        return llmPlan(context, appKnowledge)
    }

    private fun extractPackage(intent: String): String? {
        // Extract package name from intent text using app knowledge
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

    private fun classifyScreen(screen: UnifiedScreen): String {
        return when {
            screen.elements.any { it.role == "AlertDialog" } -> "dialog"
            screen.elements.any { it.text.lowercase().contains("loading") } -> "loading"
            screen.elements.any { it.scrollable } -> "list"
            screen.elements.any { it.editable } -> "form"
            else -> "unknown"
        }
    }

    private fun findWorkflow(
        knowledge: AppKnowledge,
        intent: String,
        screenType: String
    ): Workflow? {
        return knowledge.workflows.values.firstOrNull { workflow ->
            workflow.triggers.any { intent.contains(it, ignoreCase = true) } &&
            (workflow.startScreen == null || workflow.startScreen == screenType)
        }
    }

    private fun instantiateWorkflow(
        workflow: Workflow,
        context: PlanningContext
    ): ActionPlan {
        val steps = workflow.steps.map { step ->
            step.toActionStep(context.currentScreen, context.targetApp)
        }

        return ActionPlan(
            steps = steps,
            requiresConfirmation = steps.any { it.verification.type == VerificationType.SCREEN_CHANGE } ||
                workflow.requiresConfirmation,
            estimatedDurationMs = steps.size * 2000L
        )
    }

    private fun llmPlan(context: PlanningContext, appKnowledge: AppKnowledge?): ActionPlan {
        // Fallback: use LLM to reason about steps
        // For now, return a generic plan based on intent keywords
        return genericPlan(context)
    }

    private fun genericPlan(context: PlanningContext): ActionPlan {
        val intent = context.userIntent.lowercase()

        val steps = when {
            intent.contains("open") || intent.contains("launch") -> {
                listOf(
                    ActionStep.GlobalAction(
                        ActionStep.GlobalActionType.HOME,
                        "home screen",
                        VerificationConfig(VerificationType.SCREEN_CHANGE)
                    )
                )
            }
            intent.contains("send") || intent.contains("message") -> {
                listOf(
                    ActionStep.Tap(
                        "send_button",
                        "send",
                        VerificationConfig(VerificationType.ELEMENT_PRESENT, mapOf("targetRole" to "send_button"))
                    )
                )
            }
            else -> emptyList()
        }

        return ActionPlan(
            steps = steps,
            requiresConfirmation = steps.any { step ->
                step is ActionStep.Tap && step.target.contains("send")
            }
        )
    }
}

fun WorkflowStep.toActionStep(
    screen: UnifiedScreen,
    appKnowledge: AppKnowledge?
): ActionStep {
    // Resolve target element from screen
    val targetElement = screen.findElementByRole(targetRole)
        ?: screen.elements.firstOrNull { it.clickable && it.text.contains(targetRole, ignoreCase = true) }

    val ref = targetElement?.ref ?: "unknown"
    val targetName = targetRole

    return when (action) {
        "tap" -> ActionStep.Tap(ref, targetName, verification.toConfig())
        "type" -> ActionStep.Type(
            ref,
            params["text"] ?: "",
            targetName,
            verification.toConfig()
        )
        "scroll" -> ActionStep.Scroll(
            ref,
            params["direction"] ?: "down",
            targetName,
            verification.toConfig()
        )
        "long_press" -> ActionStep.LongPress(ref, targetName, verification.toConfig())
        "swipe_left" -> ActionStep.Swipe(ref, "swipe_left", targetName, verification.toConfig())
        "swipe_right" -> ActionStep.Swipe(ref, "swipe_right", targetName, verification.toConfig())
        "swipe_up" -> ActionStep.Swipe(ref, "swipe_up", targetName, verification.toConfig())
        "swipe_down" -> ActionStep.Swipe(ref, "swipe_down", targetName, verification.toConfig())
        "drag" -> ActionStep.Drag(
            ref,
            params["targetX"]?.toFloat() ?: 0f,
            params["targetY"]?.toFloat() ?: 0f,
            targetName,
            verification.toConfig()
        )
        "double_tap" -> ActionStep.DoubleTap(ref, targetName, verification.toConfig())
        "back" -> ActionStep.GlobalAction(
            ActionStep.GlobalActionType.BACK,
            "back",
            verification.toConfig()
        )
        "home" -> ActionStep.GlobalAction(
            ActionStep.GlobalActionType.HOME,
            "home",
            verification.toConfig()
        )
        else -> ActionStep.Tap(ref, targetName, verification.toConfig())
    }
}

fun VerificationTemplate.toConfig(): VerificationConfig {
    return VerificationConfig(
        type = when (type) {
            "element_present" -> VerificationType.ELEMENT_PRESENT
            "element_gone" -> VerificationType.ELEMENT_GONE
            "text_contains" -> VerificationType.TEXT_CONTAINS
            "state_changed" -> VerificationType.STATE_CHANGED
            "screen_change" -> VerificationType.SCREEN_CHANGE
            "global_state" -> VerificationType.GLOBAL_STATE
            "tree_diff" -> VerificationType.TREE_DIFF
            else -> VerificationType.SCREEN_CHANGE
        },
        params = this.params
    )
}

interface LlmClient {
    suspend fun planSteps(context: PlanningContext): ActionPlan?
}