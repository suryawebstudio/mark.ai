package com.surya.jarvis.execution

import android.accessibilityservice.AccessibilityService
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.view.ViewConfiguration
import android.accessibilityservice.GestureDescription
import com.surya.jarvis.accessibility.UnifiedScreen
import com.surya.jarvis.accessibility.SemanticElement
import kotlin.math.sqrt

class ActionExecutor(
    private val accessibilityService: AccessibilityService
) {
    
    fun execute(step: ActionStep, screen: UnifiedScreen): ExecutionResult {
        return try {
            when (step) {
                is ActionStep.Tap -> executeTap(step, screen)
                is ActionStep.Type -> executeType(step, screen)
                is ActionStep.Scroll -> executeScroll(step, screen)
                is ActionStep.LongPress -> executeLongPress(step, screen)
                is ActionStep.Swipe -> executeSwipe(step, screen)
                is ActionStep.Drag -> executeDrag(step, screen)
                is ActionStep.DoubleTap -> executeDoubleTap(step, screen)
                is ActionStep.PinchZoom -> executePinchZoom(step, screen)
                is ActionStep.GlobalAction -> executeGlobalAction(step)
                is ActionStep.IntentAction -> executeIntent(step)
                is ActionStep.Wait -> executeWait(step)
                is ActionStep.VerifyScreen -> executeVerifyScreen(step, screen)
                else -> ExecutionResult.failed(ErrorType.UNKNOWN, "Unknown action type: ${step.javaClass.simpleName}")
            }
        } catch (e: Exception) {
            ExecutionResult.failed(ErrorType.UNKNOWN, "Action execution exception: ${e.message}")
        }
    }
    
    private fun executeTap(step: ActionStep.Tap, screen: UnifiedScreen): ExecutionResult {
        val node = screen.findElementByRef(step.ref)
            ?: return ExecutionResult.failed(ErrorType.STALE_REF, "Ref not found in current screen")
        
        if (!node.visible) {
            return ExecutionResult.failed(ErrorType.NOT_VISIBLE, "Element no longer visible")
        }
        if (!node.clickable) {
            return ExecutionResult.failed(ErrorType.DISABLED_TARGET, "Element is not clickable")
        }
        
        // Method 1: Accessibility action (primary)
        val clickable = findClickableAncestor(node) ?: node
        var success = clickable.performAction(
            android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK
        )
        
        // Method 2: Gesture to element bounds center (fallback)
        if (!success) {
            success = dispatchTapGesture(clickable.bounds.centerX().toFloat(), clickable.bounds.centerY().toFloat())
        }
        
        return if (success) {
            ExecutionResult.success("Tapped ${step.target}")
        } else {
            ExecutionResult.failed(ErrorType.GESTURE_REJECTED, "Both click and gesture failed")
        }
    }
    
    private fun executeType(step: ActionStep.Type, screen: UnifiedScreen): ExecutionResult {
        val node = screen.findElementByRef(step.ref)
            ?: return ExecutionResult.failed(ErrorType.STALE_REF, "Ref not found in current screen")
        
        if (!node.editable) {
            return ExecutionResult.failed(ErrorType.NOT_EDITABLE, "Element is not editable")
        }
        
        val arguments = Bundle().apply {
            putCharSequence(
                android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                step.text
            )
        }
        val success = node.performAction(
            android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT,
            arguments
        )
        
        return if (success) {
            ExecutionResult.success("Typed into ${step.target}")
        } else {
            ExecutionResult.failed(ErrorType.TYPE_FAILED, "Field could not accept text")
        }
    }
    
    private fun executeScroll(step: ActionStep.Scroll, screen: UnifiedScreen): ExecutionResult {
        val node = screen.findElementByRef(step.ref)
            ?: return ExecutionResult.failed(ErrorType.STALE_REF, "Ref not found in current screen")
        
        if (!node.scrollable) {
            return ExecutionResult.failed(ErrorType.NOT_SCROLLABLE, "Element is not scrollable")
        }
        
        val action = when (step.direction.lowercase()) {
            "up", "backward", "previous" -> android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            "down", "forward", "next", "" -> android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            else -> return ExecutionResult.failed(ErrorType.UNKNOWN, "Use scroll direction up or down")
        }
        
        val success = node.performAction(action)
        return if (success) {
            ExecutionResult.success("Scrolled ${step.direction}")
        } else {
            ExecutionResult.failed(ErrorType.SCROLL_FAILED, "Could not scroll")
        }
    }
    
    private fun executeLongPress(step: ActionStep.LongPress, screen: UnifiedScreen): ExecutionResult {
        val node = screen.findElementByRef(step.ref)
            ?: return ExecutionResult.failed(ErrorType.STALE_REF, "Ref not found")
        
        val target = findLongClickableAncestor(node) ?: node
        val centerX = target.bounds.centerX().toFloat()
        val centerY = target.bounds.centerY().toFloat()
        
        return dispatchLongPressGesture(centerX, centerY, "Long-pressed ${step.target}")
    }
    
    private fun executeSwipe(step: ActionStep.Swipe, screen: UnifiedScreen): ExecutionResult {
        val node = screen.findElementByRef(step.ref)
            ?: return ExecutionResult.failed(ErrorType.STALE_REF, "Ref not found")
        
        val bounds = node.bounds
        val (startX, startY, endX, endY) = when (step.direction.lowercase()) {
            "swipe_left" -> bounds.right.toFloat() to bounds.centerY().toFloat() to bounds.left.toFloat() to bounds.centerY().toFloat()
            "swipe_right" -> bounds.left.toFloat() to bounds.centerY().toFloat() to bounds.right.toFloat() to bounds.centerY().toFloat()
            "swipe_up" -> bounds.centerX().toFloat() to bounds.bottom.toFloat() to bounds.centerX().toFloat() to bounds.top.toFloat()
            "swipe_down" -> bounds.centerX().toFloat() to bounds.top.toFloat() to bounds.centerX().toFloat() to bounds.bottom.toFloat()
            else -> return ExecutionResult.failed(ErrorType.UNKNOWN, "Invalid swipe direction")
        }
        
        return dispatchSwipeGesture(startX, startY, endX, endY, "Swiped ${step.direction}")
    }
    
    private fun executeDrag(step: ActionStep.Drag, screen: UnifiedScreen): ExecutionResult {
        val node = screen.findElementByRef(step.ref)
            ?: return ExecutionResult.failed(ErrorType.STALE_REF, "Ref not found")
        
        val bounds = node.bounds
        val startX = bounds.centerX().toFloat()
        val startY = bounds.centerY().toFloat()
        val targetX = step.targetX
        val targetY = step.targetY
        
        return dispatchDragGesture(startX, startY, targetX, targetY, "Dragged ${step.target}")
    }
    
    private fun executeDoubleTap(step: ActionStep.DoubleTap, screen: UnifiedScreen): ExecutionResult {
        val node = screen.findElementByRef(step.ref)
            ?: return ExecutionResult.failed(ErrorType.STALE_REF, "Ref not found")
        
        val clickable = findClickableAncestor(node) ?: node
        val centerX = clickable.bounds.centerX().toFloat()
        val centerY = clickable.bounds.centerY().toFloat()
        
        return dispatchDoubleTapGesture(centerX, centerY, "Double-tapped ${step.target}")
    }
    
    private fun executePinchZoom(step: ActionStep.PinchZoom, screen: UnifiedScreen): ExecutionResult {
        val node = screen.findElementByRef(step.ref)
            ?: return ExecutionResult.failed(ErrorType.STALE_REF, "Ref not found")
        
        val bounds = node.bounds
        val centerX = bounds.centerX().toFloat()
        val centerY = bounds.centerY().toFloat()
        val radius = min(bounds.width(), bounds.height()) / 4f
        
        return dispatchPinchGesture(centerX, centerY, radius, step.pinchIn, "Pinched ${if (step.pinchIn) "in" else "out"} ${step.target}")
    }
    
    private fun executeGlobalAction(step: ActionStep.GlobalAction): ExecutionResult {
        val action = when (step.actionType) {
            ActionStep.GlobalActionType.BACK -> AccessibilityService.GLOBAL_ACTION_BACK
            ActionStep.GlobalActionType.HOME -> AccessibilityService.GLOBAL_ACTION_HOME
            ActionStep.GlobalActionType.RECENTS -> AccessibilityService.GLOBAL_ACTION_RECENTS
            ActionStep.GlobalActionType.NOTIFICATIONS -> AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
            ActionStep.GlobalActionType.QUICK_SETTINGS -> AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS
        }
        val success = accessibilityService.performGlobalAction(action)
        return if (success) {
            ExecutionResult.success("Global action: ${step.actionType}")
        } else {
            ExecutionResult.failed(ErrorType.GLOBAL_ACTION_FAILED, "Global action rejected")
        }
    }
    
    private fun executeIntent(step: ActionStep.IntentAction): ExecutionResult {
        return try {
            accessibilityService.context.startActivity(step.intent)
            ExecutionResult.success("Intent launched")
        } catch (e: Exception) {
            ExecutionResult.failed(ErrorType.INTENT_FAILED, e.message ?: "Intent failed")
        }
    }
    
    private fun executeWait(step: ActionStep.Wait): ExecutionResult {
        Thread.sleep(step.durationMs)
        return ExecutionResult.success("Waited ${step.durationMs}ms")
    }
    
    private fun executeVerifyScreen(step: ActionStep.VerifyScreen, screen: UnifiedScreen): ExecutionResult {
        val missing = step.expectedElements.filterNot { expected ->
            screen.elements.any { it.text.contains(expected, ignoreCase = true) || it.description.contains(expected, ignoreCase = true) }
        }
        return if (missing.isEmpty()) {
            ExecutionResult.success("Screen verified")
        } else {
            ExecutionResult.failed(ErrorType.VERIFICATION_FAILED, "Missing expected elements: ${missing.joinToString(", ")}")
        }
    }
    
    // Gesture dispatch methods
    private fun dispatchTapGesture(x: Float, y: Float): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0L, 80L)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return accessibilityService.dispatchGesture(gesture, null, null)
    }
    
    private fun dispatchSwipeGesture(startX: Float, startY: Float, endX: Float, endY: Float, description: String): ExecutionResult {
        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }
        val duration = calculateSwipeDuration(startX, startY, endX, endY)
        val stroke = GestureDescription.StrokeDescription(path, 0L, duration)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        val accepted = accessibilityService.dispatchGesture(gesture, null, null)
        return if (accepted) ExecutionResult.success(description) 
        else ExecutionResult.failed(ErrorType.GESTURE_REJECTED, "Swipe gesture rejected")
    }
    
    private fun dispatchDragGesture(startX: Float, startY: Float, endX: Float, endY: Float, description: String): ExecutionResult {
        val path = Path().apply {
            moveTo(startX, startY)
            lineTo((startX + endX) / 2, (startY + endY) / 2)
            lineTo(endX, endY)
        }
        val duration = calculateDragDuration(startX, startY, endX, endY)
        val stroke = GestureDescription.StrokeDescription(path, 0L, duration)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        val accepted = accessibilityService.dispatchGesture(gesture, null, null)
        return if (accepted) ExecutionResult.success(description) 
        else ExecutionResult.failed(ErrorType.GESTURE_REJECTED, "Drag gesture rejected")
    }
    
    private fun dispatchLongPressGesture(x: Float, y: Float, description: String): ExecutionResult {
        val path = Path().apply { moveTo(x, y) }
        val duration = ViewConfiguration.getLongPressTimeout().toLong()
        val stroke = GestureDescription.StrokeDescription(path, 0L, duration)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        val accepted = accessibilityService.dispatchGesture(gesture, null, null)
        return if (accepted) ExecutionResult.success(description) 
        else ExecutionResult.failed(ErrorType.GESTURE_REJECTED, "Long press gesture rejected")
    }
    
    private fun dispatchDoubleTapGesture(x: Float, y: Float, description: String): ExecutionResult {
        val path = Path().apply { moveTo(x, y) }
        val tapDuration = ViewConfiguration.getTapTimeout().toLong()
        val doubleTapInterval = ViewConfiguration.getDoubleTapTimeout().toLong()
        val stroke1 = GestureDescription.StrokeDescription(path, 0L, tapDuration)
        val stroke2 = GestureDescription.StrokeDescription(path, doubleTapInterval, tapDuration)
        val gesture = GestureDescription.Builder().addStroke(stroke1).addStroke(stroke2).build()
        val accepted = accessibilityService.dispatchGesture(gesture, null, null)
        return if (accepted) ExecutionResult.success(description) 
        else ExecutionResult.failed(ErrorType.GESTURE_REJECTED, "Double tap gesture rejected")
    }
    
    private fun dispatchPinchGesture(centerX: Float, centerY: Float, radius: Float, pinchIn: Boolean, description: String): ExecutionResult {
        val (start1X, start1Y, end1X, end1Y) = if (pinchIn) {
            centerX - radius to centerY to centerX - radius / 2 to centerY
        } else {
            centerX - radius / 2 to centerY to centerX - radius to centerY
        }
        val (start2X, start2Y, end2X, end2Y) = if (pinchIn) {
            centerX + radius to centerY to centerX + radius / 2 to centerY
        } else {
            centerX + radius / 2 to centerY to centerX + radius to centerY
        }
        
        val path1 = Path().apply { moveTo(start1X, start1Y); lineTo(end1X, end1Y) }
        val path2 = Path().apply { moveTo(start2X, start2Y); lineTo(end2X, end2Y) }
        val stroke1 = GestureDescription.StrokeDescription(path1, 0L, 500L, true)
        val stroke2 = stroke1.continueStroke(path2, 0L, 500L, false)
        val gesture = GestureDescription.Builder().addStroke(stroke1).addStroke(stroke2).build()
        val accepted = accessibilityService.dispatchGesture(gesture, null, null)
        return if (accepted) ExecutionResult.success(description) 
        else ExecutionResult.failed(ErrorType.GESTURE_REJECTED, "Pinch gesture rejected")
    }
    
    private fun calculateSwipeDuration(x1: Float, y1: Float, x2: Float, y2: Float): Long {
        val distance = sqrt((x1 - x2).pow(2) + (y1 - y2).pow(2))
        return (distance / 2f).coerceIn(200, 800).toLong()
    }
    
    private fun calculateDragDuration(x1: Float, y1: Float, x2: Float, y2: Float): Long {
        val distance = sqrt((x1 - x2).pow(2) + (y1 - y2).pow(2))
        return (distance / 1.5f).coerceIn(300, 1500).toLong()
    }
    
    private fun findClickableAncestor(node: SemanticElement): SemanticElement? {
        if (node.clickable) return node
        node.childrenRefs.mapNotNull { /* Would need parent lookup */ null }
        return null
    }
    
    private fun findLongClickableAncestor(node: SemanticElement): SemanticElement? {
        if (node.longClickable) return node
        return null
    }
}

data class ExecutionResult(
    val success: Boolean,
    val message: String,
    val newScreenSnapshot: UnifiedScreen? = null,
    val verified: Boolean = false,
    val retryable: Boolean = false,
    val errorType: ErrorType? = null
) {
    companion object {
        fun success(msg: String) = ExecutionResult(true, msg)
        fun failed(errorType: ErrorType, msg: String) = ExecutionResult(
            false, msg, errorType = errorType, retryable = errorType.isRetryable()
        )
    }
}