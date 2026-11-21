# UPDATED PROJECT IMPLEMENTATION GUIDE - MARK Android AI Assistant

## Project Overview

**MARK** (Mark Android Recognition Kit) is an AI-powered voice assistant for Android that combines:
- **Gemini Live API** for real-time voice conversation
- **Android Accessibility Service** for screen reading and control
- **ML Kit OCR** for visual text recognition
- **App Knowledge Base** for app-specific automation

## Architecture Overview

```
┌─────────────────────────────────────────────────────────────────┐
│                      MARK Android App                           │
├─────────────────────────────────────────────────────────────────┤
│  LiveVoiceManager (Voice I/O)                                   │
│  ├── Gemini Live WebSocket (Voice ↔ AI)                        │
│  ├── 15 Tool Declarations (phoneControlTools)                  │
│  └── Audio Capture/Playback (AudioRecord/AudioTrack)           │
├─────────────────────────────────────────────────────────────────┤
│  TaskCoordinator (Orchestration)                                │
│  ├── ActionPlanner (Workflow instantiation + LLM fallback)     │
│  ├── SafetyGates (Confirmation for sensitive actions)          │
│  ├── ActionExecutor (Delegates to AccessibilityService)        │
│  ├── VerificationEngine (7 strategies)                         │
│  ├── RecoveryEngine (11 error types, 8 recovery actions)       │
│  └── AccessibilityScreenObserver (Semantic screen inspection)  │
├─────────────────────────────────────────────────────────────────┤
│  JarvisAccessibilityService (Android Accessibility API)        │
│  ├── Semantic Screen Inspection (inspectScreen)                │
│  ├── Visual Screen Inspection (inspectVisualScreen + OCR)      │
│  ├── Action Execution (control - 15 action types)              │
│  ├── Global Actions (Back, Home, Recents, Notifications)       │
│  ├── Camera Capture (rear camera still image)                  │
│  └── Flashlight Control                                         │
├─────────────────────────────────────────────────────────────────┤
│  Knowledge & Learning Systems                                   │
│  ├── AppKnowledgeManager (Room DB + Bundled JSON assets)       │
│  ├── ExplorationEngine (Auto-discovers app UI)                 │
│  └── UserFeedbackHandler (Learns from corrections)             │
├─────────────────────────────────────────────────────────────────┤
│  Data Layer                                                     │
│  ├── Room Database (AppKnowledge, KeyElement, Workflow, etc.)  │
│  ├── Bundled Assets (6 apps × JSON knowledge files)            │
│  └── ML Kit OCR (On-device text recognition)                   │
└─────────────────────────────────────────────────────────────────┘
```

## Implementation Phases

### Phase 1: Core Reliability ✅ COMPLETE
- Updated accessibility_service_config.xml with gestures, screenshot
- Enhanced JarvisAccessibilityService with all action types
- Fixed gesture duration calculation
- Improved screenshot error handling

### Phase 2: App Knowledge Base ✅ COMPLETE
- AppKnowledge data model (KeyElement, Workflow, VerificationTemplate, etc.)
- Room database for persistence
- Bundled JSON knowledge for 6 apps
- ML Kit OCR integration

### Phase 3: Advanced Interactions ✅ COMPLETE
- Visual tap (grid-based)
- OCR-enhanced screen inspection
- Expanded control_visible_screen actions
- Camera capture integration

### Phase 4: Verification & Recovery ✅ COMPLETE
- VerificationEngine (7 strategies)
- RecoveryEngine (11 errors, 8 actions)
- SafetyGates (confirmation policy)
- TaskCoordinator (full lifecycle)
- ActionPlanner (workflow + LLM fallback)
- ActionExecutor (delegates to AccessibilityService)

### Phase 5: Learning System 🔄 PARTIAL
- ExplorationEngine (auto-discovers UI)
- UserFeedbackHandler (learns corrections)
- Version migration in AppKnowledgeManager
- **Remaining**: Production testing, feedback loop validation

## Key Implementation Details

### 15 Tool Declarations (phoneControlTools)

| Tool | Description | Parameters |
|------|-------------|------------|
| `launch_app` | Open app by visible name | `app_name` |
| `open_settings_page` | Open Android Settings page | `page` (enum) |
| `set_flashlight` | Toggle flashlight | `enabled` (bool) |
| `capture_camera_image` | Capture rear camera still | - |
| `inspect_visible_screen` | Read accessibility tree | - |
| `inspect_visual_screen` | Screenshot + OCR + grid | - |
| `visual_tap` | Tap grid cell from visual inspection | `cell`, `token`, `confirmed` |
| `control_visible_screen` | 15 action types on screen | `action`, `snapshot_id`, `ref`, `target`, `text`, `direction`, `confirmed` |
| `start_task` | Start multi-step task | - |
| `cancel_task` | Cancel current task | - |
| `complete_task` | Mark task complete | - |
| `list_files` | List shared storage | - |
| `create_text_file` | Create UTF-8 text file | `name`, `content` |
| `edit_text_file` | Replace text file content | `name`, `content` |
| `delete_file` | Delete file/folder | `name`, `confirmed` |

### Action Types (control_visible_screen)

| Action | Description | Required Params |
|--------|-------------|-----------------|
| `tap` | Single tap | `snapshot_id`, `ref` |
| `long_press` | Long press | `snapshot_id`, `ref` |
| `double_tap` | Double tap | `snapshot_id`, `ref` |
| `type` | Text input | `snapshot_id`, `ref`, `text` |
| `scroll` | Scroll container | `snapshot_id`, `ref`, `direction` (up/down) |
| `swipe_left/right/up/down` | Swipe gestures | `snapshot_id`, `ref` |
| `drag` | Drag to coordinates | `snapshot_id`, `ref`, `text`(x), `direction`(y) |
| `pinch_in/out` | Pinch zoom | `snapshot_id`, `ref` |
| `back/home/overview` | Global navigation | - |

### Verification Strategies (7)

```kotlin
enum class VerificationType {
    ELEMENT_PRESENT,    // Wait for element to appear
    ELEMENT_GONE,       // Wait for element to disappear
    TEXT_CONTAINS,      // Verify text in element
    STATE_CHANGED,      // Verify state toggle (checked, focused)
    SCREEN_CHANGE,      // Verify package/activity change
    GLOBAL_STATE,       // Verify global action result
    TREE_DIFF           // Compare before/after tree
}
```

### Error Types (11) & Recovery Actions (8)

| Error Type | Recovery Action |
|------------|-----------------|
| STALE_REF | ReinspectAndReplan |
| AMBIGUOUS_TARGET | ReinspectAndReplan |
| DISABLED_TARGET | WaitAndRetry(2s) |
| NOT_FOUND | Explore (navigate BACK) |
| NOT_VISIBLE | Explore (scroll to reveal) |
| PERMISSION_DIALOG | UserGuidance |
| LOADING_SCREEN | WaitAndRetry(10s) |
| UNEXPECTED_SCREEN | NavigateBackToKnown |
| GESTURE_REJECTED | RetryWithAdjustment |
| TIMEOUT | WaitAndRetry(5s) |
| SECURE_WINDOW | Escalate |

## Build Instructions

### Prerequisites
- Android SDK (API 34, Build Tools 34.0.0)
- JDK 21
- Gradle 9.6.1 (wrapper included)

### Setup (Run Once)
```bash
# Restore Android SDK if needed
cd /workspace/app
./setup-android-sdk.sh

# Or manually:
export ANDROID_HOME=/opt/android-sdk
export PATH=$PATH:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools
yes | sdkmanager --licenses
sdkmanager "platforms;android-34" "build-tools;34.0.0" "platform-tools"
```

### Build Commands
```bash
cd /workspace/app

# Clean build
./gradlew clean assembleDebug --no-daemon

# Compile only (faster iteration)
./gradlew :app:compileDebugKotlin --no-daemon

# Run lint
./gradlew :app:lint --no-daemon

# Full debug build
./gradlew assembleDebug --no-daemon
```

### Output
- **Debug APK**: `app/build/outputs/apk/debug/app-debug.apk` (~134MB)
- **Build Time**: ~1.5 minutes (first build), ~30s (incremental)

## Deployment

### Install on Device
```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### Enable Accessibility Service
1. Open Settings → Accessibility
2. Find "MARK" or "JARVIS Accessibility Service"
3. Enable the service
4. Grant permissions: "Display over other apps" if prompted

### Required Permissions (granted at install)
- `BIND_ACCESSIBILITY_SERVICE` (service)
- `RECORD_AUDIO` (voice input)
- `CAMERA` (visual tap, capture)
- `READ/WRITE_EXTERNAL_STORAGE` (file ops)
- `INTERNET` (Gemini Live WebSocket)
- `FOREGROUND_SERVICE` (voice session)

## Testing Checklist

### Voice Commands
- [ ] "Open WhatsApp"
- [ ] "Send message to John"
- [ ] "Open Gmail and search for invoice"
- [ ] "Take a screenshot"
- [ ] "Turn on flashlight"
- [ ] "Open Settings → Wi-Fi"
- [ ] "What's on my screen?"

### Visual Interactions
- [ ] `inspect_visual_screen` returns grid + OCR
- [ ] `visual_tap` on grid cell works
- [ ] Camera capture sends image to vision session

### Multi-step Tasks
- [ ] "Open WhatsApp and send message to John saying hello"
- [ ] Safety gate prompts for confirmation on send
- [ ] Task completes after verification

### Error Recovery
- [ ] Stale ref triggers re-inspection
- [ ] Loading screen waits and retries
- [ ] Unexpected screen navigates BACK
- [ ] Secure window escalates (no crash)

## Known Limitations

1. **No physical device testing in CI** - Requires manual deployment
2. **OCR platform type casts** - 3 compiler warnings, runtime OK
3. **Deprecated Camera2 API** - `createCaptureSession` deprecation
4. **APK size** - 134MB (native libs not stripped)
5. **No emulator testing** - Requires physical device with Accessibility

## Configuration Files

### `local.properties`
```properties
sdk.dir=/opt/android-sdk
```

### `app/build.gradle.kts` Key Settings
```kotlin
compileSdk = 34
targetSdk = 34
minSdk = 26

dependencies {
    // ML Kit OCR
    implementation("com.google.mlkit:text-recognition:16.0.1")
    // Coroutines + Play Services for Task.await()
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.7.3")
    // Serialization for JSON knowledge
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
}
```

### `accessibility_service_config.xml` Key Features
```xml
<accessibility-service
    android:canPerformGestures="true"
    android:canTakeScreenshot="true"
    android:accessibilityEventTypes="typeAllMask"
    android:accessibilityFlags="flagDefault|flagRequestFilterKeyEvents|flagRequestTouchExplorationMode|flagReportViewIds|flagRetrieveInteractiveWindows|flagIncludeNotImportantViews"
    android:accessibilityFeedbackType="feedbackGeneric"
    android:notificationTimeout="100"
    android:settingsActivity="com.surya.jarvis.SettingsActivity" />
```

## Troubleshooting

### Build Failures
| Error | Solution |
|-------|----------|
| SDK not found | Run `./setup-android-sdk.sh` |
| License not accepted | `yes | sdkmanager --licenses` |
| SSL certificate error | Re-import certs: `update-ca-certificates -f` |
| Out of memory | Increase Gradle heap: `org.gradle.jvmargs=-Xmx4g` |

### Runtime Issues
| Issue | Solution |
|-------|----------|
| Accessibility not working | Enable service in Settings → Accessibility |
| Gestures not executing | Check `canPerformGestures="true"` in config |
| Visual tap fails | Ensure Android 11+, service enabled |
| OCR not working | Check ML Kit model downloaded, camera permission |
| WebSocket disconnects | Check network, Gemini API key |

## Future Enhancements

1. **Proactive UI Analysis** - Predict next action from screen context
2. **Cross-app Workflows** - Chain actions across multiple apps
3. **Offline Mode** - Local LLM for basic commands
4. **Multi-language Support** - i18n for voice commands
5. **Plugin System** - Third-party app knowledge packages
6. **Cloud Sync** - Sync learned knowledge across devices