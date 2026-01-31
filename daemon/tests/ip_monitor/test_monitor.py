"""Tests for IP monitor."""

import asyncio
from contextlib import asynccontextmanager
from unittest.mock import AsyncMock, patch

import pytest

from ras.ip_monitor.monitor import IpMonitor


def make_stun_with_repeat(ip_sequence):
    """Create a stun mock that returns values from sequence, repeating last value."""
    call_idx = [0]

    async def get_ip():
        idx = min(call_idx[0], len(ip_sequence) - 1)
        result = ip_sequence[idx]
        call_idx[0] += 1
        if isinstance(result, Exception):
            raise result
        return result

    stun = AsyncMock()
    stun.get_public_ip.side_effect = get_ip
    return stun


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
        # Wait for background task to complete
        pending = asyncio.all_tasks() - {asyncio.current_task()}
        if pending:
            await asyncio.gather(*pending, return_exceptions=True)


class TestIpMonitorInit:
    """Tests for IpMonitor initialization."""

    def test_initializes_with_defaults(self):
        """Monitor initializes with default values."""
        stun = AsyncMock()
        monitor = IpMonitor(stun_client=stun)

        assert monitor.current_ip is None
        assert monitor.current_port is None
        assert monitor.is_running is False
        assert monitor._interval == 30.0

    def test_accepts_custom_interval(self):
        """Monitor accepts custom check interval."""
        stun = AsyncMock()
        monitor = IpMonitor(stun_client=stun, check_interval=60.0)

        assert monitor._interval == 60.0

    def test_accepts_callback(self):
        """Monitor accepts IP change callback."""
        stun = AsyncMock()
        callback = AsyncMock()
        monitor = IpMonitor(stun_client=stun, on_ip_change=callback)

        assert monitor._on_ip_change is callback


class TestIpMonitorStart:
    """Tests for starting the monitor."""

    @pytest.mark.asyncio
    async def test_gets_initial_ip(self):
        """Start gets initial IP from STUN."""
        stun = AsyncMock()
        stun.get_public_ip.return_value = ("1.2.3.4", 8821)

        monitor = IpMonitor(stun_client=stun, check_interval=100)
        await monitor.start()

        assert monitor.current_ip == "1.2.3.4"
        assert monitor.current_port == 8821
        assert monitor.is_running is True

        await monitor.stop()

    @pytest.mark.asyncio
    async def test_start_is_idempotent(self):
        """Multiple starts don't cause issues."""
        stun = AsyncMock()
        stun.get_public_ip.return_value = ("1.2.3.4", 8821)

        monitor = IpMonitor(stun_client=stun, check_interval=100)
        await monitor.start()
        await monitor.start()  # Second start should be no-op

        assert stun.get_public_ip.call_count == 1

        await monitor.stop()


class TestIpMonitorStop:
    """Tests for stopping the monitor."""

    @pytest.mark.asyncio
    async def test_stop_sets_running_false(self):
        """Stop sets running to False."""
        stun = AsyncMock()
        stun.get_public_ip.return_value = ("1.2.3.4", 8821)

        monitor = IpMonitor(stun_client=stun, check_interval=100)
        await monitor.start()
        await monitor.stop()

        assert monitor.is_running is False

    @pytest.mark.asyncio
    async def test_stop_without_start_is_safe(self):
        """Stop without start doesn't raise."""
        stun = AsyncMock()
        monitor = IpMonitor(stun_client=stun)

        await monitor.stop()  # Should not raise


class TestIpMonitorDetection:
    """Tests for IP change detection."""

    @pytest.mark.asyncio
    async def test_detects_ip_change(self):
        """Detects when IP changes."""
        stun = make_stun_with_repeat([("1.2.3.4", 8821), ("5.6.7.8", 8821)])

        callback = AsyncMock()
        monitor = IpMonitor(
            stun_client=stun,
            check_interval=0.01,
            on_ip_change=callback,
        )

        async with run_monitor_with_mocked_sleep(monitor, iterations=2):
            pass

        callback.assert_called_once_with("5.6.7.8", 8821)

    @pytest.mark.asyncio
    async def test_detects_port_change(self):
        """Detects when port changes."""
        stun = make_stun_with_repeat([("1.2.3.4", 8821), ("1.2.3.4", 9999)])

        callback = AsyncMock()
        monitor = IpMonitor(
            stun_client=stun,
            check_interval=0.01,
            on_ip_change=callback,
        )

        async with run_monitor_with_mocked_sleep(monitor, iterations=2):
            pass

        callback.assert_called_once_with("1.2.3.4", 9999)

    @pytest.mark.asyncio
    async def test_no_callback_on_same_ip(self):
        """No callback when IP stays the same."""
        stun = make_stun_with_repeat([
            ("1.2.3.4", 8821),  # Initial
            ("1.2.3.4", 8821),  # Same
            ("1.2.3.4", 8821),  # Same
        ])

        callback = AsyncMock()
        monitor = IpMonitor(
            stun_client=stun,
            check_interval=0.01,
            on_ip_change=callback,
        )

        async with run_monitor_with_mocked_sleep(monitor, iterations=3):
            pass

        callback.assert_not_called()

    @pytest.mark.asyncio
    async def test_handles_stun_failure(self):
        """Handles STUN query failure gracefully."""
        stun = make_stun_with_repeat([
            ("1.2.3.4", 8821),  # Initial
            Exception("STUN timeout"),  # Failure
            ("1.2.3.4", 8821),  # Recovery
        ])

        callback = AsyncMock()
        monitor = IpMonitor(
            stun_client=stun,
            check_interval=0.01,
            on_ip_change=callback,
        )

        async with run_monitor_with_mocked_sleep(monitor, iterations=3):
            pass

        # Should not crash, callback not called (no change)
        callback.assert_not_called()

    @pytest.mark.asyncio
    async def test_multiple_ip_changes(self):
        """Detects multiple IP changes."""
        stun = make_stun_with_repeat([
            ("1.2.3.4", 8821),  # Initial
            ("5.6.7.8", 8821),  # First change
            ("5.6.7.8", 8821),  # Same
            ("9.10.11.12", 8821),  # Second change
        ])

        callback = AsyncMock()
        monitor = IpMonitor(
            stun_client=stun,
            check_interval=0.01,
            on_ip_change=callback,
        )

        async with run_monitor_with_mocked_sleep(monitor, iterations=4):
            pass

        assert callback.call_count == 2
        callback.assert_any_call("5.6.7.8", 8821)
        callback.assert_any_call("9.10.11.12", 8821)


class TestIpMonitorCheckNow:
    """Tests for immediate IP check."""

    @pytest.mark.asyncio
    async def test_check_now_returns_true_on_change(self):
        """check_now returns True when IP changes."""
        stun = AsyncMock()
        stun.get_public_ip.side_effect = [
            ("1.2.3.4", 8821),  # Initial
            ("5.6.7.8", 8821),  # Changed
        ]

        monitor = IpMonitor(stun_client=stun)
        await monitor.start()
        await monitor.stop()

        result = await monitor.check_now()

        assert result is True
        assert monitor.current_ip == "5.6.7.8"

    @pytest.mark.asyncio
    async def test_check_now_returns_false_on_same(self):
        """check_now returns False when IP is same."""
        stun = AsyncMock()
        stun.get_public_ip.return_value = ("1.2.3.4", 8821)

        monitor = IpMonitor(stun_client=stun)
        await monitor.start()
        await monitor.stop()

        result = await monitor.check_now()

        assert result is False

    @pytest.mark.asyncio
    async def test_check_now_handles_failure(self):
        """check_now returns False on STUN failure."""
        stun = AsyncMock()
        stun.get_public_ip.side_effect = [
            ("1.2.3.4", 8821),  # Initial
            Exception("STUN timeout"),  # Failure
        ]

        monitor = IpMonitor(stun_client=stun)
        await monitor.start()
        await monitor.stop()

        result = await monitor.check_now()

        assert result is False
        assert monitor.current_ip == "1.2.3.4"  # Unchanged


class TestIpMonitorCallbackError:
    """Tests for callback error handling."""

    @pytest.mark.asyncio
    async def test_callback_error_doesnt_crash_monitor(self):
        """Callback error doesn't crash the monitor."""
        stun = AsyncMock()
        stun = make_stun_with_repeat([
            ("1.2.3.4", 8821),  # Initial
            ("5.6.7.8", 8821),  # Changed
            ("9.10.11.12", 8821),  # Changed again
        ])

        callback = AsyncMock(side_effect=[Exception("Callback failed"), None])
        monitor = IpMonitor(
            stun_client=stun,
            check_interval=0.01,
            on_ip_change=callback,
        )

        async with run_monitor_with_mocked_sleep(monitor, iterations=3):
            pass

        # Should have called callback twice despite first failure
        assert callback.call_count == 2


class TestIpMonitorSetCallback:
    """Tests for set_callback."""

    @pytest.mark.asyncio
    async def test_set_callback_changes_callback(self):
        """set_callback changes the callback."""
        stun = make_stun_with_repeat([
            ("1.2.3.4", 8821),  # Initial
            ("5.6.7.8", 8821),  # Changed
        ])

        callback1 = AsyncMock()
        callback2 = AsyncMock()

        monitor = IpMonitor(
            stun_client=stun,
            check_interval=0.01,
            on_ip_change=callback1,
        )

        monitor.set_callback(callback2)

        async with run_monitor_with_mocked_sleep(monitor, iterations=2):
            pass

        callback1.assert_not_called()
        callback2.assert_called_once()

    def test_set_callback_to_none(self):
        """set_callback can remove callback."""
        stun = AsyncMock()
        callback = AsyncMock()
        monitor = IpMonitor(stun_client=stun, on_ip_change=callback)

        monitor.set_callback(None)

        assert monitor._on_ip_change is None
