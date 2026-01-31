"""Tests for authentication handler."""

import asyncio
from unittest.mock import AsyncMock, patch, MagicMock
from contextlib import asynccontextmanager

import pytest

from ras.pairing.auth_handler import AuthHandler
from ras.crypto import derive_key, compute_hmac
from ras.proto.ras import (
    AuthEnvelope,
    AuthChallenge,
    AuthResponse,
    AuthVerify,
    AuthSuccess,
    AuthError,
    AuthErrorErrorCode,
)


class TestAuthHandlerSuccess:
    """Tests for successful authentication."""

    @pytest.fixture
    def auth_key(self):
        """Create test auth key."""
        master_secret = b"\x01" * 32
        return derive_key(master_secret, "auth")

    @pytest.mark.asyncio
    async def test_successful_handshake(self, auth_key):
        """Complete successful handshake."""
        handler = AuthHandler(auth_key, "test-device")

        messages_sent = []
        messages_to_receive = asyncio.Queue()

        async def send(data):
            messages_sent.append(data)
            # After challenge is sent, queue the response
            if len(messages_sent) == 1:
                # Parse challenge
                envelope = AuthEnvelope().parse(data)
                server_nonce = envelope.challenge.nonce

                # Create valid response
                client_hmac = compute_hmac(auth_key, server_nonce)
                client_nonce = b"\xaa" * 32
                response = AuthEnvelope(
                    response=AuthResponse(
                        hmac=client_hmac,
                        nonce=client_nonce,
                    )
                )
                await messages_to_receive.put(bytes(response))

        async def receive():
            return await messages_to_receive.get()

        result = await handler.run_handshake(send, receive)

        assert result is True
        assert handler.is_authenticated
        # Should have sent: challenge, verify, success
        assert len(messages_sent) == 3

    @pytest.mark.asyncio
    async def test_sends_challenge_first(self, auth_key):
        """First message is AuthChallenge."""
        handler = AuthHandler(auth_key, "test-device")

        messages_sent = []

        async def send(data):
            messages_sent.append(data)
            if len(messages_sent) == 1:
                # Stop after challenge
                raise asyncio.CancelledError()

        async def receive():
            # Never called - send raises first
            raise RuntimeError("Should not be called")

        try:
            await handler.run_handshake(send, receive)
        except asyncio.CancelledError:
            pass

        # Parse first message
        envelope = AuthEnvelope().parse(messages_sent[0])
        assert envelope.challenge is not None
        assert len(envelope.challenge.nonce) == 32

    @pytest.mark.asyncio
    async def test_challenge_nonce_is_random(self, auth_key):
        """Challenge nonces are random each time."""
        nonces = []

        for _ in range(5):
            handler = AuthHandler(auth_key, "test-device")
            messages_sent = []

            async def send(data):
                messages_sent.append(data)
                raise asyncio.CancelledError()

            async def receive():
                # Never called - send raises first
                raise RuntimeError("Should not be called")

            try:
                await handler.run_handshake(send, receive)
            except asyncio.CancelledError:
                pass

            envelope = AuthEnvelope().parse(messages_sent[0])
            nonces.append(envelope.challenge.nonce)

        assert len(set(nonces)) == 5


class TestAuthHandlerErrors:
    """Tests for authentication errors."""

    @pytest.fixture
    def auth_key(self):
        """Create test auth key."""
        master_secret = b"\x01" * 32
        return derive_key(master_secret, "auth")

    @pytest.mark.asyncio
    async def test_wrong_hmac_fails(self, auth_key):
        """Wrong client HMAC fails authentication."""
        handler = AuthHandler(auth_key, "test-device")

        messages_sent = []
        messages_to_receive = asyncio.Queue()

        async def send(data):
            messages_sent.append(data)
            if len(messages_sent) == 1:
                # Send response with wrong HMAC
                response = AuthEnvelope(
                    response=AuthResponse(
                        hmac=b"\x00" * 32,  # Wrong!
                        nonce=b"\xaa" * 32,
                    )
                )
                await messages_to_receive.put(bytes(response))

        async def receive():
            return await messages_to_receive.get()

        result = await handler.run_handshake(send, receive)

        assert result is False
        assert not handler.is_authenticated

        # Should have sent: challenge, error
        assert len(messages_sent) == 2
        error_envelope = AuthEnvelope().parse(messages_sent[-1])
        assert error_envelope.error is not None
        assert error_envelope.error.code == AuthErrorErrorCode.INVALID_HMAC

    @pytest.mark.asyncio
    async def test_wrong_nonce_length_fails(self, auth_key):
        """Wrong nonce length fails authentication."""
        handler = AuthHandler(auth_key, "test-device")

        messages_sent = []
        messages_to_receive = asyncio.Queue()

        async def send(data):
            messages_sent.append(data)
            if len(messages_sent) == 1:
                envelope = AuthEnvelope().parse(data)
                server_nonce = envelope.challenge.nonce
                client_hmac = compute_hmac(auth_key, server_nonce)

                # Send response with wrong nonce length
                response = AuthEnvelope(
                    response=AuthResponse(
                        hmac=client_hmac,
                        nonce=b"\xaa" * 16,  # Too short!
                    )
                )
                await messages_to_receive.put(bytes(response))

        async def receive():
            return await messages_to_receive.get()

        result = await handler.run_handshake(send, receive)

        assert result is False
        error_envelope = AuthEnvelope().parse(messages_sent[-1])
        assert error_envelope.error.code == AuthErrorErrorCode.INVALID_NONCE

    @pytest.mark.asyncio
    async def test_unexpected_message_fails(self, auth_key):
        """Unexpected message type fails with protocol error."""
        handler = AuthHandler(auth_key, "test-device")

        messages_sent = []
        messages_to_receive = asyncio.Queue()

        async def send(data):
            messages_sent.append(data)
            if len(messages_sent) == 1:
                # Send verify instead of response
                response = AuthEnvelope(
                    verify=AuthVerify(hmac=b"\x00" * 32)
                )
                await messages_to_receive.put(bytes(response))

        async def receive():
            return await messages_to_receive.get()

        result = await handler.run_handshake(send, receive)

        assert result is False
        error_envelope = AuthEnvelope().parse(messages_sent[-1])
        assert error_envelope.error.code == AuthErrorErrorCode.PROTOCOL_ERROR

    @pytest.mark.asyncio
    async def test_timeout_fails(self, auth_key):
        """Timeout fails authentication."""
        handler = AuthHandler(auth_key, "test-device")

        messages_sent = []

        async def send(data):
            messages_sent.append(data)

        async def receive():
            raise asyncio.TimeoutError()

        # Mock asyncio.timeout to raise immediately
        @asynccontextmanager
        async def mock_timeout(seconds):
            yield

        with patch("ras.pairing.auth_handler.asyncio.timeout", mock_timeout):
            result = await handler.run_handshake(send, receive)

        assert result is False
        # Should send error on timeout
        if len(messages_sent) > 1:
            error_envelope = AuthEnvelope().parse(messages_sent[-1])
            assert error_envelope.error.code == AuthErrorErrorCode.TIMEOUT


class TestAuthHandlerVerifyMessage:
    """Tests for the verify message sent by server."""

    @pytest.fixture
    def auth_key(self):
        """Create test auth key."""
        master_secret = b"\x01" * 32
        return derive_key(master_secret, "auth")

    @pytest.mark.asyncio
    async def test_verify_contains_correct_hmac(self, auth_key):
        """Server verify message contains correct HMAC of client nonce."""
        handler = AuthHandler(auth_key, "test-device")

        messages_sent = []
        messages_to_receive = asyncio.Queue()
        client_nonce = b"\xbb" * 32

        async def send(data):
            messages_sent.append(data)
            if len(messages_sent) == 1:
                # Send valid response
                envelope = AuthEnvelope().parse(data)
                server_nonce = envelope.challenge.nonce
                client_hmac = compute_hmac(auth_key, server_nonce)
                response = AuthEnvelope(
                    response=AuthResponse(
                        hmac=client_hmac,
                        nonce=client_nonce,
                    )
                )
                await messages_to_receive.put(bytes(response))

        async def receive():
            return await messages_to_receive.get()

        await handler.run_handshake(send, receive)

        # Check verify message (second message)
        verify_envelope = AuthEnvelope().parse(messages_sent[1])
        expected_hmac = compute_hmac(auth_key, client_nonce)
        assert verify_envelope.verify.hmac == expected_hmac


class TestAuthHandlerSuccessMessage:
    """Tests for the success message."""

    @pytest.fixture
    def auth_key(self):
        """Create test auth key."""
        master_secret = b"\x01" * 32
        return derive_key(master_secret, "auth")

    @pytest.mark.asyncio
    async def test_success_contains_device_id(self, auth_key):
        """Success message contains device ID."""
        handler = AuthHandler(auth_key, "my-device-123")

        messages_sent = []
        messages_to_receive = asyncio.Queue()

        async def send(data):
            messages_sent.append(data)
            if len(messages_sent) == 1:
                envelope = AuthEnvelope().parse(data)
                server_nonce = envelope.challenge.nonce
                client_hmac = compute_hmac(auth_key, server_nonce)
                response = AuthEnvelope(
                    response=AuthResponse(
                        hmac=client_hmac,
                        nonce=b"\xaa" * 32,
                    )
                )
                await messages_to_receive.put(bytes(response))

        async def receive():
            return await messages_to_receive.get()

        await handler.run_handshake(send, receive)

        # Check success message (third message)
        success_envelope = AuthEnvelope().parse(messages_sent[2])
        assert success_envelope.success.device_id == "my-device-123"
