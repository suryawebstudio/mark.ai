package com.surya.jarvis.knowledge

import kotlinx.serialization.Serializable

@Serializable
data class AppKnowledge(
    val packageName: String,
    val appName: String,
    val versionCode: Long,
    val versionName: String,
    val launcherActivity: String,
    val mainActivities: List<String>,
    val accessibilityLevel: AccessibilityLevel,
    val keyElements: Map<String, KeyElement>,
    val navigationPatterns: Map<String, NavigationPattern>,
    val workflows: Map<String, Workflow>,
    val commonDialogs: List<DialogPattern>,
    val intents: Map<String, IntentTemplate>,
    val deepLinks: Map<String, String>,
    val versionHistory: List<VersionSnapshot> = emptyList(),
    val lastVerified: Long = System.currentTimeMillis()
)

enum class AccessibilityLevel { FULL, PARTIAL, MINIMAL, NONE }

@Serializable
data class KeyElement(
    val role: String,
    val resourceIds: List<String>,
    val textPatterns: List<String> = emptyList(),
    val descriptionPatterns: List<String> = emptyList(),
    val parentContext: String? = null,
    val actionTypes: Set<String> = setOf("click")
)

@Serializable
data class NavigationPattern(
    val fromScreen: String,
    val toScreen: String,
    val action: ActionTemplate,
    val verification: VerificationTemplate
)

@Serializable
data class Workflow(
    val name: String,
    val description: String,
    val triggers: List<String>,
    val startScreen: String? = null,
    val steps: List<WorkflowStep>,
    val requiresConfirmation: Boolean = false
)

@Serializable
data class WorkflowStep(
    val action: String,
    val targetRole: String,
    val params: Map<String, String> = emptyMap(),
    val verification: VerificationTemplate,
    val requiresConfirmation: Boolean = false,
    val fallback: List<WorkflowStep> = emptyList()
)

@Serializable
data class ActionTemplate(
    val type: String,
    val params: Map<String, String> = emptyMap()
)

@Serializable
data class VerificationTemplate(
    val type: String,
    val params: Map<String, String> = emptyMap()
)

@Serializable
data class DialogPattern(
    val trigger: String,
    val textPatterns: List<String>,
    val action: String
)

@Serializable
data class IntentTemplate(
    val action: String,
    val data: String? = null,
    val packageName: String? = null,
    val extras: Map<String, String> = emptyMap()
)

@Serializable
data class VersionSnapshot(
    val versionCode: Long,
    val versionName: String,
    val timestamp: Long,
    val keyElementChanges: Map<String, List<String>>,
    val uiChanges: List<String>
)