# MARK voice reliability fix

This update hardens MARK Live voice mode against temporary session and app-lifecycle interruptions.

## Changes

- Added OkHttp WebSocket keepalive pings every 20 seconds.
- Added bounded reconnect with exponential backoff (1–30 seconds) after token errors, WebSocket failures, server errors, idle/session closure, or audio-send failures.
- Reconnect re-establishes the setup handshake and restarts microphone capture/playback without creating duplicate threads.
- Explicit user stop remains terminal; it cancels pending reconnects and releases audio resources.
- The voice foreground service is no longer stopped with the launcher task and now remains sticky when another app is opened.
- A system-recreated sticky service treats a null restart intent as a voice start, preventing a stale notification with no active listener.
- App launches add the required `FLAG_ACTIVITY_NEW_TASK` for the application/service context and preserve MARK's voice service.
- Status messages report connection, reconnect, speaker, and microphone lifecycle problems.

## Validation

Validated with:

```text
./gradlew testDebugUnitTest assembleDebug lintDebug --no-daemon
```

The build completed successfully with zero lint errors. Android device testing is still recommended because audio-focus behavior varies by manufacturer and Android version.
