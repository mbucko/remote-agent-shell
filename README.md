# RemoteAgentShell

A mobile app to remotely control your AI coding agents (Claude Code, Cursor, Cline, Aider, etc.) from your phone. Monitor agent output, send commands, and approve/reject tool calls remotely.

## Features

- **Session Management** - Create, list, rename, and kill agent sessions
- **Terminal I/O** - Real-time output streaming, keyboard input, special keys
- **Clipboard Sync** - Paste from phone clipboard to terminal
- **Push Notifications** - Get notified when agents need approval
- **QR Code Pairing** - Secure device pairing with mutual authentication
- **P2P Connection** - Direct WebRTC connection, no cloud required
- **NAT Traversal** - ntfy signaling relay when direct connection fails
- **Tailscale Direct** - Fastest connection when both devices are on Tailscale

## Connection Methods

The app tries multiple connection strategies in order of preference:

### Working

| Method | Scenario | Status |
|--------|----------|--------|
| **Tailscale Direct** | Both devices on same Tailscale network | ✅ Works |
| **Local Network** | Same WiFi/LAN, direct HTTP signaling | ✅ Works |
| **WebRTC + STUN** | Different networks, typical home NAT | ✅ Works |
| **WebRTC + ntfy** | Signaling relay when direct fails | ✅ Works |

### TODO

| Method | Scenario | Status |
|--------|----------|--------|
| **TURN Relay** | Fallback when P2P fails completely | 🚧 Not implemented |
| **Hairpin NAT** | Both devices behind same public IP | 🚧 Detected but no workaround |
| **Symmetric NAT** | Both on commercial VPN (NordVPN, etc.) | 🚧 Detected but no workaround |

### Connection Priority

1. **Tailscale** (if both on Tailscale) - Direct UDP, lowest latency
2. **Local HTTP** (same network) - Direct WebRTC signaling
3. **ntfy relay** (cross-NAT) - WebRTC via encrypted relay

## Quick Start

### Prerequisites

- Python 3.11+
- [uv](https://docs.astral.sh/uv/) (package manager)
- tmux

### Installation

```bash
cd daemon
uv sync
```

### Running the Daemon

```bash
# Start the daemon (foreground)
cd daemon
uv run ras daemon start

# Check daemon status
uv run ras daemon status

# Stop the daemon
uv run ras daemon stop
```

### Install as System Service (Recommended)

The daemon should run in the background and start automatically on login.

**One-line install:**

```bash
./services/install.sh
```

This auto-detects your OS (macOS/Linux) and installs the appropriate service.

**Manual installation:**

<details>
<summary>macOS (launchd)</summary>

```bash
# Copy service file
cp services/com.ras.daemon.plist ~/Library/LaunchAgents/

# Edit paths in the plist to match your installation
# Then load the service
launchctl load ~/Library/LaunchAgents/com.ras.daemon.plist

# Commands
launchctl start com.ras.daemon    # Start
launchctl stop com.ras.daemon     # Stop
tail -f /tmp/ras.log              # View logs
```

</details>

<details>
<summary>Linux (systemd)</summary>

```bash
# Copy service file
mkdir -p ~/.config/systemd/user
cp services/ras.service ~/.config/systemd/user/

# Edit paths in the service file to match your installation
# Then enable and start
systemctl --user daemon-reload
systemctl --user enable ras
systemctl --user start ras

# Commands
systemctl --user status ras       # Status
journalctl --user -u ras -f       # View logs
```

</details>

**Uninstall:**

```bash
./services/install.sh --remove
```

### Running Tests

```bash
cd daemon

# Run all tests
uv run pytest

# Run with coverage
uv run pytest --cov=ras
```

## Pairing

The daemon and mobile app use QR code pairing with mutual authentication:

```bash
# Generate pairing QR code (terminal)
uv run ras pair

# Open QR code in browser
uv run ras pair --browser

# Save QR code to file
uv run ras pair --output qr.png
```

### Pairing Flow

1. Daemon generates master secret and displays QR code
2. Mobile app scans QR, extracts secret and connection info
3. Mobile attempts direct HTTP signaling with HMAC authentication
4. If direct connection fails (NAT), falls back to ntfy signaling relay
5. WebRTC connection established via STUN/TURN
6. Mutual authentication handshake over data channel
7. Device stored for future reconnections

### Network Permissions

The daemon requires incoming network connections for WebRTC (peer-to-peer).

**macOS**: When first running the daemon, macOS will prompt "Do you want to allow incoming network connections?" for your terminal app (Terminal, iTerm, VS Code, etc.). **Click "Allow"** - without this, WebRTC ICE connectivity checks will fail and pairing will timeout.

**Windows**: You may need to allow the daemon through Windows Firewall. A prompt should appear on first run.

**Linux**: If using `ufw` or `iptables`, ensure UDP traffic is allowed for WebRTC:
```bash
# UFW example - allow UDP for WebRTC
sudo ufw allow proto udp from 192.168.0.0/16
```

## Security

- **HKDF** (RFC 5869) for key derivation
- **AES-256-GCM** for authenticated encryption (ntfy signaling)
- **HMAC-SHA256** for authentication
- **Constant-time comparison** for all HMAC verification
- **Rate limiting** on signaling endpoints
- **Timestamp validation** (±30 seconds)
- **Nonce replay protection** for ntfy messages
- **Sensitive data zeroing** on cleanup

## Architecture

### Daemon (Python)

- **Pairing Module**: QR generation, HTTP signaling, authentication
- **ntfy Signaling**: Encrypted relay for NAT traversal (AES-256-GCM)
- **Session Manager**: Create/list/kill sessions, persistence, validation
- **Terminal Handler**: Output capture via pipe-pane, input via send-keys
- **Notification Detector**: Pattern matching for prompts/errors/completion
- **WebRTC**: Peer connection management via aiortc
- **Crypto**: HKDF key derivation, AES-GCM, HMAC utilities
- **CLI**: Click-based command interface

### Mobile App (Kotlin)

- QR code scanning and pairing
- WebRTC client with auto-reconnect
- Session list and management UI
- Terminal emulator with full ANSI escape sequence support (Termux terminal-emulator, Apache 2.0)
- Clipboard paste support
- Push notification handling
- Deep link navigation

## Protocol Buffers

Protocol definitions are in `proto/`:

- `qr_payload.proto` - QR code data structure
- `signaling.proto` - HTTP signaling messages
- `auth.proto` - Authentication handshake
- `ntfy.proto` - IP change notifications
- `ntfy_signaling.proto` - Encrypted ntfy relay for NAT traversal
- `sessions.proto` - Session management messages
- `terminal.proto` - Terminal I/O messages
- `clipboard.proto` - Clipboard sync messages
- `daemon.proto` - Daemon control messages

Regenerate Python code after changing protos:

```bash
cd daemon
uv run python -m grpc_tools.protoc -I../proto --python_betterproto_out=src/ras/proto ../proto/*.proto
```

## Session Management

Manage multiple AI agent sessions running in tmux:

```bash
# List sessions
uv run ras session list

# Create session (auto-detects installed agents)
uv run ras session create --directory ~/repos/myproject --agent claude

# Kill session
uv run ras session kill <session_id>
```

Sessions are automatically persisted and reconciled with tmux on daemon restart.

## Terminal I/O

Real-time terminal interaction over WebRTC:

- **Output streaming** - Chunked output via `tmux pipe-pane`
- **Input handling** - Keyboard input, special keys (Ctrl+C, arrows, etc.)
- **Raw mode** - Direct terminal access for interactive prompts
- **Reconnection buffer** - 100KB buffer for catching up after reconnect

## Push Notifications

Get notified when agents need your attention:

- **Approval prompts** - "Proceed? (y/n)" detected via pattern matching
- **Task completion** - Shell prompt returned after agent exits
- **Errors** - Error patterns detected in output

Notifications delivered via WebRTC (instant when connected). Tap to open session.

## Test Vectors

Cross-platform test vectors in `test-vectors/` ensure daemon and mobile app
implementations are compatible:

- `key_derivation.json` - HKDF test vectors
- `hmac.json` - HMAC-SHA256 test vectors
- `qr_payload.json` - QR payload encoding
- `auth_handshake.json` - Authentication protocol
- `signaling.json` - HTTP signaling format
- `ntfy.json` - ntfy notification format
- `ntfy_signaling.json` - ntfy signaling relay encryption
- `sessions.json` - Session management protocol

## License

MIT License - see [LICENSE](LICENSE) for details.
