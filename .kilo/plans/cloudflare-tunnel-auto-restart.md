# Cloudflare Tunnel Auto-Restart Watchdog Plan

## Objective
Create an automated watchdog script that:
1. Starts Cloudflare Quick Tunnel in foreground
2. Monitors the process every 30 seconds
3. Automatically restarts when platform kills it
4. Captures and reports the new public URL each restart
5. Runs continuously until manually stopped

## Current State
- ✅ File manager running on `http://127.0.0.1:8080` (gunicorn, 4 workers)
- ✅ `cloudflared` installed at `/workspace/app/cloudflared`
- ✅ Secure credentials active
- ✅ Files ready for download
- ⚠️ Platform kills tunnel after ~10 minutes
- ✅ Tunnel generates new random URL each restart

## Plan: Automated Tunnel Watchdog

### 1. Create Watchdog Script
Create `/workspace/app/tunnel_watchdog.py` that:
- Starts `cloudflared tunnel --url http://127.0.0.1:8080` as subprocess
- Monitors stdout for tunnel URL pattern: `https://*.trycloudflare.com`
- Extracts and logs the public URL immediately when generated
- Monitors process health every 30 seconds
- On process death: logs termination, waits 5s, restarts
- Continues indefinitely until manual stop (Ctrl+C)

### 2. URL Notification
- Print new URL to stdout immediately when detected
- Log timestamp and URL to `/tmp/tunnel_urls.log`
- Format: `[TIMESTAMP] New tunnel URL: https://xxx.trycloudflare.com`

### 3. Process Management
- Use `subprocess.Popen` with pipes for stdout/stderr
- Non-blocking read of stdout to capture URL in real-time
- Health check: poll process every 30s
- Graceful restart: SIGTERM → wait 3s → SIGKILL if needed → restart

### 4. Logging
- All tunnel output to `/tmp/tunnel_watchdog.log`
- URL history to `/tmp/tunnel_urls.log`
- Rotation: keep last 1000 lines

### 5. Signal Handling
- SIGINT/SIGTERM: graceful shutdown of child process
- Clean exit on user interrupt

## Implementation Details

### Script Structure
```python
#!/usr/bin/env python3
import subprocess, time, re, sys, signal, os
from datetime import datetime

TUNNEL_CMD = ['./cloudflared', 'tunnel', '--url', 'http://127.0.0.1:8080']
URL_PATTERN = re.compile(r'https://[a-z0-9-]+\.trycloudflare\.com')
CHECK_INTERVAL = 30  # seconds
RESTART_DELAY = 5    # seconds
```

### Key Functions
- `start_tunnel()` - spawn subprocess, return process object
- `extract_url(output)` - regex extract trycloudflare.com URL
- `monitor_loop()` - main watchdog loop
- `signal_handler()` - cleanup on exit

### Validation
- Test: run script, verify URL captured, wait for platform kill, verify auto-restart
- Verify new URL captured and logged each restart

## Files to Create
- `/workspace/app/tunnel_watchdog.py` - main watchdog script
- `/tmp/tunnel_watchdog.log` - runtime log
- `/tmp/tunnel_urls.log` - URL history

## Deliverables
1. Working watchdog script
2. Instructions to run: `python3 tunnel_watchdog.py`
3. URL notification every restart
4. Continuous operation until manual stop