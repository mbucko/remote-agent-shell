# Security Protocol

This document describes the security model for RemoteAgentShell.

## Overview

RemoteAgentShell uses end-to-end encryption for all communication between the phone and laptop. The security model is based on a shared secret exchanged via QR code during initial pairing.

```
┌─────────────────────────────────────────────────────────────────┐
│                     SECURITY LAYERS                             │
│                                                                 │
│  ┌───────────────────────────────────────────────────────────┐  │
│  │  Layer 3: Application Security                            │  │
│  │  • Message authentication                                 │  │
│  │  • Replay protection                                      │  │
│  └───────────────────────────────────────────────────────────┘  │
│  ┌───────────────────────────────────────────────────────────┐  │
│  │  Layer 2: Transport Security                              │  │
│  │  • AES-GCM encryption                                     │  │
│  │  • Mutual authentication                                  │  │
│  └───────────────────────────────────────────────────────────┘  │
│  ┌───────────────────────────────────────────────────────────┐  │
│  │  Layer 1: Key Exchange                                    │  │
│  │  • QR code (physical, out-of-band)                        │  │
│  │  • 256-bit master secret                                  │  │
│  └───────────────────────────────────────────────────────────┘  │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

---

## Threat Model

### In Scope (Protected Against)

| Threat | Protection |
|--------|------------|
| Network eavesdropping | All traffic encrypted (AES-GCM) |
| Man-in-the-middle | QR code exchange is physical, mutual auth |
| Replay attacks | Timestamps and nonces on all messages |
| Unauthorized access | Must have shared secret to connect |
| ntfy server snooping | IP updates are encrypted end-to-end |
| Device impersonation | Mutual authentication handshake |

### Out of Scope (Not Protected Against)

| Threat | Reason |
|--------|--------|
| Compromised laptop | If attacker has laptop access, game over |
| Compromised phone | If attacker has phone access, game over |
| QR code photographed | Physical security is user's responsibility |
| Rubber hose cryptanalysis | Out of scope for software |

---

## Key Management

### Master Secret Generation

Generated on the laptop daemon when pairing is initiated.

```python
import secrets

master_secret = secrets.token_bytes(32)  # 256 bits
```

**Requirements:**
- Must use cryptographically secure random number generator
- Never use `random.random()` or similar weak RNGs
- Generate fresh secret for each pairing

### Key Derivation

Multiple keys are derived from the master secret using HKDF (HMAC-based Key Derivation Function).

```
master_secret (32 bytes)
       │
       ├── HKDF(secret, "auth")     ──▶ auth_key (32 bytes)
       │                                 Used for: Authentication handshake
       │
       ├── HKDF(secret, "encrypt")  ──▶ encrypt_key (32 bytes)
       │                                 Used for: P2P message encryption
       │
       ├── HKDF(secret, "ntfy")     ──▶ ntfy_key (32 bytes)
       │                                 Used for: IP update encryption
       │
       └── SHA256(secret)[:12]      ──▶ ntfy_topic (12 chars)
                                         Used for: ntfy topic name
```

**Why derive separate keys?**
- Compromise of one key doesn't affect others
- Different keys for different purposes (defense in depth)
- Standard cryptographic practice

### Key Derivation Implementation

```python
import hashlib
import hmac

def derive_key(master_secret: bytes, purpose: str) -> bytes:
    """Derive a purpose-specific key using HKDF-like construction."""
    return hmac.new(
        key=master_secret,
        msg=purpose.encode('utf-8'),
        digestmod=hashlib.sha256
    ).digest()

def derive_topic(master_secret: bytes) -> str:
    """Derive ntfy topic from master secret."""
    hash_bytes = hashlib.sha256(master_secret).digest()
    return hash_bytes[:6].hex()  # 12 hex characters
```

```kotlin
// Android
fun deriveKey(masterSecret: ByteArray, purpose: String): ByteArray {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(masterSecret, "HmacSHA256"))
    return mac.doFinal(purpose.toByteArray(Charsets.UTF_8))
}

fun deriveTopic(masterSecret: ByteArray): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val hash = digest.digest(masterSecret)
    return hash.take(6).joinToString("") { "%02x".format(it) }
}
```

---

## Encryption

### Algorithm: AES-256-GCM

All encrypted data uses AES-256-GCM (Galois/Counter Mode).

| Property | Value |
|----------|-------|
| Algorithm | AES-256 |
| Mode | GCM (authenticated encryption) |
| Key size | 256 bits (32 bytes) |
| Nonce size | 96 bits (12 bytes) |
| Tag size | 128 bits (16 bytes) |

**Why AES-GCM?**
- Authenticated encryption (confidentiality + integrity)
- Fast, hardware-accelerated on most devices
- Standard, well-analyzed, no known weaknesses
- Built into Android and Python

### Encryption Format

```
┌─────────────────────────────────────────────────────────────┐
│  Nonce (12 bytes)  │  Ciphertext (variable)  │  Tag (16 bytes)  │
└─────────────────────────────────────────────────────────────┘
```

### Implementation

```python
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
import os

def encrypt(key: bytes, plaintext: bytes) -> bytes:
    """Encrypt data using AES-256-GCM."""
    nonce = os.urandom(12)
    aesgcm = AESGCM(key)
    ciphertext = aesgcm.encrypt(nonce, plaintext, None)
    return nonce + ciphertext  # nonce || ciphertext || tag

def decrypt(key: bytes, data: bytes) -> bytes:
    """Decrypt data using AES-256-GCM."""
    nonce = data[:12]
    ciphertext = data[12:]
    aesgcm = AESGCM(key)
    return aesgcm.decrypt(nonce, ciphertext, None)
```

```kotlin
// Android
fun encrypt(key: ByteArray, plaintext: ByteArray): ByteArray {
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    val keySpec = SecretKeySpec(key, "AES")
    cipher.init(Cipher.ENCRYPT_MODE, keySpec)
    val nonce = cipher.iv  // GCM generates random IV
    val ciphertext = cipher.doFinal(plaintext)
    return nonce + ciphertext
}

fun decrypt(key: ByteArray, data: ByteArray): ByteArray {
    val nonce = data.sliceArray(0 until 12)
    val ciphertext = data.sliceArray(12 until data.size)
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    val keySpec = SecretKeySpec(key, "AES")
    val gcmSpec = GCMParameterSpec(128, nonce)
    cipher.init(Cipher.DECRYPT_MODE, keySpec, gcmSpec)
    return cipher.doFinal(ciphertext)
}
```

---

## Authentication

### Mutual Authentication Handshake

After P2P connection is established, both sides prove they know the shared secret.

```
┌──────────┐                                          ┌──────────┐
│  Phone   │                                          │  Laptop  │
└────┬─────┘                                          └────┬─────┘
     │                                                     │
     │              1. challenge                           │
     │◀────────────────────────────────────────────────────│
     │    nonce_l = random(32)                             │
     │                                                     │
     │              2. response + challenge                │
     │─────────────────────────────────────────────────────▶
     │    hmac_p = HMAC(auth_key, nonce_l)                 │
     │    nonce_p = random(32)                             │
     │                                                     │
     │              3. verify + response                   │
     │◀────────────────────────────────────────────────────│
     │    verify hmac_p                                    │
     │    hmac_l = HMAC(auth_key, nonce_p)                 │
     │                                                     │
     │              4. verify                              │
     │    verify hmac_l                                    │
     │                                                     │
     │         ═══ AUTHENTICATED ═══                       │
     │                                                     │
```

### Handshake Messages

**1. Laptop → Phone: Challenge**
```json
{
  "type": "auth_challenge",
  "nonce": "base64-encoded-32-bytes"
}
```

**2. Phone → Laptop: Response + Challenge**
```json
{
  "type": "auth_response",
  "hmac": "base64-encoded-32-bytes",
  "nonce": "base64-encoded-32-bytes"
}
```

**3. Laptop → Phone: Verify + Response**
```json
{
  "type": "auth_verify",
  "hmac": "base64-encoded-32-bytes"
}
```

### HMAC Calculation

```python
import hmac
import hashlib

def calculate_hmac(auth_key: bytes, nonce: bytes) -> bytes:
    return hmac.new(
        key=auth_key,
        msg=nonce,
        digestmod=hashlib.sha256
    ).digest()
```

### Rate Limiting

To prevent brute force attacks:
- Track failed authentication attempts
- Exponential backoff after failures (1s, 2s, 4s, 8s, 16s)
- Lock out after 5 consecutive failures
- Reset counter on successful authentication

---

## Replay Protection

### P2P Messages

All P2P messages include a timestamp and sequence number:

```json
{
  "type": "...",
  "seq": 12345,
  "timestamp": 1706384400,
  "payload": { ... }
}
```

**Validation rules (IPsec/DTLS sliding window pattern):**
- Timestamp must be within 60 seconds of current time
- Sequence number must be within the replay window (reject if below floor)
- Duplicate sequence numbers rejected
- Track highest seen seq to establish window floor

### ntfy Messages

IP update messages include timestamp and nonce:

```json
{
  "ip": "99.88.77.66",
  "port": 8821,
  "timestamp": 1706384400,
  "nonce": "random-16-bytes"
}
```

**Validation rules:**
- Timestamp must be within 5 minutes of current time
- Nonce must not have been seen before (keep recent nonce cache)
- Reject messages with old timestamps

---

## Secure Storage

### Android

Secrets are stored using Android Keystore:

```kotlin
// Store master secret in Android Keystore
fun storeSecret(alias: String, secret: ByteArray) {
    val keyStore = KeyStore.getInstance("AndroidKeyStore")
    keyStore.load(null)

    // Generate a key for encrypting the secret
    val keyGenerator = KeyGenerator.getInstance(
        KeyProperties.KEY_ALGORITHM_AES,
        "AndroidKeyStore"
    )
    keyGenerator.init(
        KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
        .build()
    )

    // Encrypt and store
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.ENCRYPT_MODE, keyGenerator.generateKey())
    val encrypted = cipher.doFinal(secret)

    // Store encrypted secret + IV in SharedPreferences
    // (actual secret is protected by Keystore)
}
```

**Properties:**
- Secret never leaves secure hardware (on supported devices)
- Tied to device, cannot be extracted
- Automatically deleted on app uninstall

### Laptop

Secrets stored in user's home directory with restricted permissions:

```python
import os
import stat

SECRET_FILE = os.path.expanduser("~/.config/ras/secret")

def store_secret(secret: bytes):
    os.makedirs(os.path.dirname(SECRET_FILE), exist_ok=True)

    with open(SECRET_FILE, 'wb') as f:
        f.write(secret)

    # Restrict permissions to owner only (600)
    os.chmod(SECRET_FILE, stat.S_IRUSR | stat.S_IWUSR)
```

**On Linux:** Consider using libsecret/GNOME Keyring
**On macOS:** Consider using Keychain

---

## Revocation

### Unpairing a Device

To revoke a phone's access:

1. On laptop, run: `ras unpair`
2. Daemon generates new master secret
3. Old phone can no longer connect (wrong secret)
4. Re-scan QR code to pair again

### Lost Phone

If phone is lost or stolen:

1. On laptop, run: `ras unpair`
2. Previous secret is invalidated
3. Thief cannot connect (even with stored secret)
4. Pair a new phone when ready

---

## Security Checklist

### Implementation Checklist

- [ ] Use `secrets` / `SecureRandom` for random bytes
- [ ] Use HKDF for key derivation
- [ ] Use AES-256-GCM for encryption
- [ ] Include nonce with every encrypted message
- [ ] Validate timestamps on all messages
- [ ] Store secrets in Keystore / secure storage
- [ ] Clear secrets from memory after use
- [ ] Restrict file permissions on laptop

### Deployment Checklist

- [ ] QR code display only on demand (not persistent)
- [ ] Secret regenerated on unpair
- [ ] No logging of secrets or keys
- [ ] Secure connection indicator in UI
- [ ] Warn user if connection is not authenticated

---

## Cryptographic Agility

For future algorithm changes:

```json
{
  "version": 1,
  "algorithm": "AES-256-GCM",
  "kdf": "HKDF-SHA256",
  ...
}
```

Version field allows protocol upgrades while maintaining backward compatibility.

### Version Downgrade Protection

When parsing connection info or messages, reject versions lower than the minimum supported. Never allow downgrade to insecure protocol versions.

---

## Future Considerations

### Perfect Forward Secrecy (PFS)

Current design: If the master secret is compromised, all past messages can be decrypted.

WebRTC's DTLS layer provides PFS at the transport level. However, our application-layer encryption (using static `encrypt_key`) does not have PFS.

**Post-MVP enhancement:** Implement ephemeral key exchange (like Signal's Double Ratchet) for application-layer PFS.

### Key Rotation

Currently, no mechanism to rotate keys without re-pairing. For MVP, users can revoke a device and re-pair if compromise is suspected.

**Post-MVP enhancement:** Add key rotation protocol that allows refreshing keys without re-scanning QR code.
