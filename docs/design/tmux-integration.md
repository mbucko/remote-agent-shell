# tmux Integration

> **Status:** Not yet designed

This document will describe how the daemon integrates with tmux to capture and control AI agent sessions.

## Planned Topics

- [ ] tmux session management
- [ ] Capturing output (`tmux capture-pane`)
- [ ] Sending input (`tmux send-keys`)
- [ ] Real-time output streaming (`tmux pipe-pane`)
- [ ] Detecting prompts and permission requests
- [ ] Handling ANSI escape codes
- [ ] Multiple pane support
- [ ] Session persistence and recovery

## Key tmux Commands

```bash
# Create a new session
tmux new-session -d -s ras-claude "claude"

# Capture current pane content
tmux capture-pane -t ras-claude -p

# Send keys to session
tmux send-keys -t ras-claude "hello" Enter

# Stream output in real-time
tmux pipe-pane -t ras-claude "cat >> /tmp/output.log"

# List sessions
tmux list-sessions
```

## Open Questions

1. How to detect when agent is waiting for input?
2. How to handle scrollback buffer size?
3. Real-time streaming vs polling?
4. How to parse permission prompts from different agents?

## Related Documents

- [daemon.md](daemon.md) - Daemon architecture
- [agent-protocols.md](agent-protocols.md) - Agent-specific integrations
