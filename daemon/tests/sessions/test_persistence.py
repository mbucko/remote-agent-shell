"""Tests for session persistence module."""

import json
from pathlib import Path

import pytest

from ras.sessions.persistence import PersistedSession, SessionPersistence


class TestSessionPersistence:
    """Tests for SessionPersistence class."""

    @pytest.fixture
    def persistence(self, tmp_path: Path) -> SessionPersistence:
        """Create persistence instance with temp file."""
        return SessionPersistence(path=tmp_path / "sessions.json")

    @pytest.fixture
    def sample_session(self) -> PersistedSession:
        """Create a sample session."""
        return PersistedSession(
            id="abc123DEF456",
            tmux_name="ras-claude-myproject",
            display_name="claude-myproject",
            directory="/home/user/myproject",
            agent="claude",
            created_at=1700000000,
            last_activity_at=1700001000,
            status=1,  # ACTIVE
        )


class TestLoad:
    """Tests for load method."""

    @pytest.mark.asyncio
    async def test_returns_empty_list_if_file_missing(self, tmp_path: Path):
        """Returns empty list if file doesn't exist."""
        persistence = SessionPersistence(path=tmp_path / "nonexistent.json")
        result = await persistence.load()
        assert result == []

    @pytest.mark.asyncio
    async def test_loads_sessions_from_file(self, tmp_path: Path):
        """Loads sessions from JSON file."""
        path = tmp_path / "sessions.json"
        data = {
            "sessions": [
                {
                    "id": "abc123DEF456",
                    "tmux_name": "ras-claude-test",
                    "display_name": "claude-test",
                    "directory": "/home/user/test",
                    "agent": "claude",
                    "created_at": 1700000000,
                    "last_activity_at": 1700001000,
                    "status": 1,
                }
            ],
            "recent_directories": ["/home/user/recent"],
        }
        path.write_text(json.dumps(data))

        persistence = SessionPersistence(path=path)
        result = await persistence.load()

        assert len(result) == 1
        assert result[0].id == "abc123DEF456"
        assert result[0].tmux_name == "ras-claude-test"

    @pytest.mark.asyncio
    async def test_loads_recent_directories(self, tmp_path: Path):
        """Loads recent directories from file."""
        path = tmp_path / "sessions.json"
        data = {
            "sessions": [],
            "recent_directories": ["/path1", "/path2"],
        }
        path.write_text(json.dumps(data))

        persistence = SessionPersistence(path=path)
        await persistence.load()

        assert persistence.get_recent_directories() == ["/path1", "/path2"]

    @pytest.mark.asyncio
    async def test_skips_invalid_entries(self, tmp_path: Path):
        """Skips invalid session entries."""
        path = tmp_path / "sessions.json"
        data = {
            "sessions": [
                {"id": "valid12chars", "tmux_name": "ras-test"},  # Missing fields
                {
                    "id": "abc123DEF456",
                    "tmux_name": "ras-claude-test",
                    "display_name": "claude-test",
                    "directory": "/home/user/test",
                    "agent": "claude",
                    "created_at": 1700000000,
                    "last_activity_at": 1700001000,
                    "status": 1,
                },
            ],
        }
        path.write_text(json.dumps(data))

        persistence = SessionPersistence(path=path)
        result = await persistence.load()

        assert len(result) == 1
        assert result[0].id == "abc123DEF456"

    @pytest.mark.asyncio
    async def test_handles_invalid_json(self, tmp_path: Path):
        """Handles invalid JSON gracefully."""
        path = tmp_path / "sessions.json"
        path.write_text("not valid json {{{")

        persistence = SessionPersistence(path=path)
        result = await persistence.load()

        assert result == []


class TestSave:
    """Tests for save method."""

    @pytest.mark.asyncio
    async def test_saves_sessions_to_file(self, tmp_path: Path):
        """Saves sessions to JSON file."""
        path = tmp_path / "sessions.json"
        persistence = SessionPersistence(path=path)

        session = PersistedSession(
            id="abc123DEF456",
            tmux_name="ras-claude-test",
            display_name="claude-test",
            directory="/home/user/test",
            agent="claude",
            created_at=1700000000,
            last_activity_at=1700001000,
            status=1,
        )

        await persistence.save([session])

        data = json.loads(path.read_text())
        assert len(data["sessions"]) == 1
        assert data["sessions"][0]["id"] == "abc123DEF456"

    @pytest.mark.asyncio
    async def test_creates_parent_directory(self, tmp_path: Path):
        """Creates parent directory if it doesn't exist."""
        path = tmp_path / "subdir" / "sessions.json"
        persistence = SessionPersistence(path=path)

        await persistence.save([])

        assert path.exists()

    @pytest.mark.asyncio
    async def test_saves_recent_directories(self, tmp_path: Path):
        """Saves recent directories."""
        path = tmp_path / "sessions.json"
        persistence = SessionPersistence(path=path)

        persistence.add_recent_directory("/path1")
        persistence.add_recent_directory("/path2")
        await persistence.save([])

        data = json.loads(path.read_text())
        assert data["recent_directories"] == ["/path2", "/path1"]


class TestRecentDirectories:
    """Tests for recent directory tracking."""

    def test_add_to_front(self, tmp_path: Path):
        """New directories are added to the front."""
        persistence = SessionPersistence(path=tmp_path / "sessions.json")

        persistence.add_recent_directory("/path1")
        persistence.add_recent_directory("/path2")

        assert persistence.get_recent_directories() == ["/path2", "/path1"]

    def test_moves_existing_to_front(self, tmp_path: Path):
        """Re-adding existing directory moves it to front."""
        persistence = SessionPersistence(path=tmp_path / "sessions.json")

        persistence.add_recent_directory("/path1")
        persistence.add_recent_directory("/path2")
        persistence.add_recent_directory("/path1")

        assert persistence.get_recent_directories() == ["/path1", "/path2"]

    def test_respects_max_limit(self, tmp_path: Path):
        """Respects max recent directories limit."""
        persistence = SessionPersistence(
            path=tmp_path / "sessions.json", max_recent_dirs=3
        )

        for i in range(5):
            persistence.add_recent_directory(f"/path{i}")

        recent = persistence.get_recent_directories()
        assert len(recent) == 3
        assert recent == ["/path4", "/path3", "/path2"]

    def test_get_returns_copy(self, tmp_path: Path):
        """get_recent_directories returns a copy."""
        persistence = SessionPersistence(path=tmp_path / "sessions.json")

        persistence.add_recent_directory("/path1")
        recent = persistence.get_recent_directories()
        recent.append("/modified")

        assert persistence.get_recent_directories() == ["/path1"]
