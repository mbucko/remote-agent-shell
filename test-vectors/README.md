# Test Vectors for RemoteAgentShell Pairing Protocol

This directory contains pre-computed test vectors for validating cryptographic implementations across platforms (Python daemon, Android, iOS).

## Purpose

Both the daemon and mobile apps must produce **identical outputs** for the same inputs. These test vectors ensure:

1. **Key derivation** produces the same keys from the same master secret
2. **HMAC computation** produces the same signatures
3. **Signaling authentication** is correctly verified
4. **Auth handshake** follows the expected protocol

## Files

| File | Description |
|------|-------------|
| `key_derivation.json` | HKDF-SHA256 key derivation vectors |
| `hmac.json` | HMAC-SHA256 computation vectors |
| `signaling.json` | HTTP signaling HMAC vectors |
| `qr_payload.json` | QR code payload validation vectors |
| `auth_handshake.json` | WebRTC auth handshake vectors |

## Algorithms

### HKDF (Key Derivation)

```
Algorithm: HKDF-SHA256 (RFC 5869)
Salt: empty (None)
Info: UTF-8 encoded purpose string ("auth", "encrypt", "ntfy")
Output length: 32 bytes
```

### ntfy Topic Derivation

```
topic = "ras-" + hex(SHA256(master_secret))[0:12]
```

### Signaling HMAC

```
HMAC_input = UTF8(session_id) || BigEndian64(timestamp) || body_bytes
signature = HMAC-SHA256(auth_key, HMAC_input)
```

### Auth Handshake HMAC

```
Client response: HMAC-SHA256(auth_key, server_nonce)
Server verify: HMAC-SHA256(auth_key, client_nonce)
```

## Usage

### Python

```python
import json
from pathlib import Path

vectors = json.loads(Path("test-vectors/key_derivation.json").read_text())

for v in vectors["vectors"]:
    master_secret = bytes.fromhex(v["master_secret_hex"])
    expected = bytes.fromhex(v["expected_key_hex"])
    actual = hkdf_derive(master_secret, v["purpose"].encode())
    assert actual == expected, f"Failed: {v['id']}"
```

### Kotlin

```kotlin
val vectors = Json.decodeFromString<KeyDerivationVectors>(
    File("test-vectors/key_derivation.json").readText()
)

vectors.vectors.forEach { v ->
    val masterSecret = v.masterSecretHex.hexToByteArray()
    val expected = v.expectedKeyHex.hexToByteArray()
    val actual = hkdfDerive(masterSecret, v.purpose.toByteArray())
    assertEquals(expected, actual, "Failed: ${v.id}")
}
```

## Regenerating Vectors

If the protocol changes, regenerate vectors:

```bash
cd remote-agent-shell
python3 scripts/compute_test_vectors.py
```

## Security Notes

1. **Test vectors use deterministic values** - Real implementations must use CSPRNG
2. **Master secrets in tests are NOT secure** - Never use test values in production
3. **Verify constant-time comparison** - HMAC comparison must be constant-time

## Vector Format

All hex values are lowercase. All byte arrays are represented as hex strings without `0x` prefix.

Example:
```json
{
  "master_secret_hex": "0123456789abcdef...",
  "expected_key_hex": "bec0c3289e346d89..."
}
```
