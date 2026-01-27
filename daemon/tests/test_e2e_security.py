"""End-to-end security tests.

These tests verify the complete security flow:
1. Secret generation and key derivation
2. Mutual authentication handshake
3. Encrypted message exchange
4. ntfy IP update encryption
5. Device storage security
6. Error scenarios and attack prevention
"""

import base64
import json
import time
from unittest.mock import AsyncMock, Mock

import pytest

from ras.auth import AuthError, AuthState, Authenticator
from ras.connection import ConnectionInfo
from ras.crypto import (
    CryptoError,
    KeyBundle,
    compute_hmac,
    decrypt,
    derive_keys,
    encrypt,
    generate_secret,
    verify_hmac,
)
from ras.errors import MessageError
from ras.message import Message, MessageCodec
from ras.ntfy import NtfyCrypto
from ras.storage import Device, DeviceStorage


class TestFullPairingFlow:
    """Test complete pairing flow from QR scan to authenticated connection."""

    def test_pairing_flow_secret_generation(self):
        """Secret is generated securely during pairing."""
        secret = generate_secret()
        assert len(secret) == 32
        # Verify reasonable entropy
        unique_bytes = len(set(secret))
        assert unique_bytes > 20

    def test_pairing_flow_connection_info_with_secret(self):
        """ConnectionInfo includes secret in QR code data."""
        secret = generate_secret()
        info = ConnectionInfo(ip="192.168.1.100", port=8821, secret=secret)

        # Verify QR code JSON includes secret
        qr_data = json.loads(info.to_json())
        assert "secret" in qr_data
        assert "topic" in qr_data

        # Verify secret can be decoded
        decoded_secret = base64.b64decode(qr_data["secret"])
        assert decoded_secret == secret

    def test_pairing_flow_key_derivation(self):
        """Keys are correctly derived from secret."""
        secret = generate_secret()
        keys = derive_keys(secret)

        # All keys are independent
        assert keys.auth_key != keys.encrypt_key
        assert keys.encrypt_key != keys.ntfy_key
        assert keys.auth_key != keys.ntfy_key

        # Topic is hex string
        assert len(keys.topic) == 12
        assert all(c in "0123456789abcdef" for c in keys.topic)

    def test_pairing_flow_both_sides_derive_same_keys(self):
        """Both daemon and phone derive identical keys from same secret."""
        secret = generate_secret()

        # Simulate daemon scanning QR
        daemon_keys = derive_keys(secret)

        # Simulate phone having same secret
        phone_keys = derive_keys(secret)

        assert daemon_keys.auth_key == phone_keys.auth_key
        assert daemon_keys.encrypt_key == phone_keys.encrypt_key
        assert daemon_keys.ntfy_key == phone_keys.ntfy_key
        assert daemon_keys.topic == phone_keys.topic


class TestFullAuthenticationFlow:
    """Test complete mutual authentication handshake."""

    def test_auth_flow_success(self):
        """Full authentication succeeds with same secret."""
        secret = generate_secret()
        keys = derive_keys(secret)

        daemon = Authenticator(auth_key=keys.auth_key, role="daemon")
        phone = Authenticator(auth_key=keys.auth_key, role="phone")

        # Step 1: Daemon sends challenge
        challenge = daemon.create_challenge()
        assert daemon.state == AuthState.CHALLENGED
        assert challenge["type"] == "auth_challenge"

        # Step 2: Phone responds with HMAC + own nonce
        response = phone.respond_to_challenge(challenge)
        assert phone.state == AuthState.RESPONDED
        assert response["type"] == "auth_response"

        # Step 3: Daemon verifies phone's response
        assert daemon.verify_response(response) is True

        # Step 4: Daemon sends verification
        verify = daemon.create_verify(response["nonce"])
        assert daemon.state == AuthState.AUTHENTICATED

        # Step 5: Phone verifies daemon
        assert phone.verify_verify(verify) is True
        assert phone.state == AuthState.AUTHENTICATED

    def test_auth_flow_wrong_secret_fails(self):
        """Authentication fails with different secrets."""
        daemon_secret = generate_secret()
        phone_secret = generate_secret()  # Different secret

        daemon_keys = derive_keys(daemon_secret)
        phone_keys = derive_keys(phone_secret)

        daemon = Authenticator(auth_key=daemon_keys.auth_key)
        phone = Authenticator(auth_key=phone_keys.auth_key)

        challenge = daemon.create_challenge()
        response = phone.respond_to_challenge(challenge)

        # Daemon rejects phone's response (wrong HMAC)
        assert daemon.verify_response(response) is False
        assert daemon.state == AuthState.FAILED

    def test_auth_flow_replay_attack_fails(self):
        """Replay attacks are detected and rejected."""
        secret = generate_secret()
        keys = derive_keys(secret)

        daemon = Authenticator(auth_key=keys.auth_key)
        phone = Authenticator(auth_key=keys.auth_key)

        challenge = daemon.create_challenge()
        response = phone.respond_to_challenge(challenge)

        # First use succeeds
        assert daemon.verify_response(response) is True

        # Reset state to simulate replay
        daemon._state = AuthState.CHALLENGED
        daemon._our_nonce = bytes.fromhex(challenge["nonce"])

        # Replay fails (nonce already used)
        assert daemon.verify_response(response) is False

    def test_auth_flow_rate_limiting(self):
        """Rate limiting kicks in after failed attempts."""
        daemon_secret = generate_secret()
        wrong_secret = generate_secret()

        daemon = Authenticator(auth_key=derive_keys(daemon_secret).auth_key)
        attacker = Authenticator(auth_key=derive_keys(wrong_secret).auth_key)

        # Simulate multiple failed attempts
        for _ in range(5):
            challenge = daemon.create_challenge()
            response = attacker.respond_to_challenge(challenge)
            daemon.verify_response(response)  # Fails

        # Rate limiting should now be active
        with pytest.raises(AuthError, match="Too many failed"):
            daemon._check_rate_limit()


class TestFullEncryptedMessageFlow:
    """Test complete encrypted message exchange."""

    def test_message_flow_roundtrip(self):
        """Messages can be encrypted and decrypted."""
        secret = generate_secret()
        keys = derive_keys(secret)

        # Daemon sends message
        daemon_codec = MessageCodec(encrypt_key=keys.encrypt_key)
        msg = Message(type="output", payload={"text": "Hello from daemon"})
        encrypted = daemon_codec.encode(msg)

        # Phone receives and decrypts
        phone_codec = MessageCodec(encrypt_key=keys.encrypt_key)
        decrypted = phone_codec.decode(encrypted)

        assert decrypted.type == "output"
        assert decrypted.payload["text"] == "Hello from daemon"

    def test_message_flow_sequence_tracking(self):
        """Sequence numbers are tracked across messages."""
        secret = generate_secret()
        keys = derive_keys(secret)

        sender = MessageCodec(encrypt_key=keys.encrypt_key)
        receiver = MessageCodec(encrypt_key=keys.encrypt_key)

        # Send multiple messages
        messages = []
        for i in range(5):
            msg = Message(type="ping", payload={"num": i})
            encrypted = sender.encode(msg)
            decrypted = receiver.decode(encrypted)
            messages.append(decrypted)

        # Verify sequence numbers increment
        for i in range(1, 5):
            assert messages[i].seq == messages[i-1].seq + 1

    def test_message_flow_wrong_key_fails(self):
        """Messages encrypted with wrong key can't be decrypted."""
        secret1 = generate_secret()
        secret2 = generate_secret()

        codec1 = MessageCodec(encrypt_key=derive_keys(secret1).encrypt_key)
        codec2 = MessageCodec(encrypt_key=derive_keys(secret2).encrypt_key)

        msg = Message(type="secret", payload={"data": "sensitive"})
        encrypted = codec1.encode(msg)

        with pytest.raises(MessageError, match="Decryption failed"):
            codec2.decode(encrypted)

    def test_message_flow_tampered_data_fails(self):
        """Tampered messages are rejected."""
        secret = generate_secret()
        keys = derive_keys(secret)

        codec = MessageCodec(encrypt_key=keys.encrypt_key)
        msg = Message(type="test", payload={})
        encrypted = bytearray(codec.encode(msg))

        # Tamper with the ciphertext
        encrypted[20] ^= 0xFF

        with pytest.raises(MessageError, match="Decryption failed"):
            codec.decode(bytes(encrypted))

    def test_message_flow_replay_detection(self):
        """Replayed messages are detected."""
        secret = generate_secret()
        keys = derive_keys(secret)

        sender = MessageCodec(encrypt_key=keys.encrypt_key)
        receiver = MessageCodec(encrypt_key=keys.encrypt_key)

        msg = Message(type="important", payload={})
        encrypted = sender.encode(msg)

        # First decode succeeds
        receiver.decode(encrypted)

        # Replay fails
        with pytest.raises(MessageError, match="Replay"):
            receiver.decode(encrypted)

    def test_message_flow_expired_timestamp(self):
        """Expired messages are rejected."""
        secret = generate_secret()
        keys = derive_keys(secret)

        codec = MessageCodec(encrypt_key=keys.encrypt_key, max_age=60)

        # Create message with old timestamp
        msg = Message(
            type="old",
            payload={},
            seq=1,
            timestamp=int(time.time()) - 120  # 2 minutes old
        )
        encrypted = codec.encode(msg)

        with pytest.raises(MessageError, match="expired"):
            codec.decode(encrypted)

    def test_message_flow_out_of_order_within_window(self):
        """Out-of-order messages within window are accepted."""
        secret = generate_secret()
        keys = derive_keys(secret)

        sender = MessageCodec(encrypt_key=keys.encrypt_key, window_size=100)
        receiver = MessageCodec(encrypt_key=keys.encrypt_key, window_size=100)

        # Send seq 10 first
        msg10 = Message(type="test", payload={}, seq=10, timestamp=int(time.time()))
        enc10 = sender.encode(msg10)

        # Send seq 5 second (out of order)
        msg5 = Message(type="test", payload={}, seq=5, timestamp=int(time.time()))
        enc5 = sender.encode(msg5)

        # Receive in order 10, then 5
        receiver.decode(enc10)
        dec5 = receiver.decode(enc5)  # Should succeed
        assert dec5.seq == 5


class TestNtfyIpUpdateFlow:
    """Test encrypted IP updates via ntfy."""

    def test_ntfy_flow_encrypt_decrypt(self):
        """IP updates can be encrypted and decrypted."""
        import os

        secret = generate_secret()
        keys = derive_keys(secret)

        sender = NtfyCrypto(ntfy_key=keys.ntfy_key)
        receiver = NtfyCrypto(ntfy_key=keys.ntfy_key)

        nonce = os.urandom(16)
        encrypted = sender.encrypt_ip_notification(
            ip="192.168.1.100",
            port=8821,
            timestamp=int(time.time()),
            nonce=nonce,
        )
        decrypted = receiver.decrypt_ip_notification(encrypted)

        assert decrypted.ip == "192.168.1.100"
        assert decrypted.port == 8821

    def test_ntfy_flow_wrong_key_fails(self):
        """IP updates encrypted with wrong key can't be decrypted."""
        import os

        from cryptography.exceptions import InvalidTag

        secret1 = generate_secret()
        secret2 = generate_secret()

        sender = NtfyCrypto(ntfy_key=derive_keys(secret1).ntfy_key)
        receiver = NtfyCrypto(ntfy_key=derive_keys(secret2).ntfy_key)

        nonce = os.urandom(16)
        encrypted = sender.encrypt_ip_notification(
            ip="192.168.1.100",
            port=8821,
            timestamp=int(time.time()),
            nonce=nonce,
        )

        with pytest.raises(InvalidTag):
            receiver.decrypt_ip_notification(encrypted)

    def test_ntfy_flow_preserves_timestamp_and_nonce(self):
        """Timestamp and nonce are preserved for replay protection at app layer."""
        import os

        secret = generate_secret()
        keys = derive_keys(secret)

        crypto = NtfyCrypto(ntfy_key=keys.ntfy_key)

        timestamp = int(time.time())
        nonce = os.urandom(16)

        encrypted = crypto.encrypt_ip_notification(
            ip="192.168.1.100",
            port=8821,
            timestamp=timestamp,
            nonce=nonce,
        )

        decrypted = crypto.decrypt_ip_notification(encrypted)

        # App layer can use these for replay/expiration checks
        assert decrypted.timestamp == timestamp
        assert decrypted.nonce == nonce


class TestDeviceStorageSecurityFlow:
    """Test secure device storage."""

    def test_storage_flow_save_load(self, tmp_path):
        """Devices can be saved and loaded securely."""
        secret = generate_secret()
        storage = DeviceStorage(tmp_path)

        device = Device(
            id="phone123",
            name="My Phone",
            secret=secret,
        )
        storage.save(device)

        loaded = storage.load("phone123")
        assert loaded.secret == secret

    def test_storage_flow_file_permissions(self, tmp_path):
        """Device files have restricted permissions."""
        storage = DeviceStorage(tmp_path)
        device = Device(id="test", name="Test", secret=b"x" * 32)
        storage.save(device)

        file_path = tmp_path / "device-test.json"
        mode = file_path.stat().st_mode & 0o777
        assert mode == 0o600  # Owner read/write only

    def test_storage_flow_directory_permissions(self, tmp_path):
        """Storage directory has restricted permissions."""
        dir_path = tmp_path / "secure"
        storage = DeviceStorage(dir_path)

        mode = dir_path.stat().st_mode & 0o777
        assert mode == 0o700  # Owner only

    def test_storage_flow_path_traversal_blocked(self, tmp_path):
        """Path traversal attempts are blocked."""
        storage = DeviceStorage(tmp_path)

        from ras.errors import StorageError
        with pytest.raises(StorageError, match="Invalid"):
            storage.save(Device(id="../evil", name="Evil", secret=b"x" * 32))


class TestCompleteSecurityLifecycle:
    """Test complete security lifecycle from pairing to message exchange."""

    def test_complete_lifecycle(self, tmp_path):
        """Full lifecycle: pairing -> auth -> encrypted messaging."""
        # Step 1: Generate pairing info
        secret = generate_secret()
        info = ConnectionInfo(ip="192.168.1.100", port=8821, secret=secret)

        # Step 2: Both sides derive keys
        daemon_keys = derive_keys(secret)
        phone_keys = derive_keys(secret)

        # Step 3: Store device
        storage = DeviceStorage(tmp_path)
        device = Device(id="myphone", name="My Phone", secret=secret)
        storage.save(device)

        # Step 4: Authenticate
        daemon_auth = Authenticator(auth_key=daemon_keys.auth_key, role="daemon")
        phone_auth = Authenticator(auth_key=phone_keys.auth_key, role="phone")

        challenge = daemon_auth.create_challenge()
        response = phone_auth.respond_to_challenge(challenge)
        assert daemon_auth.verify_response(response) is True
        verify = daemon_auth.create_verify(response["nonce"])
        assert phone_auth.verify_verify(verify) is True

        # Step 5: Exchange encrypted messages
        daemon_codec = MessageCodec(encrypt_key=daemon_keys.encrypt_key)
        phone_codec = MessageCodec(encrypt_key=phone_keys.encrypt_key)

        # Daemon sends command
        cmd = Message(type="command", payload={"action": "ls"})
        encrypted = daemon_codec.encode(cmd)
        received = phone_codec.decode(encrypted)
        assert received.payload["action"] == "ls"

        # Phone sends response
        resp = Message(type="output", payload={"stdout": "file1.txt\nfile2.txt"})
        encrypted = phone_codec.encode(resp)
        received = daemon_codec.decode(encrypted)
        assert "file1.txt" in received.payload["stdout"]

    def test_multi_device_scenario(self, tmp_path):
        """Multiple devices can be paired independently."""
        storage = DeviceStorage(tmp_path)

        # Pair two devices
        phone1_secret = generate_secret()
        phone2_secret = generate_secret()

        storage.save(Device(id="phone1", name="Phone 1", secret=phone1_secret))
        storage.save(Device(id="phone2", name="Phone 2", secret=phone2_secret))

        # Each device has independent keys
        keys1 = derive_keys(phone1_secret)
        keys2 = derive_keys(phone2_secret)

        assert keys1.auth_key != keys2.auth_key
        assert keys1.encrypt_key != keys2.encrypt_key

        # Messages from phone1 can't be decrypted with phone2's key
        codec1 = MessageCodec(encrypt_key=keys1.encrypt_key)
        codec2 = MessageCodec(encrypt_key=keys2.encrypt_key)

        msg = Message(type="test", payload={})
        encrypted = codec1.encode(msg)

        with pytest.raises(MessageError):
            codec2.decode(encrypted)


class TestSecurityEdgeCases:
    """Test security edge cases and boundary conditions."""

    def test_empty_payload_encryption(self):
        """Empty payloads are handled correctly."""
        secret = generate_secret()
        keys = derive_keys(secret)
        codec = MessageCodec(encrypt_key=keys.encrypt_key)

        msg = Message(type="ping", payload={})
        encrypted = codec.encode(msg)
        decrypted = codec.decode(encrypted)
        assert decrypted.payload == {}

    def test_large_payload_encryption(self):
        """Large payloads are handled correctly."""
        secret = generate_secret()
        keys = derive_keys(secret)
        codec = MessageCodec(encrypt_key=keys.encrypt_key)

        large_data = "x" * 100000
        msg = Message(type="output", payload={"data": large_data})
        encrypted = codec.encode(msg)
        decrypted = codec.decode(encrypted)
        assert decrypted.payload["data"] == large_data

    def test_unicode_in_payload(self):
        """Unicode in payloads is handled correctly."""
        secret = generate_secret()
        keys = derive_keys(secret)
        codec = MessageCodec(encrypt_key=keys.encrypt_key)

        msg = Message(type="output", payload={"text": "Hello, 世界! 🌍"})
        encrypted = codec.encode(msg)
        decrypted = codec.decode(encrypted)
        assert decrypted.payload["text"] == "Hello, 世界! 🌍"

    def test_concurrent_codecs_independent(self):
        """Multiple codecs track state independently."""
        secret = generate_secret()
        keys = derive_keys(secret)

        codec1 = MessageCodec(encrypt_key=keys.encrypt_key)
        codec2 = MessageCodec(encrypt_key=keys.encrypt_key)

        # Each codec has its own sequence counter
        msg1 = Message(type="test", payload={})
        msg2 = Message(type="test", payload={})

        codec1.encode(msg1)
        codec2.encode(msg2)

        assert msg1.seq == 1
        assert msg2.seq == 1

    def test_hmac_timing_safety(self):
        """HMAC verification is timing-safe."""
        key = b"x" * 32
        data = b"test data"

        correct_mac = compute_hmac(key, data)
        wrong_mac = b"y" * 32

        # Both should complete in similar time (no early exit on mismatch)
        # This is a functional test - actual timing tests require specialized tools
        assert verify_hmac(key, data, correct_mac) is True
        assert verify_hmac(key, data, wrong_mac) is False

    def test_nonce_uniqueness_over_many_operations(self):
        """Nonces remain unique over many operations."""
        secret = generate_secret()
        keys = derive_keys(secret)
        codec = MessageCodec(encrypt_key=keys.encrypt_key)

        # Generate many encrypted messages
        seen_nonces = set()
        for _ in range(100):
            msg = Message(type="test", payload={})
            encrypted = codec.encode(msg)
            # Extract nonce (first 12 bytes)
            nonce = encrypted[:12]
            assert nonce not in seen_nonces
            seen_nonces.add(nonce)
