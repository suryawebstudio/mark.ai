# Accessibility Settings fix

Removed `flagRequestAccessibilityButton` from MARK's accessibility-service configuration. Android Settings will no longer show the separate “Mark shortcut” option; MARK uses the standard accessibility service enable/disable control.

Validation: `testDebugUnitTest assembleDebug lintDebug` completed successfully with zero lint errors.
APK SHA-256: `ca6293522233f4b598d2c5e12ef112a1c521f5c83a8174d5c2cc3136075f14ae`
