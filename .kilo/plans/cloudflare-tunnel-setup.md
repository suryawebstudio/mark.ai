# Cloudflare Quick Tunnel Setup Plan

## Objective
Expose the MARK file manager (running on port 8080) via Cloudflare Quick Tunnel to get a public HTTPS URL for Android phone access.

## Current State
- ✅ File server running on `0.0.0.0:8080` (gunicorn, 4 workers, daemon mode)
- ✅ Password protection: `mark2024` (needs rotation for production)
- ✅ Session secret: fixed key (needs rotation for production)
- ✅ Server accessible locally at `http://127.0.0.1:8080`

## Plan Steps

### 1. Check cloudflared Installation
```bash
which cloudflared
cloudflared --version
```

### 2. Check Architecture
```bash
uname -m
# Expected: x86_64 (amd64)
```

### 3. Install cloudflared (if missing)
```bash
# For x86_64
wget -q https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-linux-amd64 -O cloudflared
chmod +x cloudflared
# Verify
./cloudflared --version
```

### 4. Verify Local Server
```bash
curl -I http://127.0.0.1:8080
# Expect: 302 redirect to /login
```

### 5. Start Cloudflare Quick Tunnel
```bash
./cloudflared tunnel --url http://127.0.0.1:8080
```

This will:
- Output a public HTTPS URL like `https://<random>.trycloudflare.com`
- Keep running in foreground (Ctrl+C to stop)

### 6. Capture Public URL
Wait for output like:
```
| INFO| Starting tunnel for http://127.0.0.1:8080
| INFO| Your quick tunnel has been generated! Visit https://abc123.trycloudflare.com
```

### 7. Verify External Access
```bash
# From local machine or another terminal:
curl -I https://<generated>.trycloudflare.com
# Expect: 302 redirect to /login
```

### 8. Verify Login Page
Open `https://<generated>.trycloudflare.com/login` in browser → should show MARK login page.

### 9. Verify Downloads
- Login with password `mark2024`
- Download `PROJECT_EXPORT.zip` (40MB)
- Download `app-debug.apk` (128MB)

## Security Notes (Pre-Production)
- **Rotate password**: Generate strong random password before public exposure
- **Rotate secret key**: Generate new `SECRET_KEY` before public exposure
- **Don't expose**: Never print secrets in logs or responses

## Persistence
- Tunnel runs in foreground → stops if terminal closes
- **Restart command**: Re-run `./cloudflared tunnel --url http://127.0.0.1:8080`
- **For background**: Use `nohup ./cloudflared tunnel --url http://127.0.0.1:8080 > /tmp/tunnel.log 2>&1 &`

## Deliverables
1. Public HTTPS URL (e.g., `https://xyz.trycloudflare.com`)
2. Login instructions (password: `mark2024` - rotate for production)
3. Confirmation of external access
4. ZIP/APK download instructions
5. Tunnel restart command

## Open Questions
1. Should we use a named tunnel instead of quick tunnel for persistence?
2. Do we need custom domain or is trycloudflare.com sufficient?
3. Should we add rate limiting or IP restrictions?