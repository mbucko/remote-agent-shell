# RemoteAgentShell - Architecture Overview

A mobile app to remotely control your AI coding agents.

## What It Does

RemoteAgentShell lets you control AI coding agents (Claude Code, Cursor, Aider, etc.) from your phone. You can:

- View agent output in real-time
- Send messages and commands
- Approve or reject tool calls
- Monitor long-running tasks while away from your desk

## System Overview

```
┌─────────────────────────────────────────────────────────────────┐
│                          LAPTOP                                 │
│                                                                 │
│  ┌─────────────────┐      ┌─────────────────────────────────┐   │
│  │   AI Agent      │      │         RAS Daemon              │   │
│  │  (Claude Code,  │◀────▶│  - Captures agent I/O           │   │
│  │   Cursor, etc.) │      │  - Exposes P2P endpoint         │   │
│  │                 │      │  - Handles authentication       │   │
│  │   [runs in      │      │                                 │   │
│  │    tmux]        │      │                                 │   │
│  └─────────────────┘      └───────────────┬─────────────────┘   │
│                                           │                     │
└───────────────────────────────────────────┼─────────────────────┘
                                            │
                                            │ P2P (WebRTC)
                                            │
┌───────────────────────────────────────────┼─────────────────────┐
│                        MOBILE             │                     │
│                                           │                     │
│                           ┌───────────────▼─────────────────┐   │
│                           │        Android App              │   │
│                           │  - View agent output            │   │
│                           │  - Send commands                │   │
│                           │  - Approve/reject actions       │   │
│                           └─────────────────────────────────┘   │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

## Components

### 1. RAS Daemon (Laptop)

A background service that runs on your development machine.

- Manages tmux sessions running AI agents
- Captures terminal output via `tmux pipe-pane`
- Sends input via `tmux send-keys`
- Detects agent prompts and errors via pattern matching
- Exposes a P2P endpoint for mobile connection
- Handles encryption and authentication

**Status:** Implemented. See [daemon.md](daemon.md)

### 2. Android App (Mobile)

Native Android app for remote control.

- Pairs with daemon via QR code
- Connects directly via P2P (WebRTC)
- Manages sessions (create, list, rename, kill)
- Displays agent output in real-time terminal emulator
- Sends keyboard input and special keys
- Paste from clipboard to terminal
- Receives push notifications for prompts/errors/completion

**Status:** In progress. See [android-app.md](android-app.md)

### 3. Communication Layer

Direct peer-to-peer connection between phone and laptop.

- WebRTC for P2P connectivity
- STUN for NAT traversal (Google servers by default, configurable)
- ntfy for signaling relay (ntfy.sh by default, configurable)
- QR code for initial pairing
- Encrypted IP updates via ntfy when address changes
- **Protocol Buffers** for efficient message serialization (shared schema in `/proto/`)

**Status:** Designed. See [communication.md](communication.md)

### 4. Security Layer

End-to-end encryption and authentication.

- Shared secret exchanged via QR code
- Key derivation using HKDF
- AES-GCM encryption for all traffic
- Mutual authentication handshake

**Status:** Designed. See [security.md](security.md)

## Connection Flow

### First-Time Pairing (At Home)

```
1. Start daemon on laptop
   $ ras daemon start

2. Daemon displays QR code
   ┌─────────────────────────────┐
   │  Scan to pair:              │
   │  ┌───────────────────┐      │
   │  │  ▄▄▄▄▄ ▄▄▄ ▄▄▄▄▄  │      │
   │  │  █   █ ▀▄▀ █   █  │      │
   │  │  █▄▄▄█ ▀▄▀ █▄▄▄█  │      │
   │  └───────────────────┘      │
   └─────────────────────────────┘

3. Open Android app, scan QR code

4. Phone connects directly to laptop via P2P

5. Done - connection persists until you unpair
```

### Reconnection (Automatic)

```
1. Phone opens app
2. Retrieves stored connection info
3. Connects to laptop via saved address
4. If IP changed, receives update via ntfy
5. Reconnects to new address
```

### IP Change While Away

```
1. Laptop IP changes (ISP, router reboot, etc.)
2. Active connection drops
3. Daemon detects new IP
4. Daemon sends encrypted IP update via ntfy
5. Phone receives notification, decrypts new IP
6. Phone reconnects to new address
```

## Design Documents

| Document | Description |
|----------|-------------|
| [features.md](features.md) | Complete feature list (MVP and future) |
| [communication.md](communication.md) | P2P protocol, WebRTC, STUN, signaling, multi-device |
| [security.md](security.md) | Encryption, authentication, key management |
| [daemon.md](daemon.md) | Laptop daemon architecture, CLI, config |
