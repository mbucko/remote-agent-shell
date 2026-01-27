# Daemon Architecture

The daemon (`ras`) runs on the laptop/server and handles all server-side operations.

## CLI Interface

### Daemon Control

```bash
ras daemon start              # Start daemon in background
ras daemon stop               # Stop daemon
ras daemon status             # Show status, connected devices, active sessions
ras daemon restart            # Restart daemon
ras daemon logs               # View daemon logs (tail -f style)
ras daemon logs --since 1h    # Logs from last hour
```

### Device Pairing

```bash
ras pair                      # Generate and display QR code for new device
ras pair --name "Pixel 8"     # Pair with device name

ras devices list              # List all paired devices
ras devices list --json       # JSON output for scripting

ras devices revoke <id>       # Revoke device access
ras devices revoke --all      # Revoke all devices
ras devices rename <id> <name># Rename device
```

### Session Management

```bash
ras sessions list             # List all sessions (running and stopped)
ras sessions list --active    # Only running sessions

ras sessions new              # Start new session (interactive prompts)
ras sessions new --agent claude --dir ~/repos/myproject
ras sessions new --command "python serve.py"

ras sessions attach <id>      # Attach to session locally (tmux attach)
ras sessions kill <id>        # Kill session
ras sessions kill --all       # Kill all sessions
```

### Configuration

```bash
ras config show               # Show current configuration
ras config show --json        # JSON output

ras config set <key> <value>  # Set config value
ras config set default_directory ~/projects
ras config set default_agent opencode
ras config set port 9000

ras config reset              # Reset to defaults
ras config reset <key>        # Reset specific key
```

### Diagnostics

```bash
ras doctor                    # Run system checks
                              # - Check installed agents
                              # - Check port availability
                              # - Check STUN connectivity
                              # - Check ntfy connectivity

ras version                   # Show version info
ras agents                    # List detected agents with paths
ras agents refresh            # Re-scan for installed agents
```

---

## Configuration

### Config File Location

```
~/.config/ras/config.yaml     # Main configuration
~/.config/ras/devices/        # Paired device secrets
~/.config/ras/sessions/       # Session history/metadata
~/.config/ras/ras.log         # Log file
```

### Config File Format

```yaml
# ~/.config/ras/config.yaml

# Server settings
port: 8821
bind_address: 0.0.0.0

# Default session settings
default_directory: ~/repos
default_agent: claude

# ntfy settings
ntfy_server: https://ntfy.sh
# ntfy_server: https://ntfy.myserver.com  # self-hosted

# IP monitoring
ip_check_interval: 30  # seconds

# Logging
log_level: info  # debug, info, warn, error
log_file: ~/.config/ras/ras.log
log_max_size: 10M
log_max_files: 5

# Auto-start
auto_start: false  # Register with systemd/launchd

# STUN servers
stun_servers:
  - stun:stun.l.google.com:19302
  - stun:stun.cloudflare.com:3478
```

---

## Agent Detection

### Supported Agents

| Agent | Detection Command | Default Args |
|-------|-------------------|--------------|
| Claude Code | `which claude` | (none) |
| Open Code | `which opencode` | (none) |
| Aider | `which aider` | (none) |
| Cursor | `which cursor` | (none) |
| Cline | `which cline` | (none) |

### Detection Process

On daemon startup (and on `ras agents refresh`):

```python
AGENTS = [
    {"id": "claude", "name": "Claude Code", "command": "claude"},
    {"id": "opencode", "name": "Open Code", "command": "opencode"},
    {"id": "aider", "name": "Aider", "command": "aider"},
    {"id": "cursor", "name": "Cursor", "command": "cursor"},
    {"id": "cline", "name": "Cline", "command": "cline"},
]

def detect_agents():
    installed = []
    for agent in AGENTS:
        result = subprocess.run(["which", agent["command"]], capture_output=True)
        if result.returncode == 0:
            path = result.stdout.decode().strip()
            installed.append({
                "id": agent["id"],
                "name": agent["name"],
                "path": path
            })
    return installed
```

### Custom Agents

Users can add custom agents in config:

```yaml
custom_agents:
  - id: my-agent
    name: My Custom Agent
    command: /path/to/my-agent
    args: ["--some-flag"]
```

---

## Session Management

### Session Lifecycle

```
┌─────────────┐
│   CREATED   │  ras sessions new
└──────┬──────┘
       │
       ▼
┌─────────────┐
│   RUNNING   │  Agent is executing
└──────┬──────┘
       │
       ├─────────────────────┐
       ▼                     ▼
┌─────────────┐       ┌─────────────┐
│    IDLE     │       │   EXITED    │  Agent finished/crashed
│  (waiting)  │       └──────┬──────┘
└─────────────┘              │
                             ▼
                      ┌─────────────┐
                      │   STOPPED   │  Cleaned up
                      └─────────────┘
```

### Session Storage

```json
// ~/.config/ras/sessions/sess-a1b2c3.json
{
  "id": "a1b2c3",
  "name": "claude-myproject",
  "agent": "claude",
  "directory": "/home/user/repos/myproject",
  "tmux_session": "ras-a1b2c3",
  "status": "running",
  "created_at": "2025-01-27T10:30:00Z",
  "started_by": "device-x7y8z9",  // which device started it
  "pid": 12345
}
```

### tmux Session Naming

```
ras-{session_id}

Example: ras-a1b2c3
```

All RAS sessions prefixed with `ras-` for easy identification.

---

## IP Monitoring

### Detection Methods

1. **Periodic STUN query** (reliable, works behind NAT)
2. **Network interface monitoring** (fast, local only)

### Flow

```
┌─────────────────────────────────────────────────────────────┐
│                       DAEMON                                │
│                                                             │
│  1. Query STUN every {ip_check_interval} seconds            │
│  2. Compare with last known IP                              │
│  3. If changed:                                             │
│     a. Update stored IP                                     │
│     b. Encrypt new IP with each device's ntfy_key           │
│     c. POST to each device's ntfy topic                     │
│                                                             │
└─────────────────────────────────────────────────────────────┘
```

### IP Change Notification

```python
def on_ip_change(new_ip: str, new_port: int):
    for device in paired_devices:
        payload = {
            "ip": new_ip,
            "port": new_port,
            "timestamp": int(time.time()),
            "nonce": secrets.token_bytes(16).hex()
        }
        encrypted = encrypt(device.ntfy_key, json.dumps(payload))

        requests.post(
            f"{config.ntfy_server}/{device.topic}",
            data=encrypted,
            headers={"Content-Type": "application/octet-stream"}
        )
```

---

## Background Service

### Linux (systemd)

```ini
# ~/.config/systemd/user/ras.service
[Unit]
Description=RemoteAgentShell Daemon
After=network.target

[Service]
Type=simple
ExecStart=/usr/local/bin/ras daemon start --foreground
Restart=on-failure
RestartSec=5

[Install]
WantedBy=default.target
```

```bash
# Enable auto-start
ras config set auto_start true
systemctl --user enable ras
systemctl --user start ras
```

### macOS (launchd)

```xml
<!-- ~/Library/LaunchAgents/com.ras.daemon.plist -->
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>Label</key>
    <string>com.ras.daemon</string>
    <key>ProgramArguments</key>
    <array>
        <string>/usr/local/bin/ras</string>
        <string>daemon</string>
        <string>start</string>
        <string>--foreground</string>
    </array>
    <key>RunAtLoad</key>
    <true/>
    <key>KeepAlive</key>
    <true/>
</dict>
</plist>
```

---

## Logging

### Log Levels

| Level | Description |
|-------|-------------|
| `debug` | Verbose debugging info |
| `info` | Normal operation events |
| `warn` | Warning conditions |
| `error` | Error conditions |

### Log Format

```
2025-01-27 10:30:45 [INFO] Daemon started on port 8821
2025-01-27 10:30:46 [INFO] Detected agents: claude, opencode
2025-01-27 10:31:02 [INFO] Device 'Pixel 8' connected
2025-01-27 10:31:05 [DEBUG] WebRTC connection established
2025-01-27 10:32:15 [INFO] New session created: ras-a1b2c3
2025-01-27 10:45:00 [WARN] IP changed: 98.23.45.1 -> 99.88.77.66
2025-01-27 10:45:01 [INFO] Sent IP update to 2 devices
```

### Log Rotation

- Max file size: configurable (default 10MB)
- Max files: configurable (default 5)
- Old logs: `ras.log.1`, `ras.log.2`, etc.

---

## Security

### Secret Storage

```
~/.config/ras/devices/
├── device-a1b2c3.json    # Contains encrypted secrets
└── device-x7y8z9.json
```

- File permissions: `600` (owner read/write only)
- Directory permissions: `700` (owner only)

### Daemon Binding

- Default: `0.0.0.0:8821` (all interfaces)
- Configurable: `bind_address` in config
- Firewall: No manual port opening needed (NAT hole punching)

---

## Open Questions

1. **Language choice**: Python (fast dev) vs Go (single binary, performance) vs Rust (performance, safety)?
2. **Windows support**: How to handle background service on Windows?
3. **GUI for QR code**: Terminal-based QR? Open browser? Desktop notification?
4. **Session limits**: Max concurrent sessions?

---

## Related Documents

- [communication.md](communication.md) - P2P protocol details
- [security.md](security.md) - Encryption and authentication
- [features.md](features.md) - Complete feature list
