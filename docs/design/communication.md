# Communication Protocol

This document describes how the Android app and laptop daemon communicate.

## Overview

RemoteAgentShell uses **direct peer-to-peer (P2P) communication** between the phone and laptop. There is no relay server - all traffic flows directly between devices.

```
┌─────────────┐                              ┌─────────────┐
│   Android   │◀══════════ P2P ═════════════▶│   Laptop    │
│     App     │         (WebRTC)             │   Daemon    │
└─────────────┘                              └─────────────┘
```

## Technology Stack

| Component | Technology | Purpose |
|-----------|------------|---------|
| P2P Transport | WebRTC DataChannel | Bidirectional real-time data |
| NAT Traversal | STUN | Discover public IP, hole punching |
| Signaling | QR Code | Exchange initial connection info |
| IP Updates | ntfy.sh | Notify phone when laptop IP changes |

## Why WebRTC?

- **P2P**: Direct connection, no relay server
- **NAT Traversal**: Built-in STUN/ICE for hole punching
- **Encryption**: DTLS encryption included
- **Battle-tested**: Same tech as video calls (Google Meet, Discord)
- **Cross-platform**: Works on Android and desktop

## Why No TURN (Relay)?

TURN servers relay traffic when P2P fails. We explicitly don't use TURN because:

1. **Latency**: Relay adds latency
2. **Infrastructure**: Would require running/paying for servers
3. **Privacy**: Traffic would pass through third party

**Trade-off**: P2P works for ~80-85% of networks. On strict NAT (some corporate firewalls, certain mobile carriers), connection may fail. This is acceptable for our use case.

---

## Connection Phases

### Phase 1: Initial Pairing (QR Code)

First-time setup requires physical access to the laptop.

```
┌─────────────────────────────────────────────────────────────────┐
│                         LAPTOP                                  │
│                                                                 │
│  1. Daemon starts                                               │
│  2. Generates 32-byte master secret                             │
│  3. Contacts STUN server to discover public IP                  │
│  4. Displays QR code                                            │
│                                                                 │
│     ┌─────────────────────────────────────┐                     │
│     │  ┌─────────────────┐                │                     │
│     │  │  QR CODE        │  Contains:     │                     │
│     │  │                 │  • Secret only │                     │
│     │  │                 │                │                     │
│     │  │                 │  (Everything   │                     │
│     │  └─────────────────┘   derived)     │                     │
│     └─────────────────────────────────────┘                     │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
                              │
                              │ User scans QR
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│                         ANDROID                                 │
│                                                                 │
│  1. Scans QR code                                               │
│  2. Extracts connection info                                    │
│  3. Stores master secret in Android Keystore                    │
│  4. Initiates P2P connection                                    │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

**QR Code Payload:**

The QR code contains only the master secret. Everything else is derived from it.

```
base64(protobuf(QrPayload))

QrPayload:
  version: 1
  master_secret: <32 bytes>
```

| Derived Value | Derivation |
|---------------|------------|
| `session_id` | HKDF(secret, "session") → first 24 hex chars |
| `ntfy_topic` | "ras-" + SHA256(secret)[:12] |
| `auth_key` | HKDF(secret, "auth") |
| `daemon IP/port` | Discovered via mDNS or ntfy DISCOVER |

### Phase 2: P2P Connection (WebRTC)

After scanning QR code, phone connects via HTTP to exchange SDP, then establishes WebRTC.

```
┌──────────┐                                          ┌──────────┐
│  Phone   │                                          │  Laptop  │
└────┬─────┘                                          └────┬─────┘
     │                                                     │
     │  1. Scan QR code (get master_secret)                │
     │  1a. Derive session_id, ntfy_topic from secret      │
     │  1b. Discover daemon via mDNS or ntfy DISCOVER      │
     │                                                     │
     │  2. Create WebRTC offer                             │
     │                                                     │
     │  3. POST /signal/{session_id}                       │
     │     body: { "offer": "sdp..." }                     │
     │─────────────────────────────────────────────────────▶
     │                                                     │
     │  4. Laptop creates answer                           │
     │                                                     │
     │  5. Response: { "answer": "sdp..." }                │
     │◀────────────────────────────────────────────────────│
     │                                                     │
     │  6. Both set remote descriptions                    │
     │                                                     │
     │  7. ICE negotiation / hole punching                 │
     │◀───────────────────────────────────────────────────▶│
     │                                                     │
     │  8. DTLS handshake (WebRTC built-in)                │
     │◀───────────────────────────────────────────────────▶│
     │                                                     │
     │  9. DataChannel open - P2P established!             │
     │◀═══════════════════════════════════════════════════▶│
     │                                                     │
```

**HTTP Signaling Endpoints (Daemon):**

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/health` | GET | Health check |
| `/signal/{session_id}` | POST | Exchange SDP offer/answer |

### Phase 3: Authentication Handshake

After P2P connection is established, both sides authenticate using the shared secret.

```
┌──────────┐                                          ┌──────────┐
│  Phone   │                                          │  Laptop  │
└────┬─────┘                                          └────┬─────┘
     │                                                     │
     │  1. Laptop sends challenge                          │
     │◀────────────────────────────────────────────────────│
     │     { "type": "challenge",                          │
     │       "nonce": "random-32-bytes" }                  │
     │                                                     │
     │  2. Phone responds with HMAC                        │
     │─────────────────────────────────────────────────────▶
     │     { "type": "response",                           │
     │       "hmac": HMAC(auth_key, nonce) }               │
     │                                                     │
     │  3. Laptop verifies HMAC                            │
     │     (proves phone knows the secret)                 │
     │                                                     │
     │  4. Laptop sends its own response                   │
     │◀────────────────────────────────────────────────────│
     │     { "type": "verified",                           │
     │       "hmac": HMAC(auth_key, nonce + 1) }           │
     │                                                     │
     │  5. Phone verifies                                  │
     │     (proves laptop knows the secret)                │
     │                                                     │
     │  6. Mutual authentication complete!                 │
     │◀═══════════════════════════════════════════════════▶│
     │                                                     │
```

### Phase 4: Reconnection

Phone stores connection info and reconnects automatically.

```
┌──────────┐                                          ┌──────────┐
│  Phone   │                                          │  Laptop  │
└────┬─────┘                                          └────┬─────┘
     │                                                     │
     │  1. App opens                                       │
     │                                                     │
     │  2. Retrieve stored IP/port/secret                  │
     │                                                     │
     │  3. Attempt P2P connection to stored address        │
     │─────────────────────────────────────────────────────▶
     │                                                     │
     │  4a. If success: authenticate and resume            │
     │◀═══════════════════════════════════════════════════▶│
     │                                                     │
     │  4b. If fail: check ntfy for IP update              │
     │      (see Phase 5)                                  │
     │                                                     │
```

### Phase 5: IP Change Notification

When laptop's IP changes, it notifies the phone via ntfy.

```
┌──────────┐          ┌──────────┐          ┌──────────┐
│  Laptop  │          │   ntfy   │          │  Phone   │
└────┬─────┘          └────┬─────┘          └────┬─────┘
     │                     │                     │
     │  1. Detect IP       │                     │
     │     change          │                     │
     │                     │                     │
     │  2. Create payload  │                     │
     │     {ip, timestamp, │                     │
     │      nonce}         │                     │
     │                     │                     │
     │  3. Encrypt with    │                     │
     │     ntfy_key        │                     │
     │                     │                     │
     │  4. POST to ntfy    │                     │
     │─────────────────────▶                     │
     │                     │                     │
     │                     │  5. Push to phone   │
     │                     │─────────────────────▶
     │                     │                     │
     │                     │     (encrypted      │
     │                     │      blob)          │
     │                     │                     │
     │                     │  6. Phone decrypts  │
     │                     │     with ntfy_key   │
     │                     │                     │
     │                     │  7. Verify timestamp│
     │                     │     (reject if old) │
     │                     │                     │
     │  8. Phone connects to new IP              │
     │◀══════════════════════════════════════════│
     │                     │                     │
```

**ntfy Message Payload (before encryption):**

```json
{
  "version": 1,
  "ip": "99.88.77.66",
  "port": 8821,
  "timestamp": 1706384400,
  "nonce": "random-16-bytes"
}
```

**Encrypted payload sent to ntfy:**

```
POST https://ntfy.sh/{topic}
Content-Type: application/octet-stream

{AES-GCM encrypted blob}
```

---

## STUN Servers

We use free public STUN servers for NAT traversal:

```
stun:stun.l.google.com:19302
stun:stun.cloudflare.com:3478
stun:stun.services.mozilla.com:3478
```

These are only used to discover public IPs. No traffic flows through them.

---

## ntfy Configuration

ntfy.sh is used only for IP change notifications.

| Setting | Value |
|---------|-------|
| Server | `ntfy.sh` (public) or self-hosted |
| Topic | Derived from master secret: `sha256(secret)[:12]` |
| Payload | AES-GCM encrypted JSON |
| Auth | None needed (topic is secret, payload is encrypted) |

**Self-hosting option:** Users can run their own ntfy server and configure the daemon to use it.

---

## Message Protocol

After authentication, all messages use **Protocol Buffers (protobuf)** for efficient binary serialization.

### Why Protobuf?

- **Compact**: Smaller payloads than JSON (important for terminal output streaming)
- **Fast**: Binary parsing is faster than JSON text parsing
- **Type-safe**: Schema validation, code generation for Kotlin and Python
- **Versioned**: Easy to add fields without breaking compatibility

### Proto Schema Location

Shared proto files are in the repository root:

```
remote-agent-shell/
├── proto/                    # Shared protobuf schemas
│   ├── messages.proto        # Message envelope and common types
│   ├── sessions.proto        # Session management messages
│   ├── terminal.proto        # Terminal input/output messages
│   └── clipboard.proto       # Clipboard/image transfer messages
├── daemon/                   # Python daemon (uses protobuf)
└── android/                  # Android app (uses protobuf)
```

### Message Envelope

All messages are wrapped in a `MessageEnvelope`:

```protobuf
message MessageEnvelope {
  uint64 id = 1;
  uint64 timestamp = 2;

  oneof payload {
    // Session management
    ListSessionsRequest list_sessions = 10;
    SessionsList sessions_list = 11;
    AttachRequest attach = 12;
    AttachResponse attached = 13;
    DetachRequest detach = 14;

    // Terminal
    TerminalInput input = 20;
    TerminalOutput output = 21;
    SpecialKey key = 22;
    ResizeTerminal resize = 23;
    ScrollbackRequest scrollback = 24;

    // Clipboard
    ClipboardStart clipboard_start = 30;
    ClipboardChunk clipboard_chunk = 31;
    ClipboardEnd clipboard_end = 32;
    ClipboardResult clipboard_result = 33;

    // Control
    Ping ping = 40;
    Pong pong = 41;
    Error error = 50;
  }
}
```

### Message Types

| Type | Direction | Description |
|------|-----------|-------------|
| `TerminalOutput` | Laptop → Phone | Terminal output from agent |
| `TerminalInput` | Phone → Laptop | User input to send to agent |
| `SpecialKey` | Phone → Laptop | Special key press (Ctrl+C, etc.) |
| `ListSessionsRequest` | Phone → Laptop | Request session list |
| `SessionsList` | Laptop → Phone | List of available sessions |
| `AttachRequest` | Phone → Laptop | Attach to a session |
| `AttachResponse` | Laptop → Phone | Attach confirmation with terminal size |
| `ClipboardStart/Chunk/End` | Phone → Laptop | Chunked clipboard transfer |
| `ClipboardResult` | Laptop → Phone | Clipboard operation result |
| `Ping/Pong` | Both | Keepalive |
| `Error` | Both | Error message |

**Full schema definitions:** See `/proto/*.proto` files

---

## Connection States

```
┌─────────────┐
│ DISCONNECTED│
└──────┬──────┘
       │ scan QR / open app
       ▼
┌─────────────┐
│ CONNECTING  │──────────────┐
└──────┬──────┘              │
       │ P2P established     │ timeout/fail
       ▼                     ▼
┌─────────────┐        ┌───────────┐
│AUTHENTICATING│        │  FAILED   │
└──────┬──────┘        └───────────┘
       │ auth success
       ▼
┌─────────────┐
│  CONNECTED  │◀─────────────┐
└──────┬──────┘              │
       │ connection lost     │ reconnect success
       ▼                     │
┌─────────────┐              │
│RECONNECTING │──────────────┘
└──────┬──────┘
       │ all retries failed
       ▼
┌─────────────┐
│ DISCONNECTED│
└─────────────┘
```

---

## Error Handling

| Error | Cause | Resolution |
|-------|-------|------------|
| STUN failure | Can't reach STUN servers | Check internet connection |
| P2P timeout | NAT too strict, firewall | Try different network |
| Auth failure | Wrong secret | Re-scan QR code |
| ntfy decrypt fail | Corrupted or tampered message | Ignore message |
| ntfy replay | Old message replayed | Reject (timestamp check) |

---

## Multi-Device Support

Multiple phones/tablets can connect simultaneously, each with its own secret.

### Device Management

```
~/.config/ras/devices/
  ├── device-a1b2c3.json
  └── device-x7y8z9.json
```

**Device file format:**
```json
{
  "id": "a1b2c3",
  "name": "Pixel 8",
  "secret": "base64-encoded-32-bytes",
  "topic": "ras-8f2k4m",
  "paired_at": "2025-01-27T10:30:00Z",
  "last_seen": "2025-01-27T15:45:00Z"
}
```

### Pairing New Device

Each device scans its own QR code with a unique secret:

```
$ ras pair
Generating new device pairing...

┌─────────────────────────────────┐
│  Scan to pair new device:       │
│  ┌───────────────────┐          │
│  │ QR CODE           │          │
│  │ (unique secret)   │          │
│  └───────────────────┘          │
│                                 │
│  Device ID: a1b2c3              │
└─────────────────────────────────┘
```

### Output Broadcasting

Daemon broadcasts output to ALL connected devices:

```
┌─────────┐      ┌─────────┐      ┌─────────┐
│  tmux   │─────▶│ daemon  │─────▶│ Phone 1 │
│ output  │      │         │─────▶│ Phone 2 │
└─────────┘      └─────────┘─────▶│ Tablet  │
                                  └─────────┘
```

### Input Handling

Any device can send input. Daemon forwards to tmux:

```
┌─────────┐      ┌─────────┐      ┌─────────┐
│ Phone 1 │─────▶│         │      │         │
│ Phone 2 │─────▶│ daemon  │─────▶│  tmux   │
│ Tablet  │─────▶│         │      │         │
└─────────┘      └─────────┘      └─────────┘
```

**Conflict handling (MVP):** Last-write-wins. Input is processed in order received.

**Future:** Add optional locking (one device takes control, others read-only).

### Input Echo

When one device sends input, others are notified:

```json
{
  "type": "input_echo",
  "from": "Pixel 8",
  "data": "approve"
}
```

This prevents confusion when multiple people are watching.

### Revoking a Device

```bash
$ ras devices list
ID       NAME       LAST SEEN
a1b2c3   Pixel 8    2 minutes ago
x7y8z9   iPad       3 days ago

$ ras devices revoke x7y8z9
Device 'iPad' revoked. It can no longer connect.
```

Revoked device's secret is deleted. It must re-pair to connect again.

---

## Future Considerations

- **Multiple agents**: Connect to multiple tmux sessions
- **Web client**: Browser-based client in addition to Android
- **iOS app**: Port to iOS
