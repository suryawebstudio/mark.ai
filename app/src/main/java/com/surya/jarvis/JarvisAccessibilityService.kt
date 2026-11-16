package com.surya.jarvis

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.util.Base64
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.Locale
import java.util.UUID

/**
 * Provides narrow, visible-screen automation for MARK.
 *
 * MARK follows an observe -> act -> verify loop. Every actionable element is
 * assigned a ref that belongs to the latest snapshot. Actions against an old
 * snapshot are rejected instead of being guessed against a changed screen.
 */
class JarvisAccessibilityService : AccessibilityService() {

    private data class RefEntry(
        val role: String,
        val label: String,
        val description: String,
        val nativeId: String?
    )

    data class VisualCapture(
        val jpegBase64: String,
        val width: Int,
        val height: Int
    )

    companion object {
        @Volatile
        private var current: JarvisAccessibilityService? = null

        fun isEnabled(): Boolean = current != null

        fun inspectScreen(): String = current?.describeVisibleScreen()
            ?: errorMessage("Accessibility control is not enabled. Open Android Accessibility settings and enable MARK.")

        fun captureVisualScreen(): Pair<Boolean, VisualCapture?> = current?.captureScreenWithGrid()
            ?: (false to null)

        fun control(
            snapshotId: String,
            ref: String,
            action: String,
            target: String,
            text: String,
            direction: String,
            confirmed: Boolean
        ): Pair<Boolean, String> = current?.performControl(
            snapshotId,
            ref,
            action,
            target,
            text,
            direction,
            confirmed
        ) ?: (false to "Accessibility control is not enabled. Enable MARK in Android Accessibility settings first.")

        private fun errorMessage(message: String): String =
            JSONObject().put("success", false).put("message", message).toString()
    }

    private var snapshotCounter = 0L
    private var currentSnapshotId: String? = null
    private val refs = LinkedHashMap<String, RefEntry>()
    private var snapshotTruncated = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        current = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Screens are queried only after an explicit MARK tool call. No screen
        // contents are stored or uploaded from accessibility events.
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        refs.clear()
        currentSnapshotId = null
        if (current === this) current = null
        super.onDestroy()
    }

    private fun performControl(
        snapshotId: String,
        ref: String,
        rawAction: String,
        target: String,
        text: String,
        rawDirection: String,
        confirmed: Boolean
    ): Pair<Boolean, String> {
        val action = rawAction.trim().lowercase(Locale.ROOT)
        val direction = rawDirection.trim().lowercase(Locale.ROOT)

        if (action.isBlank()) return false to "Specify a visible-screen action."
        if (isSensitive(action, target, text) && !confirmed) {
            return false to "This action may send, delete, purchase, call, or share something. Ask the user for confirmation, then repeat it with confirmed=true."
        }

        if (action in setOf("back", "home", "overview", "recent_apps")) {
            return when (action) {
                "back" -> globalAction(GLOBAL_ACTION_BACK, "Went back.")
                "home" -> globalAction(GLOBAL_ACTION_HOME, "Went to the Home screen.")
                else -> globalAction(GLOBAL_ACTION_RECENTS, "Opened recent apps.")
            }
        }

        val expectedSnapshot = currentSnapshotId
        if (expectedSnapshot.isNullOrBlank() || snapshotId.isBlank()) {
            return false to "No current screen snapshot. Call inspect_visible_screen before this action."
        }
        if (snapshotId != expectedSnapshot) {
            return false to "The screen changed and this snapshot is stale. Call inspect_visible_screen again and use a new ref."
        }
        if (ref.isBlank()) {
            return false to "A snapshot ref is required. Call inspect_visible_screen and use the exact ref for one visible element."
        }

        val entry = refs[ref]
            ?: return false to "That ref is not valid for the current snapshot. Call inspect_visible_screen again."
        val node = resolveRef(entry)
            ?: return false to "That ref is stale or ambiguous on the current screen. Call inspect_visible_screen again and use a new ref."
        if (!node.isVisibleToUser && action != "scroll") {
            return false to "That ref is no longer visible. Call inspect_visible_screen again."
        }
        if (!node.isEnabled) return false to "The selected control is disabled."

        return when (action) {
            "tap", "click", "press" -> tapRef(node, entry.label)
            "type", "input", "write" -> typeIntoRef(node, text, entry.label)
            "scroll" -> scrollRef(node, direction)
            else -> false to "Unsupported visible-screen action. Use tap, type, scroll, back, home, or overview."
        }
    }

    private fun tapRef(node: AccessibilityNodeInfo, label: String): Pair<Boolean, String> {
        val clickable = if (node.isClickable) node else clickableAncestor(node)
            ?: return false to "The selected element is not clickable. Inspect the screen and choose a clickable control."
        val success = clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        return if (success) true to "Tapped the visible control '${label.ifBlank { "selected element" }}'."
        else false to "Android rejected the click for the selected control."
    }

    private fun typeIntoRef(node: AccessibilityNodeInfo, text: String, label: String): Pair<Boolean, String> {
        if (text.isBlank()) return false to "Specify the text to enter."
        val editable = if (node.isEditable) node else editableDescendant(node)
            ?: return false to "The selected element is not an editable text field."
        val arguments = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        val success = editable.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
        return if (success) true to "Entered text into the visible field${label.takeIf { it.isNotBlank() }?.let { " '$it'" } ?: ""}."
        else false to "The visible text field could not accept text."
    }

    private fun scrollRef(node: AccessibilityNodeInfo, direction: String): Pair<Boolean, String> {
        if (!node.isScrollable) return false to "The selected element is not scrollable. Inspect the screen and choose a scrollable area."
        val action = when (direction) {
            "up", "backward", "previous" -> AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            "down", "forward", "next", "" -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            else -> return false to "Use scroll direction up or down."
        }
        return if (node.performAction(action)) {
            true to "Scrolled ${if (action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) "down" else "up"}."
        } else false to "The visible area could not be scrolled."
    }

    private fun globalAction(action: Int, successMessage: String): Pair<Boolean, String> =
        if (performGlobalAction(action)) true to successMessage
        else false to "Android rejected that navigation action."

    private fun describeVisibleScreen(): String {
        val root = rootInActiveWindow
            ?: return JSONObject().put("success", false).put("message", "No accessible screen is currently visible.").toString()

        snapshotCounter += 1
        val snapshotId = "a${snapshotCounter.toString(36)}${UUID.randomUUID().toString().take(6)}"
        val nextRefs = LinkedHashMap<String, RefEntry>()
        val elements = JSONArray()
        snapshotTruncated = false
        collectElements(root, elements, nextRefs, snapshotId, 0)
        refs.clear()
        refs.putAll(nextRefs)
        currentSnapshotId = snapshotId

        return JSONObject()
            .put("success", true)
            .put("snapshot_id", snapshotId)
            .put("package", root.packageName?.toString().orEmpty().ifBlank { "unknown" })
            .put("class", root.className?.toString().orEmpty().ifBlank { "unknown" })
            .put("ref_count", refs.size)
            .put("complete", !snapshotTruncated)
            .put("elements", elements)
            .toString()
    }

    /**
     * Capture one current-screen image for apps whose UI is not exposed through
     * accessibility. The image is deliberately separate from the semantic
     * snapshot and is never captured continuously.
     */
    private fun captureScreenWithGrid(): Pair<Boolean, VisualCapture?> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return false to null
        }

        val executor = Executors.newSingleThreadExecutor()
        val latch = CountDownLatch(1)
        var result: VisualCapture? = null
        try {
            takeScreenshot(
                Display.DEFAULT_DISPLAY,
                executor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(screenshot: ScreenshotResult) {
                        try {
                            val source = screenshot.hardwareBuffer
                            val bitmap = Bitmap.wrapHardwareBuffer(source, screenshot.colorSpace)
                                ?.copy(Bitmap.Config.ARGB_8888, true)
                            source.close()
                            if (bitmap != null) {
                                drawGroundingGrid(bitmap)
                                val output = ByteArrayOutputStream()
                                bitmap.compress(Bitmap.CompressFormat.JPEG, 82, output)
                                val width = bitmap.width
                                val height = bitmap.height
                                bitmap.recycle()
                                result = VisualCapture(
                                    Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP),
                                    width,
                                    height
                                )
                            }
                        } catch (_: Exception) {
                            result = null
                        } finally {
                            latch.countDown()
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        latch.countDown()
                    }
                }
            )
            latch.await(3, TimeUnit.SECONDS)
        } catch (_: Exception) {
            return false to null
        } finally {
            executor.shutdownNow()
        }
        return (result != null) to result
    }

    private fun drawGroundingGrid(bitmap: Bitmap) {
        val canvas = Canvas(bitmap)
        val columns = 8
        val rows = 12
        val cellWidth = bitmap.width.toFloat() / columns
        val cellHeight = bitmap.height.toFloat() / rows
        val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(150, 255, 0, 255)
            strokeWidth = 2f
            style = Paint.Style.STROKE
        }
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = (bitmap.width.coerceAtMost(bitmap.height) * 0.028f).coerceAtLeast(18f)
            style = Paint.Style.FILL
            setShadowLayer(4f, 1f, 1f, Color.BLACK)
        }
        for (column in 0..columns) {
            val x = column * cellWidth
            canvas.drawLine(x, 0f, x, bitmap.height.toFloat(), linePaint)
        }
        for (row in 0..rows) {
            val y = row * cellHeight
            canvas.drawLine(0f, y, bitmap.width.toFloat(), y, linePaint)
        }
        for (column in 0 until columns) {
            canvas.drawText(('A'.code + column).toChar().toString(), column * cellWidth + 8f, textPaint.textSize + 8f, textPaint)
        }
        for (row in 0 until rows) {
            canvas.drawText((row + 1).toString(), 8f, row * cellHeight + textPaint.textSize + 8f, textPaint)
        }
    }

    private fun collectElements(
        node: AccessibilityNodeInfo,
        elements: JSONArray,
        outputRefs: MutableMap<String, RefEntry>,
        snapshotId: String,
        depth: Int
    ) {
        if (depth > 20 || elements.length() >= 100) {
            snapshotTruncated = true
            return
        }
        val text = node.text?.toString()?.trim().orEmpty()
        val description = node.contentDescription?.toString()?.trim().orEmpty()
        val role = node.className?.toString()?.substringAfterLast('.') ?: "node"
        val actionable = node.isClickable || node.isEditable || node.isScrollable || node.actionList.isNotEmpty()
        val visible = node.isVisibleToUser || actionable
        val nativeId = node.viewIdResourceName?.takeIf { it.isNotBlank() }

        if (visible && (text.isNotBlank() || description.isNotBlank() || actionable)) {
            val ref = if (actionable) "@$snapshotId:e${outputRefs.size + 1}" else ""
            val label = text.ifBlank { description }
            val item = JSONObject()
                .put("role", role)
                .put("text", text)
                .put("description", description)
                .put("name", text.ifBlank { description })
                .put("visible", node.isVisibleToUser)
                .put("enabled", node.isEnabled)
                .put("clickable", node.isClickable)
                .put("editable", node.isEditable)
                .put("scrollable", node.isScrollable)
                .put("states", JSONArray().apply {
                    if (node.isEnabled) put("enabled") else put("disabled")
                    if (node.isFocused) put("focused")
                    if (node.isChecked) put("checked")
                    if (node.isSelected) put("selected")
                })
            if (nativeId != null) item.put("native_id", nativeId)
            if (ref.isNotBlank()) {
                item.put("ref", ref)
                item.put("actions", JSONArray().apply {
                    if (node.isClickable) put("click")
                    if (node.isEditable) put("type")
                    if (node.isScrollable) put("scroll")
                })
                val bounds = Rect()
                node.getBoundsInScreen(bounds)
                item.put("bounds", JSONObject()
                    .put("left", bounds.left)
                    .put("top", bounds.top)
                    .put("right", bounds.right)
                    .put("bottom", bounds.bottom))
                outputRefs[ref] = RefEntry(role, label, description, nativeId)
            }
            elements.put(item)
        }
        for (index in 0 until node.childCount) {
            node.getChild(index)?.let { collectElements(it, elements, outputRefs, snapshotId, depth + 1) }
        }
    }

    /**
     * Re-identify a ref against the current accessibility tree instead of
     * trusting the AccessibilityNodeInfo retained during the old snapshot.
     * Stable identity is role + name/description + optional native view id.
     * Ambiguous matches fail closed so MARK never guesses between controls.
     */
    private fun resolveRef(entry: RefEntry): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        val matches = mutableListOf<AccessibilityNodeInfo>()

        fun walk(node: AccessibilityNodeInfo, depth: Int) {
            if (depth > 20 || matches.size > 1) return
            val text = node.text?.toString()?.trim().orEmpty()
            val description = node.contentDescription?.toString()?.trim().orEmpty()
            val role = node.className?.toString()?.substringAfterLast('.') ?: "node"
            val actionable = node.isClickable || node.isEditable || node.isScrollable || node.actionList.isNotEmpty()
            val visible = node.isVisibleToUser || actionable
            val nativeId = node.viewIdResourceName?.takeIf { it.isNotBlank() }
            val label = text.ifBlank { description }
            val identityMatches = visible && actionable && role == entry.role &&
                label == entry.label && description == entry.description &&
                (entry.nativeId == null || nativeId == entry.nativeId)
            if (identityMatches) matches += node
            for (index in 0 until node.childCount) {
                node.getChild(index)?.let { walk(it, depth + 1) }
            }
        }

        walk(root, 0)
        return matches.singleOrNull()
    }

    private fun clickableAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var parent = node.parent
        while (parent != null) {
            if (parent.isClickable) return parent
            parent = parent.parent
        }
        return null
    }

    private fun editableDescendant(node: AccessibilityNodeInfo): AccessibilityNodeInfo? =
        if (node.isEditable) node
        else (0 until node.childCount).asSequence()
            .mapNotNull { index -> node.getChild(index)?.let(::editableDescendant) }
            .firstOrNull()

    private fun isSensitive(action: String, target: String, text: String): Boolean {
        val value = "$action $target $text".lowercase(Locale.ROOT)
        return listOf(
            "send", "submit", "purchase", "buy", "pay", "delete", "remove",
            "call", "share", "post", "publish", "transfer", "confirm", "install"
        ).any(value::contains)
    }
}
