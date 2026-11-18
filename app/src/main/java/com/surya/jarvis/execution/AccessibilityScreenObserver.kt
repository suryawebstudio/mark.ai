package com.surya.jarvis.execution

import com.surya.jarvis.JarvisAccessibilityService
import com.surya.jarvis.accessibility.RectBounds
import com.surya.jarvis.accessibility.SemanticElement
import com.surya.jarvis.accessibility.UnifiedScreen
import org.json.JSONObject

/**
 * Adapts MARK's existing accessibility snapshot to the execution architecture.
 * It is deliberately read-only: no node is retained and no action is issued.
 */
class AccessibilityScreenObserver(
    private val inspect: () -> String = { JarvisAccessibilityService.inspectScreen() },
    private val clock: () -> Long = { System.currentTimeMillis() }
) : ScreenObserver {

    override fun inspect(): UnifiedScreen? = runCatching {
        parse(inspect(), clock())
    }.getOrNull()

    internal fun parse(payload: String, timestamp: Long): UnifiedScreen? {
        val root = JSONObject(payload)
        if (!root.optBoolean("success", false)) return null

        val snapshotId = root.optString("snapshot_id").takeIf { it.isNotBlank() } ?: return null
        val packageName = root.optString("package", "unknown")
        val activityName = root.optString("class", "unknown")
        val elementsJson = root.optJSONArray("elements") ?: return null
        val elements = buildList(elementsJson.length()) {
            for (index in 0 until elementsJson.length()) {
                val item = elementsJson.optJSONObject(index) ?: continue
                add(parseElement(item))
            }
        }

        return UnifiedScreen(
            snapshotId = snapshotId,
            packageName = packageName,
            activityName = activityName,
            timestamp = timestamp,
            elements = elements
        )
    }

    private fun parseElement(item: JSONObject): SemanticElement {
        val bounds = item.optJSONObject("bounds")
        val left = bounds?.optInt("left", 0) ?: 0
        val top = bounds?.optInt("top", 0) ?: 0
        val right = bounds?.optInt("right", 0) ?: 0
        val bottom = bounds?.optInt("bottom", 0) ?: 0
        val statesJson = item.optJSONArray("states")
        val states = buildSet {
            if (statesJson != null) {
                for (index in 0 until statesJson.length()) {
                    statesJson.optString(index).takeIf { it.isNotBlank() }?.let(::add)
                }
            }
        }

        return SemanticElement(
            ref = item.optString("ref"),
            role = item.optString("role", "node"),
            text = item.optString("text"),
            description = item.optString("description"),
            nativeId = item.optString("native_id").takeIf { it.isNotBlank() },
            bounds = RectBounds(left, top, right, bottom),
            clickable = item.optBoolean("clickable", false),
            editable = item.optBoolean("editable", false),
            scrollable = item.optBoolean("scrollable", false),
            enabled = item.optBoolean("enabled", false),
            states = states
        )
    }
}
