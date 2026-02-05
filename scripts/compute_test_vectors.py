#!/usr/bin/env python3
"""
Compute test vectors for Phase 7a: Pairing Contract Definition.

This script generates all cryptographic test vectors that both daemon (Python)
and Android (Kotlin) implementations must pass.
"""

import hashlib
import hmac
import json
import struct
import sys
from pathlib import Path

# Try to use cryptography library for HKDF, fall back to manual if not available
try:
    from cryptography.hazmat.primitives.kdf.hkdf import HKDF
    from cryptography.hazmat.primitives import hashes
    HAS_CRYPTOGRAPHY = True
except ImportError:
    HAS_CRYPTOGRAPHY = False


def hkdf_sha256(ikm: bytes, info: bytes, length: int = 32, salt: bytes = b"") -> bytes:
    """HKDF-SHA256 key derivation (RFC 5869)."""
    if HAS_CRYPTOGRAPHY:
        hkdf = HKDF(
            algorithm=hashes.SHA256(),
            length=length,
            salt=salt if salt else None,
            info=info,
        )
        return hkdf.derive(ikm)
    else:
        # Manual HKDF implementation
        # Extract
        if not salt:
            salt = b'\x00' * 32
        prk = hmac.new(salt, ikm, hashlib.sha256).digest()

        # Expand
        t = b""
        okm = b""
        for i in range(1, (length + 31) // 32 + 1):
            t = hmac.new(prk, t + info + bytes([i]), hashlib.sha256).digest()
            okm += t
        return okm[:length]


def hmac_sha256(key: bytes, message: bytes) -> bytes:
    """HMAC-SHA256."""
    return hmac.new(key, message, hashlib.sha256).digest()


def sha256(data: bytes) -> bytes:
    """SHA-256 hash."""
    return hashlib.sha256(data).digest()


def derive_ntfy_topic(master_secret: bytes) -> str:
    """Derive ntfy topic from master secret."""
    hash_bytes = sha256(master_secret)
    return "ras-" + hash_bytes[:6].hex()


def compute_signaling_hmac(auth_key: bytes, session_id: str, timestamp: int, body: bytes) -> bytes:
    """
    Compute HMAC for HTTP signaling.
    HMAC_input = UTF8(session_id) || BigEndian64(timestamp) || body
    """
    hmac_input = session_id.encode('utf-8') + struct.pack('>Q', timestamp) + body
    return hmac_sha256(auth_key, hmac_input)


def compute_key_derivation_vectors() -> dict:
    """Compute key derivation test vectors."""
    vectors = []

    # Test case 1: Standard key derivation
    master_secret = bytes.fromhex("0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef")

    auth_key = hkdf_sha256(master_secret, b"auth")
    encrypt_key = hkdf_sha256(master_secret, b"encrypt")
    ntfy_key = hkdf_sha256(master_secret, b"ntfy")
    ntfy_topic = derive_ntfy_topic(master_secret)

    vectors.append({
        "id": "derive_auth_key_1",
        "master_secret_hex": master_secret.hex(),
        "purpose": "auth",
        "expected_key_hex": auth_key.hex()
    })

    vectors.append({
        "id": "derive_encrypt_key_1",
        "master_secret_hex": master_secret.hex(),
        "purpose": "encrypt",
        "expected_key_hex": encrypt_key.hex()
    })

    vectors.append({
        "id": "derive_ntfy_key_1",
        "master_secret_hex": master_secret.hex(),
        "purpose": "ntfy",
        "expected_key_hex": ntfy_key.hex()
    })

    vectors.append({
        "id": "derive_topic_1",
        "master_secret_hex": master_secret.hex(),
        "expected_topic": ntfy_topic
    })

    # Test case 2: All zeros
    master_secret_zeros = bytes(32)
    auth_key_zeros = hkdf_sha256(master_secret_zeros, b"auth")

    vectors.append({
        "id": "derive_auth_key_zeros",
        "master_secret_hex": master_secret_zeros.hex(),
        "purpose": "auth",
        "expected_key_hex": auth_key_zeros.hex()
    })

    # Test case 3: All ones (0xff)
    master_secret_ones = bytes([0xff] * 32)
    auth_key_ones = hkdf_sha256(master_secret_ones, b"auth")

    vectors.append({
        "id": "derive_auth_key_ones",
        "master_secret_hex": master_secret_ones.hex(),
        "purpose": "auth",
        "expected_key_hex": auth_key_ones.hex()
    })

    return {
        "description": "HKDF key derivation test vectors",
        "algorithm": "HKDF-SHA256",
        "salt": "empty (None)",
        "vectors": vectors
    }


def compute_hmac_vectors() -> dict:
    """Compute HMAC test vectors."""
    vectors = []

    key = bytes.fromhex("0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef")

    # Test case 1: Standard HMAC
    message1 = bytes.fromhex("fedcba9876543210fedcba9876543210fedcba9876543210fedcba9876543210")
    hmac1 = hmac_sha256(key, message1)

    vectors.append({
        "id": "hmac_auth_challenge",
        "key_hex": key.hex(),
        "message_hex": message1.hex(),
        "expected_hmac_hex": hmac1.hex()
    })

    # Test case 2: Empty message
    hmac2 = hmac_sha256(key, b"")

    vectors.append({
        "id": "hmac_empty_message",
        "key_hex": key.hex(),
        "message_hex": "",
        "expected_hmac_hex": hmac2.hex()
    })

    # Test case 3: Long message
    message3 = bytes(range(64))
    hmac3 = hmac_sha256(key, message3)

    vectors.append({
        "id": "hmac_long_message",
        "key_hex": key.hex(),
        "message_hex": message3.hex(),
        "expected_hmac_hex": hmac3.hex()
    })

    return {
        "description": "HMAC-SHA256 test vectors",
        "vectors": vectors
    }


def compute_signaling_vectors() -> dict:
    """Compute HTTP signaling HMAC test vectors."""
    vectors = []
    error_cases = []

    auth_key = bytes.fromhex("0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef")

    # Test case 1: Valid signaling
    session_id1 = "abc123def456"
    timestamp1 = 1706400000
    body1 = bytes.fromhex("0a1048656c6c6f20576f726c6421")
    sig1 = compute_signaling_hmac(auth_key, session_id1, timestamp1, body1)

    vectors.append({
        "id": "signaling_valid",
        "auth_key_hex": auth_key.hex(),
        "session_id": session_id1,
        "timestamp": timestamp1,
        "body_hex": body1.hex(),
        "expected_signature_hex": sig1.hex()
    })

    # Test case 2: Different session ID
    session_id2 = "xyz789"
    sig2 = compute_signaling_hmac(auth_key, session_id2, timestamp1, body1)

    vectors.append({
        "id": "signaling_different_session",
        "auth_key_hex": auth_key.hex(),
        "session_id": session_id2,
        "timestamp": timestamp1,
        "body_hex": body1.hex(),
        "expected_signature_hex": sig2.hex()
    })

    # Test case 3: Different timestamp
    timestamp3 = 1706400100
    sig3 = compute_signaling_hmac(auth_key, session_id1, timestamp3, body1)

    vectors.append({
        "id": "signaling_different_timestamp",
        "auth_key_hex": auth_key.hex(),
        "session_id": session_id1,
        "timestamp": timestamp3,
        "body_hex": body1.hex(),
        "expected_signature_hex": sig3.hex()
    })

    # Error cases
    error_cases = [
        {
            "id": "timestamp_too_old",
            "description": "Timestamp more than 30 seconds in past",
            "current_time": 1706400100,
            "request_timestamp": 1706400000,
            "expected_error": "AUTHENTICATION_FAILED"
        },
        {
            "id": "timestamp_in_future",
            "description": "Timestamp more than 30 seconds in future",
            "current_time": 1706400000,
            "request_timestamp": 1706400100,
            "expected_error": "AUTHENTICATION_FAILED"
        },
        {
            "id": "wrong_signature",
            "description": "HMAC doesn't match",
            "expected_error": "AUTHENTICATION_FAILED"
        },
        {
            "id": "unknown_session",
            "description": "Session ID not found",
            "expected_error": "INVALID_SESSION"
        }
    ]

    return {
        "description": "HTTP signaling HMAC test vectors",
        "hmac_algorithm": "HMAC-SHA256",
        "hmac_input_format": "UTF8(session_id) || BigEndian64(timestamp) || body_bytes",
        "vectors": vectors,
        "error_cases": error_cases
    }


def compute_qr_payload_vectors() -> dict:
    """Compute QR payload test vectors."""
    # Note: We need protobuf to compute the serialized form
    # For now, we document the fields and expected validation behavior

    master_secret = bytes.fromhex("0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef")
    ntfy_topic = derive_ntfy_topic(master_secret)

    vectors = [
        {
            "id": "valid_ipv4",
            "fields": {
                "version": 1,
                "ip": "192.168.1.100",
                "port": 8821,
                "master_secret_hex": master_secret.hex(),
                "session_id": "abc123def456ghij",
                "ntfy_topic": ntfy_topic
            },
            "note": "Protobuf hex computed by implementation tests"
        },
        {
            "id": "valid_ipv6_full",
            "fields": {
                "version": 1,
                "ip": "2001:0db8:85a3:0000:0000:8a2e:0370:7334",
                "port": 8821,
                "master_secret_hex": master_secret.hex(),
                "session_id": "abc123def456ghij",
                "ntfy_topic": ntfy_topic
            }
        },
        {
            "id": "valid_ipv6_compressed",
            "fields": {
                "version": 1,
                "ip": "2001:db8::1",
                "port": 8821,
                "master_secret_hex": master_secret.hex(),
                "session_id": "abc123def456ghij",
                "ntfy_topic": ntfy_topic
            }
        },
        {
            "id": "valid_ipv6_localhost",
            "fields": {
                "version": 1,
                "ip": "::1",
                "port": 8821,
                "master_secret_hex": master_secret.hex(),
                "session_id": "abc123def456ghij",
                "ntfy_topic": ntfy_topic
            }
        },
        {
            "id": "port_min",
            "fields": {
                "version": 1,
                "ip": "192.168.1.1",
                "port": 1,
                "master_secret_hex": master_secret.hex(),
                "session_id": "abc123def456ghij",
                "ntfy_topic": ntfy_topic
            }
        },
        {
            "id": "port_max",
            "fields": {
                "version": 1,
                "ip": "192.168.1.1",
                "port": 65535,
                "master_secret_hex": master_secret.hex(),
                "session_id": "abc123def456ghij",
                "ntfy_topic": ntfy_topic
            }
        }
    ]

    error_cases = [
        {
            "id": "invalid_version",
            "description": "Unsupported protocol version",
            "fields": {
                "version": 999,
                "ip": "192.168.1.1",
                "port": 8821,
                "master_secret_hex": master_secret.hex(),
                "session_id": "abc123def456ghij",
                "ntfy_topic": "ras-abc123"
            },
            "expected_error": "UNSUPPORTED_VERSION"
        },
        {
            "id": "missing_ip",
            "description": "IP field is empty",
            "fields": {
                "version": 1,
                "ip": "",
                "port": 8821,
                "master_secret_hex": master_secret.hex(),
                "session_id": "abc123def456ghij",
                "ntfy_topic": "ras-abc123"
            },
            "expected_error": "MISSING_FIELD"
        },
        {
            "id": "invalid_secret_length_short",
            "description": "Master secret too short (16 bytes instead of 32)",
            "fields": {
                "version": 1,
                "ip": "192.168.1.1",
                "port": 8821,
                "master_secret_hex": "0123456789abcdef0123456789abcdef",
                "session_id": "abc123def456ghij",
                "ntfy_topic": "ras-abc123"
            },
            "expected_error": "INVALID_SECRET_LENGTH"
        },
        {
            "id": "invalid_secret_length_long",
            "description": "Master secret too long (64 bytes instead of 32)",
            "fields": {
                "version": 1,
                "ip": "192.168.1.1",
                "port": 8821,
                "master_secret_hex": "0123456789abcdef" * 8,
                "session_id": "abc123def456ghij",
                "ntfy_topic": "ras-abc123"
            },
            "expected_error": "INVALID_SECRET_LENGTH"
        },
        {
            "id": "port_zero",
            "description": "Port 0 is invalid",
            "fields": {
                "version": 1,
                "ip": "192.168.1.1",
                "port": 0,
                "master_secret_hex": master_secret.hex(),
                "session_id": "abc123def456ghij",
                "ntfy_topic": "ras-abc123"
            },
            "expected_error": "INVALID_PORT"
        },
        {
            "id": "port_too_large",
            "description": "Port > 65535 is invalid",
            "fields": {
                "version": 1,
                "ip": "192.168.1.1",
                "port": 65536,
                "master_secret_hex": master_secret.hex(),
                "session_id": "abc123def456ghij",
                "ntfy_topic": "ras-abc123"
            },
            "expected_error": "INVALID_PORT"
        },
        {
            "id": "malformed_protobuf",
            "description": "Invalid protobuf bytes",
            "raw_bytes_hex": "ffffffff",
            "expected_error": "PARSE_ERROR"
        },
        {
            "id": "truncated_protobuf",
            "description": "Protobuf truncated mid-field",
            "raw_bytes_hex": "0801120a",
            "expected_error": "PARSE_ERROR"
        }
    ]

    return {
        "description": "QR code payload test vectors",
        "format": "protobuf",
        "vectors": vectors,
        "error_cases": error_cases
    }


def compute_auth_handshake_vectors() -> dict:
    """Compute authentication handshake test vectors."""
    master_secret = bytes.fromhex("0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef")
    auth_key = hkdf_sha256(master_secret, b"auth")

    # Nonces for testing
    server_nonce = bytes.fromhex("aa" * 32)
    client_nonce = bytes.fromhex("bb" * 32)

    # Compute expected HMACs
    client_hmac = hmac_sha256(auth_key, server_nonce)  # Client proves it knows secret
    server_hmac = hmac_sha256(auth_key, client_nonce)  # Server proves it knows secret

    vectors = [
        {
            "id": "full_handshake_success",
            "description": "Complete successful handshake",
            "steps": [
                {
                    "direction": "server_to_client",
                    "message": "AuthChallenge",
                    "nonce_hex": server_nonce.hex()
                },
                {
                    "direction": "client_to_server",
                    "message": "AuthResponse",
                    "expected_hmac_hex": client_hmac.hex(),
                    "nonce_hex": client_nonce.hex()
                },
                {
                    "direction": "server_to_client",
                    "message": "AuthVerify",
                    "expected_hmac_hex": server_hmac.hex()
                },
                {
                    "direction": "server_to_client",
                    "message": "AuthSuccess",
                    "device_id": "test-device-123"
                }
            ],
            "expected_result": "AUTHENTICATED"
        },
        {
            "id": "wrong_client_hmac",
            "description": "Client sends wrong HMAC",
            "steps": [
                {
                    "direction": "server_to_client",
                    "message": "AuthChallenge",
                    "nonce_hex": server_nonce.hex()
                },
                {
                    "direction": "client_to_server",
                    "message": "AuthResponse",
                    "hmac_hex": "00" * 32,
                    "nonce_hex": client_nonce.hex()
                },
                {
                    "direction": "server_to_client",
                    "message": "AuthError",
                    "expected_error_code": "INVALID_HMAC"
                }
            ],
            "expected_result": "FAILED"
        },
        {
            "id": "wrong_server_hmac",
            "description": "Server sends wrong HMAC (client should reject)",
            "steps": [
                {
                    "direction": "server_to_client",
                    "message": "AuthChallenge",
                    "nonce_hex": server_nonce.hex()
                },
                {
                    "direction": "client_to_server",
                    "message": "AuthResponse",
                    "expected_hmac_hex": client_hmac.hex(),
                    "nonce_hex": client_nonce.hex()
                },
                {
                    "direction": "server_to_client",
                    "message": "AuthVerify",
                    "hmac_hex": "00" * 32
                }
            ],
            "expected_result": "FAILED",
            "expected_client_action": "CLOSE_CONNECTION"
        },
        {
            "id": "invalid_nonce_length",
            "description": "Server sends nonce with wrong length",
            "steps": [
                {
                    "direction": "server_to_client",
                    "message": "AuthChallenge",
                    "nonce_hex": "aabbccdd"
                }
            ],
            "expected_result": "FAILED",
            "expected_client_action": "CLOSE_CONNECTION"
        },
        {
            "id": "unexpected_message_type",
            "description": "Receive AuthVerify before AuthChallenge",
            "steps": [
                {
                    "direction": "server_to_client",
                    "message": "AuthVerify",
                    "hmac_hex": "00" * 32
                }
            ],
            "expected_result": "FAILED",
            "expected_error_code": "PROTOCOL_ERROR"
        },
        {
            "id": "timeout",
            "description": "No response within timeout period",
            "timeout_ms": 10000,
            "steps": [
                {
                    "direction": "server_to_client",
                    "message": "AuthChallenge",
                    "nonce_hex": server_nonce.hex()
                },
                {
                    "action": "wait",
                    "duration_ms": 15000
                }
            ],
            "expected_result": "FAILED",
            "expected_error_code": "TIMEOUT"
        }
    ]

    return {
        "description": "Authentication handshake test vectors",
        "test_master_secret_hex": master_secret.hex(),
        "test_auth_key_hex": auth_key.hex(),
        "vectors": vectors
    }


def encode_varint(value: int) -> bytes:
    """Encode an integer as a protobuf varint."""
    result = []
    while value > 0x7f:
        result.append((value & 0x7f) | 0x80)
        value >>= 7
    result.append(value & 0x7f)
    return bytes(result)


def encode_proto_string(field_number: int, value: str) -> bytes:
    """Encode a string field in protobuf wire format."""
    data = value.encode("utf-8")
    tag = (field_number << 3) | 2  # wire type 2 = length-delimited
    return bytes([tag]) + encode_varint(len(data)) + data


def encode_proto_int64(field_number: int, value: int) -> bytes:
    """Encode an int64 field in protobuf wire format."""
    tag = (field_number << 3) | 0  # wire type 0 = varint
    return bytes([tag]) + encode_varint(value)


def compute_lan_direct_vectors() -> dict:
    """Compute LAN Direct WebSocket auth test vectors.

    These vectors verify the full LAN Direct auth contract:
    1. Key derivation: master_secret -> deriveKey(secret, "auth") -> auth_key
    2. HMAC computation: computeSignalingHmac(authKey, deviceId, timestamp, emptyBody)
    3. Protobuf serialization: LanDirectAuthRequest fields -> protobuf bytes

    The double-derivation bug (calling deriveKey on an already-derived authToken)
    is caught by verifying that both platforms produce identical auth request bytes
    from the same master_secret.
    """
    master_secret = bytes.fromhex(
        "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
    )
    auth_key = hkdf_sha256(master_secret, b"auth")

    # Fixed test inputs
    device_id = "test-device-abc123"
    timestamp = 1706400000

    # Step 1: HMAC computation (same as signaling HMAC with empty body)
    signature = compute_signaling_hmac(auth_key, device_id, timestamp, b"")
    signature_hex = signature.hex()

    # Step 2: Protobuf serialization
    # LanDirectAuthRequest { device_id=1, timestamp=2, signature=3 }
    proto_bytes = (
        encode_proto_string(1, device_id)
        + encode_proto_int64(2, timestamp)
        + encode_proto_string(3, signature_hex)
    )

    # Also compute what happens with the WRONG key (master_secret used directly)
    # This is the exact bug: Android was passing master_secret to computeSignalingHmac
    # instead of the derived auth_key
    wrong_signature = compute_signaling_hmac(master_secret, device_id, timestamp, b"")
    wrong_signature_hex = wrong_signature.hex()

    wrong_proto_bytes = (
        encode_proto_string(1, device_id)
        + encode_proto_int64(2, timestamp)
        + encode_proto_string(3, wrong_signature_hex)
    )

    vectors = [
        {
            "id": "key_derivation",
            "description": "Derive auth key from master secret",
            "master_secret_hex": master_secret.hex(),
            "purpose": "auth",
            "expected_auth_key_hex": auth_key.hex(),
        },
        {
            "id": "hmac_computation",
            "description": "Compute signaling HMAC for WebSocket auth",
            "auth_key_hex": auth_key.hex(),
            "device_id": device_id,
            "timestamp": timestamp,
            "body_hex": "",
            "expected_signature_hex": signature_hex,
        },
        {
            "id": "protobuf_serialization",
            "description": "Serialize LanDirectAuthRequest to protobuf bytes",
            "device_id": device_id,
            "timestamp": timestamp,
            "signature_hex": signature_hex,
            "expected_protobuf_hex": proto_bytes.hex(),
        },
        {
            "id": "full_auth_request",
            "description": "Full flow: master_secret -> auth_key -> HMAC -> protobuf",
            "master_secret_hex": master_secret.hex(),
            "device_id": device_id,
            "timestamp": timestamp,
            "expected_auth_key_hex": auth_key.hex(),
            "expected_signature_hex": signature_hex,
            "expected_protobuf_hex": proto_bytes.hex(),
        },
    ]

    error_cases = [
        {
            "id": "double_derivation_bug",
            "description": "Using master_secret directly instead of derived auth_key produces wrong HMAC",
            "master_secret_hex": master_secret.hex(),
            "device_id": device_id,
            "timestamp": timestamp,
            "wrong_signature_hex": wrong_signature_hex,
            "wrong_protobuf_hex": wrong_proto_bytes.hex(),
            "correct_signature_hex": signature_hex,
            "note": "This is the exact bug: Android called deriveKey() on an already-derived authToken",
        },
        {
            "id": "expired_timestamp",
            "description": "Timestamp more than 30s in the past should be rejected",
            "auth_key_hex": auth_key.hex(),
            "device_id": device_id,
            "timestamp": 1706399900,
            "current_time": 1706400000,
            "expected_error": "authentication_failed",
        },
        {
            "id": "wrong_device_id",
            "description": "Auth request device_id does not match URL device_id",
            "auth_key_hex": auth_key.hex(),
            "request_device_id": "wrong-device-id",
            "url_device_id": device_id,
            "timestamp": timestamp,
            "expected_error": "authentication_failed",
        },
    ]

    return {
        "description": "LAN Direct WebSocket auth test vectors",
        "protocol": "LanDirectAuthRequest protobuf over WebSocket",
        "hmac_input_format": "UTF8(device_id) || BigEndian64(timestamp) || empty_body",
        "vectors": vectors,
        "error_cases": error_cases,
    }


def main():
    """Generate all test vectors and save to files."""
    output_dir = Path(__file__).parent.parent / "test-vectors"
    output_dir.mkdir(exist_ok=True)

    # Compute and save all vectors
    vectors = {
        "key_derivation.json": compute_key_derivation_vectors(),
        "hmac.json": compute_hmac_vectors(),
        "signaling.json": compute_signaling_vectors(),
        "qr_payload.json": compute_qr_payload_vectors(),
        "auth_handshake.json": compute_auth_handshake_vectors(),
        "lan_direct_auth.json": compute_lan_direct_vectors(),
    }

    for filename, data in vectors.items():
        filepath = output_dir / filename
        with open(filepath, "w") as f:
            json.dump(data, f, indent=2)
        print(f"Generated: {filepath}")

    print(f"\nAll test vectors generated in {output_dir}")
    print(f"Using cryptography library: {HAS_CRYPTOGRAPHY}")


if __name__ == "__main__":
    main()
