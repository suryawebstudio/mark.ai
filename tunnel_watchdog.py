#!/usr/bin/env python3
"""
Cloudflare Tunnel Auto-Restart Watchdog
Monitors cloudflared process and restarts automatically when platform kills it.
Captures and logs new public URL on each restart.
"""

import subprocess
import time
import re
import sys
import signal
import os
from datetime import datetime

# Configuration
TUNNEL_CMD = ['./cloudflared', 'tunnel', '--url', 'http://127.0.0.1:8080']
URL_PATTERN = re.compile(r'https://[a-z0-9-]+\.trycloudflare\.com')
CHECK_INTERVAL = 30  # seconds
RESTART_DELAY = 5    # seconds
LOG_FILE = '/tmp/tunnel_watchdog.log'
URL_LOG_FILE = '/tmp/tunnel_urls.log'

# Global process reference
tunnel_process = None
current_url = None
shutdown = False

def log_message(message, level="INFO"):
    """Log message with timestamp to stdout and log file."""
    timestamp = datetime.now().strftime('%Y-%m-%d %H:%M:%S')
    log_entry = f"[{timestamp}] [{level}] {message}"
    print(log_entry, flush=True)
    try:
        with open(LOG_FILE, 'a') as f:
            f.write(log_entry + '\n')
    except:
        pass

def log_url(url):
    """Log new tunnel URL to URL history file."""
    timestamp = datetime.now().strftime('%Y-%m-%d %H:%M:%S')
    log_entry = f"[{timestamp}] {url}"
    try:
        with open(URL_LOG_FILE, 'a') as f:
            f.write(log_entry + '\n')
    except:
        pass

def extract_url(text):
    """Extract trycloudflare.com URL from text."""
    match = URL_PATTERN.search(text)
    return match.group(0) if match else None

def signal_handler(signum, frame):
    """Handle shutdown signals gracefully."""
    global shutdown, tunnel_process
    log_message("Shutdown signal received, stopping tunnel...")
    shutdown = True
    if tunnel_process and tunnel_process.poll() is None:
        tunnel_process.terminate()
        try:
            tunnel_process.wait(timeout=5)
        except subprocess.TimeoutExpired:
            tunnel_process.kill()
            tunnel_process.wait()
    sys.exit(0)

def start_tunnel():
    """Start cloudflared tunnel process and return process object."""
    global tunnel_process, current_url
    
    log_message("Starting Cloudflare Quick Tunnel...")
    
    # Set up environment
    env = os.environ.copy()
    env['CLOUDFLARED_NO_AUTOUPDATE'] = '1'
    
    tunnel_process = subprocess.Popen(
        TUNNEL_CMD,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        bufsize=1,
        universal_newlines=True,
        cwd='/workspace/app',
        env=env
    )
    
    current_url = None
    return tunnel_process

def monitor_output(process):
    """Read process output line by line and extract URL."""
    global current_url
    
    url_reported = False
    
    while process.poll() is None:
        line = process.stdout.readline()
        if not line:
            break
            
        line = line.strip()
        if line:
            log_message(line, "TUNNEL")
            
            # Extract URL if not yet reported
            if not url_reported:
                url = extract_url(line)
                if url:
                    current_url = url
                    url_reported = True
                    log_message(f"🎉 NEW PUBLIC URL: {url}", "SUCCESS")
                    log_url(url)
                    print(f"\n{'='*60}")
                    print(f"PUBLIC URL: {url}")
                    print(f"{'='*60}\n", flush=True)
    
    # Process ended, read any remaining output
    if process.poll() is not None:
        remaining = process.stdout.read()
        if remaining:
            for line in remaining.strip().split('\n'):
                if line:
                    log_message(line, "TUNNEL")

def check_tunnel_health(process):
    """Check if tunnel process is still alive."""
    return process.poll() is None

def main():
    global tunnel_process, shutdown, current_url
    
    # Setup signal handlers
    signal.signal(signal.SIGINT, signal_handler)
    signal.signal(signal.SIGTERM, signal_handler)
    
    # Compile URL pattern globally
    global URL_PATTERN
    URL_PATTERN = re.compile(r'https://[a-z0-9-]+\.trycloudflare\.com')
    
    log_message("=" * 60)
    log_message("CLOUDFLARE TUNNEL WATCHDOG STARTED")
    log_message("Monitoring: cloudflared tunnel --url http://127.0.0.1:8080")
    log_message("Check interval: 30s | Restart delay: 5s")
    log_message("Press Ctrl+C to stop")
    log_message("=" * 60)
    
    restart_count = 0
    
    while not shutdown:
        # Start tunnel
        tunnel_process = start_tunnel()
        restart_count += 1
        log_message(f"Tunnel started (restart #{restart_count})", "START")
        
        # Monitor output and extract URL
        monitor_output(tunnel_process)
        
        # Process ended
        exit_code = tunnel_process.poll()
        log_message(f"Tunnel process ended with exit code: {exit_code}", "END")
        
        if shutdown:
            break
            
        # Wait before restart
        log_message(f"Waiting {RESTART_DELAY}s before restart...")
        for i in range(RESTART_DELAY):
            if shutdown:
                break
            time.sleep(1)
        
        if shutdown:
            break
    
    log_message("Watchdog stopped")

if __name__ == '__main__':
    # Compile regex once
    URL_PATTERN = re.compile(r'https://[a-z0-9-]+\.trycloudflare\.com')
    main()