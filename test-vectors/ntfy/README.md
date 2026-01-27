# ntfy Test Vectors

Cross-platform test vectors for ntfy IP change notifications.

## Files

- `topic_derivation.json` - Topic name derivation from master secret
- `key_derivation.json` - HKDF key derivation for ntfy encryption key
- `encryption.json` - AES-256-GCM encryption/decryption test vectors

## Usage

Both daemon (Python) and Android (Kotlin) implementations MUST pass all test vectors.

### Topic Derivation

```python
topic = "ras-" + sha256(master_secret)[:6].hex()
```

### Key Derivation

```python
ntfy_key = HKDF(
    algorithm=SHA256,
    length=32,
    salt=None,  # empty
    info=b"ntfy"
).derive(master_secret)
```

### Encryption Format

```
Encrypted = IV (12 bytes) || Ciphertext || Tag (16 bytes)
Message = base64(Encrypted)
```

### Protobuf Schema

```protobuf
message IpChangeNotification {
    string ip = 1;
    uint32 port = 2;
    int64 timestamp = 3;
    bytes nonce = 4;
}
```

## Validation Rules

- Minimum encrypted message size: 28 bytes (12 IV + 16 tag)
- Timestamp must be within 300 seconds (5 minutes) of current time
- Nonce must be exactly 16 bytes
- Nonce cache holds last 100 nonces (reject duplicates)
