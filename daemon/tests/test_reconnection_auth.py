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
    """Tests for daemon reconnection callback (after auth is complete)."""

    @pytest.mark.asyncio
    async def test_on_device_reconnected_does_not_run_auth(self):
        """_on_device_reconnected should NOT run auth - server.py already did it.

        This test guards against the double-auth bug where both server.py
        and daemon.py were running authentication, causing the second auth
        to fail because the client had already switched to encrypted mode.
        """
        from ras.daemon import Daemon
        from ras.config import Config

        # Create minimal daemon
        config = Config()
        daemon = Daemon(config)

        # Mock peer connection
        mock_peer = AsyncMock()
        mock_peer.send = AsyncMock()
        mock_peer.close = AsyncMock()
        mock_peer.on_message = MagicMock()
        mock_peer.wait_connected = AsyncMock()

        auth_key = bytes(range(32))

        # Patch on_new_connection to verify it's called
        daemon.on_new_connection = AsyncMock()

        # Call the callback (auth already done by server.py)
        await daemon._on_device_reconnected(
            device_id="test-device",
            device_name="Test Phone",
            peer=mock_peer,
            auth_key=auth_key
        )

        # Verify NO auth challenge was sent (no send() for auth)
        # The callback should just set up the connection
        mock_peer.send.assert_not_called()

        # Verify on_new_connection was called (connection established)
        daemon.on_new_connection.assert_called_once()

    @pytest.mark.asyncio
    async def test_on_device_reconnected_closes_peer_on_connection_failure(self):
        """_on_device_reconnected should close peer if WebRTC connection fails."""
        from ras.daemon import Daemon
        from ras.config import Config

        config = Config()
        daemon = Daemon(config)

        mock_peer = AsyncMock()
        mock_peer.close = AsyncMock()
        mock_peer.wait_connected = AsyncMock(side_effect=Exception("ICE failed"))

        await daemon._on_device_reconnected(
            device_id="test-device",
            device_name="Test Phone",
            peer=mock_peer,
            auth_key=bytes(32)
        )

        # Verify peer was closed on connection failure
        mock_peer.close.assert_called_once()


class TestNtfyReconnectionManagerAuth:
    """Tests that NtfyReconnectionManager runs auth before calling callback."""

    @pytest.fixture
    def device_store(self):
        """Mock device store with a test device."""
        from ras.device_store import PairedDevice
        store = MagicMock()
        device = PairedDevice(
            device_id="test-device-123",
            name="Test Phone",
            master_secret=bytes(range(32)),
            paired_at="2024-01-01T00:00:00Z"
        )
        store.get.return_value = device
        return store

    @pytest.mark.asyncio
    async def test_ntfy_reconnection_runs_auth_before_callback(self, device_store):
        """NtfyReconnectionManager should run auth handshake before calling callback.

        This test guards against the bug where ntfy reconnection path was
        skipping authentication entirely, causing the Android app's auth
        attempt to timeout while daemon thought connection was established.
        """
        from ras.ntfy_signaling.reconnection_manager import NtfyReconnectionManager

        callback_called = False
        callback_args = None

        async def mock_callback(device_id, device_name, peer, auth_key):
            nonlocal callback_called, callback_args
            callback_called = True
            callback_args = (device_id, device_name, peer, auth_key)

        manager = NtfyReconnectionManager(
            device_store=device_store,
            on_reconnection=mock_callback,
        )

        # Mock peer
        mock_peer = AsyncMock()
        mock_peer.wait_connected = AsyncMock()
        mock_peer.close = AsyncMock()
        messages_sent = []

        async def mock_send(data):
            messages_sent.append(data)

        mock_peer.send = mock_send

        # Simulate client auth response
        auth_key = bytes(range(32))  # Must match device's master_secret derived key

        async def mock_receive():
            # Wait for challenge to be sent
            if len(messages_sent) == 1:
                from ras.proto.ras import AuthEnvelope, AuthResponse
                from ras.crypto import compute_hmac, derive_key

                # Parse the challenge
                envelope = AuthEnvelope().parse(messages_sent[0])
                challenge_nonce = envelope.challenge.nonce

                # Derive auth key same way manager does
                derived_auth_key = derive_key(bytes(range(32)), "auth")

                # Compute valid HMAC response
                import secrets
                response_hmac = compute_hmac(derived_auth_key, challenge_nonce)
                client_nonce = secrets.token_bytes(32)

                response = AuthEnvelope(
                    response=AuthResponse(
                        hmac=response_hmac,
                        nonce=client_nonce
                    )
                )
                return bytes(response)
            raise Exception("Unexpected receive call")

        mock_peer.receive = mock_receive

        # Call _on_offer_received (simulates ntfy OFFER processed)
        await manager._on_offer_received(
            device_id="test-device-123",
            device_name="Test Phone",
            peer=mock_peer,
            is_reconnection=True,
        )

        # Verify auth was run (challenge was sent)
        assert len(messages_sent) >= 1, "Auth challenge should have been sent"

        # Verify first message was a challenge
        from ras.proto.ras import AuthEnvelope
        first_envelope = AuthEnvelope().parse(messages_sent[0])
        assert first_envelope.challenge is not None, "First message should be auth challenge"

        # Verify callback was called after auth succeeded
        assert callback_called, "Callback should be called after successful auth"
        assert callback_args[0] == "test-device-123"

    @pytest.mark.asyncio
    async def test_ntfy_reconnection_closes_peer_on_auth_failure(self, device_store):
        """NtfyReconnectionManager should close peer if auth fails."""
        from ras.ntfy_signaling.reconnection_manager import NtfyReconnectionManager

        callback_called = False

        async def mock_callback(device_id, device_name, peer, auth_key):
            nonlocal callback_called
            callback_called = True

        manager = NtfyReconnectionManager(
            device_store=device_store,
            on_reconnection=mock_callback,
        )

        mock_peer = AsyncMock()
        mock_peer.wait_connected = AsyncMock()
        mock_peer.close = AsyncMock()
        mock_peer.send = AsyncMock()

        # Simulate invalid auth response
        async def mock_receive():
            from ras.proto.ras import AuthEnvelope, AuthResponse
            import secrets
            # Return WRONG HMAC
            response = AuthEnvelope(
                response=AuthResponse(
                    hmac=secrets.token_bytes(32),  # Wrong HMAC
                    nonce=secrets.token_bytes(32)
                )
            )
            return bytes(response)

        mock_peer.receive = mock_receive

        await manager._on_offer_received(
            device_id="test-device-123",
            device_name="Test Phone",
            peer=mock_peer,
            is_reconnection=True,
        )

        # Verify peer was closed due to auth failure
        mock_peer.close.assert_called()

        # Verify callback was NOT called
        assert not callback_called, "Callback should not be called on auth failure"

    @pytest.mark.asyncio
    async def test_ntfy_reconnection_closes_peer_on_connection_failure(self, device_store):
        """NtfyReconnectionManager should close peer if WebRTC connection fails."""
        from ras.ntfy_signaling.reconnection_manager import NtfyReconnectionManager

        callback_called = False

        async def mock_callback(device_id, device_name, peer, auth_key):
            nonlocal callback_called
            callback_called = True

        manager = NtfyReconnectionManager(
            device_store=device_store,
            on_reconnection=mock_callback,
        )

        mock_peer = AsyncMock()
        mock_peer.wait_connected = AsyncMock(side_effect=Exception("ICE failed"))
        mock_peer.close = AsyncMock()

        await manager._on_offer_received(
            device_id="test-device-123",
            device_name="Test Phone",
            peer=mock_peer,
            is_reconnection=True,
        )

        # Verify peer was closed due to connection failure
        mock_peer.close.assert_called()

        # Verify callback was NOT called
        assert not callback_called


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
