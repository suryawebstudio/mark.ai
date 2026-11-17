# Cloudflare Quick Tunnel - Persistent Terminal Option (Option 1)

## Objective
Run Cloudflare Quick Tunnel in a persistent foreground terminal to expose the MARK file manager via public HTTPS URL.

## Current State
- ✅ File manager running on `http://127.0.0.1:8080` (gunicorn, 4 workers, daemon mode)
- ✅ Secure credentials generated (password & secret key)
- ✅ Files ready: `PROJECT_EXPORT.zip` (40MB), `app-debug.apk` (128MB)
- ✅ `cloudflared` installed at `/workspace/app/cloudflared`
- ⚠️ Platform kills background processes after ~5 min
- ✅ Quick Tunnel works when run in foreground

## Chosen Option: Persistent Terminal (Option 1)
Run tunnel in **foreground** in a **persistent terminal** that stays open.

## Execution Plan

### Step 1: Verify File Manager Running
```bash
curl -I http://127.0.0.1:8080
# Expect: 302 redirect to /login
```

### Step 2: Start Cloudflare Quick Tunnel (Foreground)
```bash
cd /workspace/app
./cloudflared tunnel --url http://127.0.0.1:8080
```
- **Must keep terminal open and in foreground**
- Process will output public URL: `https://<random>.trycloudflare.com`
- Keep this terminal tab **open** for duration of file access

### Step 3: Capture Public URL
Wait for output:
```
| INFO| Your quick Tunnel has been created! Visit it at: https://<random>.trycloudflare.com
```

### Step 4: Access Files
1. Open `https://<generated>.trycloudflare.com` in browser
2. Login with password: `gJbuM/9zOgrChAeyMVVf3mSVg4b7CFZgMzRr9hhEFHQ=`
3. Download:
   - `https://<tunnel>.trycloudflare.com/download/PROJECT_EXPORT.zip`
   - `https://<tunnel>.trycloudflare.com/download/app/build/outputs/apk/debug/app-debug.apk`

## Credentials (Save Securely)
```
Password: gJbuM/9zOgrChAeyMVVf3mSVg4b7CFZgMzRr9hhEFHQ=
Secret:   0iZoXpZQ7M/3enLsdg/fOrhgiVLu6kgJG6qJw/N2EME=
```

## Files to Download
| File | Size | Local Path |
|------|------|------------|
| PROJECT_EXPORT.zip | 40 MB | /workspace/app/PROJECT_EXPORT.zip |
| app-debug.apk | 128 MB | /workspace/app/app/build/outputs/apk/debug/app-debug.apk |

## Download URLs (When Tunnel Active)
| File | URL |
|------|-----|
| Login | `https://<tunnel>.trycloudflare.com/login` |
| PROJECT_EXPORT.zip | `https://<tunnel>.trycloudflare.com/download/PROJECT_EXPORT.zip` |
| app-debug.apk | `https://<tunnel>.trycloudflare.com/download/app/build/outputs/apk/debug/app-debug.apk` |

## Tunnel Restart Command
If tunnel stops (platform kills after ~5 min):
```bash
cd /workspace/app
./cloudflared tunnel --url http://127.0.0.1:8080
```
- Generates **new random URL** each run
- Must keep terminal open

## Platform Limitation
- Background processes killed after ~5 min
- Must run tunnel in **foreground** in **persistent terminal**
- New random URL generated each restart

## Security
- ✅ Strong random password generated (32-char base64)
- ✅ Strong random secret key generated (32-char base64)
- ✅ Password authentication enforced
- ⚠️ Rotate credentials for production use