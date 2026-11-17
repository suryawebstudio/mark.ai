# MARK Android Build Recovery & Code Repair Plan

## Executive Summary

This plan addresses the complete recovery of the MARK Android AI assistant project build environment and implementation of the remaining execution architecture. The project has Phases 1-3 completed (Core Reliability, App Knowledge Base, Advanced Interactions) but Phases 4-5 (Verification & Recovery, Learning System) are partially implemented with placeholder code that needs to be connected to actual Android accessibility APIs.

## Current State Analysis

### Working Components
- ✅ LiveVoiceManager.kt — Full Gemini Live voice conversation with tool calls
- ✅ JarvisAccessibilityService.kt — Complete accessibility actions (tap, type, scroll, swipe, drag, long press, double tap, pinch, gestures)
- ✅ App Knowledge System — Room database, bundled JSON for 6 apps, ML Kit OCR
- ✅ Execution Package Classes — VerificationEngine, RecoveryEngine, ActionExecutor, ActionPlanner, SafetyGates, TaskCoordinator, ScreenObserver, ActionStep (all exist but need integration)
- ✅ Exploration Package — ExplorationEngine, UserFeedbackHandler (exist but need integration)
- ✅ Build Config — Gradle 9.6.1, Kotlin 2.3.21, AGP 9.3.1, all dependencies declared

### Missing/Blocking
- ❌ **Android SDK** — Ephemeral environment lost `/opt/android-sdk` (platforms android-36, build-tools 36.0.0)
- ❌ **ScreenObserver implementation** — Interface exists but DefaultScreenObserver returns null
- ❌ **TaskCoordinator integration** — Not connected to LiveVoiceManager
- ❌ **UnifiedScreen ↔ JarvisAccessibilityService bridge** — Semantic screen inspection not implemented
- ❌ **AppKnowledgeManager.getKnowledgeSync** — Used by execution package but expects sync API
- ❌ **OcrProcessor.recognizeText** — Used by LiveVoiceManager, needs suspend function

## Phase 1: Restore Android Build Environment

### 1.1 Verify Java & Gradle
```bash
java -version  # Should show 21.0.13
~/.gradle/wrapper/dists/gradle-9.6.1-bin/gradle-9.6.1/bin/gradle --version
```

### 1.2 Install Android Command-Line Tools
```bash
cd /tmp
wget --no-check-certificate https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip -O cmdline-tools.zip
mkdir -p /opt/android-sdk/cmdline-tools/latest
unzip -o cmdline-tools.zip -d /opt/android-sdk/cmdline-tools/latest
```

### 1.3 Accept Licenses & Install Platforms
```bash
export ANDROID_HOME=/opt/android-sdk
export PATH=$PATH:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools

yes | sdkmanager --licenses
sdkmanager "platforms;android-36" "build-tools;36.0.0" "platform-tools"
```

### 1.4 Configure Environment
```bash
echo "sdk.dir=/opt/android-sdk" > /workspace/app/local.properties
export ANDROID_HOME=/opt/android-sdk
export ANDROID_SDK_ROOT=/opt/android-sdk
```

### 1.5 Verify Gradle Detects SDK
```bash
cd /workspace/app
~/.gradle/wrapper/dists/gradle-9.6.1-bin/gradle-9.6.1/bin/gradle :app:androidDependencies --no-daemon
```

### Validation Criteria
- [ ] `sdkmanager --list` shows android-36 and build-tools;36.0.0 installed
- [ ] `./gradlew :app:androidDependencies` succeeds
- [ ] `ANDROID_HOME` points to valid SDK directory

## Phase 2: Fix Kotlin Implementation Errors

### 2.1 LiveVoiceManager.kt Issues

**Missing Methods** (referenced but not implemented):
- `status(message: String)` — ✅ EXISTS at line 1402-1406
- `fail(message: String)` — ✅ EXISTS at line 1412-1422
- `startPlayback()` — ✅ EXISTS at line 1287-1372
- `startAudioCapture()` — ✅ EXISTS at line 1116-1255
- `playAudio(bytes: ByteArray)` — ✅ EXISTS at line 1374-1384
- `interruptPlayback()` — ✅ EXISTS at line 1386-1400
- `handlePhoneToolCall(webSocket, toolCall)` — ✅ EXISTS at line 596-630
- `executeTaskTool(name, args)` — ✅ EXISTS at line 632-732
- `executePhoneTool(name, args)` — ✅ EXISTS at line 781-821
- `captureVisualScreen()` — ✅ EXISTS at line 824-868
- `performVisualTap(cell, token, confirmed)` — ✅ EXISTS at line 875-891

**Actual Issues to Fix**:
1. **OcrProcessor.recognizeText()** called with `runBlocking` at line 840 but method is `suspend` — need to ensure coroutine context
2. **Bitmap import missing** — `decodeBase64ToBitmap` uses `android.graphics.Bitmap` but import not present
3. **Unused camera imports** — Lines 7-10 import camera2 APIs used only in `captureCameraImage()`

### 2.2 JarvisAccessibilityService.kt Issues

**Syntax/Logic Issues**:
1. **Line 213**: `area = "${column / 2}:${row / 2}"` — integer division in Kotlin, should use `column / 2` but both are Ints, result is Int (OK)
2. **Line 501**: `screenshot.hardwareBuffer` — requires API 29+, minSdk is 26. Need version check
3. **Line 502**: `Bitmap.wrapHardwareBuffer` — requires API 29+
4. **Missing imports**: `android.view.ScreenshotResult`, `android.view.TakeScreenshotCallback`, `android.graphics.Surface` not imported
4. **Line 611**: `snapshotId` parameter in `describeVisibleScreen` shadows class property
5. **Thread safety**: `refs` and `currentSnapshotId` accessed from multiple threads without synchronization

**Missing Methods Referenced by Execution Package**:
- `inspectSemantic(): UnifiedScreen` — Needs to convert `describeVisibleScreen()` JSON output to `UnifiedScreen` object
- `findElementByRef(ref: String): SemanticElement` — Need to bridge ref system
- `getCurrentPackage()`, `getCurrentActivity()` — Need implementation

### 2.3 Execution Package Integration Issues

**ScreenObserver Implementation**:
```kotlin
// Current DefaultScreenObserver returns null
class DefaultScreenObserver(...) : ScreenObserver {
    override suspend fun inspectSemantic(): UnifiedScreen? = null  // NEEDS REAL IMPLEMENTATION
}
```

**Required Bridge**: Create `AccessibilityScreenObserver` that:
1. Calls `JarvisAccessibilityService.describeVisibleScreen()` 
2. Parses JSON to `UnifiedScreen` with `SemanticElement` list
3. Implements `getCurrentPackage()`/`getCurrentActivity()`

**ActionExecutor.findClickableAncestor/findLongClickableAncestor**:
- Lines 309-318: Stub implementations return null — need parent traversal using `SemanticElement.parentRef`

**ActionPlanner Dependencies**:
- `LlmClient` interface defined but no implementation
- `AppKnowledgeManagerStub` used — needs real `AppKnowledgeManager` instance

### Validation Criteria
- [ ] `./gradlew :app:compileDebugKotlin` succeeds with zero errors
- [ ] All `private fun` methods in LiveVoiceManager are actually used/called
- [ ] JarvisAccessibilityService compiles without warnings
- [ ] Execution package compiles without "unused" or "stub" warnings

## Phase 3: Replace Placeholder Execution Packages

### 3.1 Implement AccessibilityScreenObserver
**File**: `app/src/main/java/com/surya/jarvis/execution/AccessibilityScreenObserver.kt`

```kotlin
class AccessibilityScreenObserver(
    private val context: Context
) : ScreenObserver {
    override suspend fun inspectSemantic(): UnifiedScreen? = withContext(Dispatchers.IO) {
        val jsonStr = JarvisAccessibilityService.describeVisibleScreen()
        parseToUnifiedScreen(jsonStr)
    }
    
    override fun getCurrentPackage(): String? {
        // Extract from latest snapshot
        return try {
            val json = JSONObject(JarvisAccessibilityService.describeVisibleScreen())
            json.optString("package").takeIf { it != "unknown" }
        } catch (_: Exception) { null }
    }
    
    override fun getCurrentActivity(): String? {
        return try {
            val json = JSONObject(JarvisAccessibilityService.describeVisibleScreen())
            json.optString("class").takeIf { it != "unknown" }
        } catch (_: Exception) { null }
    }
    
    private fun parseToUnifiedScreen(jsonStr: String): UnifiedScreen? {
        // Parse JSON from describeVisibleScreen() to UnifiedScreen
        // Map elements JSON array to SemanticElement list
    }
}
```

### 3.2 Connect ActionExecutor to JarvisAccessibilityService
**Issue**: `ActionExecutor` takes `AccessibilityService` but uses `SemanticElement` with `bounds: Rect` and `ref: String` — need to map to actual `AccessibilityNodeInfo`

**Solution**: 
- Add `executeWithAccessibilityService(step, screen, service: JarvisAccessibilityService)` method
- Use `service.resolveRef(entry)` pattern from `JarvisAccessibilityService`
- Replace `SemanticElement.bounds` with actual node bounds from accessibility service

### 3.3 Implement Real Verification Strategies
**Current**: VerificationEngine uses `screenObserver.inspectSemantic()` which returns null
**Fix**: After ScreenObserver works, verification strategies will function

### 3.4 Implement Real Recovery Actions
**Current**: RecoveryEngine calls `planner.createPlan()` and `executor.execute()` but these use placeholder data
**Fix**: Wire through real components

### 3.5 Connect TaskCoordinator to LiveVoiceManager
**Integration Point**: Replace `LiveVoiceManager.executeTaskTool()` task state machine with `TaskCoordinator.executeTask()`

```kotlin
// In LiveVoiceManager
private val taskCoordinator: TaskCoordinator by lazy {
    TaskCoordinator(
        planner = ActionPlanner(appKnowledgeManager, null),
        executor = ActionExecutor(JarvisAccessibilityService.current!!),
        verifier = VerificationEngine(AccessibilityScreenObserver(context)),
        recovery = RecoveryEngine(AccessibilityScreenObserver(context), ...),
        safetyGates = SafetyGates(),
        screenObserver = AccessibilityScreenObserver(context),
        appKnowledgeManager = appKnowledgeManager
    )
}
```

### Validation Criteria
- [ ] `AccessibilityScreenObserver.inspectSemantic()` returns valid `UnifiedScreen`
- [ ] `ActionExecutor.execute()` performs real accessibility actions via `JarvisAccessibilityService`
- [ ] `TaskCoordinator.executeTask()` runs a multi-step workflow end-to-end
- [ ] VerificationEngine strategies return meaningful results
- [ ] RecoveryEngine recovers from common error types (STALE_REF, NOT_FOUND, etc.)

## Phase 4: MARK Execution Architecture

### 4.1 Verify Execution Workflow
1. **Receive instruction** → `LiveVoiceManager.handlePhoneToolCall()`
2. **Understand task** → `TaskCoordinator.extractPackage()` + `ActionPlanner.createPlan()`
3. **Create plan** → `ActionPlanner` matches workflow or uses LLM fallback
4. **Identify elements** → `ScreenObserver.inspectSemantic()` + `AppKnowledge` matching
5. **Execute action** → `ActionExecutor.execute()` with fallback chain
6. **Observe screen** → `ScreenObserver.inspectSemantic()` after each action
7. **Verify result** → `VerificationEngine.verify()` with appropriate strategy
8. **Recover if needed** → `RecoveryEngine.classifyAndRecover()` + `executeRecovery()`
9. **Stop if impossible** → `RecoveryAction.Escalate` → `TaskResult.failed()`
10. **Report result** → `ToolResult` back to Gemini Live

### 4.2 Safety Gates Enforcement
- Confirm sensitive actions BEFORE execution
- `confirmed=true` required for send, delete, pay, call, share, post, install
- User confirmation prompted via `TaskResult.confirmationRequired()`
- LiveVoiceManager returns `confirmation_required` status to Gemini

### 4.3 Task Lifecycle Management
- `start_task` → Initialize `TaskSession`, clear state
- Each step: Observe → Plan → Safety Check → Execute → Verify → Recover
- `complete_task` → Only after `verification.taskComplete == true`
- `cancel_task` → Clear `TaskSession`, stop execution
- Max 24 steps per task (configurable)

### Validation Criteria
- [ ] End-to-end task execution: "Open WhatsApp and send message to John" works
- [ ] Confirmation flow blocks sensitive actions without `confirmed=true`
- [ ] Retry policy handles transient failures (gesture rejected, timeout)
- [ ] Recovery handles STALE_REF by re-inspecting and re-planning
- [ ] Task completes only after verification passes

## Phase 5: Build and Test

### 5.1 Gradle Configuration Checks
```bash
./gradlew :app:dependencies --configuration debugCompileClasspath --no-daemon
./gradlew :app:lint --no-daemon
```

### 5.2 Compile Kotlin
```bash
./gradlew :app:compileDebugKotlin --no-daemon
./gradlew :app:compileDebugJavaWithJavac --no-daemon
```

### 5.3 Full Debug Build
```bash
./gradlew :app:assembleDebug --no-daemon
```

### 5.4 Verify Build Artifacts
- APK at `app/build/outputs/apk/debug/app-debug.apk`
- Check `aapt dump badging app-debug.apk` for permissions, services
- Verify `accessibility_service_config.xml` packaged correctly

### 5.5 Run Unit Tests
```bash
./gradlew :app:testDebugUnitTest --no-daemon
```

### 5.6 Manifest & Config Checks
- [ ] `android.permission.BIND_ACCESSIBILITY_SERVICE` on JarvisAccessibilityService
- [ ] `canPerformGestures="true"`, `canTakeScreenshot="true"` in config
- [ ] `minSdk=26`, `targetSdk=36`, `compileSdk=36`
- [ ] All required permissions declared
- [ ] FileProvider authority configured
- [ ] VoiceInteractionService meta-data present

### Validation Criteria
- [ ] `assembleDebug` completes successfully
- [ ] APK contains all services and permissions
- [ ] Unit tests pass (if any)
- [ ] Lint shows no critical errors

## Phase 6: Persistence & Documentation

### 6.1 SDK Persistence Strategy
```bash
# Create setup script for future sessions
cat > /workspace/app/setup-android-sdk.sh << 'EOF'
#!/bin/bash
set -e
export ANDROID_HOME=/opt/android-sdk
export ANDROID_SDK_ROOT=/opt/android-sdk

if [ ! -d "$ANDROID_HOME/platforms/android-36" ]; then
    echo "Installing Android SDK..."
    cd /tmp
    wget -q --no-check-certificate https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip -O cmdline-tools.zip
    mkdir -p $ANDROID_HOME/cmdline-tools/latest
    unzip -o cmdline-tools.zip -d $ANDROID_HOME/cmdline-tools/latest
    export PATH=$PATH:$ANDROID_HOME/cmdline-tools/latest/bin
    yes | sdkmanager --licenses
    sdkmanager "platforms;android-36" "build-tools;36.0.0" "platform-tools"
fi

echo "sdk.dir=$ANDROID_HOME" > /workspace/app/local.properties
echo "Android SDK ready at $ANDROID_HOME"
EOF
chmod +x /workspace/app/setup-android-sdk.sh
```

### 6.2 Documentation Files to Create
1. **BUILD_RECOVERY_REPORT.md** — This plan execution log
2. **ANDROID_SDK_SETUP.md** — SDK installation commands
3. **KOTLIN_FIX_REPORT.md** — All compilation fixes applied
4. **ACCESSIBILITY_IMPLEMENTATION.md** — How execution package connects to accessibility service
5. **REMAINING_ISSUES.md** — Known limitations and future work

### Validation Criteria
- [ ] `setup-android-sdk.sh` restores build environment in < 5 minutes
- [ ] All 5 documentation files created with accurate content
- [ ] No hardcoded paths in documentation that won't persist

## Phase 7: Export & Verification

### 7.1 Create Export Structure
```
PROJECT_EXPORT/
├── PROJECT_IMPLEMENTATION_GUIDE.md
├── FILE_MANIFEST.md
├── REIMPLEMENTATION_GUIDE.md
├── source/
│   └── (all project files preserving structure)
├── configuration/
│   ├── local.properties.example
│   └── gradle.properties.example
├── scripts/
│   └── setup-android-sdk.sh
└── assets/
    └── (app_knowledge JSON files)
```

### 7.2 Create ZIP
```bash
cd /workspace/app
jar cf PROJECT_EXPORT.zip -C . PROJECT_EXPORT
```

### 7.3 Verify Export
- [ ] All modified source files in `PROJECT_EXPORT/source/`
- [ ] Documentation files present
- [ ] ZIP extracts cleanly
- [ ] No build caches, .gradle, or secrets in export

## Risk Assessment & Mitigation

| Risk | Impact | Likelihood | Mitigation |
|------|--------|------------|------------|
| Android SDK download fails (network) | Build impossible | Medium | Pre-download commandlinetools.zip; use `--no-check-certificate` |
| UnifiedScreen parsing fails | Verification broken | High | Add robust JSON parsing with fallbacks; log parse errors |
| Gesture dispatch rejected on some devices | Actions fail | High | Implement coordinate fallback in ActionExecutor; test on multiple API levels |
| ScreenObserver returns stale data | Verification false positives | Medium | Add snapshotId validation; force re-inspect after each action |
| Memory leaks in accessibility service | Crash over time | Low | Clear refs in onDestroy; use WeakReference for nodes |

## Dependencies Between Phases

```
Phase 1 (SDK) 
    → Phase 2 (Kotlin fixes) 
        → Phase 3 (Execution wiring) 
            → Phase 4 (Architecture validation)
                → Phase 5 (Build & test)
                    → Phase 6 (Persistence docs)
                        → Phase 7 (Export)
```

## Open Questions for Implementation Agent

1. **LlmClient implementation**: Should we implement a real LLM client for planning fallback, or keep the generic plan as-is?
2. **ScreenObserver threading**: `inspectSemantic()` is suspend — should it run on `Dispatchers.IO` or `Dispatchers.Default`?
3. **UnifiedScreen from JSON**: Should `parseToUnifiedScreen()` live in `UnifiedScreen` companion object or `AccessibilityScreenObserver`?
4. **ActionExecutor.findClickableAncestor**: Should use `parentRef` to traverse `UnifiedScreen.elements` or call back to `JarvisAccessibilityService` for real `AccessibilityNodeInfo`?
5. **TaskCoordinator in LiveVoiceManager**: Should replace entire task state machine, or wrap it for gradual migration?

## Plan Exit

This plan is ready for implementation. The next agent should:
1. Start with Phase 1 (SDK restoration) — blocking for all other phases
2. Proceed sequentially through phases
3. Run validation criteria at each phase before continuing
4. Document all changes in the 5 report files
5. Create final export ZIP

**Saved plan**: `.kilo/plans/1794833899676-android-build-recovery-plan.md`