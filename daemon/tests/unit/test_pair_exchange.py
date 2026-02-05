"""Tests for pairing credential exchange (decoupled from WebRTC).

Tests cover:
1. PairRequest/PairResponse HMAC validation
2. HTTP pair-complete endpoint
3. ntfy PAIR_REQUEST handler
4. Error cases: invalid HMAC, wrong nonce, wrong session_id
"""

import asyncio
import os
import time
from unittest.mock import AsyncMock, MagicMock, patch

import pytest

from ras.crypto import (
    PAIR_NONCE_LENGTH,
    compute_pair_request_hmac,
    compute_pair_response_hmac,
    derive_key,
    derive_ntfy_topic,
    derive_session_id,
    generate_master_secret,
)
from ras.proto.ras import PairRequest, PairResponse


class MockDeviceStore:
    """Mock device store for testing."""

    def __init__(self):
        self.devices = {}

    async def add_device(self, device_id: str, device_name: str, master_secret: bytes):
        self.devices[device_id] = {
            "device_id": device_id,
            "device_name": device_name,
            "master_secret": master_secret,
        }

    async def save(self):
        pass

    def get(self, device_id: str):
        return self.devices.get(device_id)


class TestPairHmacCrypto:
    """Tests for pairing HMAC functions."""

    def test_pair_request_hmac_deterministic(self):
        """Same inputs produce same HMAC."""
        auth_key = os.urandom(32)
        session_id = "test-session"
        device_id = "phone-123"
        nonce = os.urandom(32)

        hmac1 = compute_pair_request_hmac(auth_key, session_id, device_id, nonce)
        hmac2 = compute_pair_request_hmac(auth_key, session_id, device_id, nonce)
        assert hmac1 == hmac2

    def test_pair_request_hmac_different_keys(self):
        """Different auth keys produce different HMACs."""
        session_id = "test-session"
        device_id = "phone-123"
        nonce = os.urandom(32)

        hmac1 = compute_pair_request_hmac(os.urandom(32), session_id, device_id, nonce)
        hmac2 = compute_pair_request_hmac(os.urandom(32), session_id, device_id, nonce)
        assert hmac1 != hmac2

    def test_pair_request_hmac_different_nonces(self):
        """Different nonces produce different HMACs."""
        auth_key = os.urandom(32)
        session_id = "test-session"
        device_id = "phone-123"

        hmac1 = compute_pair_request_hmac(auth_key, session_id, device_id, os.urandom(32))
        hmac2 = compute_pair_request_hmac(auth_key, session_id, device_id, os.urandom(32))
        assert hmac1 != hmac2

    def test_pair_request_hmac_different_device_ids(self):
        """Different device IDs produce different HMACs."""
        auth_key = os.urandom(32)
        session_id = "test-session"
        nonce = os.urandom(32)

        hmac1 = compute_pair_request_hmac(auth_key, session_id, "phone-1", nonce)
        hmac2 = compute_pair_request_hmac(auth_key, session_id, "phone-2", nonce)
        assert hmac1 != hmac2

    def test_pair_request_hmac_length(self):
        """HMAC is 32 bytes (SHA-256)."""
        hmac = compute_pair_request_hmac(os.urandom(32), "s", "d", os.urandom(32))
        assert len(hmac) == 32

    def test_pair_response_hmac_deterministic(self):
        """Same inputs produce same response HMAC."""
        auth_key = os.urandom(32)
        nonce = os.urandom(32)

        hmac1 = compute_pair_response_hmac(auth_key, nonce)
        hmac2 = compute_pair_response_hmac(auth_key, nonce)
        assert hmac1 == hmac2

    def test_pair_response_hmac_different_keys(self):
        """Different auth keys produce different response HMACs."""
        nonce = os.urandom(32)

        hmac1 = compute_pair_response_hmac(os.urandom(32), nonce)
        hmac2 = compute_pair_response_hmac(os.urandom(32), nonce)
        assert hmac1 != hmac2

    def test_pair_request_and_response_hmacs_different(self):
        """Request and response HMACs are different (different prefixes)."""
        auth_key = os.urandom(32)
        nonce = os.urandom(32)

        req_hmac = compute_pair_request_hmac(auth_key, "session", "device", nonce)
        resp_hmac = compute_pair_response_hmac(auth_key, nonce)
        assert req_hmac != resp_hmac

    def test_mutual_verification(self):
        """Both sides can verify each other's HMACs."""
        master_secret = generate_master_secret()
        auth_key = derive_key(master_secret, "auth")
        session_id = derive_session_id(master_secret)
        device_id = "phone-123"
        nonce = os.urandom(PAIR_NONCE_LENGTH)

        # Phone computes request HMAC
        req_hmac = compute_pair_request_hmac(auth_key, session_id, device_id, nonce)

        # Daemon verifies request HMAC
        expected = compute_pair_request_hmac(auth_key, session_id, device_id, nonce)
        assert req_hmac == expected

        # Daemon computes response HMAC
        resp_hmac = compute_pair_response_hmac(auth_key, nonce)

        # Phone verifies response HMAC
        expected = compute_pair_response_hmac(auth_key, nonce)
        assert resp_hmac == expected


class TestPairCompleteEndpoint:
    """Tests for POST /api/pair/{session_id}/complete endpoint."""

    @pytest.mark.asyncio
    async def test_valid_pair_request_completes(self):
        """Valid PairRequest completes pairing and returns PairResponse."""
        from ras.server import PairingSession, UnifiedServer

        device_store = MockDeviceStore()
        server = UnifiedServer(device_store=device_store)

        # Create a pairing session
        master_secret = generate_master_secret()
        session_id = derive_session_id(master_secret)
        auth_key = derive_key(master_secret, "auth")

        session = PairingSession(
            session_id=session_id,
            master_secret=master_secret,
            auth_key=auth_key,
            ntfy_topic=derive_ntfy_topic(master_secret),
            created_at=time.time(),
            expires_at=time.time() + 300,
            state="pending",
        )
        server._pairing_sessions[session_id] = session

        # Build PairRequest
        nonce = os.urandom(PAIR_NONCE_LENGTH)
        auth_proof = compute_pair_request_hmac(auth_key, session_id, "phone-1", nonce)

        pair_req = PairRequest(
            device_id="phone-1",
            device_name="My Phone",
            auth_proof=auth_proof,
            nonce=nonce,
            session_id=session_id,
        )

        # Call _complete_pairing_exchange directly
        pair_resp = await server._complete_pairing_exchange(
            session, "phone-1", "My Phone", nonce
        )

        # Verify response
        assert pair_resp.daemon_device_id != ""
        assert pair_resp.hostname != ""
        assert len(pair_resp.auth_proof) == 32

        # Verify response HMAC is valid
        expected_resp_hmac = compute_pair_response_hmac(auth_key, nonce)
        assert pair_resp.auth_proof == expected_resp_hmac

        # Verify session state
        assert session.state == "completed"
        assert session.device_id == "phone-1"
        assert session.device_name == "My Phone"

        # Verify device was stored
        assert "phone-1" in device_store.devices

    @pytest.mark.asyncio
    async def test_invalid_hmac_rejected(self):
        """PairRequest with wrong HMAC is rejected."""
        from ras.server import PairingSession, UnifiedServer

        device_store = MockDeviceStore()
        server = UnifiedServer(device_store=device_store)

        master_secret = generate_master_secret()
        session_id = derive_session_id(master_secret)
        auth_key = derive_key(master_secret, "auth")

        session = PairingSession(
            session_id=session_id,
            master_secret=master_secret,
            auth_key=auth_key,
            ntfy_topic=derive_ntfy_topic(master_secret),
            created_at=time.time(),
            expires_at=time.time() + 300,
            state="pending",
        )
        server._pairing_sessions[session_id] = session

        # Build PairRequest with WRONG auth proof
        nonce = os.urandom(PAIR_NONCE_LENGTH)
        wrong_proof = os.urandom(32)  # Random bytes, not a valid HMAC

        pair_req = PairRequest(
            device_id="phone-1",
            device_name="My Phone",
            auth_proof=wrong_proof,
            nonce=nonce,
            session_id=session_id,
        )

        # Simulate HTTP request
        request = MagicMock()
        request.match_info = {"session_id": session_id}
        request.read = AsyncMock(return_value=bytes(pair_req))
        request.remote = "127.0.0.1"

        response = await server._handle_pair_complete(request)

        # Should fail with auth error
        assert response.status == 400
        assert session.state == "failed"
        assert "phone-1" not in device_store.devices

    @pytest.mark.asyncio
    async def test_wrong_session_id_rejected(self):
        """PairRequest with mismatched session_id is rejected."""
        from ras.server import PairingSession, UnifiedServer

        device_store = MockDeviceStore()
        server = UnifiedServer(device_store=device_store)

        master_secret = generate_master_secret()
        session_id = derive_session_id(master_secret)
        auth_key = derive_key(master_secret, "auth")

        session = PairingSession(
            session_id=session_id,
            master_secret=master_secret,
            auth_key=auth_key,
            ntfy_topic=derive_ntfy_topic(master_secret),
            created_at=time.time(),
            expires_at=time.time() + 300,
            state="pending",
        )
        server._pairing_sessions[session_id] = session

        nonce = os.urandom(PAIR_NONCE_LENGTH)
        # Use wrong session_id in the HMAC
        wrong_session = "wrong-session-id"
        auth_proof = compute_pair_request_hmac(auth_key, wrong_session, "phone-1", nonce)

        pair_req = PairRequest(
            device_id="phone-1",
            device_name="My Phone",
            auth_proof=auth_proof,
            nonce=nonce,
            session_id=wrong_session,
        )

        request = MagicMock()
        request.match_info = {"session_id": session_id}
        request.read = AsyncMock(return_value=bytes(pair_req))
        request.remote = "127.0.0.1"

        response = await server._handle_pair_complete(request)
        assert response.status == 400

    @pytest.mark.asyncio
    async def test_wrong_nonce_length_rejected(self):
        """PairRequest with wrong nonce length is rejected."""
        from ras.server import PairingSession, UnifiedServer

        device_store = MockDeviceStore()
        server = UnifiedServer(device_store=device_store)

        master_secret = generate_master_secret()
        session_id = derive_session_id(master_secret)
        auth_key = derive_key(master_secret, "auth")

        session = PairingSession(
            session_id=session_id,
            master_secret=master_secret,
            auth_key=auth_key,
            ntfy_topic=derive_ntfy_topic(master_secret),
            created_at=time.time(),
            expires_at=time.time() + 300,
            state="pending",
        )
        server._pairing_sessions[session_id] = session

        short_nonce = os.urandom(16)  # Wrong length
        auth_proof = compute_pair_request_hmac(auth_key, session_id, "phone-1", short_nonce)

        pair_req = PairRequest(
            device_id="phone-1",
            device_name="My Phone",
            auth_proof=auth_proof,
            nonce=short_nonce,
            session_id=session_id,
        )

        request = MagicMock()
        request.match_info = {"session_id": session_id}
        request.read = AsyncMock(return_value=bytes(pair_req))
        request.remote = "127.0.0.1"

        response = await server._handle_pair_complete(request)
        assert response.status == 400

    @pytest.mark.asyncio
    async def test_expired_session_rejected(self):
        """PairRequest for expired session is rejected."""
        from ras.server import PairingSession, UnifiedServer

        device_store = MockDeviceStore()
        server = UnifiedServer(device_store=device_store)

        master_secret = generate_master_secret()
        session_id = derive_session_id(master_secret)
        auth_key = derive_key(master_secret, "auth")

        session = PairingSession(
            session_id=session_id,
            master_secret=master_secret,
            auth_key=auth_key,
            ntfy_topic=derive_ntfy_topic(master_secret),
            created_at=time.time() - 600,
            expires_at=time.time() - 1,  # Already expired
            state="pending",
        )
        server._pairing_sessions[session_id] = session

        nonce = os.urandom(PAIR_NONCE_LENGTH)
        auth_proof = compute_pair_request_hmac(auth_key, session_id, "phone-1", nonce)

        pair_req = PairRequest(
            device_id="phone-1",
            device_name="My Phone",
            auth_proof=auth_proof,
            nonce=nonce,
            session_id=session_id,
        )

        request = MagicMock()
        request.match_info = {"session_id": session_id}
        request.read = AsyncMock(return_value=bytes(pair_req))
        request.remote = "127.0.0.1"

        response = await server._handle_pair_complete(request)
        assert response.status == 400

    @pytest.mark.asyncio
    async def test_pairing_callback_called(self):
        """on_pairing_complete callback is called after successful pairing."""
        from ras.server import PairingSession, UnifiedServer

        device_store = MockDeviceStore()
        callback_args = []

        async def on_pairing_complete(device_id, device_name):
            callback_args.append((device_id, device_name))

        server = UnifiedServer(
            device_store=device_store,
            on_pairing_complete=on_pairing_complete,
        )

        master_secret = generate_master_secret()
        session_id = derive_session_id(master_secret)
        auth_key = derive_key(master_secret, "auth")

        session = PairingSession(
            session_id=session_id,
            master_secret=master_secret,
            auth_key=auth_key,
            ntfy_topic=derive_ntfy_topic(master_secret),
            created_at=time.time(),
            expires_at=time.time() + 300,
            state="pending",
        )
        server._pairing_sessions[session_id] = session

        nonce = os.urandom(PAIR_NONCE_LENGTH)
        await server._complete_pairing_exchange(session, "phone-1", "My Phone", nonce)

        assert len(callback_args) == 1
        assert callback_args[0] == ("phone-1", "My Phone")


class TestNtfyPairRequestHandler:
    """Tests for PAIR_REQUEST handling in ntfy handler."""

    @pytest.mark.asyncio
    async def test_pair_request_in_reconnection_mode_rejected(self):
        """PAIR_REQUEST is rejected when handler is in reconnection mode."""
        from ras.ntfy_signaling.handler import NtfySignalingHandler

        master_secret = generate_master_secret()
        handler = NtfySignalingHandler(
            master_secret=master_secret,
            pending_session_id="",  # Reconnection mode
        )

        # Create encrypted PAIR_REQUEST
        from ras.ntfy_signaling.crypto import NtfySignalingCrypto, derive_signaling_key
        from ras.ntfy_signaling.validation import NONCE_SIZE
        from ras.proto.ras.ras import NtfySignalMessage, NtfySignalMessageMessageType

        session_id = derive_session_id(master_secret)
        auth_key = derive_key(master_secret, "auth")
        nonce = os.urandom(PAIR_NONCE_LENGTH)

        pair_req = PairRequest(
            device_id="phone-1",
            device_name="Phone",
            auth_proof=compute_pair_request_hmac(auth_key, session_id, "phone-1", nonce),
            nonce=nonce,
            session_id=session_id,
        )

        msg = NtfySignalMessage(
            type=NtfySignalMessageMessageType.PAIR_REQUEST,
            session_id=session_id,
            timestamp=int(time.time()),
            nonce=os.urandom(NONCE_SIZE),
            pair_request=pair_req,
        )

        crypto = NtfySignalingCrypto(derive_signaling_key(master_secret))
        encrypted = crypto.encrypt(bytes(msg))

        result = await handler.handle_message(encrypted)
        assert result is None  # Rejected in reconnection mode

        await handler.close()

    @pytest.mark.asyncio
    async def test_pair_request_wrong_session_rejected(self):
        """PAIR_REQUEST with wrong session_id is rejected."""
        from ras.ntfy_signaling.handler import NtfySignalingHandler
        from ras.ntfy_signaling.crypto import NtfySignalingCrypto, derive_signaling_key
        from ras.ntfy_signaling.validation import NONCE_SIZE
        from ras.proto.ras.ras import NtfySignalMessage, NtfySignalMessageMessageType

        master_secret = generate_master_secret()
        session_id = derive_session_id(master_secret)
        auth_key = derive_key(master_secret, "auth")

        handler = NtfySignalingHandler(
            master_secret=master_secret,
            pending_session_id=session_id,
        )

        nonce = os.urandom(PAIR_NONCE_LENGTH)
        wrong_session = "wrong-session"

        pair_req = PairRequest(
            device_id="phone-1",
            device_name="Phone",
            auth_proof=compute_pair_request_hmac(auth_key, wrong_session, "phone-1", nonce),
            nonce=nonce,
            session_id=wrong_session,
        )

        msg = NtfySignalMessage(
            type=NtfySignalMessageMessageType.PAIR_REQUEST,
            session_id=session_id,
            timestamp=int(time.time()),
            nonce=os.urandom(NONCE_SIZE),
            pair_request=pair_req,
        )

        crypto = NtfySignalingCrypto(derive_signaling_key(master_secret))
        encrypted = crypto.encrypt(bytes(msg))

        result = await handler.handle_message(encrypted)
        assert result is None  # Rejected

        await handler.close()

    @pytest.mark.asyncio
    async def test_pair_request_wrong_hmac_rejected(self):
        """PAIR_REQUEST with invalid auth_proof is rejected."""
        from ras.ntfy_signaling.handler import NtfySignalingHandler
        from ras.ntfy_signaling.crypto import NtfySignalingCrypto, derive_signaling_key
        from ras.ntfy_signaling.validation import NONCE_SIZE
        from ras.proto.ras.ras import NtfySignalMessage, NtfySignalMessageMessageType

        master_secret = generate_master_secret()
        session_id = derive_session_id(master_secret)

        handler = NtfySignalingHandler(
            master_secret=master_secret,
            pending_session_id=session_id,
        )

        nonce = os.urandom(PAIR_NONCE_LENGTH)

        pair_req = PairRequest(
            device_id="phone-1",
            device_name="Phone",
            auth_proof=os.urandom(32),  # Random, not valid HMAC
            nonce=nonce,
            session_id=session_id,
        )

        msg = NtfySignalMessage(
            type=NtfySignalMessageMessageType.PAIR_REQUEST,
            session_id=session_id,
            timestamp=int(time.time()),
            nonce=os.urandom(NONCE_SIZE),
            pair_request=pair_req,
        )

        crypto = NtfySignalingCrypto(derive_signaling_key(master_secret))
        encrypted = crypto.encrypt(bytes(msg))

        result = await handler.handle_message(encrypted)
        assert result is None  # Rejected

        await handler.close()

    @pytest.mark.asyncio
    async def test_valid_pair_request_returns_response(self):
        """Valid PAIR_REQUEST returns HandlerResult with PAIR_RESPONSE."""
        from ras.ntfy_signaling.handler import NtfySignalingHandler
        from ras.ntfy_signaling.crypto import NtfySignalingCrypto, derive_signaling_key
        from ras.ntfy_signaling.validation import NONCE_SIZE
        from ras.proto.ras.ras import NtfySignalMessage, NtfySignalMessageMessageType

        master_secret = generate_master_secret()
        session_id = derive_session_id(master_secret)
        auth_key = derive_key(master_secret, "auth")

        handler = NtfySignalingHandler(
            master_secret=master_secret,
            pending_session_id=session_id,
        )

        nonce = os.urandom(PAIR_NONCE_LENGTH)
        auth_proof = compute_pair_request_hmac(auth_key, session_id, "phone-1", nonce)

        pair_req = PairRequest(
            device_id="phone-1",
            device_name="Test Phone",
            auth_proof=auth_proof,
            nonce=nonce,
            session_id=session_id,
        )

        msg = NtfySignalMessage(
            type=NtfySignalMessageMessageType.PAIR_REQUEST,
            session_id=session_id,
            timestamp=int(time.time()),
            nonce=os.urandom(NONCE_SIZE),
            pair_request=pair_req,
        )

        crypto = NtfySignalingCrypto(derive_signaling_key(master_secret))
        encrypted = crypto.encrypt(bytes(msg))

        result = await handler.handle_message(encrypted)

        assert result is not None
        assert result.should_respond is True
        assert result.is_pair_complete is True
        assert result.peer is None  # No WebRTC
        assert result.device_id == "phone-1"
        assert result.device_name == "Test Phone"

        # Decrypt the response
        response_msg = NtfySignalMessage().parse(crypto.decrypt(result.answer_encrypted))
        assert response_msg.type == NtfySignalMessageMessageType.PAIR_RESPONSE
        assert response_msg.pair_response.daemon_device_id != ""
        assert len(response_msg.pair_response.auth_proof) == 32

        # Verify response HMAC
        expected_resp_hmac = compute_pair_response_hmac(auth_key, nonce)
        assert response_msg.pair_response.auth_proof == expected_resp_hmac

        await handler.close()
