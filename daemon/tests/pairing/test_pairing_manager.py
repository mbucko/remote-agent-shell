"""Tests for pairing manager."""

import asyncio
from unittest.mock import AsyncMock, MagicMock, patch

import pytest

from ras.pairing.pairing_manager import PairingManager
from ras.pairing.session import PairingSession, PairingState


class TestPairingManagerInit:
    """Tests for pairing manager initialization."""

    def test_initializes_with_defaults(self):
        """Manager initializes with default settings."""
        stun_client = MagicMock()
        device_store = MagicMock()
        manager = PairingManager(stun_client, device_store)

        assert manager.host == "0.0.0.0"
        assert manager.port == 8821
        assert len(manager.sessions) == 0
        assert manager.signaling_server is None

    def test_custom_host_and_port(self):
        """Manager accepts custom host and port."""
        stun_client = MagicMock()
        device_store = MagicMock()
        manager = PairingManager(
            stun_client, device_store, host="127.0.0.1", port=9999
        )

        assert manager.host == "127.0.0.1"
        assert manager.port == 9999


class TestStartPairing:
    """Tests for starting pairing."""

    @pytest.fixture
    def mock_stun_client(self):
        """Create mock STUN client."""
        client = MagicMock()
        client.get_public_ip = AsyncMock(return_value="1.2.3.4")
        return client

    @pytest.fixture
    def mock_device_store(self):
        """Create mock device store."""
        store = MagicMock()
        store.add_device = AsyncMock()
        return store

    @pytest.fixture
    def manager(self, mock_stun_client, mock_device_store):
        """Create pairing manager."""
        return PairingManager(mock_stun_client, mock_device_store)

    @pytest.mark.asyncio
    async def test_creates_session(self, manager):
        """Start pairing creates a session."""
        with patch("ras.pairing.pairing_manager.SignalingServer") as mock_server_class:
            mock_server = MagicMock()
            mock_server.start = AsyncMock(return_value=MagicMock())
            mock_server_class.return_value = mock_server

            with patch("builtins.print"):  # Suppress output
                session = await manager.start_pairing(display_mode="terminal")

        assert session is not None
        assert session.session_id in manager.sessions
        assert session.state == PairingState.QR_DISPLAYED

    @pytest.mark.asyncio
    async def test_gets_public_ip(self, manager, mock_stun_client):
        """Start pairing gets public IP from STUN."""
        with patch("ras.pairing.pairing_manager.SignalingServer") as mock_server_class:
            mock_server = MagicMock()
            mock_server.start = AsyncMock(return_value=MagicMock())
            mock_server_class.return_value = mock_server

            with patch("builtins.print"):
                await manager.start_pairing(display_mode="terminal")

        mock_stun_client.get_public_ip.assert_called_once()

    @pytest.mark.asyncio
    async def test_starts_signaling_server(self, manager):
        """Start pairing starts signaling server."""
        with patch("ras.pairing.pairing_manager.SignalingServer") as mock_server_class:
            mock_server = MagicMock()
            mock_server.start = AsyncMock(return_value=MagicMock())
            mock_server_class.return_value = mock_server

            with patch("builtins.print"):
                await manager.start_pairing(display_mode="terminal")

        mock_server.start.assert_called_once_with("0.0.0.0", 8821)

    @pytest.mark.asyncio
    async def test_reuses_signaling_server(self, manager):
        """Multiple pairings reuse signaling server."""
        with patch("ras.pairing.pairing_manager.SignalingServer") as mock_server_class:
            mock_server = MagicMock()
            mock_server.start = AsyncMock(return_value=MagicMock())
            mock_server_class.return_value = mock_server

            with patch("builtins.print"):
                await manager.start_pairing(display_mode="terminal")
                await manager.start_pairing(display_mode="terminal")

        # Server should only be created once
        assert mock_server_class.call_count == 1


class TestGetSession:
    """Tests for getting sessions."""

    @pytest.fixture
    def manager(self):
        """Create pairing manager."""
        stun_client = MagicMock()
        device_store = MagicMock()
        return PairingManager(stun_client, device_store)

    def test_returns_none_for_unknown_session(self, manager):
        """Returns None for unknown session ID."""
        result = manager.get_session("unknown-session-id")
        assert result is None

    def test_returns_session_when_exists(self, manager):
        """Returns session when it exists."""
        session = PairingSession.create(b"\x00" * 32, b"\x01" * 32)
        manager.sessions[session.session_id] = session

        result = manager.get_session(session.session_id)
        assert result is session


class TestHandleSignal:
    """Tests for handling signaling."""

    @pytest.fixture
    def mock_stun_client(self):
        """Create mock STUN client."""
        client = MagicMock()
        client.get_public_ip = AsyncMock(return_value="1.2.3.4")
        return client

    @pytest.fixture
    def mock_device_store(self):
        """Create mock device store."""
        store = MagicMock()
        store.add_device = AsyncMock()
        return store

    @pytest.fixture
    def manager(self, mock_stun_client, mock_device_store):
        """Create pairing manager."""
        return PairingManager(mock_stun_client, mock_device_store)

    @pytest.mark.asyncio
    async def test_raises_for_unknown_session(self, manager):
        """Raises ValueError for unknown session."""
        with pytest.raises(ValueError, match="Session not found"):
            await manager.handle_signal(
                session_id="unknown",
                sdp_offer="offer",
                device_id="device",
                device_name="name",
            )

    @pytest.mark.asyncio
    async def test_stores_device_info(self, manager):
        """Stores device info on session."""
        session = PairingSession.create(b"\x00" * 32, b"\x01" * 32)
        session.transition_to(PairingState.QR_DISPLAYED)
        manager.sessions[session.session_id] = session

        with patch("ras.pairing.pairing_manager.PeerConnection") as mock_pc_class:
            mock_pc = MagicMock()
            mock_pc.accept_offer = AsyncMock(return_value="test")
            mock_pc.wait_connected = AsyncMock()
            mock_pc.on_message = MagicMock()
            mock_pc_class.return_value = mock_pc

            await manager.handle_signal(
                session_id=session.session_id,
                sdp_offer="test",
                device_id="test-device",
                device_name="Test Phone",
            )

        assert session.device_id == "test-device"
        assert session.device_name == "Test Phone"

    @pytest.mark.asyncio
    async def test_transitions_to_connecting(self, manager):
        """Transitions session to CONNECTING state."""
        session = PairingSession.create(b"\x00" * 32, b"\x01" * 32)
        session.transition_to(PairingState.QR_DISPLAYED)
        manager.sessions[session.session_id] = session

        with patch("ras.pairing.pairing_manager.PeerConnection") as mock_pc_class:
            mock_pc = MagicMock()
            mock_pc.accept_offer = AsyncMock(return_value="test")
            mock_pc.wait_connected = AsyncMock()
            mock_pc.on_message = MagicMock()
            mock_pc_class.return_value = mock_pc

            await manager.handle_signal(
                session_id=session.session_id,
                sdp_offer="test",
                device_id="device",
                device_name="name",
            )

        assert session.state == PairingState.CONNECTING

    @pytest.mark.asyncio
    async def test_returns_sdp_answer(self, manager):
        """Returns SDP answer from peer connection."""
        session = PairingSession.create(b"\x00" * 32, b"\x01" * 32)
        session.transition_to(PairingState.QR_DISPLAYED)
        manager.sessions[session.session_id] = session

        with patch("ras.pairing.pairing_manager.PeerConnection") as mock_pc_class:
            mock_pc = MagicMock()
            mock_pc.accept_offer = AsyncMock(
                return_value="v=0\\r\\n"
            )
            mock_pc.wait_connected = AsyncMock()
            mock_pc.on_message = MagicMock()
            mock_pc_class.return_value = mock_pc

            result = await manager.handle_signal(
                session_id=session.session_id,
                sdp_offer="test",
                device_id="device",
                device_name="name",
            )

        assert result == "v=0\\r\\n"


class TestOnPairingComplete:
    """Tests for pairing completion callback."""

    @pytest.fixture
    def manager(self):
        """Create pairing manager."""
        stun_client = MagicMock()
        device_store = MagicMock()
        return PairingManager(stun_client, device_store)

    def test_registers_callback(self, manager):
        """Registers completion callback."""
        callback = AsyncMock()
        manager.on_pairing_complete(callback)

        assert manager._on_pairing_complete is callback


class TestCleanupSession:
    """Tests for session cleanup."""

    @pytest.fixture
    def mock_stun_client(self):
        """Create mock STUN client."""
        client = MagicMock()
        client.get_public_ip = AsyncMock(return_value="1.2.3.4")
        return client

    @pytest.fixture
    def mock_device_store(self):
        """Create mock device store."""
        store = MagicMock()
        store.add_device = AsyncMock()
        return store

    @pytest.fixture
    def manager(self, mock_stun_client, mock_device_store):
        """Create pairing manager."""
        return PairingManager(mock_stun_client, mock_device_store)

    @pytest.mark.asyncio
    async def test_removes_session(self, manager):
        """Cleanup removes session from manager."""
        session = PairingSession.create(b"\x00" * 32, b"\x01" * 32)
        session.transition_to(PairingState.QR_DISPLAYED)
        manager.sessions[session.session_id] = session

        await manager._cleanup_session(session.session_id)

        assert session.session_id not in manager.sessions

    @pytest.mark.asyncio
    async def test_closes_peer_connection(self, manager):
        """Cleanup closes peer connection."""
        session = PairingSession.create(b"\x00" * 32, b"\x01" * 32)
        session.transition_to(PairingState.QR_DISPLAYED)
        mock_pc = MagicMock()
        mock_pc.close = AsyncMock()
        session.peer_connection = mock_pc
        manager.sessions[session.session_id] = session

        await manager._cleanup_session(session.session_id)

        mock_pc.close.assert_called_once()

    @pytest.mark.asyncio
    async def test_zeros_sensitive_data(self, manager):
        """Cleanup zeros sensitive data."""
        master_secret = b"\xaa" * 32
        auth_key = b"\xbb" * 32
        session = PairingSession.create(master_secret, auth_key)
        session.transition_to(PairingState.QR_DISPLAYED)
        manager.sessions[session.session_id] = session

        await manager._cleanup_session(session.session_id)

        assert session.master_secret == b"\x00" * 32
        assert session.auth_key == b"\x00" * 32


class TestStop:
    """Tests for stopping manager."""

    @pytest.fixture
    def manager(self):
        """Create pairing manager."""
        stun_client = MagicMock()
        device_store = MagicMock()
        return PairingManager(stun_client, device_store)

    @pytest.mark.asyncio
    async def test_cleans_up_all_sessions(self, manager):
        """Stop cleans up all sessions."""
        session1 = PairingSession.create(b"\x00" * 32, b"\x01" * 32)
        session1.transition_to(PairingState.QR_DISPLAYED)
        session2 = PairingSession.create(b"\x02" * 32, b"\x03" * 32)
        session2.transition_to(PairingState.QR_DISPLAYED)
        manager.sessions[session1.session_id] = session1
        manager.sessions[session2.session_id] = session2

        await manager.stop()

        assert len(manager.sessions) == 0

    @pytest.mark.asyncio
    async def test_stops_signaling_server(self, manager):
        """Stop stops signaling server."""
        mock_runner = MagicMock()
        mock_runner.cleanup = AsyncMock()
        manager._server_runner = mock_runner
        manager.signaling_server = MagicMock()

        await manager.stop()

        mock_runner.cleanup.assert_called_once()
        assert manager.signaling_server is None
        assert manager._server_runner is None
