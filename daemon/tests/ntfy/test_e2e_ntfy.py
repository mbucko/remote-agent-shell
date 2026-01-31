"""End-to-end tests for ntfy IP notification system.

These tests verify the complete flow:
1. IP change detection via IpMonitor
2. Encryption via NtfyCrypto
3. Publishing via NtfyPublisher
4. Decryption and validation

Covers all scenarios, edge cases, and error possibilities.
"""

import asyncio
import base64
import os
import time
from contextlib import asynccontextmanager
from unittest.mock import AsyncMock, MagicMock, patch

import aiohttp
import pytest


@asynccontextmanager
async def run_monitor_with_mocked_sleep(monitor, iterations: int):
    """Run monitor with mocked sleep, stopping after N iterations."""
    sleep_count = [0]

    async def mock_sleep(delay):
        sleep_count[0] += 1
        if sleep_count[0] >= iterations:
            monitor._running = False

    with patch("ras.ip_monitor.monitor.asyncio.sleep", side_effect=mock_sleep):
        await monitor.start()
        yield
        pending = asyncio.all_tasks() - {asyncio.current_task()}
        if pending:
            await asyncio.gather(*pending, return_exceptions=True)

from cryptography.exceptions import InvalidTag

from ras.crypto import derive_key, derive_ntfy_topic, generate_secret
from ras.ip_monitor.monitor import IpMonitor
from ras.ntfy.crypto import IpChangeData, NtfyCrypto
from ras.ntfy.publisher import NtfyPublisher


class MockNtfyServer:
    """Mock ntfy server for E2E testing."""

    def __init__(self):
        self.messages: list[tuple[str, str]] = []  # (topic, encrypted_data)
        self.fail_count = 0
        self.max_failures = 0

    def handle_publish(self, topic: str, data: str) -> int:
        """Handle publish request, return status code."""
        if self.fail_count < self.max_failures:
            self.fail_count += 1
            return 500

        self.messages.append((topic, data))
        return 200

    def get_messages(self, topic: str) -> list[str]:
        """Get all messages for a topic."""
        return [data for t, data in self.messages if t == topic]


class MockAndroidClient:
    """Simulates Android app receiving and decrypting ntfy messages."""

    def __init__(self, master_secret: bytes):
        self._master_secret = master_secret
        self._ntfy_key = derive_key(master_secret, "ntfy")
        self._topic = derive_ntfy_topic(master_secret)
        self._crypto = NtfyCrypto(self._ntfy_key)
        self._seen_nonces: set[bytes] = set()
        self._max_age = 300  # 5 minutes

    @property
    def topic(self) -> str:
        return self._topic

    def receive_notification(self, encrypted: str) -> IpChangeData:
        """Receive and decrypt a notification.

        Validates timestamp and replay protection.

        Returns:
            Decrypted IP change data.

        Raises:
            ValueError: If message is expired or replayed.
            InvalidTag: If decryption fails.
        """
        data = self._crypto.decrypt_ip_notification(encrypted)

        # Timestamp validation
        now = int(time.time())
        if abs(now - data.timestamp) > self._max_age:
            raise ValueError(f"Message expired: timestamp {data.timestamp}, now {now}")

        # Replay protection
        if data.nonce in self._seen_nonces:
            raise ValueError("Replay detected: nonce already seen")
        self._seen_nonces.add(data.nonce)

        return data


def create_mock_session(mock_server: MockNtfyServer):
    """Create a mock aiohttp session that captures data to the mock server."""
    captured_data = []

    mock_response = AsyncMock()
    mock_response.__aenter__.return_value = mock_response
    mock_response.__aexit__.return_value = None

    def post_side_effect(url, **kwargs):
        topic_from_url = url.split("/")[-1]
        data = kwargs.get("data", "")
        captured_data.append((topic_from_url, data))
        status = mock_server.handle_publish(topic_from_url, data)
        mock_response.status = status
        mock_response.text = AsyncMock(return_value="OK" if status == 200 else "Error")
        return mock_response

    session = AsyncMock(spec=aiohttp.ClientSession)
    session.post.side_effect = post_side_effect

    return session, captured_data


# =============================================================================
# HAPPY PATH E2E TESTS
# =============================================================================


class TestE2EHappyPath:
    """Test the complete happy path flow."""

    @pytest.mark.asyncio
    async def test_full_flow_ip_change_to_notification(self):
        """Complete flow: IP change detected → encrypted → published → decrypted."""
        # Setup
        master_secret = generate_secret()
        ntfy_key = derive_key(master_secret, "ntfy")
        topic = derive_ntfy_topic(master_secret)

        # Create components
        stun_client = AsyncMock()
        stun_client.get_public_ip.side_effect = [
            ("192.168.1.100", 8821),  # Initial IP
            ("10.0.0.50", 8821),  # Changed IP
        ]

        mock_server = MockNtfyServer()
        session, _ = create_mock_session(mock_server)

        crypto = NtfyCrypto(ntfy_key)
        publisher = NtfyPublisher(crypto=crypto, topic=topic, http_session=session)
        publisher.RETRY_DELAYS = [0.01, 0.01, 0.01]

        # Callback that publishes to ntfy
        async def on_ip_change(ip: str, port: int) -> None:
            await publisher.publish_ip_change(ip, port)

        monitor = IpMonitor(
            stun_client=stun_client,
            check_interval=0.01,
            on_ip_change=on_ip_change,
        )

        # Run the flow
        await monitor.start()
        await asyncio.sleep(0.05)
        await monitor.stop()

        # Verify message was published
        messages = mock_server.get_messages(topic)
        assert len(messages) == 1

        # Simulate Android client receiving the notification
        android_client = MockAndroidClient(master_secret)
        data = android_client.receive_notification(messages[0])

        assert data.ip == "10.0.0.50"
        assert data.port == 8821
        assert len(data.nonce) == 16

    @pytest.mark.asyncio
    async def test_multiple_ip_changes_all_published(self):
        """Multiple IP changes are all published and decryptable."""
        master_secret = generate_secret()
        ntfy_key = derive_key(master_secret, "ntfy")
        topic = derive_ntfy_topic(master_secret)

        stun_client = AsyncMock()
        stun_client.get_public_ip.side_effect = [
            ("1.1.1.1", 8821),  # Initial
            ("2.2.2.2", 8821),  # Change 1
            ("3.3.3.3", 8821),  # Change 2
            ("4.4.4.4", 8821),  # Change 3
        ]

        mock_server = MockNtfyServer()
        session, _ = create_mock_session(mock_server)

        crypto = NtfyCrypto(ntfy_key)
        publisher = NtfyPublisher(crypto=crypto, topic=topic, http_session=session)
        publisher.RETRY_DELAYS = [0.01, 0.01, 0.01]

        async def on_ip_change(ip: str, port: int) -> None:
            await publisher.publish_ip_change(ip, port)

        monitor = IpMonitor(
            stun_client=stun_client,
            check_interval=0.01,
            on_ip_change=on_ip_change,
        )

        await monitor.start()
        await asyncio.sleep(0.1)
        await monitor.stop()

        # Verify all changes published
        messages = mock_server.get_messages(topic)
        assert len(messages) == 3

        # Verify each message is decryptable with correct IP
        android_client = MockAndroidClient(master_secret)
        ips = []
        for msg in messages:
            data = android_client.receive_notification(msg)
            ips.append(data.ip)

        assert ips == ["2.2.2.2", "3.3.3.3", "4.4.4.4"]

    @pytest.mark.asyncio
    async def test_port_change_triggers_notification(self):
        """Port change (same IP) triggers notification."""
        master_secret = generate_secret()
        ntfy_key = derive_key(master_secret, "ntfy")
        topic = derive_ntfy_topic(master_secret)

        stun_client = AsyncMock()
        stun_client.get_public_ip.side_effect = [
            ("1.2.3.4", 8821),  # Initial
            ("1.2.3.4", 9999),  # Port changed
        ]

        mock_server = MockNtfyServer()
        session, _ = create_mock_session(mock_server)
        published_ips = []

        crypto = NtfyCrypto(ntfy_key)
        publisher = NtfyPublisher(crypto=crypto, topic=topic, http_session=session)
        publisher.RETRY_DELAYS = [0.01, 0.01, 0.01]

        async def on_ip_change(ip: str, port: int) -> None:
            published_ips.append((ip, port))
            await publisher.publish_ip_change(ip, port)

        monitor = IpMonitor(
            stun_client=stun_client,
            check_interval=0.01,
            on_ip_change=on_ip_change,
        )

        await monitor.start()
        await asyncio.sleep(0.05)
        await monitor.stop()

        assert published_ips == [("1.2.3.4", 9999)]

        messages = mock_server.get_messages(topic)
        android_client = MockAndroidClient(master_secret)
        data = android_client.receive_notification(messages[0])
        assert data.port == 9999

    @pytest.mark.asyncio
    async def test_no_change_no_notification(self):
        """If IP doesn't change, no notification is sent."""
        master_secret = generate_secret()
        ntfy_key = derive_key(master_secret, "ntfy")
        topic = derive_ntfy_topic(master_secret)

        stun_client = AsyncMock()
        stun_client.get_public_ip.side_effect = [
            ("1.2.3.4", 8821),  # Initial
            ("1.2.3.4", 8821),  # Same
            ("1.2.3.4", 8821),  # Same
        ]

        mock_server = MockNtfyServer()
        session, _ = create_mock_session(mock_server)

        crypto = NtfyCrypto(ntfy_key)
        publisher = NtfyPublisher(crypto=crypto, topic=topic, http_session=session)
        callback = AsyncMock()

        monitor = IpMonitor(
            stun_client=stun_client,
            check_interval=0.01,
            on_ip_change=callback,
        )

        await monitor.start()
        await asyncio.sleep(0.05)
        await monitor.stop()

        callback.assert_not_called()
        assert len(mock_server.messages) == 0


# =============================================================================
# ERROR HANDLING E2E TESTS
# =============================================================================


class TestE2EErrorHandling:
    """Test error handling throughout the flow."""

    @pytest.mark.asyncio
    async def test_stun_failure_doesnt_crash_monitor(self):
        """STUN failure doesn't crash the monitor - it recovers."""
        stun_client = AsyncMock()
        stun_client.get_public_ip.side_effect = [
            ("1.2.3.4", 8821),  # Initial
            Exception("STUN server unreachable"),  # Failure
            ("1.2.3.4", 8821),  # Recovery
        ]

        callback = AsyncMock()
        monitor = IpMonitor(
            stun_client=stun_client,
            check_interval=0.01,
            on_ip_change=callback,
        )

        await monitor.start()
        await asyncio.sleep(0.05)
        await monitor.stop()

        # Should still be running (not crashed)
        assert stun_client.get_public_ip.call_count >= 3
        # No IP change callback (IP stayed the same)
        callback.assert_not_called()

    @pytest.mark.asyncio
    async def test_ntfy_server_failure_retries_and_recovers(self):
        """ntfy server failure triggers retries, eventually succeeds."""
        master_secret = generate_secret()
        ntfy_key = derive_key(master_secret, "ntfy")
        topic = derive_ntfy_topic(master_secret)

        mock_server = MockNtfyServer()
        mock_server.max_failures = 2  # Fail twice, then succeed
        session, _ = create_mock_session(mock_server)

        crypto = NtfyCrypto(ntfy_key)
        publisher = NtfyPublisher(crypto=crypto, topic=topic, http_session=session)
        publisher.RETRY_DELAYS = [0.01, 0.01, 0.01]

        result = await publisher.publish_ip_change("5.6.7.8", 8821)

        assert result is True
        assert mock_server.fail_count == 2
        assert len(mock_server.messages) == 1

    @pytest.mark.asyncio
    async def test_ntfy_server_permanent_failure_returns_false(self):
        """Permanent ntfy server failure returns False after retries."""
        master_secret = generate_secret()
        ntfy_key = derive_key(master_secret, "ntfy")
        topic = derive_ntfy_topic(master_secret)

        mock_response = AsyncMock()
        mock_response.status = 500
        mock_response.text = AsyncMock(return_value="Server error")
        mock_response.__aenter__.return_value = mock_response
        mock_response.__aexit__.return_value = None

        session = AsyncMock(spec=aiohttp.ClientSession)
        session.post.return_value = mock_response

        crypto = NtfyCrypto(ntfy_key)
        publisher = NtfyPublisher(crypto=crypto, topic=topic, http_session=session)
        publisher.RETRY_DELAYS = [0.01, 0.01, 0.01]

        result = await publisher.publish_ip_change("5.6.7.8", 8821)

        assert result is False
        assert session.post.call_count == 3  # MAX_RETRIES

    @pytest.mark.asyncio
    async def test_callback_failure_doesnt_crash_monitor(self):
        """Callback failure doesn't crash the monitor."""
        stun_client = AsyncMock()
        stun_client.get_public_ip.side_effect = [
            ("1.2.3.4", 8821),  # Initial
            ("5.6.7.8", 8821),  # Changed
            ("9.10.11.12", 8821),  # Changed again
        ]

        callback = AsyncMock(side_effect=[Exception("Callback failed"), None])
        monitor = IpMonitor(
            stun_client=stun_client,
            check_interval=0.01,
            on_ip_change=callback,
        )

        await monitor.start()
        await asyncio.sleep(0.05)
        await monitor.stop()

        # Both callbacks should have been attempted
        assert callback.call_count == 2

    @pytest.mark.asyncio
    async def test_network_exception_during_publish_retries(self):
        """Network exception during publish triggers retry."""
        master_secret = generate_secret()
        ntfy_key = derive_key(master_secret, "ntfy")
        topic = derive_ntfy_topic(master_secret)

        mock_response = AsyncMock()
        mock_response.status = 200
        mock_response.__aenter__.return_value = mock_response
        mock_response.__aexit__.return_value = None

        session = AsyncMock(spec=aiohttp.ClientSession)
        session.post.side_effect = [
            aiohttp.ClientError("Connection refused"),
            aiohttp.ClientError("Timeout"),
            mock_response,
        ]

        crypto = NtfyCrypto(ntfy_key)
        publisher = NtfyPublisher(crypto=crypto, topic=topic, http_session=session)
        publisher.RETRY_DELAYS = [0.01, 0.01, 0.01]

        result = await publisher.publish_ip_change("5.6.7.8", 8821)

        assert result is True
        assert session.post.call_count == 3

    @pytest.mark.asyncio
    async def test_ntfy_failure_doesnt_stop_ip_monitoring(self):
        """ntfy publish failure doesn't stop IP monitoring."""
        stun_client = AsyncMock()
        stun_client.get_public_ip.side_effect = [
            ("1.1.1.1", 8821),  # Initial
            ("2.2.2.2", 8821),  # Change 1 - ntfy will fail
            ("3.3.3.3", 8821),  # Change 2 - ntfy will succeed
        ]

        master_secret = generate_secret()
        ntfy_key = derive_key(master_secret, "ntfy")
        topic = derive_ntfy_topic(master_secret)

        mock_response_fail = AsyncMock()
        mock_response_fail.status = 500
        mock_response_fail.text = AsyncMock(return_value="Error")
        mock_response_fail.__aenter__.return_value = mock_response_fail
        mock_response_fail.__aexit__.return_value = None

        mock_response_success = AsyncMock()
        mock_response_success.status = 200
        mock_response_success.__aenter__.return_value = mock_response_success
        mock_response_success.__aexit__.return_value = None

        session = AsyncMock(spec=aiohttp.ClientSession)
        # Fail all retries on first publish, succeed on second
        session.post.side_effect = [
            mock_response_fail,
            mock_response_fail,
            mock_response_fail,
            mock_response_success,
        ]

        crypto = NtfyCrypto(ntfy_key)
        publisher = NtfyPublisher(crypto=crypto, topic=topic, http_session=session)
        publisher.RETRY_DELAYS = [0.001, 0.001, 0.001]

        callbacks_received = []

        async def on_ip_change(ip: str, port: int) -> None:
            callbacks_received.append(ip)
            await publisher.publish_ip_change(ip, port)

        monitor = IpMonitor(
            stun_client=stun_client,
            check_interval=0.01,
            on_ip_change=on_ip_change,
        )

        await monitor.start()
        await asyncio.sleep(0.08)
        await monitor.stop()

        # Both IP changes should have been detected
        assert callbacks_received == ["2.2.2.2", "3.3.3.3"]


# =============================================================================
# SECURITY E2E TESTS
# =============================================================================


class TestE2ESecurity:
    """Test security properties of the E2E flow."""

    @pytest.mark.asyncio
    async def test_wrong_key_cannot_decrypt(self):
        """Messages encrypted with one key cannot be decrypted with another."""
        secret1 = generate_secret()
        secret2 = generate_secret()  # Different secret

        ntfy_key1 = derive_key(secret1, "ntfy")
        crypto1 = NtfyCrypto(ntfy_key1)

        # Encrypt with key1
        nonce = os.urandom(16)
        encrypted = crypto1.encrypt_ip_notification(
            ip="1.2.3.4",
            port=8821,
            timestamp=int(time.time()),
            nonce=nonce,
        )

        # Try to decrypt with different secret's key
        android_client = MockAndroidClient(secret2)
        with pytest.raises(InvalidTag):
            android_client.receive_notification(encrypted)

    @pytest.mark.asyncio
    async def test_replay_attack_detected(self):
        """Replayed messages are detected and rejected."""
        master_secret = generate_secret()
        ntfy_key = derive_key(master_secret, "ntfy")

        crypto = NtfyCrypto(ntfy_key)
        nonce = os.urandom(16)
        encrypted = crypto.encrypt_ip_notification(
            ip="1.2.3.4",
            port=8821,
            timestamp=int(time.time()),
            nonce=nonce,
        )

        android_client = MockAndroidClient(master_secret)

        # First receive succeeds
        data = android_client.receive_notification(encrypted)
        assert data.ip == "1.2.3.4"

        # Replay fails
        with pytest.raises(ValueError, match="[Rr]eplay"):
            android_client.receive_notification(encrypted)

    @pytest.mark.asyncio
    async def test_expired_message_rejected(self):
        """Expired messages are rejected."""
        master_secret = generate_secret()
        ntfy_key = derive_key(master_secret, "ntfy")

        crypto = NtfyCrypto(ntfy_key)
        nonce = os.urandom(16)

        # Create message with old timestamp
        old_timestamp = int(time.time()) - 600  # 10 minutes ago
        encrypted = crypto.encrypt_ip_notification(
            ip="1.2.3.4",
            port=8821,
            timestamp=old_timestamp,
            nonce=nonce,
        )

        android_client = MockAndroidClient(master_secret)
        with pytest.raises(ValueError, match="expired"):
            android_client.receive_notification(encrypted)

    @pytest.mark.asyncio
    async def test_future_message_rejected(self):
        """Messages with future timestamps are rejected."""
        master_secret = generate_secret()
        ntfy_key = derive_key(master_secret, "ntfy")

        crypto = NtfyCrypto(ntfy_key)
        nonce = os.urandom(16)

        # Create message with future timestamp
        future_timestamp = int(time.time()) + 600  # 10 minutes in future
        encrypted = crypto.encrypt_ip_notification(
            ip="1.2.3.4",
            port=8821,
            timestamp=future_timestamp,
            nonce=nonce,
        )

        android_client = MockAndroidClient(master_secret)
        with pytest.raises(ValueError, match="expired"):
            android_client.receive_notification(encrypted)

    @pytest.mark.asyncio
    async def test_tampered_message_rejected(self):
        """Tampered messages are rejected (GCM authentication)."""
        master_secret = generate_secret()
        ntfy_key = derive_key(master_secret, "ntfy")

        crypto = NtfyCrypto(ntfy_key)
        nonce = os.urandom(16)
        encrypted = crypto.encrypt_ip_notification(
            ip="1.2.3.4",
            port=8821,
            timestamp=int(time.time()),
            nonce=nonce,
        )

        # Tamper with the ciphertext
        data = bytearray(base64.b64decode(encrypted))
        data[20] ^= 0xFF  # Flip a bit
        tampered = base64.b64encode(bytes(data)).decode()

        android_client = MockAndroidClient(master_secret)
        with pytest.raises(InvalidTag):
            android_client.receive_notification(tampered)

    @pytest.mark.asyncio
    async def test_each_notification_has_unique_nonce(self):
        """Each notification has a unique nonce."""
        master_secret = generate_secret()
        ntfy_key = derive_key(master_secret, "ntfy")
        topic = derive_ntfy_topic(master_secret)

        published_nonces = []

        mock_response = AsyncMock()
        mock_response.status = 200
        mock_response.__aenter__.return_value = mock_response
        mock_response.__aexit__.return_value = None

        def post_side_effect(url, **kwargs):
            # Decrypt to get nonce
            crypto = NtfyCrypto(ntfy_key)
            data = crypto.decrypt_ip_notification(kwargs["data"])
            published_nonces.append(data.nonce)
            return mock_response

        session = AsyncMock(spec=aiohttp.ClientSession)
        session.post.side_effect = post_side_effect

        crypto = NtfyCrypto(ntfy_key)
        publisher = NtfyPublisher(crypto=crypto, topic=topic, http_session=session)

        # Publish multiple times
        for _ in range(10):
            await publisher.publish_ip_change("1.2.3.4", 8821)

        # All nonces should be unique
        assert len(published_nonces) == 10
        assert len(set(published_nonces)) == 10

    @pytest.mark.asyncio
    async def test_each_notification_has_unique_iv(self):
        """Each notification has a unique IV (even for same content)."""
        master_secret = generate_secret()
        ntfy_key = derive_key(master_secret, "ntfy")
        topic = derive_ntfy_topic(master_secret)

        encrypted_messages = []

        mock_response = AsyncMock()
        mock_response.status = 200
        mock_response.__aenter__.return_value = mock_response
        mock_response.__aexit__.return_value = None

        def post_side_effect(url, **kwargs):
            encrypted_messages.append(kwargs["data"])
            return mock_response

        session = AsyncMock(spec=aiohttp.ClientSession)
        session.post.side_effect = post_side_effect

        crypto = NtfyCrypto(ntfy_key)
        publisher = NtfyPublisher(crypto=crypto, topic=topic, http_session=session)

        # Publish same content multiple times
        for _ in range(10):
            await publisher.publish_ip_change("1.2.3.4", 8821)

        # Extract IVs (first 12 bytes of each encrypted message)
        ivs = []
        for msg in encrypted_messages:
            decoded = base64.b64decode(msg)
            ivs.append(decoded[:12])

        # All IVs should be unique
        assert len(ivs) == 10
        assert len(set(ivs)) == 10


# =============================================================================
# IPv6 E2E TESTS
# =============================================================================


class TestE2EIPv6:
    """Test IPv6 support through the E2E flow."""

    @pytest.mark.asyncio
    async def test_ipv6_address_full_flow(self):
        """IPv6 address works through the full flow."""
        master_secret = generate_secret()
        ntfy_key = derive_key(master_secret, "ntfy")
        topic = derive_ntfy_topic(master_secret)

        ipv6 = "2001:db8:85a3::8a2e:370:7334"

        mock_server = MockNtfyServer()
        session, _ = create_mock_session(mock_server)

        crypto = NtfyCrypto(ntfy_key)
        publisher = NtfyPublisher(crypto=crypto, topic=topic, http_session=session)

        await publisher.publish_ip_change(ipv6, 8821)

        messages = mock_server.get_messages(topic)
        android_client = MockAndroidClient(master_secret)
        data = android_client.receive_notification(messages[0])

        assert data.ip == ipv6
        assert data.port == 8821

    @pytest.mark.asyncio
    async def test_ipv6_loopback(self):
        """IPv6 loopback address works."""
        master_secret = generate_secret()
        ntfy_key = derive_key(master_secret, "ntfy")

        crypto = NtfyCrypto(ntfy_key)
        nonce = os.urandom(16)

        encrypted = crypto.encrypt_ip_notification(
            ip="::1",
            port=8821,
            timestamp=int(time.time()),
            nonce=nonce,
        )

        android_client = MockAndroidClient(master_secret)
        data = android_client.receive_notification(encrypted)
        assert data.ip == "::1"

    @pytest.mark.asyncio
    async def test_ipv6_compressed(self):
        """Compressed IPv6 address works."""
        master_secret = generate_secret()
        ntfy_key = derive_key(master_secret, "ntfy")

        crypto = NtfyCrypto(ntfy_key)
        nonce = os.urandom(16)

        encrypted = crypto.encrypt_ip_notification(
            ip="fe80::1",
            port=443,
            timestamp=int(time.time()),
            nonce=nonce,
        )

        android_client = MockAndroidClient(master_secret)
        data = android_client.receive_notification(encrypted)
        assert data.ip == "fe80::1"


# =============================================================================
# CONCURRENCY E2E TESTS
# =============================================================================


class TestE2EConcurrency:
    """Test concurrent operations."""

    @pytest.mark.asyncio
    async def test_concurrent_ip_monitors(self):
        """Multiple IP monitors can run concurrently."""
        callbacks_called = {"monitor1": 0, "monitor2": 0}

        stun1 = AsyncMock()
        stun1.get_public_ip.side_effect = [
            ("1.1.1.1", 8821),
            ("2.2.2.2", 8821),
        ]

        stun2 = AsyncMock()
        stun2.get_public_ip.side_effect = [
            ("3.3.3.3", 8821),
            ("4.4.4.4", 8821),
        ]

        async def callback1(ip, port):
            callbacks_called["monitor1"] += 1

        async def callback2(ip, port):
            callbacks_called["monitor2"] += 1

        monitor1 = IpMonitor(stun1, check_interval=0.01, on_ip_change=callback1)
        monitor2 = IpMonitor(stun2, check_interval=0.01, on_ip_change=callback2)

        await asyncio.gather(monitor1.start(), monitor2.start())
        await asyncio.sleep(0.05)
        await asyncio.gather(monitor1.stop(), monitor2.stop())

        assert callbacks_called["monitor1"] == 1
        assert callbacks_called["monitor2"] == 1

    @pytest.mark.asyncio
    async def test_concurrent_publishes(self):
        """Concurrent publishes don't interfere."""
        master_secret = generate_secret()
        ntfy_key = derive_key(master_secret, "ntfy")
        topic = derive_ntfy_topic(master_secret)

        published_ips = []

        mock_response = AsyncMock()
        mock_response.status = 200
        mock_response.__aenter__.return_value = mock_response
        mock_response.__aexit__.return_value = None

        def post_side_effect(url, **kwargs):
            # Capture the data synchronously
            crypto = NtfyCrypto(ntfy_key)
            data = crypto.decrypt_ip_notification(kwargs["data"])
            published_ips.append(data.ip)
            return mock_response

        session = AsyncMock(spec=aiohttp.ClientSession)
        session.post.side_effect = post_side_effect

        crypto = NtfyCrypto(ntfy_key)
        publisher = NtfyPublisher(crypto=crypto, topic=topic, http_session=session)

        # Publish concurrently
        await asyncio.gather(
            publisher.publish_ip_change("1.1.1.1", 8821),
            publisher.publish_ip_change("2.2.2.2", 8821),
            publisher.publish_ip_change("3.3.3.3", 8821),
        )

        # All should be published
        assert len(published_ips) == 3
        assert set(published_ips) == {"1.1.1.1", "2.2.2.2", "3.3.3.3"}


# =============================================================================
# LIFECYCLE E2E TESTS
# =============================================================================


class TestE2ELifecycle:
    """Test component lifecycle management."""

    @pytest.mark.asyncio
    async def test_monitor_start_stop_restart(self):
        """Monitor can be started, stopped, and restarted."""
        stun = AsyncMock()
        stun.get_public_ip.return_value = ("1.2.3.4", 8821)

        monitor = IpMonitor(stun, check_interval=100)

        # Start
        await monitor.start()
        assert monitor.is_running is True

        # Stop
        await monitor.stop()
        assert monitor.is_running is False

        # Restart
        await monitor.start()
        assert monitor.is_running is True
        assert monitor.current_ip == "1.2.3.4"

        await monitor.stop()

    @pytest.mark.asyncio
    async def test_publisher_context_manager(self):
        """Publisher properly manages session lifecycle."""
        master_secret = generate_secret()
        ntfy_key = derive_key(master_secret, "ntfy")
        topic = derive_ntfy_topic(master_secret)

        crypto = NtfyCrypto(ntfy_key)

        # Test without context manager - session is None
        publisher = NtfyPublisher(crypto=crypto, topic=topic)
        assert publisher._session is None

        # Test with context manager
        async with NtfyPublisher(crypto=crypto, topic=topic) as publisher:
            assert publisher._session is not None

        # After exit, session should be closed
        assert publisher._session is None

    @pytest.mark.asyncio
    async def test_callback_can_be_changed(self):
        """Callback can be changed after monitor starts."""
        stun = AsyncMock()
        stun.get_public_ip.side_effect = [
            ("1.1.1.1", 8821),  # Initial
            ("2.2.2.2", 8821),  # Change 1
            ("3.3.3.3", 8821),  # Change 2
        ]

        callback1_calls = []
        callback2_calls = []

        async def callback1(ip, port):
            callback1_calls.append(ip)

        async def callback2(ip, port):
            callback2_calls.append(ip)

        monitor = IpMonitor(stun, check_interval=0.02, on_ip_change=callback1)
        await monitor.start()

        # Wait for first change
        await asyncio.sleep(0.03)

        # Change callback
        monitor.set_callback(callback2)

        # Wait for second change
        await asyncio.sleep(0.03)
        await monitor.stop()

        assert callback1_calls == ["2.2.2.2"]
        assert callback2_calls == ["3.3.3.3"]

    @pytest.mark.asyncio
    async def test_stop_without_start_is_safe(self):
        """Stopping a monitor that was never started doesn't raise."""
        stun = AsyncMock()
        monitor = IpMonitor(stun, check_interval=100)

        # Should not raise
        await monitor.stop()

    @pytest.mark.asyncio
    async def test_double_start_is_idempotent(self):
        """Starting a monitor twice doesn't cause issues."""
        stun = AsyncMock()
        stun.get_public_ip.return_value = ("1.2.3.4", 8821)

        monitor = IpMonitor(stun, check_interval=100)

        await monitor.start()
        await monitor.start()  # Second start should be no-op

        # Should have only called get_public_ip once
        assert stun.get_public_ip.call_count == 1

        await monitor.stop()


# =============================================================================
# EDGE CASE TESTS
# =============================================================================


class TestE2EEdgeCases:
    """Test edge cases and boundary conditions."""

    @pytest.mark.asyncio
    async def test_empty_ip_address(self):
        """Empty IP address is handled (though unusual)."""
        master_secret = generate_secret()
        ntfy_key = derive_key(master_secret, "ntfy")

        crypto = NtfyCrypto(ntfy_key)
        nonce = os.urandom(16)

        encrypted = crypto.encrypt_ip_notification(
            ip="",
            port=8821,
            timestamp=int(time.time()),
            nonce=nonce,
        )

        android_client = MockAndroidClient(master_secret)
        data = android_client.receive_notification(encrypted)
        assert data.ip == ""

    @pytest.mark.asyncio
    async def test_max_port_number(self):
        """Maximum port number (65535) is handled."""
        master_secret = generate_secret()
        ntfy_key = derive_key(master_secret, "ntfy")

        crypto = NtfyCrypto(ntfy_key)
        nonce = os.urandom(16)

        encrypted = crypto.encrypt_ip_notification(
            ip="1.2.3.4",
            port=65535,
            timestamp=int(time.time()),
            nonce=nonce,
        )

        android_client = MockAndroidClient(master_secret)
        data = android_client.receive_notification(encrypted)
        assert data.port == 65535

    @pytest.mark.asyncio
    async def test_zero_port(self):
        """Zero port is handled."""
        master_secret = generate_secret()
        ntfy_key = derive_key(master_secret, "ntfy")

        crypto = NtfyCrypto(ntfy_key)
        nonce = os.urandom(16)

        encrypted = crypto.encrypt_ip_notification(
            ip="1.2.3.4",
            port=0,
            timestamp=int(time.time()),
            nonce=nonce,
        )

        android_client = MockAndroidClient(master_secret)
        data = android_client.receive_notification(encrypted)
        assert data.port == 0

    @pytest.mark.asyncio
    async def test_rapid_ip_changes(self):
        """Rapid IP changes are all detected and published."""
        master_secret = generate_secret()
        ntfy_key = derive_key(master_secret, "ntfy")
        topic = derive_ntfy_topic(master_secret)

        # Create 20 different IPs
        ips = [(f"10.0.0.{i}", 8821) for i in range(1, 21)]
        initial_ip = ("10.0.0.0", 8821)

        stun_client = AsyncMock()
        stun_client.get_public_ip.side_effect = [initial_ip] + ips

        mock_server = MockNtfyServer()
        session, _ = create_mock_session(mock_server)

        crypto = NtfyCrypto(ntfy_key)
        publisher = NtfyPublisher(crypto=crypto, topic=topic, http_session=session)
        publisher.RETRY_DELAYS = [0.001, 0.001, 0.001]

        published_count = 0

        async def on_ip_change(ip: str, port: int) -> None:
            nonlocal published_count
            await publisher.publish_ip_change(ip, port)
            published_count += 1

        monitor = IpMonitor(
            stun_client=stun_client,
            check_interval=0.005,  # Very fast
            on_ip_change=on_ip_change,
        )

        await monitor.start()
        await asyncio.sleep(0.3)  # Wait for many cycles
        await monitor.stop()

        # Should have published many changes
        assert published_count >= 15  # Allow some timing slack

    @pytest.mark.asyncio
    async def test_very_long_ip_address(self):
        """Very long IP address (like verbose IPv6) is handled."""
        master_secret = generate_secret()
        ntfy_key = derive_key(master_secret, "ntfy")

        # Full expanded IPv6
        long_ipv6 = "2001:0db8:0000:0000:0000:0000:0000:0001"

        crypto = NtfyCrypto(ntfy_key)
        nonce = os.urandom(16)

        encrypted = crypto.encrypt_ip_notification(
            ip=long_ipv6,
            port=8821,
            timestamp=int(time.time()),
            nonce=nonce,
        )

        android_client = MockAndroidClient(master_secret)
        data = android_client.receive_notification(encrypted)
        assert data.ip == long_ipv6

    @pytest.mark.asyncio
    async def test_message_at_timestamp_boundary(self):
        """Message at exact max_age boundary is handled."""
        master_secret = generate_secret()
        ntfy_key = derive_key(master_secret, "ntfy")

        crypto = NtfyCrypto(ntfy_key)
        nonce = os.urandom(16)

        # Create message at exactly max_age seconds ago
        android_client = MockAndroidClient(master_secret)
        boundary_timestamp = int(time.time()) - android_client._max_age

        encrypted = crypto.encrypt_ip_notification(
            ip="1.2.3.4",
            port=8821,
            timestamp=boundary_timestamp,
            nonce=nonce,
        )

        # Should still be valid at exact boundary
        data = android_client.receive_notification(encrypted)
        assert data.ip == "1.2.3.4"
