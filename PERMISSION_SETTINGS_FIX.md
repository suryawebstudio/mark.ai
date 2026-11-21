# Permission settings fix

The settings screen was showing permission cards for Contacts, Calls, SMS, Calendar, Bluetooth, Call History, Media, and Exact Alarms even though those permissions had been removed from the hardened manifest. Those stale cards could not work.

The settings screen now exposes only capabilities supported by this build: microphone, camera, notifications, accessibility control, full storage access, default assistant, and notification access. The native bridge now treats notification access as a special Android settings access and rejects unsupported capability keys instead of trying to request undeclared permissions.

Validation: `testDebugUnitTest assembleDebug lintDebug` completed successfully with zero lint errors.
