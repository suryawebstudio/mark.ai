# ANDROID_INTERACTION_REFERENCE.md
## Detailed Documentation for Every Interaction Type

**Project:** MARK (formerly JARVIS) — Universal Android App Control System  
**Last Updated:** 2026-11-16

---

## 1. TAPPING INTERACTIONS

### 1.1 Single Tap

**Technical Explanation:**  
A single tap is the most fundamental interaction. On Android, it can be executed via:
1. **Accessibility Action:** `AccessibilityNodeInfo.performAction(AccessibilityNodeInfo.ACTION_CLICK)` - Preferred for elements in the accessibility tree
2. **Gesture Dispatch:** `AccessibilityService.dispatchGesture()` with a zero-length path at element center - Fallback when accessibility action fails
3. **Visual Fallback:** Coordinate-based tap via grid cell - For elements not in accessibility tree

**Android API/Method:**
```kotlin
// Method 1: Accessibility Action (PRIMARY)
node.performAction(AccessibilityNodeInfo.ACTION_CLICK)

// Method 2: Gesture to Element Bounds (FALLBACK)
val bounds = Rect()
node.getBoundsInScreen(bounds)
val centerX = (bounds.left + bounds.right) / 2f
val centerY = (bounds.top + bounds.bottom) / 2f
val path = Path().apply { moveTo(centerX, centerY) }
val stroke = GestureDescription.StrokeDescription(path, 0, ViewConfiguration.getTapTimeout())
val gesture = GestureDescription.Builder().addStroke(stroke).build()
dispatchGesture(gesture, callback, handler)

// Method 3: Visual Fallback Grid Tap (LAST RESORT)
// See Section 1.5
```

**How MARK Identifies Target:**
1. Call `inspect_visible_screen` → get snapshot with refs (e.g., `@a1abc123:e1`)
2. Match target by: text label, contentDescription, resource-id, role
3. Verify element is `clickable` and `visibleToUser`
4. Use exact `snapshot_id` and `ref` in `control_visible_screen`

**How MARK Executes:**
```kotlin
// In JarvisAccessibilityService.control()
when (action) {
    "tap", "click", "press" -> tapRef(node, entry.label)
}

private fun tapRef(node: AccessibilityNodeInfo, label: String): Pair<Boolean, String> {
    val clickable = if (node.isClickable) node else clickableAncestor(node)
        ?: return false to "The selected element is not clickable."
    val success = clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    return if (success) true to "Tapped '$label'." else false to "Android rejected the click."
}
```

**How MARK Verifies Result:**
1. Re-inspect screen (`inspect_visible_screen`) after 300-500ms
2. Check for expected changes: new screen, dialog, element state change
3. Compare before/after accessibility tree for:
   - New package/activity (navigation occurred)
   - Target element state (checked, selected, text changed)
   - New elements appearing (dialog, menu, list item)

**Failure Handling:**
| Failure | Detection | Recovery |
|---------|-----------|----------|
| Element not clickable | `!clickableAncestor(node)` | Find parent clickable, or use gesture to bounds |
| Stale ref | `resolveRef()` returns null | Re-inspect screen, get new ref |
| Click rejected | `performAction()` returns false | Retry with gesture to bounds center |
| Wrong target | Verification fails | Recovery engine: navigate back, re-plan |

---

### 1.2 Double Tap

**Technical Explanation:**  
Double tap is two rapid taps at the same location. Used for: zoom in maps/images, select word in text, some custom controls.

**Android API/Method:**
```kotlin
// Via GestureDescription with two zero-length strokes
val path = Path().apply { moveTo(x, y) }
val stroke1 = GestureDescription.StrokeDescription(path, 0, ViewConfiguration.getTapTimeout())
val stroke2 = GestureDescription.StrokeDescription(
    path, 
    ViewConfiguration.getDoubleTapTimeout(),  // Delay between taps
    ViewConfiguration.getTapTimeout()
)
val gesture = GestureDescription.Builder()
    .addStroke(stroke1)
    .addStroke(stroke2)
    .build()
dispatchGesture(gesture, callback, handler)
```

**When MARK Should Use:**
- Google Maps zoom (double tap to zoom in)
- Image gallery zoom
- Text selection (double tap to select word)
- Custom app controls that require double tap

**Implementation for MARK:**
```kotlin
// Add to control_visible_screen action types
"double_tap" -> doubleTapRef(node, entry.label)

private fun doubleTapRef(node: AccessibilityNodeInfo, label: String): Pair<Boolean, String> {
    val clickable = if (node.isClickable) node else clickableAncestor(node)
        ?: return false to "Element not clickable for double tap."
    val bounds = Rect()
    clickable.getBoundsInScreen(bounds)
    val x = (bounds.left + bounds.right) / 2f
    val y = (bounds.top + bounds.bottom) / 2f
    
    val path = Path().apply { moveTo(x, y) }
    val tapDuration = ViewConfiguration.getTapTimeout().toLong()
    val doubleTapInterval = ViewConfiguration.getDoubleTapTimeout().toLong()
    
    val stroke1 = GestureDescription.StrokeDescription(path, 0, tapDuration)
    val stroke2 = GestureDescription.StrokeDescription(path, doubleTapInterval, tapDuration)
    
    val gesture = GestureDescription.Builder().addStroke(stroke1).addStroke(stroke2).build()
    val accepted = dispatchGesture(gesture, null, null)
    return if (accepted) true to "Double-tapped '$label'." else false to "Gesture rejected."
}
```

---

### 1.3 Coordinate-Based Tap (Visual Fallback)

**Technical Explanation:**  
Used when element is NOT in accessibility tree. MARK captures screenshot with 8×12 grid, user/LLM identifies grid cell, MARK dispatches tap to cell center.

**Current MARK Implementation:**
```kotlin
// JarvisAccessibilityService.performVisualTap()
private fun performVisualTap(cell: String, suppliedToken: String): Pair<Boolean, String> {
    // Validate token, parse cell (e.g., "C7"), map to coordinates
    val normalized = cell.trim().uppercase(Locale.ROOT)
    val match = Regex("^([A-H])(1[0-2]|[1-9])$").matchEntire(normalized)
    val column = match.groupValues[1][0] - 'A'  // 0-7
    val row = match.groupValues[2].toInt() - 1   // 0-11
    
    val x = (column + 0.5f) * visualWidth / 8f
    val y = (row + 0.5f) * visualHeight / 12f
    
    val path = Path().apply { moveTo(x, y) }
    val gesture = GestureDescription.Builder()
        .addStroke(GestureDescription.StrokeDescription(path, 0, 80))
        .build()
    dispatchGesture(gesture, null, null)
}
```

**Limitations:**
- Grid resolution: ~135×200px per cell on 1080×2400 screen
- Cannot tap precise small targets (icons < 48dp)
- Single-use token prevents replay attacks
- 15-second token expiry

---

### 1.4 Tap Verification Checklist

| Verification Step | Method | Timeout |
|-------------------|--------|---------|
| Screen changed | Re-inspect, compare package/activity | 500ms |
| Dialog appeared | Check for `AlertDialog`, `Dialog` class nodes | 500ms |
| Navigation occurred | New activity/package in root node | 1000ms |
| Element state changed | Compare target node states (checked, selected) | 300ms |
| Text content updated | Check EditText/TextView content | 300ms |

---

## 2. LONG PRESS

### 2.1 Technical Explanation

Long press (press and hold) triggers contextual menus, drag initiation, or special actions. Duration typically 500ms+ (configurable via `ViewConfiguration.getLongPressTimeout()`).

### 2.2 Android API/Method

```kotlin
// Method 1: Accessibility Action (if supported)
node.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)

// Method 2: Gesture Dispatch (reliable fallback)
val path = Path().apply { moveTo(x, y) }
val longPressDuration = ViewConfiguration.getLongPressTimeout().toLong()  // ~500ms
val stroke = GestureDescription.StrokeDescription(path, 0, longPressDuration)
val gesture = GestureDescription.Builder().addStroke(stroke).build()
dispatchGesture(gesture, callback, handler)
```

### 2.3 MARK Implementation

```kotlin
// Add to control_visible_screen actions
"long_press" -> longPressRef(node, entry.label)

private fun longPressRef(node: AccessibilityNodeInfo, label: String): Pair<Boolean, String> {
    val target = if (node.isLongClickable) node else longClickableAncestor(node)
        ?: return false to "Element does not support long press."
    
    val bounds = Rect()
    target.getBoundsInScreen(bounds)
    val x = (bounds.left + bounds.right) / 2f
    val y = (bounds.top + bounds.bottom) / 2f
    
    val path = Path().apply { moveTo(x, y) }
    val duration = ViewConfiguration.getLongPressTimeout().toLong()
    val stroke = GestureDescription.StrokeDescription(path, 0, duration)
    val gesture = GestureDescription.Builder().addStroke(stroke).build()
    
    val accepted = dispatchGesture(gesture, null, null)
    return if (accepted) true to "Long-pressed '$label'." else false to "Gesture rejected."
}

private fun longClickableAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
    var parent = node.parent
    while (parent != null) {
        if (parent.isLongClickable) return parent
        parent = parent.parent
    }
    return null
}
```

### 2.4 Use Cases for MARK

| App | Element | Purpose |
|-----|---------|---------|
| WhatsApp | Message bubble | Select message (reply, forward, delete) |
| Instagram | Post image | Save to collection |
| Chrome | Link | Open in new tab / copy link |
| Files | File/folder | Multi-select mode |
| Home screen | App icon | Uninstall / app info / widget |
| Maps | Location | Drop pin / measure distance |

### 2.5 Verification

1. Wait 500-800ms after gesture
2. Re-inspect screen
3. Check for: contextual menu (`PopupMenu`, `MenuPopupWindow`), selection mode (checkable items), drag shadow

---

## 3. SCROLLING

### 3.1 Vertical Scrolling

**Technical Explanation:**  
Scrolling moves content within a scrollable container (ListView, RecyclerView, ScrollView, NestedScrollView). Accessibility provides `ACTION_SCROLL_FORWARD` (down) and `ACTION_SCROLL_BACKWARD` (up).

**Android API/Method:**
```kotlin
// Accessibility Action (PRIMARY)
node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)  // Down
node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) // Up

// Gesture Swipe (FALLBACK - more control over distance)
val path = Path().apply {
    moveTo(centerX, startY)
    lineTo(centerX, endY)
}
val stroke = GestureDescription.StrokeDescription(path, 0, 300)  // 300ms duration
```

**MARK Current Implementation:**
```kotlin
// JarvisAccessibilityService.scrollRef()
private fun scrollRef(node: AccessibilityNodeInfo, direction: String): Pair<Boolean, String> {
    if (!node.isScrollable) return false to "Element is not scrollable."
    val action = when (direction) {
        "up", "backward", "previous" -> AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        "down", "forward", "next", "" -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        else -> return false to "Use scroll direction up or down."
    }
    return if (node.performAction(action)) {
        true to "Scrolled ${if (action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) "down" else "up"}."
    } else false to "Could not scroll."
}
```

### 3.2 Horizontal Scrolling

```kotlin
// For horizontal scrollable containers (HorizontalScrollView, RecyclerView horizontal)
node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)  // Right
node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) // Left

// Or gesture swipe left/right
val path = Path().apply {
    moveTo(startX, centerY)
    lineTo(endX, centerY)
}
```

### 3.3 Nested Scrolling

**Challenge:** Multiple scrollable containers nested (e.g., chat list inside bottom sheet).

**MARK Strategy:**
1. Inspect screen → identify all scrollable elements with refs
2. Target specific container by ref (not just "scroll down")
3. Use `scrollRef(node, direction)` on the correct container
4. Verify scroll occurred by checking first/last visible item change

### 3.4 Infinite Scrolling / List End Detection

**Detection:**
- `ACTION_SCROLL_FORWARD` returns `false` when at bottom
- Last item in list remains same after scroll attempt
- "End of list" indicator (ProgressBar, "No more items" text)

**Implementation:**
```kotlin
private fun scrollToEnd(node: AccessibilityNodeInfo, maxAttempts: Int = 20): Boolean {
    var previousLastItemText = ""
    repeat(maxAttempts) {
        val currentLastItem = getLastVisibleItem(node)
        if (currentLastItem == previousLastItemText) return true  // Reached end
        previousLastItemText = currentLastItem
        if (!node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) return true
        Thread.sleep(300)  // Wait for load
    }
    return false
}
```

### 3.5 Scroll Verification

| Check | Method |
|-------|--------|
| Content moved | First visible item changed |
| New items loaded | Item count increased |
| Reached end | Scroll action returns false, last item unchanged |
| Loading indicator | ProgressBar visible during scroll |

---

## 4. SWIPING

### 4.1 Left/Right Swipes (Navigation)

**Technical Explanation:**  
Swipes are continuous gestures with movement. Used for: tab switching, carousel navigation, dismissing cards, navigation drawers.

**Android API/Method:**
```kotlin
// GestureDescription with line path
val path = Path().apply {
    moveTo(startX, centerY)
    lineTo(endX, centerY)
}
val stroke = GestureDescription.StrokeDescription(path, 0, 300)  // 300ms
val gesture = GestureDescription.Builder().addStroke(stroke).build()
dispatchGesture(gesture, callback, handler)
```

### 4.2 MARK Swipe Implementation

```kotlin
// Add to control_visible_screen
"swipe_left" -> swipeGesture(startX, startY, endX, endY, "left")
"swipe_right" -> swipeGesture(startX, startY, endX, endY, "right")
"swipe_up" -> swipeGesture(startX, startY, endX, endY, "up")
"swipe_down" -> swipeGesture(startX, startY, endX, endY, "down")

// Or semantic swipe on element
"swipe" -> swipeElement(node, direction)

private fun swipeElement(node: AccessibilityNodeInfo, direction: String): Pair<Boolean, String> {
    val bounds = Rect()
    node.getBoundsInScreen(bounds)
    val (startX, startY, endX, endY) = when (direction) {
        "left"  -> bounds.right to bounds.centerY() to bounds.left to bounds.centerY()
        "right" -> bounds.left to bounds.centerY() to bounds.right to bounds.centerY()
        "up"    -> bounds.centerX() to bounds.bottom to bounds.centerX() to bounds.top
        "down"  -> bounds.centerX() to bounds.top to bounds.centerX() to bounds.bottom
        else -> return false to "Invalid swipe direction"
    }
    // ... dispatch gesture
}
```

### 4.3 Use Cases

| App | Swipe Action | Purpose |
|-----|--------------|---------|
| Instagram | Left/Right on stories | Next/previous story |
| YouTube | Up/Down on video | Mini-player / close |
| Gmail | Left/Right on email | Archive / delete |
| Chrome | Left/Right on tab bar | Switch tabs |
| Maps | Two-finger rotate/tilt | 3D view (requires multi-touch) |
| Tinder-style | Left/Right on card | Dislike/like |

### 4.4 Multi-Touch Gestures (Pinch, Rotate)

**Technical Explanation:**  
Requires `GestureDescription` with multiple simultaneous strokes (`willContinue = true`).

```kotlin
// Pinch zoom example (two fingers)
val finger1 = Path().apply { moveTo(x1, y1); lineTo(x1 + dx, y1 + dy) }
val finger2 = Path().apply { moveTo(x2, y2); lineTo(x2 - dx, y2 - dy) }

val stroke1 = GestureDescription.StrokeDescription(finger1, 0, 500, true)  // willContinue
val stroke2 = stroke1.continueStroke(finger2, 0, 500, false)

val gesture = GestureDescription.Builder().addStroke(stroke1).addStroke(stroke2).build()
```

**MARK Support:** Implement for Maps zoom, image gallery pinch-to-zoom.

---

## 5. DRAGGING

### 5.1 Technical Explanation

Drag = press + move + release. Used for: reordering lists, moving sliders, drag-and-drop, map panning.

### 5.2 Android API/Method

```kotlin
// GestureDescription with continuous path
val path = Path().apply {
    moveTo(startX, startY)
    lineTo(midX, midY)   // Intermediate points for smooth curve
    lineTo(endX, endY)
}
val stroke = GestureDescription.StrokeDescription(path, 0, duration)
val gesture = GestureDescription.Builder().addStroke(stroke).build()
dispatchGesture(gesture, callback, handler)
```

### 5.3 MARK Drag Implementation

```kotlin
// Add to control_visible_screen
"drag" -> dragElement(node, targetX, targetY)  // Or dragToRef

private fun dragElement(node: AccessibilityNodeInfo, targetX: Float, targetY: Float): Pair<Boolean, String> {
    val bounds = Rect()
    node.getBoundsInScreen(bounds)
    val startX = bounds.centerX().toFloat()
    val startY = bounds.centerY().toFloat()
    
    val path = Path().apply {
        moveTo(startX, startY)
        // Add intermediate points for natural curve
        lineTo((startX + targetX) / 2, (startY + targetY) / 2)
        lineTo(targetX, targetY)
    }
    val duration = 500L  // Adjust based on distance
    val stroke = GestureDescription.StrokeDescription(path, 0, duration)
    val gesture = GestureDescription.Builder().addStroke(stroke).build()
    val accepted = dispatchGesture(gesture, null, null)
    return if (accepted) true to "Dragged element." else false to "Drag rejected."
}
```

### 5.4 Use Cases

| App | Drag Action | Purpose |
|-----|-------------|---------|
| Playlists | Drag track | Reorder playlist |
| Home screen | Drag icon | Move app / create folder |
| Maps | Drag map | Pan view |
| Sliders | Drag thumb | Adjust volume/brightness |
| Split screen | Drag divider | Resize panes |
| Files | Drag file to folder | Move file |

### 5.5 Drag-and-Drop Verification

1. After drag, re-inspect screen
2. Check: element at new position, drop zone highlighted, list reordered
3. For sliders: verify thumb position / value changed

---

## 6. TYPING / TEXT INPUT

### 6.1 Technical Explanation

Text input via accessibility uses `ACTION_SET_TEXT` with a Bundle argument. This bypasses keyboard but works reliably for EditText fields.

### 6.3 Android API/Method

```kotlin
// Accessibility Set Text (PRIMARY - no keyboard needed)
val arguments = Bundle().apply {
    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
}
node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)

// Alternative: Focus + Type via keyboard (if needed)
// 1. Focus element: node.performAction(ACTION_ACCESSIBILITY_FOCUS)
// 2. Show keyboard: InputMethodManager.showSoftInput()
// 3. Send key events (complex, avoid unless necessary)
```

### 6.3 MARK Current Implementation

```kotlin
// JarvisAccessibilityService.typeIntoRef()
private fun typeIntoRef(node: AccessibilityNodeInfo, text: String, label: String): Pair<Boolean, String> {
    if (text.isBlank()) return false to "Specify text to enter."
    val editable = if (node.isEditable) node else editableDescendant(node)
        ?: return false to "Not an editable text field."
    val arguments = Bundle().apply {
        putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
    }
    val success = editable.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
    return if (success) true to "Entered text into '$label'." else false to "Field could not accept text."
}
```

### 6.4 Advanced Text Operations

| Operation | Method |
|-----------|--------|
| **Clear text** | `ACTION_SET_TEXT` with empty string, or `ACTION_CLEAR_SELECTION` + `ACTION_SET_TEXT` |
| **Select all** | `ACTION_SELECT` with `Selection` arguments (API 24+) |
| **Replace selection** | `ACTION_SET_TEXT` replaces current selection |
| **Move cursor** | `ACTION_NEXT_AT_MOVEMENT_GRANULARITY` with `GRANULARITY_CHARACTER` |
| **Copy/Cut/Paste** | `ACTION_COPY`, `ACTION_CUT`, `ACTION_PASTE` (API 24+) |

### 6.5 Handling Different Keyboard Layouts

- **ACTION_SET_TEXT** bypasses keyboard entirely → layout independent
- For keyboard-required apps: use `InputMethodManager` + key events (complex)
- **Recommendation:** Always prefer `ACTION_SET_TEXT` via accessibility

### 6.6 Form Submission

```kotlin
// After filling fields, submit via:
"tap" on send/submit button (with confirmation)
// Or if form has no button:
node.performAction(AccessibilityNodeInfo.ACTION_IME_ACTION)  // Enter key
```

### 6.7 Autocomplete Handling

1. Type partial text via `ACTION_SET_TEXT`
2. Wait 500ms
3. Re-inspect screen for autocomplete dropdown (`ListPopupWindow`, `DropDownListView`)
4. Tap desired suggestion by ref

---

## 7. OTHER INTERACTIONS

### 7.1 Checkboxes

```kotlin
// Toggle via accessibility
node.performAction(AccessibilityNodeInfo.ACTION_CLICK)  // Toggles checked state

// Verify
val isChecked = node.isChecked
// Or force state:
val arguments = Bundle().apply { putBoolean(AccessibilityNodeInfo.ACTION_ARGUMENT_CHECKED, true) }
node.performAction(AccessibilityNodeInfo.ACTION_SET_CHECKED, arguments)  // API 24+
```

### 7.2 Radio Buttons

```kotlin
// Click to select (only one in group can be selected)
node.performAction(AccessibilityNodeInfo.ACTION_CLICK)

// Verify
val isChecked = node.isChecked
```

### 7.3 Switches / Toggles

```kotlin
// Click toggles
node.performAction(AccessibilityNodeInfo.ACTION_CLICK)

// Or explicit set (API 24+)
val arguments = Bundle().apply { putBoolean(AccessibilityNodeInfo.ACTION_ARGUMENT_CHECKED, true) }
node.performAction(AccessibilityNodeInfo.ACTION_SET_CHECKED, arguments)
```

### 7.4 Dropdown Menus / Spinners

```kotlin
// 1. Click dropdown to expand
node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
// 2. Wait for popup (re-inspect)
// 3. Find option in popup by text/description
// 4. Click option
// 5. Verify selection changed
```

### 7.5 Tabs

```kotlin
// TabLayout tabs are usually clickable
// Identify by: role="Tab", selected state
node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
// Verify: tab becomes selected, content changes
```

### 7.6 Sliders / SeekBars

```kotlin
// Method 1: Accessibility action (API 24+)
val arguments = Bundle().apply { 
    putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, progress)  // 0.0-1.0
}
node.performAction(AccessibilityNodeInfo.ACTION_SET_PROGRESS, arguments)

// Method 2: Drag thumb (gesture)
val bounds = Rect()
node.getBoundsInScreen(bounds)
val thumbX = bounds.left + (bounds.width() * progress)
// Drag from current thumb position to thumbX
```

### 7.7 Date/Time Pickers

```kotlin
// Complex - usually requires multiple steps:
// 1. Click field to open picker
// 2. Navigate picker UI (spinner, calendar grid, clock)
// 3. Set values via click/tap
// 4. Confirm (OK/Done button)

// Better: Use intent for known apps
// Intent.ACTION_PICK_DATE, etc. (but limited support)
```

### 7.8 Dialogs

| Dialog Type | Detection | Handling |
|-------------|-----------|----------|
| AlertDialog | Class `AlertDialog`, `AlertController$AlertView` | Tap button by text ("OK", "Cancel", "Allow") |
| Bottom Sheet | `BottomSheetDialog`, `BottomSheetBehavior` | Swipe up/down, tap options |
| Permission Dialog | Package `com.android.packageinstaller` | User must handle (MARK cannot bypass) |
| Toast | `Toast` class, transient | Read text via accessibility event |

### 7.9 Navigation Actions

```kotlin
// Global actions (no ref needed)
performGlobalAction(GLOBAL_ACTION_BACK)       // Back
performGlobalAction(GLOBAL_ACTION_HOME)       // Home
performGlobalAction(GLOBAL_ACTION_RECENTS)    // Recent apps
performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS) // Notification shade
performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS) // Quick settings
```

---

## 8. INTERACTION SUMMARY TABLE

| Interaction | Primary API | Fallback | Verification | Confirmation Required |
|-------------|-------------|----------|--------------|----------------------|
| Single Tap | `ACTION_CLICK` | Gesture to bounds | Screen change, state change | Sensitive actions only |
| Double Tap | Two-stroke gesture | N/A | Zoom/selection change | No |
| Long Press | `ACTION_LONG_CLICK` / Gesture | N/A | Menu appears | No |
| Vertical Scroll | `ACTION_SCROLL_FORWARD/BACKWARD` | Swipe gesture | Content moved | No |
| Horizontal Scroll | `ACTION_SCROLL_FORWARD/BACKWARD` | Swipe gesture | Content moved | No |
| Swipe | Multi-point gesture | N/A | Navigation/dismissal | No |
| Drag | Continuous gesture | N/A | Element moved | No |
| Type Text | `ACTION_SET_TEXT` | Keyboard input | Text in field | No (but confirm before submit) |
| Clear Text | `ACTION_SET_TEXT`("") | Select all + delete | Field empty | No |
| Checkbox | `ACTION_CLICK` / `ACTION_SET_CHECKED` | N/A | Checked state | No |
| Switch | `ACTION_CLICK` / `ACTION_SET_CHECKED` | N/A | Checked state | No |
| Radio | `ACTION_CLICK` | N/A | Selected state | No |
| Dropdown | Click + click option | N/A | Selection changed | No |
| Tab | `ACTION_CLICK` | N/A | Tab selected | No |
| Slider | `ACTION_SET_PROGRESS` / Drag thumb | N/A | Value changed | No |
| Back | `GLOBAL_ACTION_BACK` | N/A | Previous screen | No |
| Home | `GLOBAL_ACTION_HOME` | N/A | Launcher visible | No |
| Recents | `GLOBAL_ACTION_RECENTS` | N/A | Overview visible | No |

---

## 9. ERROR CODES AND HANDLING

### 9.1 AccessibilityService Error Codes

| Error Code | Constant | Meaning | MARK Handling |
|------------|----------|---------|---------------|
| 1 | `ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR` | System error | Retry once, then fail |
| 2 | `ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS` | Service not enabled | Prompt user to enable |
| 3 | `ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT` | Rate limited | Wait 1s, retry |
| 4 | `ERROR_TAKE_SCREENSHOT_INVALID_DISPLAY` | Invalid display ID | Use DEFAULT_DISPLAY |
| 5 | `ERROR_TAKE_SCREENSHOT_INVALID_WINDOW` | Invalid window ID | Use active window |
| 6 | `ERROR_TAKE_SCREENSHOT_SECURE_WINDOW` | FLAG_SECURE window | Cannot capture, use accessibility only |

### 9.2 MARK Execution Error Types

```kotlin
enum class ErrorType {
    STALE_REF,           // Ref not in current tree
    AMBIGUOUS_TARGET,    // Multiple matches
    DISABLED_TARGET,     // !isEnabled
    NOT_FOUND,           // Element not in tree
    PERMISSION_DIALOG,   // System permission dialog
    LOADING_SCREEN,      // App loading
    UNEXPECTED_SCREEN,   // Wrong app/activity
    GESTURE_REJECTED,    // dispatchGesture() = false
    TIMEOUT,             // Verification timeout
    SECURE_WINDOW,       // Cannot screenshot
    UNKNOWN
}
```

---

*End of ANDROID_INTERACTION_REFERENCE.md*