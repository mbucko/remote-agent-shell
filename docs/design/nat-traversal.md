# NAT Traversal Design

This document describes how RemoteAgentShell establishes P2P connections across NAT boundaries.

## Overview

NAT (Network Address Translation) is the main obstacle to peer-to-peer connections. Both the phone and daemon are typically behind NAT routers that block incoming connections.

We solve this using:
1. **ntfy** - Signaling relay for SDP exchange
2. **STUN** - Public IP discovery for ICE candidates
3. **WebRTC ICE** - NAT hole-punching for P2P data channel

```
┌─────────────────────────────────────────────────────────────────────┐
│                         CONNECTION FLOW                              │
├─────────────────────────────────────────────────────────────────────┤
│                                                                     │
│  Phone                    ntfy.sh                    Daemon         │
│    │                         │                         │            │
│    │  1. STUN query          │                         │            │
│    │────────────────────────────────────────────────▶ STUN          │
│    │◀──── public IP:port ────────────────────────────               │
│    │                         │                         │            │
│    │                         │   2. STUN query         │            │
│    │          STUN ◀─────────────────────────────────────           │
│    │               ─────────────────────────────────▶│              │
│    │                         │     public IP:port     │             │
│    │                         │                         │            │
│    │  3. SDP offer           │                         │            │
│    │    (with ICE candidates)│                         │            │
│    │─────────────────────────▶                         │            │
│    │                         │─────────────────────────▶            │
│    │                         │                         │            │
│    │                         │  4. SDP answer          │            │
│    │                         │◀─────────────────────────            │
│    │◀─────────────────────────                         │            │
│    │                         │                         │            │
│    │  5. ICE hole-punching (direct P2P)                │            │
│    │◀══════════════════════════════════════════════════▶            │
│    │                         │                         │            │
│    │  6. DTLS + DataChannel (encrypted P2P)            │            │
│    │◀══════════════════════════════════════════════════▶            │
│                                                                     │
└─────────────────────────────────────────────────────────────────────┘
```

## Components

### STUN (Session Traversal Utilities for NAT)

**Purpose:** Discover public IP address and port.

When you're behind NAT, you only know your local IP (192.168.x.x). STUN servers tell you what IP:port the outside world sees.

```
Phone (192.168.1.5:12345)
    │
    NAT router assigns external port
    │
    ▼
Public IP (203.0.113.45:54321)
    │
    │  "What's my public IP?"
    ▼
STUN Server
    │
    │  "You're 203.0.113.45:54321"
    ▼
Phone now knows its public address
```

**Servers used (free, no account required):**
- `stun:stun.l.google.com:19302`
- `stun:stun.cloudflare.com:3478`

**Implementation:**
- Daemon: `daemon/src/ras/stun.py` - StunClient class
- Android: Built into WebRTC library

### ntfy (Signaling Relay)

**Purpose:** Exchange SDP offers/answers when peers can't reach each other directly.

The phone can't send its SDP offer directly to the daemon (daemon is behind NAT, unreachable). ntfy.sh acts as a public mailbox both can access.

```
Phone                          ntfy.sh                         Daemon
  │                               │                               │
  │  POST encrypted SDP offer     │                               │
  │──────────────────────────────▶│                               │
  │                               │   SSE/polling receives it     │
  │                               │──────────────────────────────▶│
  │                               │                               │
  │                               │   POST encrypted SDP answer   │
  │                               │◀──────────────────────────────│
  │   SSE/polling receives it     │                               │
  │◀──────────────────────────────│                               │
```

**Security:**
- SDP messages encrypted with AES-256-GCM
- Key derived from shared master secret
- Timestamp + nonce prevent replay attacks

**Implementation:**
- Daemon: `daemon/src/ras/ntfy_signaling/`
- Proto: `proto/ntfy_signaling.proto`

### ICE (Interactive Connectivity Establishment)

**Purpose:** Find a working path between peers and establish connection.

ICE tries multiple candidate pairs to find one that works:

| Candidate Type | Description | Example |
|----------------|-------------|---------|
| `host` | Local network IP | 192.168.1.5:12345 |
| `srflx` | Server-reflexive (from STUN) | 203.0.113.45:54321 |
| `relay` | TURN relay (not used) | - |

**Hole-punching:**
1. Both peers send packets to each other's public IP:port
2. NAT routers see outgoing traffic and create mappings
3. When reply comes back, NAT allows it through (matches existing mapping)
4. Direct P2P connection established

## Network Scenarios

### Scenario 1: Same Network

Both devices on same LAN (e.g., same WiFi).

```
Phone (192.168.1.5) ◀────── LAN ──────▶ Daemon (192.168.1.10)
```

- **Signaling:** Can use direct HTTP or ntfy
- **Connection:** Uses `host` candidates (local IPs)
- **Result:** Always works

### Scenario 2: Different Networks, Friendly NAT

Phone on mobile data, daemon at home. Both behind "cone" NAT.

```
Phone                                              Daemon
  │                                                  │
  NAT (cone)                                    NAT (cone)
  │                                                  │
  └──────────────── INTERNET ────────────────────────┘
```

- **Signaling:** ntfy (phone can't reach daemon directly)
- **Connection:** Uses `srflx` candidates, hole-punching works
- **Result:** Works (~80-85% of cases)

### Scenario 3: Different Networks, Symmetric NAT

Phone on corporate WiFi or strict carrier, daemon behind symmetric NAT.

```
Phone                                              Daemon
  │                                                  │
  NAT (symmetric)                            NAT (symmetric)
  │                                                  │
  └──────────────── INTERNET ────────────────────────┘
```

- **Signaling:** ntfy works fine
- **Connection:** Hole-punching FAILS
  - Symmetric NAT assigns different external ports per destination
  - Port discovered via STUN ≠ port seen by peer
- **Result:** Connection fails (~15-20% of cases)

## Configuration

### Daemon

```yaml
# ~/.config/ras/config.yaml
stun_servers:
  - "stun:stun.l.google.com:19302"
  - "stun:stun.cloudflare.com:3478"

ntfy:
  server: "https://ntfy.sh"
  enabled: true
```

### Android

STUN servers configured in `WebRTCClient.kt`:
```kotlin
private val iceServers = listOf(
    PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()
)
```

## Limitations

### Symmetric NAT (No Solution Yet)

**Problem:** Symmetric NAT assigns a different external port for each destination.

```
Normal (Cone) NAT:
  Phone:5000 → ANY destination → 203.0.113.45:54321 (same port always)

Symmetric NAT:
  Phone:5000 → STUN server  → 203.0.113.45:54321
  Phone:5000 → Daemon       → 203.0.113.45:61234  ← DIFFERENT PORT!
```

When we ask STUN "what's my public port?", it says 54321. But when we try to connect to the daemon, NAT assigns a completely different port (61234). The daemon tries to reach us on 54321, which doesn't exist → connection fails.

**Where symmetric NAT is common:**
- Corporate firewalls
- Some mobile carriers (LTE/5G)
- University networks
- Enterprise routers

**Current status:** No solution implemented. ~15-20% of connections will fail when both peers are behind symmetric NAT.

**Workarounds for users:**
- Try a different network (home WiFi vs mobile data)
- Use VPN (may provide cone NAT)
- If daemon has public IP, connection will work

### No TURN Support

TURN (Traversal Using Relays around NAT) would relay traffic when P2P fails, achieving 100% connectivity. We explicitly don't use TURN because:

1. **Cost:** TURN relays all traffic, requiring paid infrastructure
2. **Latency:** Adds relay hop
3. **Privacy:** Traffic passes through third party
4. **Accounts:** Most TURN services require signup

**Trade-off accepted:** ~15-20% of connections may fail on strict NAT. Users on corporate/restrictive networks may need to use a different network.

### Future Investigation: TURN

If the ~15-20% failure rate becomes problematic, consider:

1. **Self-hosted coturn:** Open-source TURN server
   - Pros: Full control, no per-GB costs
   - Cons: Requires VPS, maintenance

2. **Cloudflare Calls:** Has free tier
   - Pros: Easy setup, global network
   - Cons: Requires account, bandwidth limits

3. **Embedded TURN in daemon:** If daemon has public IP
   - Pros: No third party
   - Cons: Only helps if daemon is publicly reachable

4. **Fallback to ntfy relay:** Use ntfy for data when P2P fails
   - Pros: No new infrastructure
   - Cons: Higher latency, depends on ntfy.sh availability

## Implementation Files

| Component | Daemon | Android |
|-----------|--------|---------|
| STUN client | `src/ras/stun.py` | Built into WebRTC |
| Peer connection | `src/ras/peer.py` | `data/webrtc/WebRTCClient.kt` |
| SDP validation | `src/ras/sdp_validator.py` | `data/webrtc/SdpValidator.kt` |
| ntfy signaling | `src/ras/ntfy_signaling/` | `data/pairing/` |
| Config | `src/ras/config.py` | `WebRTCClient.kt` (hardcoded) |

## Debugging

### Check ICE candidates in SDP

Look for `a=candidate:` lines:
```
a=candidate:1 1 UDP 2122252543 192.168.1.5 54321 typ host
a=candidate:2 1 UDP 1686052607 203.0.113.45 12345 typ srflx
```

- `typ host` = local IP (works on same network)
- `typ srflx` = public IP from STUN (needed for cross-NAT)
- `typ relay` = TURN relay (not used)

### Common issues

| Symptom | Likely Cause | Fix |
|---------|--------------|-----|
| No `srflx` candidates | STUN server unreachable | Check internet, firewall |
| Connection timeout | Symmetric NAT | Try different network |
| Works locally, fails remotely | Missing ntfy signaling | Check ntfy subscription |
