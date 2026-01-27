"""Tests for authentication module."""

import time

import pytest

from ras.auth import AuthError, AuthState, Authenticator


class TestAuthenticatorCreation:
    """Test Authenticator creation."""

    def test_initial_state_is_pending(self):
        """Authenticator starts in PENDING state."""
        auth = Authenticator(auth_key=b"x" * 32)
        assert auth.state == AuthState.PENDING

    def test_accepts_32_byte_key(self):
        """Accepts 32-byte auth key."""
        auth = Authenticator(auth_key=b"x" * 32)
        assert auth.auth_key == b"x" * 32

    def test_rejects_wrong_key_length(self):
        """Rejects auth key that isn't 32 bytes."""
        with pytest.raises(ValueError, match="32 bytes"):
            Authenticator(auth_key=b"short")


class TestChallengeCreation:
    """Test challenge creation."""

    def test_create_challenge_returns_dict(self):
        """create_challenge returns a dict."""
        auth = Authenticator(auth_key=b"x" * 32)
        challenge = auth.create_challenge()
        assert isinstance(challenge, dict)

    def test_challenge_has_type(self):
        """Challenge has type field."""
        auth = Authenticator(auth_key=b"x" * 32)
        challenge = auth.create_challenge()
        assert challenge["type"] == "auth_challenge"

    def test_challenge_has_32_byte_nonce(self):
        """Challenge has 32-byte nonce (as hex)."""
        auth = Authenticator(auth_key=b"x" * 32)
        challenge = auth.create_challenge()
        nonce = bytes.fromhex(challenge["nonce"])
        assert len(nonce) == 32

    def test_challenge_nonces_are_unique(self):
        """Each challenge has a unique nonce."""
        auth = Authenticator(auth_key=b"x" * 32)
        c1 = auth.create_challenge()
        auth._state = AuthState.PENDING  # Reset for another challenge
        c2 = auth.create_challenge()
        assert c1["nonce"] != c2["nonce"]

    def test_state_transitions_to_challenged(self):
        """State transitions to CHALLENGED after creating challenge."""
        auth = Authenticator(auth_key=b"x" * 32)
        auth.create_challenge()
        assert auth.state == AuthState.CHALLENGED


class TestChallengeResponse:
    """Test responding to challenges."""

    def test_respond_returns_dict(self):
        """respond_to_challenge returns a dict."""
        auth = Authenticator(auth_key=b"x" * 32)
        challenge = {"type": "auth_challenge", "nonce": "aa" * 32}
        response = auth.respond_to_challenge(challenge)
        assert isinstance(response, dict)

    def test_response_has_type(self):
        """Response has type field."""
        auth = Authenticator(auth_key=b"x" * 32)
        challenge = {"type": "auth_challenge", "nonce": "aa" * 32}
        response = auth.respond_to_challenge(challenge)
        assert response["type"] == "auth_response"

    def test_response_has_hmac(self):
        """Response has HMAC of challenge nonce."""
        auth = Authenticator(auth_key=b"x" * 32)
        challenge = {"type": "auth_challenge", "nonce": "aa" * 32}
        response = auth.respond_to_challenge(challenge)
        assert "hmac" in response
        assert len(bytes.fromhex(response["hmac"])) == 32

    def test_response_has_own_nonce(self):
        """Response includes own 32-byte nonce."""
        auth = Authenticator(auth_key=b"x" * 32)
        challenge = {"type": "auth_challenge", "nonce": "aa" * 32}
        response = auth.respond_to_challenge(challenge)
        assert "nonce" in response
        assert len(bytes.fromhex(response["nonce"])) == 32

    def test_state_transitions_to_responded(self):
        """State transitions to RESPONDED after responding."""
        auth = Authenticator(auth_key=b"x" * 32)
        challenge = {"type": "auth_challenge", "nonce": "aa" * 32}
        auth.respond_to_challenge(challenge)
        assert auth.state == AuthState.RESPONDED


class TestResponseVerification:
    """Test response verification."""

    def test_verify_valid_response(self):
        """Verifies valid response from peer with same key."""
        key = b"x" * 32
        auth1 = Authenticator(auth_key=key)
        auth2 = Authenticator(auth_key=key)

        challenge = auth1.create_challenge()
        response = auth2.respond_to_challenge(challenge)

        assert auth1.verify_response(response) is True

    def test_reject_invalid_hmac(self):
        """Rejects response with wrong key (different HMAC)."""
        auth1 = Authenticator(auth_key=b"a" * 32)
        auth2 = Authenticator(auth_key=b"b" * 32)  # Different key

        challenge = auth1.create_challenge()
        response = auth2.respond_to_challenge(challenge)

        assert auth1.verify_response(response) is False

    def test_reject_without_challenge(self):
        """Rejects response when no challenge was sent."""
        auth = Authenticator(auth_key=b"x" * 32)
        response = {"type": "auth_response", "hmac": "aa" * 32, "nonce": "bb" * 32}

        assert auth.verify_response(response) is False

    def test_state_failed_on_invalid(self):
        """State transitions to FAILED on invalid response."""
        auth1 = Authenticator(auth_key=b"a" * 32)
        auth2 = Authenticator(auth_key=b"b" * 32)

        challenge = auth1.create_challenge()
        response = auth2.respond_to_challenge(challenge)
        auth1.verify_response(response)

        assert auth1.state == AuthState.FAILED


class TestReplayProtection:
    """Test replay attack protection."""

    def test_reject_replayed_response(self):
        """Rejects the same response used twice (replay)."""
        key = b"x" * 32
        auth1 = Authenticator(auth_key=key)
        auth2 = Authenticator(auth_key=key)

        challenge = auth1.create_challenge()
        response = auth2.respond_to_challenge(challenge)

        # First use succeeds
        assert auth1.verify_response(response) is True

        # Replay fails (nonce already used)
        auth1._state = AuthState.CHALLENGED  # Reset state
        auth1._our_nonce = bytes.fromhex(challenge["nonce"])
        assert auth1.verify_response(response) is False

    def test_nonces_tracked(self):
        """Used nonces are tracked to prevent replay."""
        key = b"x" * 32
        auth = Authenticator(auth_key=key)

        challenge = auth.create_challenge()
        nonce = bytes.fromhex(challenge["nonce"])

        # Nonce not yet used
        assert nonce not in auth._used_nonces

        # After verification, nonce is tracked
        auth2 = Authenticator(auth_key=key)
        response = auth2.respond_to_challenge(challenge)
        auth.verify_response(response)

        assert nonce in auth._used_nonces


class TestRateLimiting:
    """Test rate limiting after failed attempts."""

    def test_tracks_failed_attempts(self):
        """Tracks number of failed authentication attempts."""
        auth1 = Authenticator(auth_key=b"a" * 32)
        auth2 = Authenticator(auth_key=b"b" * 32)  # Different key

        assert auth1._failed_attempts == 0

        challenge = auth1.create_challenge()
        response = auth2.respond_to_challenge(challenge)
        auth1.verify_response(response)

        assert auth1._failed_attempts == 1

    def test_rate_limit_after_max_failures(self):
        """Rate limits after MAX_FAILED_ATTEMPTS failures."""
        auth1 = Authenticator(auth_key=b"a" * 32)
        auth2 = Authenticator(auth_key=b"b" * 32)

        # Simulate max failures
        auth1._failed_attempts = Authenticator.MAX_FAILED_ATTEMPTS

        with pytest.raises(AuthError, match="Too many failed"):
            auth1._check_rate_limit()

    def test_success_resets_failed_count(self):
        """Successful auth resets failed attempt counter."""
        key = b"x" * 32
        auth1 = Authenticator(auth_key=key)
        auth2 = Authenticator(auth_key=key)

        auth1._failed_attempts = 3  # Simulate previous failures

        challenge = auth1.create_challenge()
        response = auth2.respond_to_challenge(challenge)
        auth1.verify_response(response)

        assert auth1._failed_attempts == 0


class TestVerificationMessage:
    """Test verification message (auth_verified)."""

    def test_create_verify_returns_dict(self):
        """create_verify returns a dict."""
        auth = Authenticator(auth_key=b"x" * 32)
        verify = auth.create_verify("aa" * 32)
        assert isinstance(verify, dict)

    def test_verify_has_type(self):
        """Verify message has type field."""
        auth = Authenticator(auth_key=b"x" * 32)
        verify = auth.create_verify("aa" * 32)
        assert verify["type"] == "auth_verified"

    def test_verify_has_hmac(self):
        """Verify message has HMAC."""
        auth = Authenticator(auth_key=b"x" * 32)
        verify = auth.create_verify("aa" * 32)
        assert "hmac" in verify
        assert len(bytes.fromhex(verify["hmac"])) == 32

    def test_state_transitions_to_authenticated(self):
        """State transitions to AUTHENTICATED after creating verify."""
        auth = Authenticator(auth_key=b"x" * 32)
        auth.create_verify("aa" * 32)
        assert auth.state == AuthState.AUTHENTICATED


class TestVerifyVerification:
    """Test verifying the verification message."""

    def test_verify_valid_verification(self):
        """Verifies valid verification message."""
        key = b"x" * 32
        auth1 = Authenticator(auth_key=key)
        auth2 = Authenticator(auth_key=key)

        # auth2 sends challenge, auth1 responds
        challenge = {"type": "auth_challenge", "nonce": "aa" * 32}
        response = auth2.respond_to_challenge(challenge)

        # auth1 creates verification
        verify = auth1.create_verify(response["nonce"])

        # auth2 verifies it
        assert auth2.verify_verify(verify) is True
        assert auth2.state == AuthState.AUTHENTICATED

    def test_reject_invalid_verification(self):
        """Rejects verification with wrong HMAC."""
        auth1 = Authenticator(auth_key=b"a" * 32)
        auth2 = Authenticator(auth_key=b"b" * 32)

        challenge = {"type": "auth_challenge", "nonce": "aa" * 32}
        response = auth2.respond_to_challenge(challenge)

        verify = auth1.create_verify(response["nonce"])  # Wrong key

        assert auth2.verify_verify(verify) is False
        assert auth2.state == AuthState.FAILED


class TestMutualAuthentication:
    """Test full mutual authentication flow."""

    def test_full_handshake_success(self):
        """Full mutual authentication succeeds with same key."""
        key = b"x" * 32
        daemon_auth = Authenticator(auth_key=key, role="daemon")
        phone_auth = Authenticator(auth_key=key, role="phone")

        # Step 1: Daemon challenges phone
        challenge = daemon_auth.create_challenge()
        assert daemon_auth.state == AuthState.CHALLENGED

        # Step 2: Phone responds with HMAC + own challenge
        response = phone_auth.respond_to_challenge(challenge)
        assert phone_auth.state == AuthState.RESPONDED

        # Step 3: Daemon verifies phone's response
        assert daemon_auth.verify_response(response) is True

        # Step 4: Daemon sends verification
        verify = daemon_auth.create_verify(response["nonce"])
        assert daemon_auth.state == AuthState.AUTHENTICATED

        # Step 5: Phone verifies daemon
        assert phone_auth.verify_verify(verify) is True
        assert phone_auth.state == AuthState.AUTHENTICATED

    def test_full_handshake_fails_with_different_keys(self):
        """Mutual authentication fails with different keys."""
        daemon_auth = Authenticator(auth_key=b"a" * 32, role="daemon")
        phone_auth = Authenticator(auth_key=b"b" * 32, role="phone")

        challenge = daemon_auth.create_challenge()
        response = phone_auth.respond_to_challenge(challenge)

        # Daemon rejects phone's response
        assert daemon_auth.verify_response(response) is False
        assert daemon_auth.state == AuthState.FAILED
