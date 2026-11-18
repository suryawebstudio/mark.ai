package com.surya.jarvis.accessibility

import android.graphics.Rect

/**
 * Stable, framework-neutral representation of one visible MARK screen.
 * This model is intentionally separate from AccessibilityNodeInfo so planning,
 * verification, and recovery do not retain Android node objects.
 */
data class UnifiedScreen(
    val snapshotId: String,
    val packageName: String,
    val activityName: String,
    val timestamp: Long,
    val elements: List<SemanticElement>
) {
    fun findElementByRef(ref: String): SemanticElement? =
        elements.firstOrNull { it.ref == ref }

    fun findElementByRole(role: String): SemanticElement? =
        elements.firstOrNull { it.role.equals(role, ignoreCase = true) }
}

data class SemanticElement(
    val ref: String,
    val role: String,
    val text: String,
    val description: String,
    val nativeId: String?,
    val bounds: RectBounds,
    val clickable: Boolean,
    val editable: Boolean,
    val scrollable: Boolean,
    val enabled: Boolean,
    val states: Set<String>,
    val parentRef: String? = null,
    val childrenRefs: List<String> = emptyList()
) {
    val actionable: Boolean
        get() = clickable || editable || scrollable

    val visible: Boolean
        get() = bounds.width > 0 && bounds.height > 0

    fun toRect(): Rect = bounds.toRect()
}

data class RectBounds(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
) {
    val width: Int
        get() = right - left

    val height: Int
        get() = bottom - top

    fun toRect(): Rect = Rect(left, top, right, bottom)

    companion object {
        fun fromRect(rect: Rect): RectBounds =
            RectBounds(rect.left, rect.top, rect.right, rect.bottom)
    }
}
