"""Tests for agent detection module."""

import pytest

from ras.sessions.agents import AgentDetector, AgentInfo


class TestAgentDetector:
    """Tests for AgentDetector class."""

    @pytest.fixture
    def detector_with_mocked_which(self) -> AgentDetector:
        """Create detector with mocked which function."""

        def mock_which(binary: str) -> str | None:
            if binary == "claude":
                return "/usr/local/bin/claude"
            if binary == "aider":
                return "/usr/bin/aider"
            return None

        return AgentDetector(which_func=mock_which)


class TestInitialize:
    """Tests for initialize method."""

    @pytest.mark.asyncio
    async def test_scans_builtin_agents(self):
        """Scans for builtin agents."""

        def mock_which(binary: str) -> str | None:
            return f"/usr/bin/{binary}" if binary == "claude" else None

        detector = AgentDetector(which_func=mock_which)
        await detector.initialize()

        agent = detector.get_agent("claude")
        assert agent is not None
        assert agent.available is True
        assert agent.path == "/usr/bin/claude"

    @pytest.mark.asyncio
    async def test_marks_unavailable_agents(self):
        """Marks agents that aren't found as unavailable."""
        detector = AgentDetector(which_func=lambda _: None)
        await detector.initialize()

        agent = detector.get_agent("claude")
        assert agent is not None
        assert agent.available is False
        assert agent.path == ""

    @pytest.mark.asyncio
    async def test_uses_config_for_builtin_list(self):
        """Uses config to determine which agents to scan."""
        detector = AgentDetector(
            config={"builtin": ["myagent"]}, which_func=lambda b: f"/usr/bin/{b}"
        )
        await detector.initialize()

        # Should have myagent but not claude
        assert detector.get_agent("myagent") is not None
        assert detector.get_agent("claude") is None

    @pytest.mark.asyncio
    async def test_uses_config_for_display_names(self):
        """Uses config for display names."""
        detector = AgentDetector(
            config={
                "builtin": ["myagent"],
                "names": {"myagent": "My Custom Agent"},
            },
            which_func=lambda _: "/usr/bin/agent",
        )
        await detector.initialize()

        agent = detector.get_agent("myagent")
        assert agent.name == "My Custom Agent"


class TestRefresh:
    """Tests for refresh method."""

    @pytest.mark.asyncio
    async def test_rescans_agents(self):
        """Refresh re-scans for agents."""
        call_count = 0

        def mock_which(binary: str) -> str | None:
            nonlocal call_count
            call_count += 1
            return "/usr/bin/claude" if binary == "claude" else None

        detector = AgentDetector(
            config={"builtin": ["claude"]}, which_func=mock_which
        )
        await detector.initialize()
        await detector.refresh()

        # Should have been called twice (once for init, once for refresh)
        assert call_count == 2


class TestGetAll:
    """Tests for get_all method."""

    @pytest.mark.asyncio
    async def test_returns_all_agents(self):
        """Returns all agents including unavailable ones."""

        def mock_which(binary: str) -> str | None:
            return "/usr/bin/claude" if binary == "claude" else None

        detector = AgentDetector(
            config={"builtin": ["claude", "aider"]}, which_func=mock_which
        )
        await detector.initialize()

        result = await detector.get_all()

        assert len(result.agents) == 2
        binaries = {a.binary for a in result.agents}
        assert "claude" in binaries
        assert "aider" in binaries


class TestGetAvailable:
    """Tests for get_available method."""

    @pytest.mark.asyncio
    async def test_returns_only_available(self):
        """Returns only available agents."""

        def mock_which(binary: str) -> str | None:
            return "/usr/bin/claude" if binary == "claude" else None

        detector = AgentDetector(
            config={"builtin": ["claude", "aider"]}, which_func=mock_which
        )
        await detector.initialize()

        available = await detector.get_available()

        assert "claude" in available
        assert "aider" not in available
        assert available["claude"].available is True


class TestGetAgent:
    """Tests for get_agent method."""

    @pytest.mark.asyncio
    async def test_returns_agent_info(self):
        """Returns agent info by binary name."""
        detector = AgentDetector(
            config={"builtin": ["claude"]},
            which_func=lambda _: "/usr/bin/claude",
        )
        await detector.initialize()

        agent = detector.get_agent("claude")

        assert agent is not None
        assert isinstance(agent, AgentInfo)
        assert agent.binary == "claude"

    @pytest.mark.asyncio
    async def test_returns_none_for_unknown(self):
        """Returns None for unknown agent."""
        detector = AgentDetector(
            config={"builtin": ["claude"]},
            which_func=lambda _: None,
        )
        await detector.initialize()

        assert detector.get_agent("unknown") is None
