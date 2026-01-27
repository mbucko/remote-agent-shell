# Agent Integration Protocols

> **Status:** Not yet designed

This document will describe how RemoteAgentShell integrates with various AI coding agents.

## Supported Agents (Planned)

| Agent | Status | Notes |
|-------|--------|-------|
| Claude Code | Planned | Primary target |
| Cursor | Planned | |
| Aider | Planned | |
| Cline | Planned | |
| Open Code | Planned | |
| GitHub Copilot CLI | TBD | |
| Continue | TBD | |

## Planned Topics

- [ ] Detecting agent type from output patterns
- [ ] Parsing permission prompts per agent
- [ ] Structured vs unstructured output
- [ ] Agent-specific command handling
- [ ] Tool call detection and display

## Open Questions

1. Do agents have APIs/hooks we can use instead of screen scraping?
2. How to handle agents with different prompt formats?
3. Can we get structured data from any agents?
4. How to detect "waiting for input" state reliably?

## Agent-Specific Notes

### Claude Code

TBD - Research Claude Code's output format and any available hooks.

### Cursor

TBD - Research Cursor's architecture.

### Aider

TBD - Research Aider's output format.

## Related Documents

- [tmux-integration.md](tmux-integration.md) - tmux session management
- [daemon.md](daemon.md) - Daemon architecture
