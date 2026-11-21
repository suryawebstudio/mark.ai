package com.surya.jarvis.execution

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Bundle
import android.view.ViewConfiguration
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import com.surya.jarvis.accessibility.UnifiedScreen
import com.surya.jarvis.accessibility.SemanticElement
import com.surya.jarvis.JarvisAccessibilityService
import kotlin.math.sqrt
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.abs

class ActionExecutor(
    private val accessibilityService: AccessibilityService
) {
    private var currentConfirmationToken: String = ""

    fun execute(step: ActionStep, screen: UnifiedScreen, confirmationToken: String = ""): ExecutionResult {
        currentConfirmationToken = confirmationToken
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
        } finally {
            currentConfirmationToken = ""
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

        val result = JarvisAccessibilityService.control(
            snapshotId = screen.snapshotId,
            ref = step.ref,
            action = "tap",
            target = step.target,
            text = "",
            direction = "",
            confirmed = currentConfirmationToken.isNotBlank(),
            confirmationToken = currentConfirmationToken,
            visualCell = "",
            visualToken = ""
        )

        return if (result.first) {
            ExecutionResult.success("Tapped ${step.target}")
        } else {
            ExecutionResult.failed(ErrorType.GESTURE_REJECTED, result.second)
        }
    }

    private fun executeType(step: ActionStep.Type, screen: UnifiedScreen): ExecutionResult {
        val node = screen.findElementByRef(step.ref)
            ?: return ExecutionResult.failed(ErrorType.STALE_REF, "Ref not found in current screen")

        if (!node.editable) {
            return ExecutionResult.failed(ErrorType.NOT_EDITABLE, "Element is not editable")
        }

        val result = JarvisAccessibilityService.control(
            snapshotId = screen.snapshotId,
            ref = step.ref,
            action = "type",
            target = step.target,
            text = step.text,
            direction = "",
            confirmed = currentConfirmationToken.isNotBlank(),
            confirmationToken = currentConfirmationToken,
            visualCell = "",
            visualToken = ""
        )

        return if (result.first) {
            ExecutionResult.success("Typed into ${step.target}")
        } else {
            ExecutionResult.failed(ErrorType.TYPE_FAILED, result.second)
        }
    }

    private fun executeScroll(step: ActionStep.Scroll, screen: UnifiedScreen): ExecutionResult {
        val node = screen.findElementByRef(step.ref)
            ?: return ExecutionResult.failed(ErrorType.STALE_REF, "Ref not found in current screen")

        if (!node.scrollable) {
            return ExecutionResult.failed(ErrorType.NOT_SCROLLABLE, "Element is not scrollable")
        }

        val direction = when (step.direction.lowercase()) {
            "up", "backward", "previous" -> "up"
            "down", "forward", "next", "" -> "down"
            else -> return ExecutionResult.failed(ErrorType.UNKNOWN, "Use scroll direction up or down")
        }

        val result = JarvisAccessibilityService.control(
            snapshotId = screen.snapshotId,
            ref = step.ref,
            action = "scroll",
            target = step.target,
            text = "",
            direction = direction,
            confirmed = currentConfirmationToken.isNotBlank(),
            confirmationToken = currentConfirmationToken,
            visualCell = "",
            visualToken = ""
        )

        return if (result.first) {
            ExecutionResult.success("Scrolled ${step.direction}")
        } else {
            ExecutionResult.failed(ErrorType.SCROLL_FAILED, result.second)
        }
    }

    private fun executeLongPress(step: ActionStep.LongPress, screen: UnifiedScreen): ExecutionResult {
        val node = screen.findElementByRef(step.ref)
            ?: return ExecutionResult.failed(ErrorType.STALE_REF, "Ref not found")

        val result = JarvisAccessibilityService.control(
            snapshotId = screen.snapshotId,
            ref = step.ref,
            action = "long_press",
            target = step.target,
            text = "",
            direction = "",
            confirmed = currentConfirmationToken.isNotBlank(),
            confirmationToken = currentConfirmationToken,
            visualCell = "",
            visualToken = ""
        )

        return if (result.first) {
            ExecutionResult.success("Long-pressed ${step.target}")
        } else {
            ExecutionResult.failed(ErrorType.GESTURE_REJECTED, result.second)
        }
    }

    private fun executeSwipe(step: ActionStep.Swipe, screen: UnifiedScreen): ExecutionResult {
        val node = screen.findElementByRef(step.ref)
            ?: return ExecutionResult.failed(ErrorType.STALE_REF, "Ref not found")

        val direction = step.direction.lowercase()
        if (direction !in setOf("swipe_left", "swipe_right", "swipe_up", "swipe_down")) {
            return ExecutionResult.failed(ErrorType.UNKNOWN, "Invalid swipe direction")
        }

        val result = JarvisAccessibilityService.control(
            snapshotId = screen.snapshotId,
            ref = step.ref,
            action = direction,
            target = step.target,
            text = "",
            direction = "",
            confirmed = currentConfirmationToken.isNotBlank(),
            confirmationToken = currentConfirmationToken,
            visualCell = "",
            visualToken = ""
        )

        return if (result.first) {
            ExecutionResult.success("Swiped ${step.direction}")
        } else {
            ExecutionResult.failed(ErrorType.GESTURE_REJECTED, result.second)
        }
    }

    private fun executeDrag(step: ActionStep.Drag, screen: UnifiedScreen): ExecutionResult {
        val node = screen.findElementByRef(step.ref)
            ?: return ExecutionResult.failed(ErrorType.STALE_REF, "Ref not found")

        val result = JarvisAccessibilityService.control(
            snapshotId = screen.snapshotId,
            ref = step.ref,
            action = "drag",
            target = step.target,
            text = step.targetX.toString(),
            direction = step.targetY.toString(),
            confirmed = currentConfirmationToken.isNotBlank(),
            confirmationToken = currentConfirmationToken,
            visualCell = "",
            visualToken = ""
        )

        return if (result.first) {
            ExecutionResult.success("Dragged ${step.target}")
        } else {
            ExecutionResult.failed(ErrorType.GESTURE_REJECTED, result.second)
        }
    }

    private fun executeDoubleTap(step: ActionStep.DoubleTap, screen: UnifiedScreen): ExecutionResult {
        val node = screen.findElementByRef(step.ref)
            ?: return ExecutionResult.failed(ErrorType.STALE_REF, "Ref not found")

        val result = JarvisAccessibilityService.control(
            snapshotId = screen.snapshotId,
            ref = step.ref,
            action = "double_tap",
            target = step.target,
            text = "",
            direction = "",
            confirmed = currentConfirmationToken.isNotBlank(),
            confirmationToken = currentConfirmationToken,
            visualCell = "",
            visualToken = ""
        )

        return if (result.first) {
            ExecutionResult.success("Double-tapped ${step.target}")
        } else {
            ExecutionResult.failed(ErrorType.GESTURE_REJECTED, result.second)
        }
    }

    private fun executePinchZoom(step: ActionStep.PinchZoom, screen: UnifiedScreen): ExecutionResult {
        val node = screen.findElementByRef(step.ref)
            ?: return ExecutionResult.failed(ErrorType.STALE_REF, "Ref not found")

        val action = if (step.pinchIn) "pinch_in" else "pinch_out"
        val result = JarvisAccessibilityService.control(
            snapshotId = screen.snapshotId,
            ref = step.ref,
            action = action,
            target = step.target,
            text = "",
            direction = "",
            confirmed = currentConfirmationToken.isNotBlank(),
            confirmationToken = currentConfirmationToken,
            visualCell = "",
            visualToken = ""
        )

        return if (result.first) {
            ExecutionResult.success("Pinched ${if (step.pinchIn) "in" else "out"} ${step.target}")
        } else {
            ExecutionResult.failed(ErrorType.GESTURE_REJECTED, result.second)
        }
    }

    private fun executeGlobalAction(step: ActionStep.GlobalAction): ExecutionResult {
        val action = when (step.actionType) {
            ActionStep.GlobalActionType.BACK -> "back"
            ActionStep.GlobalActionType.HOME -> "home"
            ActionStep.GlobalActionType.RECENTS -> "overview"
            ActionStep.GlobalActionType.NOTIFICATIONS -> "notifications"
            ActionStep.GlobalActionType.QUICK_SETTINGS -> "quick_settings"
        }

        val result = JarvisAccessibilityService.control(
            snapshotId = "",
            ref = "",
            action = action,
            target = step.target,
            text = "",
            direction = "",
            confirmed = currentConfirmationToken.isNotBlank(),
            confirmationToken = currentConfirmationToken,
            visualCell = "",
            visualToken = ""
        )

        return if (result.first) {
            ExecutionResult.success("Global action: ${step.actionType}")
        } else {
            ExecutionResult.failed(ErrorType.GLOBAL_ACTION_FAILED, result.second)
        }
    }

    private fun executeIntent(step: ActionStep.IntentAction): ExecutionResult {
        return try {
            step.intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            accessibilityService.startActivity(step.intent)
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

    // Gesture dispatch methods (fallback for when accessibility service is not available)
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
        val start1X: Float
        val start1Y: Float
        val end1X: Float
        val end1Y: Float
        val start2X: Float
        val start2Y: Float
        val end2X: Float
        val end2Y: Float

        if (pinchIn) {
            start1X = centerX - radius
            start1Y = centerY
            end1X = centerX - radius / 2
            end1Y = centerY
            start2X = centerX + radius
            start2Y = centerY
            end2X = centerX + radius / 2
            end2Y = centerY
        } else {
            start1X = centerX - radius / 2
            start1Y = centerY
            end1X = centerX - radius
            end1Y = centerY
            start2X = centerX + radius / 2
            start2Y = centerY
            end2X = centerX + radius
            end2Y = centerY
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
        return (distance / 2f).coerceIn(200f, 800f).toLong()
    }

    private fun calculateDragDuration(x1: Float, y1: Float, x2: Float, y2: Float): Long {
        val distance = sqrt((x1 - x2).pow(2) + (y1 - y2).pow(2))
        return (distance / 1.5f).coerceIn(300f, 1500f).toLong()
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