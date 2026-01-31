"""Tests for reconnection authentication flow.

Verifies that the daemon properly authenticates reconnecting devices:
1. Auth handshake runs on device reconnection
2. Tailscale capabilities are included in ANSWER messages
3. Failed auth rejects the connection
"""

import pytest
from unittest.mock import AsyncMock, MagicMock, patch, call

from ras.pairing.auth_handler import AuthHandler
from ras.proto.ras import AuthEnvelope, AuthChallenge, AuthResponse, AuthVerify


class TestReconnectionAuthHandshake:
    """Tests that auth handshake runs on reconnection."""

    @pytest.fixture
    def auth_key(self):
        """32-byte auth key for testing."""
        return bytes(range(32))

    @pytest.fixture
    def device_id(self):
        return "test-device-123"

    @pytest.fixture
    def auth_handler(self, auth_key, device_id):
        """Create AuthHandler for testing."""
        return AuthHandler(auth_key, device_id)

    @pytest.mark.asyncio
    async def test_auth_handler_sends_challenge_first(self, auth_handler, auth_key):
        """Daemon should send auth challenge as first message."""
        messages_sent = []

        async def mock_send(data):
            messages_sent.append(data)

        async def mock_receive():
            # Return valid response after receiving challenge
            if len(messages_sent) == 1:
                # Parse the challenge we received
                envelope = AuthEnvelope().parse(messages_sent[0])
                challenge_nonce = envelope.challenge.nonce

                # Compute HMAC response
                from ras.crypto import compute_hmac
                import secrets
                response_hmac = compute_hmac(auth_key, challenge_nonce)
                client_nonce = secrets.token_bytes(32)

                response = AuthEnvelope(
                    response=AuthResponse(
                        hmac=response_hmac,
                        nonce=client_nonce
                    )
                )
                return bytes(response)
            raise Exception("Unexpected receive call")

        await auth_handler.run_handshake(mock_send, mock_receive)

        # Verify first message was a challenge
        assert len(messages_sent) >= 1
        first_envelope = AuthEnvelope().parse(messages_sent[0])
        assert first_envelope.challenge is not None
        assert len(first_envelope.challenge.nonce) == 32

    @pytest.mark.asyncio
    async def test_auth_handler_verifies_client_hmac(self, auth_handler, auth_key):
        """Daemon should verify client's HMAC before accepting."""
        messages_sent = []

        async def mock_send(data):
            messages_sent.append(data)

        async def mock_receive():
            if len(messages_sent) == 1:
                # Send WRONG HMAC
                import secrets
                wrong_hmac = secrets.token_bytes(32)
                client_nonce = secrets.token_bytes(32)

                response = AuthEnvelope(
                    response=AuthResponse(
                        hmac=wrong_hmac,
                        nonce=client_nonce
                    )
                )
                return bytes(response)
            raise Exception("Unexpected receive call")

        result = await auth_handler.run_handshake(mock_send, mock_receive)

        # Should fail due to invalid HMAC
        assert result is False

    @pytest.mark.asyncio
    async def test_auth_handler_sends_verify_on_success(self, auth_handler, auth_key):
        """Daemon should send verify message after validating client."""
        messages_sent = []

        async def mock_send(data):
            messages_sent.append(data)

        async def mock_receive():
            if len(messages_sent) == 1:
                envelope = AuthEnvelope().parse(messages_sent[0])
                challenge_nonce = envelope.challenge.nonce

                from ras.crypto import compute_hmac
                import secrets
                response_hmac = compute_hmac(auth_key, challenge_nonce)
                client_nonce = secrets.token_bytes(32)

                response = AuthEnvelope(
                    response=AuthResponse(
                        hmac=response_hmac,
                        nonce=client_nonce
                    )
                )
                return bytes(response)
            raise Exception("Unexpected receive call")

        await auth_handler.run_handshake(mock_send, mock_receive)

        # Should have sent: challenge, verify, success
        assert len(messages_sent) == 3

        # Second message should be verify
        verify_envelope = AuthEnvelope().parse(messages_sent[1])
        assert verify_envelope.verify is not None
        assert len(verify_envelope.verify.hmac) == 32

    @pytest.mark.asyncio
    async def test_auth_handler_sends_success_message(self, auth_handler, auth_key, device_id):
        """Daemon should send success with device_id after auth."""
        messages_sent = []

        async def mock_send(data):
            messages_sent.append(data)

        async def mock_receive():
            if len(messages_sent) == 1:
                envelope = AuthEnvelope().parse(messages_sent[0])
                challenge_nonce = envelope.challenge.nonce

                from ras.crypto import compute_hmac
                import secrets
                response_hmac = compute_hmac(auth_key, challenge_nonce)
                client_nonce = secrets.token_bytes(32)

                response = AuthEnvelope(
                    response=AuthResponse(
                        hmac=response_hmac,
                        nonce=client_nonce
                    )
                )
                return bytes(response)
            raise Exception("Unexpected receive call")

        result = await auth_handler.run_handshake(mock_send, mock_receive)

        # Should succeed
        assert result is True

        # Third message should be success
        success_envelope = AuthEnvelope().parse(messages_sent[2])
        assert success_envelope.success is not None
        assert success_envelope.success.device_id == device_id


class TestDaemonReconnectionCallback:
    """Tests that daemon runs auth on reconnection callback."""

    @pytest.mark.asyncio
    async def test_on_device_reconnected_runs_auth(self):
        """_on_device_reconnected should run auth handshake."""
        from ras.daemon import Daemon
        from ras.config import Config

        # Create minimal daemon
        config = Config()
        daemon = Daemon(config)

        # Mock peer connection
        mock_peer = AsyncMock()
        mock_peer.send = AsyncMock()
        mock_peer.close = AsyncMock()
        # on_message is called with a callback function, not awaited
        mock_peer.on_message = MagicMock()

        # Make wait_connected succeed, but we'll patch the AuthHandler
        # to fail immediately to avoid waiting for auth timeout
        mock_peer.wait_connected = AsyncMock()

        auth_key = bytes(range(32))

        # Patch AuthHandler.run_handshake to return False immediately
        # This tests that auth is attempted and failure is handled
        with patch("ras.daemon.AuthHandler") as MockAuthHandler:
            mock_handler = MagicMock()
            mock_handler.run_handshake = AsyncMock(return_value=False)
            MockAuthHandler.return_value = mock_handler

            # Should attempt auth and fail gracefully
            await daemon._on_device_reconnected(
                device_id="test-device",
                device_name="Test Phone",
                peer=mock_peer,
                auth_key=auth_key
            )

            # Verify AuthHandler was created with correct args
            MockAuthHandler.assert_called_once_with(auth_key, "test-device")

            # Verify run_handshake was called
            mock_handler.run_handshake.assert_called_once()

        # Verify peer was closed on auth failure
        mock_peer.close.assert_called()


class TestTailscaleCapabilities:
    """Tests that Tailscale capabilities are included in ANSWER."""

    def test_capabilities_provider_returns_tailscale_info(self):
        """get_tailscale_capabilities should return IP and port."""
        from ras.daemon import Daemon
        from ras.config import Config
        from ras.tailscale import TailscaleListener

        config = Config()
        daemon = Daemon(config)

        # Mock Tailscale listener
        mock_listener = MagicMock(spec=TailscaleListener)
        mock_listener.is_available = True
        mock_listener.get_capabilities.return_value = {
            "tailscale_ip": "100.64.0.1",
            "tailscale_port": 9876
        }
        daemon._tailscale_listener = mock_listener

        caps = daemon.get_tailscale_capabilities()

        assert caps["tailscale_ip"] == "100.64.0.1"
        assert caps["tailscale_port"] == 9876

    def test_capabilities_empty_when_no_tailscale(self):
        """get_tailscale_capabilities returns empty when no Tailscale."""
        from ras.daemon import Daemon
        from ras.config import Config

        config = Config()
        daemon = Daemon(config)

        # No Tailscale listener
        daemon._tailscale_listener = None

        caps = daemon.get_tailscale_capabilities()

        assert caps == {}

    def test_capabilities_empty_when_tailscale_unavailable(self):
        """get_tailscale_capabilities returns empty when Tailscale unavailable."""
        from ras.daemon import Daemon
        from ras.config import Config
        from ras.tailscale import TailscaleListener

        config = Config()
        daemon = Daemon(config)

        # Mock Tailscale listener as unavailable
        mock_listener = MagicMock(spec=TailscaleListener)
        mock_listener.is_available = False
        daemon._tailscale_listener = mock_listener

        caps = daemon.get_tailscale_capabilities()

        assert caps == {}
