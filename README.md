# RemoteAgentShell

A mobile app to remotely control your AI coding agents (Claude Code, Cursor, Cline, Aider, etc.) from your phone. Monitor agent output, send commands, and approve/reject tool calls remotely.

## Features

- **Session Management** - Create, list, rename, and kill agent sessions
- **Terminal I/O** - Real-time output streaming, keyboard input, special keys
- **Clipboard Sync** - Paste from phone clipboard to terminal
- **Push Notifications** - Get notified when agents need approval
- **QR Code Pairing** - Secure device pairing with mutual authentication
- **P2P Connection** - Direct WebRTC connection, no cloud required

## Quick Start

### Prerequisites

- Python 3.11+
- [uv](https://docs.astral.sh/uv/) (package manager)
- tmux

### Installation

```bash
cd daemon
uv sync --extra dev
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
3. Mobile sends HTTP signaling request with HMAC authentication
4. WebRTC connection established
5. Mutual authentication handshake over data channel
6. Device stored for future connections

## Security

- **HKDF** (RFC 5869) for key derivation
- **HMAC-SHA256** for authentication
- **Constant-time comparison** for all HMAC verification
- **Rate limiting** on signaling endpoint
- **Timestamp validation** (±30 seconds)
- **Sensitive data zeroing** on cleanup

## Architecture

### Daemon (Python)

- **Pairing Module**: QR generation, HTTP signaling, authentication
- **Session Manager**: Create/list/kill sessions, persistence, validation
- **Terminal Handler**: Output capture via pipe-pane, input via send-keys
- **Notification Detector**: Pattern matching for prompts/errors/completion
- **WebRTC**: Peer connection management via aiortc
- **Crypto**: HKDF key derivation, HMAC utilities
- **CLI**: Click-based command interface

### Mobile App (Kotlin)

- QR code scanning and pairing
- WebRTC client with auto-reconnect
- Session list and management UI
- Terminal emulator (Termux library)
- Clipboard paste support
- Push notification handling
- Deep link navigation

## Protocol Buffers

Protocol definitions are in `proto/`:

- `qr_payload.proto` - QR code data structure
- `signaling.proto` - HTTP signaling messages
- `auth.proto` - Authentication handshake
- `ntfy.proto` - IP change notifications
- `ras.proto` - Main protocol (sessions, terminal I/O, notifications)

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

## License

MIT License - see [LICENSE](LICENSE) for details.
