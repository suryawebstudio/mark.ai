package com.surya.jarvis.exploration

import android.content.Context
import com.surya.jarvis.knowledge.AppKnowledge
import com.surya.jarvis.knowledge.AppKnowledgeManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class UserFeedbackHandler(
    private val context: Context,
    private val knowledgeManager: AppKnowledgeManager
) {
    
    suspend fun reportCorrection(
        packageName: String,
        intendedAction: String,
        targetDescription: String,
        correctRef: String,
        correctAction: String
    ): Boolean {
        val knowledge = knowledgeManager.getKnowledge(packageName) ?: return false
        
        // Infer role from target description
        val role = inferRoleFromDescription(targetDescription)
        
        // Update key element mapping
        val existing = knowledge.keyElements[role]
        val updated = existing?.copy(
            resourceIds = (existing.resourceIds + correctRef).distinct(),
            textPatterns = (existing.textPatterns + targetDescription).distinct(),
            actionTypes = existing.actionTypes + correctAction
        ) ?: AppKnowledge.KeyElement(
            role = role,
            resourceIds = listOf(correctRef),
            textPatterns = listOf(targetDescription),
            actionTypes = setOf(correctAction)
        )
        
        val newKnowledge = knowledge.copy(
            keyElements = knowledge.keyElements + (role to updated)
        )
        
        knowledgeManager.updateKnowledge(newKnowledge)
        return true
    }
    
    fun learnSuccessfulWorkflow(
        packageName: String,
        workflowName: String,
        steps: List<WorkflowExecutionStep>,
        requiresConfirmation: Boolean = false
    ): Boolean {
        val knowledge = knowledgeManager.getKnowledge(packageName) ?: return false
        
        val workflow = AppKnowledge.Workflow(
            name = workflowName,
            description = "Learned workflow: $workflowName",
            triggers = steps.first().triggerKeywords,
            steps = steps.map { step ->
                AppKnowledge.WorkflowStep(
                    action = step.action,
                    targetRole = step.targetRole,
                    params = step.params,
                    verification = step.verification,
                    requiresConfirmation = step.requiresConfirmation
                )
            },
            requiresConfirmation = requiresConfirmation
        )
        
        val newKnowledge = knowledge.copy(
            workflows = knowledge.workflows + (workflowName to workflow)
        )
        
        knowledgeManager.updateKnowledge(newKnowledge)
        return true
    }
    
    fun markWorkflowForVerification(
        packageName: String,
        workflowName: String
    ): Boolean {
        val knowledge = knowledgeManager.getKnowledge(packageName) ?: return false
        
        val workflow = knowledge.workflows[workflowName] ?: return false
        val updated = workflow.copy(
            description = "${workflow.description} [NEEDS VERIFICATION - version changed]"
        )
        
        val newKnowledge = knowledge.copy(
            workflows = knowledge.workflows + (workflowName to updated)
        )
        
        knowledgeManager.updateKnowledge(newKnowledge)
        return true
    }
    
    private fun inferRoleFromDescription(description: String): String {
        val lower = description.lowercase()
        return when {
            lower.contains("send") -> "send_button"
            lower.contains("search") -> "search_field"
            lower.contains("message") || lower.contains("text") -> "message_input"
            lower.contains("compose") -> "compose_button"
            lower.contains("menu") -> "menu_button"
            lower.contains("back") -> "back_button"
            lower.contains("home") -> "home_button"
            lower.contains("call") -> "call_button"
            lower.contains("attach") -> "attach_button"
            lower.contains("scroll") || lower.contains("list") -> "scrollable_container"
            lower.contains("checkbox") -> "checkbox"
            lower.contains("switch") -> "switch"
            else -> "button"
        }
    }
    
    suspend fun reportUserCorrection(
        packageName: String,
        intendedAction: String,
        targetDescription: String,
        correctRef: String,
        correctAction: String
    ) = reportCorrection(packageName, intendedAction, targetDescription, correctRef, correctAction)
}

data class WorkflowExecutionStep(
    val action: String,
    val targetRole: String,
    val params: Map<String, String>,
    val verification: AppKnowledge.VerificationTemplate,
    val requiresConfirmation: Boolean,
    val triggerKeywords: List<String> = emptyList()
)