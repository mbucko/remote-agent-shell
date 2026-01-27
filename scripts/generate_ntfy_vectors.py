#!/usr/bin/env python3
"""
Generate COMPREHENSIVE test vectors for ntfy IP change notifications.

Covers ALL edge cases for first-try compatibility between daemon and Android.

Usage:
    cd daemon && uv run python ../scripts/generate_ntfy_vectors.py
"""

import base64
import hashlib
import json
import sys
from pathlib import Path

try:
    from cryptography.hazmat.primitives.ciphers.aead import AESGCM
    from cryptography.hazmat.primitives.kdf.hkdf import HKDF
    from cryptography.hazmat.primitives import hashes
except ImportError:
    print("Error: cryptography library not found")
    print("Run: pip install cryptography")
    sys.exit(1)


def derive_key(master_secret: bytes, purpose: str) -> bytes:
    """Derive key using HKDF-SHA256."""
    hkdf = HKDF(
        algorithm=hashes.SHA256(),
        length=32,
        salt=None,
        info=purpose.encode('utf-8'),
    )
    return hkdf.derive(master_secret)


def derive_topic(master_secret: bytes) -> str:
    """Derive ntfy topic from master secret."""
    hash_bytes = hashlib.sha256(master_secret).digest()
    return "ras-" + hash_bytes[:6].hex()


def serialize_protobuf(ip: str, port: int, timestamp: int, nonce: bytes) -> bytes:
    """Manually serialize IpChangeNotification protobuf."""
    result = bytearray()

    # Field 1: ip (string)
    ip_bytes = ip.encode('utf-8')
    result.append(0x0a)
    result.extend(encode_varint(len(ip_bytes)))
    result.extend(ip_bytes)

    # Field 2: port (uint32)
    result.append(0x10)
    result.extend(encode_varint(port))

    # Field 3: timestamp (int64)
    result.append(0x18)
    result.extend(encode_varint(timestamp))

    # Field 4: nonce (bytes)
    result.append(0x22)
    result.extend(encode_varint(len(nonce)))
    result.extend(nonce)

    return bytes(result)


def encode_varint(value: int) -> bytes:
    """Encode integer as protobuf varint."""
    result = bytearray()
    while value > 127:
        result.append((value & 0x7f) | 0x80)
        value >>= 7
    result.append(value)
    return bytes(result)


def encrypt(key: bytes, iv: bytes, plaintext: bytes) -> bytes:
    """Encrypt using AES-256-GCM."""
    aesgcm = AESGCM(key)
    ciphertext = aesgcm.encrypt(iv, plaintext, None)
    return iv + ciphertext


def generate_vectors():
    """Generate all test vectors with comprehensive edge cases."""
    vectors = {
        "description": "Comprehensive ntfy IP change notification test vectors",
        "version": 1,
        "generated_by": "scripts/generate_ntfy_vectors.py",
        "compatibility_note": "Both daemon (Python) and Android (Kotlin) MUST pass ALL vectors",
        "sections": {}
    }

    # ==================== MASTER SECRETS ====================
    secrets = {
        "normal": bytes.fromhex("0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"),
        "zeros": bytes(32),
        "ones": bytes([0xff] * 32),
        "alternating": bytes([0xaa, 0x55] * 16),
    }

    # ==================== 1. TOPIC DERIVATION ====================
    topic_vectors = []
    for name, secret in secrets.items():
        sha256_full = hashlib.sha256(secret).hexdigest()
        topic = derive_topic(secret)
        topic_vectors.append({
            "id": f"topic_{name}",
            "master_secret_hex": secret.hex(),
            "sha256_full_hex": sha256_full,
            "sha256_first_6_bytes_hex": sha256_full[:12],
            "expected_topic": topic
        })

    vectors["sections"]["topic_derivation"] = {
        "description": "Topic = 'ras-' + SHA256(master_secret)[:6].hex()",
        "algorithm": "SHA-256, take first 6 bytes, convert to hex, prepend 'ras-'",
        "vectors": topic_vectors
    }

    # ==================== 2. KEY DERIVATION ====================
    key_vectors = []
    for name, secret in secrets.items():
        ntfy_key = derive_key(secret, "ntfy")
        key_vectors.append({
            "id": f"key_{name}",
            "master_secret_hex": secret.hex(),
            "info": "ntfy",
            "salt": "None (empty)",
            "expected_key_hex": ntfy_key.hex()
        })

    vectors["sections"]["key_derivation"] = {
        "description": "HKDF-SHA256 key derivation",
        "algorithm": "HKDF(algorithm=SHA256, length=32, salt=None, info=b'ntfy')",
        "reference": "RFC 5869",
        "vectors": key_vectors
    }

    # Use standard key for remaining tests
    ntfy_key = derive_key(secrets["normal"], "ntfy")
    fixed_iv = bytes.fromhex("000102030405060708090a0b")

    # ==================== 3. PROTOBUF SERIALIZATION ====================
    proto_cases = [
        # Normal cases
        {"id": "proto_ipv4_normal", "ip": "192.168.1.100", "port": 8821, "timestamp": 1706384400, "nonce": bytes.fromhex("00112233445566778899aabbccddeeff")},
        {"id": "proto_ipv6_full", "ip": "2001:0db8:85a3:0000:0000:8a2e:0370:7334", "port": 8821, "timestamp": 1706384400, "nonce": bytes.fromhex("ffeeddccbbaa99887766554433221100")},
        {"id": "proto_ipv6_short", "ip": "2001:db8::1", "port": 8821, "timestamp": 1706384400, "nonce": bytes.fromhex("ffeeddccbbaa99887766554433221100")},
        {"id": "proto_ipv6_loopback", "ip": "::1", "port": 8080, "timestamp": 1706384400, "nonce": bytes(16)},

        # Port edge cases
        {"id": "proto_port_zero", "ip": "1.2.3.4", "port": 0, "timestamp": 1706384400, "nonce": bytes(16)},
        {"id": "proto_port_max", "ip": "1.2.3.4", "port": 65535, "timestamp": 1706384400, "nonce": bytes(16)},
        {"id": "proto_port_http", "ip": "1.2.3.4", "port": 80, "timestamp": 1706384400, "nonce": bytes(16)},
        {"id": "proto_port_https", "ip": "1.2.3.4", "port": 443, "timestamp": 1706384400, "nonce": bytes(16)},

        # Timestamp edge cases
        {"id": "proto_timestamp_zero", "ip": "1.2.3.4", "port": 8821, "timestamp": 0, "nonce": bytes(16)},
        {"id": "proto_timestamp_epoch", "ip": "1.2.3.4", "port": 8821, "timestamp": 1, "nonce": bytes(16)},
        {"id": "proto_timestamp_y2038", "ip": "1.2.3.4", "port": 8821, "timestamp": 2147483647, "nonce": bytes(16)},  # Max 32-bit signed
        {"id": "proto_timestamp_y2100", "ip": "1.2.3.4", "port": 8821, "timestamp": 4102444800, "nonce": bytes(16)},  # Year 2100

        # Nonce edge cases
        {"id": "proto_nonce_zeros", "ip": "1.2.3.4", "port": 8821, "timestamp": 1706384400, "nonce": bytes(16)},
        {"id": "proto_nonce_ones", "ip": "1.2.3.4", "port": 8821, "timestamp": 1706384400, "nonce": bytes([0xff] * 16)},
        {"id": "proto_nonce_sequential", "ip": "1.2.3.4", "port": 8821, "timestamp": 1706384400, "nonce": bytes(range(16))},

        # IP edge cases
        {"id": "proto_ip_localhost", "ip": "127.0.0.1", "port": 8821, "timestamp": 1706384400, "nonce": bytes(16)},
        {"id": "proto_ip_broadcast", "ip": "255.255.255.255", "port": 8821, "timestamp": 1706384400, "nonce": bytes(16)},
        {"id": "proto_ip_min", "ip": "0.0.0.0", "port": 8821, "timestamp": 1706384400, "nonce": bytes(16)},
    ]

    proto_vectors = []
    for tc in proto_cases:
        serialized = serialize_protobuf(tc["ip"], tc["port"], tc["timestamp"], tc["nonce"])
        proto_vectors.append({
            "id": tc["id"],
            "input": {
                "ip": tc["ip"],
                "port": tc["port"],
                "timestamp": tc["timestamp"],
                "nonce_hex": tc["nonce"].hex()
            },
            "expected_bytes_hex": serialized.hex(),
            "expected_length": len(serialized)
        })

    vectors["sections"]["protobuf_serialization"] = {
        "description": "IpChangeNotification protobuf wire format",
        "fields": {
            "1": "ip (string)",
            "2": "port (uint32)",
            "3": "timestamp (int64)",
            "4": "nonce (bytes, 16 bytes)"
        },
        "wire_format": "Proto3 with varint encoding",
        "vectors": proto_vectors
    }

    # ==================== 4. ENCRYPTION ====================
    # Select subset for encryption tests
    encrypt_cases = [
        proto_cases[0],  # IPv4 normal
        proto_cases[1],  # IPv6 full
        proto_cases[2],  # IPv6 short
        proto_cases[4],  # Port zero
        proto_cases[5],  # Port max
        proto_cases[8],  # Timestamp zero
        proto_cases[10], # Timestamp Y2038
    ]

    encryption_vectors = []
    for tc in encrypt_cases:
        plaintext = serialize_protobuf(tc["ip"], tc["port"], tc["timestamp"], tc["nonce"])
        encrypted = encrypt(ntfy_key, fixed_iv, plaintext)

        encryption_vectors.append({
            "id": f"encrypt_{tc['id']}",
            "ntfy_key_hex": ntfy_key.hex(),
            "iv_hex": fixed_iv.hex(),
            "plaintext_hex": plaintext.hex(),
            "ciphertext_base64": base64.b64encode(encrypted).decode(),
            "ciphertext_length": len(encrypted),
            "input": {
                "ip": tc["ip"],
                "port": tc["port"],
                "timestamp": tc["timestamp"],
                "nonce_hex": tc["nonce"].hex()
            }
        })

    # Additional encryption test: different key produces different output
    other_key = derive_key(secrets["zeros"], "ntfy")
    other_plaintext = serialize_protobuf("1.2.3.4", 8821, 1706384400, bytes(16))
    other_encrypted = encrypt(other_key, fixed_iv, other_plaintext)
    encryption_vectors.append({
        "id": "encrypt_different_key",
        "description": "Same plaintext with different key must produce different ciphertext",
        "ntfy_key_hex": other_key.hex(),
        "iv_hex": fixed_iv.hex(),
        "plaintext_hex": other_plaintext.hex(),
        "ciphertext_base64": base64.b64encode(other_encrypted).decode()
    })

    vectors["sections"]["encryption"] = {
        "description": "AES-256-GCM encryption",
        "format": "base64(iv_12 || ciphertext || tag_16)",
        "warning": "Test vectors use FIXED IV for reproducibility. Production MUST use random IV",
        "production_iv": {
            "python": "os.urandom(12)",
            "kotlin": "SecureRandom().nextBytes(ByteArray(12))"
        },
        "vectors": encryption_vectors
    }

    # ==================== 5. DECRYPTION (Round-trip) ====================
    vectors["sections"]["decryption"] = {
        "description": "Decryption must recover original values exactly",
        "test_procedure": [
            "1. Decode base64 to get bytes",
            "2. Extract IV (first 12 bytes)",
            "3. Decrypt remaining bytes with AES-256-GCM",
            "4. Parse protobuf",
            "5. Verify all fields match input"
        ],
        "vectors": encryption_vectors[:-1]  # Exclude "different key" test
    }

    # ==================== 6. SIZE VALIDATION ====================
    size_vectors = [
        {"id": "size_0_bytes", "input_base64": "", "length": 0, "valid": False, "reason": "Empty"},
        {"id": "size_1_byte", "input_base64": base64.b64encode(bytes(1)).decode(), "length": 1, "valid": False, "reason": "Too short"},
        {"id": "size_11_bytes", "input_base64": base64.b64encode(bytes(11)).decode(), "length": 11, "valid": False, "reason": "Less than IV"},
        {"id": "size_12_bytes", "input_base64": base64.b64encode(bytes(12)).decode(), "length": 12, "valid": False, "reason": "IV only, no tag"},
        {"id": "size_27_bytes", "input_base64": base64.b64encode(bytes(27)).decode(), "length": 27, "valid": False, "reason": "One byte short"},
        {"id": "size_28_bytes", "input_base64": base64.b64encode(bytes(28)).decode(), "length": 28, "valid": True, "reason": "Minimum valid (IV + tag, empty plaintext)"},
        {"id": "size_29_bytes", "input_base64": base64.b64encode(bytes(29)).decode(), "length": 29, "valid": True, "reason": "Minimum + 1 byte plaintext"},
        {"id": "size_100_bytes", "input_base64": base64.b64encode(bytes(100)).decode(), "length": 100, "valid": True, "reason": "Larger payload"},
    ]

    vectors["sections"]["size_validation"] = {
        "description": "Minimum size validation before decryption",
        "minimum_bytes": 28,
        "breakdown": {
            "iv": 12,
            "tag": 16,
            "minimum_plaintext": 0
        },
        "vectors": size_vectors
    }

    # ==================== 7. BASE64 VALIDATION ====================
    base64_vectors = [
        {"id": "base64_valid", "input": base64.b64encode(bytes(28)).decode(), "valid": True},
        {"id": "base64_with_padding", "input": "YWJjZA==", "valid": True},
        {"id": "base64_no_padding", "input": "YWJjZA", "valid": True, "note": "Some decoders accept this"},
        {"id": "base64_invalid_chars", "input": "abc!!!def", "valid": False, "reason": "Invalid characters"},
        {"id": "base64_spaces", "input": "YWJj ZGVm", "valid": False, "reason": "Contains space"},
        {"id": "base64_newlines", "input": "YWJj\nZGVm", "valid": False, "reason": "Contains newline"},
        {"id": "base64_empty", "input": "", "valid": True, "note": "Empty string is valid base64 (decodes to empty bytes)"},
        {"id": "base64_unicode", "input": "YWJj\u00e9", "valid": False, "reason": "Non-ASCII character"},
    ]

    vectors["sections"]["base64_validation"] = {
        "description": "Base64 decoding validation",
        "vectors": base64_vectors
    }

    # ==================== 8. DECRYPTION FAILURES ====================
    # Create a valid encrypted message
    valid_plaintext = serialize_protobuf("1.2.3.4", 8821, 1706384400, bytes(16))
    valid_encrypted = encrypt(ntfy_key, fixed_iv, valid_plaintext)

    decrypt_fail_vectors = [
        {
            "id": "decrypt_wrong_key",
            "description": "Decryption with wrong key must fail",
            "ciphertext_base64": base64.b64encode(valid_encrypted).decode(),
            "ntfy_key_hex": other_key.hex(),  # Wrong key
            "expected_error": "InvalidTag / AEADBadTagException"
        },
        {
            "id": "decrypt_corrupted_tag",
            "description": "Corrupted auth tag must fail",
            "ciphertext_base64": base64.b64encode(valid_encrypted[:-1] + bytes([valid_encrypted[-1] ^ 0xff])).decode(),
            "ntfy_key_hex": ntfy_key.hex(),
            "expected_error": "InvalidTag / AEADBadTagException"
        },
        {
            "id": "decrypt_corrupted_ciphertext",
            "description": "Corrupted ciphertext must fail",
            "ciphertext_base64": base64.b64encode(valid_encrypted[:20] + bytes([valid_encrypted[20] ^ 0xff]) + valid_encrypted[21:]).decode(),
            "ntfy_key_hex": ntfy_key.hex(),
            "expected_error": "InvalidTag / AEADBadTagException"
        },
        {
            "id": "decrypt_truncated",
            "description": "Truncated message must fail",
            "ciphertext_base64": base64.b64encode(valid_encrypted[:30]).decode(),
            "ntfy_key_hex": ntfy_key.hex(),
            "expected_error": "InvalidTag / AEADBadTagException"
        },
    ]

    vectors["sections"]["decryption_failures"] = {
        "description": "These decryption attempts MUST fail",
        "vectors": decrypt_fail_vectors
    }

    # ==================== 9. TIMESTAMP VALIDATION ====================
    # Reference time for tests
    ref_time = 1706384400  # Some fixed point

    timestamp_vectors = [
        {"id": "ts_exact_match", "message_ts": ref_time, "current_ts": ref_time, "valid": True, "delta": 0},
        {"id": "ts_1_second_ago", "message_ts": ref_time - 1, "current_ts": ref_time, "valid": True, "delta": 1},
        {"id": "ts_1_second_future", "message_ts": ref_time + 1, "current_ts": ref_time, "valid": True, "delta": -1},
        {"id": "ts_299_seconds_ago", "message_ts": ref_time - 299, "current_ts": ref_time, "valid": True, "delta": 299},
        {"id": "ts_300_seconds_ago", "message_ts": ref_time - 300, "current_ts": ref_time, "valid": True, "delta": 300, "note": "Boundary - exactly at limit"},
        {"id": "ts_301_seconds_ago", "message_ts": ref_time - 301, "current_ts": ref_time, "valid": False, "delta": 301, "reason": "Too old"},
        {"id": "ts_299_seconds_future", "message_ts": ref_time + 299, "current_ts": ref_time, "valid": True, "delta": -299},
        {"id": "ts_300_seconds_future", "message_ts": ref_time + 300, "current_ts": ref_time, "valid": True, "delta": -300, "note": "Boundary - exactly at limit"},
        {"id": "ts_301_seconds_future", "message_ts": ref_time + 301, "current_ts": ref_time, "valid": False, "delta": -301, "reason": "Too far in future"},
        {"id": "ts_1_hour_ago", "message_ts": ref_time - 3600, "current_ts": ref_time, "valid": False, "delta": 3600, "reason": "Way too old"},
        {"id": "ts_1_day_ago", "message_ts": ref_time - 86400, "current_ts": ref_time, "valid": False, "delta": 86400, "reason": "Way too old"},
    ]

    vectors["sections"]["timestamp_validation"] = {
        "description": "Timestamp must be within 300 seconds of current time",
        "window_seconds": 300,
        "formula": "abs(current_time - message_timestamp) <= 300",
        "vectors": timestamp_vectors
    }

    # ==================== 10. NONCE REPLAY DETECTION ====================
    nonce_vectors = [
        {"id": "nonce_first_use", "nonce_hex": "00112233445566778899aabbccddeeff", "seen_before": False, "valid": True},
        {"id": "nonce_second_use", "nonce_hex": "00112233445566778899aabbccddeeff", "seen_before": True, "valid": False, "reason": "Replay"},
        {"id": "nonce_different", "nonce_hex": "ffeeddccbbaa99887766554433221100", "seen_before": False, "valid": True},
        {"id": "nonce_all_zeros", "nonce_hex": "00000000000000000000000000000000", "seen_before": False, "valid": True},
        {"id": "nonce_all_zeros_replay", "nonce_hex": "00000000000000000000000000000000", "seen_before": True, "valid": False, "reason": "Replay"},
    ]

    vectors["sections"]["nonce_replay_detection"] = {
        "description": "Nonces must be tracked to prevent replay attacks",
        "cache_size": 100,
        "eviction": "FIFO when cache exceeds 100 entries",
        "vectors": nonce_vectors
    }

    # ==================== 11. NTFY EVENT TYPES ====================
    event_vectors = [
        {
            "id": "event_message",
            "json": '{"id":"abc","time":123,"event":"message","topic":"ras-test","message":"encrypted_payload"}',
            "should_process": True,
            "extract_field": "message"
        },
        {
            "id": "event_open",
            "json": '{"id":"abc","time":123,"event":"open","topic":"ras-test"}',
            "should_process": False,
            "reason": "Connection event, not a message"
        },
        {
            "id": "event_keepalive",
            "json": '{"id":"abc","time":123,"event":"keepalive","topic":"ras-test"}',
            "should_process": False,
            "reason": "Keepalive ping, not a message"
        },
        {
            "id": "event_unknown",
            "json": '{"id":"abc","time":123,"event":"unknown_type","topic":"ras-test"}',
            "should_process": False,
            "reason": "Unknown event type"
        },
        {
            "id": "event_missing",
            "json": '{"id":"abc","time":123,"topic":"ras-test"}',
            "should_process": False,
            "reason": "No event field"
        },
        {
            "id": "event_empty_message",
            "json": '{"id":"abc","time":123,"event":"message","topic":"ras-test","message":""}',
            "should_process": False,
            "reason": "Empty message field"
        },
        {
            "id": "event_missing_message",
            "json": '{"id":"abc","time":123,"event":"message","topic":"ras-test"}',
            "should_process": False,
            "reason": "No message field"
        },
    ]

    vectors["sections"]["ntfy_event_filtering"] = {
        "description": "Only 'message' events with non-empty message field should be processed",
        "vectors": event_vectors
    }

    # ==================== 12. IMPLEMENTATION REQUIREMENTS ====================
    vectors["implementation_requirements"] = {
        "cryptography": {
            "algorithm": "AES-256-GCM",
            "key_size_bytes": 32,
            "iv_size_bytes": 12,
            "tag_size_bytes": 16,
            "iv_generation": {
                "python": "os.urandom(12)",
                "kotlin": "SecureRandom().nextBytes(ByteArray(12))",
                "warning": "NEVER reuse IV with same key"
            },
            "nonce_generation": {
                "python": "os.urandom(16)",
                "kotlin": "SecureRandom().nextBytes(ByteArray(16))"
            }
        },
        "validation": {
            "timestamp_window_seconds": 300,
            "nonce_cache_max_size": 100,
            "min_encrypted_size_bytes": 28
        },
        "thread_safety": {
            "android_nonce_cache": "Collections.synchronizedSet(LinkedHashSet())"
        },
        "retry_publish": {
            "max_attempts": 3,
            "backoff_delays_ms": [1000, 2000, 4000]
        },
        "retry_websocket": {
            "max_attempts": 3,
            "backoff_delays_ms": [5000, 10000, 20000],
            "reset_on": "successful onOpen callback"
        },
        "logging": {
            "info_level": "Sanitized (no IPs, no keys)",
            "debug_level": "Full details allowed"
        }
    }

    return vectors


def main():
    vectors = generate_vectors()

    output_path = Path(__file__).parent.parent / "test-vectors" / "ntfy.json"
    with open(output_path, "w") as f:
        json.dump(vectors, f, indent=2)

    print(f"Generated comprehensive test vectors: {output_path}")

    # Summary
    total_vectors = 0
    for section_name, section in vectors.get("sections", {}).items():
        count = len(section.get("vectors", []))
        total_vectors += count
        print(f"  {section_name}: {count} vectors")

    print(f"\nTotal: {total_vectors} test vectors")

    # Key verification values
    master = bytes.fromhex("0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef")
    print(f"\nKey values for manual verification:")
    print(f"  Topic: {derive_topic(master)}")
    print(f"  ntfy_key: {derive_key(master, 'ntfy').hex()}")


if __name__ == "__main__":
    main()
