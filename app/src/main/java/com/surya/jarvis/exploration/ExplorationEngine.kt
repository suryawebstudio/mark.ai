package com.surya.jarvis.exploration

import android.content.Context
import android.os.Build
import com.surya.jarvis.accessibility.UnifiedScreen
import com.surya.jarvis.accessibility.SemanticElement
import com.surya.jarvis.knowledge.AppKnowledge
import com.surya.jarvis.knowledge.KeyElement
import com.surya.jarvis.knowledge.AccessibilityLevel
import com.surya.jarvis.knowledge.AppKnowledgeManager
import com.surya.jarvis.execution.ActionExecutor
import com.surya.jarvis.execution.ActionStep
import com.surya.jarvis.execution.VerificationConfig
import com.surya.jarvis.execution.VerificationType
import kotlinx.coroutines.delay

class ExplorationEngine(
    private val context: Context,
    private val screenObserver: (() -> UnifiedScreen?),
    private val executor: ActionExecutor,
    private val knowledgeManager: AppKnowledgeManager
) {

    suspend fun exploreApp(packageName: String): ExplorationResult {
        val knowledge = mutableMapOf<String, KeyElementDiscovery>()
        var currentScreen = screenObserver.invoke()
            ?: return ExplorationResult.Failed("Cannot inspect screen")

        // 1. Launch app
        val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName)
        if (launchIntent == null) {
            return ExplorationResult.Failed("Cannot launch app $packageName")
        }

        context.startActivity(launchIntent)
        delay(2000) // Wait for app to load
        currentScreen = screenObserver.invoke() ?: currentScreen

        // 2. Explore main screens (max 10)
        var screensExplored = 0
        var screensVisited = mutableSetOf<String>()

        while (screensExplored < 10 && currentScreen.packageName == packageName) {
            val screenKey = "${currentScreen.packageName}:${currentScreen.activityName}"
            if (screensVisited.contains(screenKey)) break
            screensVisited.add(screenKey)

            // Extract key elements from current screen
            extractKeyElements(currentScreen, knowledge)

            // Find navigation elements
            val navElements = currentScreen.elements.filter {
                it.clickable && (it.role in setOf("Tab", "Button", "NavigationItem", "ImageButton")) &&
                it.visible
            }

            if (navElements.isEmpty()) break

            // Tap first unexplored nav element
            val nextNav = navElements.firstOrNull { !knowledge.containsKey(it.description) }
            if (nextNav == null) break

            val tapResult = executor.execute(
                ActionStep.Tap(nextNav.ref, nextNav.description, VerificationConfig(VerificationType.ELEMENT_PRESENT)),
                currentScreen
            )

            if (!tapResult.success) break

            delay(1000)
            currentScreen = screenObserver.invoke() ?: currentScreen
            screensExplored++
        }

        // 3. Save discovered knowledge
        val versionCode = getVersionCode(packageName)
        val versionName = getVersionName(packageName)
        val launcherActivity = getLauncherActivity(packageName)
        val mainActivities = screensVisited.mapNotNull { it.split(":").getOrNull(1) }.toList()

        val keyElements = knowledge.mapValues { (role, discovery) ->
            discovery.toKeyElement()
        }

        val appKnowledge = AppKnowledge(
            packageName = packageName,
            appName = currentScreen.packageName,
            versionCode = versionCode,
            versionName = versionName,
            launcherActivity = launcherActivity,
            mainActivities = mainActivities,
            accessibilityLevel = determineAccessibilityLevel(currentScreen),
            keyElements = keyElements,
            navigationPatterns = emptyMap(),
            workflows = emptyMap(),
            commonDialogs = emptyList(),
            intents = emptyMap(),
            deepLinks = emptyMap()
        )

        knowledgeManager.updateKnowledge(appKnowledge)

        return ExplorationResult.Success(
            packageName = packageName,
            screensExplored = screensExplored,
            elementsDiscovered = keyElements.size,
            accessibilityLevel = appKnowledge.accessibilityLevel,
            keyElements = keyElements
        )
    }

    private fun extractKeyElements(screen: UnifiedScreen, knowledge: MutableMap<String, KeyElementDiscovery>) {
        for (element in screen.elements) {
            if (!element.actionable) continue

            val role = element.role
            val existing = knowledge[role]

            val discovery = KeyElementDiscovery(
                role = role,
                resourceIds = (existing?.resourceIds.orEmpty() + (element.nativeId ?: "")).distinct().filter { it.isNotBlank() },
                textPatterns = (existing?.textPatterns.orEmpty() + element.text).distinct().filter { it.isNotBlank() },
                descriptionPatterns = (existing?.descriptionPatterns.orEmpty() + element.description).distinct().filter { it.isNotBlank() },
                actionTypes = existing?.actionTypes.orEmpty() + setOf(
                    if (element.clickable) "click" else null,
                    if (element.editable) "type" else null,
                    if (element.scrollable) "scroll" else null
                ).filterNotNull()
            )

            knowledge[role] = discovery
        }
    }

    private fun determineAccessibilityLevel(screen: UnifiedScreen): AccessibilityLevel {
        val actionableCount = screen.elements.count { it.actionable }
        val hasNativeIds = screen.elements.any { it.nativeId != null }

        return when {
            actionableCount > 10 && hasNativeIds -> AccessibilityLevel.FULL
            actionableCount > 5 -> AccessibilityLevel.PARTIAL
            actionableCount > 0 -> AccessibilityLevel.MINIMAL
            else -> AccessibilityLevel.NONE
        }
    }

    @Suppress("DEPRECATION")
    private fun getVersionCode(packageName: String): Long {
        return try {
            context.packageManager.getPackageInfo(packageName, 0).let { info ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode
                else info.versionCode.toLong()
            }
        } catch (e: Exception) {
            0
        }
    }

    private fun getVersionName(packageName: String): String {
        return try {
            context.packageManager.getPackageInfo(packageName, 0).versionName ?: "unknown"
        } catch (e: Exception) {
            "unknown"
        }
    }

    private fun getLauncherActivity(packageName: String): String {
        return try {
            val info = context.packageManager.getLaunchIntentForPackage(packageName)
            info?.component?.className ?: "unknown"
        } catch (e: Exception) {
            "unknown"
        }
    }
}

data class KeyElementDiscovery(
    val role: String,
    val resourceIds: List<String>,
    val textPatterns: List<String>,
    val descriptionPatterns: List<String>,
    val actionTypes: Set<String>
) {
    fun toKeyElement(): KeyElement {
        return KeyElement(
            role = role,
            resourceIds = resourceIds,
            textPatterns = textPatterns,
            descriptionPatterns = descriptionPatterns,
            actionTypes = actionTypes
        )
    }
}

sealed class ExplorationResult {
    data class Success(
        val packageName: String,
        val screensExplored: Int,
        val elementsDiscovered: Int,
        val accessibilityLevel: AccessibilityLevel,
        val keyElements: Map<String, KeyElement>
    ) : ExplorationResult()

    data class Failed(val reason: String) : ExplorationResult()
}