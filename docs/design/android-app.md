# Android App Architecture

> **Status:** Not yet designed

This document will describe the Android app for remote control.

## Planned Topics

- [ ] App architecture (MVVM, Compose, etc.)
- [ ] QR code scanning
- [ ] WebRTC client implementation
- [ ] Secure storage (Android Keystore)
- [ ] UI/UX design
- [ ] Terminal output rendering
- [ ] Input handling
- [ ] Push notifications (ntfy integration)
- [ ] Background service for persistent connection
- [ ] Offline handling and reconnection

## Open Questions

1. Jetpack Compose or traditional Views?
2. How to render terminal output (ANSI codes)?
3. Notification strategy for prompts?
4. Battery optimization considerations?

## Related Documents

- [communication.md](communication.md) - P2P protocol details
- [security.md](security.md) - Encryption and authentication
