# Features

This document lists all planned features for RemoteAgentShell.

## MVP (v1.0)

Core features needed for first usable release.

### Connection
- [ ] QR code pairing
- [ ] Auto-reconnect on connection loss
- [ ] Connection status indicator
- [ ] IP change handling via ntfy

### Session Management
- [ ] List running sessions
- [ ] Select session to view/control
- [ ] Start new session
- [ ] Stop/kill session

### New Session Creation

**Directory Selection:**
- [ ] Default directory configurable in settings (e.g., `~/repos`)
- [ ] Directory browser rooted at default directory
- [ ] Navigate into subdirectories
- [ ] Navigate to parent (within allowed root)
- [ ] Remember last used directory

**Agent Selection:**
- [ ] Auto-detect installed agents on daemon startup
- [ ] Show only installed agents as options
- [ ] Support: Claude Code, Open Code, Aider, Cursor, Cline
- [ ] Default agent configurable in settings
- [ ] Custom command option (any command, not just AI agents)

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
- [ ] Real-time output streaming
- [ ] Send text input
- [ ] Approve/reject prompts (quick action buttons)
- [ ] Cancel running operation (Ctrl+C)

### Shortcut Keys
- [ ] Escape key
- [ ] Ctrl+C (cancel/interrupt)
- [ ] Ctrl+D (EOF)
- [ ] Ctrl+Z (suspend)
- [ ] Ctrl+B (tmux prefix)
- [ ] Ctrl+L (clear screen)
- [ ] Arrow keys (up/down/left/right)
- [ ] Paste from clipboard
- [ ] Custom shortcut configuration

### Notifications
- [ ] Push notification: agent needs approval
- [ ] Push notification: task completed
- [ ] Push notification: error/failure
- [ ] Notification tap → opens relevant session

### UI
- [ ] Dark theme (only, no light theme)
- [ ] Scrollable output history
- [ ] Quick action bar (approve/reject/cancel)
- [ ] Connection status indicator

### Settings
- [ ] Default directory (for new sessions)
- [ ] Default agent (for new sessions)
- [ ] View installed agents (with paths)
- [ ] Refresh agent detection
- [ ] ntfy server URL (default: ntfy.sh, or self-hosted)
- [ ] Notification preferences

---

## Post-MVP (v1.x)

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
