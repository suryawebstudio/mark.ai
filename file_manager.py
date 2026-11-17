#!/usr/bin/env python3
"""
MARK Project File Manager
A secure, read-only web file manager for the MARK Android project.
"""

import os
import zipfile
import hashlib
import secrets
from pathlib import Path
from functools import wraps
from flask import Flask, render_template_string, send_file, abort, request, session, redirect, url_for
from werkzeug.security import check_password_hash, generate_password_hash
import mimetypes

app = Flask(__name__)
app.secret_key = os.environ.get('SECRET_KEY', secrets.token_hex(32))

# Configuration
BASE_DIR = Path('/workspace/app').resolve()
PASSWORD_HASH = generate_password_hash(os.environ.get('FILE_MANAGER_PASSWORD', 'mark2024'))
MAX_FILE_SIZE = 500 * 1024 * 1024  # 500MB for streaming

# Security: Ensure path is within BASE_DIR
def safe_path(user_path: str) -> Path:
    """Resolve user path and ensure it's within BASE_DIR."""
    try:
        requested = (BASE_DIR / user_path.lstrip('/')).resolve()
        # Check if requested path is within BASE_DIR
        if not str(requested).startswith(str(BASE_DIR)):
            return None
        if not requested.exists():
            return None
        return requested
    except (ValueError, RuntimeError):
        return None

def login_required(f):
    @wraps(f)
    def decorated_function(*args, **kwargs):
        if not session.get('authenticated'):
            return redirect(url_for('login', next=request.url))
        return f(*args, **kwargs)
    return decorated_function

@app.route('/login', methods=['GET', 'POST'])
def login():
    if request.method == 'POST':
        password = request.form.get('password', '')
        if check_password_hash(PASSWORD_HASH, password):
            session['authenticated'] = True
            next_url = request.args.get('next') or url_for('index')
            return redirect(next_url)
        return render_template_string(LOGIN_TEMPLATE, error='Invalid password')
    return render_template_string(LOGIN_TEMPLATE)

@app.route('/logout')
def logout():
    session.clear()
    return redirect(url_for('login'))

@app.route('/')
@login_required
def index():
    return browse('')

@app.route('/browse/<path:subpath>')
@login_required
def browse(subpath):
    path = safe_path(subpath)
    if not path:
        abort(404)
    
    if path.is_file():
        return download_file(path, subpath)
    
    # Directory listing
    items = []
    for item in sorted(path.iterdir(), key=lambda x: (not x.is_dir(), x.name.lower())):
        rel_path = item.relative_to(BASE_DIR)
        stat = item.stat()
        items.append({
            'name': item.name,
            'path': str(rel_path),
            'is_dir': item.is_dir(),
            'size': stat.st_size if item.is_file() else None,
            'size_str': format_size(stat.st_size) if item.is_file() else '-',
            'mime': get_mime_type(item) if item.is_file() else 'directory',
            'modified': stat.st_mtime
        })
    
    parent = str(path.relative_to(BASE_DIR).parent) if path != BASE_DIR else None
    return render_template_string(BROWSE_TEMPLATE, 
        items=items, 
        current_path=str(path.relative_to(BASE_DIR)),
        parent=parent,
        base_url=request.url_root.rstrip('/')
    )

@app.route('/download/<path:subpath>')
@login_required
def download_file(subpath):
    path = safe_path(subpath)
    if not path or not path.is_file():
        abort(404)
    
    # Stream large files
    return send_file(
        path,
        as_attachment=True,
        download_name=path.name,
        mimetype=get_mime_type(path)
    )

@app.route('/zip/<path:subpath>')
@login_required
def download_zip(subpath):
    path = safe_path(subpath)
    if not path or not path.is_dir():
        abort(404)
    
    # Create ZIP in memory for small dirs, stream for large
    import io
    memory_file = io.BytesIO()
    with zipfile.ZipFile(memory_file, 'w', zipfile.ZIP_DEFLATED) as zf:
        for file_path in path.rglob('*'):
            if file_path.is_file():
                arcname = file_path.relative_to(path)
                zf.write(file_path, arcname)
    memory_file.seek(0)
    
    zip_name = f"{path.name}.zip"
    return send_file(
        memory_file,
        as_attachment=True,
        download_name=zip_name,
        mimetype='application/zip'
    )

@app.route('/search')
@login_required
def search():
    query = request.args.get('q', '').lower()
    if not query:
        return redirect(url_for('index'))
    
    results = []
    for file_path in BASE_DIR.rglob('*'):
        if file_path.is_file() and query in file_path.name.lower():
            rel = file_path.relative_to(BASE_DIR)
            stat = file_path.stat()
            results.append({
                'name': file_path.name,
                'path': str(rel),
                'size': stat.st_size,
                'size_str': format_size(stat.st_size),
                'mime': get_mime_type(file_path)
            })
    
    return render_template_string(SEARCH_TEMPLATE, 
        query=query, 
        results=results[:100],
        base_url=request.url_root.rstrip('/')
    )

def format_size(size):
    for unit in ['B', 'KB', 'MB', 'GB']:
        if size < 1024:
            return f"{size:.1f} {unit}"
        size /= 1024
    return f"{size:.1f} TB"

def get_mime_type(path):
    mime, _ = mimetypes.guess_type(str(path))
    return mime or 'application/octet-stream'

# Templates
LOGIN_TEMPLATE = '''
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>MARK File Manager - Login</title>
    <style>
        * { box-sizing: border-box; margin: 0; padding: 0; }
        body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; 
               background: linear-gradient(135deg, #1e3a8a 0%, #3b82f6 100%); min-height: 100vh; 
               display: flex; align-items: center; justify-content: center; padding: 20px; }
        .login-card { background: white; border-radius: 16px; padding: 40px; width: 100%; max-width: 400px; 
                      box-shadow: 0 20px 40px rgba(0,0,0,0.2); }
        .logo { text-align: center; margin-bottom: 30px; }
        .logo h1 { color: #1e3a8a; font-size: 2rem; font-weight: 700; }
        .logo p { color: #64748b; margin-top: 8px; }
        .form-group { margin-bottom: 20px; }
        label { display: block; margin-bottom: 8px; color: #334155; font-weight: 500; }
        input[type="password"] { width: 100%; padding: 14px 16px; border: 2px solid #e2e8f0; 
                                 border-radius: 10px; font-size: 1rem; transition: border-color 0.2s; }
        input[type="password"]:focus { outline: none; border-color: #3b82f6; }
        button { width: 100%; padding: 14px; background: #1e3a8a; color: white; border: none; 
                 border-radius: 10px; font-size: 1rem; font-weight: 600; cursor: pointer; 
                 transition: background 0.2s; }
        button:hover { background: #1e40af; }
        .error { color: #ef4444; text-align: center; margin-bottom: 16px; padding: 12px; 
                 background: #fef2f2; border-radius: 8px; }
    </style>
</head>
<body>
    <div class="login-card">
        <div class="logo">
            <h1>📁 MARK</h1>
            <p>File Manager</p>
        </div>
        {% if error %}
            <div class="error">{{ error }}</div>
        {% endif %}
        <form method="POST">
            <div class="form-group">
                <label for="password">Password</label>
                <input type="password" id="password" name="password" placeholder="Enter password" required autofocus>
            </div>
            <button type="submit">Sign In</button>
        </form>
    </div>
</body>
</html>
'''

BROWSE_TEMPLATE = '''
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>MARK File Manager - {{ current_path or 'Root' }}</title>
    <style>
        * { box-sizing: border-box; margin: 0; padding: 0; }
        body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; 
               background: #f8fafc; min-height: 100vh; }
        header { background: white; border-bottom: 1px solid #e2e8f0; padding: 16px 24px; 
                 position: sticky; top: 0; z-index: 100; display: flex; align-items: center; 
                 justify-content: space-between; flex-wrap: wrap; gap: 16px; }
        .header-left { display: flex; align-items: center; gap: 16px; }
        .logo { display: flex; align-items: center; gap: 10px; }
        .logo svg { width: 32px; height: 32px; color: #3b82f6; }
        .logo h1 { font-size: 1.25rem; font-weight: 700; color: #1e293b; }
        .path-nav { display: flex; align-items: center; gap: 4px; font-size: 0.875rem; color: #64748b; }
        .path-segment { padding: 4px 8px; border-radius: 6px; text-decoration: none; color: #3b82f6; 
                        background: #eff6ff; transition: background 0.2s; }
        .path-segment:hover { background: #dbeafe; }
        .path-current { color: #1e293b; font-weight: 500; }
        .header-actions { display: flex; gap: 12px; align-items: center; }
        .btn { padding: 10px 20px; border-radius: 8px; font-size: 0.875rem; font-weight: 500; 
               text-decoration: none; display: inline-flex; align-items: center; gap: 8px; 
               border: none; cursor: pointer; transition: all 0.2s; }
        .btn-primary { background: #1e3a8a; color: white; }
        .btn-primary:hover { background: #1e40af; }
        .btn-secondary { background: #f1f5f9; color: #334155; }
        .btn-secondary:hover { background: #e2e8f0; }
        .btn-danger { background: #fef2f2; color: #ef4444; }
        .btn-danger:hover { background: #fee2e2; }
        .search-box { position: relative; }
        .search-box input { padding: 10px 16px 10px 40px; border: 2px solid #e2e8f0; 
                           border-radius: 10px; font-size: 0.875rem; width: 250px; 
                           transition: border-color 0.2s; }
        .search-box input:focus { outline: none; border-color: #3b82f6; }
        .search-box svg { position: absolute; left: 12px; top: 50%; transform: translateY(-50%); 
                          color: #94a3b8; width: 18px; height: 18px; }
        main { padding: 24px; }
        .shortcuts { display: flex; gap: 12px; margin-bottom: 24px; flex-wrap: wrap; }
        .shortcut-card { background: white; border: 1px solid #e2e8f0; border-radius: 12px; 
                         padding: 16px 20px; display: flex; align-items: center; gap: 12px; 
                         min-width: 200px; flex: 1; transition: all 0.2s; }
        .shortcut-card:hover { border-color: #3b82f6; box-shadow: 0 4px 12px rgba(59,130,246,0.1); }
        .shortcut-icon { width: 48px; height: 48px; border-radius: 12px; display: flex; 
                         align-items: center; justify-content: center; font-size: 1.5rem; }
        .shortcut-icon.apk { background: #fef3c7; color: #f59e0b; }
        .shortcut-icon.zip { background: #dbeafe; color: #3b82f6; }
        .shortcut-icon.docs { background: #dcfce7; color: #10b981; }
        .shortcut-info { flex: 1; min-width: 0; }
        .shortcut-label { font-size: 0.75rem; color: #64748b; text-transform: uppercase; 
                          letter-spacing: 0.05em; margin-bottom: 2px; }
        .shortcut-name { font-weight: 600; color: #1e293b; white-space: nowrap; overflow: hidden; 
                         text-overflow: ellipsis; }
        .file-table { width: 100%; background: white; border-radius: 12px; overflow: hidden; 
                      border: 1px solid #e2e8f0; }
        table { width: 100%; border-collapse: collapse; }
        th, td { padding: 14px 16px; text-align: left; border-bottom: 1px solid #f1f5f9; }
        th { background: #f8fafc; font-weight: 600; color: #64748b; font-size: 0.75rem; 
             text-transform: uppercase; letter-spacing: 0.05em; }
        tr:hover { background: #f8fafc; }
        .file-name { display: flex; align-items: center; gap: 10px; font-weight: 500; color: #1e293b; }
        .file-icon { width: 32px; height: 32px; border-radius: 6px; display: flex; 
                     align-items: center; justify-content: center; flex-shrink: 0; }
        .file-icon.folder { background: #fef3c7; color: #f59e0b; }
        .file-icon.apk { background: #fef3c7; color: #f59e0b; }
        .file-icon.zip { background: #dbeafe; color: #3b82f6; }
        .file-icon.code { background: #e0e7ff; color: #6366f1; }
        .file-icon.doc { background: #dcfce7; color: #10b981; }
        .file-icon.image { background: #fce7f3; color: #ec4899; }
        .file-icon.default { background: #f1f5f9; color: #64748b; }
        .file-meta { color: #64748b; font-size: 0.875rem; }
        .btn-sm { padding: 8px 12px; font-size: 0.8125rem; }
        .empty-state { text-align: center; padding: 60px 20px; color: #94a3b8; }
        .empty-state svg { width: 64px; height: 64px; margin-bottom: 16px; opacity: 0.5; }
    </style>
</head>
<body>
    <header>
        <div class="header-left">
            <div class="logo">
                <svg xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 24 24" stroke-width="1.5" stroke="currentColor">
                    <path stroke-linecap="round" stroke-linejoin="round" d="M3 16.5v2.25A2.25 2.25 0 0 0 5.25 21h13.5A2.25 2.25 0 0 0 21 18.75V16.5m-13.5-9L12 3m0 0l4.5 4.5M12 3v13.5" />
                </svg>
                <h1>MARK</h1>
            </div>
            <nav class="path-nav">
                <a href="{{ base_url }}/" class="path-segment">Root</a>
                {% if current_path %}
                    {% set parts = current_path.split('/') %}
                    {% for i in range(parts|length) %}
                        <span>/</span>
                        {% set partial = parts[:i+1]|join('/') %}
                        <a href="{{ base_url }}/browse/{{ partial }}" class="path-segment">{{ parts[i] }}</a>
                    {% endfor %}
                {% endif %}
            </nav>
        </div>
        <div class="header-actions">
            <div class="search-box">
                <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                    <circle cx="11" cy="11" r="8" />
                    <path d="m21 21-4.35-4.35" />
                </svg>
                <form action="{{ base_url }}/search" method="GET">
                    <input type="search" name="q" placeholder="Search files..." autocomplete="off">
                </form>
            </div>
            <a href="{{ base_url }}/logout" class="btn btn-danger">Logout</a>
        </div>
    </header>
    <main>
        <!-- Shortcuts -->
        <div class="shortcuts">
            <a href="{{ base_url }}/download/app/build/outputs/apk/debug/app-debug.apk" class="shortcut-card">
                <div class="shortcut-icon apk">📱</div>
                <div class="shortcut-info">
                    <div class="shortcut-label">APK Download</div>
                    <div class="shortcut-name">app-debug.apk (128 MB)</div>
                </div>
            </a>
            <a href="{{ base_url }}/download/PROJECT_EXPORT.zip" class="shortcut-card">
                <div class="shortcut-icon zip">📦</div>
                <div class="shortcut-info">
                    <div class="shortcut-label">Source Export</div>
                    <div class="shortcut-name">PROJECT_EXPORT.zip (40 MB)</div>
                </div>
            </a>
            <a href="{{ base_url }}/download/UPDATED_PROJECT_IMPLEMENTATION_GUIDE.md" class="shortcut-card">
                <div class="shortcut-icon docs">📄</div>
                <div class="shortcut-info">
                    <div class="shortcut-label">Documentation</div>
                    <div class="shortcut-name">Implementation Guide</div>
                </div>
            </a>
        </div>

        <!-- File Table -->
        <div class="file-table">
            {% if items %}
                <table>
                    <thead>
                        <tr>
                            <th style="width: 50%">Name</th>
                            <th style="width: 15%">Size</th>
                            <th style="width: 20%">Type</th>
                            <th style="width: 15%">Actions</th>
                        </tr>
                    </thead>
                    <tbody>
                        {% for item in items %}
                        <tr>
                            <td>
                                <div class="file-name">
                                    {% if item.is_dir %}
                                        <div class="file-icon folder">📁</div>
                                        <a href="{{ base_url }}/browse/{{ item.path }}">{{ item.name }}</a>
                                    {% else %}
                                        <div class="file-icon {{ 'apk' if item.name.endswith('.apk') else 'zip' if item.name.endswith('.zip') else 'code' if item.name.endswith('.kt') or item.name.endswith('.xml') or item.name.endswith('.json') else 'doc' if item.name.endswith('.md') else 'image' if item.mime.startswith('image/') else 'default' }}">
                                            {% if item.name.endswith('.apk') %}📱
                                            {% elif item.name.endswith('.zip') %}📦
                                            {% elif item.name.endswith('.kt') or item.name.endswith('.xml') or item.name.endswith('.json') %}📝
                                            {% elif item.name.endswith('.md') %}📄
                                            {% elif item.mime.startswith('image/') %}🖼️
                                            {% else %}📄
                                            {% endif %}
                                        </div>
                                        <span>{{ item.name }}</span>
                                    {% endif %}
                                </div>
                            </td>
                            <td class="file-meta">{{ item.size_str }}</td>
                            <td class="file-meta">{{ item.mime }}</td>
                            <td>
                                {% if item.is_dir %}
                                    <a href="{{ base_url }}/zip/{{ item.path }}" class="btn btn-secondary btn-sm">📦 Download as ZIP</a>
                                {% else %}
                                    <a href="{{ base_url }}/download/{{ item.path }}" class="btn btn-primary btn-sm">⬇️ Download</a>
                                {% endif %}
                            </td>
                        </tr>
                        {% endfor %}
                    </tbody>
                </table>
            {% else %}
                <div class="empty-state">
                    <svg xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 24 24" stroke-width="1.5" stroke="currentColor">
                        <path stroke-linecap="round" stroke-linejoin="round" d="M3 16.5v2.25A2.25 2.25 0 0 0 5.25 21h13.5A2.25 2.25 0 0 0 21 18.75V16.5m-13.5-9L12 3m0 0l4.5 4.5M12 3v13.5" />
                    </svg>
                    <p>This folder is empty</p>
                </div>
            {% endif %}
        </div>
    </main>
</body>
</html>
'''

SEARCH_TEMPLATE = '''
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>Search Results - MARK File Manager</title>
    <style>
        * { box-sizing: border-box; margin: 0; padding: 0; }
        body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; background: #f8fafc; min-height: 100vh; }
        header { background: white; border-bottom: 1px solid #e2e8f0; padding: 16px 24px; position: sticky; top: 0; z-index: 100; }
        .logo { display: flex; align-items: center; gap: 10px; }
        .logo svg { width: 32px; height: 32px; color: #3b82f6; }
        .logo h1 { font-size: 1.25rem; font-weight: 700; color: #1e293b; }
        .search-info { color: #64748b; margin-top: 4px; }
        main { padding: 24px; }
        .results { background: white; border-radius: 12px; border: 1px solid #e2e8f0; overflow: hidden; }
        table { width: 100%; border-collapse: collapse; }
        th, td { padding: 14px 16px; text-align: left; border-bottom: 1px solid #f1f5f9; }
        th { background: #f8fafc; font-weight: 600; color: #64748b; font-size: 0.75rem; text-transform: uppercase; }
        tr:hover { background: #f8fafc; }
        .file-name { font-weight: 500; color: #1e293b; }
        .file-meta { color: #64748b; font-size: 0.875rem; }
        .btn { padding: 8px 16px; border-radius: 8px; font-size: 0.875rem; font-weight: 500; text-decoration: none; display: inline-flex; align-items: center; gap: 8px; border: none; cursor: pointer; }
        .btn-primary { background: #1e3a8a; color: white; }
        .btn-primary:hover { background: #1e40af; }
    </style>
</head>
<body>
    <header>
        <div class="logo">
            <svg xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 24 24" stroke-width="1.5" stroke="currentColor">
                <path stroke-linecap="round" stroke-linejoin="round" d="M3 16.5v2.25A2.25 2.25 0 0 0 5.25 21h13.5A2.25 2.25 0 0 0 21 18.75V16.5m-13.5-9L12 3m0 0l4.5 4.5M12 3v13.5" />
            </svg>
            <h1>MARK</h1>
        </div>
        <div class="search-info">Search results for: <strong>"{{ query }}"</strong> — {{ results|length }} file(s) found</div>
    </header>
    <main>
        <div class="results">
            {% if results %}
                <table>
                    <thead>
                        <tr>
                            <th style="width: 50%">Name</th>
                            <th style="width: 15%">Size</th>
                            <th style="width: 20%">Type</th>
                            <th style="width: 15%">Actions</th>
                        </tr>
                    </thead>
                    <tbody>
                        {% for item in results %}
                        <tr>
                            <td class="file-name">{{ item.name }}</td>
                            <td class="file-meta">{{ item.size_str }}</td>
                            <td class="file-meta">{{ item.mime }}</td>
                            <td><a href="{{ base_url }}/download/{{ item.path }}" class="btn btn-primary">Download</a></td>
                        </tr>
                        {% endfor %}
                    </tbody>
                </table>
            {% else %}
                <div style="padding: 60px 20px; text-align: center; color: #94a3b8;">
                    <p>No files found matching "{{ query }}"</p>
                </div>
            {% endif %}
        </div>
        <div style="margin-top: 24px; text-align: center;">
            <a href="{{ base_url }}/" style="color: #3b82f6; text-decoration: none;">← Back to File Manager</a>
        </div>
    </main>
</body>
</html>
'''

if __name__ == '__main__':
    port = int(os.environ.get('PORT', 8080))
    app.run(host='0.0.0.0', port=port, debug=False)