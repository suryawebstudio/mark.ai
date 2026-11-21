# MARK 1.0.0 hardening changelog

## Applied fixes

- Replaced executor-wide `confirmed = true` behavior with short-lived, operation-bound confirmation tokens. Tokens are bound to the action, target, and text, expire after 60 seconds, and are consumed once.
- Added sensitive-node redaction for editable, password, PIN, and OTP accessibility content before screen snapshots are serialized.
- Added fresh semantic-screen polling for element-present and element-gone verification, and strengthened screen-change verification to include observable tree changes.
- Made retry behavior conservative by removing gesture and verification outcomes from the automatic retry class.
- Removed unused contacts, SMS, calendar, call-log, Bluetooth, media, and exact-alarm permissions. File management remains explicitly dependent on the existing full-storage capability.
- Removed the file-manager fallback password and leaked cookies, Cloudflare binary, watchdog script, duplicate execution-package copy, and nested export archive from the deliverable.
- Added unit tests for safety-gate classification and an AndroidX UI Automator device smoke-test scaffold.
- Enabled release lint checking and fixed all release-blocking lint errors, including microphone permission guards, API-level guards, version-qualified theme attributes, optional camera hardware declaration, and non-exported broadcast registration.
- Added explicit microphone foreground-service type handling on Android 10+.

## Validation

Validated in a full JDK and Android SDK environment on 2026-11-18:

- `./gradlew testDebugUnitTest assembleDebug lintDebug --no-daemon`: **BUILD SUCCESSFUL**
- Lint: **0 errors**; existing non-blocking warnings remain for deprecated Camera2 API, native-library stripping, and cleanup opportunities.
- Debug APK SHA-256: `1905370178ad702196cee83c577681bfe2e80ef1fc199b4ec8f72d7d429bc598`
- Physical-device behavior and the UI Automator test still require execution on an attached Android device.

The Google Play distribution decision was intentionally not changed.
