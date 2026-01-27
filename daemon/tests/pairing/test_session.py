"""Tests for pairing session state machine."""

import time
from unittest.mock import patch

import pytest

from ras.pairing.session import PairingSession, PairingState


class TestPairingState:
    """Tests for PairingState enum."""

    def test_all_states_defined(self):
        """All expected states are defined."""
        assert PairingState.IDLE
        assert PairingState.QR_DISPLAYED
        assert PairingState.SIGNALING
        assert PairingState.CONNECTING
        assert PairingState.AUTHENTICATING
        assert PairingState.AUTHENTICATED
        assert PairingState.FAILED


class TestPairingSessionCreate:
    """Tests for PairingSession creation."""

    def test_create_generates_session_id(self):
        """Create generates unique session ID."""
        master_secret = b"\x00" * 32
        auth_key = b"\x01" * 32
        session = PairingSession.create(master_secret, auth_key)
        assert len(session.session_id) == 32  # 16 bytes hex = 32 chars

    def test_create_sets_secrets(self):
        """Create stores the provided secrets."""
        master_secret = b"\x00" * 32
        auth_key = b"\x01" * 32
        session = PairingSession.create(master_secret, auth_key)
        assert session.master_secret == master_secret
        assert session.auth_key == auth_key

    def test_create_starts_in_idle(self):
        """Created session starts in IDLE state."""
        session = PairingSession.create(b"\x00" * 32, b"\x01" * 32)
        assert session.state == PairingState.IDLE

    def test_create_sets_created_at(self):
        """Create sets created_at timestamp."""
        before = time.time()
        session = PairingSession.create(b"\x00" * 32, b"\x01" * 32)
        after = time.time()
        assert before <= session.created_at <= after

    def test_create_unique_session_ids(self):
        """Each create call generates unique session ID."""
        ids = [
            PairingSession.create(b"\x00" * 32, b"\x01" * 32).session_id
            for _ in range(10)
        ]
        assert len(set(ids)) == 10


class TestPairingSessionTransition:
    """Tests for state transitions."""

    def test_idle_to_qr_displayed(self):
        """Can transition from IDLE to QR_DISPLAYED."""
        session = PairingSession.create(b"\x00" * 32, b"\x01" * 32)
        session.transition_to(PairingState.QR_DISPLAYED)
        assert session.state == PairingState.QR_DISPLAYED

    def test_qr_displayed_to_signaling(self):
        """Can transition from QR_DISPLAYED to SIGNALING."""
        session = PairingSession.create(b"\x00" * 32, b"\x01" * 32)
        session.transition_to(PairingState.QR_DISPLAYED)
        session.transition_to(PairingState.SIGNALING)
        assert session.state == PairingState.SIGNALING

    def test_signaling_to_connecting(self):
        """Can transition from SIGNALING to CONNECTING."""
        session = PairingSession.create(b"\x00" * 32, b"\x01" * 32)
        session.transition_to(PairingState.QR_DISPLAYED)
        session.transition_to(PairingState.SIGNALING)
        session.transition_to(PairingState.CONNECTING)
        assert session.state == PairingState.CONNECTING

    def test_connecting_to_authenticating(self):
        """Can transition from CONNECTING to AUTHENTICATING."""
        session = PairingSession.create(b"\x00" * 32, b"\x01" * 32)
        session.transition_to(PairingState.QR_DISPLAYED)
        session.transition_to(PairingState.SIGNALING)
        session.transition_to(PairingState.CONNECTING)
        session.transition_to(PairingState.AUTHENTICATING)
        assert session.state == PairingState.AUTHENTICATING

    def test_authenticating_to_authenticated(self):
        """Can transition from AUTHENTICATING to AUTHENTICATED."""
        session = PairingSession.create(b"\x00" * 32, b"\x01" * 32)
        session.transition_to(PairingState.QR_DISPLAYED)
        session.transition_to(PairingState.SIGNALING)
        session.transition_to(PairingState.CONNECTING)
        session.transition_to(PairingState.AUTHENTICATING)
        session.transition_to(PairingState.AUTHENTICATED)
        assert session.state == PairingState.AUTHENTICATED

    def test_any_state_to_failed(self):
        """Can transition to FAILED from QR_DISPLAYED, SIGNALING, CONNECTING, AUTHENTICATING."""
        for target in [
            PairingState.QR_DISPLAYED,
            PairingState.SIGNALING,
            PairingState.CONNECTING,
            PairingState.AUTHENTICATING,
        ]:
            session = PairingSession.create(b"\x00" * 32, b"\x01" * 32)
            session.transition_to(PairingState.QR_DISPLAYED)
            if target != PairingState.QR_DISPLAYED:
                session.transition_to(PairingState.SIGNALING)
            if target == PairingState.CONNECTING:
                session.transition_to(PairingState.CONNECTING)
            if target == PairingState.AUTHENTICATING:
                session.transition_to(PairingState.CONNECTING)
                session.transition_to(PairingState.AUTHENTICATING)
            session.transition_to(PairingState.FAILED)
            assert session.state == PairingState.FAILED

    def test_failed_to_idle(self):
        """Can transition from FAILED back to IDLE."""
        session = PairingSession.create(b"\x00" * 32, b"\x01" * 32)
        session.transition_to(PairingState.QR_DISPLAYED)
        session.transition_to(PairingState.FAILED)
        session.transition_to(PairingState.IDLE)
        assert session.state == PairingState.IDLE

    def test_invalid_transition_raises(self):
        """Invalid transitions raise ValueError."""
        session = PairingSession.create(b"\x00" * 32, b"\x01" * 32)
        # Can't go directly from IDLE to SIGNALING
        with pytest.raises(ValueError, match="Invalid transition"):
            session.transition_to(PairingState.SIGNALING)

    def test_cannot_skip_states(self):
        """Cannot skip states in the flow."""
        session = PairingSession.create(b"\x00" * 32, b"\x01" * 32)
        session.transition_to(PairingState.QR_DISPLAYED)
        # Can't skip SIGNALING
        with pytest.raises(ValueError, match="Invalid transition"):
            session.transition_to(PairingState.CONNECTING)


class TestPairingSessionExpiration:
    """Tests for session expiration."""

    def test_new_session_not_expired(self):
        """Freshly created session is not expired."""
        session = PairingSession.create(b"\x00" * 32, b"\x01" * 32)
        session.transition_to(PairingState.QR_DISPLAYED)
        assert not session.is_expired()

    def test_qr_displayed_expires_after_timeout(self):
        """QR_DISPLAYED state expires after QR_TIMEOUT."""
        session = PairingSession.create(b"\x00" * 32, b"\x01" * 32)
        session.transition_to(PairingState.QR_DISPLAYED)
        # Simulate time passing
        session.created_at = time.time() - PairingSession.QR_TIMEOUT - 1
        assert session.is_expired()

    def test_signaling_expires(self):
        """SIGNALING state expires after combined timeout."""
        session = PairingSession.create(b"\x00" * 32, b"\x01" * 32)
        session.transition_to(PairingState.QR_DISPLAYED)
        session.transition_to(PairingState.SIGNALING)
        # Simulate time passing beyond QR + SIGNALING timeout
        session.created_at = (
            time.time()
            - PairingSession.QR_TIMEOUT
            - PairingSession.SIGNALING_TIMEOUT
            - 1
        )
        assert session.is_expired()

    def test_authenticated_never_expires(self):
        """AUTHENTICATED state never expires via is_expired()."""
        session = PairingSession.create(b"\x00" * 32, b"\x01" * 32)
        session.transition_to(PairingState.QR_DISPLAYED)
        session.transition_to(PairingState.SIGNALING)
        session.transition_to(PairingState.CONNECTING)
        session.transition_to(PairingState.AUTHENTICATING)
        session.transition_to(PairingState.AUTHENTICATED)
        # Even with old timestamp
        session.created_at = time.time() - 10000
        assert not session.is_expired()


class TestPairingSessionDeviceInfo:
    """Tests for device info storage."""

    def test_device_info_initially_none(self):
        """Device info is None initially."""
        session = PairingSession.create(b"\x00" * 32, b"\x01" * 32)
        assert session.device_id is None
        assert session.device_name is None

    def test_can_set_device_info(self):
        """Can set device info."""
        session = PairingSession.create(b"\x00" * 32, b"\x01" * 32)
        session.device_id = "device-123"
        session.device_name = "Pixel 8"
        assert session.device_id == "device-123"
        assert session.device_name == "Pixel 8"


class TestPairingSessionPeerConnection:
    """Tests for peer connection storage."""

    def test_peer_connection_initially_none(self):
        """Peer connection is None initially."""
        session = PairingSession.create(b"\x00" * 32, b"\x01" * 32)
        assert session.peer_connection is None

    def test_can_set_peer_connection(self):
        """Can set peer connection."""
        session = PairingSession.create(b"\x00" * 32, b"\x01" * 32)
        mock_pc = object()
        session.peer_connection = mock_pc
        assert session.peer_connection is mock_pc
