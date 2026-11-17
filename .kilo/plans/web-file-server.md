# Web File Server Plan

## Objective
Start a simple HTTP file server on the cloud workspace so the user can browse and download files from `/workspace/app/` via browser.

## Requirements
- Serve files from `/workspace/app/` (project root)
- Directory listing enabled
- Accessible on an exposed port
- No authentication needed (temporary use)

## Options

| Option | Pros | Cons |
|--------|------|------|
| Python `http.server` | Built-in, zero config | Single-threaded, basic |
| Node.js `http-server` | Fast, caching, CORS | Requires npm install |
| Go `static` | Fast, single binary | Need to compile/download |
| `npx serve` | Zero install, modern | Needs npx/node |

## Recommended: Python `http.server` with directory listing

```bash
cd /workspace/app && python3 -m http.server 8080 --bind 0.0.0.0
```

- Port 8080 (commonly exposed in cloud envs)
- Binds to all interfaces
- Auto directory listing
- Zero dependencies

## Alternative: Node.js if Python fails
```bash
cd /workspace/app && npx serve -l 8080 -s .
```

## Port Exposure
The cloud environment likely exposes ports via a proxy. Need to check:
- Which ports are publicly accessible
- Whether the URL follows a pattern like `https://<workspace-id>.kilo.app:8080`

## Plan Steps

1. **Check available Python version** - verify `python3` exists
2. **Start server on port 8080** - bind to 0.0.0.0
3. **Verify accessibility** - test with curl locally
4. **Provide access URL** - format based on cloud provider pattern

## Open Questions

1. **Which port is exposed?** Common options: 8080, 3000, 8000, 5000. Need to confirm.
2. **URL pattern?** Does the cloud provide a public hostname like `https://<id>.kilo.app`?
3. **HTTPS required?** Some browsers block mixed content on HTTPS pages.

## Recommendation
Start with Python http.server on port 8080. If port not accessible, try 3000, 8000, or 5000.