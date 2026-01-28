# Features

This document lists all planned features for RemoteAgentShell.

## MVP (v0.1)

Core features needed for first usable release.

### Connection
- [x] QR code pairing
- [x] Auto-reconnect on connection loss
- [x] Connection status indicator
- [x] IP change handling via ntfy

### Session Management
- [x] List running sessions
- [x] Select session to view/control
- [x] Start new session
- [x] Stop/kill session
- [x] Rename session

### New Session Creation

**Directory Selection:**
- [x] Default directory configurable in settings (e.g., `~/repos`)
- [x] Directory browser rooted at default directory
- [x] Navigate into subdirectories
- [x] Navigate to parent (within allowed root)
- [x] Remember last used directory
- [x] Whitelist/blacklist directory paths

**Agent Selection:**
- [x] Auto-detect installed agents on daemon startup
- [x] Show only installed agents as options
- [x] Support: Claude Code, Open Code, Aider, Cursor, Cline
- [ ] Default agent configurable in settings

**Agent Detection (Daemon):**
```bash
# Daemon runs on startup:
which claude    → Claude Code
which opencode  → Open Code
which aider     → Aider
which cursor    → Cursor
which cline     → Cline
```

**New Session Payload:**
```json
{
  "directory": "/home/user/repos/my-project",
  "agent": "claude",
  "custom_command": null
}
```

Or for custom command:
```json
{
  "directory": "/home/user/repos/my-project",
  "agent": null,
  "custom_command": "python main.py --serve"
}
```

### Terminal
- [x] Real-time output streaming (via tmux pipe-pane)
- [x] Send text input (line-buffered mode)
- [x] Send raw input (raw mode toggle)
- [x] Approve/reject prompts (quick action buttons)
- [x] Cancel running operation (Ctrl+C)
- [x] Scrollback buffer (10,000 lines)
- [x] Reconnection buffer (100KB on daemon)

### Shortcut Keys
- [x] Escape key
- [x] Ctrl+C (cancel/interrupt)
- [x] Ctrl+D (EOF)
- [x] Ctrl+Z (suspend)
- [x] Ctrl+B (tmux prefix)
- [x] Ctrl+L (clear screen)
- [x] Arrow keys (up/down/left/right)
- [x] Paste from clipboard (64KB limit)

### Notifications
- [x] Push notification: agent needs approval (pattern detection)
- [x] Push notification: task completed (shell prompt detection)
- [x] Push notification: error/failure (error pattern detection)
- [x] Notification tap → opens relevant session (deep linking)
- [x] Delivered via WebRTC (instant when connected)
- [x] Rate limiting (5s cooldown, deduplication)

### UI
- [x] Dark theme (only, no light theme)
- [x] Scrollable output history (10,000 lines)
- [x] Quick action bar (Y/N/Ctrl+C, customizable)
- [x] Connection status indicator
- [x] Raw mode toggle button
- [x] Paste button in input bar
- [x] Terminal emulator (Termux library)

### Settings
- [x] Default directory (for new sessions) - daemon config
- [ ] Default agent (for new sessions)
- [x] View installed agents (with paths)
- [x] Refresh agent detection
- [x] ntfy server URL (default: ntfy.sh, or self-hosted)
- [ ] Notification preferences (Android)
- [x] Directory whitelist/blacklist (daemon config)
- [x] Shell prompt pattern (daemon config)

---

## Post-MVP (v0.x)

Features for subsequent releases.

### Session Management (Enhanced)
- [ ] Session naming/renaming
- [ ] Session history (view completed sessions)
- [ ] Pinned sessions (favorites)
- [ ] Session groups/folders
- [ ] Restart stopped session
- [ ] Duplicate session
- [ ] Session templates (saved configurations)

### Multi-Session
- [ ] View multiple sessions simultaneously (split view)
- [ ] Quick switch between sessions
- [ ] Session tabs

### Multi-Device
- [ ] See other connected devices
- [ ] Input attribution (who sent what)
- [ ] Device management from app
- [ ] Optional: device locking (one device takes control)

### Search & History
- [ ] Search within session output
- [ ] Search across all sessions
- [ ] Command history
- [ ] Saved commands/snippets

### Voice
- [ ] Voice input (speech-to-text)
- [ ] Voice commands ("approve", "reject", "cancel")
- [ ] Optional: TTS for notifications

### UI (Enhanced)
- [ ] Custom shortcut configuration (user-defined quick action buttons)
- [ ] Customizable quick actions
- [ ] Font size adjustment
- [ ] Gesture support (swipe to switch sessions)
- [ ] Landscape mode optimization
- [ ] Tablet layout

### Advanced
- [ ] Watch mode (read-only, battery saver)
- [ ] Bandwidth optimization for mobile data
- [ ] Offline queue (queue commands while disconnected)
- [ ] Session sharing (share view with another user)
- [ ] Annotations (mark important output)

### Daemon Features
- [x] Prompt detection - Detect when AI agent is waiting for approval/input (pattern matching) - Moved to MVP
- [ ] Custom command option - Run any command, not just AI agents (e.g., `npm run dev`)
- [ ] Image/clipboard handling - Send images from phone to agent via system clipboard
- [ ] Local echo - Show typed characters immediately for low-latency feel (like Mosh)
- [x] Daemon-managed sessions - Daemon creates tmux sessions and starts agents - Moved to MVP

---

## Future / Maybe

Features to consider later.

### Platforms
- [ ] iOS app
- [ ] Web client
- [ ] Desktop client (for consistency)
- [ ] Wear OS (quick approve/reject from watch)

### Integrations
- [ ] GitHub integration (see PR status)
- [ ] CI/CD status
- [ ] IDE integration (VS Code extension)

### Agent-Specific
- [ ] Structured tool call display (not just text)
- [ ] File diff viewer
- [ ] Code syntax highlighting
- [ ] Agent-specific UI (Claude Code tools, Cursor composer, etc.)

### Collaboration
- [ ] Multiple users on same session
- [ ] Chat between connected users
- [ ] Access control (admin, viewer roles)

---

## Non-Features

Things we explicitly won't do.

| Feature | Reason |
|---------|--------|
| Light theme | Dark only, consistent with dev tools |
| Relay server | P2P only, no infrastructure dependency |
| Account system | No sign-up, no cloud, local pairing only |
| Telemetry | No tracking, fully private |
| Ads | Open source, no monetization |
