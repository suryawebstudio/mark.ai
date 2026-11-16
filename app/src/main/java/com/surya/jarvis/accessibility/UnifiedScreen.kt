package com.surya.jarvis.accessibility

import android.graphics.Rect
import kotlinx.serialization.Serializable

@Serializable
data class UnifiedScreen(
    val snapshotId: String,
    val packageName: String,
    val activityName: String,
    val launcherPackageName: String,
    val timestamp: Long,
    val elements: List<SemanticElement>,
    val visualCapture: VisualScreen?,
    val ocrText: String?
) {
    fun findElementByRef(ref: String): SemanticElement? {
        return elements.firstOrNull { it.ref == ref }
    }
    
    fun findElementByRole(role: String): SemanticElement? {
        return elements.firstOrNull { 
            it.role.equals(role, ignoreCase = true) ||
            role.contains(it.role, ignoreCase = true)
        }
    }
}

@Serializable
data class SemanticElement(
    val ref: String,
    val role: String,
    val text: String,
    val description: String,
    val nativeId: String?,
    val bounds: Rect,
    val clickable: Boolean,
    val editable: Boolean,
    val scrollable: Boolean,
    val focusable: Boolean,
    val longClickable: Boolean,
    val states: Set<String>,
    val parentRef: String?,
    val childrenRefs: List<String>
) {
    val actionable: Boolean
        get() = clickable || editable || scrollable || focusable || longClickable
    
    val visible: Boolean
        get() = bounds.width() > 0 && bounds.height() > 0
}

@Serializable
data class VisualScreen(
    val jpegBase64: String,
    val width: Int,
    val height: Int,
    val gridColumns: Int = 8,
    val gridRows: Int = 12,
    val token: String,
    val ocrResult: OcrScreenResult?
)

@Serializable
data class OcrScreenResult(
    val fullText: String,
    val blocks: List<OcrBlockScreen>
)

@Serializable
data class OcrBlockScreen(
    val text: String,
    val bounds: Rect,
    val lines: List<OcrLineScreen>
)

@Serializable
data class OcrLineScreen(
    val text: String,
    val bounds: Rect,
    val confidence: Float,
    val words: List<OcrWordScreen>
)

@Serializable
data class OcrWordScreen(
    val text: String,
    val bounds: Rect,
    val confidence: Float
)

sealed class ScreenSnapshot {
    data class Semantic(
        val snapshotId: String,
        val packageName: String,
        val activityName: String,
        val elements: List<SemanticElement>
    ) : ScreenSnapshot()
    
    data class Visual(
        val snapshotId: String,
        val visualScreen: VisualScreen
    ) : ScreenSnapshot()
}