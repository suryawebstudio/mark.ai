# MARK Project File Download Plan

## Objective
Make MARK project files downloadable via public HTTPS URL using Cloudflare Quick Tunnel, with proper authentication and verification.

## Current State
- ✅ File server running on `0.0.0.0:8080` (gunicorn, 4 workers, daemon mode)
- ⚠️ Password: `mark2024` (example, needs rotation)
- ⚠️ Secret: fixed key (example, needs rotation)
- ✅ Server accessible locally at `http://127.0.0.1:8080`
- ✅ Files exist: `PROJECT_EXPORT.zip` (40MB), `app-debug.apk` (128MB)
- ⚠️ Cloudflare Tunnel works but platform kills background processes after ~2 min

## Platform Constraints
- Background processes killed ~2 min after shell exits
- No `nohup`, daemon mode, or background processes survive
- Must run tunnel in **foreground** in a **persistent terminal**

## Plan Steps

### 1. Generate Secure Credentials
```bash
# Generate new password (32 chars base64)
export FILE_MANAGER_PASSWORD=$(openssl rand -base64 32)

# Generate new secret key (32 chars base64)
export SECRET_KEY=$(openssl rand -base64 32)

# Restart file manager with new credentials
pkill -f gunicorn
export FILE_MANAGER_PASSWORD
export SECRET_KEY
cd /workspace/app
nohup gunicorn --bind 0.0.0.0:8080 --workers 4 --timeout 120 --daemon file_manager:app
```

### 2. Check Kilo Port Forwarding
- Check Kilo dashboard for "Port Forwarding" or "Public URL" feature
- If available: configure port 8080 forwarding → provides direct HTTPS URL
- If unavailable: proceed to step 3

### 3. Start Cloudflare Quick Tunnel in Persistent Terminal
```bash
# In a persistent terminal session:
cd /workspace/app
./cloudflared tunnel --url http://127.0.0.1:8080
```
- Must keep terminal **open and in foreground**
- Capture generated URL: `https://<random>.trycloudflare.com`

### 4. Verify Public Access
```bash
# Test public URL
curl -I https://<generated>.trycloudflare.com
# Expect: 302 redirect to /login
```

### 5. Verify File Downloads
- Open `https://<generated>.trycloudflare.com/login` in browser
- Login with new password
- Test downloads:
  - `PROJECT_EXPORT.zip` (40MB)
  - `app-debug.apk` (128MB)

### 6. Report Results
- Actual public HTTPS URL
- Login credentials (password only, not in logs)
- Confirmation of external access
- Download instructions for ZIP and APK
- Note: tunnel must remain in foreground in persistent terminal

## Files to Download
| File | Size | Local Path |
|------|------|------------|
| PROJECT_EXPORT.zip | 40 MB | /workspace/app/PROJECT_EXPORT.zip |
| app-debug.apk | 128 MB | /workspace/app/app/build/outputs/apk/debug/app-debug.apk |

## Security Requirements
- ✅ Generate new random password (not `mark2024`)
- ✅ Generate new random secret key
- ✅ Never print secrets in logs or final report
- ✅ Password protection mandatory on all routes

## Deliverables
1. Actual public HTTPS URL (from cloudflared)
2. Login instructions (password provided separately)
3. Confirmation that external access works
4. ZIP download instructions
5. APK download instructions
6. Note: tunnel must remain in foreground in persistent terminal