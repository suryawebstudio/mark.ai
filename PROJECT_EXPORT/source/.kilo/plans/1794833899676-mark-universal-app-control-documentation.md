# PROJECT MARK — Universal Android App Control Implementation Plan

## Current State (as of 2026-11-16)

### ✅ COMPLETED: Phases 1-3 (by code agent)

**Phase 1: Core Reliability**
- Updated `accessibility_service_config.xml` with `canPerformGestures`, `canTakeScreenshot`, additional event types, flags
- Enhanced `JarvisAccessibilityService.kt` with new action types:
  - `long_press` - Long press via gesture dispatch
  - `swipe_left/right/up/down` - Element-based swipe gestures
  - `drag` - Drag from element center to target coordinates
  - `double_tap` - Two-tap gesture with proper timing
  - `pinch_in/out` - Two-finger pinch zoom gestures
- Fixed gesture duration calculation (dynamic based on distance)
- Improved screenshot error handling for all `ERROR_TAKE_SCREENSHOT_*` codes

**Phase 2: App Knowledge Base**
- Created knowledge data classes (`AppKnowledge.kt`): `AppKnowledge`, `KeyElement`, `NavigationPattern`, `Workflow`, `WorkflowStep`, `ActionTemplate`, `VerificationTemplate`, `DialogPattern`, `IntentTemplate`, `VersionSnapshot`
- Room database (`KnowledgeDatabase.kt`) for persisting learned app knowledge
- `AppKnowledgeManager` for loading bundled JSON assets and managing version migrations
- ML Kit OCR integration (`OcrProcessor.kt`) for text recognition on visual screenshots
- Bundled JSON knowledge files for 6 apps: WhatsApp, Gmail, YouTube, Google Maps, Instagram, Chrome

**Phase 3: Advanced Interactions**
- Updated `LiveVoiceManager.kt`:
  - Added `OcrProcessor` initialization and cleanup
  - Enhanced `captureVisualScreen()` to include OCR results in Gemini vision session
  - Added `visual_tap` as separate function declaration
  - Expanded `control_visible_screen` action enum with all new action types
  - Added `performVisualTap()` method for visual_tap function
- Updated `build.gradle.kts` with all required dependencies

### ❌ PENDING: Phases 4-5 (to be implemented)

---

## Phase 4: Verification & Recovery System

### 4.1 Create VerificationEngine
**File:** `app/src/main/java/com/surya/jarvis/execution/VerificationEngine.kt`

Implement verification strategies:
- `ELEMENT_PRESENT` - Wait for element to appear after action
- `ELEMENT_GONE` - Wait for element to disappear
- `TEXT_CONTAINS` - Verify text content in target element
- `STATE_CHANGED` - Verify element state changed (checked, selected, focused)
- `SCREEN_CHANGE` - Verify package/activity changed (navigation)
- `GLOBAL_STATE` - Verify system state (home, recents, notifications)
- `TREE_DIFF` - Compare before/after accessibility trees

**Data classes needed:**
```kotlin
enum class VerificationType { ELEMENT_PRESENT, ELEMENT_GONE, TEXT_CONTAINS, STATE_CHANGED, SCREEN_CHANGE, GLOBAL_STATE, TREE_DIFF }
data class VerificationConfig(val type: VerificationType, val params: Map<String, String>)
data class VerificationResult(val passed: Boolean, val message: String = "", val taskComplete: Boolean = false)
```

### 4.2 Create RecoveryEngine
**File:** `app/src/main/java/com/surya/jarvis/execution/RecoveryEngine.kt`

Implement recovery actions for each error type:
- `STALE_REF` → Re-inspect screen, re-plan
- `AMBIGUOUS_TARGET` → Re-inspect, request clarification
- `DISABLED_TARGET` → Wait + retry, or find alternative
- `NOT_FOUND` → Scroll, navigate, search, explore
- `NOT_VISIBLE` → Scroll to reveal, or navigate
- `PERMISSION_DIALOG` → User guidance, wait for dismissal
- `LOADING_SCREEN` → Wait with timeout, then retry
- `UNEXPECTED_SCREEN` → Navigate BACK to known state
- `GESTURE_REJECTED` → Retry with adjusted coordinates
- `TIMEOUT` → Retry step, then escalate
- `SECURE_WINDOW` → Escalate (cannot bypass)

**Recovery action types:**
```kotlin
sealed class RecoveryAction {
    data class ReinspectAndReplan(val reason: String) : RecoveryAction()
    data class WaitAndRetry(val delayMs: Long, val reason: String) : RecoveryAction()
    data class Explore(val reason: String) : RecoveryAction()
    data class UserGuidance(val message: String) : RecoveryAction()
    data class NavigateBackToKnown(val reason: String) : RecoveryAction()
    data class RetryWithAdjustment(val reason: String) : RecoveryAction()
    data class Escalate(val reason: String) : RecoveryAction()
}
```

### 4.3 Create SafetyGates
**File:** `app/src/main/java/com/surya/jarvis/execution/SafetyGates.kt`

Implement confirmation policy:
- Define sensitive action patterns (send, delete, pay, call, share, post, install, etc.)
- Check action + target + text for sensitivity
- Generate confirmation prompts
- Enforce `confirmed=true` for sensitive actions

### 4.4 Create Enhanced TaskCoordinator
**File:** `app/src/main/java/com/surya/jarvis/execution/TaskCoordinator.kt`

Replace current basic task state machine in `LiveVoiceManager` with full coordinator:
- Integrate `ActionPlanner`, `ActionExecutor`, `VerificationEngine`, `RecoveryEngine`, `SafetyGates`
- Implement retry policy with exponential backoff
- Handle confirmation flow properly
- Manage task lifecycle (start → observe → act → verify → recover → complete)

### 4.5 Create ActionPlanner
**File:** `app/src/main/java/com/surya/jarvis/execution/ActionPlanner.kt`

- Load `AppKnowledge` for target app
- Match current screen to known screen in knowledge base
- Find matching workflow for user intent
- Instantiate workflow with current context (parameter substitution)
- Insert verification steps after each action
- Mark sensitive steps requiring confirmation
- Fallback to LLM-based planning when no workflow exists

### 4.6 Create ActionExecutor
**File:** `app/src/main/java/com/surya/jarvis/execution/ActionExecutor.kt`

- Execute `ActionStep` using `JarvisAccessibilityService`
- Implement fallback chain:
  1. Accessibility action (`performAction`)
  2. Gesture to element bounds center
  3. Coordinate gesture (visual fallback)
  4. Intent/System action
- Return `ExecutionResult` with success, error type, retryable flag

---

## Phase 5: Learning System

### 5.1 Create ExplorationEngine
**File:** `app/src/main/java/com/surya/jarvis/exploration/ExplorationEngine.kt`

- Detect unfamiliar apps (not in knowledge base, user-installed)
- Launch app and explore main screens (max 10 screens)
- Extract key elements by role from accessibility tree
- Identify navigation elements (tabs, buttons, lists)
- Navigate through app by tapping unexplored navigation elements
- Save discovered knowledge to `AppKnowledgeManager`
- Determine accessibility level (FULL/PARTIAL/MINIMAL/NONE)

### 5.2 Implement User Feedback Loop
**File:** `app/src/main/java/com/surya/jarvis/execution/UserFeedbackHandler.kt`

- Allow user to correct failed actions
- Map user corrections to key element updates
- Update `resourceIds`, `textPatterns`, `descriptionPatterns` for roles
- Persist corrections to Room database
- Learn from successful workflows not in knowledge base

### 5.3 Version Detection & Migration
**Enhance:** `AppKnowledgeManager.kt`

- Detect version changes on app launch (`detectVersionChange()`)
- Auto-migrate key elements using fuzzy matching (text/description similarity)
- Mark workflows as "needs verification" after migration
- Prompt user to verify critical workflows after major version changes
- Maintain `versionHistory` with UI changes

---

## Implementation Order

### Week 1: Core Verification & Recovery
1. Create `execution` package
2. Implement `VerificationEngine.kt` with all 7 strategies
3. Implement `RecoveryEngine.kt` with all 11 error types
4. Implement `SafetyGates.kt` with confirmation policy
5. Unit test each component

### Week 2: Planner & Executor
1. Implement `ActionPlanner.kt` with workflow instantiation
2. Implement `ActionExecutor.kt` with fallback chain
3. Implement `TaskCoordinator.kt` integrating all components
4. Replace `LiveVoiceManager` task state machine with `TaskCoordinator`
5. Integration test with existing actions

### Week 3: Learning System
1. Create `exploration` package
2. Implement `ExplorationEngine.kt`
3. Implement `UserFeedbackHandler.kt`
4. Enhance `AppKnowledgeManager` with version migration
5. Test with unfamiliar app

### Week 4: Integration & Polish
1. Add more bundled app knowledge files (target 20 apps)
2. End-to-end testing of multi-step workflows
3. Performance optimization
4. Error handling edge cases

---

## Key Technical Decisions (Already Resolved)

1. **Primary interaction**: AccessibilityService (production) with UI Automator patterns informing design
2. **Visual fallback**: `takeScreenshot()` + 8×12 grid + ML Kit OCR (not MediaProjection)
3. **App knowledge storage**: Bundled JSON assets + Room DB for learned knowledge
4. **Cross-app workflow**: Single `TaskCoordinator` with per-app `AppKnowledge`
5. **Learning system**: On-device only, no cloud assistance
6. **Verification strategy**: Accessibility tree diff + element presence + text content

---

## Validation Criteria

### Phase 4 Complete When:
- [ ] All 7 verification strategies work correctly
- [ ] All 11 error types have recovery actions
- [ ] Safety gates block sensitive actions without confirmation
- [ ] Task coordinator executes 10+ step workflows without manual intervention
- [ ] Retry policy handles transient failures

### Phase 5 Complete When:
- [ ] Exploration engine discovers key elements in unfamiliar app
- [ ] User corrections persist and improve future actions
- [ ] Version migration preserves workflows across app updates
- [ ] System handles 20+ apps with bundled knowledge

---

## Dependencies

- All Phase 1-3 code already in place
- Room database already configured
- ML Kit OCR already integrated
- `AppKnowledgeManager` already loads bundled JSON
- `JarvisAccessibilityService` already supports all required actions

---

## Next Steps

Switch to implementation agent to begin Phase 4. Start with creating the `execution` package and `VerificationEngine.kt`.