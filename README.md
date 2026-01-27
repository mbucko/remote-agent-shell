# RemoteAgentShell

A mobile app to remotely control your AI coding agents (Claude Code, Cursor, Cline, Aider, etc.) from your phone. Monitor agent output, send commands, and approve/reject tool calls remotely.

## Project Structure

```
remote-agent-shell/
├── daemon/              # Python daemon (runs on dev machine)
│   ├── src/ras/         # Source code
│   │   ├── pairing/     # QR code pairing module
│   │   ├── proto/       # Generated protobuf code
│   │   └── ...
│   └── tests/           # Test suite
├── android/             # Android app (Kotlin)
├── proto/               # Protocol buffer definitions
├── test-vectors/        # Cross-platform test vectors
└── docs/                # Documentation
```

## Quick Start

### Prerequisites

- Python 3.11+
- pip

### Installation

```bash
# Install daemon with dev dependencies
make install-dev

# Or manually:
cd daemon && pip install -e ".[dev]"
```

### Running Tests

```bash
# Run all tests
make test

# Run with coverage
make test-cov

# Run specific test suites
make test-pairing    # Pairing module tests
make test-crypto     # Crypto tests
make test-e2e        # End-to-end tests
make test-vectors    # Test vector validation
```

### Development Commands

Run `make help` to see all available commands:

```
Setup:
  make install        Install daemon dependencies
  make install-dev    Install daemon with dev dependencies

Testing:
  make test           Run all daemon tests
  make test-quick     Run tests without slow integration tests
  make test-pairing   Run pairing module tests only
  make test-crypto    Run crypto tests only
  make test-cov       Run tests with coverage report
  make test-verbose   Run tests with verbose output

Code Quality:
  make lint           Run linter (ruff)
  make format         Format code (ruff)
  make typecheck      Run type checker (mypy)

Proto:
  make proto          Generate Python code from proto files
  make proto-clean    Remove generated proto files

Build:
  make build          Build daemon package
  make clean          Remove build artifacts
```

## Pairing

The daemon and mobile app use QR code pairing with mutual authentication:

```bash
# Generate pairing QR code (terminal)
ras pair

# Open QR code in browser
ras pair --browser

# Save QR code to file
ras pair --output qr.png
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
- **WebRTC**: Peer connection management via aiortc
- **Crypto**: HKDF key derivation, HMAC utilities
- **CLI**: Click-based command interface

### Mobile App (Kotlin)

- QR code scanning
- WebRTC client
- Real-time agent monitoring
- Command sending

## Protocol Buffers

Protocol definitions are in `proto/`:

- `qr_payload.proto` - QR code data structure
- `signaling.proto` - HTTP signaling messages
- `auth.proto` - Authentication handshake
- `ntfy.proto` - IP change notifications

Regenerate Python code after changing protos:

```bash
make proto
```

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
